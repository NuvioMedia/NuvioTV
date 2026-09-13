package loader

import (
	"context"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

func TestSegmentReaderLiveCount(t *testing.T) {
	before := LiveSegmentReaders()
	f := NewFile(context.Background(), &nzb.File{Subject: "test.mkv"}, nil, nil)
	r := NewSegmentReader(context.Background(), f, 0)
	if got := LiveSegmentReaders(); got != before+1 {
		_ = r.Close()
		t.Fatalf("expected %d live segment readers, got %d", before+1, got)
	}
	if err := r.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
	if got := LiveSegmentReaders(); got != before {
		t.Fatalf("expected %d live segment readers after close, got %d", before, got)
	}
}

func TestSegmentReaderLiveDetailsIncludeOwner(t *testing.T) {
	f := NewFile(context.Background(), &nzb.File{Subject: "test.mkv"}, nil, nil)
	f.SetOwnerSessionID("sess-42")
	r := NewSegmentReader(context.Background(), f, 0)
	defer func() { _ = r.Close() }()

	found := false
	for _, detail := range LiveSegmentReaderDetails() {
		if strings.Contains(detail, "session=sess-42") && strings.Contains(detail, `file="test.mkv"`) {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("expected live reader details to include owner session and file, got %v", LiveSegmentReaderDetails())
	}

	if err := r.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
	for _, detail := range LiveSegmentReaderDetails() {
		if strings.Contains(detail, "session=sess-42") {
			t.Fatalf("expected closed reader detail to be removed, got %v", LiveSegmentReaderDetails())
		}
	}
}

func TestSegmentReaderSeekIsNonBlocking(t *testing.T) {
	f := NewFile(context.Background(), testNZBFile("seek-test.mkv", 4, 4, 4), nil, &staticSegmentFetcher{})
	r := NewSegmentReader(context.Background(), f, 0)
	defer func() { _ = r.Close() }()

	done := make(chan error, 1)
	go func() {
		_, err := r.Seek(1, io.SeekStart)
		done <- err
	}()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Seek returned error: %v", err)
		}
	case <-time.After(250 * time.Millisecond):
		t.Fatal("Seek blocked unexpectedly")
	}
}

func TestSegmentReaderDoesNotAdvanceBeforeMappedEnd(t *testing.T) {
	fetcher := &fixedLenSegmentFetcher{length: 8}
	f := NewFile(context.Background(), testNZBFileWithSegments(10, 10), nil, fetcher)
	f.mu.Lock()
	f.detected = true
	f.segments[0].StartOffset = 0
	f.segments[0].EndOffset = 10
	f.segments[1].StartOffset = 10
	f.segments[1].EndOffset = 16
	f.totalSize = 16
	f.mu.Unlock()

	r := NewSegmentReader(context.Background(), f, 0)
	defer func() { _ = r.Close() }()

	// A segment that decodes shorter than its mapped span is a wrong map, and
	// serving anything from it would precede a shifted stream: the read must
	// fail loudly up front rather than advance past the mapped end.
	buf := make([]byte, 16)
	n, err := r.Read(buf)
	if err == nil {
		t.Fatal("expected a map-mismatch error, got nil")
	}
	if n != 0 {
		t.Fatalf("expected no bytes from a mismatched segment, got %d", n)
	}
	if !strings.Contains(err.Error(), "mapped") {
		t.Fatalf("error should name the map mismatch, got: %v", err)
	}
}

func TestSegmentReaderSeekDoesNotCancelInFlightForegroundRead(t *testing.T) {
	fetcher := &blockingForegroundSegmentFetcher{
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
	f := NewFile(context.Background(), testNZBFile("seek-read-test.mkv", 4, 4, 4), nil, fetcher)
	r := NewSegmentReader(context.Background(), f, 0)
	var releaseOnce sync.Once
	releaseFetcher := func() {
		releaseOnce.Do(func() {
			close(fetcher.release)
		})
	}
	defer func() {
		releaseFetcher()
		_ = r.Close()
	}()

	// Start Read in a goroutine — this triggers the on-demand fetch.
	type readResult struct {
		data string
		err  error
	}
	readDone := make(chan readResult, 1)
	go func() {
		buf := make([]byte, 4)
		n, err := r.Read(buf)
		readDone <- readResult{data: string(buf[:n]), err: err}
	}()

	// Wait for the fetch to actually start inside Read.
	select {
	case <-fetcher.started:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for initial segment fetch to start")
	}

	if _, err := r.Seek(1, io.SeekStart); err != nil {
		t.Fatalf("Seek returned error: %v", err)
	}

	select {
	case res := <-readDone:
		t.Fatalf("foreground read returned before fetch release: data=%q err=%v", res.data, res.err)
	default:
	}

	releaseFetcher()

	select {
	case res := <-readDone:
		if res.err != nil {
			t.Fatalf("expected foreground read to succeed after seek, got %v", res.err)
		}
		if res.data != "aaaa" {
			t.Fatalf("expected foreground read data %q, got %q", "aaaa", res.data)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for foreground read to complete")
	}
}

type blockingForegroundSegmentFetcher struct {
	started   chan struct{}
	release   chan struct{}
	startOnce sync.Once
}

func (f *blockingForegroundSegmentFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	if segment.Number == 1 {
		f.startOnce.Do(func() { close(f.started) })
		select {
		case <-f.release:
		case <-ctx.Done():
			return pool.SegmentData{}, ctx.Err()
		}
	}
	return pool.SegmentData{Body: bytesForSegment(segment.Number, segment.Bytes), Size: segment.Bytes}, nil
}

type fixedLenSegmentFetcher struct {
	length int64
}

func (f *fixedLenSegmentFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	return pool.SegmentData{Body: bytesForSegment(segment.Number, f.length), Size: f.length}, nil
}

type staticSegmentFetcher struct{}

func (f *staticSegmentFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	return pool.SegmentData{Body: bytesForSegment(segment.Number, segment.Bytes), Size: segment.Bytes}, nil
}

func testNZBFile(subject string, sizes ...int64) *nzb.File {
	segments := make([]nzb.Segment, 0, len(sizes))
	for i, size := range sizes {
		segments = append(segments, nzb.Segment{
			ID:     "seg-" + strings.Repeat("x", i+1),
			Bytes:  size,
			Number: i + 1,
		})
	}
	return &nzb.File{Subject: subject, Segments: segments}
}

func bytesForSegment(number int, size int64) []byte {
	if size <= 0 {
		return nil
	}
	b := byte('a' + number - 1)
	data := make([]byte, int(size))
	for i := range data {
		data[i] = b
	}
	return data
}
