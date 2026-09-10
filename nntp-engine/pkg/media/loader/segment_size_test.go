package loader

import (
	"context"
	"testing"

	"streamnzb/pkg/media/nzb"
)

func TestScaleDecodedSizeUsesPerSegmentNZBBytes(t *testing.T) {
	got := scaleDecodedSize(15, 10, 8)
	if got != 12 {
		t.Fatalf("scaleDecodedSize(15,10,8) = %d, want 12", got)
	}
}

func TestSegmentProbeIndicesDistinctNZBBytesAndLast(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(15)},
		{Segment: nzbSegment(10)},
	}
	got := segmentProbeIndices(segments, nil, false, false)
	want := []int{0, 1, 2}
	if len(got) != len(want) {
		t.Fatalf("probe indices = %v, want %v", got, want)
	}
	for i, idx := range want {
		if got[i] != idx {
			t.Fatalf("probe indices = %v, want %v", got, want)
		}
	}
}

// TestSegmentProbePlanSeparatesFinalSegmentClass reproduces the real-world
// failure: a 157-segment final RAR volume whose full segments encode to
// ~739,315 bytes (716,800 decoded) while the final segment encodes to 686,112
// (668,107 decoded) — only 7.2% apart. The old 10% clustering merged both into
// one class, probed ONLY the last segment, painted 668,107 across the whole
// volume, and truncated the virtual file by 7.6MB right where the Matroska
// cues live — tail reads returned 0 bytes and playback never started.
func TestSegmentProbePlanSeparatesFinalSegmentClass(t *testing.T) {
	const (
		fullEncoded  = 739315
		fullDecoded  = 716800
		lastEncoded  = 686112
		lastDecoded  = 668107
		segmentCount = 157
	)
	segments := make([]*Segment, segmentCount)
	for i := 0; i < segmentCount-1; i++ {
		// Vary encoded size slightly (±700) to mimic the 137 unique sizes seen
		// in the real NZB; all belong to the same decoded class.
		segments[i] = &Segment{Segment: nzbSegment(int64(fullEncoded + (i%7)*100 - 300))}
	}
	segments[segmentCount-1] = &Segment{Segment: nzbSegment(lastEncoded)}

	// Fast-mode plan (skipGapProbing=true) must probe a full-class segment, not
	// just the physically-last one.
	indices := segmentProbeIndices(segments, nil, false, true)
	hasNonLast := false
	for _, idx := range indices {
		if idx != segmentCount-1 {
			hasNonLast = true
			break
		}
	}
	if !hasNonLast {
		t.Fatalf("probe plan %v must include a full-size-class segment, not only the last", indices)
	}

	// With both classes probed, the reconstructed map must hit the true total.
	probed := map[int]int64{}
	for _, idx := range indices {
		if idx == segmentCount-1 {
			probed[idx] = lastDecoded
		} else {
			probed[idx] = fullDecoded
		}
	}
	sizes := buildSegmentDecodedSizesFromProbes(segments, probed, nil, true)
	var total int64
	for _, sz := range sizes {
		total += sz
	}
	want := int64((segmentCount-1)*fullDecoded + lastDecoded)
	if total != want {
		t.Fatalf("reconstructed volume size = %d, want %d (delta %d)", total, want, total-want)
	}
}

// TestLastSegmentNeverRepresentsFullClass reproduces the REMUX flicker bug:
// a 23,538-segment file whose LAST segment (734,057 encoded / 711,755 decoded)
// sits WITHIN cluster tolerance of the full segments (~739,600 / 716,800). The
// plan collapsed to probing only the last segment and painted 711,755 across
// every segment (-5,045 bytes each) — offsets drifted ~1MB per 140MB, the
// Matroska demuxer desynced, and the client reopened the stream 3-4x/second.
func TestLastSegmentNeverRepresentsFullClass(t *testing.T) {
	const (
		fullDecoded  = 716800
		lastEncoded  = 734057
		lastDecoded  = 711755
		segmentCount = 500
	)
	segments := make([]*Segment, segmentCount)
	for i := 0; i < segmentCount-1; i++ {
		// Full segments: encoded ~739,600 ± small content-dependent variation —
		// all within 3% of the last segment's encoded size.
		segments[i] = &Segment{Segment: nzbSegment(int64(739600 + (i%9)*700 - 2800))}
	}
	segments[segmentCount-1] = &Segment{Segment: nzbSegment(lastEncoded)}

	// The fast-mode plan must include a non-last probe even though clustering
	// merges everything into one class.
	indices := segmentProbeIndices(segments, nil, false, true)
	hasNonLast := false
	for _, idx := range indices {
		if idx != segmentCount-1 {
			hasNonLast = true
			break
		}
	}
	if !hasNonLast {
		t.Fatalf("probe plan %v collapsed to the last segment only", indices)
	}

	// With both probes, every full segment must get the full-class size — the
	// last segment's decoded size must never leak onto other segments.
	probed := map[int]int64{}
	for _, idx := range indices {
		if idx == segmentCount-1 {
			probed[idx] = lastDecoded
		} else {
			probed[idx] = fullDecoded
		}
	}
	sizes := buildSegmentDecodedSizesFromProbes(segments, probed, nil, true)
	for i := 0; i < segmentCount-1; i++ {
		if sizes[i] != fullDecoded {
			t.Fatalf("segment %d size = %d, want %d (last segment's size leaked into the full class)", i, sizes[i], fullDecoded)
		}
	}
	if sizes[segmentCount-1] != lastDecoded {
		t.Fatalf("last segment size = %d, want %d", sizes[segmentCount-1], lastDecoded)
	}
}

