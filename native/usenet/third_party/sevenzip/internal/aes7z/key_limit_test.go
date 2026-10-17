package aes7z

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestKeyDerivationCostBound(t *testing.T) {
	for _, cycles := range []int{-1, 25, 62, 64} {
		if _, err := calculateKey("password", cycles, nil); err == nil {
			t.Fatalf("accepted excessive/invalid cycles=%d", cycles)
		}
	}
	if _, err := calculateKey("password", 63, nil); err != nil {
		t.Fatal(err)
	}
}

func TestKeyDerivationCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan error, 1)
	go func() {
		_, err := calculateKeyContext(ctx, "unique cancellation test password", 24, []byte("cancel"))
		finished <- err
	}()
	time.AfterFunc(10*time.Millisecond, cancel)
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("derivation ignored cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled key derivation stayed busy")
	}
}
