package pool

import (
	"context"
	"time"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/decode"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
)

// DefaultPipelineDepth is how many BODY commands a read-ahead batch keeps
// outstanding on one connection. Depth hides one round trip per extra slot, and
// the depth that hides an RTT entirely is 1 + RTT/(article/bandwidth): a 768 KB
// article on a 100 Mbit connection at 80 ms is covered by 3. Past that the only
// thing extra depth buys is more bytes already committed to a connection when
// the viewer seeks, so this stays well below the 5-10 a bulk downloader uses.
const DefaultPipelineDepth = 3

// MaxPipelineDepth caps what an operator can configure. At a segment per slot,
// depth is also how much of a seek's worth of traffic is already in flight and
// has to be thrown away.
const MaxPipelineDepth = 8

// PipelinedResult is one segment's outcome from FetchSegmentsPipelined,
// aligned with the input slice by index. OK false means the caller still owns
// that segment and must fetch it the ordinary way.
type PipelinedResult struct {
	Data SegmentData
	OK   bool
}

// PipelineDepth reports the deepest pipeline any provider in this view offers,
// or 0 when none of them pipeline. Read-ahead uses it to size a batch before a
// provider has been chosen, so the ceiling is the right answer: a batch larger
// than the provider it lands on still fetches every segment, just with fewer of
// them in flight at a time.
func (p *Pool) PipelineDepth() int {
	if p == nil {
		return 0
	}
	p.mu.RLock()
	defer p.mu.RUnlock()

	deepest := 0
	for i := range p.providers {
		if p.providers[i].IsBackup {
			continue
		}
		if d := p.depthForLocked(&p.providers[i]); d > deepest {
			deepest = d
		}
	}
	return deepest
}

// depthForLocked resolves one provider's pipeline depth against the pool
// default. It must be called with p.mu held.
func (p *Pool) depthForLocked(prov *ProviderConfig) int {
	if prov.PipelineDepth != 0 {
		return pipelineDepthOrDefault(prov.PipelineDepth)
	}
	return p.pipelineDepth
}

// pipelineDepthFor resolves the depth to use on a leased connection.
func (p *Pool) pipelineDepthFor(providerID string) int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	for i := range p.providers {
		if p.providers[i].ID == providerID {
			return p.depthForLocked(&p.providers[i])
		}
	}
	return p.pipelineDepth
}

