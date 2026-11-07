package nntppool

import (
	"bytes"
	"context"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// writeChunked writes b to conn in n roughly-equal chunks, sleeping gap between
// each. net.Pipe is unbuffered, so each Write blocks until the client reads,
// which faithfully simulates a slow trickle on the wire.
func writeChunked(conn net.Conn, b []byte, n int, gap time.Duration) {
	if n < 1 {
		n = 1
	}
	step := (len(b) + n - 1) / n
	for off := 0; off < len(b); off += step {
		end := min(off+step, len(b))
		if _, err := conn.Write(b[off:end]); err != nil {
			return
		}
		if off+step < len(b) {
			time.Sleep(gap)
		}
	}
}

// TestSlowBodySucceeds is the regression test for the per-attempt-timeout bug:
// a body that takes longer than the attempt timeout to download must still
// succeed (the attempt timeout only bounds dispatch + time-to-first-byte, not
// the whole transfer), on a single connection with no churn.
func TestSlowBodySucceeds(t *testing.T) {
	payload := bytes.Repeat([]byte("abcdefghij"), 4096) // 40 KiB
	full := yencSinglePart(payload, "slow.bin")

	var dials atomic.Int64
	factory := func(ctx context.Context) (net.Conn, error) {
		dials.Add(1)
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					// ~10 chunks, 60ms apart => ~540ms total, far beyond the
					// 200ms attempt timeout but well within the stall timeout.
					writeChunked(server, full, 10, 60*time.Millisecond)
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	body, err := c.Body(context.Background(), "id@test")
	if err != nil {
		t.Fatalf("Body() error = %v, want success for a slow-but-healthy transfer", err)
	}
	if !bytes.Equal(body.Bytes, payload) {
		t.Fatalf("Body() bytes mismatch: got %d bytes, want %d", len(body.Bytes), len(payload))
	}
	if got := dials.Load(); got != 1 {
		t.Errorf("dials = %d, want 1 (no connection churn)", got)
	}
}

// TestHungProviderFailsOverFast verifies that a provider which accepts the
// command but never responds is abandoned within the attempt timeout and the
// request succeeds on the next provider.
func TestHungProviderFailsOverFast(t *testing.T) {
	payload := bytes.Repeat([]byte("Z"), 1024)
	full := yencSinglePart(payload, "ok.bin")

	hung := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			_, _ = server.Read(buf) // read the command, then never respond
			<-ctx.Done()
			_ = server.Close()
		}()
		return client, nil
	}
	healthy := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					_, _ = server.Write(full)
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: hung, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond},
		{Factory: healthy, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	start := time.Now()
	body, err := c.Body(context.Background(), "id@test")
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("Body() error = %v, want failover to healthy provider", err)
	}
	if !bytes.Equal(body.Bytes, payload) {
		t.Fatalf("Body() bytes mismatch: got %d bytes, want %d", len(body.Bytes), len(payload))
	}
	if elapsed > time.Second {
		t.Errorf("failover took %v, want < 1s (≈ attempt timeout)", elapsed)
	}
}

