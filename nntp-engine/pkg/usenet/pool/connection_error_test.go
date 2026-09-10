package pool

import (
	"context"
	"errors"
	"testing"
	"time"

	"streamnzb/pkg/usenet/nntp"
)

func TestGetConnectionPreservesPoolError(t *testing.T) {
	server, host, port, stop := startCountingNNTPServer(t, false)
	defer stop()
	_ = server

	clientPool := nntp.NewClientPool(host, port, false, "user", "pass", 1)
	defer clientPool.Shutdown()
	held, err := clientPool.Get(context.Background())
	if err != nil {
		t.Fatalf("reserve client connection: %v", err)
	}
	defer clientPool.Put(held)

	p, err := NewPool(&Config{Providers: []ProviderConfig{
		{ID: "primary", ClientPool: clientPool},
	}})
	if err != nil {
		t.Fatalf("create pool: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, _, _, _, err = p.getConnection(ctx, nil, 999, false)
	if !errors.Is(err, ErrNoProvidersAvailable) {
		t.Fatalf("expected ErrNoProvidersAvailable, got %v", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected the connection error to retain context deadline, got %v", err)
	}
}
