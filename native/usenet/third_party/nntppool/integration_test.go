package nntppool

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// --- NNTPConnection tests ---

func TestNNTPConnection_Greeting(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))
		// Keep alive until client closes
		buf := make([]byte, 1)
		_, _ = s.Read(buf)
	})

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("newNNTPConnectionFromConn() error = %v", err)
	}
	if nc.Greeting.StatusCode != 200 {
		t.Errorf("Greeting.StatusCode = %d, want 200", nc.Greeting.StatusCode)
	}
}

func TestNNTPConnection_GreetingReject(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("502 service permanently unavailable\r\n"))
	})

	reqCh := make(chan *Request)
	_, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err == nil {
		t.Fatal("expected error for 502 greeting")
	}
	if !errors.Is(err, ErrMaxConnections) {
		t.Errorf("error = %v, want ErrMaxConnections", err)
	}
}

func TestNNTPConnection_Auth(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 1024)
		n, _ := s.Read(buf)
		got := string(buf[:n])
		if got != "AUTHINFO USER testuser\r\n" {
			t.Errorf("expected AUTHINFO USER, got %q", got)
		}
		_, _ = s.Write([]byte("381 password required\r\n"))

		n, _ = s.Read(buf)
		got = string(buf[:n])
		if got != "AUTHINFO PASS testpass\r\n" {
			t.Errorf("expected AUTHINFO PASS, got %q", got)
		}
		_, _ = s.Write([]byte("281 authentication accepted\r\n"))

		// Keep alive
		_, _ = s.Read(buf)
	})

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{
		Username: "testuser",
		Password: "testpass",
	}, "", nil, nil)
	if err != nil {
		t.Fatalf("auth error = %v", err)
	}
	if nc.Greeting.StatusCode != 200 {
		t.Errorf("Greeting.StatusCode = %d", nc.Greeting.StatusCode)
	}
}

func TestNNTPConnection_AuthReject(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 1024)
		_, _ = s.Read(buf) // AUTHINFO USER
		_, _ = s.Write([]byte("381 password required\r\n"))

		_, _ = s.Read(buf) // AUTHINFO PASS
		_, _ = s.Write([]byte("481 authentication rejected\r\n"))
	})

	reqCh := make(chan *Request)
	_, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{
		Username: "testuser",
		Password: "wrongpass",
	}, "", nil, nil)
	if err == nil {
		t.Fatal("expected auth rejection error")
	}
}

func TestNNTPConnection_AuthMaxConnections(t *testing.T) {
	for _, tc := range []struct {
		name string
		code int
	}{
		{name: "temporary", code: 400},
		{name: "permanent", code: 502},
	} {
		t.Run(tc.name, func(t *testing.T) {
			conn := mockServer(t, func(s net.Conn) {
				_, _ = s.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 1024)
				_, _ = s.Read(buf) // AUTHINFO USER
				_, _ = s.Write([]byte("381 password required\r\n"))
				_, _ = s.Read(buf) // AUTHINFO PASS
				_, _ = fmt.Fprintf(s, "%d too many connections\r\n", tc.code)
			})

			_, err := newNNTPConnectionFromConn(context.Background(), conn, 1, make(chan *Request), nil, Auth{
				Username: "testuser",
				Password: "testpass",
			}, "", nil, nil)
			if !errors.Is(err, ErrMaxConnections) {
				t.Fatalf("error = %v, want ErrMaxConnections", err)
			}
		})
	}
}

func TestNNTPConnection_RunSingleRequest(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 1024)
		n, _ := s.Read(buf)
		got := string(buf[:n])
		if got != "STAT <test@example.com>\r\n" {
			t.Errorf("expected STAT command, got %q", got)
		}
		_, _ = s.Write([]byte("223 12345 <test@example.com> article exists\r\n"))
	})

	reqCh := make(chan *Request, 1)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	respCh := make(chan Response, 1)
	reqCh <- &Request{
		Ctx:     context.Background(),
		Payload: []byte("STAT <test@example.com>\r\n"),
		RespCh:  respCh,
	}

	go nc.Run()

	select {
	case resp := <-respCh:
		if resp.Err != nil {
			t.Fatalf("response error = %v", resp.Err)
		}
		if resp.StatusCode != 223 {
			t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for response")
	}

	_ = nc.Close()
}

