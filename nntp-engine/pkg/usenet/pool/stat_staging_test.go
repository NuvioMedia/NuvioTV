package pool

import (
	"context"
	"net"
	"strings"
	"sync/atomic"
	"testing"

	"streamnzb/pkg/usenet/nntp"
)

// countingNNTPServer answers STAT and BODY and reports how many of each it saw,
// so a test can tell which providers were actually consulted and which ones
// were charged for an article.
type countingNNTPServer struct {
	stats      atomic.Int64
	bodies     atomic.Int64
	allMissing bool
}

func startCountingNNTPServer(t *testing.T, allMissing bool) (*countingNNTPServer, string, int, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := &countingNNTPServer{allMissing: allMissing}
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
						srv.bodies.Add(1)
						if srv.allMissing {
							_, _ = c.Write([]byte("430 No Such Article\r\n"))
						} else {
							_, _ = c.Write([]byte("222 Body follows\r\n=ybegin size=10 line=128 name=test\r\ndata\r\n=yend size=10\r\n.\r\n"))
						}
					case strings.HasPrefix(cmd, "STAT"):
						srv.stats.Add(1)
						if srv.allMissing {
							_, _ = c.Write([]byte("430 No Such Article\r\n"))
						} else {
							_, _ = c.Write([]byte("223 Article found\r\n"))
						}
					case strings.HasPrefix(cmd, "QUIT"):
						_, _ = c.Write([]byte("205 Bye\r\n"))
						return
					default:
						_, _ = c.Write([]byte("500 Unknown\r\n"))
					}
				}
			}(conn)
		}
	}()

	return srv, addr.IP.String(), addr.Port, func() { _ = ln.Close() }
}

func twoProviderPool(t *testing.T, leaderMissing, backupMissing bool) (*Pool, *countingNNTPServer, *countingNNTPServer, func()) {
	t.Helper()
	leaderSrv, leaderHost, leaderPort, stopLeader := startCountingNNTPServer(t, leaderMissing)
	backupSrv, backupHost, backupPort, stopBackup := startCountingNNTPServer(t, backupMissing)

	leaderPool := nntp.NewClientPool(leaderHost, leaderPort, false, "user", "pass", 4)
	backupPool := nntp.NewClientPool(backupHost, backupPort, false, "user", "pass", 4)

	p, err := NewPool(&Config{Providers: []ProviderConfig{
		{ID: "leader", Priority: 0, ClientPool: leaderPool},
		{ID: "second", Priority: 1, ClientPool: backupPool},
	}})
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}
	return p, leaderSrv, backupSrv, func() {
		leaderPool.Shutdown()
		backupPool.Shutdown()
		stopLeader()
		stopBackup()
	}
}

// The point of staging: a healthy leader settles the STAT by itself, so the
// other providers never spend a connection on it.
func TestStatSegmentHealthyLeaderAnswersAlone(t *testing.T) {
	p, leader, second, cleanup := twoProviderPool(t, false, false)
	defer cleanup()

	exists, err := p.StatSegment(context.Background(), "present-1", []string{"alt.binaries.test"})
	if err != nil || !exists {
		t.Fatalf("expected the article to be found, got exists=%v err=%v", exists, err)
	}
	if got := leader.stats.Load(); got != 1 {
		t.Fatalf("expected exactly one STAT on the leader, got %d", got)
	}
	if got := second.stats.Load(); got != 0 {
		t.Fatalf("the second provider should not have been consulted, saw %d STATs", got)
	}
}

// Staging must not cost correctness: when the leader 430s, the rest are still
// consulted and a copy anywhere counts as present.
func TestStatSegmentFallsBackWhenLeader430s(t *testing.T) {
	p, leader, second, cleanup := twoProviderPool(t, true, false)
	defer cleanup()

	exists, err := p.StatSegment(context.Background(), "present-2", []string{"alt.binaries.test"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !exists {
		t.Fatal("expected the second provider's copy to count as present")
	}
	if got := leader.stats.Load(); got != 1 {
		t.Fatalf("expected the leader to be tried once, got %d", got)
	}
	if got := second.stats.Load(); got < 1 {
		t.Fatal("expected the second provider to be consulted after the leader 430'd")
	}
}

// Only a unanimous 430 marks an article missing, and that verdict is cached.
func TestStatSegmentMissingEverywhereIsDefinitive(t *testing.T) {
	p, leader, second, cleanup := twoProviderPool(t, true, true)
	defer cleanup()

	exists, err := p.StatSegment(context.Background(), "missing-everywhere", []string{"alt.binaries.test"})
	if err != nil {
		t.Fatalf("a unanimous 430 is definitive, not an error: %v", err)
	}
	if exists {
		t.Fatal("expected the article to be reported missing")
	}
	if got := leader.stats.Load(); got != 1 {
		t.Fatalf("expected one STAT on the leader, got %d", got)
	}
	if got := second.stats.Load(); got < 1 {
		t.Fatal("expected every provider to be consulted before declaring a miss")
	}
	if !p.isKnownMissing("missing-everywhere") {
		t.Fatal("expected the unanimous miss to be cached")
	}
}

func TestStatConcurrencyDerivesFromLeadingProvider(t *testing.T) {
	small := nntp.NewClientPool("127.0.0.1", 1, false, "u", "p", 16)
	large := nntp.NewClientPool("127.0.0.1", 2, false, "u", "p", 100)
	defer small.Shutdown()
	defer large.Shutdown()

	p, err := NewPool(&Config{Providers: []ProviderConfig{
		{ID: "small", Priority: 0, ClientPool: small},
		{ID: "large", Priority: 1, ClientPool: large},
	}})
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}

	// 100 / 16 = 6, within the clamp.
	if got := p.StatConcurrency(); got != 6 {
		t.Fatalf("expected a budget of 6 from the largest pool, got %d", got)
	}
}

func TestStatSegmentWithNoProvidersIsInconclusive(t *testing.T) {
	p := &Pool{missing: newPermanentMissingSegments(8), articleStats: newArticleStatsRegistry(nil)}

	exists, err := p.StatSegment(context.Background(), "orphan", nil)
	if exists {
		t.Fatal("an empty pool cannot confirm an article exists")
	}
	if err == nil {
		t.Fatal("an empty pool must report inconclusive, not a definitive miss")
	}
}
