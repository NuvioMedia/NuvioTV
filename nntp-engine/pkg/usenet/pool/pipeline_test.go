package pool

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
)

func TestPipelineDepthOrDefault(t *testing.T) {
	cases := []struct {
		in, want int
		why      string
	}{
		{in: 0, want: DefaultPipelineDepth, why: "unset takes the default"},
		{in: 1, want: 0, why: "one outstanding command is not a pipeline"},
		{in: -1, want: 0, why: "negative disables"},
		{in: 2, want: 2, why: "in range"},
		{in: MaxPipelineDepth, want: MaxPipelineDepth, why: "at the cap"},
		{in: MaxPipelineDepth + 5, want: MaxPipelineDepth, why: "clamped to the cap"},
	}
	for _, tc := range cases {
		if got := pipelineDepthOrDefault(tc.in); got != tc.want {
			t.Errorf("pipelineDepthOrDefault(%d) = %d, want %d (%s)", tc.in, got, tc.want, tc.why)
		}
	}
}

// pipelinePoolServer answers BODY line by line so several commands may be in
// flight at once, and can be scripted to answer a given message ID with a 430.
type pipelinePoolServer struct {
	ln      net.Listener
	missing map[string]bool

	mu     sync.Mutex
	bodies int
	wg     sync.WaitGroup
}

func startPipelinePoolServer(t *testing.T, missing map[string]bool) *pipelinePoolServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	s := &pipelinePoolServer{ln: ln, missing: missing}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			s.wg.Add(1)
			go func() {
				defer s.wg.Done()
				s.handle(conn)
			}()
		}
	}()
	t.Cleanup(func() {
		_ = ln.Close()
		s.wg.Wait()
	})
	return s
}

func (s *pipelinePoolServer) handle(conn net.Conn) {
	defer conn.Close()
	_, _ = conn.Write([]byte("200 ready\r\n"))
	r := bufio.NewReader(conn)
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		fields := strings.Fields(strings.TrimRight(line, "\r\n"))
		if len(fields) == 0 {
			continue
		}
		var out string
		switch strings.ToUpper(fields[0]) {
		case "AUTHINFO":
			if strings.Contains(strings.ToUpper(line), "USER") {
				out = "381 password\r\n"
			} else {
				out = "281 ok\r\n"
			}
		case "GROUP":
			out = "211 1 1 1 alt.test\r\n"
		case "DATE":
			out = "111 20260101000000\r\n"
		case "QUIT":
			_, _ = conn.Write([]byte("205 bye\r\n"))
			return
		case "BODY":
			id := ""
			if len(fields) > 1 {
				id = strings.Trim(fields[1], "<>")
			}
			s.mu.Lock()
			s.bodies++
			s.mu.Unlock()
			if s.missing[id] {
				out = "430 No Such Article\r\n"
			} else {
				// A minimal yEnc frame whose payload is the message ID's first
				// byte, so a test can tell the articles apart.
				out = fmt.Sprintf("222 0 <%s>\r\n=ybegin size=1 line=128 name=%s.bin\r\n%c\r\n=yend size=1\r\n.\r\n",
					id, id, articleWireByte(id))
			}
		default:
			out = "500 unknown\r\n"
		}
		if _, err := conn.Write([]byte(out)); err != nil {
			return
		}
	}
}

// articleWireByte is the single encoded payload byte a test article carries on
// the wire, derived from its message ID so a reply paired with the wrong
// command is visible. yEnc offsets every data byte by 42, so what the decoder
// hands back is articleDecodedByte, not this.
func articleWireByte(messageID string) byte {
	return 'A' + byte(len(messageID)%26)
}

func articleDecodedByte(messageID string) byte {
	return articleWireByte(messageID) - 42
}

func (s *pipelinePoolServer) bodyCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.bodies
}

func newPipelineTestPool(t *testing.T, s *pipelinePoolServer, depth int) *Pool {
	t.Helper()
	addr := s.ln.Addr().(*net.TCPAddr)
	cp := nntp.NewClientPool(addr.IP.String(), addr.Port, false, "u", "p", 4)
	t.Cleanup(cp.Shutdown)

	p, err := NewPool(&Config{
		Providers:     []ProviderConfig{{ID: "test", Priority: 0, ClientPool: cp}},
		SegmentCache:  NewMemorySegmentCacheWithCapacity(8),
		PipelineDepth: depth,
	})
	if err != nil {
		t.Fatalf("new pool: %v", err)
	}
	return p
}

