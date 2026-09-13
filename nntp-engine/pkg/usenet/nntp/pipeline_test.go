package nntp

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
)

// pipelineTestServer answers BODY per message ID from a script. It reads
// commands line by line — a pipelining client sends several before the first
// reply, and a mock that reads by fixed buffer would silently swallow all but
// the first of them.
type pipelineTestServer struct {
	ln net.Listener
	// reply maps a message ID to the raw bytes sent for its BODY, so a test can
	// script a 430 or a truncated body per article. An empty string means the
	// command is read but never answered, which is what the tail of a batch
	// looks like on a connection that dies with replies still owed.
	reply map[string]string
	// drop is closed by dropConnections to hang up on the client. The hangup is
	// driven by the test rather than by a served-command count so that it cannot
	// race the client's writes: a server that closes while the client is still
	// issuing turns a mid-batch disconnect into a write error on an unrelated
	// command, or into an RST that discards a reply the client had not read yet.
	drop chan struct{}

	mu       sync.Mutex
	received []string
	wg       sync.WaitGroup
}

const pipelineTestBody = "222 0 %s\r\n=ybegin size=4 line=128 name=t.bin\r\nbody\r\n=yend size=4\r\n.\r\n"

func startPipelineServer(t *testing.T, s *pipelineTestServer) *pipelineTestServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	s.ln = ln
	s.drop = make(chan struct{})
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

// dropConnections hangs up on every connection the server is holding, standing
// in for a peer that dies mid-batch. Call it only once per server.
func (s *pipelineTestServer) dropConnections() { close(s.drop) }

func (s *pipelineTestServer) handle(conn net.Conn) {
	defer conn.Close()

	// The hangup runs from its own goroutine because the command loop below is
	// parked in a read at the moment a test decides the connection should die.
	served := make(chan struct{})
	defer close(served)
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		select {
		case <-s.drop:
			_ = conn.Close()
		case <-served:
		}
	}()

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
		switch strings.ToUpper(fields[0]) {
		case "GROUP":
			_, _ = conn.Write([]byte("211 1 1 1 alt.test\r\n"))
		case "QUIT":
			_, _ = conn.Write([]byte("205 bye\r\n"))
			return
		case "BODY":
			id := ""
			if len(fields) > 1 {
				id = strings.Trim(fields[1], "<>")
			}
			s.mu.Lock()
			s.received = append(s.received, id)
			s.mu.Unlock()

			out, ok := s.reply[id]
			if !ok {
				out = fmt.Sprintf(pipelineTestBody, id)
			}
			if out == "" {
				continue
			}
			if _, err := conn.Write([]byte(out)); err != nil {
				return
			}
		default:
			_, _ = conn.Write([]byte("500 unknown\r\n"))
		}
	}
}

func (s *pipelineTestServer) client(t *testing.T) *Client {
	t.Helper()
	addr := s.ln.Addr().(*net.TCPAddr)
	c, err := NewClient(addr.IP.String(), addr.Port, false)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = c.Quit() })
	return c
}

func (s *pipelineTestServer) commands() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.received...)
}

// drainPipelined reads one reply and returns its body, failing the test on any
// transport error.
func drainPipelined(t *testing.T, p *BodyPipeline) PipelinedBody {
	t.Helper()
	reply, err := p.Next()
	if err != nil {
		t.Fatalf("next: %v", err)
	}
	if reply.Err == nil {
		if _, err := io.Copy(io.Discard, reply.Body); err != nil {
			t.Fatalf("body read: %v", err)
		}
		_ = reply.Body.Close()
	}
	return reply
}

func TestBodyPipelineDeliversRepliesInIssueOrder(t *testing.T) {
	s := startPipelineServer(t, &pipelineTestServer{})
	c := s.client(t)

	p := c.NewBodyPipeline(3)
	want := []string{"a@x", "b@x", "c@x"}
	for _, id := range want {
		if err := p.Issue(id); err != nil {
			t.Fatalf("issue %s: %v", id, err)
		}
	}
	// All three commands go out before any reply is read; that is the point.
	if p.Len() != 3 {
		t.Fatalf("outstanding = %d, want 3", p.Len())
	}

	for _, id := range want {
		reply := drainPipelined(t, p)
		if reply.Err != nil {
			t.Fatalf("article %s: %v", id, reply.Err)
		}
		if reply.MessageID != id {
			t.Fatalf("reply out of order: got %s, want %s", reply.MessageID, id)
		}
	}
	if p.Len() != 0 {
		t.Fatalf("outstanding = %d after draining, want 0", p.Len())
	}
	if p.Broken() {
		t.Fatal("pipeline broken after a clean run")
	}
	if got := s.commands(); len(got) != 3 {
		t.Fatalf("server saw %v, want 3 BODY commands", got)
	}
}

