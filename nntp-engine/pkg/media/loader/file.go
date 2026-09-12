package loader

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/decode"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
	"streamnzb/pkg/usenet/pool"
)

type SegmentFetcher interface {
	FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error)
}

// SegmentFirstFetcher is optional: when implemented, the loader uses it for segment index 0
// to try all providers in parallel and reduce latency when the article is missing everywhere.
type SegmentFirstFetcher interface {
	FetchSegmentFirst(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error)
}

// SegmentStatter is optional: when implemented by the fetcher, CheckFirstSegmentExists uses
// STAT to verify the first segment exists before opening a stream
type SegmentStatter interface {
	StatSegment(ctx context.Context, messageID string, groups []string) (exists bool, err error)
}

// StatConcurrencyHinter is optional: when implemented, the fetcher decides how
// many STATs CheckFirstSegmentExists may have in flight. The fetcher owns the
// connections, so it is the only thing that can size this sensibly.
type StatConcurrencyHinter interface {
	StatConcurrency() int
}

// SegmentBatchFetcher is optional: when implemented, read-ahead can hand a run
// of segments to one connection that keeps several BODY commands outstanding,
// instead of one connection per segment. Results are aligned with the input
// slice; anything not returned OK stays the caller's to fetch the ordinary way.
type SegmentBatchFetcher interface {
	PipelineDepth() int
	FetchSegmentsPipelined(ctx context.Context, segments []*nzb.Segment, groups []string) []pool.PipelinedResult
}

// FetchConcurrencyHinter is optional: when implemented, the fetcher reports how
// many segment fetches it can keep on the wire at once. Read-ahead needs it to
// tell a spare connection from a contended one — see readAheadBatchSize.
type FetchConcurrencyHinter interface {
	FetchConcurrency() int
}

// defaultStatConcurrency applies when the fetcher offers no hint.
const defaultStatConcurrency = 4

func shouldPersistDownloadedSegment(ctx context.Context) bool {
	return ctx == nil || ctx.Err() == nil
}

func decodeAndCloseBody(body io.ReadCloser, decodeFn func(io.Reader) (*decode.Frame, error)) (*decode.Frame, error) {
	defer body.Close()
	return decodeFn(body)
}

// MaxZeroFills is how many DISTINCT segments of one file may be zero-filled
// before the file is declared unplayable. Counting distinct indices (rather
// than fetch attempts) is what makes the cap mean "this release has N holes":
// seeking back and forth across one damaged segment must not burn the budget.
const MaxZeroFills = 10

// MaxZeroFillRun is the longest run of consecutive missing segments that is
// still zero-filled. A single absent article is a smeared frame the player
// rides out; five in a row is several seconds of zeros inside the stream,
// which most demuxers do not survive. Past this the file is failed so the
// slot fails over instead of serving a crash. The cumulative cap above is
// unchanged: this only says that ten holes must not be one hole.
const MaxZeroFillRun = 4

// slowSegmentFetchThreshold flags segment downloads that took long enough to
// drain a player's buffer; one Warn per slow segment confirms mid-stream
// stalls in the field without needing Trace-level logs.
const slowSegmentFetchThreshold = 10 * time.Second

// isArticleNotFound reports whether err indicates the article is missing (430 No Such Article).
// Used to fail fast on the first segment instead of zero-filling through many segments.
func isArticleNotFound(err error) bool {
	return nntp.IsArticleNotFound(err)
}

func (f *File) IsFailed() bool {
	return f.runFailed.Load() || f.ZeroFilledSegments() >= MaxZeroFills
}

// zeroFilledRunLocked is the length of the maximal run of zero-filled
// segments containing index. Caller holds zeroFillMu.
func (f *File) zeroFilledRunLocked(index int) int {
	run := 1
	for i := index - 1; i >= 0; i-- {
		if _, ok := f.zeroFilled[i]; !ok {
			break
		}
		run++
	}
	for i := index + 1; i < len(f.segments); i++ {
		if _, ok := f.zeroFilled[i]; !ok {
			break
		}
		run++
	}
	return run
}

// ZeroFilledSegments reports how many distinct segments of this file have been
// zero-filled so far.
func (f *File) ZeroFilledSegments() int {
	f.zeroFillMu.Lock()
	defer f.zeroFillMu.Unlock()
	return len(f.zeroFilled)
}

// isZeroFilled reports whether index was already zero-filled, so repeat reads
// of a known hole return zeros straight away instead of re-fetching an article
// that all providers have already refused.
func (f *File) isZeroFilled(index int) bool {
	f.zeroFillMu.Lock()
	defer f.zeroFillMu.Unlock()
	_, ok := f.zeroFilled[index]
	return ok
}

// zeroSegment builds the filler for a segment index using the segment map as it
// stands now, so a hole discovered before size detection still reads back at the
// mapped length afterwards.
func (f *File) zeroSegment(index int) []byte {
	size := f.segmentDecodedLen(index)
	if size < 0 {
		size = 0
	}
	return make([]byte, size)
}

type Segment struct {
	nzb.Segment
	StartOffset int64
	EndOffset   int64
}

type File struct {
	nzbFile   *nzb.File
	fetcher   SegmentFetcher
	estimator *SegmentSizeEstimator
	segments  []*Segment
	totalSize int64
	detected  bool
	// playbackStreamBytes is the size of the whole playback stream this file
	// backs a slice of — the unpacked movie, not this volume. Zero outside
	// multi-volume playback. Guarded by mu.
	playbackStreamBytes int64
	ctx                 context.Context
	ownerID             string
	mu                  sync.Mutex

	// missingFromNZB counts articles the NZB itself cannot deliver: numbering
	// gaps (materialized as placeholders or not) and segments carrying no
	// message id. missingRunFromNZB is the longest contiguous run of those
	// among the materialized segments — an NZB gap is one span by nature, so
	// the count alone cannot say whether ten holes are ten glitches or one
	// unplayable block. Both immutable after NewFile.
	missingFromNZB    int
	missingRunFromNZB int

	downloadMu        sync.Mutex
	inflightDownloads map[int]*inflightSegmentDownload
	// abandonedReadAhead holds the read-ahead fetches left behind by readers
	// that have closed, waiting to learn whether the next reader wants them,
	// keyed so each window's own grace timer can find it. Guarded by downloadMu.
	abandonedReadAhead    map[uint64]abandonedReadAheadWindow
	abandonedReadAheadSeq uint64

	zeroFillMu sync.Mutex
	zeroFilled map[int]struct{}
	// runFailed is set once a run of zero-filled segments grows past
	// MaxZeroFillRun; IsFailed reports it alongside the cumulative cap.
	runFailed atomic.Bool

	segmentDetectMu sync.Mutex

	// What produced the segment map, kept so it can be persisted and replayed
	// on a later session instead of re-probing (see SegmentMapSnapshotJSON).
	mapProbes  map[int]int64
	mapKnown   map[int64]int64
	mapSkipGap bool
	mapYenc    yencGeometry

	// mapCorrections holds decoded lengths a read measured after the map
	// claimed something else, mapDistrusted marks a map thrown away for that
	// reason, and mapRemaps bounds how often one file may do it. All guarded
	// by mu; see distrustSegmentMap.
	mapCorrections map[int]int64
	mapDistrusted  bool
	mapRemaps      int

	// yencGeo accumulates the "=ybegin size=" / "=ypart begin=" geometry of
	// every article decoded before the segment map exists. The articles carry
	// the exact map the poster wrote; collecting it costs nothing on downloads
	// already happening. Guarded by yencGeoMu, not mu, so a fetch completion
	// never contends with map detection.
	yencGeoMu sync.Mutex
	yencGeo   yencGeometry

	firstStatMu      sync.Mutex
	firstStatChecked bool
	firstStatExists  bool
	firstStatErr     error

	// yencName is the "=ybegin name=" seen on the last decoded article of this
	// file. Every article of a file repeats it, so one fetch is enough.
	yencName atomic.Value
}

