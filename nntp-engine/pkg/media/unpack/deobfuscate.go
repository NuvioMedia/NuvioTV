package unpack

import (
	"context"
	"crypto/md5"
	"fmt"
	"io"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"streamnzb/pkg/core/logger"
)

// Deobfuscation gives a release back its filenames.
//
// A fully obfuscated post replaces every subject with a random hash and drops
// the extension, so classifying by subject — which is how every other stage
// here decides whether a file is a RAR volume, a PAR2 recovery file or the
// media itself — sees nothing at all and the release is refused. The real names
// are still recoverable, in descending order of trust:
//
//  1. PAR2 FileDesc packets, which record every file of the set by name,
//     length and the MD5 of its first 16 KiB. That hash identifies each NZB
//     file outright, so names are matched by content, never by position.
//  2. The yEnc "=ybegin name=" header, which the poster's uploader wrote into
//     every article of a file.
//  3. The file's own content signature (PAR2 / RAR / 7z / container magic),
//     which names nothing but says what the file *is* — enough to route it and,
//     for RAR5, to read its volume number straight out of the header.
//
// Classification stays subject-first: when every subject already carries a
// usable filename, resolveObfuscatedNames reads nothing and returns the files
// untouched, so a normal release costs no extra fetches.

// deobfuscationReadConcurrency bounds the header reads issued in parallel. Each
// one costs a first-segment fetch, and those articles are exactly the ones
// archive scanning fetches next, so they land in the segment cache either way.
const deobfuscationReadConcurrency = 8

// synthesizedStem names files identified only by signature. The release's real
// stem is unknowable in that case; what matters is that every volume of a set
// shares one prefix, which is what archivePrefix and the volume matchers key on.
const synthesizedStem = "release"

// fileEntry is one release file plus everything deobfuscation learned about it.
type fileEntry struct {
	idx     int
	file    UnpackableFile
	subject string
	header  []byte
	kind    contentKind
	rar     rarHeaderInfo
}

// resolveObfuscatedNames returns files whose Name() reports a usable filename,
// recovering the names of an obfuscated release from its own bytes. The
// returned slice keeps the input order and length, so file indexes recorded by
// a blueprint stay valid. Files it cannot name are passed through unchanged.
func resolveObfuscatedNames(ctx context.Context, files []UnpackableFile) []UnpackableFile {
	pending := unusableNamedFiles(files)
	if len(pending) == 0 {
		return files
	}
	if err := contextErr(ctx); err != nil {
		return files
	}

	entries := readFileEntries(ctx, files, pending)
	if len(entries) == 0 {
		return files
	}

	names := make(map[int]string, len(entries))
	resolveNamesFromPAR2(ctx, files, entries, names)
	resolveNamesFromYenc(ctx, entries, names)
	resolveNamesFromSignatures(entries, names)

	dropCollidingNames(files, names)
	if len(names) == 0 {
		logger.Debug("Deobfuscation could not recover any filename", "unnamed_files", len(pending))
		return files
	}

	out := make([]UnpackableFile, len(files))
	copy(out, files)
	for idx, name := range names {
		out[idx] = &renamedFile{UnpackableFile: files[idx], name: name}
	}
	logger.Info("Recovered filenames for obfuscated release",
		"renamed", len(names),
		"unnamed", len(pending)-len(names),
		"files", len(files),
		"samples", sampleNames(out, names, 4))
	return out
}

// dropCollidingNames discards any recovered name that another file in the
// release already answers to. Archive scanning addresses volumes by name
// through NZBFS, so two files sharing one would silently resolve to whichever
// was mapped last. Keeping the raw subject for the loser is the safe outcome:
// that file is no worse off than before, and the rest of the release still
// benefits.
func dropCollidingNames(files []UnpackableFile, names map[int]string) {
	taken := make(map[string]struct{}, len(files))
	for i, f := range files {
		if _, renamed := names[i]; renamed || f == nil {
			continue
		}
		taken[strings.ToLower(ExtractFilename(f.Name()))] = struct{}{}
	}
	for _, idx := range sortedKeys(names) {
		key := strings.ToLower(names[idx])
		if _, clash := taken[key]; clash {
			logger.Debug("Deobfuscation dropped a colliding recovered name",
				"subject", files[idx].Name(), "name", names[idx])
			delete(names, idx)
			continue
		}
		taken[key] = struct{}{}
	}
}

