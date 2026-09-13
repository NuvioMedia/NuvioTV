package pool

import (
	"context"
	"fmt"
	"sync"
	"testing"
)

func TestShouldCacheFetchedSegment(t *testing.T) {
	if !shouldCacheFetchedSegment(nil) {
		t.Fatal("nil context should allow cache writes")
	}
	if !shouldCacheFetchedSegment(context.Background()) {
		t.Fatal("active context should allow cache writes")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if shouldCacheFetchedSegment(ctx) {
		t.Fatal("canceled context should block cache writes")
	}
}

func TestNewPool_NoProviders(t *testing.T) {
	_, err := NewPool(&Config{Providers: nil})
	if err != ErrNoProvidersConfigured {
		t.Fatalf("expected ErrNoProvidersConfigured, got %v", err)
	}
	_, err = NewPool(&Config{Providers: []ProviderConfig{}})
	if err != ErrNoProvidersConfigured {
		t.Fatalf("expected ErrNoProvidersConfigured, got %v", err)
	}
}

func TestNewPool_NilCacheUsesNoop(t *testing.T) {

	c := NoopSegmentCache()
	data, ok := c.Get("any")
	if ok || data.Size != 0 || len(data.Body) != 0 {
		t.Fatalf("noop cache should never return hit")
	}
	c.Set("any", SegmentData{Body: []byte("x"), Size: 1})
	data, ok = c.Get("any")
	if ok {
		t.Fatalf("noop cache should not persist")
	}
}

func TestMemorySegmentCache_LRU(t *testing.T) {
	c := NewMemorySegmentCacheWithCapacity(2)
	c.Set("id1", SegmentData{Body: []byte("v1"), Size: 2})
	c.Set("id2", SegmentData{Body: []byte("v2"), Size: 2})

	_, ok := c.Get("id1")
	if !ok {
		t.Fatal("expected id1 to be present")
	}

	// Set id3, should evict id2 because id1 was accessed last
	c.Set("id3", SegmentData{Body: []byte("v3"), Size: 2})

	_, ok = c.Get("id1")
	if !ok {
		t.Fatal("expected id1 to be present (accessed last)")
	}
	_, ok = c.Get("id2")
	if ok {
		t.Fatal("expected id2 to be evicted")
	}
	_, ok = c.Get("id3")
	if !ok {
		t.Fatal("expected id3 to be present")
	}
}
func TestMemorySegmentCache_BudgetLRU(t *testing.T) {
	// Budget for 4 bytes
	budget := &SegmentCacheBudget{maxBytes: 4}

	c := NewMemorySegmentCacheWithBudget(budget)
	c.Set("id1", SegmentData{Body: []byte("v1"), Size: 2})
	c.Set("id2", SegmentData{Body: []byte("v2"), Size: 2})

	_, ok := c.Get("id1")
	if !ok {
		t.Fatal("expected id1 to be present")
	}

	// Set id3 (2 bytes), should evict id2 (oldest) to fit
	c.Set("id3", SegmentData{Body: []byte("v3"), Size: 2})

	_, ok = c.Get("id1")
	if !ok {
		t.Fatal("expected id1 to be present (accessed last)")
	}
	_, ok = c.Get("id2")
	if ok {
		t.Fatal("expected id2 to be evicted")
	}
	_, ok = c.Get("id3")
	if !ok {
		t.Fatal("expected id3 to be present")
	}
}

func TestMemorySegmentCache_Stats(t *testing.T) {
	budget := &SegmentCacheBudget{maxBytes: 8}
	c := NewMemorySegmentCacheWithBudget(budget)
	c.Set("id1", SegmentData{Body: []byte("ab"), Size: 2})
	c.Set("id2", SegmentData{Body: []byte("cdef"), Size: 4})

	stats := cacheStats(c)
	if stats.Entries != 2 {
		t.Fatalf("expected 2 cache entries, got %d", stats.Entries)
	}
	if stats.Bytes != 6 {
		t.Fatalf("expected 6 cached bytes, got %d", stats.Bytes)
	}
	if stats.BudgetCurrent != 6 {
		t.Fatalf("expected current budget 6, got %d", stats.BudgetCurrent)
	}
	if stats.BudgetMax != 8 {
		t.Fatalf("expected max budget 8, got %d", stats.BudgetMax)
	}

	c.Purge()
	stats = cacheStats(c)
	if stats.Entries != 0 || stats.Bytes != 0 || stats.BudgetCurrent != 0 {
		t.Fatalf("expected empty cache after purge, got %+v", stats)
	}
}

func TestProviderArticleStatsCounters(t *testing.T) {
	p, err := NewPool(&Config{
		Providers: []ProviderConfig{
			{ID: "provider-a", Priority: 0},
			{ID: "provider-b", Priority: 1},
		},
	})
	if err != nil {
		t.Fatalf("NewPool: %v", err)
	}

	p.recordArticleResult("provider-a", true)
	p.recordArticleResult("provider-a", true)
	p.recordArticleResult("provider-a", false)
	p.recordArticleResult("provider-b", false)
	p.recordArticleResult("provider-b", false)

	stats := p.ProviderArticleStats()
	if len(stats) != 2 {
		t.Fatalf("ProviderArticleStats len = %d, want 2", len(stats))
	}

	if stats[0].ProviderID != "provider-a" || stats[0].AvailableCount != 2 || stats[0].UnavailableCount != 1 {
		t.Fatalf("provider-a stats = %+v", stats[0])
	}
	if stats[1].ProviderID != "provider-b" || stats[1].AvailableCount != 0 || stats[1].UnavailableCount != 2 {
		t.Fatalf("provider-b stats = %+v", stats[1])
	}
}

// Regression: Subset must share the parent's article-stats registry safely.
// Before the registry was self-locking, parent and subset guarded the same map
// with different mutexes, allowing concurrent map writes to crash the process.
func TestSubsetSharesArticleStatsSafely(t *testing.T) {
	p, err := NewPool(&Config{
		Providers: []ProviderConfig{
			{ID: "provider-a", Priority: 0},
			{ID: "provider-b", Priority: 1},
		},
	})
	if err != nil {
		t.Fatalf("NewPool: %v", err)
	}
	sub := p.Subset([]string{"provider-a"})
	if sub == nil {
		t.Fatal("Subset returned nil")
	}

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(2)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				// Unknown provider IDs force map inserts on both pools.
				p.recordArticleResult(fmt.Sprintf("dyn-%d-%d", i, j), true)
			}
		}(i)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				sub.recordArticleResult(fmt.Sprintf("dyn-sub-%d-%d", i, j), false)
			}
		}(i)
	}
	wg.Wait()

	// Counts recorded via the subset must be visible on the parent: one registry.
	sub.recordArticleResult("provider-a", true)
	for _, st := range p.ProviderArticleStats() {
		if st.ProviderID == "provider-a" && st.AvailableCount == 1 {
			return
		}
	}
	t.Fatal("subset-recorded stats not visible via parent pool")
}
