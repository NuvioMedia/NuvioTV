package nntppool

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// --- connGate tests ---

func TestConnGate_EnterExit(t *testing.T) {
	g := newConnGate(3, time.Minute)
	defer g.stop()
	ctx := context.Background()

	if avail := g.available.Load(); avail != 3 {
		t.Fatalf("initial available = %d, want 3", avail)
	}

	// Enter max times
	for i := range 3 {
		if !g.enter(ctx, ctx) {
			t.Fatal("enter() should succeed")
		}
		if avail := g.available.Load(); avail != int32(2-i) {
			t.Fatalf("available after enter %d = %d, want %d", i+1, avail, 2-i)
		}
	}

	// Exit one
	g.exit()
	if avail := g.available.Load(); avail != 1 {
		t.Fatalf("available after exit = %d, want 1", avail)
	}

	// Re-enter
	if !g.enter(ctx, ctx) {
		t.Error("re-enter after exit should succeed")
	}

	// Clean up
	for range 3 {
		g.exit()
	}
	if avail := g.available.Load(); avail != 3 {
		t.Fatalf("available after all exits = %d, want 3", avail)
	}
}

func TestConnGate_EnterBlocks(t *testing.T) {
	g := newConnGate(1, time.Minute)
	defer g.stop()
	ctx := context.Background()

	if !g.enter(ctx, ctx) {
		t.Fatal("first enter should succeed")
	}

	// Second enter should block
	entered := make(chan bool, 1)
	go func() {
		entered <- g.enter(ctx, ctx)
	}()

	// Give it a moment to block
	select {
	case <-entered:
		t.Fatal("second enter should block")
	case <-time.After(50 * time.Millisecond):
	}

	// Exit first slot
	g.exit()

	// Now second should unblock
	select {
	case ok := <-entered:
		if !ok {
			t.Error("enter after exit should return true")
		}
	case <-time.After(time.Second):
		t.Fatal("enter should unblock after exit")
	}

	g.exit()
}

func TestConnGate_EnterCancelledSlotCtx(t *testing.T) {
	g := newConnGate(1, time.Minute)
	defer g.stop()
	ctx := context.Background()

	if !g.enter(ctx, ctx) {
		t.Fatal("first enter should succeed")
	}

	slotCtx, slotCancel := context.WithCancel(context.Background())
	entered := make(chan bool, 1)
	go func() {
		entered <- g.enter(slotCtx, ctx)
	}()

	// Cancel slot context
	time.Sleep(20 * time.Millisecond)
	slotCancel()

	select {
	case ok := <-entered:
		if ok {
			t.Error("enter with cancelled slotCtx should return false")
		}
	case <-time.After(time.Second):
		t.Fatal("should unblock on slotCtx cancel")
	}

	g.exit()
}

func TestConnGate_EnterCancelledReqCtx(t *testing.T) {
	g := newConnGate(1, time.Minute)
	defer g.stop()
	ctx := context.Background()

	if !g.enter(ctx, ctx) {
		t.Fatal("first enter should succeed")
	}

	reqCtx, reqCancel := context.WithCancel(context.Background())
	entered := make(chan bool, 1)
	go func() {
		entered <- g.enter(ctx, reqCtx)
	}()

	// Cancel req context
	time.Sleep(20 * time.Millisecond)
	reqCancel()

	select {
	case ok := <-entered:
		if ok {
			t.Error("enter with cancelled reqCtx should return false")
		}
	case <-time.After(time.Second):
		t.Fatal("should unblock on reqCtx cancel")
	}

	g.exit()
}

func TestConnGate_ConcurrentEnterExit(t *testing.T) {
	g := newConnGate(5, time.Minute)
	defer g.stop()
	ctx := context.Background()

	var wg sync.WaitGroup
	for range 20 {
		wg.Go(func() {
			if g.enter(ctx, ctx) {
				time.Sleep(time.Millisecond)
				g.exit()
			}
		})
	}
	wg.Wait()

	// Verify held == 0 at end
	g.mu.Lock()
	held := g.held
	g.mu.Unlock()
	if held != 0 {
		t.Errorf("held = %d, want 0 after all goroutines done", held)
	}
}

func TestConnGate_Throttle(t *testing.T) {
	g := newConnGate(10, time.Minute)
	defer g.stop()

	// Simulate 3 running connections
	g.mu.Lock()
	g.running = 3
	g.mu.Unlock()

	g.throttle()

	g.mu.Lock()
	allowed := g.allowed
	held := g.held
	g.mu.Unlock()

	if allowed != 3 {
		t.Errorf("allowed = %d, want 3 (max(1, running))", allowed)
	}
	if avail := g.available.Load(); avail != int32(allowed-held) {
		t.Errorf("available = %d, want %d", avail, allowed-held)
	}

	// Throttle with 0 running → should go to 1
	g.mu.Lock()
	g.running = 0
	g.allowed = 10 // reset
	g.mu.Unlock()

	g.throttle()

	g.mu.Lock()
	allowed = g.allowed
	held = g.held
	g.mu.Unlock()

	if allowed != 1 {
		t.Errorf("allowed = %d, want 1 (max(1, 0))", allowed)
	}
	if avail := g.available.Load(); avail != int32(allowed-held) {
		t.Errorf("available = %d, want %d", avail, allowed-held)
	}

	// Throttle should only tighten, not loosen
	g.mu.Lock()
	g.allowed = 2
	g.running = 5
	g.mu.Unlock()

	g.throttle()

	g.mu.Lock()
	allowed = g.allowed
	held = g.held
	g.mu.Unlock()

	if allowed != 2 {
		t.Errorf("allowed = %d, want 2 (should not loosen)", allowed)
	}
	if avail := g.available.Load(); avail != int32(allowed-held) {
		t.Errorf("available = %d, want %d", avail, allowed-held)
	}
}

func TestConnGate_Restore(t *testing.T) {
	g := newConnGate(10, 50*time.Millisecond)
	defer g.stop()

	g.mu.Lock()
	g.running = 1
	g.mu.Unlock()
	g.throttle()

	g.mu.Lock()
	allowed := g.allowed
	g.mu.Unlock()
	if allowed != 1 {
		t.Fatalf("allowed = %d, want 1 after throttle", allowed)
	}
	if avail := g.available.Load(); avail != 1 {
		t.Fatalf("available = %d, want 1 after throttle (allowed=1, held=0)", avail)
	}

	// Wait for restore
	time.Sleep(100 * time.Millisecond)

	g.mu.Lock()
	allowed = g.allowed
	g.mu.Unlock()
	if allowed != 10 {
		t.Errorf("allowed = %d, want 10 after restore", allowed)
	}
	if avail := g.available.Load(); avail != 10 {
		t.Errorf("available = %d, want 10 after restore", avail)
	}
}

func TestConnGate_Snapshot(t *testing.T) {
	g := newConnGate(8, time.Minute)
	defer g.stop()

	maxSlots, running := g.snapshot()
	if maxSlots != 8 || running != 0 {
		t.Errorf("snapshot = (%d, %d), want (8, 0)", maxSlots, running)
	}

	g.markRunning()
	g.markRunning()
	maxSlots, running = g.snapshot()
	if maxSlots != 8 || running != 2 {
		t.Errorf("snapshot = (%d, %d), want (8, 2)", maxSlots, running)
	}

	g.markNotRunning()
	_, running = g.snapshot()
	if running != 1 {
		t.Errorf("running = %d, want 1", running)
	}
}

func TestConnGate_Stop(t *testing.T) {
	g := newConnGate(10, time.Hour)
	g.throttle() // start restore timer

	g.mu.Lock()
	hasTimer := g.restoreTimer != nil
	g.mu.Unlock()
	if !hasTimer {
		t.Fatal("should have restore timer after throttle")
	}

	g.stop()

	g.mu.Lock()
	hasTimer = g.restoreTimer != nil
	g.mu.Unlock()
	if hasTimer {
		t.Error("stop() should cancel restore timer")
	}
}

