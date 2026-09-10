package loader

import (
	"context"
	"sync"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

func geoSegments(n int) []*Segment {
	segs := make([]*Segment, n)
	for i := range segs {
		segs[i] = &Segment{Segment: nzb.Segment{Number: i + 1, ID: "id", Bytes: 105}}
	}
	return segs
}

func TestExactSizesFromYencGeometry(t *testing.T) {
	segs := geoSegments(5)
	stride, last := int64(100), int64(40)
	fileSize := 4*stride + last

	t.Run("uniform_layout_is_exact", func(t *testing.T) {
		sizes, ok := exactSizesFromYencGeometry(segs,
			map[int]int64{0: stride, 4: last},
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{0: 0, 4: 400}})
		if !ok {
			t.Fatal("expected exact map")
		}
		want := []int64{100, 100, 100, 100, 40}
		for i, w := range want {
			if sizes[i] != w {
				t.Fatalf("sizes[%d] = %d, want %d", i, sizes[i], w)
			}
		}
	})

	t.Run("off_grid_offset_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{4: 399}}); ok {
			t.Fatal("non-uniform offset must fall back to measuring")
		}
	})

	t.Run("measured_length_disagreement_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs,
			map[int]int64{2: 99},
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("a probed article contradicting the grid must fall back")
		}
	})

	t.Run("implausible_tail_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: 5 * stride * 2, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("a tail larger than the stride must fall back")
		}
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: 400, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("a zero-size tail must fall back")
		}
	})

	t.Run("poisoned_or_empty_geometry_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: -1, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("poisoned geometry must fall back")
		}
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{0: 0}}); ok {
			t.Fatal("offset 0 alone cannot pin a stride")
		}
	})
}

// geometryFetcher serves articles whose yEnc headers declare the exact layout.
type geometryFetcher struct {
	stride, last, fileSize int64
	n                      int

	mu      sync.Mutex
	fetched map[int]int
}

func (f *geometryFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	idx := int(segment.Number) - 1
	f.mu.Lock()
	if f.fetched == nil {
		f.fetched = make(map[int]int)
	}
	f.fetched[idx]++
	f.mu.Unlock()

	size := f.stride
	if idx == f.n-1 {
		size = f.last
	}
	body := make([]byte, size)
	return pool.SegmentData{
		Body:           body,
		Size:           size,
		YencFileSize:   f.fileSize,
		YencPartOffset: int64(idx) * f.stride,
	}, nil
}

func (f *geometryFetcher) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	total := 0
	for _, c := range f.fetched {
		total += c
	}
	return total
}

func newGeometryFile(n int, fetcher SegmentFetcher) *File {
	segs := make([]nzb.Segment, n)
	for i := range segs {
		segs[i] = nzb.Segment{Number: i + 1, ID: string(rune('a' + i)), Bytes: 105}
	}
	return NewFile(context.Background(), &nzb.File{Subject: "geo.mkv", Segments: segs}, nil, fetcher)
}

// With geometry in the articles, even the slow (non-skip-gap) path builds an
// exact map from the planner's two probes instead of downloading every segment.
func TestSegmentMapExactFromYencGeometrySkipsGapProbing(t *testing.T) {
	const n = 40
	fetcher := &geometryFetcher{stride: 100, last: 40, fileSize: 39*100 + 40, n: n}
	f := newGeometryFile(n, fetcher)

	if err := f.EnsureSegmentMap(); err != nil { // f.ctx: gap probing NOT skipped
		t.Fatal(err)
	}
	if got, want := f.Size(), int64(39*100+40); got != want {
		t.Fatalf("total = %d, want %d", got, want)
	}
	start, end, _ := f.SegmentOffsetRange(n - 1)
	if start != 3900 || end != 3940 {
		t.Fatalf("last segment mapped [%d,%d), want [3900,3940)", start, end)
	}
	if c := fetcher.fetchCount(); c > 3 {
		t.Fatalf("exact geometry should need the planner's probes only, fetched %d articles", c)
	}
}

// Without geometry the same file pays a full gap-probe pass on the slow path —
// the regression this feature removes.
func TestSegmentMapWithoutGeometryStillGapProbes(t *testing.T) {
	const n = 40
	fetcher := &geometryFetcher{stride: 100, last: 40, fileSize: 0 /* no geometry */, n: n}
	f := newGeometryFile(n, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatal(err)
	}
	fetcher.mu.Lock()
	distinct := len(fetcher.fetched)
	fetcher.mu.Unlock()
	if distinct < n-1 {
		t.Fatalf("control: expected the gap pass to fetch nearly all %d segments, fetched %d distinct", n, distinct)
	}
}

func TestSegmentMapSnapshotRoundtripsYencGeometry(t *testing.T) {
	const n = 12
	fetcher := &geometryFetcher{stride: 100, last: 40, fileSize: 11*100 + 40, n: n}
	f := newGeometryFile(n, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatal(err)
	}
	snap, ok := f.SegmentMapSnapshotJSON()
	if !ok {
		t.Fatal("no snapshot from detected map")
	}

	fresh := newGeometryFile(n, &geometryFetcher{stride: 100, last: 40, fileSize: 11*100 + 40, n: n})
	if !fresh.RestoreSegmentMapJSON(snap) {
		t.Fatalf("snapshot rejected on replay: %s", snap)
	}
	if fresh.Size() != f.Size() {
		t.Fatalf("restored total %d, want %d", fresh.Size(), f.Size())
	}
	for i := 0; i < n; i++ {
		as, ae, _ := f.SegmentOffsetRange(i)
		bs, be, _ := fresh.SegmentOffsetRange(i)
		if as != bs || ae != be {
			t.Fatalf("segment %d restored as [%d,%d), want [%d,%d)", i, bs, be, as, ae)
		}
	}
}