func TestNNTPConnection_RunBodyRequest(t *testing.T) {
	original := []byte("Hello NNTP world! This is body content for decoding test.")

	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 1024)
		_, _ = s.Read(buf) // BODY command

		_, _ = s.Write(yencSinglePart(original, "test.bin"))
	})

	reqCh := make(chan *Request, 1)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	var decoded bytes.Buffer
	respCh := make(chan Response, 1)
	reqCh <- &Request{
		Ctx:        context.Background(),
		Payload:    []byte("BODY <test@example.com>\r\n"),
		RespCh:     respCh,
		BodyWriter: &decoded,
	}

	go nc.Run()

	select {
	case resp := <-respCh:
		if resp.Err != nil {
			t.Fatalf("response error = %v", resp.Err)
		}
		if resp.StatusCode != 222 {
			t.Errorf("StatusCode = %d, want 222", resp.StatusCode)
		}
		if !bytes.Equal(decoded.Bytes(), original) {
			t.Errorf("decoded = %d bytes, want %d", decoded.Len(), len(original))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for response")
	}

	_ = nc.Close()
}

func TestNNTPConnection_RunPipelined(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 4096)
		// Read all pipelined commands (may arrive in one or multiple reads)
		var allData []byte
		for {
			n, err := s.Read(buf)
			if n > 0 {
				allData = append(allData, buf[:n]...)
			}
			// Check if we've received all 3 STAT commands
			if bytes.Count(allData, []byte("STAT")) >= 3 {
				break
			}
			if err != nil {
				break
			}
		}

		// Respond to all 3 in FIFO order
		for i := range 3 {
			_, _ = fmt.Fprintf(s, "223 %d <msg%d@id> exists\r\n", i+1, i+1)
		}
	})

	reqCh := make(chan *Request, 3)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 3, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	// Send 3 concurrent requests
	var respChs [3]chan Response
	for i := range 3 {
		respChs[i] = make(chan Response, 1)
		reqCh <- &Request{
			Ctx:     context.Background(),
			Payload: fmt.Appendf(nil, "STAT <msg%d@id>\r\n", i+1),
			RespCh:  respChs[i],
		}
	}

	go nc.Run()

	// Verify FIFO ordering
	for i := range 3 {
		select {
		case resp := <-respChs[i]:
			if resp.Err != nil {
				t.Errorf("request %d error = %v", i, resp.Err)
			}
			if resp.StatusCode != 223 {
				t.Errorf("request %d StatusCode = %d, want 223", i, resp.StatusCode)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("timeout on request %d", i)
		}
	}

	_ = nc.Close()
}

func TestNNTPConnection_CancelledRequest(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))
		// Keep alive
		buf := make([]byte, 1024)
		_, _ = s.Read(buf)
	})

	reqCh := make(chan *Request, 1)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	// Already-cancelled context
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	respCh := make(chan Response, 1)
	nc.firstReq = &Request{
		Ctx:     ctx,
		Payload: []byte("STAT <test@id>\r\n"),
		RespCh:  respCh,
	}

	go nc.Run()

	// The RespCh should be closed (no response sent)
	select {
	case <-respCh:
		// Channel closed or response delivered — either is acceptable for cancelled ctx
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: respCh should have been closed")
	}

	_ = nc.Close()
}

func TestNNTPConnection_IdleTimeout(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))
		// Keep alive until client disconnects
		buf := make([]byte, 1024)
		for {
			if _, err := s.Read(buf); err != nil {
				return
			}
		}
	})

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	nc.idleTimeout = 50 * time.Millisecond
	go nc.Run()

	// Should shut down due to idle timeout
	select {
	case <-nc.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: connection should have closed due to idle timeout")
	}
}

// --- Client send/retry tests ---

