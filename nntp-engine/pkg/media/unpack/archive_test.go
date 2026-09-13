package unpack

import (
	"context"
	"errors"
	"fmt"
	"io"
	"testing"
)

func TestGetMediaStreamForEpisodeSkipsCachedDirectBlueprintForDifferentTarget(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "Show.S01E01.mkv", data: []byte("ep1")},
		&memoryUnpackableFile{name: "Show.S01E04.mkv", data: []byte("ep4")},
	}
	cachedBP := &DirectBlueprint{FileName: "Show.S01E04.mkv", FileIndex: 1, Target: EpisodeTarget{Season: 1, Episode: 4}}

	stream, name, _, bp, err := GetMediaStreamForEpisode(context.Background(), files, cachedBP, "", EpisodeTarget{Season: 1, Episode: 1})
	if err != nil {
		t.Fatalf("GetMediaStreamForEpisode returned error: %v", err)
	}
	defer stream.Close()

	if name != "Show.S01E01.mkv" {
		t.Fatalf("expected requested episode file, got %q", name)
	}
	if bp == cachedBP {
		t.Fatal("expected cached direct blueprint to be replaced")
	}
	data, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("failed to read stream: %v", err)
	}
	if string(data) != "ep1" {
		t.Fatalf("expected episode 1 stream data, got %q", string(data))
	}
}

func TestGetMediaStreamForEpisodeSkipsCachedFailureForDifferentTarget(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "Show.S01E01.mkv", data: []byte("ep1")},
	}
	cachedBP := &FailedBlueprint{Err: io.EOF, Target: EpisodeTarget{Season: 1, Episode: 4}}

	stream, name, _, bp, err := GetMediaStreamForEpisode(context.Background(), files, cachedBP, "", EpisodeTarget{Season: 1, Episode: 1})
	if err != nil {
		t.Fatalf("GetMediaStreamForEpisode returned error: %v", err)
	}
	defer stream.Close()

	if name != "Show.S01E01.mkv" {
		t.Fatalf("expected requested episode file, got %q", name)
	}
	if bp == cachedBP {
		t.Fatal("expected cached failed blueprint to be replaced")
	}
}

func TestGetMediaStreamForEpisodeFailsWhenRequestedEpisodeMissingFromDirectFiles(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "Show.S01E04.mkv", data: []byte("ep4")},
		&memoryUnpackableFile{name: "Show.S01E06.mkv", data: []byte("ep6")},
	}

	stream, name, _, _, err := GetMediaStreamForEpisode(context.Background(), files, nil, "", EpisodeTarget{Season: 1, Episode: 1})
	if err == nil {
		if stream != nil {
			stream.Close()
		}
		t.Fatal("expected missing-episode error")
	}
	if !errors.Is(err, ErrEpisodeTargetNotFound) {
		t.Fatalf("expected ErrEpisodeTargetNotFound, got %v", err)
	}
	if name != "" {
		t.Fatalf("expected no selected file, got %q", name)
	}
	if stream != nil {
		t.Fatal("expected no stream on missing episode")
	}
}

func TestGetMediaStreamRejectsSuspiciousLargestDirectFallback(t *testing.T) {
	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "release.par2", data: []byte("par2")},
			size:                 90 * 1024 * 1024,
		},
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "0)", data: []byte("bad")},
			size:                 80 * 1024 * 1024,
		},
	}

	stream, name, _, _, err := GetMediaStreamForEpisode(context.Background(), files, nil, "", EpisodeTarget{})
	if err == nil {
		if stream != nil {
			stream.Close()
		}
		t.Fatal("expected suspicious direct fallback to be rejected")
	}
	if !errors.Is(err, io.EOF) {
		t.Fatalf("expected io.EOF, got %v", err)
	}
	if name != "" {
		t.Fatalf("expected no selected file, got %q", name)
	}
	if stream != nil {
		t.Fatal("expected no stream for suspicious direct fallback")
	}
}

func TestGetMediaStreamAllowsPlausibleLargestDirectFallback(t *testing.T) {
	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "abc12345", data: []byte("video")},
			size:                 80 * 1024 * 1024,
		},
	}

	stream, name, _, _, err := GetMediaStreamForEpisode(context.Background(), files, nil, "", EpisodeTarget{})
	if err != nil {
		t.Fatalf("expected plausible direct fallback to succeed, got %v", err)
	}
	defer stream.Close()

	if name != "abc12345" {
		t.Fatalf("expected plausible fallback name, got %q", name)
	}
}

func TestGetMediaStreamForEpisodeWithHintsRefusesLargestDirectFallbackWhenDisabled(t *testing.T) {
	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "abc12345", data: []byte("video")},
			size:                 80 * 1024 * 1024,
		},
	}

	stream, name, _, _, err := GetMediaStreamForEpisodeWithHints(
		context.Background(),
		files,
		nil,
		"",
		EpisodeTarget{},
		StreamSelectionHints{AllowLargestDirectFallback: false},
	)
	if err == nil {
		if stream != nil {
			stream.Close()
		}
		t.Fatal("expected disabled largest direct fallback to fail")
	}
	if !errors.Is(err, io.EOF) {
		t.Fatalf("expected io.EOF, got %v", err)
	}
	if name != "" {
		t.Fatalf("expected no selected file, got %q", name)
	}
	if stream != nil {
		t.Fatal("expected no stream when fallback is disabled")
	}
}

