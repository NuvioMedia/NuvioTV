package nntppool

import (
	"context"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

func newBgLaneTestConn(prio, req, bg chan *Request) *NNTPConnection {
	return &NNTPConnection{prioCh: prio, reqCh: req, bgCh: bg}
}

func TestTryNextRequest_BackgroundAfterNormal(t *testing.T) {
	for i := range 100 {
		req := make(chan *Request, 1)
		bg := make(chan *Request, 1)
		normReq := &Request{Payload: []byte("BODY <norm>\r\n")}
		bg <- &Request{Payload: []byte("BODY <bg>\r\n"), lane: laneBackground}
		req <- normReq

		c := newBgLaneTestConn(nil, req, bg)
		got, ok, found := c.tryNextRequest()
		if !found || !ok {
			t.Fatalf("iteration %d: found=%v ok=%v, want both true", i, found, ok)
		}
		if got != normReq {
			t.Fatalf("iteration %d: background request outranked a normal one", i)
		}
	}
}

func TestTryNextRequest_BackgroundServedWhenAlone(t *testing.T) {
	bg := make(chan *Request, 1)
	bgReq := &Request{Payload: []byte("BODY <bg>\r\n"), lane: laneBackground}
	bg <- bgReq

	c := newBgLaneTestConn(nil, nil, bg)
	got, ok, found := c.tryNextRequest()
	if !found || !ok || got != bgReq {
		t.Fatalf("found=%v ok=%v got=%v, want the background request", found, ok, got)
	}
}

func TestBackgroundLaneGate(t *testing.T) {
	bg := make(chan *Request)
	cases := []struct {
		name         string
		foregroundAt time.Duration // age of the last foreground dispatch; <0 = never
		inflight     int32
		floor        int32
		wantOpen     bool
	}{
		{"idle pool, nothing in flight", -1, 0, 2, true},
		{"idle pool, over the floor", -1, 8, 2, true},
		{"recent foreground, under floor", 100 * time.Millisecond, 1, 2, true},
		{"recent foreground, at floor", 100 * time.Millisecond, 2, 2, false},
		{"recent foreground, over floor", 100 * time.Millisecond, 5, 2, false},
		{"stale foreground, over floor", backgroundYieldWindow + time.Second, 5, 2, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			stats := &providerStats{}
			stats.bgFloor = tc.floor
			stats.bgInflight.Store(tc.inflight)
			if tc.foregroundAt >= 0 {
				stats.lastForeground.Store(time.Now().Add(-tc.foregroundAt).UnixNano())
			}
			got := backgroundLaneFor(stats, bg) != nil
			if got != tc.wantOpen {
				t.Fatalf("lane open = %v, want %v", got, tc.wantOpen)
			}
		})
	}
}

func TestBackgroundLaneGateStandalone(t *testing.T) {
	bg := make(chan *Request)
	if backgroundLaneFor(nil, bg) == nil {
		t.Fatal("a standalone connection (no stats) must always read the background lane")
	}
}

// bgEchoServer answers BODY with a small yEnc article and STAT with 223.
type bgEchoServer struct{}

func (bgEchoServer) factory() ConnFactory {
	return func(ctx context.Context) (net.Conn, error) {
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
				for _, cmd := range strings.Split(strings.TrimRight(string(buf[:n]), "\r\n"), "\r\n") {
					switch {
					case strings.HasPrefix(cmd, "DATE"):
						_, _ = server.Write([]byte("111 20240101000000\r\n"))
					case strings.HasPrefix(cmd, "STAT "):
						_, _ = server.Write([]byte("223 0 " + strings.TrimPrefix(cmd, "STAT ") + "\r\n"))
					case strings.HasPrefix(cmd, "BODY "):
						_, _ = server.Write(yencSinglePart([]byte("payload"), "f.bin"))
					default:
						_, _ = server.Write([]byte("500 unsupported\r\n"))
					}
				}
			}
		}()
		return client, nil
	}
}

