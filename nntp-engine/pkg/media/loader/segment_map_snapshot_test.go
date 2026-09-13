package loader

import (
	"context"
	"testing"
)

func segmentOffsets(t *testing.T, f *File) []int64 {
	t.Helper()
	offsets := make([]int64, 0, len(f.segments))
	for i := range f.segments {
		start, end, ok := f.SegmentOffsetRange(i)
		if !ok {
			t.Fatalf("segment %d has no mapped range", i)
		}
		offsets = append(offsets, start, end)
	}
	return offsets
}

// A restored map must be identical to the probed one, not merely close: the
// offsets it produces are what every read resolves against.
func TestSegmentMapSnapshotRestoresWithoutProbing(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{700, 700, 700, 512}}
	probed := NewFile(context.Background(), testNZBFileWithSegments(720, 720, 720, 530), nil, fetcher)
	if err := probed.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}
	probeCalls := len(fetcher.Calls())
	if probeCalls == 0 {
		t.Fatal("expected the first map to cost article probes")
	}

	snapshot, ok := probed.SegmentMapSnapshotJSON()
	if !ok {
		t.Fatal("expected a snapshot from a detected segment map")
	}

	replayFetcher := &varyingSizeSegmentFetcher{sizes: []int64{700, 700, 700, 512}}
	replayed := NewFile(context.Background(), testNZBFileWithSegments(720, 720, 720, 530), nil, replayFetcher)
	if !replayed.RestoreSegmentMapJSON(snapshot) {
		t.Fatal("RestoreSegmentMapJSON rejected its own snapshot")
	}
	if got := len(replayFetcher.Calls()); got != 0 {
		t.Fatalf("restore fetched %d articles, want 0", got)
	}
	if replayed.Size() != probed.Size() {
		t.Fatalf("restored size %d, want %d", replayed.Size(), probed.Size())
	}
	want := segmentOffsets(t, probed)
	got := segmentOffsets(t, replayed)
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("segment offsets diverged at %d: got %d, want %d", i, got[i], want[i])
		}
	}

	// A restored map is snapshottable in turn, so a later library save keeps
	// carrying it rather than dropping it after one replay.
	if _, ok := replayed.SegmentMapSnapshotJSON(); !ok {
		t.Fatal("expected a restored map to snapshot again")
	}
}

func TestSegmentMapSnapshotRejectsForeignVolume(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{700, 700, 512}}
	f := NewFile(context.Background(), testNZBFileWithSegments(720, 720, 530), nil, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}
	snapshot, ok := f.SegmentMapSnapshotJSON()
	if !ok {
		t.Fatal("expected a snapshot")
	}

	cases := map[string][]int64{
		"different segment count": {720, 720},
		"different encoded sizes": {720, 720, 700},
	}
	for name, sizes := range cases {
		t.Run(name, func(t *testing.T) {
			other := NewFile(context.Background(), testNZBFileWithSegments(sizes...), nil, &varyingSizeSegmentFetcher{sizes: sizes})
			if other.RestoreSegmentMapJSON(snapshot) {
				t.Fatal("restored a map measured against a different volume")
			}
			if other.Size() != sumInt64(sizes) {
				t.Fatalf("rejected restore left size %d, want the untouched %d", other.Size(), sumInt64(sizes))
			}
		})
	}
}

func sumInt64(values []int64) int64 {
	var sum int64
	for _, v := range values {
		sum += v
	}
	return sum
}

func TestSegmentMapSnapshotRejectsGarbage(t *testing.T) {
	f := NewFile(context.Background(), testNZBFileWithSegments(720, 720), nil, &varyingSizeSegmentFetcher{sizes: []int64{700, 700}})
	if f.RestoreSegmentMapJSON([]byte("not json")) {
		t.Fatal("restored a map from garbage")
	}
	if _, ok := f.SegmentMapSnapshotJSON(); ok {
		t.Fatal("expected no snapshot before the map is built")
	}
}
