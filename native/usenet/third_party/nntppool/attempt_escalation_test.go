package nntppool

import (
	"context"
	"errors"
	"fmt"
	"net"
	"testing"
	"time"
)

// hungFactory returns a server that greets, swallows every command, and never
// answers — the shape of genuinely dead infrastructure behind a live socket.
func hungFactory(ctx context.Context) (net.Conn, error) {
	client, server := net.Pipe()
	go func() {
		_, _ = server.Write([]byte("200 ready\r\n"))
		buf := make([]byte, 4096)
		_, _ = server.Read(buf)
		<-ctx.Done()
		_ = server.Close()
	}()
	return client, nil
}

// TestSlowStatusLineEscalates: a server needing longer than the attempt window
// to produce its status line must eventually be HEARD, not abandoned forever.
// With base 200ms the escalated pass runs at min(4×200ms, cap) = 800ms; a
// 300ms-delayed 430 fails the base attempt and is delivered by the escalated
// one at ~500ms total. Before escalation this request could never complete at
// any retry count: every attempt re-asked with the same too-short window.
func TestSlowStatusLineEscalates(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Factory: slowBodyFactory(300*time.Millisecond, noSuchArticle), Connections: 1, SkipPing: true, AttemptTimeout: 200 * time.Millisecond},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	start := time.Now()
	_, err = c.Body(context.Background(), "aged@spool")
	elapsed := time.Since(start)

	// The contract under test is DELIVERY: before escalation this request
	// could never complete at any retry count (every attempt re-asked with the
	// same too-short window).
	if !errors.Is(err, ErrArticleNotFound) {
		t.Fatalf("Body() error = %v, want ErrArticleNotFound (the slow 430 must be delivered, not abandoned)", err)
	}
	// Expected ~500ms (200ms base window + 300ms answer inside the escalated
	// window); 2s leaves scheduling slack while still catching a regression to
	// the pre-review ladder shape (which delivered this at ~900ms) or worse.
	if elapsed > 2*time.Second {
		t.Errorf("elapsed = %v, want ~500ms", elapsed)
	}
}

// TestExpiredAttemptsKeepTheirReason: when every attempt expires, the terminal
// error must carry the attempt-timeout story — the bare "all providers
// exhausted" reads as total infrastructure death and once cost a 17-hour
// misdiagnosis chasing dead sockets and session caps.
func TestExpiredAttemptsKeepTheirReason(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Factory: hungFactory, Connections: 1, SkipPing: true, AttemptTimeout: 100 * time.Millisecond},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	_, err = c.Body(context.Background(), "id@test")
	if err == nil {
		t.Fatal("Body() = nil error, want attempt-timeout failure")
	}
	var at *AttemptTimeoutError
	if !errors.As(err, &at) {
		t.Fatalf("Body() error = %v, want it to wrap *AttemptTimeoutError (never the bare exhausted form)", err)
	}
	if at.Phase != "response" {
		t.Errorf("AttemptTimeoutError.Phase = %q, want %q", at.Phase, "response")
	}
}

// TestEscalationBounded: a hung pool must still fail in bounded time, and the
// bound must not scale with provider count — the escalated pass shares ONE
// wall-clock budget (escalationFactor × the widest expired window, capped at
// maxAttemptTimeout) across every provider. With base 100ms that is N×100ms of
// pass 0 plus a single 400ms budget: ~500ms at N=1, ~800ms at N=4 — never
// N × (a ladder of windows).
func TestEscalationBounded(t *testing.T) {
	for _, n := range []int{1, 4} {
		t.Run(fmt.Sprintf("providers=%d", n), func(t *testing.T) {
			providers := make([]Provider, n)
			for i := range providers {
				providers[i] = Provider{
					Factory: hungFactory, Connections: 1, SkipPing: true,
					AttemptTimeout: 100 * time.Millisecond,
				}
			}
			c, err := NewClient(context.Background(), providers)
			if err != nil {
				t.Fatalf("NewClient() error = %v", err)
			}
			defer func() { _ = c.Close() }()

			start := time.Now()
			_, err = c.Body(context.Background(), "id@test")
			elapsed := time.Since(start)
			if err == nil {
				t.Fatal("Body() = nil error, want failure against a hung pool")
			}
			// N×base + one shared budget + scheduling slack. 3s catches both
			// regressions this test exists for: a per-provider ladder
			// (N=4 measured 5.6s under the reviewed revision) and identical
			// same-window replays.
			if elapsed > 3*time.Second {
				t.Errorf("elapsed = %v, want under 3s (escalation budget must not scale with provider count)", elapsed)
			}
		})
	}
}

// TestEscalationNoOpWhenWindowCannotGrow: a base window already at
// maxAttemptTimeout leaves the escalated pass nothing wider to ask, so the
// request must terminate after ONE window — never re-run identical passes.
// (The reviewed revision measured 3 × 10s here.) Skipped in -short: the one
// honest window is maxAttemptTimeout itself.
func TestEscalationNoOpWhenWindowCannotGrow(t *testing.T) {
	if testing.Short() {
		t.Skip("takes one full maxAttemptTimeout window by construction")
	}
	c, err := NewClient(context.Background(), []Provider{
		{Factory: hungFactory, Connections: 1, SkipPing: true, AttemptTimeout: maxAttemptTimeout},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	start := time.Now()
	_, err = c.Body(context.Background(), "id@test")
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("Body() = nil error, want failure against a hung provider")
	}
	if elapsed > maxAttemptTimeout+2*time.Second {
		t.Errorf("elapsed = %v, want ~%v (identical-window replay must not happen)", elapsed, maxAttemptTimeout)
	}
}