type inflightSegmentDownload struct {
	done          chan struct{}
	countFailures bool
	data          []byte
	err           error
	ctx           context.Context
	cancel        context.CancelFunc
	waiters       int
}

type zeroFillEligibleError struct {
	cause error
}

func (e *zeroFillEligibleError) Error() string { return e.cause.Error() }

func (e *zeroFillEligibleError) Unwrap() error { return e.cause }

func NewFile(ctx context.Context, f *nzb.File, estimator *SegmentSizeEstimator, fetcher SegmentFetcher) *File {
	nzbSegments, unmaterialized := normalizeNZBSegments(f.Subject, f.Segments)
	segments := make([]*Segment, len(nzbSegments))
	var offset int64
	// Unmaterialized gaps plus every segment without a message id — gap
	// placeholders and id-less originals alike — can never be fetched from any
	// provider; the count is what lets the pre-flight refuse a release that
	// would exhaust the zero-fill budget.
	missing := unmaterialized
	run, longestRun := 0, 0
	for i, s := range nzbSegments {
		if strings.TrimSpace(s.ID) == "" {
			missing++
			run++
			if run > longestRun {
				longestRun = run
			}
		} else {
			run = 0
		}
		segments[i] = &Segment{
			Segment:     s,
			StartOffset: offset,
			EndOffset:   offset + s.Bytes,
		}
		offset += s.Bytes
	}
	return &File{
		nzbFile:           f,
		fetcher:           fetcher,
		estimator:         estimator,
		segments:          segments,
		totalSize:         offset,
		missingFromNZB:    missing,
		missingRunFromNZB: longestRun,
		ctx:               ctx,
		inflightDownloads: make(map[int]*inflightSegmentDownload),
		zeroFilled:        make(map[int]struct{}),
	}
}

func (f *File) Name() string { return f.nzbFile.Subject }

// ReadFirstSegment returns the decoded bytes of the file's first article.
//
// Segment 0 always starts at offset 0, so this needs no segment map — unlike
// ReadAt and every Open* path, which must resolve an arbitrary offset and so
// probe the whole file first. For a release being identified by its opening
// bytes that difference is one article per file instead of hundreds.
func (f *File) ReadFirstSegment(ctx context.Context) ([]byte, error) {
	if len(f.segments) == 0 {
		return nil, io.EOF
	}
	return f.DownloadSegment(ctx, 0)
}

// YencFileName returns the filename the poster wrote into this file's yEnc
// headers, fetching the first segment when no article has been decoded yet.
// It is the cheapest real name available for a release whose NZB subjects are
// obfuscated: every article repeats the header, and the first segment is
// downloaded by archive scanning anyway. Returns "" when the articles carry no
// name; the fetch error is returned only when nothing could be read at all.
func (f *File) YencFileName(ctx context.Context) (string, error) {
	if name, ok := f.yencName.Load().(string); ok && name != "" {
		return name, nil
	}
	if len(f.segments) == 0 {
		return "", nil
	}
	if _, err := f.DownloadSegment(ctx, 0); err != nil {
		return "", err
	}
	name, _ := f.yencName.Load().(string)
	return name, nil
}

func (f *File) SetOwnerSessionID(sessionID string) {
	f.mu.Lock()
	f.ownerID = sessionID
	f.mu.Unlock()
}

func (f *File) OwnerSessionID() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.ownerID
}

func (f *File) Size() int64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.totalSize
}

func (f *File) Segments() []*Segment { return f.segments }

func (f *File) SegmentCount() int { return len(f.segments) }

// MissingFromNZB reports how many of this file's declared articles the NZB
// itself cannot deliver — numbering gaps and segments without a message id.
// Anything above MaxZeroFills can never stream, which
// playback.VerifyRequiredArchivesExist turns into a definitive pre-flight
// verdict instead of letting an incomplete post serve a truncated file.
func (f *File) MissingFromNZB() int { return f.missingFromNZB }

// MissingRunFromNZB is the longest contiguous run of articles the NZB itself
// cannot deliver. A run past MaxZeroFillRun would be zero-filled into the
// stream as one block and fail the file on the read that reaches it, so the
// pre-flight refuses it before the release is offered.
func (f *File) MissingRunFromNZB() int { return f.missingRunFromNZB }

// CheckFirstSegmentExists returns whether the required segments (start, middle, end) exist on the server via STAT.
// Used before opening a stream to fail fast when release segments are missing (430).
// statSampleSpread is how many evenly spaced STAT points a file of the given
// segment count gets: one per ~1000 segments (~700 MB at typical article
// sizes), floored at the historical nine and capped so the pre-flight stays
// bounded on very large single-file posts.
func statSampleSpread(segments int) int {
	const (
		base       = 9
		maxSamples = 32
	)
	n := segments / 1000
	if n < base {
		return base
	}
	if n > maxSamples {
		return maxSamples
	}
	return n
}

func (f *File) CheckFirstSegmentExists(ctx context.Context) (bool, error) {
	if len(f.segments) == 0 {
		return false, nil
	}
	f.firstStatMu.Lock()
	if f.firstStatChecked {
		exists, err := f.firstStatExists, f.firstStatErr
		f.firstStatMu.Unlock()
		return exists, err
	}
	f.firstStatMu.Unlock()

	statter, ok := f.fetcher.(SegmentStatter)
	if !ok {
		return f.recordFirstStat(true, nil)
	}

	// The head segments and the tail are always checked — they catch a
	// truncated post — and the rest of the sample is spread evenly, scaling
	// with the file so scattered damage on a large release is overwhelmingly
	// likely to surface here rather than mid-stream after Content-Length has
	// been promised. Nine fixed points let a post with holes in the low
	// percents through most of the time; the census stays cheap because STATs
	// are batched at the fetcher's concurrency.
	sampleIndices := map[int]bool{
		0:                   true,
		1:                   true,
		2:                   true,
		3:                   true,
		4:                   true,
		len(f.segments) - 1: true,
	}
	spread := statSampleSpread(len(f.segments))
	for i := 0; i < spread; i++ {
		denom := spread - 1
		if denom < 1 {
			denom = 1
		}
		sampleIndices[i*(len(f.segments)-1)/denom] = true
	}

	indices := make([]int, 0, len(sampleIndices))
	for idx := range sampleIndices {
		if idx < 0 || idx >= len(f.segments) {
			continue
		}
		indices = append(indices, idx)
	}
	// Ascending order so the head segments — the ones that catch a truncated
	// post — go out in the first batch.
	sort.Ints(indices)

	msgIDs := make([]string, 0, len(indices))
	for _, idx := range indices {
		msgID := strings.TrimSpace(f.segments[idx].ID)
		if msgID == "" {
			if idx == 0 {
				// The header article can never be fetched, so the release is
				// unplayable regardless of what the server holds.
				return f.recordFirstStat(false, nil)
			}
			// A hole the NZB itself declares (a numbering-gap placeholder or
			// an id-less segment) has nothing to STAT: the zero-fill policy
			// owns it at read time, and MissingFromNZB caps how many of these
			// a file may carry before the pre-flight refuses it outright.
			continue
		}
		msgIDs = append(msgIDs, msgID)
	}
	if len(msgIDs) == 0 {
		return f.recordFirstStat(false, nil)
	}

	// The sampled segments are independent of each other, so probe them
	// together rather than paying one round trip per segment. The fetcher caps
	// how many run at once, since every file being validated does this at the
	// same time.
	limit := defaultStatConcurrency
	if hinter, ok := f.fetcher.(StatConcurrencyHinter); ok {
		if hinted := hinter.StatConcurrency(); hinted > 0 {
			limit = hinted
		}
	}
	if limit > len(msgIDs) {
		limit = len(msgIDs)
	}

	statCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	var (
		mu       sync.Mutex
		missing  bool
		firstErr error
		wg       sync.WaitGroup
	)
	sem := make(chan struct{}, limit)
	for _, msgID := range msgIDs {
		wg.Add(1)
		sem <- struct{}{}
		go func(msgID string) {
			defer wg.Done()
			defer func() { <-sem }()

			exists, err := statter.StatSegment(statCtx, msgID, f.nzbFile.Groups)
			if err == nil && exists {
				return
			}
			mu.Lock()
			if err != nil && firstErr == nil {
				firstErr = err
			}
			if err == nil && !exists {
				missing = true
			}
			definitive := missing
			mu.Unlock()
			// One article confirmed missing on every provider settles it; stop
			// the siblings. Transient errors do not, so those are left to run:
			// another segment may still return a definitive answer.
			if definitive {
				cancel()
			}
		}(msgID)
	}
	wg.Wait()

	// A definitive miss outranks any error, including the cancellations this
	// function caused in the siblings it stopped. Without this the release
	// would be reported as inconclusive and never marked bad.
	if missing {
		return f.recordFirstStat(false, nil)
	}
	if firstErr != nil {
		return f.recordFirstStat(false, firstErr)
	}
	return f.recordFirstStat(true, nil)
}

