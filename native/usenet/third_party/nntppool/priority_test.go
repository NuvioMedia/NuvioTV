package nntppool

import (
	"context"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// newLaneTestConn builds a bare NNTPConnection wired only with request
// channels. Nothing else is touched, because tryNextRequest reads nothing else.
func newLaneTestConn(hotPrio, prio, hotReq, req chan *Request) *NNTPConnection {
	return &NNTPConnection{
		hotPrioCh: hotPrio,
		prioCh:    prio,
		hotReqCh:  hotReq,
		reqCh:     req,
	}
}

func TestTryNextRequest_PriorityBeatsNormal(t *testing.T) {
	// The bias is a coin flip today, so a single trial proves nothing.
	// 100 trials make a pre-fix pass astronomically unlikely.
	for i := range 100 {
		prio := make(chan *Request, 1)
		req := make(chan *Request, 1)
		prioReq := &Request{Payload: []byte("BODY <prio>\r\n")}
		normReq := &Request{Payload: []byte("BODY <norm>\r\n")}
		prio <- prioReq
		req <- normReq

		c := newLaneTestConn(nil, prio, nil, req)
		got, ok, found := c.tryNextRequest()
		if !found || !ok {
			t.Fatalf("iteration %d: found=%v ok=%v, want both true", i, found, ok)
		}
		if got != prioReq {
			t.Fatalf("iteration %d: got the normal-lane request, want the priority one", i)
		}
	}
}

func TestTryNextRequest_ColdPriorityBeatsHotNormal(t *testing.T) {
	// A priority request bound for a cold connection must outrank a normal
	// request bound for a hot one. Today hotReqCh is probed first.
	prio := make(chan *Request, 1)
	hotReq := make(chan *Request, 1)
	prioReq := &Request{Payload: []byte("BODY <prio>\r\n")}
	hotReq <- &Request{Payload: []byte("BODY <hotnorm>\r\n")}
	prio <- prioReq

	c := newLaneTestConn(nil, prio, hotReq, nil)
	got, ok, found := c.tryNextRequest()
	if !found || !ok {
		t.Fatalf("found=%v ok=%v, want both true", found, ok)
	}
	if got != prioReq {
		t.Fatal("hot normal request outranked a priority request")
	}
}

func TestTryNextRequest_HotPriorityFirst(t *testing.T) {
	hotPrio := make(chan *Request, 1)
	prio := make(chan *Request, 1)
	hotPrioReq := &Request{Payload: []byte("BODY <hotprio>\r\n")}
	hotPrio <- hotPrioReq
	prio <- &Request{Payload: []byte("BODY <coldprio>\r\n")}

	c := newLaneTestConn(hotPrio, prio, nil, nil)
	got, _, found := c.tryNextRequest()
	if !found || got != hotPrioReq {
		t.Fatal("hot priority channel must be probed before the cold one")
	}
}

func TestTryNextRequest_EmptyReturnsNotGot(t *testing.T) {
	c := newLaneTestConn(nil, nil, nil, nil)
	if _, _, found := c.tryNextRequest(); found {
		t.Fatal("all-nil channels must report got=false")
	}
}

// bodySteeringServer answers BODY with a small yEnc article and records which
// connection index served each message-id. The id in slowID blocks until
// release is closed, holding that connection's reader busy.
type bodySteeringServer struct {
	mu       sync.Mutex
	servedBy map[string]int // message-id -> connection index
	conns    int
	slowID   string
	release  chan struct{}
	started  chan struct{} // closed once slowID has reached the server
}

func (s *bodySteeringServer) factory(t *testing.T) ConnFactory {
	t.Helper()
	return func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		s.mu.Lock()
		idx := s.conns
		s.conns++
		s.mu.Unlock()

		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := strings.TrimRight(string(buf[:n]), "\r\n")
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240101000000\r\n"))
					continue
				}
				if !strings.HasPrefix(cmd, "BODY ") {
					_, _ = server.Write([]byte("500 unsupported\r\n"))
					continue
				}
				id := strings.Trim(strings.TrimPrefix(cmd, "BODY "), "<>")
				s.mu.Lock()
				s.servedBy[id] = idx
				s.mu.Unlock()

				if id == s.slowID {
					close(s.started) // the connection is now genuinely busy
					<-s.release      // hold its reader until the test releases it
				}
				_, _ = server.Write(yencSinglePart([]byte("payload"), "f.bin"))
			}
		}()
		return client, nil
	}
}

