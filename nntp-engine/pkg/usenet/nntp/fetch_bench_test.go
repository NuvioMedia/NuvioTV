package nntp

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// Benchmarks for NNTP command round-trip overhead per article (issue #198).
//
// A fake provider runs on loopback and answers BODY with a fixed-size body. All
// simulated latency is applied on the RESPONSE path by delayedWriter: the
// handler never sleeps, so responses to commands that were pipelined overlap in
// flight exactly as they would on a real link. One `delay` therefore equals one
// full command round trip.
//
// Bodies are discarded, not decoded — decoder cost is measured separately by the
// benchmarks in pkg/media/decode. What these measure is purely the wall-clock a
// segment fetch spends waiting on command round trips.

const benchBodyBytes = 768000

// delayedWriter releases each Write to the underlying conn `delay` after it was
// enqueued, preserving order. Queued writes overlap in flight, so N responses
// written back to back are delivered ~delay later, not N*delay later.
type delayedWriter struct {
	w     io.Writer
	delay time.Duration
	// bps throttles the link to a fixed bytes/second, on top of delay. Zero
	// leaves it unthrottled. Without it every benchmark measures a link with
	// infinite bandwidth, where hiding a round trip looks like the whole cost
	// of an article rather than the fraction of it a real provider sees.
	bps  int
	ch   chan delayedChunk
	done chan struct{}
	once sync.Once
}

type delayedChunk struct {
	at   time.Time
	data []byte
}

func newDelayedWriter(w io.Writer, delay time.Duration, bps int) *delayedWriter {
	d := &delayedWriter{w: w, delay: delay, bps: bps, ch: make(chan delayedChunk, 256), done: make(chan struct{})}
	go d.pump()
	return d
}

func (d *delayedWriter) pump() {
	defer close(d.done)
	// nextFree is when the throttled link finishes draining what it has already
	// accepted, so back-to-back chunks queue behind each other instead of each
	// paying only its own transmit time.
	var nextFree time.Time
	for c := range d.ch {
		at := c.at
		if d.bps > 0 {
			if nextFree.After(at) {
				at = nextFree
			}
			nextFree = at.Add(time.Duration(float64(len(c.data)) / float64(d.bps) * float64(time.Second)))
		}
		if wait := time.Until(at); wait > 0 {
			time.Sleep(wait)
		}
		if _, err := d.w.Write(c.data); err != nil {
			return
		}
	}
}

func (d *delayedWriter) Write(p []byte) (int, error) {
	buf := make([]byte, len(p))
	copy(buf, p)
	select {
	case d.ch <- delayedChunk{at: time.Now().Add(d.delay), data: buf}:
		return len(p), nil
	case <-d.done:
		return 0, io.ErrClosedPipe
	}
}

func (d *delayedWriter) Close() { d.once.Do(func() { close(d.ch); <-d.done }) }

// benchServer answers the subset of NNTP the fetch path uses.
type benchServer struct {
	ln    net.Listener
	body  []byte
	delay time.Duration
	bps   int
	wg    sync.WaitGroup
}

func startBenchServer(tb testing.TB, delay time.Duration) *benchServer {
	return startThrottledBenchServer(tb, delay, 0)
}

func startThrottledBenchServer(tb testing.TB, delay time.Duration, bps int) *benchServer {
	tb.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		tb.Fatalf("listen: %v", err)
	}
	// A body of realistic size; content is irrelevant since nothing decodes it.
	body := bytes.Repeat([]byte("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+/\r\n"), benchBodyBytes/66)
	s := &benchServer{ln: ln, body: body, delay: delay, bps: bps}
	s.wg.Add(1)
	go s.accept()
	tb.Cleanup(func() {
		_ = ln.Close()
		s.wg.Wait()
	})
	return s
}

func (s *benchServer) addr() (string, int) {
	a := s.ln.Addr().(*net.TCPAddr)
	return a.IP.String(), a.Port
}

func (s *benchServer) accept() {
	defer s.wg.Done()
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.handle(conn)
		}()
	}
}