// recordFirstStat caches the outcome so repeat checks on the same file are free.
func (f *File) recordFirstStat(exists bool, err error) (bool, error) {
	f.firstStatMu.Lock()
	f.firstStatChecked = true
	f.firstStatExists = exists
	f.firstStatErr = err
	f.firstStatMu.Unlock()
	return exists, err
}

// StatSegmentAt STATs the article of the segment at index via the fetcher's
// statter. Returns (true, nil) when it exists on at least one provider,
// (false, nil) when definitively missing everywhere (430 on all), and
// (false, err) when inconclusive (no statter available, transient error).
func (f *File) StatSegmentAt(ctx context.Context, index int) (bool, error) {
	if index < 0 || index >= len(f.segments) {
		return false, fmt.Errorf("segment index %d out of range (segments=%d)", index, len(f.segments))
	}
	statter, ok := f.fetcher.(SegmentStatter)
	if !ok {
		return false, errors.New("fetcher does not support STAT")
	}
	msgID := strings.TrimSpace(f.segments[index].ID)
	if msgID == "" {
		return false, nil // article without a message id cannot be fetched
	}
	return statter.StatSegment(ctx, msgID, f.nzbFile.Groups)
}

// StatConcurrency reports the fetcher's preferred aggregate STAT concurrency.
// Callers that validate several files should share this budget instead of
// letting every file independently consume it.
func (f *File) StatConcurrency() int {
	limit := defaultStatConcurrency
	if hinter, ok := f.fetcher.(StatConcurrencyHinter); ok {
		if hinted := hinter.StatConcurrency(); hinted > 0 {
			limit = hinted
		}
	}
	return limit
}

func (f *File) SegmentMapDetected() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.detected
}

func (f *File) EnsureSegmentMap() error {
	return f.EnsureSegmentMapCtx(f.ctx)
}

func (f *File) EnsureSegmentMapCtx(ctx context.Context) error {
	f.segmentDetectMu.Lock()
	defer f.segmentDetectMu.Unlock()

	f.mu.Lock()
	if f.detected {
		f.mu.Unlock()
		return nil
	}
	f.mu.Unlock()

	return f.detectSegmentSizeLocked(ctx)
}

// PrimeUniformSegmentMapFromEstimator builds a segment map without NNTP probes when
// every segment (including the last) shares the same NZB bytes and the estimator
// already knows the decoded size from an earlier volume in the same release.
func (f *File) PrimeUniformSegmentMapFromEstimator() bool {
	if f == nil || len(f.segments) == 0 || f.estimator == nil {
		return false
	}
	if !hasUniformNZBSegmentBytes(f.segments) {
		return false
	}
	// A read already caught an inherited size lying about this file; rebuilding
	// from the same shortcut would reinstate it.
	if len(f.segmentMapCorrections()) > 0 {
		return false
	}
	first := f.segments[0]
	decoded, ok := f.estimator.Get(first.Bytes)
	if !ok || decoded <= 0 {
		return false
	}

	knownByNZBBytes := map[int64]int64{first.Bytes: decoded}
	sizes := buildSegmentDecodedSizesFromProbes(f.segments, nil, knownByNZBBytes, true)

	f.mu.Lock()
	defer f.mu.Unlock()
	if f.detected {
		return true
	}
	f.totalSize = applySegmentDecodedSizes(f.segments, sizes)
	f.detected = true
	f.mapDistrusted = false
	f.recordSegmentMapInputsLocked(nil, knownByNZBBytes, true, yencGeometry{})
	logger.Trace("Primed uniform segment map from estimator",
		"name", f.Name(),
		"size", f.totalSize,
		"segments", len(f.segments),
		"decoded", decoded)
	return true
}

