package loader

import (
	"context"
	"strings"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/nzb"
)

// Batched read-ahead.
//
// A segment fetch spends one idle round trip between the last byte of one
// article and the first byte of the next, because the BODY for the next one is
// only sent once the current reply is complete. Handing a connection several
// segments at once removes that gap: the provider is already sending article
// N+1 when article N ends.
//
// Depth and fan-out are substitutes, not additions. A read-ahead window is a
// fixed number of segments, so putting D of them on one connection uses D times
// fewer connections to cover it — and providers commonly throttle per
// connection, which is why anyone runs twenty of them. Spending depth is
// therefore only free when there is no idle connection left to spend instead.
//
// readAheadBatchSize is that rule: batch only what does not fit across the
// connections this stream can actually get. When the pool has room, batches are
// one segment and the path behaves exactly as it did before. When the stream is
// connection-starved — a per-stream cap, several streams sharing an account,
// read-ahead falling behind the read pointer — batches grow to depth and the
// same connections carry several articles each. That is precisely the case
// pipelining wins, and precisely the case extra goroutines would only have
// queued up inside getConnection.

// readAheadBatchSize spreads unclaimed segments across the connections still
// free (width minus what this file already has in flight), then caps the result
// at depth. It returns 1 when every segment can have a connection to itself.
func readAheadBatchSize(unclaimed, inFlight, width, depth int) int {
	if unclaimed < 2 || depth < 2 {
		return 1
	}
	if width < 1 {
		// No hint: assume connections are scarce rather than free, since that
		// is the only assumption that cannot idle one.
		width = 1
	}
	free := width - inFlight
	if free < 1 {
		free = 1
	}
	if unclaimed <= free {
		return 1
	}
	// Floor, not ceiling. Rounding up can leave a connection with nothing to
	// do — four segments over three connections rounds to batches of two, which
	// is two batches for three connections — and an idle connection costs more
	// than the extra goroutine that rounding down leaves queued.
	size := unclaimed / free
	if size > depth {
		size = depth
	}
	return size
}

// claimedSegment is one read-ahead index this file has taken leadership of and
// therefore owes a result to.
type claimedSegment struct {
	index int
	req   *inflightSegmentDownload
}

// ReadAheadRange warms segments [from, to) in the background without counting
// failures toward IsFailed(). Segments already being fetched are left to the
// fetch that owns them; the rest are grouped per readAheadBatchSize and handed
// to the fetcher's pipelined path when it has one.
func (f *File) ReadAheadRange(ctx context.Context, from, to int) {
	if from < 0 {
		from = 0
	}
	if to > len(f.segments) {
		to = len(f.segments)
	}
	if from >= to {
		return
	}

	// A window being asked for is the first hard evidence about what the
	// windows of closed readers were worth: whatever falls outside this one is
	// holding connections this one needs.
	f.reapAbandonedReadAhead(from, to)

	batcher, depth := f.batchFetcher()
	if depth < 2 {
		for i := from; i < to; i++ {
			f.ReadAheadSegment(ctx, i)
		}
		return
	}

	// Claim the whole window first: the batch size depends on how much of it is
	// genuinely unclaimed, which is only known once every index has been asked.
	claims := make([]claimedSegment, 0, to-from)
	inFlight := 0
	for i := from; i < to; i++ {
		if f.isZeroFilled(i) {
			continue
		}
		req, leader := f.startInflightDownload(i, false)
		if !leader {
			// Someone else owns this one; we are not among its waiters.
			f.releaseInflightDownloadWaiter(i, req)
			inFlight++
			continue
		}
		claims = append(claims, claimedSegment{index: i, req: req})
	}
	if len(claims) == 0 {
		return
	}

	size := readAheadBatchSize(len(claims), inFlight, f.fetchWidth(), depth)
	logger.Trace("File ReadAheadRange", "file", f.Name(), "from", from, "to", to,
		"claimed", len(claims), "inflight", inFlight, "batch", size)

	for start := 0; start < len(claims); start += size {
		end := start + size
		if end > len(claims) {
			end = len(claims)
		}
		batch := claims[start:end]
		if len(batch) == 1 {
			// One segment is what the ordinary path already does, minus a lease.
			c := batch[0]
			go f.runInflightDownload(c.index, c.req)
			continue
		}
		go f.runPipelinedReadAhead(ctx, batcher, batch)
	}
}

// batchFetcher returns the fetcher's pipelined path and its depth, or a zero
// depth when this fetcher has none or pipelining is switched off.
func (f *File) batchFetcher() (SegmentBatchFetcher, int) {
	batcher, ok := f.fetcher.(SegmentBatchFetcher)
	if !ok {
		return nil, 0
	}
	return batcher, batcher.PipelineDepth()
}

// fetchWidth is how many fetches the fetcher says it can keep on the wire, or 0
// when it does not say.
func (f *File) fetchWidth() int {
	if hinter, ok := f.fetcher.(FetchConcurrencyHinter); ok {
		return hinter.FetchConcurrency()
	}
	return 0
}

// runPipelinedReadAhead fetches a claimed batch over one connection and
// publishes each result. Segments the pipeline could not deliver — a 430, a
// connection that dropped mid-batch, a provider that never had them — fall back
// to the ordinary per-segment path, which is where provider failover, retries
// and the zero-fill policy live.
func (f *File) runPipelinedReadAhead(ctx context.Context, batcher SegmentBatchFetcher, batch []claimedSegment) {
	segments := make([]*nzb.Segment, len(batch))
	for i, c := range batch {
		segments[i] = &f.segments[c.index].Segment
	}

	results := batcher.FetchSegmentsPipelined(ctx, segments, f.nzbFile.Groups)
	for i, c := range batch {
		if i < len(results) && results[i].OK {
			if name := strings.TrimSpace(results[i].Data.FileName); name != "" {
				f.yencName.Store(name)
			}
			f.recordYencGeometry(c.index, results[i].Data)
			f.completeInflightDownload(c.index, c.req, results[i].Data.Body, nil)
			continue
		}
		// Still the leader for this index, so the fallback owes its waiters a
		// result exactly as the ordinary read-ahead path would have.
		go f.runInflightDownload(c.index, c.req)
	}
}