// --- safeClose ---

func TestSafeClose(t *testing.T) {
	// Open channel
	ch := make(chan Response)
	safeClose(ch)
	// Verify closed
	select {
	case _, ok := <-ch:
		if ok {
			t.Error("channel should be closed")
		}
	default:
		t.Error("receive on closed channel should not block")
	}

	// Already closed channel — should not panic
	safeClose(ch) // would panic without recover in safeClose
}

// --- NewClient validation ---

func TestNewClient_Validation(t *testing.T) {
	dummyFactory := func(ctx context.Context) (net.Conn, error) {
		return nil, nil
	}

	tests := []struct {
		name      string
		providers []Provider
		wantErr   bool
	}{
		{
			name:      "no providers",
			providers: nil,
			wantErr:   true,
		},
		{
			name: "all backup",
			providers: []Provider{
				{Host: "host1:119", Connections: 1, Backup: true},
			},
			wantErr: true,
		},
		{
			name: "zero connections",
			providers: []Provider{
				{Host: "host1:119", Connections: 0},
			},
			wantErr: true,
		},
		{
			name: "missing host and factory",
			providers: []Provider{
				{Connections: 1},
			},
			wantErr: true,
		},
		{
			name: "valid with factory only",
			providers: []Provider{
				{Factory: dummyFactory, Connections: 1},
			},
			wantErr: false,
		},
		{
			name: "valid with host only",
			providers: []Provider{
				{Host: "host1:119", Connections: 1},
			},
			wantErr: false,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, err := NewClient(context.Background(), tt.providers)
			if (err != nil) != tt.wantErr {
				t.Errorf("NewClient() error = %v, wantErr = %v", err, tt.wantErr)
			}
			if c != nil {
				_ = c.Close()
			}
		})
	}
}

func TestNewClient_DuplicateProvider(t *testing.T) {
	p := Provider{Host: "news.example.com:119", Auth: Auth{Username: "user"}, Connections: 1}
	_, err := NewClient(context.Background(), []Provider{p, p}, nil)
	if err == nil {
		t.Fatal("expected error for duplicate provider")
	}
}

func TestNewClient_NilContext(t *testing.T) {
	// Nil context should default to Background
	c, err := NewClient(context.TODO(), []Provider{
		{Host: "localhost:119", Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()
	if c.ctx == nil {
		t.Error("ctx should not be nil")
	}
}

func TestClient_NumProviders(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "main1:119", Connections: 1},
		{Host: "main2:119", Connections: 1},
		{Host: "backup:119", Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if got := c.NumProviders(); got != 3 {
		t.Errorf("NumProviders() = %d, want 3", got)
	}
}

func TestClient_Stats(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "main:119", Connections: 2},
		{Host: "backup:119", Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	time.Sleep(10 * time.Millisecond) // ensure non-zero elapsed

	stats := c.Stats()
	if stats.Elapsed <= 0 {
		t.Error("Elapsed should be > 0")
	}
	if len(stats.Providers) != 2 {
		t.Errorf("Providers = %d, want 2", len(stats.Providers))
	}
	if stats.Providers[0].Name != "main:119" {
		t.Errorf("Name = %q, want main:119", stats.Providers[0].Name)
	}
	if stats.Providers[0].MaxConnections != 2 {
		t.Errorf("MaxConnections = %d, want 2", stats.Providers[0].MaxConnections)
	}
}

func TestNewClient_DefaultInflight(t *testing.T) {
	// Inflight=0 should default to 1 internally
	c, err := NewClient(context.Background(), []Provider{
		{Host: "host:119", Connections: 1, Inflight: 0},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()
	// The defaulting happens inside NewClient; we verify it didn't error.
}

// --- parseDateResponse ---

func TestParseDateResponse(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		wantErr bool
		wantY   int
		wantM   time.Month
		wantD   int
	}{
		{"full status line", "111 20240315120000", false, 2024, time.March, 15},
		{"timestamp only", "20240315120000", false, 2024, time.March, 15},
		{"too short", "111 2024", true, 0, 0, 0},
		{"empty", "", true, 0, 0, 0},
		{"bad format", "111 not-a-date!!", true, 0, 0, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := parseDateResponse(tt.input)
			if (err != nil) != tt.wantErr {
				t.Errorf("parseDateResponse(%q) error = %v, wantErr %v", tt.input, err, tt.wantErr)
				return
			}
			if !tt.wantErr {
				if got.Year() != tt.wantY || got.Month() != tt.wantM || got.Day() != tt.wantD {
					t.Errorf("parseDateResponse(%q) = %v, want %d-%v-%d", tt.input, got, tt.wantY, tt.wantM, tt.wantD)
				}
			}
		})
	}
}

// --- pingProvider ---

func TestPingProvider_Success(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return mockServer(t, func(s net.Conn) {
			_, _ = s.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 1024)
			_, _ = s.Read(buf) // DATE command
			_, _ = s.Write([]byte("111 20240315120000\r\n"))
		}), nil
	}

	result := pingProvider(context.Background(), factory, Auth{})
	if result.Err != nil {
		t.Fatalf("pingProvider() error = %v", result.Err)
	}
	if result.RTT <= 0 {
		t.Errorf("RTT = %v, want > 0", result.RTT)
	}
	if result.ServerTime.IsZero() {
		t.Error("ServerTime should not be zero")
	}
	if result.ServerTime.Year() != 2024 || result.ServerTime.Month() != time.March {
		t.Errorf("ServerTime = %v, want 2024-03-15", result.ServerTime)
	}
}

func TestPingProvider_WithAuth(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return mockServer(t, func(s net.Conn) {
			_, _ = s.Write([]byte("200 server ready\r\n"))

			buf := make([]byte, 1024)
			_, _ = s.Read(buf) // AUTHINFO USER
			_, _ = s.Write([]byte("381 password required\r\n"))
			_, _ = s.Read(buf) // AUTHINFO PASS
			_, _ = s.Write([]byte("281 authentication accepted\r\n"))

			_, _ = s.Read(buf) // DATE
			_, _ = s.Write([]byte("111 20240315120000\r\n"))
		}), nil
	}

	result := pingProvider(context.Background(), factory, Auth{Username: "user", Password: "pass"})
	if result.Err != nil {
		t.Fatalf("pingProvider() error = %v", result.Err)
	}
	if result.RTT <= 0 {
		t.Errorf("RTT = %v, want > 0", result.RTT)
	}
}

func TestPingProvider_DialError(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return nil, fmt.Errorf("connection refused")
	}

	result := pingProvider(context.Background(), factory, Auth{})
	if result.Err == nil {
		t.Fatal("expected error for dial failure")
	}
}

func TestPingProvider_BadGreeting(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return mockServer(t, func(s net.Conn) {
			_, _ = s.Write([]byte("502 service unavailable\r\n"))
		}), nil
	}

	result := pingProvider(context.Background(), factory, Auth{})
	if result.Err == nil {
		t.Fatal("expected error for bad greeting")
	}
}

// --- AddProvider / RemoveProvider ---