func segmentsFor(ids ...string) []*nzb.Segment {
	segs := make([]*nzb.Segment, len(ids))
	for i, id := range ids {
		segs[i] = &nzb.Segment{ID: id, Number: i + 1}
	}
	return segs
}

func TestFetchSegmentsPipelinedReturnsAllInOrder(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 3)

	segs := segmentsFor("a@x", "bb@x", "ccc@x")
	results := p.FetchSegmentsPipelined(context.Background(), segs, []string{"alt.test"})

	if len(results) != len(segs) {
		t.Fatalf("results = %d, want %d", len(results), len(segs))
	}
	for i, r := range results {
		if !r.OK {
			t.Fatalf("segment %d (%s) not fetched", i, segs[i].ID)
		}
		if len(r.Data.Body) != 1 {
			t.Fatalf("segment %d body = %q, want one byte", i, r.Data.Body)
		}
		want := articleDecodedByte(segs[i].ID)
		if r.Data.Body[0] != want {
			t.Fatalf("segment %d body = %q, want %q — replies came back out of order",
				i, r.Data.Body[0], want)
		}
	}
}

// The whole point of the batch is that it uses one connection for several
// articles. If it took a lease per article there would be nothing to gain.
func TestFetchSegmentsPipelinedUsesOneConnection(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 3)

	segs := segmentsFor("a@x", "b@x", "c@x")
	p.FetchSegmentsPipelined(context.Background(), segs, []string{"alt.test"})

	if got := s.bodyCount(); got != 3 {
		t.Fatalf("server saw %d BODY commands, want 3", got)
	}
	if got := p.providers[0].ClientPool.TotalConnections(); got != 1 {
		t.Fatalf("connections opened = %d, want 1", got)
	}
}

// A 430 belongs to the fallback path, which is the only one that asks every
// provider. The batch must hand that segment back unfetched — and keep going,
// because the articles behind it are still queued on a healthy connection.
func TestFetchSegmentsPipelinedLeavesMissingToFallback(t *testing.T) {
	s := startPipelinePoolServer(t, map[string]bool{"b@x": true})
	p := newPipelineTestPool(t, s, 3)

	segs := segmentsFor("a@x", "b@x", "c@x")
	results := p.FetchSegmentsPipelined(context.Background(), segs, []string{"alt.test"})

	if !results[0].OK || !results[2].OK {
		t.Fatalf("articles around the missing one were dropped: %+v", results)
	}
	if results[1].OK {
		t.Fatal("missing article reported as fetched")
	}
	// Nothing was recorded against the provider, and nothing was marked
	// permanently missing: one provider's 430 proves nothing on its own.
	if p.isKnownMissing("b@x") {
		t.Fatal("a single provider's 430 marked the article permanently missing")
	}
}

func TestFetchSegmentsPipelinedServesCacheWithoutConnecting(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 3)

	cached := SegmentData{Body: []byte("cached"), Size: 6}
	p.cache.Set("a@x", cached)
	p.cache.Set("b@x", cached)

	results := p.FetchSegmentsPipelined(context.Background(), segmentsFor("a@x", "b@x"), []string{"alt.test"})
	for i, r := range results {
		if !r.OK || string(r.Data.Body) != "cached" {
			t.Fatalf("segment %d = %+v, want the cached body", i, r)
		}
	}
	if got := s.bodyCount(); got != 0 {
		t.Fatalf("server saw %d BODY commands for an all-cached batch, want 0", got)
	}
}

// A single article has nothing to pipeline, so the batch declines it rather
// than spending a lease the ordinary path would spend anyway.
func TestFetchSegmentsPipelinedDeclinesSingleSegment(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 3)

	results := p.FetchSegmentsPipelined(context.Background(), segmentsFor("a@x"), []string{"alt.test"})
	if results[0].OK {
		t.Fatal("single-segment batch was fetched by the pipeline")
	}
	if got := s.bodyCount(); got != 0 {
		t.Fatalf("server saw %d BODY commands, want 0", got)
	}
}

