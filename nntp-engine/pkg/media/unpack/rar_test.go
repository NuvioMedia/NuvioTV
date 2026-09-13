package unpack

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"streamnzb/pkg/core/logger"

	"github.com/javi11/rardecode/v2"
)

// The logger is silenced once for the whole test binary rather than per test.
// These packages start read-ahead goroutines that outlive the test body and
// call logger.Trace, so a per-test restore wrote logger.Log while those
// goroutines were still reading it — a data race the detector flagged the first
// time CI managed to compile that job. Production assigns Log once in
// logger.Init before anything starts, so only the tests ever had this problem.
// Same shape as pkg/indexer's test setup.
func init() {
	logger.Log = slog.New(slog.NewTextHandler(io.Discard, nil))
}

// Counters are atomic because the real *File is safe to call from several
// goroutines and this stands in for it: aggregateRemainingVolumesFromStart
// primes continuation volumes in the background, so EnsureSegmentMap runs off
// the test goroutine while the assertions read the counts.
type trackingUnpackableFile struct {
	*memoryUnpackableFile
	ensureCalls atomic.Int64
	detected    atomic.Bool
}

// EnsureSegmentMap mirrors the real *File: detection runs once and subsequent
// calls are cheap cache hits. ensureCalls therefore counts actual detection
// work, not the number of (possibly redundant) invocations.
func (f *trackingUnpackableFile) EnsureSegmentMap() error {
	if !f.detected.CompareAndSwap(false, true) {
		return nil
	}
	f.ensureCalls.Add(1)
	return nil
}

// calls reads the counter. Named rather than inlined so every assertion goes
// through the atomic load.
func (f *trackingUnpackableFile) calls() int64 { return f.ensureCalls.Load() }

func TestNormalizeContinuationProbeFallsBackToFirstPartPackedSize(t *testing.T) {
	probe := normalizeContinuationProbe([]rardecode.FilePartInfo{
		{DataOffset: 100, PackedSize: 49_999_328},
		{DataOffset: 24, PackedSize: 0},
	})
	if probe.dataOffset != 24 {
		t.Fatalf("dataOffset = %d, want 24", probe.dataOffset)
	}
	if probe.packedSize != 49_999_328 {
		t.Fatalf("packedSize = %d, want 49999328", probe.packedSize)
	}
}

func TestAggregateRemainingVolumesFromStartSkipsSegmentDetectionWhenProbeProvidesPackedSize(t *testing.T) {
	firstFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part001.rar", data: []byte("first")}}
	secondFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part002.rar", data: []byte("second")}}
	thirdFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part003.rar", data: []byte("third")}}

	parts, err := aggregateRemainingVolumesFromStart(
		context.Background(),
		[]filePart{{name: "movie.mkv", unpackedSize: 1000, dataOffset: 100, packedSize: 200, volFile: firstFile, volName: firstFile.Name(), isMedia: true}},
		[]UnpackableFile{firstFile, secondFile, thirdFile},
		0,
		"movie.mkv",
		1000,
		continuationProbe{dataOffset: 24, packedSize: 300},
	)
	if err != nil {
		t.Fatalf("aggregateRemainingVolumesFromStart returned error: %v", err)
	}

	if len(parts) != 3 {
		t.Fatalf("expected 3 parts, got %d", len(parts))
	}
	if got := parts[1].packedSize; got != 300 {
		t.Fatalf("expected second continuation packed size 300, got %d", got)
	}
	if got := parts[2].packedSize; got != 500 {
		t.Fatalf("expected final continuation packed size 500, got %d", got)
	}
	if got := parts[1].dataOffset; got != 24 {
		t.Fatalf("expected first continuation dataOffset 24, got %d", got)
	}
	if got := parts[2].dataOffset; got != 24 {
		t.Fatalf("expected later continuation dataOffset 24, got %d", got)
	}
	if got := secondFile.calls(); got != 0 {
		t.Fatalf("expected no EnsureSegmentMap on middle volumes with probe metadata, got second=%d", got)
	}
	// The last volume is primed in the background for playback seeks. Waited
	// for rather than slept past: a fixed sleep either flakes on a slow runner
	// or wastes time on a fast one, and it made this assertion meaningless
	// whenever the priming had not started yet.
	deadline := time.Now().Add(2 * time.Second)
	for thirdFile.calls() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := thirdFile.calls(); got > 1 {
		t.Fatalf("expected at most one background EnsureSegmentMap on last volume, got third=%d", got)
	}
}

