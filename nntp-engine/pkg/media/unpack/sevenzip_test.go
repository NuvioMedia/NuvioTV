package unpack

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
)

type nopReadSeekCloser struct {
	*bytes.Reader
}

func (n *nopReadSeekCloser) Close() error { return nil }

type memoryUnpackableFile struct {
	name string
	data []byte
}

type sizedUnpackableFile struct {
	*memoryUnpackableFile
	size         int64
	resolvedSize int64
	ensureCalls  int
}

func (f *memoryUnpackableFile) Name() string { return f.name }

func (f *memoryUnpackableFile) Size() int64 { return int64(len(f.data)) }

func (f *memoryUnpackableFile) EnsureSegmentMap() error { return nil }

func (f *memoryUnpackableFile) OpenStream() (io.ReadSeekCloser, error) {
	return f.OpenStreamCtx(context.Background())
}

func (f *memoryUnpackableFile) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	return &nopReadSeekCloser{Reader: bytes.NewReader(f.data)}, nil
}

func (f *memoryUnpackableFile) OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	if offset < 0 {
		offset = 0
	}
	if offset > int64(len(f.data)) {
		offset = int64(len(f.data))
	}
	return io.NopCloser(bytes.NewReader(f.data[offset:])), nil
}

func (f *memoryUnpackableFile) ReadAt(p []byte, off int64) (int, error) {
	return bytes.NewReader(f.data).ReadAt(p, off)
}

func (f *sizedUnpackableFile) Size() int64 { return f.size }

func (f *sizedUnpackableFile) EnsureSegmentMap() error {
	f.ensureCalls++
	f.size = f.resolvedSize
	return nil
}

func TestFilesToPartsSplitSevenZipProbesFirstMiddleAndLastOnly(t *testing.T) {
	first := &sizedUnpackableFile{
		memoryUnpackableFile: &memoryUnpackableFile{name: "release.7z.001"},
		size:                 110,
		resolvedSize:         100,
	}
	middle := &sizedUnpackableFile{
		memoryUnpackableFile: &memoryUnpackableFile{name: "release.7z.002"},
		size:                 130,
		resolvedSize:         120,
	}
	extraMiddle := &sizedUnpackableFile{
		memoryUnpackableFile: &memoryUnpackableFile{name: "release.7z.003"},
		size:                 130,
		resolvedSize:         120,
	}
	last := &sizedUnpackableFile{
		memoryUnpackableFile: &memoryUnpackableFile{name: "release.7z.004"},
		size:                 90,
		resolvedSize:         80,
	}

	ctx := context.Background()
	parts, err := filesToParts(ctx, []UnpackableFile{first, middle, extraMiddle, last})
	if err != nil {
		t.Fatalf("filesToParts returned error: %v", err)
	}

	if len(parts) != 4 {
		t.Fatalf("expected 4 parts, got %d", len(parts))
	}
	if parts[0].Size != 100 || parts[1].Size != 120 || parts[2].Size != 120 || parts[3].Size != 80 {
		t.Fatalf("expected part sizes [100 120 120 80], got [%d %d %d %d]", parts[0].Size, parts[1].Size, parts[2].Size, parts[3].Size)
	}
	// First, one leading middle, one trailing middle (the uniformity check)
	// and last are probed — exactly once each, however many middles sit
	// between them.
	if first.ensureCalls != 1 || middle.ensureCalls != 1 || extraMiddle.ensureCalls != 1 || last.ensureCalls != 1 {
		t.Fatalf("expected one probe each, got first=%d middle=%d extra=%d last=%d",
			first.ensureCalls, middle.ensureCalls, extraMiddle.ensureCalls, last.ensureCalls)
	}
}