func TestAddProvider(t *testing.T) {
	makeFactory := func(statusCode int) ConnFactory {
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
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = fmt.Fprintf(server, "%d response\r\n", statusCode)
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(223), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if c.NumProviders() != 1 {
		t.Fatalf("NumProviders() = %d, want 1", c.NumProviders())
	}

	// Add a second provider.
	err = c.AddProvider(Provider{Factory: makeFactory(223), Connections: 1})
	if err != nil {
		t.Fatalf("AddProvider() error = %v", err)
	}

	if c.NumProviders() != 2 {
		t.Errorf("NumProviders() = %d, want 2", c.NumProviders())
	}

	// Verify it shows up in Stats with ping data.
	stats := c.Stats()
	if len(stats.Providers) != 2 {
		t.Fatalf("Stats().Providers = %d, want 2", len(stats.Providers))
	}
}

func TestAddProvider_Backup(t *testing.T) {
	makeFactory := func() ConnFactory {
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
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = server.Write([]byte("223 exists\r\n"))
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	err = c.AddProvider(Provider{Factory: makeFactory(), Connections: 1, Backup: true})
	if err != nil {
		t.Fatalf("AddProvider(backup) error = %v", err)
	}

	if c.NumProviders() != 2 {
		t.Errorf("NumProviders() = %d, want 2", c.NumProviders())
	}
}

func TestAddProvider_Validation(t *testing.T) {
	dummyFactory := func(ctx context.Context) (net.Conn, error) {
		return nil, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: dummyFactory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Zero connections
	if err := c.AddProvider(Provider{Host: "host:119", Connections: 0}); err == nil {
		t.Error("expected error for zero connections")
	}

	// Missing host and factory
	if err := c.AddProvider(Provider{Connections: 1}); err == nil {
		t.Error("expected error for missing host and factory")
	}
}

func TestAddProvider_DuplicateName(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "host:119", Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Same host should be rejected as duplicate.
	if err := c.AddProvider(Provider{Host: "host:119", Connections: 1}); err == nil {
		t.Error("expected error for duplicate provider name")
	}
}

func TestRemoveProvider(t *testing.T) {
	makeFactory := func() ConnFactory {
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
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = server.Write([]byte("223 exists\r\n"))
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Host: "main1:119", Factory: makeFactory(), Connections: 1},
		{Host: "main2:119", Factory: makeFactory(), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if c.NumProviders() != 2 {
		t.Fatalf("NumProviders() = %d, want 2", c.NumProviders())
	}

	err = c.RemoveProvider("main2:119")
	if err != nil {
		t.Fatalf("RemoveProvider() error = %v", err)
	}

	if c.NumProviders() != 1 {
		t.Errorf("NumProviders() = %d, want 1", c.NumProviders())
	}

	// Verify it's gone from Stats.
	stats := c.Stats()
	for _, ps := range stats.Providers {
		if ps.Name == "main2:119" {
			t.Error("removed provider still in Stats")
		}
	}
}

func TestRemoveProvider_NotFound(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "host:119", Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	err = c.RemoveProvider("nonexistent:119")
	if err == nil {
		t.Error("expected error for not-found provider")
	}
}

// --- NewClient ping integration ---

func TestNewClient_PingResults(t *testing.T) {
	makeFactory := func() ConnFactory {
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
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = server.Write([]byte("223 exists\r\n"))
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(), Connections: 1},
		{Factory: makeFactory(), Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	stats := c.Stats()
	if len(stats.Providers) != 2 {
		t.Fatalf("Providers = %d, want 2", len(stats.Providers))
	}
	for i, ps := range stats.Providers {
		if ps.Ping.Err != nil {
			t.Errorf("provider %d ping error = %v", i, ps.Ping.Err)
		}
		if ps.Ping.RTT <= 0 {
			t.Errorf("provider %d RTT = %v, want > 0", i, ps.Ping.RTT)
		}
		if ps.Ping.ServerTime.IsZero() {
			t.Errorf("provider %d ServerTime is zero", i)
		}
	}
}

func TestNewClient_PingFailureDoesNotBlock(t *testing.T) {
	// Provider that fails to connect — ping should fail but client should still work.
	failFactory := func(ctx context.Context) (net.Conn, error) {
		return nil, fmt.Errorf("connection refused")
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: failFactory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	stats := c.Stats()
	if len(stats.Providers) != 1 {
		t.Fatalf("Providers = %d, want 1", len(stats.Providers))
	}
	if stats.Providers[0].Ping.Err == nil {
		t.Error("expected ping error for failing factory")
	}
}

// TestNewClient_PingsProvidersInParallel pins the cost of startup pings to
// ~max(RTT) rather than sum(RTT). Serial pinging made an unreachable host cost
// a full handshake timeout each, and callers that hold a lock across NewClient
// (AltMount does) stalled for N × that on every config change.
func TestNewClient_PingsProvidersInParallel(t *testing.T) {
	const (
		numProviders = 4
		pingDelay    = 300 * time.Millisecond
	)

	// Slots are not pre-warmed (MinConnections defaults to 0), so the ping is
	// the only call this factory sees.
	slowFactory := func(ctx context.Context) (net.Conn, error) {
		select {
		case <-time.After(pingDelay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.Contains(string(buf[:n]), "DATE") {
					_, _ = server.Write([]byte("111 20240315120000\r\n"))
				}
			}
		}()
		return client, nil
	}

	providers := make([]Provider, numProviders)
	for i := range providers {
		providers[i] = Provider{Name: fmt.Sprintf("p%d", i), Factory: slowFactory, Connections: 1}
	}

	start := time.Now()
	c, err := NewClient(context.Background(), providers)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Half the serial cost is a wide margin that still fails the old behaviour
	// (which needed numProviders × pingDelay).
	if limit := numProviders * pingDelay / 2; elapsed >= limit {
		t.Errorf("NewClient took %v, want < %v (pings must run concurrently, not serially)", elapsed, limit)
	}

	// Callers read Ping right after NewClient returns; parallelising must not
	// turn it into a result that lands later.
	stats := c.Stats()
	if len(stats.Providers) != numProviders {
		t.Fatalf("Providers = %d, want %d", len(stats.Providers), numProviders)
	}
	for i, ps := range stats.Providers {
		if ps.Ping.Err != nil {
			t.Errorf("provider %d ping error = %v", i, ps.Ping.Err)
		}
		if ps.Ping.RTT <= 0 {
			t.Errorf("provider %d RTT = %v, want > 0", i, ps.Ping.RTT)
		}
	}
}

// --- DispatchStrategy option wiring ---

func TestNewClient_DefaultDispatchRoundRobin(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "host1:119", Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if c.dispatch != DispatchRoundRobin {
		t.Errorf("dispatch = %d, want DispatchRoundRobin (%d)", c.dispatch, DispatchRoundRobin)
	}
}

func TestNewClient_WithDispatchFIFO(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "host1:119", Connections: 1},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if c.dispatch != DispatchFIFO {
		t.Errorf("dispatch = %d, want DispatchFIFO (%d)", c.dispatch, DispatchFIFO)
	}
}

// --- AddProvider then Send integration ---

func TestAddThenSend(t *testing.T) {
	makeFactory := func(statusCode int) ConnFactory {
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
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = fmt.Fprintf(server, "%d response\r\n", statusCode)
					}
				}
			}()
			return client, nil
		}
	}

	// Start with one main provider that returns 430.
	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(430), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Request should return 430 (only provider).
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	cancel()
	if resp.StatusCode != 430 {
		t.Fatalf("StatusCode = %d, want 430 before adding backup", resp.StatusCode)
	}

	// Add a backup provider that returns 223.
	err = c.AddProvider(Provider{Factory: makeFactory(223), Connections: 1, Backup: true})
	if err != nil {
		t.Fatalf("AddProvider() error = %v", err)
	}

	// Now request should fallback to backup and return 223.
	ctx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
	resp = <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	cancel()
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223 after adding backup", resp.StatusCode)
	}
}

// --- Error propagation ---