func TestGetMediaStreamForEpisodeAllowsLargestDirectFallbackWhenHinted(t *testing.T) {
	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "abc12345", data: []byte("video")},
			size:                 80 * 1024 * 1024,
		},
	}

	stream, name, _, _, err := GetMediaStreamForEpisodeWithHints(
		context.Background(),
		files,
		nil,
		"",
		EpisodeTarget{Season: 1, Episode: 1},
		StreamSelectionHints{AllowLargestDirectFallback: true},
	)
	if err != nil {
		t.Fatalf("expected hinted targeted fallback to succeed, got %v", err)
	}
	defer stream.Close()

	if name != "abc12345" {
		t.Fatalf("expected plausible fallback name, got %q", name)
	}
}

func TestGetMediaStreamForEpisodeNamesUnknownFileFromItsSignature(t *testing.T) {
	// An extension-less file whose first bytes are an EBML header: the
	// signature scan names it, so it is selected as direct media rather than
	// having to reach the content probe.
	data := append([]byte{0x1A, 0x45, 0xDF, 0xA3}, make([]byte, 1024)...)
	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "abc12345", data: data},
			size:                 80 * 1024 * 1024,
		},
	}

	stream, name, _, _, err := GetMediaStreamForEpisodeWithHints(
		context.Background(),
		files,
		nil,
		"",
		EpisodeTarget{},
		StreamSelectionHints{AllowLargestDirectFallback: false},
	)
	if err != nil {
		t.Fatalf("expected signature-named candidate to succeed, got %v", err)
	}
	defer stream.Close()

	if name != "abc12345.mkv" {
		t.Fatalf("expected the recovered container extension on the original stem, got %q", name)
	}
}

func TestGetMediaStreamForEpisodeSelectsUnknownNameByContentProbe(t *testing.T) {
	// The EBML header sits past offset 0, so the signature scan cannot name the
	// file and selection has to fall through to the content probe.
	data := append(make([]byte, 64), []byte{0x1A, 0x45, 0xDF, 0xA3}...)
	data = append(data, make([]byte, 1024)...)
	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: "abc12345", data: data},
			size:                 80 * 1024 * 1024,
		},
	}

	stream, name, _, _, err := GetMediaStreamForEpisodeWithHints(
		context.Background(),
		files,
		nil,
		"",
		EpisodeTarget{},
		StreamSelectionHints{AllowLargestDirectFallback: false},
	)
	if err != nil {
		t.Fatalf("expected content probe fallback to succeed, got %v", err)
	}
	defer stream.Close()

	if name != "abc12345" {
		t.Fatalf("expected probed candidate name, got %q", name)
	}
}

func TestPlausibleLargestDirectFallbackAllowsCommonReleasePunctuation(t *testing.T) {
	if !isPlausibleLargestDirectFallbackName("Movie, Title '11 [1080p]") {
		t.Fatal("expected common release punctuation to remain plausible")
	}
}

// A compressed archive is a permanent property of the release, so the fast-probe
// caveat — which exists for scans that may simply not have looked hard enough —
// must not be stamped onto it. Marking it makes the caller fail open and the
// release comes back on every search forever.
func TestMaybeMarkArchiveFastProbeLeavesDefinitiveVerdictsUnmarked(t *testing.T) {
	compressed := fmt.Errorf("compressed RAR archive (file: %s): %w", "obfuscated.mkv", ErrCompressedArchive)

	got := maybeMarkArchiveFastProbe(context.Background(), compressed)
	if errors.Is(got, ErrArchiveFastProbe) {
		t.Fatalf("compressed-archive verdict was softened into a fast-probe result: %v", got)
	}
	if !errors.Is(got, ErrCompressedArchive) {
		t.Fatalf("expected ErrCompressedArchive to survive, got %v", got)
	}
}

func TestMaybeMarkArchiveFastProbeStillMarksInconclusiveScanFailures(t *testing.T) {
	got := maybeMarkArchiveFastProbe(context.Background(), errors.New("RAR header scan failed on 2 volume(s)"))
	if !errors.Is(got, ErrArchiveFastProbe) {
		t.Fatalf("expected ErrArchiveFastProbe on an inconclusive scan failure, got %v", got)
	}
}

func TestMaybeMarkArchiveFastProbePassesNilThrough(t *testing.T) {
	if err := maybeMarkArchiveFastProbe(context.Background(), nil); err != nil {
		t.Fatalf("expected nil, got %v", err)
	}
}

// warmableStubFile records what a tail warm asks of a volume.
type warmableStubFile struct {
	stubFile
	ensured    int
	prefetches [][2]int64
	err        error
}