func (f *File) detectSegmentSizeLocked(ctx context.Context) error {
	if ctx == nil {
		ctx = f.ctx
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	f.mu.Lock()
	if f.detected {
		f.mu.Unlock()
		return nil
	}
	f.mu.Unlock()

	if f.PrimeUniformSegmentMapFromEstimator() {
		return nil
	}

	if len(f.segments) == 0 {
		f.mu.Lock()
		f.totalSize = 0
		f.detected = true
		f.mapDistrusted = false
		f.mu.Unlock()
		return nil
	}

	knownByNZBBytes := make(map[int64]int64)
	if f.estimator != nil {
		if decoded, ok := f.estimator.Get(f.segments[0].Bytes); ok {
			knownByNZBBytes[f.segments[0].Bytes] = decoded
			logger.Trace("Using estimated segment size", "name", f.Name(), "size", decoded)
		}
	}

	includeMiddle := shouldProbeMiddleSegment(ctx, f.segments)
	indices := segmentProbeIndices(f.segments, knownByNZBBytes, includeMiddle, IsSkipGapProbingEnabled(ctx))
	logSegmentProbePlan(ctx, f.Name(), f.segments, indices, knownByNZBBytes, includeMiddle)

	probedByIndex, err := f.probeSegmentIndicesParallel(ctx, indices)
	if err != nil {
		return err
	}

	// Lengths a refused read already measured are real probes of this file —
	// better evidence than anything the plan can infer, and free.
	for idx, decoded := range f.segmentMapCorrections() {
		if idx >= 0 && idx < len(f.segments) && decoded > 0 {
			probedByIndex[idx] = decoded
		}
	}

	// The probes just decoded articles, and each article names its own exact
	// offset and the file's exact size (=ypart/=ybegin). When that geometry
	// proves a uniform layout the map is exact from what is already in hand:
	// no class clustering, no ratio scaling, and no gap pass — which on the
	// non-skip path used to probe (download) every remaining segment.
	geo := f.yencGeometrySnapshot()
	exactSizes, exact := exactSizesFromYencGeometry(f.segments, probedByIndex, geo)

	if !exact && !IsSkipGapProbingEnabled(ctx) {
		if missing := segmentUnprobedIndices(len(f.segments), indices); len(missing) > 0 {
			logger.Debug("Segment map gap probe",
				"name", f.Name(),
				"indices", missing,
				"count", len(missing))
			gapProbed, gapErr := f.probeSegmentIndicesParallel(ctx, missing)
			if gapErr != nil {
				return gapErr
			}
			for idx, decoded := range gapProbed {
				probedByIndex[idx] = decoded
			}
		}
	}

	lastIdx := len(f.segments) - 1
	for idx, decoded := range probedByIndex {
		if idx < 0 || idx >= len(f.segments) {
			continue
		}
		logger.Debug("Detected segment size",
			"name", f.Name(),
			"index", idx,
			"size", decoded,
			"nzb_size", f.segments[idx].Bytes)
		// Teach the estimator every class this probe measured, so the next
		// volume of the same release can skip re-measuring it.
		//
		// This used to seed only from index 0, which on a non-uniform post is
		// almost never probed — the planner picks whichever segment first
		// represents each size class. An 87-volume release therefore logged
		// known_from_estimator=0 on every single volume and paid a full-class
		// probe for each, having already measured that exact class.
		//
		// The physical last segment is excluded for the same reason
		// buildSegmentDecodedSizesFromProbes excludes it from class matching:
		// it is remainder-sized, and letting it stand for the full-segment
		// class would paint a short size across whole files.
		if f.estimator != nil && decoded > 0 && !(idx == lastIdx && lastIdx > 0) {
			f.estimator.Set(f.segments[idx].Bytes, decoded)
		}
	}

	var sizes []int64
	if exact {
		sizes = exactSizes
		logger.Debug("Exact segment map from yEnc part headers",
			"name", f.Name(),
			"file_size", geo.fileSize,
			"offsets", len(geo.offsets),
			"segments", len(f.segments))
	} else {
		geo = yencGeometry{} // only geometry that produced the map is persisted
		sizes = buildSegmentDecodedSizesFromProbes(f.segments, probedByIndex, knownByNZBBytes, IsSkipGapProbingEnabled(ctx))
		if includeMiddle {
			mid := middleProbeIndex(len(f.segments))
			if midDecoded, ok := probedByIndex[mid]; ok {
				applyUniformMiddleCalibration(f.segments, sizes, mid, midDecoded)
			}
		}
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	if f.detected {
		return nil
	}
	f.totalSize = applySegmentDecodedSizes(f.segments, sizes)
	f.detected = true
	f.mapDistrusted = false
	f.recordSegmentMapInputsLocked(probedByIndex, knownByNZBBytes, IsSkipGapProbingEnabled(ctx), geo)
	nzbSum := sumNZBSegmentBytes(f.segments)
	logSegmentMapSizeCheck(f.Name(), f.segments, nzbSum, f.totalSize, probedByIndex)
	logger.Debug("Recalculated total decoded size",
		"name", f.Name(),
		"size", f.totalSize,
		"segments", len(f.segments),
		"probed", len(probedByIndex))
	return nil
}

func logSegmentMapSizeCheck(name string, segments []*Segment, nzbSum, decodedSum int64, probedByIndex map[int]int64) {
	attrs := []any{
		"name", name,
		"nzb_bytes_sum", nzbSum,
		"decoded_sum", decodedSum,
		"delta_decoded_minus_nzb", decodedSum - nzbSum,
		"segments", len(segments),
	}
	if len(segments) > 0 && segments[0].Bytes > 0 {
		if dec, ok := probedByIndex[0]; ok && dec > 0 {
			attrs = append(attrs, "seg0_decode_ratio", float64(dec)/float64(segments[0].Bytes))
		}
	}
	if n := len(segments); n > 0 {
		attrs = append(attrs, "last_nzb_bytes", segments[n-1].Bytes)
		if dec, ok := probedByIndex[n-1]; ok {
			attrs = append(attrs, "last_decoded", dec)
		}
	}
	logger.Debug("Segment map size check", attrs...)
}

// SegmentOffsetRange returns decoded byte offsets for a segment index after map detection.
func (f *File) SegmentOffsetRange(index int) (start, end int64, ok bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if index < 0 || index >= len(f.segments) {
		return 0, 0, false
	}
	s := f.segments[index]
	return s.StartOffset, s.EndOffset, true
}

// segmentDecodedLen returns the virtual decoded byte length for a segment index.
func (f *File) segmentDecodedLen(idx int) int64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	if idx < 0 || idx >= len(f.segments) {
		return 0
	}
	s := f.segments[idx]
	if f.detected {
		return s.EndOffset - s.StartOffset
	}
	return s.Bytes
}

// verifyMappedSegmentLength refuses a downloaded segment whose decoded length
// disagrees with the segment map. The map can be estimated — gap probing is
// skipped on archive reads, and unprobed segments inherit a class
// representative's size — and serving through a mismatch silently shifts every
// byte after it: the demuxer desyncs and the served range no longer matches
// the release. A loud error turns that silent corruption into a failover, and
// the Warn names the segment so the estimator can be fixed from the field.
// Before the map is detected, mapped lengths are still encoded sizes, so a
// mismatch there is expected and not checked.
//
// The refusal is also the one place holding the ground truth, so it does not
// just refuse: it throws the disproved map away (distrustSegmentMap) and lets
// the next EnsureSegmentMap rebuild one from real probes.
func (f *File) verifyMappedSegmentLength(index int, data []byte) error {
	f.mu.Lock()
	detected := f.detected
	distrusted := f.mapDistrusted
	f.mu.Unlock()
	if !detected {
		if distrusted {
			// Mapped lengths are encoded sizes again until the rebuild runs,
			// so passing here would serve exactly the shifted bytes the
			// refusal exists to prevent.
			return fmt.Errorf("segment map for %q was disproved and has not been rebuilt yet", f.Name())
		}
		return nil
	}
	mapped := f.segmentDecodedLen(index)
	if int64(len(data)) == mapped {
		return nil
	}
	logger.Warn("Segment decoded length disagrees with the segment map",
		"file", f.Name(), "index", index, "mapped", mapped, "decoded", len(data))
	f.distrustSegmentMap(index, int64(len(data)))
	return fmt.Errorf("segment %d decoded %d bytes but is mapped as %d: refusing to serve shifted bytes", index, len(data), mapped)
}

// maxSegmentMapRemaps bounds how often one file may throw its map away and
// re-probe. A map that still disagrees with the articles after two rebuilds is
// not going to be fixed by a third, and every rebuild costs NNTP fetches.
const maxSegmentMapRemaps = 2

// distrustSegmentMap discards a segment map that a decoded article just
// disproved, keeping the measurement that disproved it.
//
// A mismatch means some length in the map was inferred rather than measured:
// the usual source is an estimator entry painted across a size class no article
// of this file was ever probed for. Left in place that is permanent — every
// read of the class is refused, the container layer fills the resulting holes,
// and the player receives a well-formed file it cannot decode instead of the
// addon failing over. So the entry that may have taught it is forgotten, the
// real length is kept as a probe, and the map reverts to undetected so the next
// EnsureSegmentMap rebuilds it against actual articles.
func (f *File) distrustSegmentMap(index int, decoded int64) {
	if f == nil || index < 0 || index >= len(f.segments) || decoded <= 0 {
		return
	}

	f.mu.Lock()
	if !f.detected || f.mapRemaps >= maxSegmentMapRemaps {
		f.mu.Unlock()
		return
	}
	f.mapRemaps++
	if f.mapCorrections == nil {
		f.mapCorrections = make(map[int]int64, 1)
	}
	f.mapCorrections[index] = decoded
	f.detected = false
	f.mapDistrusted = true
	f.mapProbes, f.mapKnown, f.mapYenc = nil, nil, yencGeometry{}
	// Back to the pre-detection layout NewFile starts from, so totalSize and
	// the offsets stay consistent with each other while the map is missing.
	f.totalSize = applySegmentDecodedSizes(f.segments, nzbSegmentSizes(f.segments))
	nzbBytes := f.segments[index].Bytes
	attempt := f.mapRemaps
	// Under mu, so the entry is gone before the map is observably undetected:
	// a reader entering EnsureSegmentMapCtx in the gap would otherwise rebuild
	// from the very value this call is throwing the map away for. The
	// estimator never calls back into File, and RestoreSegmentMapJSON already
	// takes its lock under mu, so the mu -> estimator order is the established
	// one.
	if f.estimator != nil {
		f.estimator.Forget(nzbBytes)
	}
	f.mu.Unlock()

	logger.Warn("Discarded a segment map disproved by a decoded article",
		"file", f.Name(), "index", index, "decoded", decoded, "nzb_bytes", nzbBytes, "attempt", attempt)
}

// segmentMapCorrections returns the decoded lengths reads measured against a
// map that turned out to be wrong. They are ground truth, so a rebuild folds
// them in alongside its own probes instead of re-fetching those articles.
func (f *File) segmentMapCorrections() map[int]int64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.mapCorrections) == 0 {
		return nil
	}
	out := make(map[int]int64, len(f.mapCorrections))
	for idx, decoded := range f.mapCorrections {
		out[idx] = decoded
	}
	return out
}