func TestSend_FactoryErrorPropagates(t *testing.T) {
	dialErr := fmt.Errorf("dial tcp: connection refused")
	factory := func(ctx context.Context) (net.Conn, error) {
		return nil, dialErr
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

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err == nil {
		t.Fatal("expected error from Send when factory fails")
	}
	if !errors.Is(resp.Err, dialErr) {
		t.Errorf("got error %q, want it to wrap %q", resp.Err, dialErr)
	}
	if !strings.Contains(resp.Err.Error(), "provider-0") {
		t.Errorf("error %q should contain provider name", resp.Err)
	}
}

func TestSend_ContextCancellationPropagates(t *testing.T) {
	// Factory that blocks until context is cancelled.
	factory := func(ctx context.Context) (net.Conn, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithCancel(context.Background())

	respCh := c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)

	// Cancel the request context.
	cancel()

	select {
	case resp := <-respCh:
		if resp.Err == nil {
			t.Fatal("expected error from Send on context cancellation")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for response after cancellation")
	}
}

func TestSend_All430Propagates(t *testing.T) {
	makeFactory := func(statusCode int) ConnFactory {
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
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = fmt.Fprintf(server, "%d no such article\r\n", statusCode)
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(430), Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <id@test>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("expected no Err for 430 response, got %v", resp.Err)
	}
	if resp.StatusCode != 430 {
		t.Errorf("StatusCode = %d, want 430", resp.StatusCode)
	}
}

func TestFailRequest(t *testing.T) {
	testErr := fmt.Errorf("test error")
	ch := make(chan Response, 1)
	failRequest(ch, testErr)

	resp, ok := <-ch
	if !ok {
		t.Fatal("expected to receive response before channel closed")
	}
	if resp.Err != testErr {
		t.Errorf("got error %v, want %v", resp.Err, testErr)
	}

	// Channel should now be closed.
	_, ok = <-ch
	if ok {
		t.Error("channel should be closed after failRequest")
	}
}

func TestFailRequest_DoubleClose(t *testing.T) {
	ch := make(chan Response, 1)
	failRequest(ch, fmt.Errorf("err1"))
	// Second call should not panic (safeClose recovers).
	failRequest(ch, fmt.Errorf("err2"))
}

// --- extractProbeMsgID ---

func TestExtractProbeMsgID(t *testing.T) {
	tests := []struct {
		name    string
		payload string
		want    string // empty means nil expected
	}{
		{"BODY with msgid", "BODY <a@b>\r\n", "<a@b>"},
		{"HEAD with msgid", "HEAD <foo@bar.example>\r\n", "<foo@bar.example>"},
		{"ARTICLE with msgid", "ARTICLE <x@y>\r\n", "<x@y>"},
		{"STAT is excluded", "STAT <a@b>\r\n", ""},
		{"POST is excluded", "POST\r\n", ""},
		{"GROUP has no msgid", "GROUP alt.test\r\n", ""},
		{"DATE has no msgid", "DATE\r\n", ""},
		{"malformed no closing >", "BODY <a@b\r\n", ""},
		{"empty payload", "", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractProbeMsgID([]byte(tt.payload))
			if tt.want == "" {
				if got != nil {
					t.Errorf("extractProbeMsgID() = %q, want nil", got)
				}
			} else {
				if string(got) != tt.want {
					t.Errorf("extractProbeMsgID() = %q, want %q", got, tt.want)
				}
			}
		})
	}
}

// --- STAT probe failover tests ---

// makeStatProbeFactory creates a ConnFactory for STAT probe tests.
// cmdLog (guarded by mu) records every non-DATE command received.
// responses maps command prefixes to response lines.
func makeStatProbeFactory(t *testing.T, mu *sync.Mutex, cmdLog *[]string, responses map[string]string) ConnFactory {
	t.Helper()
	return func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := strings.TrimRight(string(buf[:n]), "\r\n")
				// Always respond to DATE for ping.
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240315120000\r\n"))
					continue
				}
				mu.Lock()
				*cmdLog = append(*cmdLog, cmd)
				mu.Unlock()
				var reply string
				for prefix, resp := range responses {
					if strings.HasPrefix(cmd, prefix) {
						reply = resp
						break
					}
				}
				if reply == "" {
					reply = "500 unknown command"
				}
				_, _ = fmt.Fprintf(server, "%s\r\n", reply)
			}
		}()
		return client, nil
	}
}

func TestSend_430StatProbeWinnerGetsBody(t *testing.T) {
	// P1: BODY→430 (first attempt, forces probe).
	// P2: STAT→430.
	// P3: STAT→223, BODY→222 (winner).
	var mu sync.Mutex
	var p1Cmds, p2Cmds, p3Cmds []string

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory: makeStatProbeFactory(t, &mu, &p1Cmds, map[string]string{
				"BODY": "430 no such article",
				"STAT": "430 no such article",
			}),
			Connections: 1,
		},
		{
			Factory: makeStatProbeFactory(t, &mu, &p2Cmds, map[string]string{
				"STAT": "430 no such article",
			}),
			Connections: 1,
		},
		{
			Factory: makeStatProbeFactory(t, &mu, &p3Cmds, map[string]string{
				"STAT": "223 0 <test@host> article exists",
				"BODY": "222 0 <test@host> body follows\r\n.\r\n",
			}),
			Connections: 1,
		},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("BODY <test@host>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("unexpected error: %v", resp.Err)
	}
	if resp.StatusCode != 222 {
		t.Errorf("StatusCode = %d, want 222", resp.StatusCode)
	}

	mu.Lock()
	defer mu.Unlock()

	// P1 should have received BODY.
	if len(p1Cmds) == 0 || !strings.HasPrefix(p1Cmds[0], "BODY") {
		t.Errorf("P1 commands = %v, want first to be BODY", p1Cmds)
	}
	// P2 should have received STAT (probe), not BODY.
	if len(p2Cmds) == 0 || !strings.HasPrefix(p2Cmds[0], "STAT") {
		t.Errorf("P2 commands = %v, want first to be STAT probe", p2Cmds)
	}
	for _, cmd := range p2Cmds {
		if strings.HasPrefix(cmd, "BODY") {
			t.Error("P2 should not have received BODY")
		}
	}
	// P3 should have received STAT then BODY.
	if len(p3Cmds) < 2 {
		t.Fatalf("P3 commands = %v, want at least STAT + BODY", p3Cmds)
	}
	if !strings.HasPrefix(p3Cmds[0], "STAT") {
		t.Errorf("P3 first command = %q, want STAT", p3Cmds[0])
	}
	if !strings.HasPrefix(p3Cmds[1], "BODY") {
		t.Errorf("P3 second command = %q, want BODY", p3Cmds[1])
	}
}

// TestSend_423FailsOverLikeA430 mirrors TestSend_430StatProbeWinnerGetsBody
// with 423 ("no article with that number"). Both codes map to
// ErrArticleNotFound and raceCandidates already treats them alike; the main
// dispatch loop used to match 430 only, so a 423 was delivered as a "success"
// to the caller with no failover to the remaining mains.
func TestSend_423FailsOverLikeA430(t *testing.T) {
	// P1: BODY→423 (first attempt, must force the probe).
	// P2: STAT→423.
	// P3: STAT→223, BODY→222 (winner).
	var mu sync.Mutex
	var p1Cmds, p2Cmds, p3Cmds []string

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory: makeStatProbeFactory(t, &mu, &p1Cmds, map[string]string{
				"BODY": "423 no article with that number",
				"STAT": "423 no article with that number",
			}),
			Connections: 1,
		},
		{
			Factory: makeStatProbeFactory(t, &mu, &p2Cmds, map[string]string{
				"STAT": "423 no article with that number",
			}),
			Connections: 1,
		},
		{
			Factory: makeStatProbeFactory(t, &mu, &p3Cmds, map[string]string{
				"STAT": "223 0 <test@host> article exists",
				"BODY": "222 0 <test@host> body follows\r\n.\r\n",
			}),
			Connections: 1,
		},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("BODY <test@host>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("unexpected error: %v", resp.Err)
	}
	if resp.StatusCode != 222 {
		t.Fatalf("StatusCode = %d, want 222 (423 must fail over, not be delivered)", resp.StatusCode)
	}

	mu.Lock()
	defer mu.Unlock()

	if len(p1Cmds) == 0 || !strings.HasPrefix(p1Cmds[0], "BODY") {
		t.Errorf("P1 commands = %v, want first to be BODY", p1Cmds)
	}
	if len(p2Cmds) == 0 || !strings.HasPrefix(p2Cmds[0], "STAT") {
		t.Errorf("P2 commands = %v, want first to be STAT probe", p2Cmds)
	}
	if len(p3Cmds) < 2 {
		t.Fatalf("P3 commands = %v, want at least STAT + BODY", p3Cmds)
	}
	if !strings.HasPrefix(p3Cmds[0], "STAT") {
		t.Errorf("P3 first command = %q, want STAT", p3Cmds[0])
	}
	if !strings.HasPrefix(p3Cmds[1], "BODY") {
		t.Errorf("P3 second command = %q, want BODY", p3Cmds[1])
	}
}

