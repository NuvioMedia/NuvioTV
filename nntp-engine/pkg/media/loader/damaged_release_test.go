package loader

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/textproto"
	"strings"
	"sync"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
	"streamnzb/pkg/usenet/pool"
)

// damagedSegmentFetcher serves a synthetic release whose listed articles are all
// present except the indices in missing, which answer 430 the way a provider
// does for an article that was never propagated or was removed.
type damagedSegmentFetcher struct {
	segmentSize int
	missing     map[int]struct{}

	mu      sync.Mutex
	fetches map[int]int
}

func newDamagedSegmentFetcher(segmentSize int, missing ...int) *damagedSegmentFetcher {
	gone := make(map[int]struct{}, len(missing))
	for _, idx := range missing {
		gone[idx] = struct{}{}
	}
	return &damagedSegmentFetcher{segmentSize: segmentSize, missing: gone, fetches: make(map[int]int)}
}

func (f *damagedSegmentFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	index := segment.Number - 1

	f.mu.Lock()
	f.fetches[index]++
	f.mu.Unlock()

	if _, gone := f.missing[index]; gone {
		return pool.SegmentData{}, fmt.Errorf("fetch segment %s: %w", segment.ID, &textproto.Error{Code: 430, Msg: "No Such Article"})
	}
	return pool.SegmentData{Body: segmentPayload(index, f.segmentSize)}, nil
}

func (f *damagedSegmentFetcher) fetchCount(index int) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.fetches[index]
}

// segmentPayload gives every segment a distinct, non-zero byte so a zero-filled
// gap is distinguishable from real data that merely landed at the wrong offset.
func segmentPayload(index, size int) []byte {
	return bytes.Repeat([]byte{byte(index%255 + 1)}, size)
}

func damagedNZBFile(segments, segmentSize int) *nzb.File {
	sizes := make([]int64, segments)
	for i := range sizes {
		sizes[i] = int64(segmentSize)
	}
	return testNZBFileWithSegments(sizes...)
}

// playbackCtx mirrors how a play request reads: gap probing off, so the segment
// map comes from sampling rather than downloading every article up front.
func playbackCtx() context.Context {
	return WithSkipGapProbing(context.Background(), true)
}

func TestIsolatedMissingArticlesPlayThroughAsZeroFilledGaps(t *testing.T) {
	const segments, segmentSize = 12, 1024
	fetcher := newDamagedSegmentFetcher(segmentSize, 3, 7)
	f := NewFile(context.Background(), damagedNZBFile(segments, segmentSize), nil, fetcher)

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()

	got, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("reading a release with two holes must succeed, got: %v", err)
	}
	if len(got) != segments*segmentSize {
		t.Fatalf("read %d bytes, want %d", len(got), segments*segmentSize)
	}
	for i := 0; i < segments; i++ {
		want := segmentPayload(i, segmentSize)
		if i == 3 || i == 7 {
			want = make([]byte, segmentSize)
		}
		if !bytes.Equal(got[i*segmentSize:(i+1)*segmentSize], want) {
			t.Fatalf("segment %d did not read back as expected", i)
		}
	}
	if holes := f.ZeroFilledSegments(); holes != 2 {
		t.Fatalf("zero-filled segments = %d, want 2", holes)
	}
	if f.IsFailed() {
		t.Fatal("two holes must not mark the file failed")
	}
}

func TestMissingArticlesPastPolicyFailFatally(t *testing.T) {
	const segmentSize = 1024
	// Holes on every other segment: each is an isolated glitch under the run
	// cap, so it is the cumulative cap alone that ends the stream.
	missing := make([]int, 0, MaxZeroFills+1)
	for i := 1; i <= MaxZeroFills+1; i++ {
		missing = append(missing, 2*i)
	}
	fetcher := newDamagedSegmentFetcher(segmentSize, missing...)
	f := NewFile(context.Background(), damagedNZBFile(2*(MaxZeroFills+1)+4, segmentSize), nil, fetcher)

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()

	_, err = io.ReadAll(stream)
	if !errors.Is(err, ErrTooManyZeroFills) {
		t.Fatalf("read error = %v, want ErrTooManyZeroFills", err)
	}
	// The 430 must survive the join: the serve layer classifies the release from
	// this cause, not from the threshold sentinel alone.
	if !nntp.IsArticleNotFound(err) {
		t.Fatalf("read error lost its missing-article cause: %v", err)
	}
	if holes := f.ZeroFilledSegments(); holes != MaxZeroFills {
		t.Fatalf("zero-filled segments = %d, want %d", holes, MaxZeroFills)
	}
	if !f.IsFailed() {
		t.Fatal("a file past the zero-fill cap must report as failed")
	}
}