// TestMidBodyStallStreamErrors verifies that when a streamed transfer stalls
// after delivering partial bytes, the caller gets an error (rather than the
// pool silently re-streaming into the same writer on another provider).
func TestMidBodyStallStreamErrors(t *testing.T) {
	payload := bytes.Repeat([]byte("Q"), 64*1024)
	full := yencSinglePart(payload, "stall.bin")
	half := full[:len(full)/2]

	stalling := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					_, _ = server.Write(half) // partial, then hang
					<-ctx.Done()
					_ = server.Close()
					return
				}
			}
		}()
		return client, nil
	}
	healthy := makeBodyFactory(t, 200)

	c, err := NewClient(context.Background(), []Provider{
		{Factory: stalling, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond, StallTimeout: 250 * time.Millisecond},
		{Factory: healthy, Connections: 1, SkipPing: true},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	var sink bytes.Buffer
	_, err = c.BodyStream(context.Background(), "id@test", &sink)
	if err == nil {
		t.Fatal("BodyStream() error = nil, want stall error (no silent failover after partial stream)")
	}
	if sink.Len() >= len(payload) {
		t.Errorf("sink has %d bytes, want a partial (<%d) stream with no duplication", sink.Len(), len(payload))
	}
}

// TestStatProbeWinnerStallStreamErrors covers the STAT-probe failover path:
// when the probe winner stalls mid-body after partial delivery to a caller's
// writer, the error must surface rather than the pool re-streaming into the
// same writer on a later provider.
func TestStatProbeWinnerStallStreamErrors(t *testing.T) {
	payload := bytes.Repeat([]byte("W"), 64*1024)
	full := yencSinglePart(payload, "win.bin")
	half := full[:len(full)/2]

	// answers maps command prefix -> handler.
	serve := func(onBody func(net.Conn), onStat string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					cmd := string(buf[:n])
					switch {
					case strings.HasPrefix(cmd, "STAT"):
						_, _ = server.Write([]byte(onStat))
					case strings.HasPrefix(cmd, "BODY"):
						if onBody != nil {
							onBody(server)
							return
						}
						_, _ = server.Write([]byte("430 no such article\r\n"))
					}
				}
			}()
			return client, nil
		}
	}

	// A: 430 on BODY (triggers the race). B: STAT 223 winner, stalls on BODY.
	// C: STAT 430 miss (so B is the deterministic winner) + healthy BODY.
	provA := serve(nil, "430 no\r\n")
	provB := serve(func(s net.Conn) {
		_, _ = s.Write(half) // partial, then hang until the conn is torn down
		time.Sleep(2 * time.Second)
		_ = s.Close()
	}, "223 1 <id@test> exists\r\n")
	provC := serve(func(s net.Conn) {
		_, _ = s.Write(full)
	}, "430 no\r\n")

	c, err := NewClient(context.Background(), []Provider{
		{Factory: provA, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond},
		{Factory: provB, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond, StallTimeout: 250 * time.Millisecond},
		{Factory: provC, Connections: 1, SkipPing: true},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	var sink bytes.Buffer
	_, err = c.BodyStream(context.Background(), "id@test", &sink)
	if err == nil {
		t.Fatal("BodyStream() error = nil, want stall error (no re-stream onto provider C)")
	}
	if sink.Len() >= len(payload) {
		t.Errorf("sink has %d bytes, want partial (<%d) with no duplication", sink.Len(), len(payload))
	}
}

// TestBufferedStallRecovers verifies that a buffered (non-streaming) request
// that stalls on one provider recovers by failing over to a healthy provider,
// since the per-attempt buffer makes retrying safe.
func TestBufferedStallRecovers(t *testing.T) {
	payload := bytes.Repeat([]byte("M"), 1024)
	full := yencSinglePart(payload, "ok.bin")

	stalling := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					_, _ = server.Write([]byte("222 0 <id@test> body\r\n=ybegin ")) // partial header, then hang
					<-ctx.Done()
					_ = server.Close()
					return
				}
			}
		}()
		return client, nil
	}
	healthy := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					_, _ = server.Write(full)
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: stalling, Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond, StallTimeout: 200 * time.Millisecond},
		{Factory: healthy, Connections: 1, SkipPing: true},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	body, err := c.Body(context.Background(), "id@test")
	if err != nil {
		t.Fatalf("Body() error = %v, want recovery via healthy provider", err)
	}
	if !bytes.Equal(body.Bytes, payload) {
		t.Fatalf("Body() bytes mismatch: got %d bytes, want %d", len(body.Bytes), len(payload))
	}
}

// TestAttemptTimeoutMethod covers the adaptive per-attempt timeout selection.
func TestAttemptTimeoutMethod(t *testing.T) {
	tests := []struct {
		name     string
		explicit time.Duration
		ttfb     time.Duration
		want     time.Duration
	}{
		{"explicit override wins", 3 * time.Second, 50 * time.Millisecond, 3 * time.Second},
		{"no sample falls back to floor", 0, 0, minAttemptTimeout},
		{"tiny rtt clamps to floor", 0, 10 * time.Millisecond, minAttemptTimeout},
		{"mid rtt scales by 4x", 0, time.Second, 4 * time.Second},
		{"huge rtt clamps to cap", 0, 5 * time.Second, maxAttemptTimeout},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			g := &providerGroup{p: Provider{AttemptTimeout: tt.explicit}}
			if tt.ttfb > 0 {
				g.stats.ttfbEWMA.Store(int64(tt.ttfb))
			}
			if got := g.attemptTimeout(); got != tt.want {
				t.Errorf("attemptTimeout() = %v, want %v", got, tt.want)
			}
		})
	}
}

// TestRecordTTFB covers the EWMA update and the "not measured" guards.
func TestRecordTTFB(t *testing.T) {
	var s providerStats
	recordTTFB(&s, 0)            // non-positive: ignored
	recordTTFB(&s, -time.Second) // negative: ignored
	if s.ttfbEWMA.Load() != 0 {
		t.Fatalf("ttfbEWMA = %d, want 0 for unmeasured sample", s.ttfbEWMA.Load())
	}
	recordTTFB(nil, time.Second) // nil stats: no panic

	recordTTFB(&s, 100*time.Millisecond)
	if got := s.ttfbEWMA.Load(); got != int64(100*time.Millisecond) {
		t.Fatalf("ttfbEWMA = %d, want first sample stored directly", got)
	}
	// Second sample moves the average toward it (EWMA, α=0.2).
	recordTTFB(&s, 200*time.Millisecond)
	if got := s.ttfbEWMA.Load(); got <= int64(100*time.Millisecond) || got >= int64(200*time.Millisecond) {
		t.Fatalf("ttfbEWMA = %d, want between 100ms and 200ms", got)
	}
}