func TestSend_430StatProbeParallel(t *testing.T) {
	// P1: BODY→430 immediately.
	// P2 and P3: STAT responds after 150ms with 430.
	// If probed in parallel, total wait ≈ 150ms; sequential would be ≈ 300ms.
	delay := 150 * time.Millisecond

	makeDelayedFactory := func(cmdLog *[]string, mu *sync.Mutex, statReply string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					cmd := strings.TrimRight(string(buf[:n]), "\r\n")
					if strings.HasPrefix(cmd, "DATE") {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
						continue
					}
					mu.Lock()
					*cmdLog = append(*cmdLog, cmd)
					mu.Unlock()
					if strings.HasPrefix(cmd, "STAT") {
						time.Sleep(delay)
					}
					_, _ = fmt.Fprintf(server, "%s\r\n", statReply)
				}
			}()
			return client, nil
		}
	}

	var mu sync.Mutex
	var p1Cmds, p2Cmds, p3Cmds []string

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory: makeStatProbeFactory(t, &mu, &p1Cmds, map[string]string{
				"BODY": "430 no such article",
				"STAT": "430 no such article",
			}),
			Connections: 1,
		},
		{Factory: makeDelayedFactory(&p2Cmds, &mu, "430 no such article"), Connections: 1},
		{Factory: makeDelayedFactory(&p3Cmds, &mu, "430 no such article"), Connections: 1},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	start := time.Now()
	resp := <-c.Send(ctx, []byte("BODY <test@host>\r\n"), nil)
	elapsed := time.Since(start)

	if resp.StatusCode != 430 {
		t.Errorf("StatusCode = %d, want 430", resp.StatusCode)
	}
	// Parallel: should finish in ~delay, not ~2*delay.
	// Allow generous margin (3x) to avoid flakiness.
	if elapsed > 3*delay {
		t.Errorf("elapsed = %v, want ≤ %v (probes should be parallel)", elapsed, 3*delay)
	}
}

func TestSend_All430WithStatProbeReturnsSaved430(t *testing.T) {
	makeAll430 := func() ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					cmd := string(buf[:n])
					if strings.HasPrefix(cmd, "DATE") {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = server.Write([]byte("430 no such article\r\n"))
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeAll430(), Connections: 1},
		{Factory: makeAll430(), Connections: 1},
		{Factory: makeAll430(), Connections: 1, Backup: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("BODY <test@host>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("unexpected error: %v", resp.Err)
	}
	if resp.StatusCode != 430 {
		t.Errorf("StatusCode = %d, want 430", resp.StatusCode)
	}
}

func TestSend_430NoMsgIDUsesSequentialFallback(t *testing.T) {
	// GROUP command has no message-ID: probe should not be used.
	// P1 returns error; P2 returns 211 success.
	var mu sync.Mutex
	var p2Cmds []string

	makeGroupFactory := func(reply string, cmdLog *[]string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					cmd := strings.TrimRight(string(buf[:n]), "\r\n")
					if strings.HasPrefix(cmd, "DATE") {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
						continue
					}
					if cmdLog != nil {
						mu.Lock()
						*cmdLog = append(*cmdLog, cmd)
						mu.Unlock()
					}
					_, _ = fmt.Fprintf(server, "%s\r\n", reply)
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeGroupFactory("430 no such article", nil), Connections: 1},
		{Factory: makeGroupFactory("211 15 1 15 alt.test", &p2Cmds), Connections: 1},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Use a command without a message-ID: STAT with a bare article number.
	resp := <-c.Send(ctx, []byte("GROUP alt.test\r\n"), nil)
	if resp.StatusCode != 211 {
		t.Errorf("StatusCode = %d, want 211", resp.StatusCode)
	}

	// P2 should not have received any STAT probe — only the GROUP command.
	mu.Lock()
	defer mu.Unlock()
	for _, cmd := range p2Cmds {
		if strings.HasPrefix(cmd, "STAT") {
			t.Errorf("P2 received STAT probe %q, expected only GROUP fallback", cmd)
		}
	}
}

func TestSend_WithStatProbeDisabled(t *testing.T) {
	// With WithStatProbe(false), should NOT probe — fall back sequentially.
	var mu sync.Mutex
	var p1Cmds, p2Cmds []string

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory: makeStatProbeFactory(t, &mu, &p1Cmds, map[string]string{
				"BODY": "430 no such article",
			}),
			Connections: 1,
		},
		{
			Factory: makeStatProbeFactory(t, &mu, &p2Cmds, map[string]string{
				"BODY": "222 0 <test@host> body\r\n.\r\n",
			}),
			Connections: 1,
		},
	}, WithDispatchStrategy(DispatchFIFO), WithStatProbe(false))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("BODY <test@host>\r\n"), nil)
	if resp.StatusCode != 222 {
		t.Errorf("StatusCode = %d, want 222", resp.StatusCode)
	}

	mu.Lock()
	defer mu.Unlock()

	// No STAT commands should have been sent to either provider.
	for _, cmd := range p1Cmds {
		if strings.HasPrefix(cmd, "STAT") {
			t.Errorf("P1 received STAT %q but StatProbe is disabled", cmd)
		}
	}
	for _, cmd := range p2Cmds {
		if strings.HasPrefix(cmd, "STAT") {
			t.Errorf("P2 received STAT %q but StatProbe is disabled", cmd)
		}
	}
}

func TestSend_430Probe502RemovesProvider(t *testing.T) {
	// P1: BODY→430 (triggers probe of remaining providers).
	// P2: STAT→502 (probe returns 502; P2 should be removed from pool).
	// P3: STAT→223, BODY→222 (winner).
	var mu sync.Mutex
	var p1Cmds, p2Cmds, p3Cmds []string

	p2Factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := strings.TrimRight(string(buf[:n]), "\r\n")
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240315120000\r\n"))
					continue
				}
				mu.Lock()
				p2Cmds = append(p2Cmds, cmd)
				mu.Unlock()
				// Always reply 502 on STAT probe.
				_, _ = server.Write([]byte("502 service unavailable\r\n"))
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory: makeStatProbeFactory(t, &mu, &p1Cmds, map[string]string{
				"BODY": "430 no such article",
				"STAT": "430 no such article",
			}),
			Connections: 1,
		},
		{Factory: p2Factory, Connections: 1},
		{
			Factory: makeStatProbeFactory(t, &mu, &p3Cmds, map[string]string{
				"STAT": "223 0 <test@host> exists",
				"BODY": "222 0 <test@host> body\r\n.\r\n",
			}),
			Connections: 1,
		},
	}, WithDispatchStrategy(DispatchFIFO))
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("BODY <test@host>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("unexpected error: %v", resp.Err)
	}
	if resp.StatusCode != 222 {
		t.Errorf("StatusCode = %d, want 222", resp.StatusCode)
	}

	// P2 should have been removed due to 502 probe response.
	if c.NumProviders() != 2 {
		t.Errorf("NumProviders() = %d, want 2 (P2 removed after 502 probe response)", c.NumProviders())
	}
}