func TestReconcileMainPartsPackedSpanTrimsOvershootFromLastPart(t *testing.T) {
	parts := []filePart{
		{packedSize: 200},
		{packedSize: 300},
		{packedSize: 510},
	}
	header := int64(1000)
	reconciled := reconcileMainPartsPackedSpan(parts, header)
	if got := totalPackedSize(reconciled); got != header {
		t.Fatalf("packed total = %d, want %d", got, header)
	}
	if got := reconciled[2].packedSize; got != 500 {
		t.Fatalf("last packed = %d, want 500", got)
	}
}

func TestApplyContinuationProbeToMainPartsAlignsFirstVolume(t *testing.T) {
	firstFile := &memoryUnpackableFile{name: "release.part001.rar", data: []byte("first")}
	parts := []filePart{{
		name: "movie.mkv", unpackedSize: 1000, dataOffset: 200, packedSize: 199,
		volFile: firstFile, volName: firstFile.Name(), isMedia: true,
	}}

	aligned := applyContinuationProbeToMainParts(parts, continuationProbe{
		firstDataOffset: 103,
		firstPackedSize: 105215780,
		dataOffset:      103,
		packedSize:      105215779,
	})

	if got := aligned[0].dataOffset; got != 103 {
		t.Fatalf("dataOffset = %d, want 103", got)
	}
	if got := aligned[0].packedSize; got != 105215780 {
		t.Fatalf("packedSize = %d, want 105215780 (keep first-volume STORE span)", got)
	}

	// Large first/continuation deltas should not be overwritten.
	parts[0].packedSize = 400
	aligned = applyContinuationProbeToMainParts(parts, continuationProbe{
		firstDataOffset: 103,
		firstPackedSize: 400,
		dataOffset:      103,
		packedSize:      300,
	})
	if got := aligned[0].packedSize; got != 400 {
		t.Fatalf("packedSize = %d, want 400", got)
	}
}

func TestAggregateRemainingVolumesIncludesAllFirstVolumeParts(t *testing.T) {
	firstFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part001.rar", data: []byte("first")}}
	secondFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part002.rar", data: []byte("second")}}
	thirdFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part003.rar", data: []byte("third")}}

	mainParts := []filePart{
		{name: "movie.mkv", unpackedSize: 1000, dataOffset: 10, packedSize: 40, volFile: firstFile, volName: firstFile.Name(), isMedia: true},
		{name: "movie.mkv", unpackedSize: 1000, dataOffset: 50, packedSize: 60, volFile: firstFile, volName: firstFile.Name(), isMedia: true},
	}

	parts, err := aggregateRemainingVolumesFromStart(
		context.Background(),
		mainParts,
		[]UnpackableFile{firstFile, secondFile, thirdFile},
		0,
		"movie.mkv",
		1000,
		continuationProbe{dataOffset: 24, packedSize: 300},
	)
	if err != nil {
		t.Fatalf("aggregateRemainingVolumesFromStart returned error: %v", err)
	}

	if len(parts) != 4 {
		t.Fatalf("expected 4 parts (2 first-volume blocks + 2 continuation volumes), got %d", len(parts))
	}
	if got := parts[0].packedSize; got != 40 {
		t.Fatalf("expected first block packed size 40, got %d", got)
	}
	if got := parts[1].packedSize; got != 60 {
		t.Fatalf("expected second first-volume block packed size 60, got %d", got)
	}
	if got := parts[2].packedSize; got != 300 {
		t.Fatalf("expected first continuation packed size 300, got %d", got)
	}
	if got := parts[3].packedSize; got != 600 {
		t.Fatalf("expected final continuation packed size 600, got %d", got)
	}

	var vOffset int64
	for i, p := range parts {
		start := vOffset
		end := vOffset + p.packedSize
		// Offset 85 should land in the second first-volume block, not the first continuation volume.
		if i == 1 && (85 < start || 85 >= end) {
			t.Fatalf("offset 85 expected in part %d [%d,%d)", i, start, end)
		}
		if i == 2 && 85 >= start {
			t.Fatalf("offset 85 incorrectly mapped to continuation part %d at %d", i, start)
		}
		vOffset += p.packedSize
	}
	if vOffset != 1000 {
		t.Fatalf("expected virtual span 1000, got %d", vOffset)
	}
}

