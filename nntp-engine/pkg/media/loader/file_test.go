package loader

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/textproto"
	"sort"
	"sync"
	"testing"
	"time"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/decode"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
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

func TestShouldPersistDownloadedSegment(t *testing.T) {
	if !shouldPersistDownloadedSegment(nil) {
		t.Fatal("nil context should allow segment persistence")
	}
	if !shouldPersistDownloadedSegment(context.Background()) {
		t.Fatal("active context should allow segment persistence")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if shouldPersistDownloadedSegment(ctx) {
		t.Fatal("canceled context should block segment persistence")
	}
}

type trackingReadCloser struct {
	io.Reader
	closeCalls int
}

func (t *trackingReadCloser) Close() error {
	t.closeCalls++
	return nil
}

func TestDecodeAndCloseBodyClosesOnSuccess(t *testing.T) {
	body := &trackingReadCloser{Reader: bytes.NewBufferString("abc")}
	frame := &decode.Frame{Data: []byte("ok")}

	got, err := decodeAndCloseBody(body, func(r io.Reader) (*decode.Frame, error) {
		_, readErr := io.ReadAll(r)
		return frame, readErr
	})
	if err != nil {
		t.Fatalf("decodeAndCloseBody returned error: %v", err)
	}
	if got != frame {
		t.Fatal("decodeAndCloseBody returned unexpected frame")
	}
	if body.closeCalls != 1 {
		t.Fatalf("expected body to be closed once, got %d", body.closeCalls)
	}
}

func TestDecodeAndCloseBodyClosesOnError(t *testing.T) {
	body := &trackingReadCloser{Reader: bytes.NewBufferString("abc")}
	wantErr := errors.New("decode failed")

	got, err := decodeAndCloseBody(body, func(r io.Reader) (*decode.Frame, error) {
		_, _ = io.ReadAll(r)
		return nil, wantErr
	})
	if !errors.Is(err, wantErr) {
		t.Fatalf("expected %v, got %v", wantErr, err)
	}
	if got != nil {
		t.Fatal("expected nil frame on error")
	}
	if body.closeCalls != 1 {
		t.Fatalf("expected body to be closed once, got %d", body.closeCalls)
	}
}

type dedupBlockingSegmentFetcher struct {
	data         []byte
	err          error
	callObserved chan int
	release      chan struct{}
	once         sync.Once
	mu           sync.Mutex
	calls        int
}

func newDedupBlockingSegmentFetcher(data []byte, err error) *dedupBlockingSegmentFetcher {
	return &dedupBlockingSegmentFetcher{
		data:         data,
		err:          err,
		callObserved: make(chan int, 4),
		release:      make(chan struct{}),
	}
}

func (f *dedupBlockingSegmentFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	f.mu.Lock()
	f.calls++
	call := f.calls
	f.mu.Unlock()

	f.callObserved <- call

	select {
	case <-ctx.Done():
		return pool.SegmentData{}, ctx.Err()
	case <-f.release:
	}

	if f.err != nil {
		return pool.SegmentData{}, f.err
	}
	return pool.SegmentData{Body: append([]byte(nil), f.data...)}, nil
}

func (f *dedupBlockingSegmentFetcher) Release() {
	f.once.Do(func() {
		close(f.release)
	})
}

func testNZBFileWithSegments(sizes ...int64) *nzb.File {
	segments := make([]nzb.Segment, len(sizes))
	for i, size := range sizes {
		segments[i] = nzb.Segment{ID: fmt.Sprintf("<seg-%d>", i), Number: i + 1, Bytes: size}
	}
	return &nzb.File{Subject: "test.mkv", Groups: []string{"alt.test"}, Segments: segments}
}

// testNZBFileWithNSegments builds a file of n equally sized segments, so the
// nine sampled indices in CheckFirstSegmentExists land on distinct articles.
func testNZBFileWithNSegments(n int) *nzb.File {
	sizes := make([]int64, n)
	for i := range sizes {
		sizes[i] = 1024
	}
	return testNZBFileWithSegments(sizes...)
}

func TestDownloadSegmentDeduplicatesConcurrentCalls(t *testing.T) {
	fetcher := newDedupBlockingSegmentFetcher([]byte("abc"), nil)
	f := NewFile(context.Background(), testNZBFileWithSegments(3), nil, fetcher)

	results := make(chan []byte, 2)
	errs := make(chan error, 2)
	go func() {
		data, err := f.DownloadSegment(context.Background(), 0)
		results <- data
		errs <- err
	}()

	if got := <-fetcher.callObserved; got != 1 {
		t.Fatalf("expected first fetch call to be 1, got %d", got)
	}

	go func() {
		data, err := f.DownloadSegment(context.Background(), 0)
		results <- data
		errs <- err
	}()

	select {
	case got := <-fetcher.callObserved:
		t.Fatalf("expected one underlying fetch, saw call %d", got)
	case <-time.After(100 * time.Millisecond):
	}

	fetcher.Release()
	for i := 0; i < 2; i++ {
		if err := <-errs; err != nil {
			t.Fatalf("DownloadSegment returned error: %v", err)
		}
		if got := string(<-results); got != "abc" {
			t.Fatalf("expected shared data %q, got %q", "abc", got)
		}
	}
}

func TestConcurrentDownloadFailureCountsOnce(t *testing.T) {
	fetcher := newDedupBlockingSegmentFetcher(nil, fmt.Errorf("fetch segment: %w", &textproto.Error{Code: 430, Msg: "No Such Article"}))
	f := NewFile(context.Background(), testNZBFileWithSegments(3, 4), nil, fetcher)

	results := make(chan []byte, 2)
	errs := make(chan error, 2)
	go func() {
		data, err := f.DownloadSegment(context.Background(), 1)
		results <- data
		errs <- err
	}()

	if got := <-fetcher.callObserved; got != 1 {
		t.Fatalf("expected first fetch call to be 1, got %d", got)
	}

	go func() {
		data, err := f.DownloadSegment(context.Background(), 1)
		results <- data
		errs <- err
	}()

	select {
	case got := <-fetcher.callObserved:
		t.Fatalf("expected one underlying failing fetch, saw call %d", got)
	case <-time.After(100 * time.Millisecond):
	}

	fetcher.Release()
	for i := 0; i < 2; i++ {
		if err := <-errs; err != nil {
			t.Fatalf("DownloadSegment returned error: %v", err)
		}
		if got := <-results; len(got) != 4 {
			t.Fatalf("expected zero-filled segment of length 4, got %d", len(got))
		}
	}
	if got := f.ZeroFilledSegments(); got != 1 {
		t.Fatalf("expected one shared zero-fill count, got %d", got)
	}
}

func TestOpenStreamCtxUsesProvidedContextForSegmentDetection(t *testing.T) {
	fetcher := newDedupBlockingSegmentFetcher([]byte("abc"), nil)
	f := NewFile(context.Background(), testNZBFileWithSegments(3), nil, fetcher)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	stream, err := f.OpenStreamCtx(ctx)
	if stream != nil {
		_ = stream.Close()
	}
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}

	fetcher.Release()
}