func TestFilesToPartsMeasuresAllMiddlesWhenSplitIsNotUniform(t *testing.T) {
	mk := func(name string, size, resolved int64) *sizedUnpackableFile {
		return &sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: name},
			size:                 size,
			resolvedSize:         resolved,
		}
	}
	first := mk("release.7z.001", 110, 100)
	m1 := mk("release.7z.002", 130, 120)
	m2 := mk("release.7z.003", 140, 125)
	m3 := mk("release.7z.004", 150, 130)
	last := mk("release.7z.005", 90, 80)

	parts, err := filesToParts(context.Background(), []UnpackableFile{first, m1, m2, m3, last})
	if err != nil {
		t.Fatalf("filesToParts returned error: %v", err)
	}
	want := []int64{100, 120, 125, 130, 80}
	for i, w := range want {
		if parts[i].Size != w {
			t.Fatalf("part %d size = %d, want %d (non-uniform middles must be measured, not painted)", i, parts[i].Size, w)
		}
	}
}

func TestValidateSplit7zPartNamesDetectsSequenceGap(t *testing.T) {
	parts := []string{
		`[01/40] - "release.7z.001" yEnc`,
		`[03/40] - "release.7z.003" yEnc`,
	}

	err := validateSplit7zPartNames(parts)
	if err == nil {
		t.Fatal("expected sequence gap error")
	}
	if !strings.Contains(err.Error(), "expected part .002") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestValidateSplit7zPartNamesAcceptsComplete29VolumeSet(t *testing.T) {
	parts := make([]string, 29)
	for i := range parts {
		parts[i] = fmt.Sprintf(`[%.2d/40] - "release.7z.%03d" yEnc`, i+1, i+1)
	}

	if err := validateSplit7zPartNames(parts); err != nil {
		t.Fatalf("expected complete 29-volume set to validate, got %v", err)
	}
}

func TestGetMediaStreamForEpisodeUsesCachedSevenZipBlueprintFiles(t *testing.T) {
	bp := &SevenZipBlueprint{
		MainFileName: "episode.mkv",
		TotalSize:    4,
		FileOffset:   2,
		Files: []UnpackableFile{
			&memoryUnpackableFile{name: "episode.7z.001", data: []byte("abcd")},
			&memoryUnpackableFile{name: "episode.7z.002", data: []byte("efgh")},
		},
	}

	stream, name, size, cached, err := GetMediaStreamForEpisode(context.Background(), nil, bp, "", EpisodeTarget{})
	if err != nil {
		t.Fatalf("GetMediaStreamForEpisode returned error: %v", err)
	}
	defer stream.Close()

	if name != "episode.mkv" {
		t.Fatalf("expected stream name %q, got %q", "episode.mkv", name)
	}
	if size != 4 {
		t.Fatalf("expected stream size 4, got %d", size)
	}
	if cached != bp {
		t.Fatal("expected cached blueprint to be returned")
	}

	data, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("failed to read cached 7z stream: %v", err)
	}
	if string(data) != "cdef" {
		t.Fatalf("expected mapped stream %q, got %q", "cdef", string(data))
	}
}

func TestGetMediaStreamForEpisodeSkipsCachedSevenZipBlueprintForDifferentTarget(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "Show.S01E01.mkv", data: []byte("ep1")},
		&memoryUnpackableFile{name: "Show.S01E04.mkv", data: []byte("ep4")},
	}
	cachedBP := &SevenZipBlueprint{
		MainFileName: "Show.S01E04.mkv",
		TotalSize:    3,
		FileOffset:   0,
		Files:        []UnpackableFile{&memoryUnpackableFile{name: "pack.7z.001", data: []byte("ep4")}},
		Target:       EpisodeTarget{Season: 1, Episode: 4},
	}

	stream, name, _, bp, err := GetMediaStreamForEpisode(context.Background(), files, cachedBP, "", EpisodeTarget{Season: 1, Episode: 1})
	if err != nil {
		t.Fatalf("GetMediaStreamForEpisode returned error: %v", err)
	}
	defer stream.Close()

	if name != "Show.S01E01.mkv" {
		t.Fatalf("expected requested episode file, got %q", name)
	}
	if bp == cachedBP {
		t.Fatal("expected cached 7z blueprint to be replaced")
	}
}