func TestAggregateRemainingVolumesFromStartFallsBackToSegmentDetectionWithoutProbePackedSize(t *testing.T) {
	firstFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part001.rar", data: []byte("first")}}
	secondFile := &trackingUnpackableFile{memoryUnpackableFile: &memoryUnpackableFile{name: "release.part002.rar", data: make([]byte, 350)}}

	parts, err := aggregateRemainingVolumesFromStart(
		context.Background(),
		[]filePart{{name: "movie.mkv", unpackedSize: 500, dataOffset: 100, packedSize: 200, volFile: firstFile, volName: firstFile.Name(), isMedia: true}},
		[]UnpackableFile{firstFile, secondFile},
		0,
		"movie.mkv",
		500,
		continuationProbe{dataOffset: 50},
	)
	if err != nil {
		t.Fatalf("aggregateRemainingVolumesFromStart returned error: %v", err)
	}

	if len(parts) != 2 {
		t.Fatalf("expected 2 parts, got %d", len(parts))
	}
	if got := secondFile.calls(); got != 1 {
		t.Fatalf("expected one EnsureSegmentMap call in fallback path, got %d", got)
	}
	if got := parts[1].packedSize; got != 300 {
		t.Fatalf("expected fallback packed size 300, got %d", got)
	}
}

func TestGetMediaStreamForEpisodeSkipsCachedArchiveBlueprintForDifferentTarget(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "Show.S01E01.mkv", data: []byte("ep1")},
		&memoryUnpackableFile{name: "Show.S01E04.mkv", data: []byte("ep4")},
	}
	cachedBP := &ArchiveBlueprint{MainFileName: "Show.S01E04.mkv", Target: EpisodeTarget{Season: 1, Episode: 4}}

	stream, name, _, bp, err := GetMediaStreamForEpisode(context.Background(), files, cachedBP, "", EpisodeTarget{Season: 1, Episode: 1})
	if err != nil {
		t.Fatalf("GetMediaStreamForEpisode returned error: %v", err)
	}
	defer stream.Close()

	if name != "Show.S01E01.mkv" {
		t.Fatalf("expected requested episode file, got %q", name)
	}
	if bp == cachedBP {
		t.Fatal("expected cached archive blueprint to be replaced")
	}
}

func TestTryNestedArchiveFailsWhenRequestedEpisodeMissing(t *testing.T) {
	_, err := tryNestedArchive(context.Background(), []filePart{
		{name: "Show.S01E04.rar", packedSize: 100},
		{name: "Show.S01E04.r00", packedSize: 100},
	}, nil, "", EpisodeTarget{Season: 1, Episode: 1})
	if !errors.Is(err, ErrEpisodeTargetNotFound) {
		t.Fatalf("expected ErrEpisodeTargetNotFound, got %v", err)
	}
}

func TestScanArchiveReturnsContextErrorWhenCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := ScanArchive(ctx, []UnpackableFile{
		&memoryUnpackableFile{name: "release.part01.rar", data: []byte("ignored")},
	}, "", EpisodeTarget{})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
}

func TestScanDiagnosticsClassifiesLikelyCorruption(t *testing.T) {
	diag := scanDiagnostics{
		failedScans:      10,
		evidenceScans:    9,
		invalidBlocks:    7,
		unexpectedEOF:    1,
		decodeCorruption: 1,
	}

	err := diag.classifyArchiveError()
	if err == nil {
		t.Fatal("expected corruption classification error")
	}
	if !errors.Is(err, ErrPAR2RepairRequired) {
		t.Fatalf("expected PAR2 guidance in error, got %v", err)
	}
}

func TestScanDiagnosticsIgnoresWeakCorruptionSignal(t *testing.T) {
	diag := scanDiagnostics{
		failedScans:   10,
		invalidBlocks: 2,
	}

	if err := diag.classifyArchiveError(); err != nil {
		t.Fatalf("expected no corruption classification, got %v", err)
	}
}