func TestClient_SendRetryRoundRobin(t *testing.T) {
	// Track which providers receive requests
	var mu sync.Mutex
	hits := make(map[string]int)

	makeFactory := func(name string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))

				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					mu.Lock()
					hits[name]++
					mu.Unlock()
					_ = n
					// Respond with 223 STAT ok
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory("p1"), Connections: 1},
		{Factory: makeFactory("p2"), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Send a few requests
	for range 4 {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
		cancel()
		if resp.Err != nil {
			t.Fatalf("Send() error = %v", resp.Err)
		}
		if resp.StatusCode != 223 {
			t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
		}
	}

	mu.Lock()
	defer mu.Unlock()
	// Both providers should have been hit
	if hits["p1"] == 0 || hits["p2"] == 0 {
		t.Errorf("expected round-robin: p1=%d, p2=%d", hits["p1"], hits["p2"])
	}
}

func TestClient_WeightedRoundRobin(t *testing.T) {
	// Track which providers receive first-attempt requests.
	var mu sync.Mutex
	hits := make(map[string]int)

	makeFactory := func(name string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))

				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					mu.Lock()
					hits[name]++
					mu.Unlock()
					_ = n
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}()
			return client, nil
		}
	}

	// Provider "big" has weight 50, "small" has weight 10 → total 60.
	// With load-aware routing, distribution shifts dynamically based on
	// available capacity, so we check that "big" gets the majority.
	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory("big"), Connections: 50},
		{Factory: makeFactory("small"), Connections: 10},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Reset counters after NewClient — provider ping during init adds a hit.
	mu.Lock()
	clear(hits)
	mu.Unlock()

	const N = 60
	for range N {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
		cancel()
		if resp.Err != nil {
			t.Fatalf("Send() error = %v", resp.Err)
		}
		if resp.StatusCode != 223 {
			t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
		}
	}

	mu.Lock()
	defer mu.Unlock()
	// With load-aware routing, "big" should get significantly more
	// requests than "small". Allow 20% tolerance from the ideal 50/10 split
	// because available capacity shifts as slots are held during requests.
	bigPct := float64(hits["big"]) / float64(N) * 100
	if bigPct < 60 {
		t.Errorf("big hits = %d (%.1f%%), want at least 60%% of %d", hits["big"], bigPct, N)
	}
	if hits["big"]+hits["small"] != N {
		t.Errorf("total hits = %d, want %d", hits["big"]+hits["small"], N)
	}
}

func TestClient_SendRetryFallbackBackup(t *testing.T) {
	makeFactory := func(statusCode int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))

				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = fmt.Fprintf(server, "%d response\r\n", statusCode)
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(430), Connections: 1},               // main: always 430
		{Factory: makeFactory(223), Connections: 1, Backup: true}, // backup: 223
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("Send() error = %v", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223 (from backup)", resp.StatusCode)
	}
}