func TestNZBBytesNearby(t *testing.T) {
	if !nzbBytesNearby(739315, 738628) {
		t.Error("0.1%% apart must be the same class")
	}
	if nzbBytesNearby(686112, 739315) {
		t.Error("7.2%% apart must be separate classes (final-segment case)")
	}
}

func TestSegmentUnprobedIndices(t *testing.T) {
	got := segmentUnprobedIndices(5, []int{0, 2, 4})
	want := []int{1, 3}
	if len(got) != len(want) {
		t.Fatalf("unprobed = %v, want %v", got, want)
	}
	for i, idx := range want {
		if got[i] != idx {
			t.Fatalf("unprobed = %v, want %v", got, want)
		}
	}
}

func TestSegmentProbeIndicesSkipsKnownEstimatorBytes(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(10)},
	}
	known := map[int64]int64{10: 8}
	got := segmentProbeIndices(segments, known, true, false)
	want := []int{1, 2}
	if len(got) != len(want) {
		t.Fatalf("probe indices = %v, want %v", got, want)
	}
	for i, idx := range want {
		if got[i] != idx {
			t.Fatalf("probe indices = %v, want %v", got, want)
		}
	}
}

func TestBuildSegmentDecodedSizesFromProbesVariableNZBBytes(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(15)},
		{Segment: nzbSegment(10)},
	}
	probed := map[int]int64{0: 8, 1: 12, 2: 5}
	sizes := buildSegmentDecodedSizesFromProbes(segments, probed, nil, false)
	want := []int64{8, 12, 5}
	for i, w := range want {
		if sizes[i] != w {
			t.Fatalf("sizes[%d] = %d, want %d (full=%v)", i, sizes[i], w, sizes)
		}
	}
}

func TestApplyUniformMiddleCalibration(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(10)},
	}
	sizes := []int64{8, 8, 5}
	applyUniformMiddleCalibration(segments, sizes, 1, 10)
	want := []int64{8, 10, 5}
	for i, w := range want {
		if sizes[i] != w {
			t.Fatalf("sizes[%d] = %d, want %d (full=%v)", i, sizes[i], w, sizes)
		}
	}
}

func TestShouldProbeMiddleSegmentAlwaysSkipsInFastMode(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(10)},
	}
	if shouldProbeMiddleSegment(context.Background(), segments) {
		t.Fatal("expected no middle probe in fast failover mode")
	}
}

func TestShouldProbeMiddleSegmentSkipsVariableNZBBytes(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(10)},
		{Segment: nzbSegment(15)},
		{Segment: nzbSegment(10)},
	}
	if shouldProbeMiddleSegment(context.Background(), segments) {
		t.Fatal("expected no middle probe when NZB bytes already vary per segment")
	}
}

func nzbSegment(bytes int64) nzb.Segment {
	// A message id is what makes a segment probe-able: the plan skips id-less
	// segments (numbering-gap placeholders), so the fixtures carry one.
	return nzb.Segment{ID: "<seg>", Bytes: bytes, Number: 1}
}

func TestSegmentProbeIndicesGroupsSimilarSizesInSkipGapProbing(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(792710)},
		{Segment: nzbSegment(792818)},
		{Segment: nzbSegment(792682)},
		{Segment: nzbSegment(792562)},
		{Segment: nzbSegment(792707)},
		{Segment: nzbSegment(82924)},
	}
	got := segmentProbeIndices(segments, nil, false, true)
	want := []int{3, 5}
	if len(got) != len(want) {
		t.Fatalf("probe indices = %v, want %v", got, want)
	}
	for i, idx := range want {
		if got[i] != idx {
			t.Fatalf("probe indices = %v, want %v", got, want)
		}
	}
}