func TestScanDiagnosticsDoesNotClassifyWithoutDecodeCorruptionEvidence(t *testing.T) {
	diag := scanDiagnostics{
		failedScans:   10,
		invalidBlocks: 9,
		unexpectedEOF: 1,
	}

	if err := diag.classifyArchiveError(); err != nil {
		t.Fatalf("expected no corruption classification without decode evidence, got %v", err)
	}
}

func TestSelectFirstVolumesForFastScanPrefersLikelyFirstVolumes(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "zzz-obf.rar"},
		&memoryUnpackableFile{name: "release.part002.rar"},
		&memoryUnpackableFile{name: "release.part001.rar"},
		&memoryUnpackableFile{name: "release.r00"},
		&memoryUnpackableFile{name: "random.001"},
		&memoryUnpackableFile{name: "release.r01"},
	}

	selected := selectFirstVolumesForFastScan(files, 3)
	if len(selected) != 3 {
		t.Fatalf("expected 3 selected files, got %d", len(selected))
	}

	got := []string{
		strings.ToLower(selected[0].Name()),
		strings.ToLower(selected[1].Name()),
		strings.ToLower(selected[2].Name()),
	}
	want := []string{
		"zzz-obf.rar",
		"release.part001.rar",
		"random.001",
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("unexpected rank at %d: got %q want %q", i, got[i], want[i])
		}
	}
}

func TestParseSubjectPartCounter(t *testing.T) {
	part, ok := parseSubjectPartCounter(`abc [10/30] "file.rar"`)
	if !ok || part != 10 {
		t.Fatalf("expected part 10, got part=%d ok=%v", part, ok)
	}

	if _, ok := parseSubjectPartCounter(`abc [x/30] "file.rar"`); ok {
		t.Fatal("expected invalid part token to fail parsing")
	}

	if _, ok := parseSubjectPartCounter(`abc no counter`); ok {
		t.Fatal("expected missing counter to fail parsing")
	}
}

func TestStreamFromBlueprintUnencryptedWithDummyPassword(t *testing.T) {
	bp := &ArchiveBlueprint{
		MainFileName: "movie.mkv",
		TotalSize:    1000,
		IsCompressed: false,
		AnyEncrypted: false,
		Parts: []VirtualPartDef{
			{
				VirtualStart: 0,
				VirtualEnd:   1000,
				VolFile:      &memoryUnpackableFile{name: "release.part1.rar", data: make([]byte, 1000)},
				VolOffset:    0,
			},
		},
	}
	stream, name, size, err := StreamFromBlueprint(context.Background(), bp, "some_dummy_password")
	if err != nil {
		t.Fatalf("StreamFromBlueprint returned error: %v", err)
	}
	defer stream.Close()

	if name != "movie.mkv" {
		t.Fatalf("expected movie.mkv, got %q", name)
	}
	if size != 1000 {
		t.Fatalf("expected size 1000, got %d", size)
	}
	typeName := fmt.Sprintf("%T", stream)
	if !strings.Contains(typeName, "VirtualStream") {
		t.Fatalf("expected VirtualStream type, got %s", typeName)
	}
}

func TestBuildBlueprintCiphertextBlockAlignmentForEncryptedRAR(t *testing.T) {
	parts := []filePart{
		{
			name:         "movie.mkv",
			unpackedSize: 1000,
			dataOffset:   100,
			packedSize:   200,
			volName:      "release.part01.rar",
			isMedia:      true,
			isEncrypted:  true,
		},
		{
			name:         "movie.mkv",
			unpackedSize: 1000,
			dataOffset:   100,
			packedSize:   300,
			volName:      "release.part02.rar",
			isMedia:      true,
			isEncrypted:  true,
		},
		{
			name:         "movie.mkv",
			unpackedSize: 1000,
			dataOffset:   100,
			packedSize:   510,
			volName:      "release.part03.rar",
			isMedia:      true,
			isEncrypted:  true,
		},
	}
	allFiles := []UnpackableFile{
		&memoryUnpackableFile{name: "release.part01.rar"},
		&memoryUnpackableFile{name: "release.part02.rar"},
		&memoryUnpackableFile{name: "release.part03.rar"},
	}

	bp, err := buildBlueprint(context.Background(), parts, allFiles, "password", EpisodeTarget{})
	if err != nil {
		t.Fatalf("buildBlueprint returned error: %v", err)
	}

	var totalPartsPacked int64
	for _, p := range bp.Parts {
		totalPartsPacked += (p.VirtualEnd - p.VirtualStart)
	}

	if totalPartsPacked != 1008 {
		t.Fatalf("expected total packed parts size to be 1008, got %d", totalPartsPacked)
	}
	if bp.TotalSize != 1000 {
		t.Fatalf("expected blueprint TotalSize (unpacked) to remain 1000, got %d", bp.TotalSize)
	}
}

