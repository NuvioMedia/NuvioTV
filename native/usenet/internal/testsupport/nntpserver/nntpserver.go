// Package nntpserver provides an in-process NNTP server that speaks enough of
// the wire protocol for a real *nntppool.Client to connect, authenticate,
// pipeline commands, and fetch yEnc article bodies.
//
// It exists because the questions "how much does a STAT sweep cost a live
// stream" and "does the priority lane actually protect playback" cannot be
// answered with a client-level fake. Those behaviours live inside nntppool's
// per-connection scheduler — the shared inflight semaphore, the separate body
// semaphore, the priority/normal request channels, and the strict FIFO reply
// ordering of a single NNTP connection. internal/testsupport/fakepool replaces
// that scheduler wholesale, so it cannot observe any of it.
//
// # Wire fidelity
//
// The one property that matters is pipelining with in-order replies: a client
// may have many commands outstanding on one connection, the server may work on
// them concurrently, but responses MUST come back in the order the commands
// arrived. Each connection therefore runs a reader that enqueues a response
// future per command and a writer that drains those futures in order. Without
// this, head-of-line effects — a STAT queued behind a 750 KB body, a priority
// BODY queued behind a hundred STATs — silently disappear.
//
// # Timing model
//
// RTT (± jitter) is applied per command as time-to-first-byte, concurrently
// across the connection's outstanding commands. Body payload bytes are then
// written by the single writer goroutine at BandwidthPerConn, which models one
// wire shared by everything in flight on that connection.
//
// All exported methods are safe for concurrent use.
package nntpserver

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"math/rand/v2"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/segments"
	"github.com/mnightingale/rapidyenc"
)

// writeChunk is the granularity at which the bandwidth throttle sleeps. Small
// enough that a 750 KB body is paced over many steps, large enough that the
// sleep count stays negligible next to the bytes moved.
const writeChunk = 32 * 1024

// Config describes the simulated provider.
type Config struct {
	Articles map[string][]byte // pre-encoded per-article yEnc bodies, without final dot
	// RTT is the time-to-first-byte applied to every command.
	RTT time.Duration
	// Jitter is the maximum symmetric deviation from RTT (uniform in
	// [-Jitter, +Jitter]). Zero makes latency deterministic.
	Jitter time.Duration
	// BandwidthPerConn throttles body payload bytes per connection, in bytes
	// per second. Zero disables the throttle.
	BandwidthPerConn int64
	// AggregateBandwidth caps total body payload bytes across ALL connections,
	// in bytes per second. Zero disables it.
	//
	// Without this, N connections each throttled to R sum to N×R, which no real
	// link does — and a harness that lets 100 connections reach 800 MB/s draws
	// exactly the wrong conclusions about whether spending connections on
	// latency costs throughput. Set it to the deployment's actual link rate.
	AggregateBandwidth int64
	// ArticleSize is the decoded size of the single article every BODY
	// request returns.
	ArticleSize int
	// Missing is the set of message-ids answered with 430 instead of a hit.
	// A nil map means every id exists.
	Missing map[string]struct{}
	// RequireAuth makes the server reject commands until AUTHINFO succeeds.
	RequireAuth bool
}

// Counters is a snapshot of what the server has served.
type Counters struct {
	Conns        int64
	Stats        int64
	StatMisses   int64
	Bodies       int64
	BytesWritten int64
	PeakInflight int64 // highest number of concurrently-computing commands on any one connection
	UnknownCmds  int64
}

// Server is a running fake NNTP provider.
type Server struct {
	cfg     Config
	ln      net.Listener
	article []byte // pre-encoded, dot-stuffed yEnc body; excludes the status line and the terminating dot

	wg     sync.WaitGroup
	closed atomic.Bool

	// aggMu/aggNext implement the aggregate ceiling as a virtual clock: each
	// writer reserves the slot at which its bytes may go out and sleeps until
	// then. Reservation order, not sleep precision, is what holds the rate, so
	// coarse OS timer granularity costs latency without inflating throughput.
	aggMu   sync.Mutex
	aggNext time.Time

	conns        atomic.Int64
	stats        atomic.Int64
	statMisses   atomic.Int64
	bodies       atomic.Int64
	bytesWritten atomic.Int64
	peakInflight atomic.Int64
	unknownCmds  atomic.Int64
}

// New starts a server on a loopback port. Close it when done.
func New(cfg Config) (*Server, error) {
	if cfg.ArticleSize <= 0 {
		cfg.ArticleSize = 750 * 1024
	}

	article, err := encodeArticle(cfg.ArticleSize)
	if err != nil {
		return nil, fmt.Errorf("nntpserver: encode article: %w", err)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("nntpserver: listen: %w", err)
	}

	s := &Server{cfg: cfg, ln: ln, article: article}
	s.wg.Add(1)
	go s.acceptLoop()
	return s, nil
}