func sortedKeys(names map[int]string) []int {
	keys := make([]int, 0, len(names))
	for k := range names {
		keys = append(keys, k)
	}
	sort.Ints(keys)
	return keys
}

// unusableNamedFiles lists the files whose subject does not already name them.
func unusableNamedFiles(files []UnpackableFile) []int {
	var pending []int
	for i, f := range files {
		if f == nil {
			continue
		}
		if !isUsableFilename(ExtractFilename(f.Name())) {
			pending = append(pending, i)
		}
	}
	return pending
}

// isUsableFilename reports whether a name already tells the pipeline what it
// needs, so the file can be skipped without reading a byte of it.
//
// For payload — media and archives — that takes both a known extension and a
// stem that is not a random hash: "a3f9c1e2b8d47a56.mkv" routes correctly but
// is still unmatchable to an episode and meaningless to a viewer, so it is
// worth trying to recover. For an auxiliary file the extension is the whole
// story; nothing downstream reads a PAR2's or an NFO's stem. Testing those for
// obfuscation would fetch an article to rename, say, a release's PAR2 index
// from a hash to a synthetic hash, which helps nobody.
func isUsableFilename(name string) bool {
	name = strings.TrimSpace(name)
	if name == "" {
		return false
	}
	if filepath.Ext(name) == "" {
		return false
	}
	if isKnownAuxiliaryFile(name) {
		return true
	}
	if !IsVideoFile(name) && !IsArchiveFile(name) {
		return false
	}
	return !looksObfuscatedFilename(name)
}

// looksObfuscatedFilename reports whether a filename's stem is a random token
// rather than something a person wrote: a hex hash, a long run of hex and dots,
// or a long unbroken alphanumeric string. It decides both whether a recovered
// name is worth keeping and whether an episode number parsed out of a name can
// be trusted.
func looksObfuscatedFilename(filename string) bool {
	stem := strings.TrimSpace(filename)
	if stem == "" {
		return false
	}
	if dot := strings.LastIndexByte(stem, '.'); dot > 0 {
		stem = stem[:dot]
	}
	if stem == "" {
		return false
	}
	if strings.HasPrefix(strings.ToLower(stem), "abc.xyz") {
		return true
	}
	lower := strings.ToLower(stem)
	if len(lower) == 32 && isHex(lower) {
		return true
	}
	if len(lower) >= 40 {
		hexOrDot := true
		for _, c := range lower {
			if (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == '.' {
				continue
			}
			hexOrDot = false
			break
		}
		if hexOrDot {
			return true
		}
	}
	if len(stem) >= 24 && !strings.ContainsAny(stem, ". -_") {
		alphaNum := true
		for _, c := range stem {
			if (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') {
				continue
			}
			alphaNum = false
			break
		}
		if alphaNum {
			return true
		}
	}
	return false
}

func isHex(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') {
			continue
		}
		return false
	}
	return true
}

// isKnownAuxiliaryFile reports the non-payload files a release ships alongside
// its media. They are named well enough to skip; the payload is what matters.
func isKnownAuxiliaryFile(name string) bool {
	switch strings.ToLower(filepath.Ext(name)) {
	case ExtPar2, ExtNfo, ExtNzb, ".srt", ".sub", ".idx", ".jpg", ".png", ".txt":
		return true
	}
	return false
}

// readFileEntries reads each pending file's header, in parallel, and classifies
// it by signature.
func readFileEntries(ctx context.Context, files []UnpackableFile, pending []int) []*fileEntry {
	entries := make([]*fileEntry, len(pending))
	sem := make(chan struct{}, deobfuscationReadConcurrency)
	var wg sync.WaitGroup

	for slot, idx := range pending {
		if err := contextErr(ctx); err != nil {
			break
		}
		wg.Add(1)
		sem <- struct{}{}
		go func(slot, idx int) {
			defer wg.Done()
			defer func() { <-sem }()
			f := files[idx]
			header, err := readFileHeader(ctx, f)
			if err != nil {
				logger.Debug("Deobfuscation could not read file header",
					"subject", f.Name(), "err", err)
				return
			}
			entry := &fileEntry{
				idx:     idx,
				file:    f,
				subject: ExtractFilename(f.Name()),
				header:  header,
				kind:    detectContentKind(header),
				rar:     rarHeaderInfo{volume: -1},
			}
			if entry.kind == contentRAR {
				if info, ok := parseRARHeader(header); ok {
					entry.rar = info
				}
			}
			entries[slot] = entry
		}(slot, idx)
	}
	wg.Wait()

	out := entries[:0]
	for _, e := range entries {
		if e != nil {
			out = append(out, e)
		}
	}
	return out
}

