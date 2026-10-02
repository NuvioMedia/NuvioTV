package nntppool

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
)

// minStatConcurrency floors the derived in-flight STAT bound when
// StatManyOptions.Concurrency is unset. STAT is a single-line request with a
// single-line reply and no body, so it is purely round-trip-latency bound; a
// high floor lets even a small pool amortise RTT by keeping many checks
// outstanding at once.
const minStatConcurrency = 64

// maxStatConcurrency caps the derived bound: past a few thousand outstanding
// STATs the connections' own pipelines are saturated and more dispatch
// goroutines only cost memory.
const maxStatConcurrency = 4096

// statCapacity is the pool's aggregate STAT pipeline depth — for every
// provider, connections × the per-connection bodyless-STAT inflight cap —
// clamped to [minStatConcurrency, maxStatConcurrency]. It is what a sweep must
// keep outstanding to fill every connection's pipeline: a flat dispatch bound
// below it leaves wire idle exactly on the STAT-heavy workloads StatInflight
// exists for.
func (c *Client) statCapacity() int {
	total := 0
	for _, groups := range [...]*[]*providerGroup{c.mainGroups.Load(), c.backupGroups.Load()} {
		for _, g := range *groups {
			// Mirror startProviderGroup's effective depth: Inflight floors at
			// 1, and StatInflight below it means "same as Inflight".
			inflight := max(g.p.Inflight, 1)
			statInflight := max(g.p.StatInflight, inflight)
			total += max(g.maxConns, 1) * statInflight
		}
	}
	return min(max(total, minStatConcurrency), maxStatConcurrency)
}

// StatCapacity reports the pool's aggregate STAT pipeline depth: for every
// provider (main and backup), connections × the per-connection bodyless-STAT
// inflight cap, clamped to [64, 4096]. It is the number of STATs a sweep must
// keep outstanding to fill every connection's pipeline, and the value StatMany
// derives when StatManyOptions.Concurrency is unset. Callers sizing their own
// dispatch bounds, chunk deadlines, or admission budgets should read it from
// here rather than re-deriving it from provider configuration.
func (c *Client) StatCapacity() int {
	return c.statCapacity()
}

// StatManyResult is the per-message outcome streamed by StatMany and StatAsync.
// A genuine miss (article not found, NNTP 430/423) is reported as
// Err == ErrArticleNotFound with a nil Result — it is a normal outcome of an
// existence sweep, not a fatal error.
type StatManyResult struct {
	MessageID string
	Result    *StatResult // non-nil on 2xx
	Err       error
}

// StatManyOptions tunes a StatMany sweep.
type StatManyOptions struct {
	// Concurrency bounds the number of STATs outstanding across the whole pool
	// at once. <= 0 derives the bound from the pool's aggregate STAT pipeline
	// capacity (connections × StatInflight per provider), so every
	// connection's pipeline can fill.
	Concurrency int

	// Priority routes each STAT through the priority channel so idle connections
	// pick it up ahead of normal (e.g. BODY) traffic.
	Priority bool

	// Background routes each STAT through the background lane: served only by
	// connections with nothing priority or normal queued, and capped while
	// foreground traffic is recent (see Provider.BackgroundFloor). For sweeps
	// nobody is waiting on, such as a repair census. Takes precedence over
	// Priority when both are set.
	Background bool

	// Provider, when set, restricts every STAT to the named provider group
	// (per-provider availability audit — retention differs per provider). The
	// name matches Client provider names ("host:port" or "host:port+username").
	// When empty, STATs dispatch across the whole pool with the same
	// cross-provider/backup failover semantics as Stat ("exists anywhere").
	Provider string

	// Skip, when non-nil, is consulted immediately before each message-id is
	// dispatched. Returning true abandons that id: no STAT is sent and no
	// result is emitted for it. It lets a caller stop checking ids whose
	// verdict is already settled without chunking the sweep, so sweep width
	// and early-termination granularity stop competing.
	//
	// A skipped id emits no StatManyResult — same as an id left undispatched
	// by context cancellation — so callers cannot assume one result per input
	// id; check ctx.Err() or otherwise account for gaps after draining rather
	// than relying on result count.
	//
	// Skip is called concurrently from every dispatch goroutine and must be
	// safe for concurrent use. It should be cheap: it runs once per
	// message-id.
	Skip func(messageID string) bool
}