func (f *warmableStubFile) EnsureSegmentMapCtx(context.Context) error {
	f.ensured++
	return f.err
}

func (f *warmableStubFile) PrefetchPlaybackRange(_ context.Context, offset, length int64) {
	f.prefetches = append(f.prefetches, [2]int64{offset, length})
}

func TestWarmPlaybackTailMapsAndPullsTheVolumeTheMediaEndsIn(t *testing.T) {
	head := &warmableStubFile{stubFile: stubFile{name: "x.part01.rar", size: 1000}}
	tail := &warmableStubFile{stubFile: stubFile{name: "x.part02.rar", size: 64 << 20}}
	bp := &ArchiveBlueprint{
		MainFileName: "movie.mkv",
		Parts: []VirtualPartDef{
			{VirtualStart: 0, VirtualEnd: 999, VolFile: head},
			{VirtualStart: 1000, VirtualEnd: 1999, VolFile: tail},
		},
	}
	if !WarmPlaybackTail(context.Background(), bp, nil) {
		t.Fatal("expected the tail volume to be warmed")
	}
	if tail.ensured != 1 {
		t.Fatalf("tail volume mapped %d times, want 1", tail.ensured)
	}
	if len(tail.prefetches) != 1 {
		t.Fatalf("tail volume prefetched %d ranges, want 1", len(tail.prefetches))
	}
	if got := tail.prefetches[0]; got[0] != tail.Size()-playbackTailWarmBytes || got[1] != playbackTailWarmBytes {
		t.Fatalf("prefetched %v, want the last %d bytes", got, int64(playbackTailWarmBytes))
	}
	// The head is already mapped and being read by startup; warming it again
	// would spend articles the opening bytes need.
	if head.ensured != 0 || len(head.prefetches) != 0 {
		t.Fatalf("head volume was warmed: ensured=%d prefetches=%d", head.ensured, len(head.prefetches))
	}
}

// A directly-posted release has no volumes to map — its cost is the cold
// articles at the end of the one file, which is what the player reads first.
func TestWarmPlaybackTailPullsTheEndOfADirectFile(t *testing.T) {
	other := &warmableStubFile{stubFile: stubFile{name: "sample.mkv", size: 1 << 20}}
	media := &warmableStubFile{stubFile: stubFile{name: "movie.mkv", size: 32 << 20}}
	bp := &DirectBlueprint{FileName: "movie.mkv", FileIndex: 1}

	if !WarmPlaybackTail(context.Background(), bp, []UnpackableFile{other, media}) {
		t.Fatal("expected the direct file to be warmed")
	}
	if len(media.prefetches) != 1 {
		t.Fatalf("media prefetched %d ranges, want 1", len(media.prefetches))
	}
	if got := media.prefetches[0]; got[0] != media.Size()-playbackTailWarmBytes {
		t.Fatalf("prefetched from %d, want the last %d bytes", got[0], int64(playbackTailWarmBytes))
	}
	if len(other.prefetches) != 0 {
		t.Fatal("warmed a file the blueprint does not play")
	}
}

// The recorded index belongs to the file list the blueprint was built from; a
// replay rebuilds that list, so the name has to win.
func TestWarmPlaybackTailResolvesDirectFileByName(t *testing.T) {
	media := &warmableStubFile{stubFile: stubFile{name: "movie.mkv", size: 32 << 20}}
	decoy := &warmableStubFile{stubFile: stubFile{name: "extras.mkv", size: 32 << 20}}
	bp := &DirectBlueprint{FileName: "movie.mkv", FileIndex: 1}

	if !WarmPlaybackTail(context.Background(), bp, []UnpackableFile{media, decoy}) {
		t.Fatal("expected the named file to be warmed")
	}
	if len(media.prefetches) != 1 || len(decoy.prefetches) != 0 {
		t.Fatalf("warm followed the stale index: media=%d decoy=%d", len(media.prefetches), len(decoy.prefetches))
	}
}

func TestWarmPlaybackTailIsBestEffort(t *testing.T) {
	failing := &warmableStubFile{stubFile: stubFile{name: "x.part01.rar"}, err: errors.New("no article")}
	bp := &ArchiveBlueprint{Parts: []VirtualPartDef{{VolFile: failing}}}
	if WarmPlaybackTail(context.Background(), bp, nil) {
		t.Fatal("a failed warm must report itself, not claim success")
	}
	if len(failing.prefetches) != 0 {
		t.Fatal("prefetched a volume whose map could not be built")
	}
	if WarmPlaybackTail(context.Background(), &ArchiveBlueprint{}, nil) {
		t.Fatal("a blueprint with no parts has no tail to warm")
	}
	if WarmPlaybackTail(context.Background(), &DirectBlueprint{FileName: "gone.mkv"}, nil) {
		t.Fatal("a direct blueprint with no matching file has nothing to warm")
	}
	if WarmPlaybackTail(context.Background(), &FailedBlueprint{}, nil) {
		t.Fatal("a failed blueprint has nothing to warm")
	}
}