// resolveNamesFromPAR2 names files by matching each one's first-16-KiB MD5
// against the PAR2 set's FileDesc packets. A PAR2 file describes the release as
// it was before the poster renamed anything, so this is the only source that
// recovers a *real* name, and matching on content means a mismatched or
// reordered NZB can never produce a wrong one.
func resolveNamesFromPAR2(ctx context.Context, files []UnpackableFile, entries []*fileEntry, names map[int]string) {
	sources := par2Sources(files, entries)
	if len(sources) == 0 {
		return
	}
	meta, err := parsePAR2Metadata(ctx, sources)
	if err != nil || meta == nil || len(meta.files) == 0 {
		logger.Debug("Deobfuscation found no usable PAR2 metadata", "par2_files", len(sources), "err", err)
		return
	}

	byHash := make(map[[16]byte]*par2FileMeta, len(meta.files))
	for _, fm := range meta.files {
		if fm.name != "" {
			byHash[fm.hash16k] = fm
		}
	}
	matched := 0
	for _, e := range entries {
		if e.kind == contentPAR2 {
			continue
		}
		fm, ok := byHash[md5.Sum(hashable16k(e.header))]
		if !ok {
			continue
		}
		name := filepath.Base(strings.TrimSpace(fm.name))
		if !isUsableFilename(name) {
			// The set was renamed before its PAR2 was built, so the recovery
			// data repeats the obfuscated names. Later tiers do better.
			continue
		}
		names[e.idx] = name
		matched++
	}
	if matched > 0 {
		logger.Debug("Deobfuscation matched names from PAR2 FileDesc packets",
			"matched", matched, "par2_entries", len(meta.files))
	}
}

// par2Sources returns the smallest PAR2 file available, preferring one named
// ".par2" and falling back to one identified by signature. The smallest volume
// is enough: PAR2 repeats every critical packet — the file list included — in
// each volume, so the index file carries the whole description without pulling
// megabytes of recovery slices.
func par2Sources(files []UnpackableFile, entries []*fileEntry) []UnpackableFile {
	if named := collectPAR2Files(files); len(named) > 0 {
		return named[:1]
	}
	var smallest UnpackableFile
	for _, e := range entries {
		if e.kind != contentPAR2 {
			continue
		}
		if smallest == nil || e.file.Size() < smallest.Size() {
			smallest = e.file
		}
	}
	if smallest == nil {
		return nil
	}
	return []UnpackableFile{smallest}
}

// hashable16k trims a header to what PAR2 hashes: the file's first 16 KiB, or
// the whole file when it is shorter.
func hashable16k(header []byte) []byte {
	if len(header) > contentSignatureBytes {
		return header[:contentSignatureBytes]
	}
	return header
}

// resolveNamesFromYenc falls back to the filename the poster's uploader wrote
// into the articles themselves. It costs nothing extra: the article carrying
// the header is the one already fetched for the signature check.
func resolveNamesFromYenc(ctx context.Context, entries []*fileEntry, names map[int]string) {
	matched := 0
	for _, e := range entries {
		if _, done := names[e.idx]; done {
			continue
		}
		namer, ok := e.file.(yencNamer)
		if !ok {
			continue
		}
		raw, err := namer.YencFileName(ctx)
		if err != nil {
			logger.Debug("Deobfuscation could not read a yEnc filename", "subject", e.subject, "err", err)
			continue
		}
		name := filepath.Base(strings.TrimSpace(raw))
		if name == "" || !isUsableFilename(name) {
			continue
		}
		names[e.idx] = name
		matched++
	}
	if matched > 0 {
		logger.Debug("Deobfuscation matched names from yEnc headers", "matched", matched)
	}
}