func TestFetchSegmentsPipelinedDisabledDepthDoesNothing(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 1) // 1 == off

	if got := p.PipelineDepth(); got != 0 {
		t.Fatalf("PipelineDepth() = %d, want 0", got)
	}
	results := p.FetchSegmentsPipelined(context.Background(), segmentsFor("a@x", "b@x"), []string{"alt.test"})
	for i, r := range results {
		if r.OK {
			t.Fatalf("segment %d fetched with pipelining off", i)
		}
	}
	if got := s.bodyCount(); got != 0 {
		t.Fatalf("server saw %d BODY commands with pipelining off, want 0", got)
	}
}

func TestFetchConcurrencyCountsProviderConnections(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 3)

	if got := p.FetchConcurrency(); got != 4 {
		t.Fatalf("FetchConcurrency() = %d, want 4", got)
	}
	// A per-stream cap is the ceiling read-ahead has to plan against, not the
	// provider's full allowance.
	capped := p.SubsetForLease("stream", []string{"test"}, map[string]int{"test": 2})
	if got := capped.FetchConcurrency(); got != 2 {
		t.Fatalf("capped FetchConcurrency() = %d, want 2", got)
	}
}

// newMultiProviderPool builds a pool whose providers point at the same server
// but carry different per-provider depths.
func newMultiProviderPool(t *testing.T, s *pipelinePoolServer, poolDefault int, depths ...int) *Pool {
	t.Helper()
	addr := s.ln.Addr().(*net.TCPAddr)
	providers := make([]ProviderConfig, len(depths))
	for i, d := range depths {
		cp := nntp.NewClientPool(addr.IP.String(), addr.Port, false, "u", "p", 4)
		t.Cleanup(cp.Shutdown)
		providers[i] = ProviderConfig{
			ID:            fmt.Sprintf("p%d", i),
			Priority:      i,
			ClientPool:    cp,
			PipelineDepth: d,
		}
	}
	p, err := NewPool(&Config{
		Providers:     providers,
		SegmentCache:  NewMemorySegmentCacheWithCapacity(8),
		PipelineDepth: poolDefault,
	})
	if err != nil {
		t.Fatalf("new pool: %v", err)
	}
	return p
}

func TestPipelineDepthPerProvider(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	// Deployment default 3; first provider inherits it, second pins 5, third
	// switches pipelining off for itself alone.
	p := newMultiProviderPool(t, s, 3, 0, 5, 1)

	if got := p.pipelineDepthFor("p0"); got != 3 {
		t.Fatalf("unset provider depth = %d, want the pool default 3", got)
	}
	if got := p.pipelineDepthFor("p1"); got != 5 {
		t.Fatalf("pinned provider depth = %d, want 5", got)
	}
	if got := p.pipelineDepthFor("p2"); got != 0 {
		t.Fatalf("provider with pipelining off = %d, want 0", got)
	}
	// Read-ahead sizes a batch before a provider is chosen, so the pool reports
	// the deepest one available.
	if got := p.PipelineDepth(); got != 5 {
		t.Fatalf("PipelineDepth() = %d, want the deepest provider's 5", got)
	}
}

// A provider is allowed to opt out on its own while the rest of the pool keeps
// pipelining, which is the whole point of moving the knob per provider.
func TestPipelineDepthProviderOptOutIsHonoured(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newMultiProviderPool(t, s, 3, 1)

	if got := p.PipelineDepth(); got != 0 {
		t.Fatalf("PipelineDepth() = %d with the only provider opted out, want 0", got)
	}
	results := p.FetchSegmentsPipelined(context.Background(), segmentsFor("a@x", "b@x"), []string{"alt.test"})
	for i, r := range results {
		if r.OK {
			t.Fatalf("segment %d fetched against a provider that opted out", i)
		}
	}
	if got := s.bodyCount(); got != 0 {
		t.Fatalf("server saw %d BODY commands from an opted-out provider, want 0", got)
	}
}

// An unknown provider ID — a subset rebuilt around a provider this view no
// longer holds — falls back to the pool default rather than to zero, which
// would silently switch pipelining off.
func TestPipelineDepthUnknownProviderTakesPoolDefault(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newMultiProviderPool(t, s, 4, 0)

	if got := p.pipelineDepthFor("nobody"); got != 4 {
		t.Fatalf("unknown provider depth = %d, want the pool default 4", got)
	}
}