func TestBuildSegmentDecodedSizesFromProbesGroupsSimilarSizesInSkipGapProbing(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(792710)},
		{Segment: nzbSegment(792818)},
		{Segment: nzbSegment(792682)},
		{Segment: nzbSegment(82924)},
	}
	probed := map[int]int64{0: 768000, 3: 80000}
	sizes := buildSegmentDecodedSizesFromProbes(segments, probed, nil, true)
	want := []int64{768000, 768000, 768000, 80000}
	for i, w := range want {
		if sizes[i] != w {
			t.Fatalf("sizes[%d] = %d, want %d (full=%v)", i, sizes[i], w, sizes)
		}
	}
}

func TestBuildSegmentDecodedSizesFromProbesPreservesEstimator(t *testing.T) {
	segments := []*Segment{
		{Segment: nzbSegment(768000)},
		{Segment: nzbSegment(768000)},
		{Segment: nzbSegment(366792)},
	}
	probed := map[int]int64{2: 350762}
	known := map[int64]int64{768000: 768000}
	sizes := buildSegmentDecodedSizesFromProbes(segments, probed, known, true)
	want := []int64{768000, 768000, 350762}
	for i, w := range want {
		if sizes[i] != w {
			t.Fatalf("sizes[%d] = %d, want %d (full=%v)", i, sizes[i], w, sizes)
		}
	}
}

// A release's volumes share an article size, so measuring it once should be
// enough. The planner probes whichever segment first represents each size
// class, which on a non-uniform post is rarely index 0 — seeding only from
// index 0 meant an 87-volume release re-measured the same class 87 times.
func TestSegmentMapSeedsEstimatorFromAnyProbedClass(t *testing.T) {
	ctx := WithSkipGapProbing(context.Background(), true)
	estimator := NewSegmentSizeEstimator()

	// Full articles vary slightly in encoded size; the tail article is a
	// genuinely different, remainder-sized class.
	encoded := []int64{737351, 737400, 737300, 317171}
	decoded := []int64{716800, 716800, 716800, 307200}

	first := &varyingSizeSegmentFetcher{sizes: decoded}
	volume1 := NewFile(ctx, testNZBFileWithSegments(encoded...), estimator, first)
	if err := volume1.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMapCtx: %v", err)
	}
	firstProbes := len(first.Calls())
	if firstProbes < 2 {
		t.Fatalf("first volume probed %d segments, expected it to measure both classes", firstProbes)
	}
	if got, ok := estimator.Get(737351); !ok || got != 716800 {
		t.Fatalf("estimator learned (%d, %v) for the full class, want 716800", got, ok)
	}

	second := &varyingSizeSegmentFetcher{sizes: decoded}
	volume2 := NewFile(ctx, testNZBFileWithSegments(encoded...), estimator, second)
	if err := volume2.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMapCtx (second volume): %v", err)
	}
	if got := len(second.Calls()); got >= firstProbes {
		t.Fatalf("second volume probed %d segments, want fewer than the first volume's %d", got, firstProbes)
	}
	if volume2.Size() != volume1.Size() {
		t.Fatalf("second volume mapped to %d, want the same %d as the first", volume2.Size(), volume1.Size())
	}
}

// The last segment is remainder-sized. Letting it teach the estimator would
// paint a short article across every full segment of the next volume.
func TestSegmentMapNeverSeedsEstimatorFromTheTailArticle(t *testing.T) {
	ctx := WithSkipGapProbing(context.Background(), true)
	estimator := NewSegmentSizeEstimator()

	encoded := []int64{737351, 737400, 317171}
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{716800, 716800, 307200}}
	f := NewFile(ctx, testNZBFileWithSegments(encoded...), estimator, fetcher)
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMapCtx: %v", err)
	}

	if got, ok := estimator.Get(317171); ok {
		t.Fatalf("estimator learned the remainder article as a class (%d)", got)
	}
}