func TestDownloadSegmentLeaderCancellationDoesNotCancelFollower(t *testing.T) {
	fetcher := newDedupBlockingSegmentFetcher([]byte("abc"), nil)
	f := NewFile(context.Background(), testNZBFileWithSegments(3), nil, fetcher)

	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	defer cancelLeader()

	leaderErrs := make(chan error, 1)
	go func() {
		_, err := f.DownloadSegment(leaderCtx, 0)
		leaderErrs <- err
	}()

	if got := <-fetcher.callObserved; got != 1 {
		t.Fatalf("expected first fetch call to be 1, got %d", got)
	}

	followerData := make(chan []byte, 1)
	followerErrs := make(chan error, 1)
	go func() {
		data, err := f.DownloadSegment(context.Background(), 0)
		followerData <- data
		followerErrs <- err
	}()

	select {
	case got := <-fetcher.callObserved:
		t.Fatalf("expected follower to join existing fetch, saw call %d", got)
	case <-time.After(100 * time.Millisecond):
	}

	cancelLeader()

	select {
	case err := <-leaderErrs:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected leader error %v, got %v", context.Canceled, err)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for leader cancellation")
	}

	fetcher.Release()

	select {
	case err := <-followerErrs:
		if err != nil {
			t.Fatalf("follower DownloadSegment returned error: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for follower result")
	}
	if got := string(<-followerData); got != "abc" {
		t.Fatalf("expected follower data %q, got %q", "abc", got)
	}
}