// --- resolveProviderName ---

func TestResolveProviderName(t *testing.T) {
	tests := []struct {
		name  string
		p     Provider
		index int
		want  string
	}{
		{"host only", Provider{Host: "news.example.com:563"}, 0, "news.example.com:563"},
		{"host with auth", Provider{Host: "news.example.com:563", Auth: Auth{Username: "myuser"}}, 0, "news.example.com:563+myuser"},
		{"explicit name", Provider{Name: "provider-id-1", Host: "news.example.com:563", Auth: Auth{Username: "myuser"}}, 0, "provider-id-1"},
		{"factory only", Provider{Factory: func(ctx context.Context) (net.Conn, error) { return nil, nil }}, 3, "provider-3"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := resolveProviderName(tt.p, tt.index)
			if got != tt.want {
				t.Errorf("resolveProviderName() = %q, want %q", got, tt.want)
			}
		})
	}
}

// --- TestProvider standalone function ---

func TestTestProvider_Success(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return mockServer(t, func(s net.Conn) {
			_, _ = s.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 1024)
			_, _ = s.Read(buf) // DATE
			_, _ = s.Write([]byte("111 20240315120000\r\n"))
		}), nil
	}

	result := TestProvider(context.Background(), Provider{Factory: factory})
	if result.Err != nil {
		t.Fatalf("TestProvider() error = %v", result.Err)
	}
	if result.RTT <= 0 {
		t.Errorf("RTT = %v, want > 0", result.RTT)
	}
	if result.ServerTime.IsZero() {
		t.Error("ServerTime should not be zero")
	}
}

func TestTestProvider_WithAuth(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return mockServer(t, func(s net.Conn) {
			_, _ = s.Write([]byte("200 server ready\r\n"))

			buf := make([]byte, 1024)
			_, _ = s.Read(buf) // AUTHINFO USER
			_, _ = s.Write([]byte("381 password required\r\n"))
			_, _ = s.Read(buf) // AUTHINFO PASS
			_, _ = s.Write([]byte("281 authentication accepted\r\n"))

			_, _ = s.Read(buf) // DATE
			_, _ = s.Write([]byte("111 20240315120000\r\n"))
		}), nil
	}

	result := TestProvider(context.Background(), Provider{
		Factory: factory,
		Auth:    Auth{Username: "user", Password: "pass"},
	})
	if result.Err != nil {
		t.Fatalf("TestProvider() error = %v", result.Err)
	}
	if result.RTT <= 0 {
		t.Errorf("RTT = %v, want > 0", result.RTT)
	}
}

func TestTestProvider_DialError(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		return nil, fmt.Errorf("connection refused")
	}

	result := TestProvider(context.Background(), Provider{Factory: factory})
	if result.Err == nil {
		t.Fatal("expected error for dial failure")
	}
}

// --- dynamic weighted round-robin tests ---

// dynamicWeightedSelect mirrors the inline weight computation in sendWithRetry.
// It returns the selected provider index for a given slot value.
func dynamicWeightedSelect(groups []*providerGroup, slot int) int {
	var cumWeights [8]int
	totalW := 0
	for i, g := range groups {
		avail := max(1, int(g.gate.available.Load()))
		totalW += avail
		cumWeights[i] = totalW
	}
	s := slot % totalW
	return sort.SearchInts(cumWeights[:len(groups)], s+1)
}

func TestDynamicWeights_Available(t *testing.T) {
	// Provider 0: 50 available, Provider 1: 10 available.
	// Distribution should be 50:10 = 5:1.
	g0 := &providerGroup{gate: newConnGate(50, time.Minute)}
	g1 := &providerGroup{gate: newConnGate(10, time.Minute)}
	groups := []*providerGroup{g0, g1}

	counts := [2]int{}
	totalW := 60 // 50 + 10
	for slot := range totalW {
		idx := dynamicWeightedSelect(groups, slot)
		counts[idx]++
	}

	if counts[0] != 50 {
		t.Errorf("provider 0 got %d requests, want 50", counts[0])
	}
	if counts[1] != 10 {
		t.Errorf("provider 1 got %d requests, want 10", counts[1])
	}
}

func TestDynamicWeights_Saturated(t *testing.T) {
	// All providers at available=0 → each gets weight 1 → equal distribution.
	g0 := &providerGroup{gate: newConnGate(10, time.Minute)}
	g1 := &providerGroup{gate: newConnGate(10, time.Minute)}
	ctx := context.Background()

	// Saturate both gates.
	for range 10 {
		g0.gate.enter(ctx, ctx)
		g1.gate.enter(ctx, ctx)
	}

	if g0.gate.available.Load() != 0 {
		t.Fatalf("g0.available = %d, want 0", g0.gate.available.Load())
	}
	if g1.gate.available.Load() != 0 {
		t.Fatalf("g1.available = %d, want 0", g1.gate.available.Load())
	}

	groups := []*providerGroup{g0, g1}
	counts := [2]int{}
	totalW := 2 // weight 1 each
	for slot := range totalW {
		idx := dynamicWeightedSelect(groups, slot)
		counts[idx]++
	}

	if counts[0] != 1 || counts[1] != 1 {
		t.Errorf("saturated distribution = %v, want [1 1]", counts)
	}
}

func TestDynamicWeights_Throttled(t *testing.T) {
	// Provider 0: 50 max, not throttled → available=50.
	// Provider 1: 50 max, throttled to 5 → available=5.
	g0 := &providerGroup{gate: newConnGate(50, time.Minute)}
	g1 := &providerGroup{gate: newConnGate(50, time.Minute)}

	// Simulate throttle on g1: set running=5, then throttle.
	g1.gate.mu.Lock()
	g1.gate.running = 5
	g1.gate.mu.Unlock()
	g1.gate.throttle()

	avail0 := int(g0.gate.available.Load())
	avail1 := int(g1.gate.available.Load())
	if avail0 != 50 {
		t.Fatalf("g0 available = %d, want 50", avail0)
	}
	if avail1 != 5 {
		t.Fatalf("g1 available = %d, want 5", avail1)
	}

	groups := []*providerGroup{g0, g1}
	counts := [2]int{}
	totalW := avail0 + avail1 // 55
	for slot := range totalW {
		idx := dynamicWeightedSelect(groups, slot)
		counts[idx]++
	}

	if counts[0] != 50 {
		t.Errorf("provider 0 got %d requests, want 50", counts[0])
	}
	if counts[1] != 5 {
		t.Errorf("provider 1 got %d requests, want 5", counts[1])
	}
}

func TestDynamicWeights_ThreeProviders(t *testing.T) {
	g0 := &providerGroup{gate: newConnGate(50, time.Minute)}
	g1 := &providerGroup{gate: newConnGate(10, time.Minute)}
	g2 := &providerGroup{gate: newConnGate(20, time.Minute)}
	groups := []*providerGroup{g0, g1, g2}

	counts := [3]int{}
	totalW := 80
	for slot := range totalW {
		idx := dynamicWeightedSelect(groups, slot)
		counts[idx]++
	}

	if counts[0] != 50 || counts[1] != 10 || counts[2] != 20 {
		t.Errorf("distribution = %v, want [50 10 20]", counts)
	}
}