func newBgTestClient(t *testing.T) (*Client, *providerGroup) {
	t.Helper()
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     bgEchoServer{}.factory(),
		Connections: 2,
		SkipPing:    true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c, (*c.mainGroups.Load())[0]
}

func TestClient_BodyBackgroundRoundTrip(t *testing.T) {
	c, g := newBgTestClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	body, err := c.BodyBackground(ctx, "bg@h")
	if err != nil {
		t.Fatalf("BodyBackground: %v", err)
	}
	if string(body.Bytes) != "payload" {
		t.Fatalf("decoded %q, want %q", body.Bytes, "payload")
	}
	if got := g.stats.bgInflight.Load(); got != 0 {
		t.Fatalf("bgInflight after completion = %d, want 0", got)
	}
	if g.stats.lastForeground.Load() != 0 {
		t.Fatal("a background body must not stamp lastForeground")
	}

	if _, err := c.Body(ctx, "fg@h"); err != nil {
		t.Fatalf("Body: %v", err)
	}
	if g.stats.lastForeground.Load() == 0 {
		t.Fatal("a normal-lane body must stamp lastForeground")
	}
	if got := g.stats.bgInflight.Load(); got != 0 {
		t.Fatalf("bgInflight after a foreground body = %d, want 0", got)
	}
}

func TestClient_StatBackgroundAndStatManyBackground(t *testing.T) {
	c, g := newBgTestClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := c.StatBackground(ctx, "one@h"); err != nil {
		t.Fatalf("StatBackground: %v", err)
	}
	n := 0
	for r := range c.StatMany(ctx, []string{"a@h", "b@h", "c@h"}, StatManyOptions{Background: true}) {
		if r.Err != nil {
			t.Fatalf("StatMany background: %v", r.Err)
		}
		n++
	}
	if n != 3 {
		t.Fatalf("got %d results, want 3", n)
	}
	if g.stats.lastForeground.Load() != 0 {
		t.Fatal("background STATs must not stamp lastForeground")
	}
	if got := g.stats.bgInflight.Load(); got != 0 {
		t.Fatalf("bgInflight after sweep = %d, want 0", got)
	}
}

func TestBackgroundDispatchUsesBackgroundChannel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	g := &providerGroup{
		ctx:           context.Background(),
		reqCh:         make(chan *Request, 1),
		prioCh:        make(chan *Request, 1),
		hotReqCh:      make(chan *Request),
		hotPrioCh:     make(chan *Request),
		hotIdleBodyCh: make(chan *Request),
		bgCh:          make(chan *Request, 1),
	}
	cl := &Client{ctx: context.Background()}

	go func() {
		_, _, _ = cl.tryGroupTimeout(ctx, g, []byte("BODY <bg@h>\r\n"), nil, nil, laneBackground, 500*time.Millisecond)
	}()

	select {
	case req := <-g.bgCh:
		if req.lane != laneBackground {
			t.Fatalf("request lane = %v, want laneBackground", req.lane)
		}
		req.RespCh <- Response{}
	case <-g.hotIdleBodyCh:
		t.Fatal("background body was steered onto the idle-body channel")
	case <-g.hotPrioCh:
		t.Fatal("background body was dispatched on the priority lane")
	case <-g.hotReqCh:
		t.Fatal("background body was dispatched on the normal lane")
	case <-g.reqCh:
		t.Fatal("background body was dispatched on the cold normal lane")
	case <-time.After(2 * time.Second):
		t.Fatal("background body was never dispatched")
	}
}

func TestProviderBackgroundFloorDefaultsToQuarter(t *testing.T) {
	cases := []struct{ conns, floor, want int32 }{
		{8, 0, 2}, {10, 0, 2}, {3, 0, 1}, {1, 0, 1}, {40, 5, 5}, {8, 100, 7},
	}
	for _, tc := range cases {
		if got := resolveBackgroundFloor(int(tc.conns), int(tc.floor)); got != tc.want {
			t.Errorf("conns=%d floor=%d: got %d, want %d", tc.conns, tc.floor, got, tc.want)
		}
	}
}

