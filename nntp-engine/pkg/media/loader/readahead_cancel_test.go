package loader

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

// blockingCancelFetcher blocks every fetch until its context ends or the test
// releases it, and records the context per segment number.
type blockingCancelFetcher struct {
	mu       sync.Mutex
	started  map[int]chan struct{} // closed when the fetch for that segment begins
	released map[int]chan struct{} // per-segment release (see releaseSeg)
	release  chan struct{}
	size     int
}

func newBlockingCancelFetcher(size int) *blockingCancelFetcher {
	return &blockingCancelFetcher{started: make(map[int]chan struct{}), release: make(chan struct{}), size: size}
}

func (f *blockingCancelFetcher) startedCh(num int) chan struct{} {
	f.mu.Lock()
	defer f.mu.Unlock()
	ch, ok := f.started[num]
	if !ok {
		ch = make(chan struct{})
		f.started[num] = ch
	}
	return ch
}

func (f *blockingCancelFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	ch := f.startedCh(int(segment.Number))
	select {
	case <-ch:
	default:
		close(ch)
	}
	select {
	case <-ctx.Done():
		return pool.SegmentData{}, ctx.Err()
	case <-f.release:
	case <-f.releasedCh(int(segment.Number)):
	}
	body := make([]byte, f.size)
	return pool.SegmentData{Body: body, Size: int64(len(body))}, nil
}

// releasedCh is a per-segment release, so a test can let one fetch through
// while the rest stay in flight.
func (f *blockingCancelFetcher) releasedCh(num int) chan struct{} {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.released == nil {
		f.released = make(map[int]chan struct{})
	}
	ch, ok := f.released[num]
	if !ok {
		ch = make(chan struct{})
		f.released[num] = ch
	}
	return ch
}

func (f *blockingCancelFetcher) releaseSeg(num int) {
	ch := f.releasedCh(num)
	select {
	case <-ch:
	default:
		close(ch)
	}
}

func testFileWithSegments(t *testing.T, fetcher SegmentFetcher, n int) *File {
	t.Helper()
	segs := make([]nzb.Segment, n)
	for i := range segs {
		segs[i] = nzb.Segment{Number: i + 1, ID: string(rune('a' + i)), Bytes: 100}
	}
	return NewFile(context.Background(), &nzb.File{Subject: "t.mkv", Segments: segs}, nil, fetcher)
}