// "Nothing here looked like media" and "the media here is compressed" arrive at
// the same dead end, but only the second one proves the release will never play.
func TestSevenZipNoMediaErrOnlyBlamesCompressionWhenCompressedMediaWasSeen(t *testing.T) {
	compressed := sevenZipNoMediaErr("Show.S01E01.mkv")
	if !errors.Is(compressed, ErrCompressedArchive) {
		t.Fatalf("expected ErrCompressedArchive, got %v", compressed)
	}
	if !strings.Contains(compressed.Error(), "Show.S01E01.mkv") {
		t.Fatalf("expected the offending file name in %q", compressed.Error())
	}

	unrecognized := sevenZipNoMediaErr("")
	if errors.Is(unrecognized, ErrCompressedArchive) {
		t.Fatal("an archive with no recognizable media was blamed on compression")
	}
}

// headerStubFile is a stubFile whose first bytes are readable, for
// signature-identification tests.
type headerStubFile struct {
	stubFile
	header []byte
}

func (f *headerStubFile) ReadAt(p []byte, off int64) (int, error) {
	if off >= int64(len(f.header)) {
		return 0, nil
	}
	return copy(p, f.header[off:]), nil
}

func TestIdentify7zSplitPartsBySignature(t *testing.T) {
	sevenZip := []byte{'7', 'z', 0xBC, 0xAF, 0x27, 0x1C}
	files := []UnpackableFile{
		&headerStubFile{stubFile: stubFile{name: "b2.002", size: 100}},
		&headerStubFile{stubFile: stubFile{name: "b2.001", size: 100}, header: sevenZip},
		&stubFile{name: "b2.par2", size: 10},
		&stubFile{name: "readme.nfo", size: 1},
	}
	parts, err := Identify7zSplitPartsBySignature(context.Background(), files)
	if err != nil {
		t.Fatalf("Identify7zSplitPartsBySignature returned error: %v", err)
	}
	if len(parts) != 2 {
		t.Fatalf("identified %d parts, want 2", len(parts))
	}
	if ExtractFilename(parts[0].Name()) != "b2.001" {
		t.Fatalf("first part = %q, want b2.001", parts[0].Name())
	}

	// The same names fronting a RAR signature must not be claimed as 7z.
	rarFiles := []UnpackableFile{
		&headerStubFile{stubFile: stubFile{name: "b2.001", size: 100}, header: []byte("Rar!\x1a\x07\x01\x00")},
		&headerStubFile{stubFile: stubFile{name: "b2.002", size: 100}},
	}
	if _, err := Identify7zSplitPartsBySignature(context.Background(), rarFiles); err == nil {
		t.Fatal("a RAR-signature set must not identify as 7z")
	}
}

func TestSevenZipMediaCandidateAcceptsLargeExtensionlessNames(t *testing.T) {
	cases := []struct {
		name string
		size int64
		want bool
	}{
		{"movie.mkv", 10, true},                     // video by name, size irrelevant
		{"a9f3c2d1e4b78812", 200 << 20, true},       // obfuscated: size is the only signal
		{"BDMV/STREAM/00000.m2ts", 200 << 20, true}, // disc structure is video by name
		{"sample-movie.mkv", 200 << 20, false},      // samples never
		{"inner.rar", 200 << 20, false},             // a nested archive is not media
		{"inner.7z", 200 << 20, false},
		{"disc.iso", 200 << 20, false}, // iso is the nested path's business
		{"readme.nfo", 4 << 10, false}, // small and not video
	}
	for _, tc := range cases {
		if got := isSevenZipMediaCandidate(tc.name, tc.size); got != tc.want {
			t.Errorf("isSevenZipMediaCandidate(%q, %d) = %v, want %v", tc.name, tc.size, got, tc.want)
		}
	}
}