// A background request is expected to wait: it must not expire at dispatch
// after the attempt window the way a foreground request does. Only the
// caller's context bounds how long it may queue.
func TestBackgroundDispatchWaitsPastAttemptWindow(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	g := &providerGroup{
		ctx:      context.Background(),
		reqCh:    make(chan *Request),
		prioCh:   make(chan *Request),
		hotReqCh: make(chan *Request),
		bgCh:     make(chan *Request), // unbuffered: nobody is reading yet
	}
	cl := &Client{ctx: context.Background()}

	go func() {
		time.Sleep(400 * time.Millisecond) // four attempt windows with no reader
		req := <-g.bgCh
		req.RespCh <- Response{StatusCode: 223}
	}()

	resp, ok, done := cl.tryGroupTimeout(ctx, g, []byte("STAT <bg@h>\r\n"), nil, nil, laneBackground, 100*time.Millisecond)
	if done || !ok || resp.Err != nil {
		t.Fatalf("resp.Err=%v ok=%v done=%v: background dispatch must wait, not expire", resp.Err, ok, done)
	}
	if resp.StatusCode != 223 {
		t.Fatalf("status = %d, want 223", resp.StatusCode)
	}
}

// holdingServer answers BODY after holding it for a fixed delay.
type holdingServer struct{ hold time.Duration }

func (s holdingServer) factory() ConnFactory {
	return func(ctx context.Context) (net.Conn, error) {
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
				for _, cmd := range strings.Split(strings.TrimRight(string(buf[:n]), "\r\n"), "\r\n") {
					switch {
					case strings.HasPrefix(cmd, "DATE"):
						_, _ = server.Write([]byte("111 20240101000000\r\n"))
					case strings.HasPrefix(cmd, "BODY "):
						time.Sleep(s.hold)
						_, _ = server.Write(yencSinglePart([]byte("payload"), "f.bin"))
					default:
						_, _ = server.Write([]byte("500 unsupported\r\n"))
					}
				}
			}
		}()
		return client, nil
	}
}