func (s *benchServer) handle(conn net.Conn) {
	defer conn.Close()
	var w io.Writer = conn
	if s.delay > 0 || s.bps > 0 {
		dw := newDelayedWriter(conn, s.delay, s.bps)
		defer dw.Close()
		w = dw
	}
	bw := bufio.NewWriterSize(w, 64<<10)
	reply := func(format string, args ...any) error {
		if _, err := fmt.Fprintf(bw, format, args...); err != nil {
			return err
		}
		return bw.Flush()
	}
	if reply("200 bench ready\r\n") != nil {
		return
	}
	r := bufio.NewReaderSize(conn, 8<<10)
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		cmd := strings.ToUpper(strings.Fields(strings.TrimRight(line, "\r\n"))[0])
		switch cmd {
		case "AUTHINFO":
			if strings.Contains(strings.ToUpper(line), "USER") {
				err = reply("381 password\r\n")
			} else {
				err = reply("281 ok\r\n")
			}
		case "GROUP":
			err = reply("211 1 1 1 alt.bench\r\n")
		case "DATE":
			err = reply("111 20260101000000\r\n")
		case "BODY":
			if _, werr := bw.WriteString("222 0 <bench>\r\n"); werr != nil {
				return
			}
			if _, werr := bw.Write(s.body); werr != nil {
				return
			}
			if _, werr := bw.WriteString(".\r\n"); werr != nil {
				return
			}
			err = bw.Flush()
		case "QUIT":
			_ = reply("205 bye\r\n")
			return
		default:
			err = reply("500 unknown\r\n")
		}
		if err != nil {
			return
		}
	}
}

func benchClient(tb testing.TB, s *benchServer) *Client {
	tb.Helper()
	host, port := s.addr()
	c, err := NewClient(host, port, false)
	if err != nil {
		tb.Fatalf("dial: %v", err)
	}
	tb.Cleanup(func() { _ = c.Quit() })
	return c
}

// drainBody reads and discards one article body.
func drainBody(tb testing.TB, r io.ReadCloser) {
	n, err := io.Copy(io.Discard, r)
	_ = r.Close()
	if err != nil {
		tb.Fatalf("body read: %v", err)
	}
	if n == 0 {
		tb.Fatal("empty body")
	}
}

func forEachDelay(b *testing.B, fn func(b *testing.B, s *benchServer)) {
	for _, d := range []time.Duration{0, 10 * time.Millisecond, 30 * time.Millisecond} {
		b.Run(fmt.Sprintf("rtt=%s", d), func(b *testing.B) {
			s := startBenchServer(b, d)
			b.SetBytes(benchBodyBytes)
			b.ResetTimer()
			fn(b, s)
		})
	}
}

// BenchmarkArticleGroupThenBody is the production sequence: every segment fetch
// calls Group before Body on its leased connection. Since Client.Group skips a
// GROUP for the group already selected, this must now cost the same as
// BenchmarkArticleBodyOnly — one command round trip, not two. A regression that
// reinstates the per-article GROUP shows up here as a doubling at rtt > 0.
func BenchmarkArticleGroupThenBody(b *testing.B) {
	forEachDelay(b, func(b *testing.B, s *benchServer) {
		c := benchClient(b, s)
		for i := 0; i < b.N; i++ {
			if err := c.Group("alt.bench"); err != nil {
				b.Fatalf("group: %v", err)
			}
			r, err := c.Body("<bench>")
			if err != nil {
				b.Fatalf("body: %v", err)
			}
			drainBody(b, r)
		}
	})
}

// BenchmarkArticleBodyOnly drops the per-article GROUP, keeping one command
// round trip per article.
func BenchmarkArticleBodyOnly(b *testing.B) {
	forEachDelay(b, func(b *testing.B, s *benchServer) {
		c := benchClient(b, s)
		if err := c.Group("alt.bench"); err != nil {
			b.Fatalf("group: %v", err)
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			r, err := c.Body("<bench>")
			if err != nil {
				b.Fatalf("body: %v", err)
			}
			drainBody(b, r)
		}
	})
}

// benchPipelined issues `depth` BODY commands before reading the first reply,
// then keeps `depth` commands outstanding — the prototype of the pipelining the
// issue asks about. It uses the textproto pipeline the Client already owns, so
// it measures what production would get, not an idealized socket.
func benchPipelined(b *testing.B, c *Client, depth int) {
	type pending struct {
		id uint
	}
	queue := make([]pending, 0, depth)
	issue := func() error {
		c.setDeadline()
		id, err := c.conn.Cmd("BODY %s", "<bench>")
		if err != nil {
			return err
		}
		queue = append(queue, pending{id: id})
		return nil
	}
	consume := func() error {
		p := queue[0]
		queue = queue[1:]
		c.conn.StartResponse(p.id)
		defer c.conn.EndResponse(p.id)
		if _, _, err := c.conn.ReadCodeLine(222); err != nil {
			return err
		}
		n, err := io.Copy(io.Discard, newArticleReader(c.conn.R))
		if err != nil {
			return err
		}
		if n == 0 {
			return fmt.Errorf("empty body")
		}
		return nil
	}

	issued := 0
	for i := 0; i < b.N; i++ {
		for issued < b.N && len(queue) < depth {
			if err := issue(); err != nil {
				b.Fatalf("issue: %v", err)
			}
			issued++
		}
		if err := consume(); err != nil {
			b.Fatalf("consume: %v", err)
		}
	}
}

