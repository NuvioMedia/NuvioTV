package loader

import (
	"context"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

type fixedSegmentFetcher struct{ size int }

func (f *fixedSegmentFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	body := make([]byte, f.size)
	for i := range body {
		body[i] = byte(segment.Number)
	}
	return pool.SegmentData{Body: body, Size: int64(len(body))}, nil
}

// A seek that stays inside the current segment must not throw away the cached
// segment bytes or reset the read-ahead window — the encrypted-RAR path steps
// back one AES block per read, and resetting on each of those held it to a
// serial fetch.
func TestSeekWithinSegmentKeepsCacheAndReadAhead(t *testing.T) {
	nzbFile := &nzb.File{Subject: "test.mkv", Segments: []nzb.Segment{
		{Number: 1, ID: "a", Bytes: 100},
		{Number: 2, ID: "b", Bytes: 100},
		{Number: 3, ID: "c", Bytes: 100},
	}}
	f := NewFile(context.Background(), nzbFile, nil, &fixedSegmentFetcher{size: 100})
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatal(err)
	}

	r := NewSegmentReader(context.Background(), f, 0)
	defer r.Close()

	buf := make([]byte, 50)
	if _, err := r.Read(buf); err != nil {
		t.Fatal(err)
	}

	r.mu.Lock()
	cachedSeg, lastRA := r.currentSegIdx, r.lastReadAheadSeg
	r.mu.Unlock()
	if cachedSeg != 0 {
		t.Fatalf("expected segment 0 cached after read, got %d", cachedSeg)
	}
	if lastRA == -1 {
		t.Fatal("expected read to have triggered read-ahead")
	}

	// Backward wiggle inside segment 0.
	if _, err := r.Seek(34, 0); err != nil {
		t.Fatal(err)
	}
	r.mu.Lock()
	sameCache, sameRA, segOff := r.currentSegIdx, r.lastReadAheadSeg, r.segOff
	r.mu.Unlock()
	if sameCache != cachedSeg {
		t.Fatalf("same-segment seek dropped the cached segment: %d -> %d", cachedSeg, sameCache)
	}
	if sameRA != lastRA {
		t.Fatalf("same-segment seek reset read-ahead: %d -> %d", lastRA, sameRA)
	}
	if segOff != 34 {
		t.Fatalf("segOff = %d, want 34", segOff)
	}

	// The read after the wiggle serves the right bytes from the kept cache.
	if _, err := r.Read(buf[:1]); err != nil {
		t.Fatal(err)
	}
	if buf[0] != 1 {
		t.Fatalf("read %d after same-segment seek, want segment 1's fill byte", buf[0])
	}

	// A cross-segment seek still resets both.
	if _, err := r.Seek(250, 0); err != nil {
		t.Fatal(err)
	}
	r.mu.Lock()
	crossCache, crossRA := r.currentSegIdx, r.lastReadAheadSeg
	r.mu.Unlock()
	if crossCache != -1 || crossRA != -1 {
		t.Fatalf("cross-segment seek kept stale state: cache=%d readahead=%d", crossCache, crossRA)
	}
}
