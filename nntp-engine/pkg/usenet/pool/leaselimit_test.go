package pool

import (
	"context"
	"testing"
	"time"

	"streamnzb/pkg/media/nzb"
)

func TestLeaseLimiterCapsConcurrentPermits(t *testing.T) {
	limiter := newLeaseLimiter("stream-a", map[string]int{"eweka": 2})

	first, ok := limiter.tryAcquire("eweka")
	if !ok {
		t.Fatal("first permit should be granted")
	}
	second, ok := limiter.tryAcquire("eweka")
	if !ok {
		t.Fatal("second permit should be granted")
	}
	if _, ok := limiter.tryAcquire("eweka"); ok {
		t.Fatal("third permit exceeds the cap and must be refused")
	}

	first()
	third, ok := limiter.tryAcquire("eweka")
	if !ok {
		t.Fatal("releasing a permit should free a slot")
	}

	// Releasing twice must not hand back a slot that was never taken.
	first()
	if _, ok := limiter.tryAcquire("eweka"); ok {
		t.Fatal("a double release must not inflate the cap")
	}
	second()
	third()
}

func TestLeaseLimiterLeavesUnlistedProvidersUncapped(t *testing.T) {
	limiter := newLeaseLimiter("stream-a", map[string]int{"eweka": 1})
	for i := 0; i < 50; i++ {
		if _, ok := limiter.tryAcquire("newshosting"); !ok {
			t.Fatalf("uncapped provider refused a permit on attempt %d", i+1)
		}
	}
}

func TestLeaseLimiterAcquireWaitsThenGivesUpWithContext(t *testing.T) {
	limiter := newLeaseLimiter("stream-a", map[string]int{"eweka": 1})
	held, ok := limiter.tryAcquire("eweka")
	if !ok {
		t.Fatal("expected the first permit")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, ok := limiter.acquire(ctx, "eweka"); ok {
		t.Fatal("acquire should have given up when the cap stayed full")
	}

	held()
	if _, ok := limiter.acquire(context.Background(), "eweka"); !ok {
		t.Fatal("acquire should succeed once a permit is free")
	}
}

func TestLeaseRegistrySharesOneBudgetPerLease(t *testing.T) {
	registry := newLeaseRegistry()
	limits := map[string]int{"eweka": 1}

	// Two subsets of the same stream must not each get their own allowance.
	first := registry.limiter("alice", limits)
	second := registry.limiter("alice", limits)
	if first != second {
		t.Fatal("the same lease key must resolve to one limiter")
	}
	if _, ok := first.tryAcquire("eweka"); !ok {
		t.Fatal("expected the first permit")
	}
	if _, ok := second.tryAcquire("eweka"); ok {
		t.Fatal("a second subset must draw on the same budget, not a fresh one")
	}

	// A different stream has its own budget.
	other := registry.limiter("bob", limits)
	if _, ok := other.tryAcquire("eweka"); !ok {
		t.Fatal("a different lease should have its own allowance")
	}
}

func TestLeaseRegistryIgnoresEmptyLeaseOrLimits(t *testing.T) {
	registry := newLeaseRegistry()
	if limiter := registry.limiter("", map[string]int{"eweka": 1}); limiter != nil {
		t.Fatal("an empty lease key must not create a limiter")
	}
	if limiter := registry.limiter("alice", nil); limiter != nil {
		t.Fatal("no limits means no limiter")
	}
}

func TestLeaseLimiterAppliesRaisedLimit(t *testing.T) {
	registry := newLeaseRegistry()
	limiter := registry.limiter("alice", map[string]int{"eweka": 1})
	if _, ok := limiter.tryAcquire("eweka"); !ok {
		t.Fatal("expected the first permit")
	}
	if _, ok := limiter.tryAcquire("eweka"); ok {
		t.Fatal("cap of 1 should refuse a second permit")
	}

	// Saving a higher cap must take effect for connections taken from then on.
	registry.limiter("alice", map[string]int{"eweka": 3})
	for i := 0; i < 3; i++ {
		if _, ok := limiter.tryAcquire("eweka"); !ok {
			t.Fatalf("raised cap should grant permit %d", i+1)
		}
	}
}

// A lease-scoped subset charges the bytes it pulls to its stream, and the parent
// pool sees them: the registry is shared, the way provider article stats are.
func TestSubsetForLeaseAttributesFetchedBytes(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 0)

	lease := p.SubsetForLease("stream01", []string{"test"}, nil)
	if lease == nil {
		t.Fatal("SubsetForLease returned nil")
	}
	if _, err := lease.FetchSegment(context.Background(), &nzb.Segment{ID: "a@x"}, []string{"alt.test"}); err != nil {
		t.Fatalf("fetch segment: %v", err)
	}

	charged := p.streamByteTotals()["stream01"]
	if charged <= 0 {
		t.Fatalf("stream01 charged %d bytes, want > 0", charged)
	}
	if got := lease.streamByteTotals()["stream01"]; got != charged {
		t.Fatalf("subset sees %d bytes, parent sees %d; registry is not shared", got, charged)
	}
}

// An unattributed view (no lease) must not charge anyone.
func TestSubsetWithoutLeaseChargesNoStream(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 0)

	plain := p.Subset([]string{"test"})
	if _, err := plain.FetchSegment(context.Background(), &nzb.Segment{ID: "b@x"}, []string{"alt.test"}); err != nil {
		t.Fatalf("fetch segment: %v", err)
	}

	if got := p.streamByteTotals(); len(got) != 0 {
		t.Fatalf("StreamBytes() = %v, want empty", got)
	}
}

// A subset derived from a lease keeps the attribution: read-ahead and failover
// both re-derive views from the fetcher a session was handed.
func TestSubsetOfLeaseKeepsAttribution(t *testing.T) {
	s := startPipelinePoolServer(t, nil)
	p := newPipelineTestPool(t, s, 0)

	derived := p.SubsetForLease("stream01", []string{"test"}, nil).Subset([]string{"test"})
	if _, err := derived.FetchSegment(context.Background(), &nzb.Segment{ID: "c@x"}, []string{"alt.test"}); err != nil {
		t.Fatalf("fetch segment: %v", err)
	}

	if got := p.streamByteTotals()["stream01"]; got <= 0 {
		t.Fatalf("stream01 charged %d bytes through a derived subset, want > 0", got)
	}
}