// The forced full-segment probe exists so the remainder-sized last article can
// never stand for the full class. A class already measured on an earlier volume
// satisfies that requirement just as well, so the plan may skip the probe — but
// only if the map it builds is still right.
func TestKnownClassReplacesTheForcedFullSegmentProbe(t *testing.T) {
	const (
		fullEncoded  = 739600
		fullDecoded  = 716800
		lastEncoded  = 734057
		lastDecoded  = 711755
		segmentCount = 500
	)
	segments := make([]*Segment, segmentCount)
	for i := 0; i < segmentCount-1; i++ {
		// Same shape as the incident: every full segment sits within 3% of the
		// last one, so clustering merges them into a single class.
		segments[i] = &Segment{Segment: nzbSegment(int64(fullEncoded + (i%9)*700 - 2800))}
	}
	segments[segmentCount-1] = &Segment{Segment: nzbSegment(lastEncoded)}

	known := map[int64]int64{segments[0].Bytes: fullDecoded}
	indices := segmentProbeIndices(segments, known, false, true)
	if len(indices) != 1 || indices[0] != segmentCount-1 {
		t.Fatalf("probe plan %v re-measured a class the estimator already knew", indices)
	}

	// The saving is only legitimate if the map is identical to the probed one:
	// full segments take the known size, the last takes its own.
	probed := map[int]int64{segmentCount - 1: lastDecoded}
	sizes := buildSegmentDecodedSizesFromProbes(segments, probed, known, true)
	for i := 0; i < segmentCount-1; i++ {
		if sizes[i] != fullDecoded {
			t.Fatalf("segment %d sized %d, want the known full class %d", i, sizes[i], fullDecoded)
		}
	}
	if sizes[segmentCount-1] != lastDecoded {
		t.Fatalf("last segment sized %d, want its own %d", sizes[segmentCount-1], lastDecoded)
	}
}

// The incident shape: an estimator entry from an unrelated release supplies the
// only decoded size in the plan, so the file is mapped without a single article
// of its own being measured. Every read is then refused forever. The refusal
// holds the ground truth, so it must also repair the map.
func TestSegmentMapRebuildsAfterAnArticleDisprovesIt(t *testing.T) {
	ctx := WithSkipGapProbing(context.Background(), true)

	// All four declared sizes cluster into one class, so the planner has a
	// single representative — and the estimator already "knows" it.
	encoded := []int64{739600, 739000, 738000, 734057}
	decoded := []int64{398336, 398336, 398336, 390000}

	estimator := NewSegmentSizeEstimator()
	estimator.Set(739600, 716800) // measured on somebody else's release

	fetcher := &varyingSizeSegmentFetcher{sizes: decoded}
	f := NewFile(ctx, testNZBFileWithSegments(encoded...), estimator, fetcher)

	buf := make([]byte, 1024)
	if _, err := f.ReadAtCtx(ctx, buf, 0); err == nil {
		t.Fatal("expected the inherited map to be refused")
	}
	if _, ok := estimator.Get(739600); ok {
		t.Fatal("the disproved estimator entry survived: the next volume inherits it too")
	}

	if _, err := f.ReadAtCtx(ctx, buf, 0); err != nil {
		t.Fatalf("read after the rebuild: %v", err)
	}
	var want int64
	for _, d := range decoded {
		want += d
	}
	if got := f.Size(); got != want {
		t.Fatalf("rebuilt map totals %d, want the measured %d", got, want)
	}
	for i := range encoded {
		if got := f.segmentDecodedLen(i); got != decoded[i] {
			t.Fatalf("segment %d mapped as %d, want %d", i, got, decoded[i])
		}
	}
}

// A map is only rebuilt a bounded number of times: articles that keep
// disagreeing are not fixed by more probes, and each round costs fetches.
func TestSegmentMapRebuildIsBounded(t *testing.T) {
	f := NewFile(context.Background(), testNZBFileWithSegments(1000, 1000), nil, nil)
	f.mu.Lock()
	f.detected = true
	f.mu.Unlock()

	for i := 0; i <= maxSegmentMapRemaps; i++ {
		f.distrustSegmentMap(0, 900)
		f.mu.Lock()
		f.detected = true
		f.mapDistrusted = false
		f.mu.Unlock()
	}
	f.mu.Lock()
	remaps := f.mapRemaps
	f.mu.Unlock()
	if remaps != maxSegmentMapRemaps {
		t.Fatalf("map was rebuilt %d times, want a cap of %d", remaps, maxSegmentMapRemaps)
	}
}

func TestSegmentSizeEstimatorForgetsAClassWithinTolerance(t *testing.T) {
	e := NewSegmentSizeEstimator()
	e.Set(739600, 716800)
	e.Set(317171, 307200)

	e.Forget(739600 + segmentSizeEstimatorTolerance - 1)
	if _, ok := e.Get(739600); ok {
		t.Fatal("Forget missed a class Get would have matched")
	}
	if decoded, ok := e.Get(317171); !ok || decoded != 307200 {
		t.Fatalf("Forget dropped an unrelated class (%d, %v)", decoded, ok)
	}
}
