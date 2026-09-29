package engine

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

type heldCloseConn struct {
	net.Conn
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (c *heldCloseConn) Close() error {
	c.once.Do(func() { close(c.entered); <-c.release })
	return c.Conn.Close()
}

func TestProviderCloseDoesNotLockUnrelatedAccounts(t *testing.T) {
	xml, articles := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	_ = xml
	server, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	entered, release := make(chan struct{}), make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	var pools providerPools
	provider := nntppool.Provider{Host: "held", Connections: 1, SkipPing: true, Factory: func(ctx context.Context) (net.Conn, error) {
		var dial net.Dialer
		conn, err := dial.DialContext(ctx, "tcp", server.Addr())
		if err != nil {
			return nil, err
		}
		return &heldCloseConn{Conn: conn, entered: entered, release: release}, nil
	}}
	lease, err := pools.acquire(ctx, []nntppool.Provider{provider})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := lease.clients[0].Body(ctx, "f0-s0@test"); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { lease.Close(); close(done) }()
	select {
	case <-entered:
	case <-ctx.Done():
		t.Fatal("close did not reach connection")
	}
	unrelated := make(chan error, 1)
	go func() {
		other, err := pools.acquire(ctx, []nntppool.Provider{{Host: "unrelated", Connections: 1, SkipPing: true}})
		if err == nil {
			other.Close()
		}
		unrelated <- err
	}()
	select {
	case err := <-unrelated:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("network Close holds global provider lock")
	}
	waitCtx, stop := context.WithTimeout(ctx, 30*time.Millisecond)
	defer stop()
	if _, err := pools.acquire(waitCtx, []nntppool.Provider{provider}); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("same account bypassed close barrier: %v", err)
	}
	close(release)
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("close did not finish")
	}
}

func TestProviderCreationDoesNotLockUnrelatedAccounts(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	entered, release := make(chan struct{}), make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	var pools providerPools
	done := make(chan struct{})
	go func() {
		defer close(done)
		lease, err := pools.acquire(ctx, []nntppool.Provider{{Host: "slow", Connections: 1, Factory: func(ctx context.Context) (net.Conn, error) {
			close(entered)
			select {
			case <-release:
			case <-ctx.Done():
			}
			return nil, errors.New("fixture probe failed")
		}}})
		if err == nil {
			lease.Close()
		}
	}()
	select {
	case <-entered:
	case <-ctx.Done():
		t.Fatal("probe did not start")
	}
	otherDone := make(chan struct{})
	go func() {
		defer close(otherDone)
		lease, err := pools.acquire(ctx, []nntppool.Provider{{Host: "other", Connections: 1, SkipPing: true}})
		if err == nil {
			lease.Close()
		}
	}()
	select {
	case <-otherDone:
	case <-time.After(time.Second):
		t.Fatal("network connect holds global provider lock")
	}
	close(release)
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("creation stuck")
	}
}
