package nntppool

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// countingConn tallies Write calls on the client side of a connection.
//
// Write count is the quantity the coalescing question turns on: the writer
// loop's bufio.Writer only saves anything if several commands share one Write.
// One Write per command is one syscall per command and, with TLS underneath,
// one record per command — 29 bytes of framing on a ~50-byte STAT.
type countingConn struct {
	net.Conn
	c *statCounter
}

func (c *countingConn) Write(p []byte) (int, error) {
	c.c.writes.Add(1)
	c.c.bytes.Add(int64(len(p)))
	return c.Conn.Write(p)
}

// statCounter aggregates write counts across every connection a sweep opens.
type statCounter struct {
	writes atomic.Int64
	bytes  atomic.Int64
}

// startStatMockServer runs a TCP mock that answers every STAT with 223.
//
// The server flushes only once its read buffer is drained, so it answers a
// pipelined batch in one write the way a real server does, and applies rtt at
// that point only — a sweep that keeps the pipeline full pays it once per
// batch rather than once per command.
func startStatMockServer(tb testing.TB, rtt time.Duration) net.Listener {
	tb.Helper()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		tb.Fatalf("listen: %v", err)
	}
	tb.Cleanup(func() { _ = ln.Close() })

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer func() { _ = conn.Close() }()
				r := bufio.NewReader(conn)
				w := bufio.NewWriter(conn)
				if _, err := w.WriteString("200 mock ready\r\n"); err != nil {
					return
				}
				if err := w.Flush(); err != nil {
					return
				}
				for {
					line, err := r.ReadString('\n')
					if err != nil {
						return
					}
					if !strings.HasPrefix(line, "STAT") {
						continue
					}
					if _, err := w.WriteString("223 0 <x@h> exists\r\n"); err != nil {
						return
					}
					if r.Buffered() == 0 {
						if rtt > 0 {
							time.Sleep(rtt)
						}
						if err := w.Flush(); err != nil {
							return
						}
					}
				}
			}(conn)
		}
	}()

	return ln
}

// countingFactory dials the mock and reports every client-side Write into c.
func countingFactory(addr string, c *statCounter) ConnFactory {
	return func(ctx context.Context) (net.Conn, error) {
		var d net.Dialer
		raw, err := d.DialContext(ctx, "tcp", addr)
		if err != nil {
			return nil, err
		}
		return &countingConn{Conn: raw, c: c}, nil
	}
}

// sweepClient builds a client pointed at addr with a deep STAT pipeline.
func sweepClient(tb testing.TB, addr string, conns, statInflight int, c *statCounter) (*Client, context.CancelFunc) {
	tb.Helper()

	ctx, cancel := context.WithCancel(context.Background())
	cl, err := NewClient(ctx, []Provider{{
		Factory:        countingFactory(addr, c),
		Connections:    conns,
		MinConnections: conns,
		Inflight:       2,
		StatInflight:   statInflight,
		SkipPing:       true,
	}})
	if err != nil {
		cancel()
		tb.Fatalf("NewClient: %v", err)
	}
	return cl, cancel
}

func runSweep(tb testing.TB, cl *Client, n int) int {
	tb.Helper()

	ids := make([]string, n)
	for i := range ids {
		ids[i] = fmt.Sprintf("a%d@h", i)
	}
	ok := 0
	for r := range cl.StatMany(context.Background(), ids, StatManyOptions{}) {
		if r.Err == nil && r.Result != nil {
			ok++
		}
	}
	return ok
}

// TestStatSweep_CoalescesWrites asserts that a pipelined STAT sweep leaves in
// fewer Writes than it has commands.
//
// This is the claim the writer loop's bufio.Writer is there to make good on.
// It fails today: the loop flushes whenever anything is buffered, without
// first checking whether it is actually about to block, so every command gets
// its own Write.
func TestStatSweep_CoalescesWrites(t *testing.T) {
	const nStats = 512

	ln := startStatMockServer(t, 2*time.Millisecond)
	var counter statCounter
	cl, cancel := sweepClient(t, ln.Addr().String(), 4, 32, &counter)

	if got := runSweep(t, cl, nStats); got != nStats {
		cancel()
		_ = cl.Close()
		t.Fatalf("sweep completed %d of %d STATs", got, nStats)
	}

	writes := counter.writes.Load()
	_ = cl.Close()
	cancel()

	t.Logf("%d STATs → %d writes (%.2f writes/STAT, %d bytes)",
		nStats, writes, float64(writes)/float64(nStats), counter.bytes.Load())

	if writes >= nStats {
		t.Errorf("no write coalescing: %d writes for %d STATs (want < %d)",
			writes, nStats, nStats)
	}
}

// BenchmarkStatSweepConcurrency reports writes-per-STAT as the sweep's
// dispatch bound is raised past the pool's pipeline capacity (here 4 conns x
// StatInflight 32 = 128).
//
// Coalescing needs a backlog, and a dispatch bound equal to capacity produces
// none: every STAT it admits is one the pipeline immediately swallows, so the
// writer never has a second command in hand while writing the first. The
// oversubscribed rows are what a queue actually buys.
func BenchmarkStatSweepConcurrency(b *testing.B) {
	for _, conc := range []int{128, 512, 2048} {
		b.Run(fmt.Sprintf("conc=%d", conc), func(b *testing.B) {
			const nStats = 512

			ln := startStatMockServer(b, 2*time.Millisecond)
			var counter statCounter
			cl, cancel := sweepClient(b, ln.Addr().String(), 4, 32, &counter)
			defer func() {
				_ = cl.Close()
				cancel()
			}()

			ids := make([]string, nStats)
			for i := range ids {
				ids[i] = fmt.Sprintf("a%d@h", i)
			}

			b.ResetTimer()
			for range b.N {
				ok := 0
				for r := range cl.StatMany(context.Background(), ids, StatManyOptions{Concurrency: conc}) {
					if r.Err == nil && r.Result != nil {
						ok++
					}
				}
				if ok != nStats {
					b.Fatalf("sweep completed %d of %d STATs", ok, nStats)
				}
			}
			b.StopTimer()

			b.ReportMetric(float64(counter.writes.Load())/float64(nStats*b.N), "writes/STAT")
		})
	}
}

// BenchmarkStatSweepWrites reports writes-per-STAT for a sweep, at zero
// latency and at a latency where a backlog is guaranteed to build.
func BenchmarkStatSweepWrites(b *testing.B) {
	for _, rtt := range []time.Duration{0, 2 * time.Millisecond} {
		b.Run(fmt.Sprintf("rtt=%v", rtt), func(b *testing.B) {
			const nStats = 512

			ln := startStatMockServer(b, rtt)
			var counter statCounter
			cl, cancel := sweepClient(b, ln.Addr().String(), 4, 32, &counter)
			defer func() {
				_ = cl.Close()
				cancel()
			}()

			b.ResetTimer()
			for range b.N {
				if got := runSweep(b, cl, nStats); got != nStats {
					b.Fatalf("sweep completed %d of %d STATs", got, nStats)
				}
			}
			b.StopTimer()

			total := float64(nStats * b.N)
			b.ReportMetric(float64(counter.writes.Load())/total, "writes/STAT")
		})
	}
}