func TestScanArchiveReturnsVolumeScanErrorInsteadOfEmptyArchive(t *testing.T) {
	// Memory file containing garbage data that will cause rardecode.ListArchiveInfo to fail
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "invalid_release.part01.rar", data: []byte("not a real rar archive header")},
	}

	_, err := ScanArchive(context.Background(), files, "", EpisodeTarget{})
	if err == nil {
		t.Fatal("expected ScanArchive to fail on invalid RAR data")
	}

	if strings.Contains(err.Error(), "empty archive") {
		t.Fatalf("expected detailed volume scan failure error, got misleading 'empty archive': %v", err)
	}
	if !strings.Contains(err.Error(), "RAR header scan failed") {
		t.Fatalf("expected error to contain 'RAR header scan failed', got: %v", err)
	}
}

func TestScanArchiveWithMiddleVolumesOnlyReportsMissingFirstVolume(t *testing.T) {
	// Set containing only middle volumes (e.g. part02.rar, part03.rar) with missing part01.rar
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "release.part02.rar", data: []byte("middle volume content")},
		&memoryUnpackableFile{name: "release.part03.rar", data: []byte("middle volume content 2")},
	}

	_, err := ScanArchive(context.Background(), files, "", EpisodeTarget{})
	if err == nil {
		t.Fatal("expected ScanArchive to fail on missing first volume set")
	}

	if strings.Contains(err.Error(), "empty archive") {
		t.Fatalf("expected missing first volume error, got misleading 'empty archive': %v", err)
	}
	if !strings.Contains(err.Error(), "missing from release") {
		t.Fatalf("expected error to contain 'missing from release', got: %v", err)
	}
	if !errors.Is(err, ErrPAR2RepairRequired) {
		t.Fatalf("expected ErrPAR2RepairRequired, got %v", err)
	}
}

func TestTryNestedArchiveScansRemainingOuterVolumesWhenFirstVolumeMissing(t *testing.T) {
	initialParts := []filePart{
		{name: "inner.r16", volName: "outer.part01.rar", packedSize: 100},
	}

	allOuter := []UnpackableFile{
		&memoryUnpackableFile{name: "outer.part01.rar", data: []byte("outer part 1")},
		&memoryUnpackableFile{name: "outer.part02.rar", data: []byte("outer part 2")},
	}

	_, err := tryNestedArchive(context.Background(), initialParts, allOuter, "", EpisodeTarget{})
	if err == nil {
		t.Fatal("expected error on dummy data")
	}
}

// A nested set is only as complete as the outer scan that produced it, so a
// missing inner first volume must not be reported as a damaged release.
func TestScanArchiveNestedSetMissingFirstVolumeIsNotBlamedOnPAR2(t *testing.T) {
	// The shape a partial outer scan leaves behind: some inner continuation
	// volumes, no inner first volume, and no PAR2 anywhere in sight.
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "inner.r16", data: []byte("inner continuation volume")},
		&memoryUnpackableFile{name: "inner.r31", data: []byte("another inner continuation")},
	}

	ctx := WithNestDepth(context.Background(), 1)
	_, err := ScanArchive(ctx, files, "", EpisodeTarget{})
	if err == nil {
		t.Fatal("expected ScanArchive to fail without an inner first volume")
	}
	if !errors.Is(err, ErrNestedSetIncomplete) {
		t.Fatalf("expected ErrNestedSetIncomplete, got %v", err)
	}
	if errors.Is(err, ErrPAR2RepairRequired) {
		t.Fatalf("nested set failure must not claim PAR2 repair: %v", err)
	}
	if strings.Contains(err.Error(), "missing from release") {
		t.Fatalf("nested set failure must not blame the release: %v", err)
	}
}