// A 430 is a single-line reply, so the articles queued behind it are still
// lined up. Losing the rest of a batch to one missing article would make
// pipelining worse than the per-segment path on any release with a hole.
func TestBodyPipelineSurvivesMissingArticle(t *testing.T) {
	s := startPipelineServer(t, &pipelineTestServer{
		reply: map[string]string{"b@x": "430 No Such Article\r\n"},
	})
	c := s.client(t)

	p := c.NewBodyPipeline(3)
	for _, id := range []string{"a@x", "b@x", "c@x"} {
		if err := p.Issue(id); err != nil {
			t.Fatalf("issue %s: %v", id, err)
		}
	}

	first := drainPipelined(t, p)
	if first.Err != nil {
		t.Fatalf("first article: %v", first.Err)
	}

	missing := drainPipelined(t, p)
	if missing.Err == nil {
		t.Fatal("missing article reported success")
	}
	if !IsArticleNotFound(missing.Err) {
		t.Fatalf("missing article error = %v, want 430", missing.Err)
	}
	if p.Broken() {
		t.Fatal("pipeline broken by a 430")
	}

	last := drainPipelined(t, p)
	if last.Err != nil {
		t.Fatalf("article after the 430: %v", last.Err)
	}
	if last.MessageID != "c@x" {
		t.Fatalf("reply after the 430 = %s, want c@x", last.MessageID)
	}
}

// Abandoning a body mid-read leaves article bytes on the socket that would be
// parsed as the next reply's status line, so the pipeline has to declare itself
// broken and force the connection to be discarded rather than pooled.
func TestBodyPipelineBreaksOnUndrainedBody(t *testing.T) {
	big := "222 0 a@x\r\n" + strings.Repeat("padding line to outrun the read buffer\r\n", 500) + ".\r\n"
	s := startPipelineServer(t, &pipelineTestServer{reply: map[string]string{"a@x": big}})
	c := s.client(t)

	p := c.NewBodyPipeline(2)
	for _, id := range []string{"a@x", "b@x"} {
		if err := p.Issue(id); err != nil {
			t.Fatalf("issue %s: %v", id, err)
		}
	}

	reply, err := p.Next()
	if err != nil {
		t.Fatalf("next: %v", err)
	}
	// Read one buffer's worth and give up, the way a decode error does.
	buf := make([]byte, 16)
	if _, err := reply.Body.Read(buf); err != nil {
		t.Fatalf("body read: %v", err)
	}
	_ = reply.Body.Close()

	if !p.Broken() {
		t.Fatal("pipeline not broken after an undrained body")
	}
	if _, err := p.Next(); !errors.Is(err, ErrPipelineBroken) {
		t.Fatalf("next after break = %v, want ErrPipelineBroken", err)
	}
	if err := p.Issue("c@x"); !errors.Is(err, ErrPipelineBroken) {
		t.Fatalf("issue after break = %v, want ErrPipelineBroken", err)
	}
}

// A connection that dies mid-batch takes every queued reply with it. The
// pipeline must report that as a transport error rather than as a per-article
// failure, so the caller discards the connection instead of concluding the
// remaining articles are missing.
func TestBodyPipelineReportsConnectionLoss(t *testing.T) {
	// b and c are read but never answered, so the batch is genuinely mid-flight
	// when the connection goes; the hangup itself waits for the test, which
	// keeps the disconnect from landing on an Issue still on the wire.
	s := startPipelineServer(t, &pipelineTestServer{
		reply: map[string]string{"b@x": "", "c@x": ""},
	})
	c := s.client(t)

	p := c.NewBodyPipeline(3)
	for _, id := range []string{"a@x", "b@x", "c@x"} {
		if err := p.Issue(id); err != nil {
			t.Fatalf("issue %s: %v", id, err)
		}
	}

	first := drainPipelined(t, p)
	if first.Err != nil {
		t.Fatalf("first article: %v", first.Err)
	}

	s.dropConnections()

	reply, err := p.Next()
	if err == nil {
		t.Fatalf("next after disconnect succeeded with %+v", reply)
	}
	if IsArticleNotFound(err) {
		t.Fatal("connection loss reported as a missing article")
	}
	if !p.Broken() {
		t.Fatal("pipeline not broken after connection loss")
	}
}

func TestBodyPipelineRefusesOverfill(t *testing.T) {
	s := startPipelineServer(t, &pipelineTestServer{})
	c := s.client(t)

	p := c.NewBodyPipeline(2)
	for _, id := range []string{"a@x", "b@x"} {
		if err := p.Issue(id); err != nil {
			t.Fatalf("issue %s: %v", id, err)
		}
	}
	if err := p.Issue("c@x"); err == nil {
		t.Fatal("issue past depth succeeded")
	}
	if p.Broken() {
		t.Fatal("a refused issue must not break the pipeline")
	}
	if p.Len() != 2 {
		t.Fatalf("outstanding = %d, want 2", p.Len())
	}
}

// Depth below 2 is not a pipeline; it is what Client.Body already does.
func TestNewBodyPipelineRaisesDegenerateDepth(t *testing.T) {
	s := startPipelineServer(t, &pipelineTestServer{})
	c := s.client(t)

	for _, depth := range []int{-1, 0, 1} {
		if got := c.NewBodyPipeline(depth).Depth(); got != 2 {
			t.Fatalf("NewBodyPipeline(%d).Depth() = %d, want 2", depth, got)
		}
	}
}

func TestBodyPipelineNextOnEmptyReturnsEOF(t *testing.T) {
	s := startPipelineServer(t, &pipelineTestServer{})
	c := s.client(t)

	if _, err := c.NewBodyPipeline(2).Next(); !errors.Is(err, io.EOF) {
		t.Fatalf("next on empty pipeline = %v, want io.EOF", err)
	}
}