func (f *File) FindSegmentIndex(offset int64) int {
	idx := sort.Search(len(f.segments), func(i int) bool {
		return f.segments[i].EndOffset > offset
	})
	if idx < len(f.segments) && offset >= f.segments[idx].StartOffset {
		return idx
	}
	return -1
}

// DownloadSegment fetches segment index on demand.
// On all-provider failure, it zero-fills the segment and counts it toward IsFailed().
func (f *File) DownloadSegment(ctx context.Context, index int) ([]byte, error) {
	return f.doDownloadSegment(ctx, index, true)
}

// ReadAheadSegment downloads a segment in the background without counting failures
// toward IsFailed(). Used by SegmentReader to warm the pool cache ahead of the read
// pointer so subsequent Read calls don't block on network I/O.
func (f *File) ReadAheadSegment(ctx context.Context, index int) {
	if index < 0 || index >= len(f.segments) {
		return
	}
	go func() {
		_, _ = f.doDownloadSegment(ctx, index, false)
	}()
}

// maxAbandonedJoinRetries bounds how often a caller re-attempts after joining
// an in-flight download that CancelAbandonedReadAhead had already condemned.
const maxAbandonedJoinRetries = 2

func (f *File) doDownloadSegment(ctx context.Context, index int, countFailures bool) ([]byte, error) {
	// A segment already ruled a hole stays a hole: every provider refused it
	// once, so re-fetching on each pass over the same offset only adds latency
	// and would double-count the same damage against MaxZeroFills.
	if index >= 0 && index < len(f.segments) && f.isZeroFilled(index) {
		return f.zeroSegment(index), nil
	}

	// Callers wait on a shared in-flight fetch keyed by segment index, but they do
	// not own the underlying request lifecycle. That shared fetch runs on the file
	// context so short-lived probe/prefetch/read cancellations do not poison a
	// segment download that another reader may still need moments later.
	//
	// The one exception is a fetch CancelAbandonedReadAhead condemned: a caller
	// that raced such a cancellation gets context.Canceled back while its own
	// context is perfectly alive, and retries against a fresh request. The retry
	// cannot spin on the dead one — completeInflightDownload retires a request
	// from the map in the same critical section that closes its done channel.
	for attempt := 0; ; attempt++ {
		req, leader := f.startInflightDownload(index, countFailures)
		logger.Trace("File doDownloadSegment: start", "file", f.Name(), "index", index, "leader", leader, "countFailures", countFailures)
		if leader {
			go f.runInflightDownload(index, req)
		}

		select {
		case <-ctx.Done():
			// Expected when a probe/playback stream is closed or seeks mid-download; not an error.
			f.releaseInflightDownloadWaiter(index, req)
			logger.Trace("File doDownloadSegment: ctx cancelled", "file", f.Name(), "index", index, "err", ctx.Err())
			return nil, ctx.Err()
		case <-req.done:
			f.releaseInflightDownloadWaiter(index, req)
			logger.Trace("File doDownloadSegment: req done", "file", f.Name(), "index", index, "err", req.err)
			// Only a real read retries: a read-ahead waiter re-requesting the
			// fetch it was just cancelled out of would resurrect it.
			if req.err != nil && countFailures && attempt < maxAbandonedJoinRetries &&
				errors.Is(req.err, context.Canceled) && ctx.Err() == nil &&
				(f.ctx == nil || f.ctx.Err() == nil) {
				logger.Trace("File doDownloadSegment: joined an abandoned fetch, retrying", "file", f.Name(), "index", index, "attempt", attempt)
				continue
			}
			return req.data, req.err
		}
	}
}

// cancelAbandonedReadAheadIn aborts in-flight downloads inside [from, to) that
// only read-ahead ever asked for. Read-ahead fetches deliberately run on the
// file context so transient cancellations cannot poison them — but a window a
// reader has jumped away from keeps downloading segments nobody will read, and
// with the playback window at up to ~100 segments those dead fetches occupy
// the very connections the new position is now queued behind.
//
// The range is the caller's OWN abandoned window, never "everything else": a
// file is shared by concurrent readers, tail warms and prefetches, and a
// file-wide sweep keyed off one reader's position cancelled all of them —
// including the startup warm, on nothing more than http.ServeContent's
// size-probing Seek(0, End). A fetch with a real reader attached
// (countFailures) is never touched either way.
func (f *File) cancelAbandonedReadAheadIn(from, to int) {
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()
	cancelled := 0
	for index, req := range f.inflightDownloads {
		if req.countFailures || index < from || index >= to {
			continue
		}
		req.cancel()
		cancelled++
	}
	if cancelled > 0 {
		logger.Trace("File cancelAbandonedReadAheadIn", "file", f.Name(), "cancelled", cancelled, "from", from, "to", to)
	}
}

// abandonedReadAheadWindow is the read-ahead a closed reader left in flight,
// captured as the exact requests it had running rather than as a range. The
// next reader decides their fate, and by then the range alone would also name
// fetches that reader started itself.
type abandonedReadAheadWindow struct {
	reqs  map[int]*inflightSegmentDownload
	timer *time.Timer
}

// abandonedReadAheadGrace is how long a closed reader's window waits to be
// claimed before it is dropped unasked.
//
// The reader that would claim it is the next Range request of the same play,
// which arrives in milliseconds, so this is long enough to lose nothing. What
// it bounds is the case no reader on this file ever answers for: the last
// request of a session, whose window goes on holding connections from a pool
// every other session shares. Reaping is otherwise driven by demand on the
// same file, and demand from a different file cannot see it.
const abandonedReadAheadGrace = 2 * time.Second