// encodeArticle yEnc-encodes a deterministic payload once, so serving a body
// costs a memcpy rather than an encode. The result is dot-stuffed and ends
// with CRLF, ready to be followed by the terminating ".\r\n".
func encodeArticle(size int) ([]byte, error) {
	payload := segments.Payload(0, size)

	var buf bytes.Buffer
	enc, err := rapidyenc.NewEncoder(&buf, rapidyenc.Meta{
		FileName:   "altmount-bench.bin",
		FileSize:   int64(size),
		PartNumber: 1,
		TotalParts: 1,
		Offset:     0,
		PartSize:   int64(size),
	})
	if err != nil {
		return nil, err
	}
	if _, err := enc.Write(payload); err != nil {
		return nil, err
	}
	if err := enc.Close(); err != nil {
		return nil, err
	}

	// Dot-stuffing: any line starting with '.' must be doubled so it is not
	// mistaken for the terminating dot. yEnc rarely emits one, but a body that
	// terminates early is a silent, maddening benchmark bug.
	out := bytes.ReplaceAll(buf.Bytes(), []byte("\r\n."), []byte("\r\n.."))
	if len(out) > 0 && out[0] == '.' {
		out = append([]byte{'.'}, out...)
	}
	return out, nil
}

// Addr is the server's listening address.
func (s *Server) Addr() string { return s.ln.Addr().String() }

// Dial satisfies nntppool.ConnFactory.
func (s *Server) Dial(ctx context.Context) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, "tcp", s.Addr())
}

// Close stops the listener and waits for connection goroutines to finish.
func (s *Server) Close() error {
	if !s.closed.CompareAndSwap(false, true) {
		return nil
	}
	err := s.ln.Close()
	s.wg.Wait()
	return err
}

// ResetPeakInflight zeroes the pipeline-depth high-water mark, so a benchmark
// can measure its own window rather than inheriting a warm-up burst.
func (s *Server) ResetPeakInflight() { s.peakInflight.Store(0) }

// Counters snapshots what has been served so far.
func (s *Server) Counters() Counters {
	return Counters{
		Conns:        s.conns.Load(),
		Stats:        s.stats.Load(),
		StatMisses:   s.statMisses.Load(),
		Bodies:       s.bodies.Load(),
		BytesWritten: s.bytesWritten.Load(),
		PeakInflight: s.peakInflight.Load(),
		UnknownCmds:  s.unknownCmds.Load(),
	}
}

func (s *Server) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return // listener closed
		}
		s.conns.Add(1)
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.serve(conn)
		}()
	}
}

// response is one pending reply. head is written first; when body is non-nil it
// is written after head under the bandwidth throttle and followed by ".\r\n".
type response struct {
	head []byte
	body []byte
}

func (s *Server) serve(conn net.Conn) {
	defer func() { _ = conn.Close() }()

	// Buffered deep enough for the deepest pipeline a caller can open
	// (connections × StatInflight is per-connection bounded by StatInflight).
	futures := make(chan chan response, 8192)

	var writerWG sync.WaitGroup
	writerWG.Add(1)
	go func() {
		defer writerWG.Done()
		s.writeLoop(conn, futures)
	}()

	defer func() {
		close(futures)
		writerWG.Wait()
	}()

	if _, err := conn.Write([]byte("200 altmount-bench ready\r\n")); err != nil {
		return
	}

	var inflight atomic.Int64
	authed := !s.cfg.RequireAuth
	r := bufio.NewReaderSize(conn, 64*1024)

	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		cmd := strings.TrimRight(line, "\r\n")
		if cmd == "" {
			continue
		}

		// Commands answered inline, in order, with no simulated latency:
		// they are handshake/keepalive, not workload.
		if imm, quit, handled := s.immediate(cmd, &authed); handled {
			fut := make(chan response, 1)
			fut <- response{head: imm}
			futures <- fut
			if quit {
				return
			}
			continue
		}

		fut := make(chan response, 1)
		futures <- fut

		n := inflight.Add(1)
		for {
			peak := s.peakInflight.Load()
			if n <= peak || s.peakInflight.CompareAndSwap(peak, n) {
				break
			}
		}

		go func(cmd string, fut chan response) {
			defer inflight.Add(-1)
			time.Sleep(s.latency())
			fut <- s.answer(cmd)
		}(cmd, fut)
	}
}

// immediate handles the commands that must not be delayed or reordered.
func (s *Server) immediate(cmd string, authed *bool) (reply []byte, quit, handled bool) {
	upper := strings.ToUpper(cmd)
	switch {
	case strings.HasPrefix(upper, "AUTHINFO USER"):
		return []byte("281 authenticated\r\n"), false, true
	case strings.HasPrefix(upper, "AUTHINFO PASS"):
		*authed = true
		return []byte("281 authenticated\r\n"), false, true
	case upper == "DATE":
		return []byte("111 20260101000000\r\n"), false, true
	case upper == "QUIT":
		return []byte("205 bye\r\n"), true, true
	case upper == "MODE READER":
		return []byte("200 reader\r\n"), false, true
	}
	return nil, false, false
}