func TestDownloadSegmentReusesWaiterlessInflightRequest(t *testing.T) {
	fetcher := newDedupBlockingSegmentFetcher([]byte("fresh"), nil)
	f := NewFile(context.Background(), testNZBFileWithSegments(5), nil, fetcher)

	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	defer cancelLeader()

	leaderErrs := make(chan error, 1)
	go func() {
		_, err := f.DownloadSegment(leaderCtx, 0)
		leaderErrs <- err
	}()

	if got := <-fetcher.callObserved; got != 1 {
		t.Fatalf("expected first fetch call to be 1, got %d", got)
	}

	cancelLeader()

	select {
	case err := <-leaderErrs:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected leader error %v, got %v", context.Canceled, err)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for leader cancellation")
	}

	select {
	case got := <-fetcher.callObserved:
		t.Fatalf("expected waiterless inflight request to remain reusable, saw call %d", got)
	case <-time.After(100 * time.Millisecond):
	}

	followerData := make(chan []byte, 1)
	followerErrs := make(chan error, 1)
	go func() {
		data, err := f.DownloadSegment(context.Background(), 0)
		followerData <- data
		followerErrs <- err
	}()

	select {
	case got := <-fetcher.callObserved:
		t.Fatalf("expected follower to reuse waiterless in-flight request, saw call %d", got)
	case <-time.After(100 * time.Millisecond):
	}

	fetcher.Release()

	select {
	case err := <-followerErrs:
		if err != nil {
			t.Fatalf("follower DownloadSegment returned error: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for follower result")
	}
	if got := string(<-followerData); got != "fresh" {
		t.Fatalf("expected follower data %q, got %q", "fresh", got)
	}
}

type varyingSizeSegmentFetcher struct {
	sizes []int64
	mu    sync.Mutex
	calls []int
}

func (f *varyingSizeSegmentFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	idx := segment.Number - 1
	if idx < 0 || idx >= len(f.sizes) {
		return pool.SegmentData{}, fmt.Errorf("unexpected segment number %d", segment.Number)
	}
	f.mu.Lock()
	f.calls = append(f.calls, segment.Number)
	f.mu.Unlock()
	size := f.sizes[idx]
	data := bytes.Repeat([]byte{byte('a' + idx)}, int(size))
	return pool.SegmentData{Body: data, Size: size}, nil
}

func (f *varyingSizeSegmentFetcher) Calls() []int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]int(nil), f.calls...)
}

func TestEnsureSegmentMapUsesActualLastSegmentSize(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{8, 8, 5}}
	f := NewFile(context.Background(), testNZBFileWithSegments(10, 10, 10), nil, fetcher)

	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	if got := f.Size(); got != 21 {
		t.Fatalf("expected decoded size %d, got %d", 21, got)
	}
	segs := f.Segments()
	if got := segs[2].StartOffset; got != 16 {
		t.Fatalf("expected last segment start offset %d, got %d", 16, got)
	}
	if got := segs[2].EndOffset; got != 21 {
		t.Fatalf("expected last segment end offset %d, got %d", 21, got)
	}
	calls := fetcher.Calls()
	if !segmentProbeCallsMatch(calls, []int{1, 2, 3}) {
		t.Fatalf("expected first, middle, and last segment probes, got %v", calls)
	}
	reader, err := f.OpenReaderAt(context.Background(), 16)
	if err != nil {
		t.Fatalf("OpenReaderAt returned error: %v", err)
	}
	defer func() { _ = reader.Close() }()
	data, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("ReadAll returned error: %v", err)
	}
	if got := string(data); got != "ccccc" {
		t.Fatalf("expected tail data %q, got %q", "ccccc", got)
	}
}

