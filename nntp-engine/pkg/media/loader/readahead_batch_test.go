package loader

import (
	"bytes"
	"context"
	"sync"
	"testing"
	"time"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

func TestReadAheadBatchSize(t *testing.T) {
	const depth = 3

	cases := []struct {
		name                       string
		unclaimed, inFlight, width int
		depth                      int
		want                       int
		why                        string
	}{
		{
			name:      "spare connections stay one segment each",
			unclaimed: 8, inFlight: 0, width: 20, depth: depth, want: 1,
			why: "depth and fan-out are substitutes; with connections idle, fan-out is free and pipelining would only give the provider fewer sockets to throttle",
		},
		{
			name:      "exactly enough connections",
			unclaimed: 10, inFlight: 0, width: 10, depth: depth, want: 1,
			why: "every segment can still have a connection to itself",
		},
		{
			name:      "in-flight fetches count against the width",
			unclaimed: 9, inFlight: 10, width: 10, depth: depth, want: depth,
			why: "the connections are already spoken for, so extra goroutines would only queue inside getConnection",
		},
		{
			name:      "partially contended",
			unclaimed: 12, inFlight: 2, width: 6, depth: depth, want: 3,
			why: "12 segments over 4 free connections is 3 each, which is under depth",
		},
		{
			name:      "spread stops at depth",
			unclaimed: 40, inFlight: 0, width: 2, depth: depth, want: depth,
			why: "past depth the only thing extra queueing buys is more bytes to throw away on a seek",
		},
		{
			name:      "single segment never batches",
			unclaimed: 1, inFlight: 8, width: 2, depth: depth, want: 1,
			why: "one article has nothing to pipeline behind it",
		},
		{
			name:      "pipelining disabled",
			unclaimed: 24, inFlight: 24, width: 4, depth: 0, want: 1,
			why: "depth below 2 is the escape hatch and must hold on every path",
		},
		{
			name:      "no width hint assumes contention",
			unclaimed: 6, inFlight: 0, width: 0, depth: depth, want: depth,
			why: "a fetcher that cannot say how wide it is must not be assumed wide, since guessing wide idles connections",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := readAheadBatchSize(tc.unclaimed, tc.inFlight, tc.width, tc.depth)
			if got != tc.want {
				t.Fatalf("readAheadBatchSize(unclaimed=%d, inFlight=%d, width=%d, depth=%d) = %d, want %d — %s",
					tc.unclaimed, tc.inFlight, tc.width, tc.depth, got, tc.want, tc.why)
			}
		})
	}
}

// The batch must never be so LARGE that connections sit idle: covering the
// unclaimed segments at the chosen size has to produce at least one batch per
// free connection. Erring the other way only queues a goroutine, which is what
// the unbatched path did anyway.
func TestReadAheadBatchSizeNeverIdlesAConnection(t *testing.T) {
	for width := 1; width <= 32; width++ {
		for inFlight := 0; inFlight <= width; inFlight++ {
			for unclaimed := 1; unclaimed <= 48; unclaimed++ {
				size := readAheadBatchSize(unclaimed, inFlight, width, 3)
				batches := (unclaimed + size - 1) / size
				free := width - inFlight
				if free < 1 {
					free = 1
				}
				if batches < free && batches < unclaimed {
					t.Fatalf("unclaimed=%d inFlight=%d width=%d: size %d makes %d batches for %d free connections",
						unclaimed, inFlight, width, size, batches, free)
				}
			}
		}
	}
}

// batchingFetcher is a damagedSegmentFetcher that also implements the pipelined
// path, recording which segments arrived as a batch and how large each batch
// was. Segments in refuse are handed back unfetched, standing in for a 430 or a
// connection that dropped mid-batch.
type batchingFetcher struct {
	*damagedSegmentFetcher
	depth  int
	width  int
	refuse map[int]struct{}

	// fellBack fires once the first declined segment reaches the ordinary
	// per-segment path, so a test can wait for the fallback instead of racing it.
	fellBack chan int

	mu      sync.Mutex
	batches [][]int
}

func newBatchingFetcher(segmentSize, depth, width int, refuse ...int) *batchingFetcher {
	declined := make(map[int]struct{}, len(refuse))
	for _, idx := range refuse {
		declined[idx] = struct{}{}
	}
	return &batchingFetcher{
		damagedSegmentFetcher: newDamagedSegmentFetcher(segmentSize),
		depth:                 depth,
		width:                 width,
		refuse:                declined,
		fellBack:              make(chan int, len(declined)+1),
	}
}

// FetchSegment is the ordinary per-segment path. Anything the pipeline declined
// arrives here, which is the whole contract between the two.
func (f *batchingFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	data, err := f.damagedSegmentFetcher.FetchSegment(ctx, segment, groups)
	if _, declined := f.refuse[segment.Number-1]; declined {
		select {
		case f.fellBack <- segment.Number - 1:
		default:
		}
	}
	return data, err
}

func (f *batchingFetcher) PipelineDepth() int    { return f.depth }
func (f *batchingFetcher) FetchConcurrency() int { return f.width }