// A seek must abort read-ahead fetches outside the new window, but never one a
// real reader is waiting on.
func TestCancelAbandonedReadAhead(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 30)

	// Pure read-ahead claims on segments 0 and 1.
	f.ReadAheadSegment(context.Background(), 0)
	f.ReadAheadSegment(context.Background(), 1)
	// A real reader waiting on segment 2.
	readerErr := make(chan error, 1)
	go func() {
		_, err := f.DownloadSegment(context.Background(), 2)
		readerErr <- err
	}()
	for _, num := range []int{1, 2, 3} {
		select {
		case <-fetcher.startedCh(num):
		case <-time.After(2 * time.Second):
			t.Fatalf("fetch for segment %d never started", num)
		}
	}

	// The reader's abandoned window covered [0, 3); 0 and 1 are pure
	// read-ahead, 2 has a real reader and must survive.
	f.cancelAbandonedReadAheadIn(0, 3)

	// The abandoned fetches drain out of the inflight map with a cancellation.
	deadline := time.Now().Add(2 * time.Second)
	for {
		f.downloadMu.Lock()
		_, has0 := f.inflightDownloads[0]
		_, has1 := f.inflightDownloads[1]
		_, has2 := f.inflightDownloads[2]
		f.downloadMu.Unlock()
		if !has0 && !has1 {
			if !has2 {
				t.Fatal("segment 2 has a real reader and must not be cancelled")
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("abandoned fetches not retired: seg0=%v seg1=%v", has0, has1)
		}
		time.Sleep(5 * time.Millisecond)
	}

	// Release the fetcher: the real reader completes normally.
	close(fetcher.release)
	select {
	case err := <-readerErr:
		if err != nil {
			t.Fatalf("real reader failed: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("real reader never completed")
	}
}

// A caller that joins a fetch in the same instant it is condemned retries
// against a fresh request instead of surfacing context.Canceled to the player.
func TestDownloadSegmentRetriesAbandonedJoin(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 4)

	f.ReadAheadSegment(context.Background(), 0)
	select {
	case <-fetcher.startedCh(1):
	case <-time.After(2 * time.Second):
		t.Fatal("read-ahead fetch never started")
	}

	// Condemn it, then immediately ask for the segment as a real reader. The
	// reader either joins the dying request (and must retry) or starts fresh.
	f.cancelAbandonedReadAheadIn(0, 1)
	done := make(chan error, 1)
	go func() {
		_, err := f.DownloadSegment(context.Background(), 0)
		done <- err
	}()

	// Give the retry a live fetch to land on, then let everything through.
	time.Sleep(20 * time.Millisecond)
	close(fetcher.release)

	select {
	case err := <-done:
		if err != nil && errors.Is(err, context.Canceled) {
			t.Fatalf("reader surfaced the abandoned fetch's cancellation: %v", err)
		}
		if err != nil {
			t.Fatalf("reader failed: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("reader never completed")
	}
}

// inflightAbandoned reports how many pure read-ahead fetches are in flight.
func inflightAbandoned(f *File) int {
	f.downloadMu.Lock()
	defer f.downloadMu.Unlock()
	n := 0
	for _, req := range f.inflightDownloads {
		if !req.countFailures {
			n++
		}
	}
	return n
}

// http.ServeContent probes the size with Seek(0, End) and rewinds with
// Seek(0, Start) before reading a byte, on every single request. Those
// bookkeeping seeks must not cancel anything — cancelling there killed the
// startup warm and the tail warm the moment the first request arrived. Only a
// read at a genuinely disjoint position abandons the old window.
func TestSeekAloneDoesNotCancelReadAhead(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 200)

	r := NewSegmentReaderWithReadAhead(context.Background(), f, 0, 4)
	defer r.Close()

	// An independent warm, far from the reader — a tail warm.
	f.ReadAheadSegment(context.Background(), 150)
	select {
	case <-fetcher.startedCh(151):
	case <-time.After(2 * time.Second):
		t.Fatal("tail warm never started")
	}

	// ServeContent's bookkeeping: size probe, rewind, range seek.
	for _, off := range []int64{0, 100 * 200, 0, 50 * 100} {
		if _, err := r.Seek(off, 0); err != nil {
			t.Fatal(err)
		}
	}
	if got := inflightAbandoned(f); got == 0 {
		t.Fatal("bookkeeping seeks cancelled the tail warm")
	}

	// A read at the seek target (segment 50) opens a disjoint window; the
	// reader had no previous window, so still nothing to cancel — and the tail
	// warm must survive the read's own window too.
	readDone := make(chan error, 1)
	go func() {
		buf := make([]byte, 10)
		_, err := r.Read(buf)
		readDone <- err
	}()
	select {
	case <-fetcher.startedCh(51):
	case <-time.After(2 * time.Second):
		t.Fatal("read never fetched its segment")
	}
	if got := inflightAbandoned(f); got == 0 {
		t.Fatal("reading cancelled the unrelated tail warm")
	}
	close(fetcher.release)
	if err := <-readDone; err != nil {
		t.Fatal(err)
	}
}

// A read after a disjoint jump cancels the reader's own abandoned window —
// and only that window.
func TestReadAfterJumpCancelsOwnOldWindow(t *testing.T) {
	fetcher := newBlockingCancelFetcher(100)
	f := testFileWithSegments(t, fetcher, 200)

	r := NewSegmentReaderWithReadAhead(context.Background(), f, 0, 4)
	defer r.Close()

	// Unrelated warm outside both windows.
	f.ReadAheadSegment(context.Background(), 150)

	// First read at 0 completes (its own fetch is let through) and establishes
	// window [0, 4); the read-ahead fetches for 1..3 stay in flight.
	fetcher.releaseSeg(1)
	buf := make([]byte, 10)
	if _, err := r.Read(buf); err != nil {
		t.Fatal(err)
	}
	for _, num := range []int{2, 3, 4, 151} { // read-ahead of 1..3 plus the warm
		select {
		case <-fetcher.startedCh(num):
		case <-time.After(2 * time.Second):
			t.Fatalf("fetch for segment number %d never started", num)
		}
	}

	// Jump: the old window [0,4) is disjoint from the new one [100,104).
	if _, err := r.Seek(100*100, 0); err != nil {
		t.Fatal(err)
	}
	fetcher.releaseSeg(101)
	if _, err := r.Read(buf); err != nil {
		t.Fatal(err)
	}

	// The old window's pure read-ahead (segments 1..3) drains away; the far
	// warm (150) survives.
	deadline := time.Now().Add(2 * time.Second)
	for {
		f.downloadMu.Lock()
		_, has1 := f.inflightDownloads[1]
		_, has2 := f.inflightDownloads[2]
		_, has3 := f.inflightDownloads[3]
		_, hasWarm := f.inflightDownloads[150]
		f.downloadMu.Unlock()
		if !has1 && !has2 && !has3 {
			if !hasWarm {
				t.Fatal("jump cancelled the unrelated warm at 150")
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("old window not cancelled: 1=%v 2=%v 3=%v", has1, has2, has3)
		}
		time.Sleep(5 * time.Millisecond)
	}

	close(fetcher.release)
}