func TestEnsureSegmentMapUsesPerSegmentNZBBytes(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{8, 12, 5}}
	f := NewFile(context.Background(), testNZBFileWithSegments(10, 15, 10), nil, fetcher)

	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	if got := f.Size(); got != 25 {
		t.Fatalf("expected decoded size %d, got %d", 25, got)
	}
	segs := f.Segments()
	if got := segs[1].StartOffset; got != 8 {
		t.Fatalf("expected second segment start offset %d, got %d", 8, got)
	}
	if got := segs[1].EndOffset; got != 20 {
		t.Fatalf("expected second segment end offset %d, got %d", 20, got)
	}
	calls := fetcher.Calls()
	if len(calls) != 3 {
		t.Fatalf("expected three parallel probe fetches (distinct NZB bytes + last), got %v", calls)
	}
}

func TestEnsureSegmentMapSlowModeCalibratesUniformNZB(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{8, 10, 5}}
	f := NewFile(context.Background(), testNZBFileWithSegments(10, 10, 10), nil, fetcher)

	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	if got := f.Size(); got != 23 {
		t.Fatalf("expected decoded size %d, got %d", 23, got)
	}
	calls := fetcher.Calls()
	if !segmentProbeCallsMatch(calls, []int{1, 2, 3}) {
		t.Fatalf("expected first, middle, and last segment probes, got %v", calls)
	}
}

func TestEnsureSegmentMapFastModeGapProbesSkippedMiddle(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{8, 10, 5}}
	ctx := context.Background()
	f := NewFile(ctx, testNZBFileWithSegments(10, 10, 10), nil, fetcher)

	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMapCtx returned error: %v", err)
	}
	if got := f.Size(); got != 23 {
		t.Fatalf("expected decoded size %d after gap probe, got %d", 23, got)
	}
	calls := fetcher.Calls()
	if !segmentProbeCallsMatch(calls, []int{1, 2, 3}) {
		t.Fatalf("expected first, gap middle, and last probes in fast mode, got %v", calls)
	}
}

func segmentProbeCallsMatch(calls []int, want []int) bool {
	if len(calls) != len(want) {
		return false
	}
	got := append([]int(nil), calls...)
	sort.Ints(got)
	expected := append([]int(nil), want...)
	sort.Ints(expected)
	for i := range expected {
		if got[i] != expected[i] {
			return false
		}
	}
	return true
}

func TestEnsureSegmentMapEstimatorPrimesWithoutNNTPProbes(t *testing.T) {
	estimator := NewSegmentSizeEstimator()
	estimator.Set(10, 8)
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{8, 8, 5}}
	f := NewFile(context.Background(), testNZBFileWithSegments(10, 10, 10), estimator, fetcher)

	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	// Fast mode is always enabled: when the estimator knows the decoded size for
	// uniform NZB bytes, the segment map is primed without any NNTP probes.
	// The last segment uses the estimator's value (8) rather than its actual
	// decoded size (5) to avoid the extra probe that would delay startup.
	if got := f.Size(); got != 24 {
		t.Fatalf("expected primed decoded size %d, got %d", 24, got)
	}
	if calls := fetcher.Calls(); len(calls) != 0 {
		t.Fatalf("expected no NNTP probes when estimator primes uniform segment map, got %v", calls)
	}
}

