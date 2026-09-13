package pool

import (
	"context"
	"fmt"
	"net"
	"strings"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
)

func startMockNNTPServer(t *testing.T) (string, int, func()) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}

	addr := ln.Addr().(*net.TCPAddr)

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = c.Write([]byte("200 Welcome\r\n"))

				buf := make([]byte, 1024)
				for {
					n, err := c.Read(buf)
					if err != nil {
						return
					}
					cmd := string(buf[:n])
					if strings.HasPrefix(cmd, "AUTHINFO USER") {
						_, _ = c.Write([]byte("381 PASS required\r\n"))
					} else if strings.HasPrefix(cmd, "AUTHINFO PASS") {
						_, _ = c.Write([]byte("281 Authentication accepted\r\n"))
					} else if strings.HasPrefix(cmd, "GROUP") {
						_, _ = c.Write([]byte("211 group selected\r\n"))
					} else if strings.HasPrefix(cmd, "BODY") {
						if strings.Contains(cmd, "missing") {
							_, _ = c.Write([]byte("430 No Such Article\r\n"))
						} else {
							// Return a valid yEnc frame
							_, _ = c.Write([]byte("222 Body follows\r\n=ybegin size=10 line=128 name=test\r\ndata\r\n=yend size=10\r\n.\r\n"))
						}
					} else if strings.HasPrefix(cmd, "STAT") {
						if strings.Contains(cmd, "missing") {
							_, _ = c.Write([]byte("430 No Such Article\r\n"))
						} else {
							_, _ = c.Write([]byte("223 Article found\r\n"))
						}
					} else if strings.HasPrefix(cmd, "QUIT") {
						_, _ = c.Write([]byte("205 Bye\r\n"))
						return
					} else {
						_, _ = c.Write([]byte("500 Unknown command\r\n"))
					}
				}
			}(conn)
		}
	}()

	cleanup := func() {
		_ = ln.Close()
	}
	return addr.IP.String(), addr.Port, cleanup
}

// startScriptedNNTPServer answers every BODY with the given response line, so a
// test can stand up providers with distinct failure modes.
func startScriptedNNTPServer(t *testing.T, bodyResponse string) (string, int, func()) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().(*net.TCPAddr)
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = c.Write([]byte("200 Welcome\r\n"))
				buf := make([]byte, 1024)
				for {
					n, err := c.Read(buf)
					if err != nil {
						return
					}
					cmd := string(buf[:n])
					switch {
					case strings.HasPrefix(cmd, "AUTHINFO USER"):
						_, _ = c.Write([]byte("381 PASS required\r\n"))
					case strings.HasPrefix(cmd, "AUTHINFO PASS"):
						_, _ = c.Write([]byte("281 Authentication accepted\r\n"))
					case strings.HasPrefix(cmd, "GROUP"):
						_, _ = c.Write([]byte("211 group selected\r\n"))
					case strings.HasPrefix(cmd, "BODY"):
						_, _ = c.Write([]byte(bodyResponse))
					case strings.HasPrefix(cmd, "QUIT"):
						_, _ = c.Write([]byte("205 Bye\r\n"))
						return
					default:
						_, _ = c.Write([]byte("500 Unknown command\r\n"))
					}
				}
			}(conn)
		}
	}()
	return addr.IP.String(), addr.Port, func() { _ = ln.Close() }
}

// A mixed outcome — one provider says 430, another fails for a weaker reason —
// must not surface as a missing-article error: the caller would zero-fill a
// segment nobody proved absent.
func TestMixedFetchOutcomeIsNotReportedAsMissing(t *testing.T) {
	hostA, portA, cleanupA := startScriptedNNTPServer(t, "430 No Such Article\r\n")
	defer cleanupA()
	hostB, portB, cleanupB := startScriptedNNTPServer(t, "502 transient server hiccup\r\n")
	defer cleanupB()

	poolA := nntp.NewClientPool(hostA, portA, false, "user", "pass", 2)
	poolB := nntp.NewClientPool(hostB, portB, false, "user", "pass", 2)
	defer poolA.Shutdown()
	defer poolB.Shutdown()

	p, err := NewPool(&Config{Providers: []ProviderConfig{
		{ID: "provider-430", Priority: 0, ClientPool: poolA},
		{ID: "provider-flaky", Priority: 1, ClientPool: poolB},
	}})
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}

	_, err = p.fetchSegmentOnce(context.Background(), "mixed-1", &nzb.Segment{ID: "mixed-1"}, []string{"alt.binaries.test"})
	if err == nil {
		t.Fatal("expected the fetch to fail")
	}
	if nntp.IsArticleNotFound(err) {
		t.Fatalf("mixed outcome must not read as missing-everywhere: %v", err)
	}
	if p.isKnownMissing("mixed-1") {
		t.Fatal("mixed outcome must not cache the segment as known-missing")
	}
}

