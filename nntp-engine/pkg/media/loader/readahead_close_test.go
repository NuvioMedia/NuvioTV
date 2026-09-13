package loader

import (
	"context"
	"testing"
	"time"
)

// inflightHas reports whether index is still being fetched.
func inflightHas(f *File, index int) bool {
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()
	_, ok := f.inflightDownloads[index]
	return ok
}

// waitInflightGone waits for the named indexes to drain out of the in-flight map.
func waitInflightGone(t *testing.T, f *File, indexes ...int) {
	t.Helper()
	waitInflightGoneWithin(t, f, 2*time.Second, indexes...)
}

func waitInflightGoneWithin(t *testing.T, f *File, within time.Duration, indexes ...int) {
	t.Helper()
	deadline := time.Now().Add(within)
	for {
		left := 0
		for _, i := range indexes {
			if inflightHas(f, i) {
				left++
			}
		}
		if left == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%d of %v still in flight; they hold connections the new window needs", left, indexes)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// warmedReader opens a reader at startSeg, reads one byte so it establishes a
// read-ahead window, and returns it with that window in flight.
func warmedReader(t *testing.T, f *File, fetcher *blockingCancelFetcher, startSeg, window int) *SegmentReader {
	t.Helper()
	// Segment numbers are 1-based; the reader needs its own segment to return.
	fetcher.releaseSeg(startSeg + 1)
	r := NewSegmentReaderWithReadAhead(context.Background(), f, f.segments[startSeg].StartOffset, window)
	if _, err := r.Read(make([]byte, 1)); err != nil {
		t.Fatalf("read at segment %d: %v", startSeg, err)
	}
	for i := startSeg + 1; i < startSeg+window; i++ {
		select {
		case <-fetcher.startedCh(i + 1):
		case <-time.After(2 * time.Second):
			t.Fatalf("read-ahead fetch for segment %d never started", i)
		}
	}
	return r
}

// A closed reader's read-ahead window keeps downloading: read-ahead runs on the
// file context on purpose, so closing the reader cancels nothing. Serving is
// per-request, so the next request decides — and when it is a seek away, the
// abandoned window must let go of the connections it is holding.
func TestClosedReaderReadAheadCancelledBySeekAway(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 40)

	first := warmedReader(t, f, fetcher, 0, 4)
	if err := first.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	// Nothing is cancelled yet: the next request may well be the rest of this
	// same read, and its prefetch is exactly what was warmed.
	for _, i := range []int{1, 2, 3} {
		if !inflightHas(f, i) {
			t.Fatalf("segment %d cancelled at close; a contiguous follow-up would have wanted it", i)
		}
	}

	// The follow-up seeks elsewhere instead, so the old window is dead weight.
	second := warmedReader(t, f, fetcher, 20, 4)
	defer second.Close()

	waitInflightGone(t, f, 1, 2, 3)
}

// A player streaming in consecutive Range requests closes a reader and opens
// the next one over the window this one warmed. That prefetch is about to be
// read, so it must survive the close.
func TestClosedReaderReadAheadSurvivesContiguousFollowUp(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 40)

	first := warmedReader(t, f, fetcher, 0, 6)
	if err := first.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	// The follow-up picks up where the first left off, inside the warm window.
	second := warmedReader(t, f, fetcher, 1, 6)
	defer second.Close()

	// Give a wrongly-eager cancellation time to land.
	time.Sleep(100 * time.Millisecond)
	for _, i := range []int{2, 3, 4, 5} {
		if !inflightHas(f, i) {
			t.Fatalf("segment %d was cancelled; the follow-up read is about to want it", i)
		}
	}
}

// The capture names requests, not indexes, so a segment re-fetched between the
// close and the reap is a different request and must not be hit by the old
// window's cancellation.
func TestReapLeavesResurrectedSegmentAlone(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 40)

	first := warmedReader(t, f, fetcher, 0, 4)
	if err := first.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	// Segment 3's fetch completes and a real reader starts a fresh one.
	fetcher.releaseSeg(4)
	waitInflightGone(t, f, 3)
	readerDone := make(chan error, 1)
	go func() {
		_, err := f.DownloadSegment(context.Background(), 3)
		readerDone <- err
	}()
	select {
	case <-fetcher.startedCh(4):
	case <-time.After(2 * time.Second):
		t.Fatal("re-fetch of segment 3 never started")
	}

	// A window far away reaps the abandoned one.
	second := warmedReader(t, f, fetcher, 20, 4)
	defer second.Close()
	waitInflightGone(t, f, 1, 2)

	fetcher.releaseSeg(4)
	select {
	case err := <-readerDone:
		if err != nil {
			t.Fatalf("the re-fetch was cancelled by the old window's reap: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("re-fetch never finished")
	}
}

// Reaping is driven by demand on the same file, so the last window of a session
// has no reader left to answer for it — and it holds connections out of a pool
// every other session shares. The grace bounds that.
func TestUnclaimedAbandonedWindowIsReapedAfterGrace(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 40)

	r := warmedReader(t, f, fetcher, 0, 4)
	if err := r.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	// No follow-up reader ever comes; only the grace can free these.
	waitInflightGoneWithin(t, f, abandonedReadAheadGrace+2*time.Second, 1, 2, 3)
}