func TestEnsureSegmentMapWithSkipGapProbing(t *testing.T) {
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{8, 10, 5}}
	ctx := context.Background()
	ctx = WithSkipGapProbing(ctx, true)
	f := NewFile(ctx, testNZBFileWithSegments(10, 10, 6), nil, fetcher)

	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMapCtx returned error: %v", err)
	}
	if got := f.Size(); got != 21 {
		t.Fatalf("expected decoded size %d, got %d", 21, got)
	}
	calls := fetcher.Calls()
	if !segmentProbeCallsMatch(calls, []int{1, 3}) {
		t.Fatalf("expected only first and last segment probes, got %v", calls)
	}
}

func TestPrimeUniformSegmentMapFromEstimatorSkipsNNTPProbes(t *testing.T) {
	estimator := NewSegmentSizeEstimator()
	estimator.Set(768000, 768000)
	fetcher := &varyingSizeSegmentFetcher{sizes: []int64{768000, 768000, 768000}}
	ctx := context.Background()
	f := NewFile(ctx, testNZBFileWithSegments(768000, 768000, 768000), estimator, fetcher)

	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMapCtx returned error: %v", err)
	}
	if got := f.Size(); got != 3*768000 {
		t.Fatalf("expected decoded size %d, got %d", 3*768000, got)
	}
	if calls := fetcher.Calls(); len(calls) != 0 {
		t.Fatalf("expected no NNTP probes for uniform primed volume, got %v", calls)
	}
}

// statSpyFetcher answers STATs from a per-message-ID script and records how
// many ran at once.
type statSpyFetcher struct {
	mu         sync.Mutex
	calls      int
	inFlight   int
	maxInFlopt int
	hint       int
	// answer is consulted per message id; a nil entry means "exists".
	answer func(msgID string) (bool, error)
	// block, when non-nil, holds every STAT until closed.
	block chan struct{}
}

func (s *statSpyFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	return pool.SegmentData{}, errors.New("not used")
}

func (s *statSpyFetcher) StatSegment(ctx context.Context, msgID string, groups []string) (bool, error) {
	s.mu.Lock()
	s.calls++
	s.inFlight++
	if s.inFlight > s.maxInFlopt {
		s.maxInFlopt = s.inFlight
	}
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.inFlight--
		s.mu.Unlock()
	}()

	if s.block != nil {
		select {
		case <-s.block:
		case <-ctx.Done():
			return false, ctx.Err()
		}
	}
	if s.answer != nil {
		return s.answer(msgID)
	}
	return true, nil
}

func (s *statSpyFetcher) StatConcurrency() int { return s.hint }

func (s *statSpyFetcher) stats() (calls, maxInFlight int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.calls, s.maxInFlopt
}

func TestCheckFirstSegmentExistsAllPresent(t *testing.T) {
	fetcher := &statSpyFetcher{hint: 4}
	f := NewFile(context.Background(), testNZBFileWithNSegments(40), nil, fetcher)

	exists, err := f.CheckFirstSegmentExists(context.Background())
	if err != nil || !exists {
		t.Fatalf("expected the file to validate, got exists=%v err=%v", exists, err)
	}
	// Head 0-4 and the last segment, plus nine evenly spread points; the
	// overlaps collapse to 12 distinct indices for a 40-segment file.
	if calls, _ := fetcher.stats(); calls != 12 {
		t.Fatalf("expected the 12 sampled segments to be probed, got %d", calls)
	}
}

func TestStatSampleSpreadScalesWithFileSize(t *testing.T) {
	if got := statSampleSpread(500); got != 9 {
		t.Fatalf("statSampleSpread(500) = %d, want the floor of 9", got)
	}
	if got := statSampleSpread(20_000); got != 20 {
		t.Fatalf("statSampleSpread(20000) = %d, want 20", got)
	}
	if got := statSampleSpread(1_000_000); got != 32 {
		t.Fatalf("statSampleSpread(1000000) = %d, want the cap of 32", got)
	}
}