// BenchmarkArticleBodyPipelined measures BODY pipelining at several depths.
func BenchmarkArticleBodyPipelined(b *testing.B) {
	for _, depth := range []int{2, 4, 8} {
		b.Run(fmt.Sprintf("depth=%d", depth), func(b *testing.B) {
			forEachDelay(b, func(b *testing.B, s *benchServer) {
				c := benchClient(b, s)
				if err := c.Group("alt.bench"); err != nil {
					b.Fatalf("group: %v", err)
				}
				b.ResetTimer()
				benchPipelined(b, c, depth)
			})
		})
	}
}

// Throttled benchmarks: the same comparison on a link with finite bandwidth,
// which is the only form of it that predicts anything about production.
//
// On an unthrottled loopback an article transfers instantly, so the round trip
// IS the article's cost and pipelining looks like a 6x win. On a real link the
// article takes size/bandwidth to arrive and the round trip is only the gap in
// front of it, so the win is 1 + RTT/(size/bandwidth) — large on a fast
// connection, small on a slow one. These runs bracket that: 25 Mbit is a
// per-connection rate a throttling provider hands out, 100 Mbit is what an
// unthrottled one does on a well-peered line.
var benchLinkRates = []struct {
	name string
	bps  int
}{
	{"25Mbit", 25_000_000 / 8},
	{"100Mbit", 100_000_000 / 8},
}

func forEachThrottledLink(b *testing.B, fn func(b *testing.B, s *benchServer)) {
	for _, rate := range benchLinkRates {
		for _, d := range []time.Duration{30 * time.Millisecond, 80 * time.Millisecond} {
			b.Run(fmt.Sprintf("%s/rtt=%s", rate.name, d), func(b *testing.B) {
				s := startThrottledBenchServer(b, d, rate.bps)
				b.SetBytes(benchBodyBytes)
				b.ResetTimer()
				fn(b, s)
			})
		}
	}
}

// BenchmarkThrottledBodyOnly is the production sequence on a finite link: one
// BODY at a time, one idle round trip between articles.
func BenchmarkThrottledBodyOnly(b *testing.B) {
	forEachThrottledLink(b, func(b *testing.B, s *benchServer) {
		c := benchClient(b, s)
		if err := c.Group("alt.bench"); err != nil {
			b.Fatalf("group: %v", err)
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			r, err := c.Body("<bench>")
			if err != nil {
				b.Fatalf("body: %v", err)
			}
			drainBody(b, r)
		}
	})
}

// BenchmarkThrottledBodyPipelined is the same link driven by BodyPipeline. The
// delta against BenchmarkThrottledBodyOnly at equal rate and RTT is what
// read-ahead batching is worth on a connection that is already saturated.
func BenchmarkThrottledBodyPipelined(b *testing.B) {
	for _, depth := range []int{2, 3, 4} {
		b.Run(fmt.Sprintf("depth=%d", depth), func(b *testing.B) {
			forEachThrottledLink(b, func(b *testing.B, s *benchServer) {
				c := benchClient(b, s)
				if err := c.Group("alt.bench"); err != nil {
					b.Fatalf("group: %v", err)
				}
				b.ResetTimer()
				benchBodyPipeline(b, c, depth)
			})
		})
	}
}

// benchBodyPipeline drives the production BodyPipeline, keeping depth commands
// outstanding. Unlike benchPipelined it exercises the shipped type, so a
// regression in Issue/Next shows up here rather than only in production.
func benchBodyPipeline(b *testing.B, c *Client, depth int) {
	p := c.NewBodyPipeline(depth)
	issued := 0
	for i := 0; i < b.N; i++ {
		for issued < b.N && p.Len() < p.Depth() {
			if err := p.Issue("<bench>"); err != nil {
				b.Fatalf("issue: %v", err)
			}
			issued++
		}
		reply, err := p.Next()
		if err != nil {
			b.Fatalf("next: %v", err)
		}
		if reply.Err != nil {
			b.Fatalf("article: %v", reply.Err)
		}
		drainBody(b, reply.Body)
	}
}