func TestDynamicWeights_SingleProvider(t *testing.T) {
	g0 := &providerGroup{gate: newConnGate(50, time.Minute)}
	groups := []*providerGroup{g0}

	for slot := range 50 {
		idx := dynamicWeightedSelect(groups, slot)
		if idx != 0 {
			t.Fatalf("slot %d mapped to idx %d, want 0", slot, idx)
		}
	}
}

func TestDynamicWeights_LargeN(t *testing.T) {
	// Simulate 6000 requests across 2 providers (50+10 available).
	g0 := &providerGroup{gate: newConnGate(50, time.Minute)}
	g1 := &providerGroup{gate: newConnGate(10, time.Minute)}
	groups := []*providerGroup{g0, g1}

	const N = 6000
	totalW := 60
	counts := [2]int{}

	for i := range N {
		idx := dynamicWeightedSelect(groups, i%totalW)
		counts[idx]++
	}

	wantP0 := N * 50 / totalW
	wantP1 := N * 10 / totalW
	if counts[0] != wantP0 || counts[1] != wantP1 {
		t.Errorf("over %d requests: provider 0 = %d (want %d), provider 1 = %d (want %d)",
			N, counts[0], wantP0, counts[1], wantP1)
	}

	pct0 := float64(counts[0]) / float64(N) * 100
	if math.Abs(pct0-83.33) > 1.0 {
		t.Errorf("provider 0 percentage = %.2f%%, want ~83.33%%", pct0)
	}
}

func TestUserAgent_StoredOnConnection(t *testing.T) {
	srv, cli := net.Pipe()
	defer func() { _ = srv.Close() }()
	defer func() { _ = cli.Close() }()

	go func() {
		_, _ = srv.Write([]byte("200 server ready\r\n"))
		buf := make([]byte, 256)
		for {
			if _, err := srv.Read(buf); err != nil {
				return
			}
		}
	}()

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), cli, 1, reqCh, nil, Auth{}, "TestAgent/1.0", nil, nil)
	if err != nil {
		t.Fatalf("newNNTPConnectionFromConn() error = %v", err)
	}

	if nc.userAgent != "TestAgent/1.0" {
		t.Errorf("userAgent = %q, want %q", nc.userAgent, "TestAgent/1.0")
	}
}

func TestUserAgent_EmptyIsAccepted(t *testing.T) {
	srv, cli := net.Pipe()
	defer func() { _ = srv.Close() }()
	defer func() { _ = cli.Close() }()

	go func() {
		_, _ = srv.Write([]byte("200 server ready\r\n"))
		buf := make([]byte, 256)
		for {
			if _, err := srv.Read(buf); err != nil {
				return
			}
		}
	}()

	reqCh := make(chan *Request)
	nc, err := newNNTPConnectionFromConn(context.Background(), cli, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("newNNTPConnectionFromConn() error = %v", err)
	}
	if nc.userAgent != "" {
		t.Errorf("userAgent = %q, want empty", nc.userAgent)
	}
}

// --- Quota tests ---

func TestProviderGroup_isQuotaExceeded_Unlimited(t *testing.T) {
	g := &providerGroup{}
	// quotaBytes == 0 → always false regardless of usage
	g.stats.quotaUsed.Store(1_000_000)
	g.stats.quotaExceeded.Store(true)
	if g.isQuotaExceeded() {
		t.Error("isQuotaExceeded() = true with no quota configured, want false")
	}
}

func TestProviderGroup_isQuotaExceeded_NotYetHit(t *testing.T) {
	g := &providerGroup{}
	g.stats.quotaBytes = 100
	// Flag not set → fast path returns false without checking time.
	if g.isQuotaExceeded() {
		t.Error("isQuotaExceeded() = true before quota hit, want false")
	}
}

func TestProviderGroup_isQuotaExceeded_FlagSet_NoReset(t *testing.T) {
	g := &providerGroup{} // quotaPeriod == 0 → no auto-reset
	g.stats.quotaBytes = 100
	g.stats.quotaUsed.Store(100)
	g.stats.quotaExceeded.Store(true)

	if !g.isQuotaExceeded() {
		t.Error("isQuotaExceeded() = false after quota hit with no period, want true")
	}
}

func TestProviderGroup_isQuotaExceeded_PeriodElapsed_Resets(t *testing.T) {
	g := &providerGroup{quotaPeriod: time.Hour}
	g.stats.quotaBytes = 50
	g.stats.quotaUsed.Store(50)
	g.stats.quotaExceeded.Store(true)
	// Set reset deadline in the past so the period is considered elapsed.
	g.quotaResetAt.Store(time.Now().Add(-time.Second).UnixNano())

	if g.isQuotaExceeded() {
		t.Error("isQuotaExceeded() = true after period elapsed, want false (reset should have fired)")
	}
	if g.stats.quotaUsed.Load() != 0 {
		t.Errorf("quotaUsed after reset = %d, want 0", g.stats.quotaUsed.Load())
	}
	if g.stats.quotaExceeded.Load() {
		t.Error("quotaExceeded flag should be cleared after reset")
	}
	if g.quotaResetAt.Load() <= time.Now().UnixNano() {
		t.Error("quotaResetAt should be scheduled in the future after reset")
	}
}

func TestProviderGroup_isQuotaExceeded_PeriodNotYetElapsed(t *testing.T) {
	g := &providerGroup{quotaPeriod: time.Hour}
	g.stats.quotaBytes = 50
	g.stats.quotaUsed.Store(50)
	g.stats.quotaExceeded.Store(true)
	// Reset deadline is in the future — should stay exceeded.
	g.quotaResetAt.Store(time.Now().Add(time.Hour).UnixNano())

	if !g.isQuotaExceeded() {
		t.Error("isQuotaExceeded() = false before period elapsed, want true")
	}
}

func TestProviderStats_QuotaAccounting(t *testing.T) {
	var stats providerStats
	stats.quotaBytes = 100

	// Add 60 bytes — not yet exceeded.
	stats.quotaUsed.Add(60)
	if stats.quotaExceeded.Load() {
		t.Error("quotaExceeded should be false before threshold")
	}

	// Simulate readLoop: add 40 more bytes → crosses threshold.
	if stats.quotaUsed.Add(40) >= stats.quotaBytes {
		stats.quotaExceeded.Store(true)
	}
	if !stats.quotaExceeded.Load() {
		t.Error("quotaExceeded should be true after crossing threshold")
	}
	if stats.quotaUsed.Load() != 100 {
		t.Errorf("quotaUsed = %d, want 100", stats.quotaUsed.Load())
	}
}