// The whole point of the change: the sampled segments go out together instead
// of one round trip at a time.
func TestCheckFirstSegmentExistsProbesSegmentsConcurrently(t *testing.T) {
	fetcher := &statSpyFetcher{hint: 4, block: make(chan struct{})}
	f := NewFile(context.Background(), testNZBFileWithNSegments(40), nil, fetcher)

	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = f.CheckFirstSegmentExists(context.Background())
	}()

	time.Sleep(50 * time.Millisecond)
	_, maxInFlight := fetcher.stats()
	close(fetcher.block)
	<-done

	if maxInFlight != 4 {
		t.Fatalf("expected 4 concurrent STATs from the hint, saw %d", maxInFlight)
	}
}

// The important one: cancelling the siblings must not turn a definitive 430
// into an inconclusive error, or bad releases stop being marked bad.
func TestCheckFirstSegmentExistsDefinitiveMissBeatsSiblingCancellation(t *testing.T) {
	var missOnce sync.Once
	fetcher := &statSpyFetcher{
		hint:  8,
		block: make(chan struct{}),
	}
	// One STAT reports a definitive miss immediately; the rest block until the
	// cancellation caused by that miss releases them with a context error.
	fetcher.answer = func(msgID string) (bool, error) {
		definitive := false
		missOnce.Do(func() { definitive = true })
		if definitive {
			return false, nil
		}
		return true, nil
	}

	f := NewFile(context.Background(), testNZBFileWithNSegments(40), nil, fetcher)

	// Unblock everything: blocked siblings observe ctx cancellation instead.
	go func() {
		time.Sleep(20 * time.Millisecond)
		close(fetcher.block)
	}()

	exists, err := f.CheckFirstSegmentExists(context.Background())
	if exists {
		t.Fatal("expected the file to be reported missing")
	}
	if err != nil {
		t.Fatalf("a definitive miss must report (false, nil), not an error: %v", err)
	}
}

func TestCheckFirstSegmentExistsTransientErrorIsInconclusive(t *testing.T) {
	boom := errors.New("connection reset")
	fetcher := &statSpyFetcher{
		hint: 4,
		answer: func(msgID string) (bool, error) {
			return false, boom
		},
	}
	f := NewFile(context.Background(), testNZBFileWithNSegments(40), nil, fetcher)

	exists, err := f.CheckFirstSegmentExists(context.Background())
	if exists {
		t.Fatal("expected exists=false on a transient error")
	}
	if !errors.Is(err, boom) {
		t.Fatalf("expected the transient error to surface, got %v", err)
	}
}

func TestCheckFirstSegmentExistsRespectsConcurrencyHint(t *testing.T) {
	fetcher := &statSpyFetcher{hint: 2, block: make(chan struct{})}
	f := NewFile(context.Background(), testNZBFileWithNSegments(40), nil, fetcher)

	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = f.CheckFirstSegmentExists(context.Background())
	}()

	time.Sleep(50 * time.Millisecond)
	_, maxInFlight := fetcher.stats()
	close(fetcher.block)
	<-done

	if maxInFlight > 2 {
		t.Fatalf("hint of 2 exceeded: %d STATs in flight", maxInFlight)
	}
}

func TestCheckFirstSegmentExistsCachesResult(t *testing.T) {
	fetcher := &statSpyFetcher{hint: 4}
	f := NewFile(context.Background(), testNZBFileWithNSegments(40), nil, fetcher)

	if _, err := f.CheckFirstSegmentExists(context.Background()); err != nil {
		t.Fatalf("first check failed: %v", err)
	}
	firstCalls, _ := fetcher.stats()
	if _, err := f.CheckFirstSegmentExists(context.Background()); err != nil {
		t.Fatalf("second check failed: %v", err)
	}
	if calls, _ := fetcher.stats(); calls != firstCalls {
		t.Fatalf("expected the cached result to skip STATs, calls went %d -> %d", firstCalls, calls)
	}
}
