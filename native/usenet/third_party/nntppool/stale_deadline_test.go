package nntppool

import (
	"context"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// pipelineTrickleFactory returns a ConnFactory whose server counts dials and
// answers every command with "223" — serially, one reply per `perReply`. This
// is the sweep-burst shape: the server is healthy (each reply's TTFB from the
// previous reply is small), but a deep pipeline means the tail requests'
// dispatch-anchored attempt windows expire long before their FIFO turn.
func pipelineTrickleFactory(dials *atomic.Int32, perReply time.Duration) ConnFactory {
	return func(ctx context.Context) (net.Conn, error) {
		dials.Add(1)
		client, server := net.Pipe()
		cmds := make(chan struct{}, 1024)
		go func() {
			for range cmds {
				time.Sleep(perReply)
				if _, err := server.Write([]byte("223 0 <x@h> exists\r\n")); err != nil {
					return
				}
			}
		}()
		go func() {
			defer func() { _ = server.Close() }()
			defer close(cmds)
			if _, err := server.Write([]byte("200 server ready\r\n")); err != nil {
				return
			}
			buf := make([]byte, 8192)
			for {
				rn, err := server.Read(buf)
				if err != nil {
					return
				}
				for _, line := range strings.Split(string(buf[:rn]), "\r\n") {
					if line != "" {
						cmds <- struct{}{}
					}
				}
			}
		}()
		return client, nil
	}
}

// A pipeline of requests whose attempt windows expired while they queued must
// not cost the connection: the caller has already failed over, the server is
// healthy and steadily answering, and killing the connection fails every other
// pipelined request with "connection died" (observed live: a wide STAT sweep
// left 3692 of 4147 checks unresolved per pass and took a 50-connection pool
// to zero throughput for minutes). The TTFB bound is re-anchored at each
// response's drain start instead, so queue wait never reads as a hung server.
func TestStaleAttemptDeadlineDoesNotKillPipeline(t *testing.T) {
	var dials atomic.Int32
	c, err := NewClient(context.Background(), []Provider{{
		Factory:        pipelineTrickleFactory(&dials, 60*time.Millisecond),
		Connections:    1,
		Inflight:       2,
		StatInflight:   16,
		AttemptTimeout: 100 * time.Millisecond,
		StallTimeout:   5 * time.Second,
		SkipPing:       true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	ids := make([]string, 8)
	for i := range ids {
		ids[i] = "x@h"
	}
	// The burst: every window expires while the server withholds. Outcomes are
	// failure or escalation-rescued success — either is fine; the connection is
	// what must survive.
	for range c.StatMany(context.Background(), ids, StatManyOptions{Concurrency: 8}) {
	}

	// Give the reader time to drain the late replies, then prove the same
	// connection still serves.
	deadline := time.Now().Add(3 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if _, lastErr = c.Stat(context.Background(), "x@h"); lastErr == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if lastErr != nil {
		t.Fatalf("follow-up STAT after the expired burst failed: %v", lastErr)
	}
	if got := dials.Load(); got != 1 {
		t.Fatalf("connection was redialed %d times; the expired burst must not kill it", got)
	}
}

// slowDripBodyFactory serves one BODY slowly: status line and a first chunk at
// once, the rest after `pause`. Every later command is answered immediately.
func slowDripBodyFactory(dials *atomic.Int32, pause time.Duration) ConnFactory {
	return func(ctx context.Context) (net.Conn, error) {
		dials.Add(1)
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			if _, err := server.Write([]byte("200 server ready\r\n")); err != nil {
				return
			}
			buf := make([]byte, 8192)
			first := true
			for {
				rn, err := server.Read(buf)
				if err != nil {
					return
				}
				for _, line := range strings.Split(string(buf[:rn]), "\r\n") {
					if line == "" {
						continue
					}
					if strings.HasPrefix(line, "BODY") && first {
						first = false
						if _, err := server.Write([]byte("222 0 <x@h> body\r\nline one\r\n")); err != nil {
							return
						}
						time.Sleep(pause)
						if _, err := server.Write([]byte("line two\r\n.\r\n")); err != nil {
							return
						}
						continue
					}
					if strings.HasPrefix(line, "BODY") {
						if _, err := server.Write([]byte("222 0 <x@h> body\r\nquick\r\n.\r\n")); err != nil {
							return
						}
						continue
					}
					if _, err := server.Write([]byte("223 0 <x@h> exists\r\n")); err != nil {
						return
					}
				}
			}
		}()
		return client, nil
	}
}

// A caller abandoning a healthy mid-flight body (its own context deadline)
// must not cost the connection either: the drain continues under the rolling
// stall bound, not the dead caller's deadline.
func TestCancelledBodyDrainSurvivesCallerDeadline(t *testing.T) {
	var dials atomic.Int32
	c, err := NewClient(context.Background(), []Provider{{
		Factory:        slowDripBodyFactory(&dials, 300*time.Millisecond),
		Connections:    1,
		Inflight:       2,
		AttemptTimeout: time.Second,
		StallTimeout:   5 * time.Second,
		SkipPing:       true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if _, err := c.Body(ctx, "x@h"); err == nil {
		t.Fatal("expected the abandoned body to fail with the caller's deadline")
	}

	deadline := time.Now().Add(3 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if _, lastErr = c.Stat(context.Background(), "x@h"); lastErr == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if lastErr != nil {
		t.Fatalf("follow-up STAT after the abandoned body failed: %v", lastErr)
	}
	if got := dials.Load(); got != 1 {
		t.Fatalf("connection was redialed %d times; an abandoned mid-body drain must not kill it", got)
	}
}

// The stall bound still protects against a genuinely hung server: an abandoned
// request whose response never starts is torn down once the stall grace runs
// out — the leniency is for queue wait, not for zombies.
func TestAbandonedRequestOnHungServerStillDies(t *testing.T) {
	var dials atomic.Int32
	factory := func(ctx context.Context) (net.Conn, error) {
		n := dials.Add(1)
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			if _, err := server.Write([]byte("200 server ready\r\n")); err != nil {
				return
			}
			buf := make([]byte, 8192)
			for {
				rn, err := server.Read(buf)
				if err != nil {
					return
				}
				if n > 1 {
					for range strings.Count(string(buf[:rn]), "\r\n") {
						if _, err := server.Write([]byte("223 0 <x@h> exists\r\n")); err != nil {
							return
						}
					}
				}
				// First connection: swallow everything, answer nothing.
			}
		}()
		return client, nil
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:        factory,
		Connections:    1,
		Inflight:       2,
		AttemptTimeout: 100 * time.Millisecond,
		StallTimeout:   300 * time.Millisecond,
		SkipPing:       true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	// The first STAT hangs; whether this call fails or is rescued by an
	// internal retry on the replacement connection is incidental. What matters:
	// STATs eventually succeed AND the hung connection was torn down.
	_, _ = c.Stat(context.Background(), "x@h")

	deadline := time.Now().Add(5 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if _, lastErr = c.Stat(context.Background(), "x@h"); lastErr == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if lastErr != nil {
		t.Fatalf("STAT never recovered after the hung connection: %v", lastErr)
	}
	if got := dials.Load(); got < 2 {
		t.Fatalf("dials = %d, want >= 2 (the hung connection must be torn down and replaced)", got)
	}
}