// resolveNamesFromSignatures names whatever is left from its content alone.
// The name is synthetic — the real one is gone — but it carries the two facts
// the pipeline needs: what the file is, and where it sits in its volume set.
func resolveNamesFromSignatures(entries []*fileEntry, names map[int]string) {
	var rarVolumes, sevenZip, unknown []*fileEntry
	par2Count := 0
	for _, e := range entries {
		if _, done := names[e.idx]; done {
			continue
		}
		switch e.kind {
		case contentRAR:
			rarVolumes = append(rarVolumes, e)
		case contentPAR2:
			par2Count++
			names[e.idx] = fmt.Sprintf("%s.%03d%s", synthesizedStem, par2Count, ExtPar2)
		case content7z:
			sevenZip = append(sevenZip, e)
		case contentUnknown:
			unknown = append(unknown, e)
		default:
			// A standalone media file keeps its own stem: it identifies the
			// file for the viewer, and unlike a volume set nothing needs the
			// files of the release to share a prefix.
			names[e.idx] = stemOrDefault(e.subject) + e.kind.ext()
		}
	}
	for order, e := range orderRARVolumes(rarVolumes) {
		names[e.idx] = fmt.Sprintf("%s.part%03d%s", synthesizedStem, order+1, ExtRar)
	}
	nameSplit7zParts(sevenZip, unknown, rarVolumes, names)
	if len(rarVolumes) > 0 || len(sevenZip) > 0 || par2Count > 0 {
		logger.Debug("Deobfuscation named files by content signature",
			"rar_volumes", len(rarVolumes), "7z_parts", len(sevenZip), "par2_files", par2Count)
	}
}

// nameSplit7zParts names a split 7z set. Only its first part carries the 7z
// signature — the continuations are raw payload with no magic of their own — so
// the leftover unidentified files behind a lone 7z header are its remaining
// parts, in NZB order. A set with more than one signature, or one sharing the
// release with RAR volumes, is not that shape and gets no guesswork.
func nameSplit7zParts(sevenZip, unknown, rarVolumes []*fileEntry, names map[int]string) {
	if len(sevenZip) == 0 {
		return
	}
	if len(sevenZip) > 1 || len(rarVolumes) > 0 {
		// Separate archives, not one split set: give each its own prefix so the
		// 7z identifier does not merge them and no two files collide on a name.
		for i, e := range sevenZip {
			names[e.idx] = fmt.Sprintf("%s%03d%s", synthesizedStem, i+1, Ext7z)
		}
		return
	}
	names[sevenZip[0].idx] = fmt.Sprintf("%s%s.001", synthesizedStem, Ext7z)
	for i, e := range unknown {
		names[e.idx] = fmt.Sprintf("%s%s.%03d", synthesizedStem, Ext7z, i+2)
	}
}

// stemOrDefault strips any extension from a subject-derived name, falling back
// to the synthesized stem when nothing usable is left.
func stemOrDefault(name string) string {
	base := filepath.Base(strings.TrimSpace(name))
	stem := strings.TrimSuffix(base, filepath.Ext(base))
	if stem == "" || stem == "." {
		return synthesizedStem
	}
	return stem
}

// orderRARVolumes puts a set of anonymous RAR volumes back in order. RAR5
// records each volume's number in its own header, which is authoritative;
// RAR4 records only which volume is first, so the rest keep NZB order behind
// it — the order the poster uploaded them in.
func orderRARVolumes(volumes []*fileEntry) []*fileEntry {
	ordered := make([]*fileEntry, len(volumes))
	copy(ordered, volumes)
	sort.SliceStable(ordered, func(i, j int) bool {
		a, b := ordered[i].rar, ordered[j].rar
		if a.volume >= 0 && b.volume >= 0 {
			return a.volume < b.volume
		}
		if a.isFirst != b.isFirst {
			return a.isFirst
		}
		return false // sort.SliceStable keeps NZB order for the rest
	})
	return ordered
}

func sampleNames(files []UnpackableFile, names map[int]string, limit int) []string {
	out := make([]string, 0, limit)
	for idx := range names {
		if len(out) >= limit {
			break
		}
		out = append(out, ExtractFilename(files[idx].Name()))
	}
	sort.Strings(out)
	return out
}

// yencNamer is the optional capability a segment-backed file offers for
// reading the poster's yEnc filename.
type yencNamer interface {
	YencFileName(ctx context.Context) (string, error)
}

// originalNamer is implemented by files whose name was rewritten here, so a
// blueprint can be serialized against the release's real NZB subjects and
// re-link on replay even though the rename is not persisted.
type originalNamer interface {
	OriginalName() string
}

// originalName returns the NZB subject behind a possibly-renamed file.
func originalName(f UnpackableFile) string {
	if o, ok := f.(originalNamer); ok {
		return o.OriginalName()
	}
	if f == nil {
		return ""
	}
	return f.Name()
}