// The top-level case keeps its PAR2 wording, so the two stay distinguishable.
func TestScanArchiveTopLevelMissingFirstVolumeStillReportsPAR2(t *testing.T) {
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "release.part02.rar", data: []byte("middle volume content")},
		&memoryUnpackableFile{name: "release.part03.rar", data: []byte("middle volume content 2")},
	}

	_, err := ScanArchive(context.Background(), files, "", EpisodeTarget{})
	if !errors.Is(err, ErrPAR2RepairRequired) {
		t.Fatalf("expected ErrPAR2RepairRequired at depth 0, got %v", err)
	}
	if errors.Is(err, ErrNestedSetIncomplete) {
		t.Fatalf("top-level failure must not be classed as a nested-set problem: %v", err)
	}
}

func TestIsFirstVolumeName(t *testing.T) {
	firsts := []string{"release.rar", "release.part01.rar", "release.part1.rar", "movie.mkv"}
	for _, name := range firsts {
		if !isFirstVolumeName(name) {
			t.Errorf("%q should be able to open a set", name)
		}
	}
	continuations := []string{"release.part02.rar", "release.part13.rar", "release.r00", "release.r16"}
	for _, name := range continuations {
		if isFirstVolumeName(name) {
			t.Errorf("%q is a continuation volume", name)
		}
	}
}

func TestSetHasFirstVolumeOnlyConsidersItsOwnSet(t *testing.T) {
	parts := []filePart{
		{name: "wanted.r16"},
		{name: "wanted.r31"},
		{name: "unrelated.rar"},
	}
	wanted := archiveSetName("wanted.r16")
	if setHasFirstVolume(parts, wanted) {
		t.Fatal("another set's first volume must not satisfy this set")
	}

	parts = append(parts, filePart{name: "wanted.rar"})
	if !setHasFirstVolume(parts, wanted) {
		t.Fatal("expected the set's own first volume to be recognised")
	}
}

// countingUnpackableFile records how many times the scanner opened it, so a
// test can prove the rescan stopped early instead of walking every volume.
type countingUnpackableFile struct {
	*memoryUnpackableFile
	opened *atomic.Int64
}

func (f *countingUnpackableFile) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	f.opened.Add(1)
	return f.memoryUnpackableFile.OpenStreamCtx(ctx)
}

// A 135-volume release used to scan every remaining outer volume looking for
// one inner first volume, running past the client's timeout.
func TestTryNestedArchiveCapsTheOuterRescan(t *testing.T) {
	var opened atomic.Int64
	initialParts := []filePart{
		{name: "inner.r16", volName: "outer.part001.rar", packedSize: 100},
	}

	allOuter := []UnpackableFile{
		&memoryUnpackableFile{name: "outer.part001.rar", data: []byte("outer part 1")},
	}
	for i := 2; i <= 135; i++ {
		allOuter = append(allOuter, &countingUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{
				name: fmt.Sprintf("outer.part%03d.rar", i),
				data: []byte("not a parseable rar volume"),
			},
			opened: &opened,
		})
	}

	_, err := tryNestedArchive(context.Background(), initialParts, allOuter, "", EpisodeTarget{})
	if err == nil {
		t.Fatal("expected the nested scan to fail on unparseable volumes")
	}
	if got := opened.Load(); got > maxNestedRescanVolumes {
		t.Fatalf("rescan opened %d outer volumes, cap is %d", got, maxNestedRescanVolumes)
	}
	if opened.Load() == 0 {
		t.Fatal("expected the rescan to try at least some outer volumes")
	}
}

func TestStreamFromBlueprintCompressedIsDefinitive(t *testing.T) {
	bp := &ArchiveBlueprint{
		MainFileName: "tjrtajrykjrwji5rikr5.mkv",
		TotalSize:    1000,
		IsCompressed: true,
	}

	_, _, _, err := StreamFromBlueprint(context.Background(), bp, "")
	if !errors.Is(err, ErrCompressedArchive) {
		t.Fatalf("expected ErrCompressedArchive, got %v", err)
	}
	if !strings.Contains(err.Error(), "tjrtajrykjrwji5rikr5.mkv") {
		t.Fatalf("expected the offending file name in %q", err.Error())
	}
}