// A writer that parks with the background lane disarmed (gate closed at the
// floor) must re-arm it once background in-flight drops, or queued background
// work waits for an unrelated wake-up. Inflight 2 lets the writer park while
// its background body is still in flight, which is when the stall bites.
func TestBackgroundLaneRearmsAfterInflightDrops(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{{
		Factory:         holdingServer{hold: 150 * time.Millisecond}.factory(),
		Connections:     1,
		MinConnections:  1,
		Inflight:        2,
		BackgroundFloor: 1,
		SkipPing:        true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := c.Body(ctx, "fg@h"); err != nil { // stamps recent foreground: gate closes at the floor
		t.Fatal(err)
	}

	errs := make(chan error, 3)
	for i := range 3 {
		go func() {
			_, err := c.BodyBackground(ctx, fmt.Sprintf("bg%d@h", i))
			errs <- err
		}()
	}
	deadline := time.After(3 * time.Second)
	for range 3 {
		select {
		case err := <-errs:
			if err != nil {
				t.Fatalf("background body: %v", err)
			}
		case <-deadline:
			t.Fatal("background bodies stalled: the parked writer never re-armed the background lane")
		}
	}
}

// Background work may only land on a connection whose pipeline is empty:
// behind a foreground body it would inherit that body's latency, and — the
// other way round — a foreground request landing behind pipelined background
// STATs on a missing-article-slow provider waits seconds per STAT.
func TestBackgroundLaneRequiresIdlePipeline(t *testing.T) {
	bg := make(chan *Request, 1)
	c := &NNTPConnection{bgCh: bg, inflightSem: make(chan struct{}, 4), bodySem: make(chan struct{}, 2)}

	c.inflightSem <- struct{}{} // the writer's own slot, held while it chooses
	if c.bgLane() == nil {
		t.Fatal("idle pipeline: background lane must be open")
	}
	c.inflightSem <- struct{}{} // one reply still owed
	if c.bgLane() != nil {
		t.Fatal("a reply is pending: background lane must be closed")
	}
	<-c.inflightSem
	c.bodySem <- struct{}{} // a body in flight
	if c.bgLane() != nil {
		t.Fatal("a body is in flight: background lane must be closed")
	}
}

// While a connection has background requests pending it reads only the
// background lane, so foreground work never queues behind them.
func TestForegroundLanesClosedWhileBackgroundPending(t *testing.T) {
	prio := make(chan *Request, 1)
	req := make(chan *Request, 1)
	bg := make(chan *Request, 1)
	prio <- &Request{Payload: []byte("BODY <p>\r\n"), lane: lanePriority}
	req <- &Request{Payload: []byte("BODY <n>\r\n")}

	c := &NNTPConnection{prioCh: prio, reqCh: req, bgCh: bg, inflightSem: make(chan struct{}, 4), bodySem: make(chan struct{}, 2)}
	c.inflightSem <- struct{}{}
	c.bgPending.Store(1)

	if _, _, got := c.tryNextRequest(); got {
		t.Fatal("foreground request served by a connection with background pending")
	}
	bgReq := &Request{Payload: []byte("STAT <b>\r\n"), lane: laneBackground}
	bg <- bgReq
	c.inflightSem <- struct{}{} // pending background reply: pipeline not idle, but more background may pipeline behind it
	if r, _, got := c.tryNextRequest(); !got || r != bgReq {
		t.Fatal("background request must still be served by a background-busy connection")
	}

	c.bgPending.Store(0)
	<-c.inflightSem
	if r, _, got := c.tryNextRequest(); !got || r.lane != lanePriority {
		t.Fatal("with no background pending the priority request must be served")
	}
}

// End to end: a connection draining a background body must not be handed a
// priority body while the other connection is free.
func TestPriorityBodyAvoidsBackgroundBusyConnection(t *testing.T) {
	srv := &bodySteeringServer{
		servedBy: map[string]int{},
		slowID:   "slowbg@h",
		release:  make(chan struct{}),
		started:  make(chan struct{}),
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:        srv.factory(t),
		Connections:    2,
		MinConnections: 2,
		Inflight:       2,
		SkipPing:       true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	deadline := time.Now().Add(10 * time.Second)
	for {
		srv.mu.Lock()
		n := srv.conns
		srv.mu.Unlock()
		if n >= 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("connections never finished pre-warm dialing")
		}
		time.Sleep(10 * time.Millisecond)
	}
	for _, id := range []string{"warm0@h", "warm1@h", "warm2@h", "warm3@h"} {
		if _, err := c.Body(ctx, id); err != nil {
			t.Fatalf("warm-up body %q: %v", id, err)
		}
	}

	slowDone := make(chan struct{})
	go func() {
		defer close(slowDone)
		_, _ = c.BodyBackground(ctx, "slowbg@h")
	}()
	select {
	case <-srv.started:
	case <-time.After(10 * time.Second):
		t.Fatal("background body never reached the server")
	}

	for i := range 6 {
		id := fmt.Sprintf("fast%d@h", i)
		if _, err := c.BodyPriority(ctx, id); err != nil {
			t.Fatalf("priority body: %v", err)
		}
		srv.mu.Lock()
		slowConn, fastConn := srv.servedBy["slowbg@h"], srv.servedBy[id]
		srv.mu.Unlock()
		if slowConn == fastConn {
			t.Fatalf("priority body %s landed on connection %d, which is draining a background body", id, fastConn)
		}
	}

	close(srv.release)
	<-slowDone
}