// abandonReadAheadWindow records the reader-less fetches still running in
// [from, to) when a reader closed.
//
// It cancels nothing yet. Serving is per-request: a player streaming a file in
// consecutive Range requests closes a reader and opens the next one over the
// very window this one warmed, so cancelling at close would throw away the
// prefetch that request is about to read. A player that seeks instead leaves
// the window dead, and up to a full playback window of fetches nobody will
// read goes on holding the connections the seek is queued behind — which is
// the stall this exists to end. Which one it was is knowable one read-ahead
// later, so that is where the decision lives (see reapAbandonedReadAhead).
//
// Fetches with a real reader attached are never captured, exactly as
// cancelAbandonedReadAheadIn never touches them.
func (f *File) abandonReadAheadWindow(from, to int) {
	if to <= from {
		return
	}
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()

	var reqs map[int]*inflightSegmentDownload
	for index, req := range f.inflightDownloads {
		if req.countFailures || index < from || index >= to {
			continue
		}
		if reqs == nil {
			reqs = make(map[int]*inflightSegmentDownload)
		}
		reqs[index] = req
	}
	if reqs == nil {
		return
	}
	f.abandonedReadAheadSeq++
	id := f.abandonedReadAheadSeq
	if f.abandonedReadAhead == nil {
		f.abandonedReadAhead = make(map[uint64]abandonedReadAheadWindow)
	}
	f.abandonedReadAhead[id] = abandonedReadAheadWindow{
		reqs:  reqs,
		timer: time.AfterFunc(abandonedReadAheadGrace, func() { f.dropAbandonedReadAhead(id) }),
	}
	logger.Trace("File abandonReadAheadWindow", "file", f.Name(), "from", from, "to", to, "held", len(reqs))
}

// dropAbandonedReadAhead cancels everything one window is still holding, for
// the window whose grace ran out.
func (f *File) dropAbandonedReadAhead(id uint64) {
	f.downloadMu.Lock()
	window, ok := f.abandonedReadAhead[id]
	delete(f.abandonedReadAhead, id)
	cancelled := 0
	if ok {
		cancelled = f.cancelHeldReadAheadLocked(window, 0, 0)
	}
	f.downloadMu.Unlock()

	if cancelled > 0 {
		logger.Trace("File dropAbandonedReadAhead", "file", f.Name(), "cancelled", cancelled)
	}
}

// cancelHeldReadAheadLocked cancels the fetches a window holds outside
// [from, to), and reports how many it stopped. Callers hold downloadMu.
//
// A fetch that has been retired, replaced, or picked up by a real reader since
// it was captured is left alone: the capture names request pointers, so a
// segment index re-fetched in between is a different request and cannot be hit
// by a cancellation meant for the old one.
func (f *File) cancelHeldReadAheadLocked(window abandonedReadAheadWindow, from, to int) int {
	cancelled := 0
	for index, req := range window.reqs {
		if index >= from && index < to {
			continue
		}
		if current, ok := f.inflightDownloads[index]; !ok || current != req || req.countFailures {
			continue
		}
		req.cancel()
		cancelled++
	}
	return cancelled
}

// reapAbandonedReadAhead settles every window a closed reader left behind
// against the window a reader now wants: a held fetch inside [from, to) is
// adopted, one outside it is cancelled so its connection goes to the new
// window instead. Either way the hold is released — an adopted fetch belongs
// to the new window, whose own reader will abandon it in turn.
func (f *File) reapAbandonedReadAhead(from, to int) {
	f.downloadMu.Lock()
	cancelled := 0
	for id, window := range f.abandonedReadAhead {
		if window.timer != nil {
			window.timer.Stop()
		}
		cancelled += f.cancelHeldReadAheadLocked(window, from, to)
		delete(f.abandonedReadAhead, id)
	}
	f.downloadMu.Unlock()

	if cancelled > 0 {
		logger.Trace("File reapAbandonedReadAhead", "file", f.Name(), "from", from, "to", to, "cancelled", cancelled)
	}
}

func (f *File) startInflightDownload(index int, countFailures bool) (*inflightSegmentDownload, bool) {
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()

	if req, ok := f.inflightDownloads[index]; ok {
		req.waiters++
		if countFailures {
			req.countFailures = true
		}
		return req, false
	}

	baseCtx := f.ctx
	if baseCtx == nil {
		baseCtx = context.Background()
	}
	sharedCtx, cancel := context.WithCancel(baseCtx)

	req := &inflightSegmentDownload{
		done:          make(chan struct{}),
		countFailures: countFailures,
		ctx:           sharedCtx,
		cancel:        cancel,
		waiters:       1,
	}
	f.inflightDownloads[index] = req
	return req, true
}

func (f *File) releaseInflightDownloadWaiter(index int, req *inflightSegmentDownload) {
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()

	current, ok := f.inflightDownloads[index]
	if !ok || current != req {
		return
	}
	if req.waiters > 0 {
		req.waiters--
	}
}

func (f *File) runInflightDownload(index int, req *inflightSegmentDownload) {
	logger.Trace("File runInflightDownload: start fetch", "file", f.Name(), "index", index, "viaFetcher", f.fetcher != nil)
	start := time.Now()
	var data []byte
	var err error
	if f.fetcher != nil {
		data, err = f.doDownloadSegmentViaFetcher(req.ctx, index)
	}
	if elapsed := time.Since(start); elapsed > slowSegmentFetchThreshold && req.ctx.Err() == nil {
		logger.Warn("Slow segment fetch", "file", f.Name(), "index", index, "duration", elapsed, "err", err)
	}
	logger.Trace("File runInflightDownload: fetch complete", "file", f.Name(), "index", index, "err", err, "dataLen", len(data))

	f.completeInflightDownload(index, req, data, err)
}

// completeInflightDownload publishes a result to everyone waiting on req and
// retires it. A req that is no longer the registered one for index lost a race
// with a reset and is dropped: its waiters have already moved to the new one.
func (f *File) completeInflightDownload(index int, req *inflightSegmentDownload, data []byte, err error) {
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()

	current, ok := f.inflightDownloads[index]
	if !ok || current != req {
		return
	}
	req.data, req.err = f.finalizeSegmentDownload(index, data, err, req.countFailures)
	delete(f.inflightDownloads, index)
	req.cancel()
	close(req.done)
}

func (f *File) finalizeSegmentDownload(index int, data []byte, err error, countFailures bool) ([]byte, error) {
	if err == nil {
		return data, nil
	}

	var eligible *zeroFillEligibleError
	if !errors.As(err, &eligible) {
		return nil, err
	}

	if !countFailures {
		return nil, fmt.Errorf("prefetch segment download failed (not counted): %w", eligible.cause)
	}

	f.zeroFillMu.Lock()
	_, known := f.zeroFilled[index]
	count := len(f.zeroFilled)
	if !known {
		if count >= MaxZeroFills {
			f.zeroFillMu.Unlock()
			return nil, fmt.Errorf("too many failed segments (%d/%d): %w", count+1, MaxZeroFills, errors.Join(ErrTooManyZeroFills, eligible.cause))
		}
		f.zeroFilled[index] = struct{}{}
		count++
	}
	run := f.zeroFilledRunLocked(index)
	f.zeroFillMu.Unlock()

	if run > MaxZeroFillRun {
		// Isolated holes are a glitch; a run this long is seconds of zeros
		// inside the stream, and no player rides that out. Fail the file so
		// the read errors and the slot fails over.
		f.runFailed.Store(true)
		return nil, fmt.Errorf("missing run of %d segments at %d exceeds %d: %w", run, index, MaxZeroFillRun, errors.Join(ErrTooManyZeroFills, eligible.cause))
	}

	// Warn, not Debug: every zero-filled segment is wrong bytes served to a
	// player, and the operator should be able to find that after the fact.
	logger.Warn("Segment unavailable, zero-filling gap", "file", f.Name(), "index", index, "holes", count, "max", MaxZeroFills, "run", run, "err", eligible.cause)
	return f.zeroSegment(index), nil
}