// renamedFile presents a release file under its recovered name. Every stage
// downstream routes on the name — the RAR scanner, the 7z identifier, the PAR2
// collector, the direct-media selector — so rewriting it here is what makes an
// obfuscated release look ordinary to all of them.
//
// It forwards every optional capability the segment layer offers rather than
// relying on embedding: those interfaces are discovered by type assertion, and
// a wrapper that quietly failed one of them would disable read-ahead or
// segment-map priming with no error to show for it.
type renamedFile struct {
	UnpackableFile
	name string
}

var (
	_ UnpackableFile                = (*renamedFile)(nil)
	_ originalNamer                 = (*renamedFile)(nil)
	_ contextAwareSegmentMapEnsurer = (*renamedFile)(nil)
	_ uniformSegmentMapPrimer       = (*renamedFile)(nil)
	_ segmentMapSnapshotter         = (*renamedFile)(nil)
	_ playbackReaderAt              = (*renamedFile)(nil)
	_ playbackPrefetcher            = (*renamedFile)(nil)
	_ playbackRangePrefetcher       = (*renamedFile)(nil)
	_ interface{ IsFailed() bool }  = (*renamedFile)(nil)
	_ yencNamer                     = (*renamedFile)(nil)
	_ firstSegmentReader            = (*renamedFile)(nil)
)

func (f *renamedFile) Name() string         { return f.name }
func (f *renamedFile) OriginalName() string { return f.UnpackableFile.Name() }

func (f *renamedFile) EnsureSegmentMapCtx(ctx context.Context) error {
	return ensureSegmentMap(ctx, f.UnpackableFile)
}

func (f *renamedFile) PrimeUniformSegmentMapFromEstimator() bool {
	if p, ok := f.UnpackableFile.(uniformSegmentMapPrimer); ok {
		return p.PrimeUniformSegmentMapFromEstimator()
	}
	return false
}

func (f *renamedFile) SegmentMapSnapshotJSON() ([]byte, bool) {
	if s, ok := f.UnpackableFile.(segmentMapSnapshotter); ok {
		return s.SegmentMapSnapshotJSON()
	}
	return nil, false
}

func (f *renamedFile) RestoreSegmentMapJSON(data []byte) bool {
	if s, ok := f.UnpackableFile.(segmentMapSnapshotter); ok {
		return s.RestoreSegmentMapJSON(data)
	}
	return false
}

func (f *renamedFile) ReadAtCtx(ctx context.Context, p []byte, off int64) (int, error) {
	if r, ok := f.UnpackableFile.(readerAtCtx); ok {
		return r.ReadAtCtx(ctx, p, off)
	}
	return f.UnpackableFile.ReadAt(p, off)
}

func (f *renamedFile) OpenPlaybackReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	return openPlaybackReaderAt(f.UnpackableFile, ctx, offset)
}

// Forwarded explicitly: renamedFile embeds the UnpackableFile interface, so a
// capability the wrapped file offers beyond that interface is not promoted and
// a type assertion on the wrapper would silently miss it.
func (f *renamedFile) OpenPlaybackStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	return openPlaybackStream(f.UnpackableFile, ctx)
}

func (f *renamedFile) PrefetchPlaybackOffset(ctx context.Context, offset int64) {
	if p, ok := f.UnpackableFile.(playbackPrefetcher); ok {
		p.PrefetchPlaybackOffset(ctx, offset)
	}
}

func (f *renamedFile) PrefetchPlaybackRange(ctx context.Context, offset, length int64) {
	if p, ok := f.UnpackableFile.(playbackRangePrefetcher); ok {
		p.PrefetchPlaybackRange(ctx, offset, length)
	}
}

func (f *renamedFile) IsFailed() bool {
	if c, ok := f.UnpackableFile.(interface{ IsFailed() bool }); ok {
		return c.IsFailed()
	}
	return false
}

func (f *renamedFile) YencFileName(ctx context.Context) (string, error) {
	if n, ok := f.UnpackableFile.(yencNamer); ok {
		return n.YencFileName(ctx)
	}
	return "", nil
}

func (f *renamedFile) ReadFirstSegment(ctx context.Context) ([]byte, error) {
	if r, ok := f.UnpackableFile.(firstSegmentReader); ok {
		return r.ReadFirstSegment(ctx)
	}
	buf := make([]byte, contentSignatureBytes)
	n, err := f.UnpackableFile.ReadAt(buf, 0)
	if n > 0 {
		return buf[:n], nil
	}
	return nil, err
}
