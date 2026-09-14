package nntppool

import (
	"bytes"
	"context"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// slowBodyServer answers every BODY with the same yEnc article, written in
// 32 KB slices with a pause between them, and counts connections and closes.
type slowBodyServer struct {
	article []byte
	pause   time.Duration
	conns   atomic.Int32
	closed  atomic.Int32
}

func (s *slowBodyServer) factory(ctx context.Context) (net.Conn, error) {
	client, server := net.Pipe()
	s.conns.Add(1)
	go func() {
		defer func() { s.closed.Add(1) }()
		_, _ = server.Write([]byte("200 ready\r\n"))
		buf := make([]byte, 4096)
		for {
			n, err := server.Read(buf)
			if err != nil {
				return
			}
			if !bytes.Contains(buf[:n], []byte("BODY")) {
				_, _ = server.Write([]byte("111 20260101120000\r\n"))
				continue
			}
			for off := 0; off < len(s.article); off += 32 << 10 {
				end := min(off+32<<10, len(s.article))
				if _, err := server.Write(s.article[off:end]); err != nil {
					return // client closed the connection
				}
				time.Sleep(s.pause)
			}
		}
	}()
	return client, nil
}

type gateWriter struct {
	first chan struct{}
	once  sync.Once
	n     atomic.Int64
}

func (w *gateWriter) Write(p []byte) (int, error) {
	w.n.Add(int64(len(p)))
	w.once.Do(func() { close(w.first) })
	return len(p), nil
}

func newAbortClient(t *testing.T, srv *slowBodyServer, abort int64) *Client {
	t.Helper()
	c, err := NewClient(context.Background(), []Provider{{
		Factory: srv.factory, Connections: 1, Inflight: 4, StatInflight: 4,
		SkipPing: true, IdleTimeout: time.Hour, AbortDrainBytes: abort,
	}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// A cancelled body with megabytes still to come closes the connection
// instead of draining it, and the next request gets a fresh connection.
func TestAbortDrainClosesConnectionOnLargeRemainder(t *testing.T) {
	srv := &slowBodyServer{article: yencSinglePart(bytes.Repeat([]byte{7}, 4<<20), "big.bin"), pause: 5 * time.Millisecond}
	c := newAbortClient(t, srv, 1<<20)

	ctx, cancel := context.WithCancel(context.Background())
	w := &gateWriter{first: make(chan struct{})}
	done := make(chan error, 1)
	go func() { _, err := c.BodyStreamPriority(ctx, "big@test", w); done <- err }()
	select {
	case <-w.first:
	case <-time.After(5 * time.Second):
		t.Fatal("no bytes arrived")
	}
	cancel()
	if err := <-done; err == nil {
		t.Fatal("cancelled request must fail")
	}
	deadline := time.Now().Add(2 * time.Second)
	for srv.closed.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if srv.closed.Load() == 0 {
		t.Fatal("connection was drained instead of closed")
	}

	// The slot reconnects and serves the next request.
	ctx2, cancel2 := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel2()
	body, err := c.BodyPriority(ctx2, "again@test")
	if err != nil || len(body.Bytes) != 4<<20 {
		t.Fatalf("follow-up body: err=%v len=%d", err, len(body.Bytes))
	}
	if srv.conns.Load() != 2 {
		t.Fatalf("connections = %d, want 2 (one closed, one fresh)", srv.conns.Load())
	}
}

// A small remainder is drained as before and the connection is reused.
func TestSmallRemainderIsDrainedNotClosed(t *testing.T) {
	srv := &slowBodyServer{article: yencSinglePart(bytes.Repeat([]byte{7}, 256<<10), "small.bin"), pause: 2 * time.Millisecond}
	c := newAbortClient(t, srv, 1<<20)

	ctx, cancel := context.WithCancel(context.Background())
	w := &gateWriter{first: make(chan struct{})}
	done := make(chan error, 1)
	go func() { _, err := c.BodyStreamPriority(ctx, "small@test", w); done <- err }()
	<-w.first
	cancel()
	<-done

	ctx2, cancel2 := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel2()
	if _, err := c.BodyPriority(ctx2, "again@test"); err != nil {
		t.Fatal(err)
	}
	if srv.conns.Load() != 1 || srv.closed.Load() != 0 {
		t.Fatalf("small drain must keep the connection: conns=%d closed=%d", srv.conns.Load(), srv.closed.Load())
	}
}

// heldServer accepts BODY commands and counts them, releasing responses only
// when told to, so the test can see how many bodies the client let through.
type heldServer struct {
	mu       sync.Mutex
	received int
	release  chan struct{}
	article  []byte
}

func (s *heldServer) factory(ctx context.Context) (net.Conn, error) {
	client, server := net.Pipe()
	go func() {
		_, _ = server.Write([]byte("200 ready\r\n"))
		buf := make([]byte, 4096)
		var pending int
		for {
			n, err := server.Read(buf)
			if err != nil {
				return
			}
			pending += bytes.Count(buf[:n], []byte("BODY"))
			s.mu.Lock()
			s.received += bytes.Count(buf[:n], []byte("BODY"))
			s.mu.Unlock()
			if !bytes.Contains(buf[:n], []byte("BODY")) {
				_, _ = server.Write([]byte("111 20260101120000\r\n"))
				continue
			}
			select {
			case <-s.release:
				for ; pending > 0; pending-- {
					_, _ = server.Write(s.article)
				}
			default:
			}
		}
	}()
	return client, nil
}

func (s *heldServer) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.received
}

// With StreamInflight 2 on a single connection, only two priority bodies
// reach the wire until one completes; normal bodies are not capped.
func TestStreamInflightCapsPriorityBodies(t *testing.T) {
	for _, priority := range []bool{true, false} {
		srv := &heldServer{release: make(chan struct{}), article: yencSinglePart([]byte("x"), "x")}
		c, err := NewClient(context.Background(), []Provider{{
			Factory: srv.factory, Connections: 1, Inflight: 10, StatInflight: 10, StreamInflight: 2,
			SkipPing: true, IdleTimeout: time.Hour,
		}})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		for i := 0; i < 4; i++ {
			go func() {
				if priority {
					_, _ = c.BodyStreamPriority(ctx, "p@test", io.Discard)
				} else {
					_, _ = c.BodyStream(ctx, "n@test", io.Discard)
				}
			}()
		}
		time.Sleep(300 * time.Millisecond)
		got := srv.count()
		cancel()
		_ = c.Close()
		want := 4
		if priority {
			want = 2
		}
		if got != want {
			t.Fatalf("priority=%v: %d BODY commands on the wire, want %d", priority, got, want)
		}
	}
}

// A connection holding its StreamInflight priority bodies must leave further
// priority requests on the lane for other slots, so a second connection dials
// and takes them instead of the first parking them behind its full pipeline.
func TestFullPriorityPipelineLeavesRequestsForIdleSlots(t *testing.T) {
	for round := 0; round < 20; round++ {
		var dials atomic.Int32
		srv := &heldServer{release: make(chan struct{}), article: yencSinglePart([]byte("x"), "x")}
		factory := func(ctx context.Context) (net.Conn, error) {
			dials.Add(1)
			return srv.factory(ctx)
		}
		c, err := NewClient(context.Background(), []Provider{{
			Factory: factory, Connections: 2, Inflight: 10, StatInflight: 10, StreamInflight: 2,
			SkipPing: true, IdleTimeout: time.Hour,
		}})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		for i := 0; i < 4; i++ {
			go func() { _, _ = c.BodyStreamPriority(ctx, "p@test", io.Discard) }()
		}
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) && srv.count() < 4 {
			time.Sleep(10 * time.Millisecond)
		}
		got, d := srv.count(), dials.Load()
		cancel()
		_ = c.Close()
		if got != 4 || d != 2 {
			t.Fatalf("round %d: %d BODY commands on the wire over %d connections, want 4 over 2", round, got, d)
		}
	}
}

// Priority bodies spread across slots before they deepen one connection's
// pipeline: a connection with a priority body in flight leaves the hot lane,
// so the next request goes cold, where idle slots compete for it and dial.
func TestPriorityBodiesSpreadBeforeDeepening(t *testing.T) {
	const rounds = 20
	total := 0
	for round := 0; round < rounds; round++ {
		var dials atomic.Int32
		srv := &heldServer{release: make(chan struct{}), article: yencSinglePart([]byte("x"), "x")}
		factory := func(ctx context.Context) (net.Conn, error) {
			dials.Add(1)
			return srv.factory(ctx)
		}
		c, err := NewClient(context.Background(), []Provider{{
			Factory: factory, Connections: 4, Inflight: 10, StatInflight: 10, StreamInflight: 4,
			SkipPing: true, IdleTimeout: time.Hour,
		}})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		for i := 0; i < 4; i++ {
			go func() { _, _ = c.BodyStreamPriority(ctx, "p@test", io.Discard) }()
			deadline := time.Now().Add(2 * time.Second)
			for time.Now().Before(deadline) && srv.count() < i+1 {
				time.Sleep(5 * time.Millisecond)
			}
		}
		total += int(dials.Load())
		cancel()
		_ = c.Close()
	}
	if avg := float64(total) / rounds; avg < 2.2 {
		t.Fatalf("4 sequential priority bodies over 4 slots dialed %.2f connections on average, want spread (>= 2.2)", avg)
	}
}
