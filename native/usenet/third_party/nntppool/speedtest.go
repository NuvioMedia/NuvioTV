package nntppool

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/javi11/nntppool/v4/nzb"
)

const DefaultSpeedTestNZBURL = "https://sabnzbd.org/tests/test_download_1GB.nzb"

// SpeedTestOptions configures a speed test run.
type SpeedTestOptions struct {
	NZBURL          string        // defaults to DefaultSpeedTestNZBURL
	NZBReader       io.Reader     // overrides NZBURL when set (for tests/local files)
	MaxSegments     int           // 0 = all
	OnProgress      func(SpeedTestProgress)
	NZBFetchTimeout time.Duration // defaults to 30s
	ProviderName    string        // if set, test only this provider
}

// SpeedTestProgress is passed to OnProgress roughly every second.
type SpeedTestProgress struct {
	Elapsed       time.Duration
	SegmentsDone  int
	SegmentsTotal int
	WireSpeedBps  float64 // instantaneous wire bytes/sec (1s delta)
	AvgSpeedBps   float64 // average wire bytes/sec since test start
	ETASeconds    float64 // estimated seconds remaining
}

// SpeedTestResult holds the outcome of a speed test.
type SpeedTestResult struct {
	Elapsed         time.Duration
	SegmentsDone    int
	SegmentsTotal   int
	Missing         int64
	Errors          int64
	WireBytes       int64
	WireSpeedBps    float64
	DecodedBytes    int64
	DecodedSpeedBps float64
	Providers       []ProviderStats
}

// SpeedTest runs a speed test by downloading NZB segments through the pool.
// It uses delta-based metrics so it is safe to call on a client that has been
// alive and doing other work.
func (c *Client) SpeedTest(ctx context.Context, opts SpeedTestOptions) (*SpeedTestResult, error) {
	segments, err := loadSpeedTestSegments(ctx, opts)
	if err != nil {
		return nil, err
	}
	if len(segments) == 0 {
		return nil, fmt.Errorf("speedtest: no segments found in NZB")
	}

	if opts.MaxSegments > 0 && opts.MaxSegments < len(segments) {
		segments = segments[:opts.MaxSegments]
	}

	// Resolve per-provider targeting.
	var targetGroup *providerGroup
	if opts.ProviderName != "" {
		targetGroup = c.findGroup(opts.ProviderName)
		if targetGroup == nil {
			return nil, fmt.Errorf("speedtest: provider %q not found", opts.ProviderName)
		}
	}

	// Snapshot stats at start for delta computation.
	startStats := c.Stats()

	// Child context so cancelling the speed test doesn't close the client.
	stCtx, stCancel := context.WithCancel(ctx)
	defer stCancel()

	totalSegs := len(segments)
	var segsDone atomic.Int64
	var bytesDecoded atomic.Int64

	// Progress goroutine.
	progressDone := make(chan struct{})
	go func() {
		defer close(progressDone)
		if opts.OnProgress == nil {
			return
		}
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		start := time.Now()
		var lastWireBytes int64
		for {
			select {
			case <-ticker.C:
				snap := c.Stats()
				wireNow := totalWireBytes(snap) - totalWireBytes(startStats)
				elapsed := time.Since(start)
				secs := elapsed.Seconds()

				instantaneous := float64(wireNow-lastWireBytes) // 1s tick
				lastWireBytes = wireNow

				var avg float64
				if secs > 0 {
					avg = float64(wireNow) / secs
				}

				done := int(segsDone.Load())
				var eta float64
				if done > 0 {
					eta = float64(totalSegs-done) / float64(done) * secs
				}

				opts.OnProgress(SpeedTestProgress{
					Elapsed:       elapsed,
					SegmentsDone:  done,
					SegmentsTotal: totalSegs,
					WireSpeedBps:  instantaneous,
					AvgSpeedBps:   avg,
					ETASeconds:    eta,
				})
			case <-stCtx.Done():
				return
			}
		}
	}()

	// Fan-out: dispatch all segment requests.
	testStart := time.Now()
	respChans := make([]<-chan Response, totalSegs)
	for i, seg := range segments {
		payload := append(append([]byte("BODY <"), seg.MessageID...), ">\r\n"...)
		if targetGroup != nil {
			respChans[i] = c.sendToGroup(stCtx, targetGroup, payload, io.Discard)
		} else {
			respChans[i] = c.Send(stCtx, payload, io.Discard)
		}
	}

	// Collect responses sequentially.
	for _, ch := range respChans {
		resp, ok := <-ch
		if ok && resp.Err == nil && resp.StatusCode != 430 && resp.StatusCode != 423 {
			bytesDecoded.Add(int64(resp.Meta.BytesDecoded))
		}
		segsDone.Add(1)
	}
	testElapsed := time.Since(testStart)

	stCancel() // stop progress goroutine
	<-progressDone

	// Snapshot stats at end for delta computation.
	endStats := c.Stats()
	elapsed := testElapsed
	wireBytes := totalWireBytes(endStats) - totalWireBytes(startStats)

	var wireSpeed float64
	if secs := elapsed.Seconds(); secs > 0 {
		wireSpeed = float64(wireBytes) / secs
	}

	decoded := bytesDecoded.Load()
	var decodedSpeed float64
	if secs := elapsed.Seconds(); secs > 0 {
		decodedSpeed = float64(decoded) / secs
	}

	// Compute per-provider deltas.
	startProvMap := make(map[string]ProviderStats, len(startStats.Providers))
	for _, ps := range startStats.Providers {
		startProvMap[ps.Name] = ps
	}

	var resultProviders []ProviderStats
	var totalMissing, totalErrors int64
	for _, ps := range endStats.Providers {
		if targetGroup != nil && ps.Name != opts.ProviderName {
			continue
		}
		sp := startProvMap[ps.Name]
		delta := ProviderStats{
			Name:              ps.Name,
			Missing:           ps.Missing - sp.Missing,
			Errors:            ps.Errors - sp.Errors,
			ActiveConnections: ps.ActiveConnections,
			MaxConnections:    ps.MaxConnections,
			Ping:              ps.Ping,
		}
		provWire := providerWireBytes(endStats, ps.Name) - providerWireBytes(startStats, ps.Name)
		if secs := elapsed.Seconds(); secs > 0 {
			delta.AvgSpeed = float64(provWire) / secs
		}
		totalMissing += delta.Missing
		totalErrors += delta.Errors
		resultProviders = append(resultProviders, delta)
	}

	return &SpeedTestResult{
		Elapsed:         elapsed,
		SegmentsDone:    int(segsDone.Load()),
		SegmentsTotal:   totalSegs,
		Missing:         totalMissing,
		Errors:          totalErrors,
		WireBytes:       wireBytes,
		WireSpeedBps:    wireSpeed,
		DecodedBytes:    decoded,
		DecodedSpeedBps: decodedSpeed,
		Providers:       resultProviders,
	}, nil
}

