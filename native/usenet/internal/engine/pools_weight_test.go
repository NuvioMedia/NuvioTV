package engine

import (
	"context"
	"testing"
	"time"

	"github.com/javi11/nntppool/v4"
)

func TestProviderDispatchFollowsConnectionShare(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var pools providerPools
	lease, err := pools.acquire(ctx, []nntppool.Provider{
		{Host: "primary", Connections: 30, SkipPing: true},
		{Host: "backup", Connections: 10, SkipPing: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Close()
	counts := make([]int, len(lease.clients))
	for range 400 {
		counts[lease.firstClient()]++
	}
	if counts[0] != 300 || counts[1] != 100 {
		t.Fatalf("dispatch share = %v, want [300 100]", counts)
	}
}

func TestSingleProviderAlwaysStartsFirst(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var pools providerPools
	lease, err := pools.acquire(ctx, []nntppool.Provider{{Host: "only", Connections: 4, SkipPing: true}})
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Close()
	for range 10 {
		if got := lease.firstClient(); got != 0 {
			t.Fatalf("firstClient = %d, want 0", got)
		}
	}
}