// answer produces the reply for a workload command.
func (s *Server) answer(cmd string) response {
	upper := strings.ToUpper(cmd)
	switch {
	case strings.HasPrefix(upper, "STAT "):
		id := messageID(cmd[len("STAT "):])
		if _, missing := s.cfg.Missing[id]; missing {
			s.stats.Add(1)
			s.statMisses.Add(1)
			return response{head: []byte("430 no such article\r\n")}
		}
		s.stats.Add(1)
		return response{head: fmt.Appendf(nil, "223 0 %s\r\n", id)}

	case strings.HasPrefix(upper, "BODY "):
		id := messageID(cmd[len("BODY "):])
		if _, missing := s.cfg.Missing[id]; missing {
			return response{head: []byte("430 no such article\r\n")}
		}
		s.bodies.Add(1)
		article := s.article
		if s.cfg.Articles != nil {
			var ok bool
			article, ok = s.cfg.Articles[id]
			if !ok {
				return response{head: []byte("430 no such article\r\n")}
			}
		}
		return response{
			head: fmt.Appendf(nil, "222 0 %s\r\n", id),
			body: article,
		}

	default:
		s.unknownCmds.Add(1)
		return response{head: []byte("500 unknown command\r\n")}
	}
}

// writeLoop drains response futures in arrival order, so replies leave the
// connection in the order their commands arrived — the property that makes
// head-of-line blocking reproduce.
func (s *Server) writeLoop(conn net.Conn, futures <-chan chan response) {
	w := bufio.NewWriterSize(conn, writeChunk)
	for fut := range futures {
		resp, ok := <-fut
		if !ok {
			return
		}
		if _, err := w.Write(resp.head); err != nil {
			return
		}
		if resp.body != nil {
			if err := s.writeThrottled(w, resp.body); err != nil {
				return
			}
			if _, err := w.Write([]byte(".\r\n")); err != nil {
				return
			}
		}
		// Flush only when nothing else is immediately ready, so a burst of
		// pipelined STAT replies coalesces into one write the way a real
		// server's socket buffer would.
		if len(futures) == 0 {
			if err := w.Flush(); err != nil {
				return
			}
		}
	}
	_ = w.Flush()
}

// reserveAggregate blocks until n bytes may be written under the aggregate
// ceiling shared by every connection. A no-op when no ceiling is configured.
func (s *Server) reserveAggregate(n int) {
	if s.cfg.AggregateBandwidth <= 0 {
		return
	}
	d := time.Duration(float64(n) / float64(s.cfg.AggregateBandwidth) * float64(time.Second))

	s.aggMu.Lock()
	now := time.Now()
	if s.aggNext.Before(now) {
		s.aggNext = now
	}
	at := s.aggNext
	s.aggNext = s.aggNext.Add(d)
	s.aggMu.Unlock()

	if wait := time.Until(at); wait > 0 {
		time.Sleep(wait)
	}
}

// writeThrottled paces body bytes at BandwidthPerConn and, when configured, at
// the aggregate ceiling shared across connections. The effective rate is the
// lower of the two.
func (s *Server) writeThrottled(w *bufio.Writer, body []byte) error {
	bw := s.cfg.BandwidthPerConn
	for off := 0; off < len(body); off += writeChunk {
		end := min(off+writeChunk, len(body))
		chunk := body[off:end]

		if bw > 0 || s.cfg.AggregateBandwidth > 0 {
			// Flush before sleeping: bytes the client cannot see yet are not
			// bytes on the wire, and the whole point is to pace the wire.
			if err := w.Flush(); err != nil {
				return err
			}
		}
		if bw > 0 {
			time.Sleep(time.Duration(float64(len(chunk)) / float64(bw) * float64(time.Second)))
		}
		s.reserveAggregate(len(chunk))

		n, err := w.Write(chunk)
		s.bytesWritten.Add(int64(n))
		if err != nil {
			return err
		}
	}
	return nil
}

// messageID strips the angle brackets NNTP wraps a message-id in, so Config.Missing
// can be keyed on the bare ids callers actually hold.
func messageID(arg string) string {
	return strings.Trim(strings.TrimSpace(arg), "<>")
}

func (s *Server) latency() time.Duration {
	d := s.cfg.RTT
	if s.cfg.Jitter > 0 {
		d += time.Duration(rand.Int64N(int64(2*s.cfg.Jitter))) - s.cfg.Jitter
	}
	if d < 0 {
		return 0
	}
	return d
}