func TestClient_SendRetryConnectionDiedSameProvider(t *testing.T) {
	// Simulate a stale pooled connection: the first connection the pool opens
	// dies mid-request (server closes without responding), the next one is
	// healthy. With a single provider there is no other provider to fall back
	// to, so the pool must retry on a fresh same-provider connection instead of
	// returning "all providers exhausted: ... connection died".
	var connNum atomic.Int32

	factory := func(ctx context.Context) (net.Conn, error) {
		n := connNum.Add(1)
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			if n == 1 {
				// Stale connection: accept one command, then drop the socket
				// without responding, mimicking a server-closed idle connection.
				_, _ = server.Read(buf)
				_ = server.Close()
				return
			}
			// Healthy connection: answer every STAT with 223.
			for {
				if _, err := server.Read(buf); err != nil {
					return
				}
				_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1, SkipPing: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("Send() error = %v, want recovery on a fresh same-provider connection", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223 after same-provider reconnect", resp.StatusCode)
	}
	if got := connNum.Load(); got < 2 {
		t.Errorf("expected at least 2 connections (stale + fresh), got %d", got)
	}
}

func TestClient_SendRetryAll430(t *testing.T) {
	makeFactory430 := func() ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))

				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = server.Write([]byte("430 no such article\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory430(), Connections: 1},
		{Factory: makeFactory430(), Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.StatusCode != 430 {
		t.Errorf("StatusCode = %d, want 430 (all providers exhausted)", resp.StatusCode)
	}
}

func TestClient_Skip430SameHost(t *testing.T) {
	// Two main providers + one backup, all same host but different auth.
	// First returns 430 → others should be skipped.
	var counts [3]atomic.Int64
	make430Factory := func(idx int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					if bytes.Contains(buf[:n], []byte("AUTHINFO")) {
						_, _ = server.Write([]byte("281 authentication accepted\r\n"))
						continue
					}
					if bytes.Contains(buf[:n], []byte("STAT")) {
						counts[idx].Add(1)
					}
					_, _ = server.Write([]byte("430 no such article\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Host: "news.example.com:563", Auth: Auth{Username: "user1", Password: "pass"}, Factory: make430Factory(0), Connections: 1},
		{Host: "news.example.com:563", Auth: Auth{Username: "user2", Password: "pass"}, Factory: make430Factory(1), Connections: 1},
		{Host: "news.example.com:563", Auth: Auth{Username: "user3", Password: "pass"}, Factory: make430Factory(2), Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.StatusCode != 430 {
		t.Fatalf("StatusCode = %d, want 430", resp.StatusCode)
	}

	total := counts[0].Load() + counts[1].Load() + counts[2].Load()
	if total != 1 {
		t.Errorf("total requests = %d, want 1 (same-host providers should be skipped)", total)
	}
}

func TestClient_Skip430DifferentHosts(t *testing.T) {
	// Two providers on different hosts. Both should be tried.
	var counts [2]atomic.Int64
	make430Factory := func(idx int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					if bytes.Contains(buf[:n], []byte("STAT")) {
						counts[idx].Add(1)
					}
					_, _ = server.Write([]byte("430 no such article\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Host: "news1.example.com:563", Factory: make430Factory(0), Connections: 1},
		{Host: "news2.example.com:563", Factory: make430Factory(1), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.StatusCode != 430 {
		t.Fatalf("StatusCode = %d, want 430", resp.StatusCode)
	}

	if counts[0].Load() != 1 || counts[1].Load() != 1 {
		t.Errorf("requests = [%d, %d], want [1, 1] (different hosts should both be tried)",
			counts[0].Load(), counts[1].Load())
	}
}

func TestClient_Skip430FactoryProviders(t *testing.T) {
	// Factory-based providers (no Host) are never skipped.
	var counts [2]atomic.Int64
	make430Factory := func(idx int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					if bytes.Contains(buf[:n], []byte("STAT")) {
						counts[idx].Add(1)
					}
					_, _ = server.Write([]byte("430 no such article\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: make430Factory(0), Connections: 1},
		{Factory: make430Factory(1), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.StatusCode != 430 {
		t.Fatalf("StatusCode = %d, want 430", resp.StatusCode)
	}

	if counts[0].Load() != 1 || counts[1].Load() != 1 {
		t.Errorf("requests = [%d, %d], want [1, 1] (factory providers should never be skipped)",
			counts[0].Load(), counts[1].Load())
	}
}

// --- FIFO dispatch tests ---

func TestClient_FIFODispatch(t *testing.T) {
	// With FIFO, provider #1 should get all requests when it has capacity.
	var mu sync.Mutex
	hits := make(map[string]int)

	makeFactory := func(name string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					mu.Lock()
					hits[name]++
					mu.Unlock()
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory("p1"), Connections: 20},
		{Factory: makeFactory("p2"), Connections: 20},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Reset counters after NewClient (ping adds hits).
	mu.Lock()
	clear(hits)
	mu.Unlock()

	// Send requests sequentially — p1 should absorb all of them since
	// its available capacity is always > 0 with 20 slots.
	const N = 10
	for range N {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
		cancel()
		if resp.Err != nil {
			t.Fatalf("Send() error = %v", resp.Err)
		}
		if resp.StatusCode != 223 {
			t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
		}
	}

	mu.Lock()
	defer mu.Unlock()
	if hits["p1"] != N {
		t.Errorf("FIFO: p1=%d p2=%d, want p1=%d p2=0", hits["p1"], hits["p2"], N)
	}
}

func TestClient_FIFO430Fallthrough(t *testing.T) {
	// Provider #1 returns 430, provider #2 returns 223.
	makeFactory := func(statusLine string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = server.Write([]byte(statusLine))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory("430 No such article\r\n"), Connections: 2},
		{Factory: makeFactory("223 1 <id@test> exists\r\n"), Connections: 2},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("Send() error = %v", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223 (should fall through from 430 provider)", resp.StatusCode)
	}
}

// TestClient_FIFO423Fallthrough mirrors TestClient_FIFO430Fallthrough with the
// other article-not-found code. A STAT payload is not STAT-probe raceable, so
// this pins the sequential failover branch of the main dispatch loop — the one
// that used to compare against 430 alone and hand a 423 back as a success.
func TestClient_FIFO423Fallthrough(t *testing.T) {
	// Provider #1 returns 423, provider #2 returns 223.
	makeFactory := func(statusLine string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = server.Write([]byte(statusLine))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory("423 No article with that number\r\n"), Connections: 2},
		{Factory: makeFactory("223 1 <id@test> exists\r\n"), Connections: 2},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("Send() error = %v", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223 (should fall through from 423 provider)", resp.StatusCode)
	}
}

// TestClient_RoundRobinMoreThan8Providers verifies that weighted round-robin
// dispatch does not panic when more than 8 providers are configured.
// Previously a fixed-size [8]int array caused an index-out-of-range panic.
func TestClient_RoundRobinMoreThan8Providers(t *testing.T) {
	const numProviders = 9 // one more than the old hard-coded limit

	makeFactory := func() ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}()
			return client, nil
		}
	}

	providers := make([]Provider, numProviders)
	for i := range providers {
		providers[i] = Provider{Factory: makeFactory(), Connections: 2}
	}

	c, err := NewClient(context.Background(), providers, WithDispatchStrategy(DispatchRoundRobin))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Send several requests — any panic in the dispatch path will surface here.
	for range 10 {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
		cancel()
		if resp.Err != nil {
			t.Fatalf("Send() error = %v", resp.Err)
		}
		if resp.StatusCode != 223 {
			t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
		}
	}
}

// --- Benchmarks ---

func benchSend(b *testing.B, providers []Provider) {
	b.Helper()

	c, err := NewClient(context.Background(), providers)
	if err != nil {
		b.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	payload := []byte("STAT <bench@test>\r\n")

	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp := <-c.Send(ctx, payload, nil)
		cancel()
		if resp.Err != nil {
			b.Fatalf("Send() error = %v", resp.Err)
		}
	}
}

func BenchmarkSend(b *testing.B) {
	benchFactory := func() ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}()
			return client, nil
		}
	}

	b.Run("EqualWeight_3_3", func(b *testing.B) {
		benchSend(b, []Provider{
			{Factory: benchFactory(), Connections: 3},
			{Factory: benchFactory(), Connections: 3},
		})
	})

	b.Run("Weighted_5_1", func(b *testing.B) {
		benchSend(b, []Provider{
			{Factory: benchFactory(), Connections: 5},
			{Factory: benchFactory(), Connections: 1},
		})
	})

	b.Run("SingleProvider", func(b *testing.B) {
		benchSend(b, []Provider{
			{Factory: benchFactory(), Connections: 6},
		})
	})
}

// --- readOneResponse helper (used in NNTPConnection setup) ---

func TestReadOneResponse(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))
		_, _ = s.Write(mockNNTPResponse("221 0 <msg@id> head",
			"Subject: Test",
			"From: user@example.com",
		))
		// Keep alive
		buf := make([]byte, 1)
		_, _ = s.Read(buf)
	})

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	resp, err := nc.readOneResponse(io.Discard)
	if err != nil {
		t.Fatalf("readOneResponse() error = %v", err)
	}
	if resp.StatusCode != 221 {
		t.Errorf("StatusCode = %d, want 221", resp.StatusCode)
	}
	if len(resp.Lines) != 2 {
		t.Errorf("Lines = %d, want 2", len(resp.Lines))
	}
}

func TestClient_HotConnectionPreference(t *testing.T) {
	// With Connections: 4, after establishing one hot connection all
	// subsequent sequential requests should reuse it (no new dials).
	var dials atomic.Int64

	factory := func(ctx context.Context) (net.Conn, error) {
		dials.Add(1)
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := string(buf[:n])
				if bytes.HasPrefix(buf[:n], []byte("DATE")) {
					_, _ = server.Write([]byte("111 20260101120000\r\n"))
				} else {
					_ = cmd
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 4},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Ping uses 1 dial. Record the baseline after client creation.
	baseline := dials.Load()

	// First request: establishes 1 hot connection (cold slot wakeup).
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	cancel()
	if resp.Err != nil {
		t.Fatalf("Send() error = %v", resp.Err)
	}
	afterFirst := dials.Load() - baseline
	if afterFirst != 1 {
		t.Fatalf("after first request: new dials = %d, want 1", afterFirst)
	}

	// Five more sequential requests — should all reuse the hot connection.
	for i := range 5 {
		// Small yield so the writer loop re-enters its hot channel select
		// after completing the previous response cycle.
		time.Sleep(10 * time.Millisecond)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
		cancel()
		if resp.Err != nil {
			t.Fatalf("Send()[%d] error = %v", i, resp.Err)
		}
	}

	if got := dials.Load() - baseline; got != 1 {
		t.Errorf("after 6 sequential requests: new dials = %d, want 1", got)
	}
}

func TestClient_ColdWakeupOnSaturation(t *testing.T) {
	// With Connections: 4, Inflight: 1, concurrent requests that exceed
	// a single connection's inflight capacity must wake cold slots.
	var dials atomic.Int64

	// slowServer blocks STAT requests until released via gate.
	// DATE (ping) and other commands respond immediately.
	gate := make(chan struct{}, 4)
	factory := func(ctx context.Context) (net.Conn, error) {
		dials.Add(1)
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if bytes.HasPrefix(buf[:n], []byte("DATE")) {
					_, _ = server.Write([]byte("111 20260101120000\r\n"))
				} else {
					// Wait for the gate before responding to STAT.
					<-gate
					_, _ = server.Write([]byte("223 1 <id@test> exists\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 4, Inflight: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Ping uses 1 dial. Record baseline.
	baseline := dials.Load()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// Send 2 concurrent requests.
	ch1 := c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	// Small delay so the first request is picked up and the connection
	// becomes hot before we send the second one.
	time.Sleep(50 * time.Millisecond)
	ch2 := c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)

	// Give time for cold slot to wake and dial.
	time.Sleep(200 * time.Millisecond)

	if got := dials.Load() - baseline; got < 2 {
		t.Errorf("concurrent requests: new dials = %d, want >= 2", got)
	}

	// Release both.
	gate <- struct{}{}
	gate <- struct{}{}

	resp1 := <-ch1
	resp2 := <-ch2
	if resp1.Err != nil {
		t.Fatalf("Send()[0] error = %v", resp1.Err)
	}
	if resp2.Err != nil {
		t.Fatalf("Send()[1] error = %v", resp2.Err)
	}
}

func TestClient_BodyPriority(t *testing.T) {
	original := []byte("Hello priority body content for testing.")

	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if bytes.Contains(buf[:n], []byte("BODY")) {
					_, _ = server.Write(yencSinglePart(original, "test.bin"))
				} else {
					// Respond to DATE and other commands so ping doesn't hang.
					_, _ = server.Write([]byte("111 20260101120000\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	body, err := c.BodyPriority(ctx, "test@example.com")
	if err != nil {
		t.Fatalf("BodyPriority() error = %v", err)
	}
	if !bytes.Equal(body.Bytes, original) {
		t.Errorf("BodyPriority() decoded %d bytes, want %d", len(body.Bytes), len(original))
	}
	if body.Encoding != EncodingYEnc {
		t.Errorf("Encoding = %d, want %d (yEnc)", body.Encoding, EncodingYEnc)
	}
}

func TestClient_502CommandRemovesProvider(t *testing.T) {
	makeFactory := func(statusCode int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))

				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = fmt.Fprintf(server, "%d response\r\n", statusCode)
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(502), Connections: 1},               // main: always 502
		{Factory: makeFactory(502), Connections: 1, Backup: true}, // backup: also 502
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if c.NumProviders() != 2 {
		t.Fatalf("NumProviders() = %d, want 2", c.NumProviders())
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	// Both providers returned 502 and were removed — all providers exhausted.
	if !errors.Is(resp.Err, ErrServiceUnavailable) {
		t.Fatalf("Send() error = %v, want ErrServiceUnavailable", resp.Err)
	}
	if c.NumProviders() != 0 {
		t.Errorf("NumProviders() = %d, want 0 (both removed)", c.NumProviders())
	}
}

func TestClient_502ReconnectDelay(t *testing.T) {
	var attempt atomic.Int32
	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				_, err := server.Read(buf)
				if err != nil {
					return
				}
				if attempt.Add(1) == 1 {
					// First command: return 502 to trigger removal.
					_, _ = server.Write([]byte("502 service unavailable\r\n"))
				} else {
					// Subsequent commands: succeed.
					_, _ = server.Write([]byte("223 article exists\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1, SkipPing: true, ReconnectDelay: 50 * time.Millisecond},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// First request: hits 502, provider is removed.
	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if !errors.Is(resp.Err, ErrServiceUnavailable) {
		t.Fatalf("first Send() error = %v, want ErrServiceUnavailable", resp.Err)
	}
	if c.NumProviders() != 0 {
		t.Errorf("NumProviders() = %d, want 0 after 502", c.NumProviders())
	}

	// Wait for reconnect goroutine to re-add the provider.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if c.NumProviders() == 1 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if c.NumProviders() != 1 {
		t.Fatalf("NumProviders() = %d, want 1 after reconnect", c.NumProviders())
	}

	// Second request: should succeed via the re-added provider.
	resp = <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("second Send() error = %v", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
	}
}

func TestClient_502CommandFallsBackToBackup(t *testing.T) {
	makeFactory := func(statusCode int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))

				buf := make([]byte, 4096)
				for {
					_, err := server.Read(buf)
					if err != nil {
						return
					}
					_, _ = fmt.Fprintf(server, "%d response\r\n", statusCode)
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(502), Connections: 1},               // main: always 502
		{Factory: makeFactory(223), Connections: 1, Backup: true}, // backup: success
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("Send() error = %v", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223 (from backup)", resp.StatusCode)
	}
	// Main provider should have been removed, backup stays.
	if c.NumProviders() != 1 {
		t.Errorf("NumProviders() = %d, want 1 (main removed)", c.NumProviders())
	}
}

// --- Keepalive tests ---

// TestKeepalive_KeepsConnectionAlive verifies that the keepalive probe is sent
// and, when the server responds correctly, the connection remains alive and can
// serve subsequent real requests.
func TestKeepalive_KeepsConnectionAlive(t *testing.T) {
	keepaliveSeen := make(chan struct{}, 1)

	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 256)
		for {
			n, err := s.Read(buf)
			if err != nil {
				return
			}
			cmd := string(buf[:n])
			switch {
			case cmd == "DATE\r\n":
				select {
				case keepaliveSeen <- struct{}{}:
				default:
				}
				_, _ = s.Write([]byte("111 20060102150405\r\n"))
			case len(cmd) > 4 && cmd[:4] == "STAT":
				_, _ = s.Write([]byte("223 1 <id@test> exists\r\n"))
			}
		}
	})

	reqCh := make(chan *Request, 1)
	stats := &providerStats{}
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, stats)
	if err != nil {
		t.Fatalf("newNNTPConnectionFromConn() error = %v", err)
	}
	nc.keepaliveInterval = 20 * time.Millisecond
	nc.keepaliveCommand = "DATE"

	go nc.Run()
	t.Cleanup(func() { _ = nc.Close() })

	// Wait for at least one keepalive probe.
	select {
	case <-keepaliveSeen:
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: keepalive probe not sent")
	}

	// Verify the connection is still alive by sending a real request.
	respCh := make(chan Response, 1)
	reqCh <- &Request{
		Ctx:     context.Background(),
		Payload: []byte("STAT <id@test>\r\n"),
		RespCh:  respCh,
	}
	select {
	case resp := <-respCh:
		if resp.Err != nil {
			t.Fatalf("real request after keepalive: error = %v", resp.Err)
		}
		if resp.StatusCode != 223 {
			t.Errorf("real request: StatusCode = %d, want 223", resp.StatusCode)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: real request after keepalive timed out")
	}
	if got := stats.Errors.Load(); got != 0 {
		t.Errorf("provider errors after successful keepalive = %d, want 0", got)
	}
}

// TestKeepalive_DeadConnection verifies that when the server drops the connection
// in response to a keepalive probe, Run() returns (allowing runConnSlot to reconnect).
func TestKeepalive_DeadConnection(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 256)
		// Wait for the keepalive command, then close without responding.
		for {
			n, err := s.Read(buf)
			if err != nil {
				return
			}
			if string(buf[:n]) == "DATE\r\n" {
				// Drop connection without responding.
				_ = s.Close()
				return
			}
		}
	})

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("newNNTPConnectionFromConn() error = %v", err)
	}
	nc.keepaliveInterval = 20 * time.Millisecond
	nc.keepaliveCommand = "DATE"

	go nc.Run()

	// Run() should return once the keepalive detects the dead connection.
	select {
	case <-nc.Done():
		// Good: connection was detected dead and Run() returned.
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: Run() should have returned after keepalive detected dead connection")
	}
}

// TestKeepalive_SilentServer verifies the probe cannot park the connection:
// a server that ACCEPTS the keepalive command but never answers (half-open
// connection, stale provider session) must be detected via the probe's
// attempt deadline — Run() returns so the pool replaces the connection.
// Without the deadline the reader blocks in a deadline-less Read forever,
// holding the connection slot and the provider session indefinitely.
func TestKeepalive_SilentServer(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		buf := make([]byte, 256)
		for {
			// Read commands and never respond — the silent half-open server.
			if _, err := s.Read(buf); err != nil {
				return
			}
		}
	})

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("newNNTPConnectionFromConn() error = %v", err)
	}
	nc.keepaliveInterval = 20 * time.Millisecond
	nc.keepaliveCommand = "DATE"
	nc.stallTimeout = 100 * time.Millisecond // bounds the probe via keepaliveProbeTimeout

	go nc.Run()

	select {
	case <-nc.Done():
		// Good: the probe deadline expired, the connection closed, Run() returned.
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: a silent server parked the keepalive probe — Run() never returned")
	}
}

func TestClient_Skip430SameStorageGroup(t *testing.T) {
	// Two providers on different hosts that resell the same upstream storage.
	// A 430 from the first means the second holds the same article set, so it
	// should be skipped.
	var counts [2]atomic.Int64
	make430Factory := func(idx int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					if bytes.Contains(buf[:n], []byte("STAT")) {
						counts[idx].Add(1)
					}
					_, _ = server.Write([]byte("430 no such article\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Host: "news1.example.com:563", StorageGroup: "backbone-a", Factory: make430Factory(0), Connections: 1},
		{Host: "news2.example.com:563", StorageGroup: "backbone-a", Factory: make430Factory(1), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.StatusCode != 430 {
		t.Fatalf("StatusCode = %d, want 430", resp.StatusCode)
	}

	// Dispatch picks the starting provider, so assert on the total rather than
	// which one went first: exactly one of the pair may be tried.
	if got := counts[0].Load() + counts[1].Load(); got != 1 {
		t.Errorf("requests = [%d, %d], total %d, want exactly 1 (same StorageGroup should be skipped)",
			counts[0].Load(), counts[1].Load(), got)
	}
}

func TestClient_Skip430StorageGroupDoesNotSkipOtherGroups(t *testing.T) {
	// Two mains sharing a group, plus a backup on its own storage. The grouped
	// main is skipped after the first 430, but the independent backup must still
	// be tried — that provider is the only one that can hold a different answer.
	var counts [3]atomic.Int64
	make430Factory := func(idx int) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					if bytes.Contains(buf[:n], []byte("STAT")) {
						counts[idx].Add(1)
					}
					_, _ = server.Write([]byte("430 no such article\r\n"))
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Host: "news1.example.com:563", StorageGroup: "backbone-a", Factory: make430Factory(0), Connections: 1},
		{Host: "news2.example.com:563", StorageGroup: "backbone-a", Factory: make430Factory(1), Connections: 1},
		{Host: "news3.example.com:563", StorageGroup: "backbone-b", Factory: make430Factory(2), Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.StatusCode != 430 {
		t.Fatalf("StatusCode = %d, want 430", resp.StatusCode)
	}

	if got := counts[0].Load() + counts[1].Load(); got != 1 {
		t.Errorf("grouped mains = [%d, %d], total %d, want exactly 1",
			counts[0].Load(), counts[1].Load(), got)
	}
	if counts[2].Load() != 1 {
		t.Errorf("independent backup requests = %d, want 1 (must not be skipped)", counts[2].Load())
	}
}