func (f *File) doDownloadSegmentViaFetcher(ctx context.Context, index int) ([]byte, error) {
	seg := f.segments[index]
	if strings.TrimSpace(seg.ID) == "" {
		// The NZB carries no article for this segment — a numbering-gap
		// placeholder from normalizeNZBSegments, or a segment posted without a
		// message id. No provider can ever serve it, so the verdict is
		// immediate and needs no network. The first segment carries the
		// container header and stays fatal, exactly like a 430 there; past it
		// the zero-fill policy decides between a rideable glitch and
		// ErrTooManyZeroFills.
		if index == 0 {
			logger.Warn("First segment missing from NZB", "file", f.Name(), "segment", seg.Number)
			return nil, fmt.Errorf("segment unavailable: article missing from NZB (segment %d)", seg.Number)
		}
		return nil, &zeroFillEligibleError{cause: fmt.Errorf("segment unavailable: article missing from NZB (segment %d)", seg.Number)}
	}

	downloadCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	var data pool.SegmentData
	var err error
	if index == 0 {
		if firstFetcher, ok := f.fetcher.(SegmentFirstFetcher); ok {
			data, err = firstFetcher.FetchSegmentFirst(downloadCtx, &seg.Segment, f.nzbFile.Groups)
		} else {
			data, err = f.fetcher.FetchSegment(downloadCtx, &seg.Segment, f.nzbFile.Groups)
		}
	} else {
		data, err = f.fetcher.FetchSegment(downloadCtx, &seg.Segment, f.nzbFile.Groups)
	}
	if err != nil {
		if index == 0 {
			logger.Warn("First segment fetch failed", "file", f.Name(), "segment", seg.Number, "err", err)
			// The first segment carries the container/volume header: nothing
			// downstream can make sense of a zero-filled one, so a miss here
			// stays a fast, definitive verdict about the release.
			if isArticleNotFound(err) {
				return nil, fmt.Errorf("segment unavailable: %w", err)
			}
			return nil, fmt.Errorf("first segment fetch failed: %w", err)
		}
		if isArticleNotFound(err) {
			// An isolated missing article past the header is a hole, not a dead
			// release. Hand it to the zero-fill policy, which decides between a
			// glitch the player rides out and ErrTooManyZeroFills once the file
			// has accumulated more holes than MaxZeroFills allows. The cause is
			// preserved either way, so the fatal error still reads as a 430.
			return nil, &zeroFillEligibleError{cause: fmt.Errorf("segment unavailable: %w", err)}
		}
		if isContextErr(err) || !shouldPersistDownloadedSegment(downloadCtx) {
			if ctxErr := downloadCtx.Err(); ctxErr != nil {
				return nil, ctxErr
			}
			return nil, err
		}
		// Anything else — a timeout, a connection reset, a decode failure, an
		// exhausted pool — is inconclusive: the bytes may well exist, so
		// substituting zeros would serve provably wrong data for a transient
		// fault. Only a 430 from every provider is evidence of a hole; the
		// rest propagates so the playback layer can retry or fail over.
		return nil, fmt.Errorf("segment fetch failed: %w", err)
	}
	if !shouldPersistDownloadedSegment(downloadCtx) {
		return nil, downloadCtx.Err()
	}
	if name := strings.TrimSpace(data.FileName); name != "" {
		f.yencName.Store(name)
	}
	f.recordYencGeometry(index, data)
	// Don't cache here when using the pool fetcher: the pool already cached by message ID.
	// Caching again would double memory use (same segment in pool cache + loader segCache) and double-count the budget.
	return data.Body, nil
}

// recordYencGeometry keeps an article's declared file size and part offset for
// the map builder. Only useful before the map exists, so it stops accumulating
// once detection is done. Articles of one file that disagree on the file size
// mean the headers cannot be trusted; the whole geometry is poisoned rather
// than mixed.
func (f *File) recordYencGeometry(index int, data pool.SegmentData) {
	if data.YencFileSize <= 0 || index < 0 || index >= len(f.segments) {
		return
	}
	if f.SegmentMapDetected() {
		return
	}
	f.yencGeoMu.Lock()
	defer f.yencGeoMu.Unlock()
	switch {
	case f.yencGeo.fileSize == 0:
		f.yencGeo.fileSize = data.YencFileSize
	case f.yencGeo.fileSize != data.YencFileSize:
		f.yencGeo.fileSize = -1 // poisoned; exactSizesFromYencGeometry refuses it
		return
	case f.yencGeo.fileSize < 0:
		return
	}
	if f.yencGeo.offsets == nil {
		f.yencGeo.offsets = make(map[int]int64)
	}
	f.yencGeo.offsets[index] = data.YencPartOffset
}

// yencGeometrySnapshot copies the collected geometry for the map builder.
func (f *File) yencGeometrySnapshot() yencGeometry {
	f.yencGeoMu.Lock()
	defer f.yencGeoMu.Unlock()
	geo := yencGeometry{fileSize: f.yencGeo.fileSize}
	if len(f.yencGeo.offsets) > 0 {
		geo.offsets = make(map[int]int64, len(f.yencGeo.offsets))
		for idx, off := range f.yencGeo.offsets {
			geo.offsets[idx] = off
		}
	}
	return geo
}

func (f *File) ReadAt(p []byte, off int64) (n int, err error) {
	return f.ReadAtCtx(f.ctx, p, off)
}

// ReadAtCtx is ReadAt bound to the caller's context. The distinction matters
// before the segment map exists: EnsureSegmentMap on the bare file context
// carries no skip-gap flag, so a first ReadAt from a scan path would probe
// every segment of the file — the whole volume — to serve a few bytes. The
// caller's context carries the policy the caller chose.
func (f *File) ReadAtCtx(ctx context.Context, p []byte, off int64) (n int, err error) {
	if ctx == nil {
		ctx = f.ctx
	}
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return 0, err
	}
	if off >= f.totalSize {
		return 0, io.EOF
	}

	startIdx := f.FindSegmentIndex(off)
	if startIdx == -1 {
		return 0, io.EOF
	}

	currentOffset := off
	totalRead := 0
	for i := startIdx; i < len(f.segments) && totalRead < len(p); i++ {
		seg := f.segments[i]
		segLen := f.segmentDecodedLen(i)
		segOff := currentOffset - seg.StartOffset
		if segOff >= segLen {
			continue
		}

		data, err := f.DownloadSegment(ctx, i)
		if err != nil {
			return totalRead, err
		}
		if err := f.verifyMappedSegmentLength(i, data); err != nil {
			return totalRead, err
		}

		remain := segLen - segOff
		avail := int64(len(data)) - segOff
		if avail < 0 {
			avail = 0
		}
		toCopy := remain
		if avail < toCopy {
			toCopy = avail
		}
		bufRoom := int64(len(p) - totalRead)
		if bufRoom < toCopy {
			toCopy = bufRoom
		}
		if toCopy <= 0 {
			continue
		}

		copied := copy(p[totalRead:], data[segOff:segOff+toCopy])
		totalRead += copied
		currentOffset += int64(copied)
	}

	if totalRead < len(p) && currentOffset >= f.totalSize {
		return totalRead, io.EOF
	}
	return totalRead, nil
}

func (f *File) OpenStream() (io.ReadSeekCloser, error) {
	return f.OpenStreamCtx(f.ctx)
}