// TestPriorityBodyAvoidsBusyConnection is an end-to-end smoke/regression check
// exercising the real dial, dispatch, and steering path through the public
// API: while one connection is draining a body, a priority body should be
// steered to the connection that is free.
//
// Inflight is 2, not 1: at Inflight 1, inflightSem (cap == StatInflight ==
// max(Inflight, StatInflight) == 1) is fully held by the connection draining
// slow@h, so its writer parks at "c.inflightSem <- struct{}{}" — invisible to
// every request lane, hotPrioCh included. With only one connection able to
// receive anything, the test would pass whether or not steering exists,
// because there was never a second candidate to route to. Inflight 2 gives
// the busy connection's writer a spare pipeline slot, so it keeps competing
// on the request lanes while its body is still in flight.
//
// This test alone is NOT a reliable regression pin, and should not be read as
// one: Go hands an unbuffered channel to whichever receiver registered first,
// and the warm-up below always leaves the free connection registered before
// the busy one (the busy one necessarily re-registers after dispatching
// slow@h, strictly later). That means hotPrioCh's naive FIFO hand-off already
// resolves to the free connection on its own, with or without the
// hotIdleBodyCh steering added by this feature — confirmed by running this
// exact test against the pre-feature commit (48f5b63), where it passes
// consistently. See TestPriorityBodySendPrefersIdleReceiver for the
// deterministic pin of the actual dispatch preference; this test remains as
// an integration-level exercise of the real dial/warm-up/dispatch machinery
// (it would still catch a deadlock, panic, or gross behavioral break).
func TestPriorityBodyAvoidsBusyConnection(t *testing.T) {
	srv := &bodySteeringServer{
		servedBy: map[string]int{},
		slowID:   "slow@h",
		release:  make(chan struct{}),
		started:  make(chan struct{}),
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:        srv.factory(t),
		Connections:    2,
		MinConnections: 2, // pre-warm both so neither has to dial mid-test
		Inflight:       2,
		SkipPing:       true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Warm-up barrier: wait for both pre-warmed connections to finish dialing,
	// then settle them with a few ordinary bodies before measuring anything.
	// Without this, pre-warm dialing can still be in flight when the
	// measurement starts, which flakes independent of the fix under test —
	// on both the fixed and the pre-fix code, purely from dial-completion
	// timing.
	dialDeadline := time.Now().Add(10 * time.Second)
	for {
		srv.mu.Lock()
		n := srv.conns
		srv.mu.Unlock()
		if n >= 2 {
			break
		}
		if time.Now().After(dialDeadline) {
			t.Fatal("connections never finished pre-warm dialing")
		}
		time.Sleep(10 * time.Millisecond)
	}
	for _, id := range []string{"warm0@h", "warm1@h", "warm2@h", "warm3@h"} {
		if _, err := c.Body(ctx, id); err != nil {
			t.Fatalf("warm-up body %q: %v", id, err)
		}
	}

	// Occupy one connection with a normal-lane body that will not complete.
	slowDone := make(chan struct{})
	go func() {
		defer close(slowDone)
		_, _ = c.Body(ctx, "slow@h")
	}()

	// Block on a signal rather than polling: the test must not proceed until a
	// connection is genuinely busy, and a sleep would make that a race.
	select {
	case <-srv.started:
	case <-time.After(10 * time.Second):
		t.Fatal("slow body never reached the server")
	}

	if _, err := c.BodyPriority(ctx, "fast@h"); err != nil {
		t.Fatalf("priority body: %v", err)
	}

	srv.mu.Lock()
	slowConn, fastConn := srv.servedBy["slow@h"], srv.servedBy["fast@h"]
	srv.mu.Unlock()

	if slowConn == fastConn {
		t.Fatalf("priority body landed on connection %d, which was already draining a body", fastConn)
	}

	close(srv.release)
	<-slowDone
}

func TestIdleBodyChanNilWhenBusy(t *testing.T) {
	hotIdle := make(chan *Request, 1)
	c := &NNTPConnection{
		hotIdleBodyCh: hotIdle,
		bodySem:       make(chan struct{}, 1),
	}
	if c.idleBodyChan() == nil {
		t.Fatal("a body-free connection must offer its idle-body channel")
	}
	c.bodySem <- struct{}{} // now a body is in flight
	if c.idleBodyChan() != nil {
		t.Fatal("a busy connection must not offer its idle-body channel")
	}
}

// A body-free connection whose pipeline still holds unanswered requests is not
// idle either: replies are FIFO, so a priority body steered here waits behind
// every pending reply. That wait is not "microseconds per STAT" in practice —
// Newshosting answers STAT for a missing article in ~1.2 s and serialises
// them per connection, so a repair liveness sweep over a mostly-dead release
// parks every connection it touches for seconds at a time. Those connections
// are exactly the body-free ones, so the old bodySem-only test steered
// playback bodies straight onto them (observed live: a stream collapsing from
// ~70 MB/s to ~5 MB/s beside such a sweep). The writer holds one inflightSem
// slot of its own while it is choosing a request, so "empty pipeline" is
// len(inflightSem) <= 1.
func TestIdleBodyChanNilWithPendingPipeline(t *testing.T) {
	c := &NNTPConnection{
		hotIdleBodyCh: make(chan *Request, 1),
		bodySem:       make(chan struct{}, 1),
		inflightSem:   make(chan struct{}, 4),
	}
	c.inflightSem <- struct{}{} // the writer's own slot
	if c.idleBodyChan() == nil {
		t.Fatal("a connection whose only inflight slot is the writer's own must offer its idle-body channel")
	}
	c.inflightSem <- struct{}{} // one bodyless request (STAT) awaiting its reply
	if c.idleBodyChan() != nil {
		t.Fatal("a connection with a pending reply must not offer its idle-body channel")
	}
	<-c.inflightSem // reply drained
	if c.idleBodyChan() == nil {
		t.Fatal("a drained pipeline must offer its idle-body channel again")
	}
}

// TestPriorityBodySendPrefersIdleReceiver pins tryGroupTimeout's dispatch
// preference directly and deterministically, with no dependence on real dial
// timing or on Go's channel FIFO hand-off order — see the comment on
// TestPriorityBodyAvoidsBusyConnection for why that black-box path cannot be
// trusted to discriminate this exact property (it structurally cannot: the
// warm-up there always leaves the free connection registered on the request
// lanes before the busy one, so naive FIFO hand-off resolves to the free
// connection whether or not the fix exists).
//
// Here the busy stand-in is deliberately registered on hotPrioCh, and given
// time to actually park, strictly BEFORE the free stand-in — so a naive FIFO
// hand-off on hotPrioCh alone would always resolve to the busy stand-in, the
// wrong outcome. A priority send must still land on hotIdleBodyCh, the
// channel only a body-free connection (idleBodyChan() returning non-nil) ever
// listens on, never on hotPrioCh.
func TestPriorityBodySendPrefersIdleReceiver(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	g := &providerGroup{
		ctx:           context.Background(),
		reqCh:         make(chan *Request, 1),
		prioCh:        make(chan *Request, 1),
		hotReqCh:      make(chan *Request),
		hotPrioCh:     make(chan *Request),
		hotIdleBodyCh: make(chan *Request),
	}
	cl := &Client{ctx: context.Background()}

	type receipt struct {
		via string
		req *Request
	}
	got := make(chan receipt, 2)

	// Stand-in for a busy connection: every real connection, busy or not,
	// always listens on hotPrioCh (idleBodyChan() only ever removes the
	// hotIdleBodyCh case from the real writer's select, never this one).
	go func() {
		select {
		case req := <-g.hotPrioCh:
			got <- receipt{"hotPrioCh (busy stand-in)", req}
		case <-ctx.Done():
		}
	}()
	time.Sleep(50 * time.Millisecond) // let it genuinely park before the free stand-in registers

	// Stand-in for the free connection: listens on both, exactly as the real
	// writer's blocking select does (idleBodyChan() case alongside hotPrioCh).
	go func() {
		select {
		case req := <-g.hotIdleBodyCh:
			got <- receipt{"hotIdleBodyCh (free stand-in)", req}
			req.RespCh <- Response{}
		case req := <-g.hotPrioCh:
			got <- receipt{"hotPrioCh (free stand-in)", req}
			req.RespCh <- Response{}
		case <-ctx.Done():
		}
	}()
	time.Sleep(50 * time.Millisecond) // let it genuinely park before dispatch runs

	go func() {
		_, _, _ = cl.tryGroupTimeout(ctx, g, []byte("BODY <fast@h>\r\n"), nil, nil, lanePriority, 500*time.Millisecond)
	}()

	select {
	case r := <-got:
		if r.via != "hotIdleBodyCh (free stand-in)" {
			t.Fatalf("priority body dispatched via %s, want hotIdleBodyCh (free stand-in)", r.via)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("neither stand-in ever received the priority dispatch")
	}
}