func TestZeroFilledSegmentIsNotRefetched(t *testing.T) {
	const segmentSize = 1024
	fetcher := newDamagedSegmentFetcher(segmentSize, 2)
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	for pass := 0; pass < 3; pass++ {
		data, err := f.DownloadSegment(context.Background(), 2)
		if err != nil {
			t.Fatalf("pass %d: DownloadSegment returned error: %v", pass, err)
		}
		if !bytes.Equal(data, make([]byte, segmentSize)) {
			t.Fatalf("pass %d: expected a zero-filled segment", pass)
		}
	}
	if got := fetcher.fetchCount(2); got != 1 {
		t.Fatalf("known hole was fetched %d times, want 1", got)
	}
	if holes := f.ZeroFilledSegments(); holes != 1 {
		t.Fatalf("re-reading one hole counted %d holes, want 1", holes)
	}
}

// transientFailureFetcher fails the indices in failing with a non-430 error —
// a timeout, a reset, an exhausted pool — the kinds of failure that say nothing
// about whether the article exists.
type transientFailureFetcher struct {
	segmentSize int
	failing     map[int]struct{}
}

func (f *transientFailureFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	index := segment.Number - 1
	if _, bad := f.failing[index]; bad {
		return pool.SegmentData{}, fmt.Errorf("fetch segment %s: failed after retries: read tcp: i/o timeout", segment.ID)
	}
	return pool.SegmentData{Body: segmentPayload(index, f.segmentSize)}, nil
}

func TestTransientFetchFailureIsAnErrorNotAHole(t *testing.T) {
	const segmentSize = 1024
	fetcher := &transientFailureFetcher{segmentSize: segmentSize, failing: map[int]struct{}{2: {}}}
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	_, err := f.DownloadSegment(context.Background(), 2)
	if err == nil {
		t.Fatal("a transient fetch failure must surface as an error, not zeros")
	}
	if nntp.IsArticleNotFound(err) {
		t.Fatalf("a transient failure must not read as a missing article: %v", err)
	}
	if holes := f.ZeroFilledSegments(); holes != 0 {
		t.Fatalf("a transient failure must not be zero-filled, got %d holes", holes)
	}
	if f.IsFailed() {
		t.Fatal("a transient failure must not count toward the zero-fill cap")
	}
}

// mismatchedSegmentFetcher serves every segment at def bytes except the
// indices in sizes — a post whose articles decode to lengths the estimated
// segment map did not predict.
type mismatchedSegmentFetcher struct {
	def   int
	sizes map[int]int
}

func (f *mismatchedSegmentFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	index := segment.Number - 1
	size := f.def
	if s, ok := f.sizes[index]; ok {
		size = s
	}
	return pool.SegmentData{Body: segmentPayload(index, size)}, nil
}

// A segment that decodes longer than the map predicted used to have its
// surplus silently dropped, shifting every byte after it. It must be a loud
// error instead.
func TestSegmentLongerThanMapFailsLoudly(t *testing.T) {
	const segmentSize = 1024
	fetcher := &mismatchedSegmentFetcher{def: segmentSize, sizes: map[int]int{2: segmentSize + 76}}
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()

	_, err = io.ReadAll(stream)
	if err == nil {
		t.Fatal("a segment longer than its mapped size must error, not silently drop bytes")
	}
	if !strings.Contains(err.Error(), "mapped") {
		t.Fatalf("error should name the map mismatch, got: %v", err)
	}
}

func TestSegmentLengthMismatchFailsLoudlyOnReadAt(t *testing.T) {
	const segmentSize = 1024
	fetcher := &mismatchedSegmentFetcher{def: segmentSize, sizes: map[int]int{2: segmentSize + 76}}
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)
	if err := f.EnsureSegmentMapCtx(playbackCtx()); err != nil {
		t.Fatalf("EnsureSegmentMapCtx returned error: %v", err)
	}

	buf := make([]byte, 3*segmentSize)
	if _, err := f.ReadAt(buf, 2*segmentSize); err == nil {
		t.Fatal("ReadAt through a mismatched segment must error, not shift bytes")
	}
}

func TestMissingFirstArticleStillFailsFast(t *testing.T) {
	const segmentSize = 1024
	fetcher := newDamagedSegmentFetcher(segmentSize, 0)
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	_, err := f.DownloadSegment(context.Background(), 0)
	if !nntp.IsArticleNotFound(err) {
		t.Fatalf("DownloadSegment(0) = %v, want a missing-article error", err)
	}
	if holes := f.ZeroFilledSegments(); holes != 0 {
		t.Fatalf("a missing header segment must not be zero-filled, got %d holes", holes)
	}
}