func (f *batchingFetcher) FetchSegmentsPipelined(_ context.Context, segments []*nzb.Segment, _ []string) []pool.PipelinedResult {
	results := make([]pool.PipelinedResult, len(segments))
	indices := make([]int, len(segments))
	for i, seg := range segments {
		index := seg.Number - 1
		indices[i] = index
		if _, declined := f.refuse[index]; declined {
			continue
		}
		results[i] = pool.PipelinedResult{
			Data: pool.SegmentData{Body: segmentPayload(index, f.segmentSize)},
			OK:   true,
		}
	}
	f.mu.Lock()
	f.batches = append(f.batches, indices)
	f.mu.Unlock()
	return results
}

func (f *batchingFetcher) batchSizes() []int {
	f.mu.Lock()
	defer f.mu.Unlock()
	sizes := make([]int, len(f.batches))
	for i, b := range f.batches {
		sizes[i] = len(b)
	}
	return sizes
}

// awaitSegment blocks until index has a result, which is how a reader that
// catches up with read-ahead observes it.
func awaitSegment(t *testing.T, f *File, index int) []byte {
	t.Helper()
	data, err := f.DownloadSegment(context.Background(), index)
	if err != nil {
		t.Fatalf("segment %d: %v", index, err)
	}
	return data
}

// A contended stream batches, and every segment in the window still reads back
// correctly — the batch owes a result to exactly the indices it claimed.
func TestReadAheadRangePipelinesWhenConnectionsAreScarce(t *testing.T) {
	const segments, segmentSize = 12, 256
	fetcher := newBatchingFetcher(segmentSize, 3, 2)
	f := NewFile(context.Background(), damagedNZBFile(segments, segmentSize), nil, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}

	f.ReadAheadRange(context.Background(), 0, segments)

	for i := 0; i < segments; i++ {
		if got := awaitSegment(t, f, i); !bytes.Equal(got, segmentPayload(i, segmentSize)) {
			t.Fatalf("segment %d read back wrong after a pipelined read-ahead", i)
		}
	}
	sizes := fetcher.batchSizes()
	if len(sizes) == 0 {
		t.Fatal("no batch was pipelined on a stream with two connections and twelve segments")
	}
	for _, size := range sizes {
		if size < 2 || size > 3 {
			t.Fatalf("batch sizes = %v, want every batch between 2 and depth 3", sizes)
		}
	}
}

// With connections to spare, batching would trade fan-out the stream is not
// short of for depth it does not need.
func TestReadAheadRangeSkipsPipelineWhenConnectionsAreFree(t *testing.T) {
	const segments, segmentSize = 8, 256
	fetcher := newBatchingFetcher(segmentSize, 3, 32)
	f := NewFile(context.Background(), damagedNZBFile(segments, segmentSize), nil, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}

	f.ReadAheadRange(context.Background(), 0, segments)
	for i := 0; i < segments; i++ {
		awaitSegment(t, f, i)
	}

	if sizes := fetcher.batchSizes(); len(sizes) != 0 {
		t.Fatalf("batched %v with 32 connections free, want the one-segment-per-connection path", sizes)
	}
}

// Anything the pipeline declines is still owed a result, and the fallback has
// to produce it the ordinary way — otherwise a reader waiting on that index
// blocks until its context expires. Segments the pipeline did deliver must
// never reach that path, or batching would double every article it fetched.
func TestReadAheadRangeFallsBackForDeclinedSegments(t *testing.T) {
	const segments, segmentSize = 9, 256
	fetcher := newBatchingFetcher(segmentSize, 3, 1, 4)
	f := NewFile(context.Background(), damagedNZBFile(segments, segmentSize), nil, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}

	// Sizing the file already fetched some segments, so the claim below is about
	// what read-ahead adds, not about the absolute count — and any fallback
	// signal those fetches left buffered has to go with them.
	base := make([]int, segments)
	for i := range base {
		base[i] = fetcher.fetchCount(i)
	}
	for drained := false; !drained; {
		select {
		case <-fetcher.fellBack:
		default:
			drained = true
		}
	}

	f.ReadAheadRange(context.Background(), 0, segments)

	select {
	case <-fetcher.fellBack:
	case <-time.After(5 * time.Second):
		t.Fatal("the declined segment never reached the per-segment path; a reader would block on it forever")
	}

	// Deltas are read before any demand read, so nothing below can add to them:
	// the fake fetcher has no cache, and a demand read of an already-retired
	// index would legitimately fetch again.
	if got := fetcher.fetchCount(4) - base[4]; got != 1 {
		t.Fatalf("declined segment fetched %d times individually, want 1", got)
	}
	for i := 0; i < segments; i++ {
		if i == 4 {
			continue
		}
		if got := fetcher.fetchCount(i) - base[i]; got != 0 {
			t.Fatalf("segment %d was delivered by the pipeline but also fetched %d times individually", i, got)
		}
	}
	if got := awaitSegment(t, f, 4); !bytes.Equal(got, segmentPayload(4, segmentSize)) {
		t.Fatal("the declined segment did not read back correctly")
	}
}

// A fetcher with no pipelined path keeps the behaviour it had before batching
// existed.
func TestReadAheadRangeWithoutBatchSupport(t *testing.T) {
	const segments, segmentSize = 6, 256
	fetcher := newDamagedSegmentFetcher(segmentSize)
	f := NewFile(context.Background(), damagedNZBFile(segments, segmentSize), nil, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}

	f.ReadAheadRange(context.Background(), 0, segments)
	for i := 0; i < segments; i++ {
		if got := awaitSegment(t, f, i); !bytes.Equal(got, segmentPayload(i, segmentSize)) {
			t.Fatalf("segment %d read back wrong", i)
		}
	}
}