// sendToGroup dispatches a request directly to a specific provider group,
// bypassing the round-robin sendWithRetry logic.
func (c *Client) sendToGroup(ctx context.Context, g *providerGroup, payload []byte, bodyWriter io.Writer) <-chan Response {
	outerCh := make(chan Response, 1)
	go func() {
		defer close(outerCh)
		innerCh := make(chan Response, 1)
		req := &Request{
			Ctx:        ctx,
			Payload:    payload,
			RespCh:     innerCh,
			BodyWriter: bodyWriter,
		}
		select {
		case <-ctx.Done():
			outerCh <- Response{Err: ctx.Err()}
			return
		case <-c.ctx.Done():
			outerCh <- Response{Err: c.ctx.Err()}
			return
		case <-g.ctx.Done():
			outerCh <- Response{Err: context.Canceled}
			return
		case g.reqCh <- req:
		}
		select {
		case resp, ok := <-innerCh:
			if ok {
				outerCh <- resp
			}
		case <-ctx.Done():
			outerCh <- Response{Err: ctx.Err()}
		case <-c.ctx.Done():
			outerCh <- Response{Err: c.ctx.Err()}
		case <-g.ctx.Done():
			outerCh <- Response{Err: context.Canceled}
		}
	}()
	return outerCh
}

// findGroup searches mainGroups and backupGroups by name.
func (c *Client) findGroup(name string) *providerGroup {
	for _, gs := range []*[]*providerGroup{c.mainGroups.Load(), c.backupGroups.Load()} {
		for _, g := range *gs {
			if g.name == name {
				return g
			}
		}
	}
	return nil
}

// loadSpeedTestSegments parses an NZB from opts.NZBReader or fetches from opts.NZBURL.
func loadSpeedTestSegments(ctx context.Context, opts SpeedTestOptions) ([]nzb.Segment, error) {
	var r io.Reader

	if opts.NZBReader != nil {
		r = opts.NZBReader
	} else {
		url := opts.NZBURL
		if url == "" {
			url = DefaultSpeedTestNZBURL
		}

		timeout := opts.NZBFetchTimeout
		if timeout == 0 {
			timeout = 30 * time.Second
		}

		httpCtx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()

		req, err := http.NewRequestWithContext(httpCtx, http.MethodGet, url, nil)
		if err != nil {
			return nil, fmt.Errorf("speedtest: create NZB request: %w", err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("speedtest: fetch NZB: %w", err)
		}
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("speedtest: NZB HTTP %d", resp.StatusCode)
		}
		r = resp.Body
	}

	n, err := nzb.Parse(r)
	if err != nil {
		return nil, fmt.Errorf("speedtest: %w", err)
	}
	return n.AllSegments(), nil
}

// totalWireBytes returns the raw BytesConsumed counter from a stats snapshot.
func totalWireBytes(s ClientStats) int64 {
	return s.BytesConsumed
}

// providerWireBytes returns the raw BytesConsumed for a named provider from a stats snapshot.
func providerWireBytes(s ClientStats, name string) int64 {
	for _, ps := range s.Providers {
		if ps.Name == name {
			return ps.BytesConsumed
		}
	}
	return 0
}