// lane resolves the sweep's request lane from the option flags.
func (o StatManyOptions) lane() lane {
	switch {
	case o.Background:
		return laneBackground
	case o.Priority:
		return lanePriority
	}
	return laneNormal
}

// StatMany checks the existence of many articles concurrently, streaming a
// StatManyResult per message-id as each check completes (results arrive out of
// order). The returned channel is closed once every dispatched check has
// reported. If ctx is cancelled mid-sweep, dispatch stops, in-flight checks are
// cancelled, and the channel is closed; message-ids not yet dispatched produce
// no result, so callers should check ctx.Err() after draining. Ids abandoned
// via StatManyOptions.Skip likewise produce no result, so a caller cannot
// assume one result per input id in general — count dispatched vs. total, or
// otherwise account for gaps, rather than relying on result count alone.
func (c *Client) StatMany(ctx context.Context, messageIDs []string, opts StatManyOptions) <-chan StatManyResult {
	if ctx == nil {
		ctx = context.Background()
	}
	conc := opts.Concurrency
	if conc <= 0 {
		conc = c.statCapacity()
	}
	if conc > len(messageIDs) && len(messageIDs) > 0 {
		conc = len(messageIDs)
	}

	out := make(chan StatManyResult, conc)

	// Resolve the target group once (outside the goroutine) so an unknown
	// provider name fails every id with a clear error rather than silently
	// dispatching pool-wide.
	var target *providerGroup
	var targetErr error
	if opts.Provider != "" {
		if target = c.findGroup(opts.Provider); target == nil {
			targetErr = fmt.Errorf("nntp: provider %q not found", opts.Provider)
		}
	}

	go func() {
		defer close(out)

		// Workers pull their own index instead of being fed by a channel. The
		// work is a fully-materialised slice, so a channel buys nothing and
		// costs a great deal: with conc up to maxStatConcurrency, every send
		// takes hchan.lock and wakes one goroutine out of a receive queue
		// thousands deep. One uncontended atomic add replaces that hand-off.
		//
		// Worker count stays conc because statOne blocks until its reply
		// arrives, so one goroutine per outstanding STAT is inherent.
		var cursor atomic.Int64
		var wg sync.WaitGroup
		wg.Add(conc)
		for range conc {
			go func() {
				defer wg.Done()
				for {
					i := int(cursor.Add(1)) - 1
					if i >= len(messageIDs) {
						return
					}
					if ctx.Err() != nil {
						return
					}
					if opts.Skip != nil && opts.Skip(messageIDs[i]) {
						continue
					}
					res := c.statOne(ctx, messageIDs[i], target, targetErr, opts.lane())
					select {
					case out <- res:
					case <-ctx.Done():
						return
					}
				}
			}()
		}

		wg.Wait()
	}()

	return out
}

// statOne performs a single STAT and maps it to a StatManyResult. When target is
// set the check is confined to that provider group; otherwise it uses the
// pool-wide failover path.
func (c *Client) statOne(ctx context.Context, messageID string, target *providerGroup, targetErr error, ln lane) StatManyResult {
	if targetErr != nil {
		return StatManyResult{MessageID: messageID, Err: targetErr}
	}

	payload := statPayload(messageID)

	var resp Response
	if target != nil {
		resp = c.statViaGroup(ctx, target, payload, ln)
	} else {
		resp = c.sendSync(ctx, payload, ln)
	}

	result, err := parseStat(messageID, resp)
	return StatManyResult{MessageID: messageID, Result: result, Err: err}
}

// statViaGroup issues a STAT against a single provider group, reusing the same
// resilient single-group send (with fresh-connection retry on connection death)
// that the failover path uses per provider. No cross-provider failover.
func (c *Client) statViaGroup(ctx context.Context, g *providerGroup, payload []byte, ln lane) Response {
	resp, ok, cancelled := c.tryGroupResilient(ctx, g, payload, nil, nil, ln, 0)
	switch {
	case cancelled:
		err := ctx.Err()
		if err == nil {
			err = c.ctx.Err()
		}
		return Response{Err: err}
	case !ok:
		if resp.Err != nil {
			return resp // expired attempts keep their typed reason
		}
		return Response{Err: ErrConnectionDied}
	default:
		return resp
	}
}