func TestProviderDemotion_430(t *testing.T) {
	host, port, cleanup := startMockNNTPServer(t)
	defer cleanup()

	poolA := nntp.NewClientPool(host, port, false, "user", "pass", 2)
	poolB := nntp.NewClientPool(host, port, false, "user", "pass", 2)
	defer poolA.Shutdown()
	defer poolB.Shutdown()

	cfg := &Config{
		Providers: []ProviderConfig{
			{ID: "provider-a", Priority: 0, ClientPool: poolA},
			{ID: "provider-b", Priority: 1, ClientPool: poolB},
		},
	}

	p, err := NewPool(cfg)
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}

	ctx := context.Background()

	// 1. Initially, consecutive 430s are 0
	p.mu.RLock()
	countA := p.consecutive430s["provider-a"]
	countB := p.consecutive430s["provider-b"]
	p.mu.RUnlock()
	if countA != 0 || countB != 0 {
		t.Fatalf("expected initial counts to be 0, got A=%d, B=%d", countA, countB)
	}

	// 2. Fetch a missing segment (provider-a tried first, fails; provider-b tried second, fails)
	_, _ = p.FetchSegment(ctx, &nzb.Segment{ID: "missing-1"}, []string{"alt.binaries.test"})

	p.mu.RLock()
	countA = p.consecutive430s["provider-a"]
	countB = p.consecutive430s["provider-b"]
	p.mu.RUnlock()
	if countA != 1 || countB != 1 {
		t.Fatalf("expected counts to increment, got A=%d, B=%d", countA, countB)
	}

	// Fetch 2 more missing segments to push provider-a over the threshold (>= 3)
	_, _ = p.FetchSegment(ctx, &nzb.Segment{ID: "missing-2"}, []string{"alt.binaries.test"})
	_, _ = p.FetchSegment(ctx, &nzb.Segment{ID: "missing-3"}, []string{"alt.binaries.test"})

	p.mu.RLock()
	countA = p.consecutive430s["provider-a"]
	countB = p.consecutive430s["provider-b"]
	p.mu.RUnlock()
	if countA != 3 || countB != 3 {
		t.Fatalf("expected counts to reach 3, got A=%d, B=%d", countA, countB)
	}

	// 3. Verify that a single success does NOT reset the consecutive 430s count (requires 10)
	_, err = p.fetchSegmentOnce(ctx, "healthy-1", &nzb.Segment{ID: "healthy-1"}, []string{"alt.binaries.test"})
	if err != nil {
		t.Fatalf("failed to fetch healthy segment: %v", err)
	}

	p.mu.RLock()
	countA = p.consecutive430s["provider-a"]
	p.mu.RUnlock()
	if countA != 3 {
		t.Fatalf("expected provider-a count to remain at 3 after 1 success, got %d", countA)
	}

	// Fetch 9 more healthy segments to reach the 10 consecutive successes threshold
	for i := 2; i <= 10; i++ {
		msgID := fmt.Sprintf("healthy-%d", i)
		_, err = p.fetchSegmentOnce(ctx, msgID, &nzb.Segment{ID: msgID}, []string{"alt.binaries.test"})
		if err != nil {
			t.Fatalf("failed to fetch healthy segment %d: %v", i, err)
		}
	}

	// Now it should reset to 0
	p.mu.RLock()
	countA = p.consecutive430s["provider-a"]
	countB = p.consecutive430s["provider-b"]
	p.mu.RUnlock()

	if countA != 0 {
		t.Fatalf("expected provider-a to reset to 0 after 10 successes, got %d", countA)
	}
	if countB != 3 {
		t.Fatalf("expected provider-b to remain at 3, got %d", countB)
	}

	// 4. Now, provider-b is demoted (count >= 3) and provider-a is healthy (count = 0).
	// Let's verify that a normal getConnection prefers the healthy provider-a.
	_, release, _, pid, err := p.getConnection(ctx, nil, 999, false)
	if err != nil {
		t.Fatalf("getConnection failed: %v", err)
	}
	release()
	if pid != "provider-a" {
		t.Fatalf("expected provider-a to be selected, got %s", pid)
	}

	// 5. Let's make provider-a fail 3 times so both are demoted again.
	_, _ = p.FetchSegment(ctx, &nzb.Segment{ID: "missing-4"}, []string{"alt.binaries.test"})
	_, _ = p.FetchSegment(ctx, &nzb.Segment{ID: "missing-5"}, []string{"alt.binaries.test"})
	_, _ = p.FetchSegment(ctx, &nzb.Segment{ID: "missing-6"}, []string{"alt.binaries.test"})

	p.mu.RLock()
	countA = p.consecutive430s["provider-a"]
	p.mu.RUnlock()
	if countA < 3 {
		t.Fatalf("expected provider-a to be demoted again, got %d", countA)
	}
}

func TestSessionIsolation_430(t *testing.T) {
	host, port, cleanup := startMockNNTPServer(t)
	defer cleanup()

	poolA := nntp.NewClientPool(host, port, false, "user", "pass", 2)
	defer poolA.Shutdown()

	cfg := &Config{
		Providers: []ProviderConfig{
			{ID: "provider-a", Priority: 0, ClientPool: poolA},
		},
	}

	parentPool, err := NewPool(cfg)
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}

	// Create two subset pools (which represent different stream sessions)
	sessionPool1 := parentPool.Subset(nil)
	sessionPool2 := parentPool.Subset(nil)

	ctx := context.Background()

	// Cause 430s in sessionPool1
	_, _ = sessionPool1.FetchSegment(ctx, &nzb.Segment{ID: "missing-1"}, []string{"alt.binaries.test"})
	_, _ = sessionPool1.FetchSegment(ctx, &nzb.Segment{ID: "missing-2"}, []string{"alt.binaries.test"})

	sessionPool1.mu.RLock()
	count1 := sessionPool1.consecutive430s["provider-a"]
	sessionPool1.mu.RUnlock()

	sessionPool2.mu.RLock()
	count2 := sessionPool2.consecutive430s["provider-a"]
	sessionPool2.mu.RUnlock()

	parentPool.mu.RLock()
	countParent := parentPool.consecutive430s["provider-a"]
	parentPool.mu.RUnlock()

	if count1 != 2 {
		t.Fatalf("expected sessionPool1 to have 2 consecutive 430s, got %d", count1)
	}
	if count2 != 0 {
		t.Fatalf("expected sessionPool2 to have 0 consecutive 430s, got %d", count2)
	}
	if countParent != 0 {
		t.Fatalf("expected parentPool to have 0 consecutive 430s, got %d", countParent)
	}
}