func TestClient_Stats_QuotaFields(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := string(buf[:n])
				if len(cmd) >= 4 && cmd[:4] == "DATE" {
					_, _ = server.Write([]byte("111 20240315120000\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory:     factory,
			Connections: 1,
			QuotaBytes:  1_000_000,
			QuotaPeriod: 30 * 24 * time.Hour,
			SkipPing:    true,
		},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	stats := c.Stats()
	if len(stats.Providers) == 0 {
		t.Fatal("no providers in stats")
	}
	ps := stats.Providers[0]
	if ps.QuotaBytes != 1_000_000 {
		t.Errorf("QuotaBytes = %d, want 1_000_000", ps.QuotaBytes)
	}
	if ps.QuotaUsed != 0 {
		t.Errorf("QuotaUsed = %d, want 0", ps.QuotaUsed)
	}
	if ps.QuotaExceeded {
		t.Error("QuotaExceeded should be false initially")
	}
	if ps.QuotaResetAt.IsZero() {
		t.Error("QuotaResetAt should be set when QuotaPeriod > 0")
	}
	if ps.QuotaResetAt.Before(time.Now()) {
		t.Error("QuotaResetAt should be in the future")
	}
}

func TestClient_QuotaExceeded_FallsThrough(t *testing.T) {
	// Two providers: first has quota exceeded, second responds normally.
	// The request must be served by the second provider.
	makeFactory := func(statusLine string) ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					n, err := server.Read(buf)
					if err != nil {
						return
					}
					cmd := string(buf[:n])
					if len(cmd) >= 4 && cmd[:4] == "DATE" {
						_, _ = server.Write([]byte("111 20240315120000\r\n"))
					} else {
						_, _ = server.Write([]byte(statusLine + "\r\n"))
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory("223 <article@quota>"), Connections: 1, SkipPing: true, QuotaBytes: 1},
		{Factory: makeFactory("223 <article@ok>"), Connections: 1, SkipPing: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Manually mark the first provider as quota-exceeded.
	mains := *c.mainGroups.Load()
	mains[0].stats.quotaExceeded.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <article@ok>\r\n"), nil)
	if resp.Err != nil {
		t.Fatalf("Send() error = %v, want nil (second provider should serve)", resp.Err)
	}
	if resp.StatusCode != 223 {
		t.Errorf("StatusCode = %d, want 223", resp.StatusCode)
	}
}

func TestClient_AllQuotaExceeded_ReturnsError(t *testing.T) {
	makeFactory := func() ConnFactory {
		return func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					if _, err := server.Read(buf); err != nil {
						return
					}
				}
			}()
			return client, nil
		}
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: makeFactory(), Connections: 1, SkipPing: true, QuotaBytes: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Mark the only provider as quota-exceeded.
	mains := *c.mainGroups.Load()
	mains[0].stats.quotaExceeded.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	resp := <-c.Send(ctx, []byte("STAT <x@x>\r\n"), nil)
	if resp.Err == nil {
		t.Fatal("Send() should return an error when all providers are quota-exceeded")
	}
	if !errors.Is(resp.Err, ErrQuotaExceeded) {
		t.Errorf("error = %v, want errors.Is(ErrQuotaExceeded)", resp.Err)
	}
}

func TestClient_ResetProviderQuota_NotFound(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "a:119", Factory: func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 ready\r\n"))
				buf := make([]byte, 512)
				for {
					if _, err := server.Read(buf); err != nil {
						return
					}
				}
			}()
			return client, nil
		}, Connections: 1, SkipPing: true},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if err := c.ResetProviderQuota("nonexistent:119"); err == nil {
		t.Error("ResetProviderQuota() on unknown provider should return an error")
	}
}

func TestClient_ResetProviderQuota_ClearsQuota(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{Host: "b:119", Factory: func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 ready\r\n"))
				buf := make([]byte, 512)
				for {
					if _, err := server.Read(buf); err != nil {
						return
					}
				}
			}()
			return client, nil
		}, Connections: 1, SkipPing: true, QuotaBytes: 1_000_000},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// Simulate quota exhaustion.
	g := (*c.mainGroups.Load())[0]
	g.stats.quotaUsed.Store(1_000_000)
	g.stats.quotaExceeded.Store(true)

	if err := c.ResetProviderQuota("b:119"); err != nil {
		t.Fatalf("ResetProviderQuota() unexpected error: %v", err)
	}

	if g.stats.quotaUsed.Load() != 0 {
		t.Errorf("quotaUsed after reset = %d, want 0", g.stats.quotaUsed.Load())
	}
	if g.stats.quotaExceeded.Load() {
		t.Error("quotaExceeded should be false after reset")
	}
	// No period configured → resetAt should be 0.
	if g.quotaResetAt.Load() != 0 {
		t.Errorf("quotaResetAt = %d, want 0 when no period set", g.quotaResetAt.Load())
	}
}

func TestClient_ResetProviderQuota_SchedulesNextPeriod(t *testing.T) {
	period := time.Hour
	c, err := NewClient(context.Background(), []Provider{
		{Host: "c:119", Factory: func(ctx context.Context) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer func() { _ = server.Close() }()
				_, _ = server.Write([]byte("200 ready\r\n"))
				buf := make([]byte, 512)
				for {
					if _, err := server.Read(buf); err != nil {
						return
					}
				}
			}()
			return client, nil
		}, Connections: 1, SkipPing: true, QuotaBytes: 1_000_000, QuotaPeriod: period},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	g := (*c.mainGroups.Load())[0]
	g.stats.quotaUsed.Store(1_000_000)
	g.stats.quotaExceeded.Store(true)

	before := time.Now()
	if err := c.ResetProviderQuota("c:119"); err != nil {
		t.Fatalf("ResetProviderQuota() unexpected error: %v", err)
	}
	after := time.Now()

	if g.stats.quotaUsed.Load() != 0 {
		t.Errorf("quotaUsed after reset = %d, want 0", g.stats.quotaUsed.Load())
	}
	if g.stats.quotaExceeded.Load() {
		t.Error("quotaExceeded should be false after reset")
	}

	resetAt := time.Unix(0, g.quotaResetAt.Load())
	earliest := before.Add(period)
	latest := after.Add(period)
	if resetAt.Before(earliest) || resetAt.After(latest) {
		t.Errorf("quotaResetAt = %v, want in [%v, %v]", resetAt, earliest, latest)
	}
}

// --- MinConnections (pre-warm) tests ---

func TestMinConnections_PreWarmsWithoutTraffic(t *testing.T) {
	var dials atomic.Int32

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
				if len(cmd) >= 4 && cmd[:4] == "DATE" {
					_, _ = server.Write([]byte("111 20240315120000\r\n"))
				} else {
					_, _ = server.Write([]byte("223 exists\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 3, MinConnections: 2},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	// No requests are sent. Only the pre-warmed slots (plus the startup
	// ping, which shares the same factory) should dial.
	deadline := time.After(2 * time.Second)
	for dials.Load() < 3 { // 1 ping + 2 pre-warmed connections
		select {
		case <-deadline:
			t.Fatalf("dials = %d, want >= 3 (ping + 2 pre-warmed) before timeout", dials.Load())
		case <-time.After(10 * time.Millisecond):
		}
	}

	// Give any (incorrect) lazy dial of the third slot a chance to happen.
	time.Sleep(100 * time.Millisecond)

	if got := dials.Load(); got != 3 {
		t.Errorf("dials = %d, want exactly 3 (ping + 2 pre-warmed, third slot stays cold)", got)
	}
}

func TestMinConnections_Validation(t *testing.T) {
	dummyFactory := func(ctx context.Context) (net.Conn, error) {
		return nil, nil
	}

	if _, err := NewClient(context.Background(), []Provider{
		{Factory: dummyFactory, Connections: 2, MinConnections: 3},
	}); err == nil {
		t.Error("expected error when MinConnections > Connections")
	}

	if _, err := NewClient(context.Background(), []Provider{
		{Factory: dummyFactory, Connections: 2, MinConnections: -1},
	}); err == nil {
		t.Error("expected error when MinConnections is negative")
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: dummyFactory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	if err := c.AddProvider(Provider{Host: "host:119", Connections: 2, MinConnections: 5}); err == nil {
		t.Error("expected AddProvider error when MinConnections > Connections")
	}
}

func TestMinConnections_ZeroKeepsAllSlotsLazy(t *testing.T) {
	var dials atomic.Int32

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
				if len(cmd) >= 4 && cmd[:4] == "DATE" {
					_, _ = server.Write([]byte("111 20240315120000\r\n"))
				} else {
					_, _ = server.Write([]byte("223 exists\r\n"))
				}
			}
		}()
		return client, nil
	}

	// MinConnections unset (0) must behave exactly as before the feature
	// existed: every slot stays cold until a request arrives, so the only
	// dial is the startup ping.
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 3},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	time.Sleep(200 * time.Millisecond)

	if got := dials.Load(); got != 1 {
		t.Errorf("dials = %d, want exactly 1 (startup ping only; no slot should pre-warm)", got)
	}
}