// TestRecordSpeed covers the throughput EWMA and the small-sample floor.
func TestRecordSpeed(t *testing.T) {
	var s providerStats
	recordSpeed(&s, speedSampleFloor-1, time.Second) // below floor: ignored
	if s.speedEWMA.Load() != 0 {
		t.Fatal("speedEWMA updated for a sub-floor sample")
	}
	recordSpeed(&s, 1_000_000, time.Second) // 1 MB/s
	if speedEWMABytesPerSec(&s) <= 0 {
		t.Fatal("speedEWMA not updated for a valid sample")
	}
}

// TestStatsExposesNewFields verifies TTFB, SpeedEWMA and AvailableSlots are
// populated in Stats() after a successful body transfer.
func TestStatsExposesNewFields(t *testing.T) {
	payload := bytes.Repeat([]byte("S"), 64*1024) // above speedSampleFloor
	full := yencSinglePart(payload, "stats.bin")
	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					_, _ = server.Write(full)
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 2, SkipPing: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if _, err := c.Body(context.Background(), "id@test"); err != nil {
		t.Fatalf("Body() error = %v", err)
	}

	st := c.Stats()
	if len(st.Providers) != 1 {
		t.Fatalf("Providers = %d, want 1", len(st.Providers))
	}
	ps := st.Providers[0]
	if ps.SpeedEWMA <= 0 {
		t.Errorf("SpeedEWMA = %v, want > 0 after a >16KiB transfer", ps.SpeedEWMA)
	}
	if ps.TTFB <= 0 {
		t.Errorf("TTFB = %v, want > 0", ps.TTFB)
	}
	if ps.AvailableSlots < 0 || ps.AvailableSlots > ps.MaxConnections {
		t.Errorf("AvailableSlots = %d, want within [0, %d]", ps.AvailableSlots, ps.MaxConnections)
	}
}

// newWeightGroup builds a minimal providerGroup with the given available-slot
// count and (optional) throughput sample, for dispatchWeights tests.
func newWeightGroup(avail int32, speed float64) *providerGroup {
	g := &providerGroup{gate: newConnGate(int(avail), 0)}
	g.gate.available.Store(avail)
	if speed > 0 {
		recordSpeed(&g.stats, int64(speed), time.Second)
	}
	return g
}

func TestDispatchWeights(t *testing.T) {
	t.Run("no samples reduces to capacity weighting", func(t *testing.T) {
		mains := []*providerGroup{newWeightGroup(2, 0), newWeightGroup(3, 0)}
		cum, total := dispatchWeights(mains, true)
		// No provider has a throughput sample => maxSpeed 0 => pure capacity.
		if total != 5 || cum[0] != 2 || cum[1] != 5 {
			t.Fatalf("cum = %v, total = %d, want [2 5], 5", cum, total)
		}
	})

	t.Run("slow provider gets a smaller share", func(t *testing.T) {
		fast := newWeightGroup(1, 4_000_000) // 4 MB/s
		slow := newWeightGroup(1, 1_000_000) // 1 MB/s
		mains := []*providerGroup{fast, slow}
		_, total := dispatchWeights(mains, true)
		// fast: 1*4, slow: 1*round(4*1/4)=1*1 => 5 total.
		if total != 5 {
			t.Fatalf("total = %d, want 5 (fast 4 + slow 1)", total)
		}
	})

	t.Run("speedAware off ignores throughput", func(t *testing.T) {
		fast := newWeightGroup(1, 4_000_000)
		slow := newWeightGroup(1, 1_000_000)
		_, total := dispatchWeights([]*providerGroup{fast, slow}, false)
		if total != 2 {
			t.Fatalf("total = %d, want 2 (pure capacity)", total)
		}
	})

	t.Run("quota-exceeded provider gets zero weight", func(t *testing.T) {
		g := newWeightGroup(2, 0)
		g.stats.quotaBytes = 100
		g.stats.quotaUsed.Store(100)
		g.stats.quotaExceeded.Store(true)
		_, total := dispatchWeights([]*providerGroup{g}, true)
		if total != 0 {
			t.Fatalf("total = %d, want 0 for quota-exceeded provider", total)
		}
	})
}