// FetchSegmentsPipelined fetches segments in order over a single leased
// connection, keeping PipelineDepth BODY commands outstanding so the next
// article is already on the wire when the current one ends.
//
// It is an optimistic fast path, not a replacement for FetchSegment. It talks
// to exactly one provider and abandons the whole remainder of the batch the
// moment the connection misbehaves, because failover inside a pipeline would
// mean re-issuing commands whose replies are already in flight. Every segment
// it does not return with OK is the caller's to fetch the normal way, where
// provider failover, the retry loop and 430 accounting live.
//
// 430s are therefore reported as "not fetched" and left unrecorded: one
// provider refusing an article proves nothing until the rest have been asked,
// and the fallback path is the one that asks. Successes are recorded here,
// since nothing else will see them.
func (p *Pool) FetchSegmentsPipelined(ctx context.Context, segments []*nzb.Segment, groups []string) []PipelinedResult {
	results := make([]PipelinedResult, len(segments))
	if p == nil || p.PipelineDepth() < 2 {
		return results
	}

	// Anything already answerable without the network is answered here, so the
	// batch spends its connection only on articles that need one.
	pending := make([]int, 0, len(segments))
	for i, seg := range segments {
		if seg == nil {
			continue
		}
		messageID := seg.ID
		if p.isKnownMissing(messageID) {
			continue
		}
		if data, ok := p.cache.Get(messageID); ok {
			results[i] = PipelinedResult{Data: data, OK: true}
			continue
		}
		pending = append(pending, i)
	}
	// One article is exactly what Client.Body already does, minus a lease.
	if len(pending) < 2 {
		return results
	}

	fetchCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()

	conn, release, discard, providerID, err := p.getConnection(fetchCtx, nil, 999, false)
	if err != nil {
		logger.Trace("pipelined fetch no connection", "err", err)
		return results
	}
	host := p.Host(providerID)

	// The depth belongs to the provider we actually got, not to the pool: a
	// batch sized against the deepest provider can land on one that pipelines
	// less, or not at all.
	depth := p.pipelineDepthFor(providerID)

	p.activeFetches.Add(1)
	defer p.activeFetches.Add(-1)

	// Interrupt a pending body read if the stream is closed or the viewer seeks.
	stopWatch := make(chan struct{})
	go func() {
		select {
		case <-fetchCtx.Done():
			discard()
		case <-stopWatch:
		}
	}()

	// A pipeline that lost sync leaves unread article bytes on the socket, so
	// the connection is poison for whoever checks it out next. Retiring the
	// watchdog and handing the connection back happen in one defer, in that
	// order: a watchdog still live past the release could discard a connection
	// another fetch has already checked out.
	poisoned := false
	defer func() {
		close(stopWatch)
		if poisoned {
			discard()
			return
		}
		release()
	}()

	if depth < 2 {
		logger.Trace("pipelined fetch declined by provider", "provider", providerID, "depth", depth)
		return results
	}

	if len(groups) > 0 {
		if err := conn.Group(groups[0]); err != nil {
			if nntp.IsBenignDisconnect(err) || fetchCtx.Err() != nil {
				logger.Trace("pipelined fetch group cancelled", "provider", providerID, "err", err)
			} else {
				logger.Debug("pipelined fetch group failed", "provider", providerID, "err", err)
			}
			return results
		}
	}

	pipe := conn.NewBodyPipeline(depth)
	fetched := 0
	issued := 0
	consumed := 0

	for consumed < len(pending) {
		for issued < len(pending) && pipe.Len() < pipe.Depth() {
			if err := pipe.Issue(segments[pending[issued]].ID); err != nil {
				break
			}
			issued++
		}
		// Nothing outstanding and nothing issuable: the first Issue failed.
		if pipe.Len() == 0 {
			break
		}

		reply, err := pipe.Next()
		if err != nil {
			if nntp.IsBenignDisconnect(err) || fetchCtx.Err() != nil {
				logger.Trace("pipelined fetch cancelled", "provider", providerID, "err", err)
			} else {
				logger.Debug("pipelined fetch reply failed", "provider", providerID, "err", err, "consumed", consumed, "of", len(pending))
			}
			break
		}
		idx := pending[consumed]
		consumed++

		if reply.Err != nil {
			// Expected during failover; the fallback path logs the aggregate.
			logger.Trace("pipelined fetch article failed", "provider", providerID, "message_id", reply.MessageID, "err", reply.Err)
			continue
		}

		cr := &countReader{Reader: reply.Body}
		frame, decodeErr := decode.DecodeToBytesSized(cr, segments[idx].Bytes)
		reply.Body.Close()
		// Charged whether or not the decode succeeded: the bytes crossed the wire.
		p.recordStreamBytes(cr.n)
		if decodeErr != nil {
			logSegmentDecodeFailure(fetchCtx, providerID, reply.MessageID, decodeErr, cr.n)
			// A half-read body means the pipeline can no longer find the next
			// reply's status line; the remaining articles fall back.
			if pipe.Broken() {
				break
			}
			continue
		}
		if !shouldCacheFetchedSegment(fetchCtx) {
			break
		}

		data := SegmentData{
			Body:           frame.Data,
			Size:           int64(len(frame.Data)),
			ProviderHost:   host,
			FileName:       frame.FileName,
			YencFileSize:   frame.FileSize,
			YencPartOffset: frame.PartOffset,
		}
		cached := data
		cached.ProviderHost = ""
		p.cache.Set(reply.MessageID, cached)
		p.clearKnownMissing(reply.MessageID)
		p.recordArticleResult(providerID, true)
		p.recordSuccess(providerID)
		results[idx] = PipelinedResult{Data: data, OK: true}
		fetched++
	}

	poisoned = pipe.Broken() || pipe.Len() > 0
	logger.Trace("pipelined fetch batch done",
		"provider", providerID, "requested", len(pending), "fetched", fetched, "depth", depth, "discarded", poisoned)
	return results
}

// pipelineDepthOrDefault clamps a configured depth into the supported range.
// Zero and negatives disable pipelining, which is the documented escape hatch
// for a provider that mishandles more than one outstanding command.
func pipelineDepthOrDefault(depth int) int {
	switch {
	case depth < 0:
		return 0
	case depth == 0:
		return DefaultPipelineDepth
	case depth == 1:
		// One outstanding command is not a pipeline; treat it as off, matching
		// what SABnzbd's "Articles per request = 1" means.
		return 0
	case depth > MaxPipelineDepth:
		return MaxPipelineDepth
	default:
		return depth
	}
}