func (f *File) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return nil, err
	}
	return NewSegmentReader(ctx, f, 0), nil
}

// OpenPlaybackStreamCtx opens a seekable stream with the playback read-ahead
// window, for direct (non-archive) playback of a whole file.
//
// OpenStreamCtx is shared with the RAR scan, PAR2 repair and archive-probe
// paths, which read small pieces and would only waste bandwidth on a deep
// prefetch — so it keeps DefaultReadAhead. Playback is the opposite case:
// throughput is read-ahead depth times per-connection rate, and at eight
// segments in flight a 4K remux runs out of runway (a 67 GB release needs
// ~7.6 MB/s sustained and was being served 6–8 MB/s, blocked on reads 97% of
// the time). Multi-volume archive playback already opened its reader with the
// deeper window; direct playback, the common case, did not.
func (f *File) OpenPlaybackStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return nil, err
	}
	return NewSegmentReaderWithReadAhead(ctx, f, 0, f.PlaybackReadAhead()), nil
}

// PlaybackReadAhead is this file's read-ahead window, sized against its own
// article size rather than assumed to be the sub-megabyte common case, and
// against the whole stream when this file is one volume of one.
func (f *File) PlaybackReadAhead() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	fileBytes := f.totalSize
	if f.playbackStreamBytes > fileBytes {
		fileBytes = f.playbackStreamBytes
	}
	return PlaybackReadAheadFor(fileBytes, f.averageSegmentSizeLocked())
}

// SetPlaybackStreamBytes records the size of the whole stream this file is one
// volume of. The window is a buffer measured in seconds of playback, and a
// 500 MB volume of a 176 GB movie is 1/300th of that runtime — sized against
// the volume, the budget floored at its minimum on exactly the releases that
// need the deepest window. Growing only, so a shorter stream opened over the
// same volume cannot shrink a window playback is already relying on.
func (f *File) SetPlaybackStreamBytes(n int64) {
	f.mu.Lock()
	if n > f.playbackStreamBytes {
		f.playbackStreamBytes = n
	}
	f.mu.Unlock()
}

// averageSegmentSizeLocked reports the mean decoded segment size, which is what
// the window has to be measured in. The mean rather than segment zero: a file
// can open with a short article, and one unlucky sample would size the whole
// window wrong. Returns 0 before the map is built, which reads as "unknown".
func (f *File) averageSegmentSizeLocked() int64 {
	if !f.detected || len(f.segments) == 0 || f.totalSize <= 0 {
		return 0
	}
	return f.totalSize / int64(len(f.segments))
}

func (f *File) OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return nil, err
	}
	return NewSegmentReader(ctx, f, offset), nil
}

// OpenPlaybackReaderAt opens a reader with a larger read-ahead window for archive
// playback seeks that cross RAR volume boundaries.
func (f *File) OpenPlaybackReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return nil, err
	}
	return NewSegmentReaderWithReadAhead(ctx, f, offset, f.PlaybackReadAhead()), nil
}

// PrefetchPlaybackOffset warms the segment cache ahead of an upcoming volume switch.
func (f *File) PrefetchPlaybackOffset(ctx context.Context, offset int64) {
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return
	}
	idx := f.FindSegmentIndex(offset)
	if idx < 0 {
		return
	}
	end := idx + f.PlaybackReadAhead()
	if end > len(f.segments) {
		end = len(f.segments)
	}
	// Bind prefetch to the lifetime of the session file context (f.ctx) rather than using context.WithoutCancel.
	// This ensures that when the session closes, the prefetches are aborted, but they aren't cancelled by transient HTTP request timeouts.
	bgCtx := f.ctx
	if bgCtx == nil {
		bgCtx = context.Background()
	}
	f.ReadAheadRange(bgCtx, idx, end)
}

// PrefetchPlaybackRange warms only the segments covering [offset, offset+length),
// instead of the full playback window PrefetchPlaybackOffset opens.
//
// The caller that needs this is a tail warm: a player reads the container index
// at the end of the file and then seeks straight back to the start, so pulling a
// whole window in behind it would spend the bandwidth the opening bytes need.
func (f *File) PrefetchPlaybackRange(ctx context.Context, offset, length int64) {
	if length <= 0 {
		return
	}
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		return
	}
	if offset < 0 {
		offset = 0
	}
	from := f.FindSegmentIndex(offset)
	if from < 0 {
		return
	}
	to := f.FindSegmentIndex(offset + length - 1)
	if to < from {
		to = len(f.segments) - 1
	}
	// Same binding as PrefetchPlaybackOffset: the session context, so a closed
	// session stops the fetch but a finished HTTP request does not.
	bgCtx := f.ctx
	if bgCtx == nil {
		bgCtx = context.Background()
	}
	f.ReadAheadRange(bgCtx, from, to+1)
}

// MaxSegmentSizeEstimatorEntries caps the number of size entries to prevent unbounded growth.
const MaxSegmentSizeEstimatorEntries = 128

// segmentSizeEstimatorTolerance is how far an NZB-declared article size may sit
// from a recorded one and still be treated as the same class. Get, Set and
// Forget must all use it: a value handed out under this window has to be
// removable under the same window.
const segmentSizeEstimatorTolerance = 4096

// SegmentSizeEstimator remembers the decoded length measured for an
// NZB-declared article size, so the later volumes of a release do not re-probe
// a class an earlier volume already measured.
//
// It is scoped to one release. The key is the declared article size alone, and
// two unrelated posters routinely declare sizes within tolerance of each other
// while their articles decode to different lengths — a process-wide instance
// therefore handed one poster's decoded size to another's file, and every read
// against the resulting map was refused by verifyMappedSegmentLength.
type SegmentSizeEstimator struct {
	entries []sizeEntry
	mu      sync.RWMutex
}

type sizeEntry struct {
	encoded int64
	decoded int64
}

func NewSegmentSizeEstimator() *SegmentSizeEstimator {
	return &SegmentSizeEstimator{entries: make([]sizeEntry, 0, 4)}
}

func (e *SegmentSizeEstimator) Get(encodedSize int64) (int64, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	for _, entry := range e.entries {
		diff := entry.encoded - encodedSize
		if diff < 0 {
			diff = -diff
		}
		if diff < segmentSizeEstimatorTolerance {
			return entry.decoded, true
		}
	}
	return 0, false
}

func (e *SegmentSizeEstimator) Set(encodedSize, decodedSize int64) {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, entry := range e.entries {
		diff := entry.encoded - encodedSize
		if diff < 0 {
			diff = -diff
		}
		if diff < segmentSizeEstimatorTolerance {
			return
		}
	}
	if len(e.entries) >= MaxSegmentSizeEstimatorEntries {
		e.entries = e.entries[1:]
	}
	e.entries = append(e.entries, sizeEntry{encoded: encodedSize, decoded: decodedSize})
}

// Forget drops every entry a Get for encodedSize could match. A decoded article
// disproved the size this class was handing out, so it must stop being handed
// out — otherwise the remaining volumes of the release inherit the same wrong
// map, and Set's first-write-wins rule would keep the bad entry forever.
func (e *SegmentSizeEstimator) Forget(encodedSize int64) {
	e.mu.Lock()
	defer e.mu.Unlock()
	kept := e.entries[:0]
	for _, entry := range e.entries {
		diff := entry.encoded - encodedSize
		if diff < 0 {
			diff = -diff
		}
		if diff < segmentSizeEstimatorTolerance {
			continue
		}
		kept = append(kept, entry)
	}
	e.entries = kept
}
