package pool

import (
	"context"
	"strings"
	"sync"
)

// Per-stream connection caps.
//
// A cap stops one stream monopolizing a provider's connections; it does not
// reserve any. Two streams capped at 30 on a 50-connection account still
// contend for the same pool — the cap is a ceiling, not a floor.
//
// The limiter lives in a registry shared by a Pool and every Subset derived
// from it, for the same reason articleStatsRegistry does: Subset() is rebuilt
// on every request, so per-lease state stored in the subset itself would reset
// constantly. The registry is keyed by lease, so all of a stream's subsets draw
// on one budget.

// leaseLimiter holds one semaphore per provider for a single lease.
type leaseLimiter struct {
	mu       sync.Mutex
	permits  map[string]chan struct{}
	limits   map[string]int
	leaseKey string
}

func newLeaseLimiter(leaseKey string, limits map[string]int) *leaseLimiter {
	copied := make(map[string]int, len(limits))
	for providerID, limit := range limits {
		if limit > 0 {
			copied[strings.TrimSpace(providerID)] = limit
		}
	}
	return &leaseLimiter{
		permits:  make(map[string]chan struct{}, len(copied)),
		limits:   copied,
		leaseKey: leaseKey,
	}
}

// semaphore returns the permit channel for providerID, or nil when the provider
// is uncapped for this lease.
func (l *leaseLimiter) semaphore(providerID string) chan struct{} {
	l.mu.Lock()
	defer l.mu.Unlock()
	limit, capped := l.limits[providerID]
	if !capped || limit <= 0 {
		return nil
	}
	if sem, ok := l.permits[providerID]; ok {
		return sem
	}
	sem := make(chan struct{}, limit)
	l.permits[providerID] = sem
	return sem
}

// tryAcquire takes a permit without waiting. The returned release is nil when
// the provider is uncapped, and callers must treat a false result as "this
// provider is at its cap right now", not as an error.
func (l *leaseLimiter) tryAcquire(providerID string) (release func(), ok bool) {
	sem := l.semaphore(providerID)
	if sem == nil {
		return func() {}, true
	}
	select {
	case sem <- struct{}{}:
		return releaseOnce(sem), true
	default:
		return nil, false
	}
}

// acquire waits for a permit, giving up only when ctx ends.
func (l *leaseLimiter) acquire(ctx context.Context, providerID string) (release func(), ok bool) {
	sem := l.semaphore(providerID)
	if sem == nil {
		return func() {}, true
	}
	select {
	case sem <- struct{}{}:
		return releaseOnce(sem), true
	case <-ctx.Done():
		return nil, false
	}
}

func releaseOnce(sem chan struct{}) func() {
	var once sync.Once
	return func() {
		once.Do(func() { <-sem })
	}
}

// leaseRegistry keeps one limiter per lease, shared by a Pool and its subsets.
type leaseRegistry struct {
	mu     sync.Mutex
	leases map[string]*leaseLimiter
}

func newLeaseRegistry() *leaseRegistry {
	return &leaseRegistry{leases: make(map[string]*leaseLimiter)}
}

// limiter returns the lease's limiter, creating it on first use. Later calls
// reuse the existing semaphores so in-flight permits stay accounted for; the
// limits are refreshed so a config change takes effect for connections taken
// from then on.
func (r *leaseRegistry) limiter(leaseKey string, limits map[string]int) *leaseLimiter {
	leaseKey = strings.TrimSpace(leaseKey)
	if r == nil || leaseKey == "" || len(limits) == 0 {
		return nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	existing, ok := r.leases[leaseKey]
	if !ok {
		existing = newLeaseLimiter(leaseKey, limits)
		r.leases[leaseKey] = existing
		return existing
	}
	existing.applyLimits(limits)
	return existing
}

// applyLimits updates the caps in place. A provider whose limit changed drops
// its semaphore so the next acquisition builds one at the new size; permits
// already held release into the old channel and are simply discarded, which
// costs a brief overshoot rather than a leak.
func (l *leaseLimiter) applyLimits(limits map[string]int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	next := make(map[string]int, len(limits))
	for providerID, limit := range limits {
		if limit > 0 {
			next[strings.TrimSpace(providerID)] = limit
		}
	}
	for providerID, limit := range next {
		if l.limits[providerID] != limit {
			delete(l.permits, providerID)
		}
	}
	for providerID := range l.limits {
		if _, still := next[providerID]; !still {
			delete(l.permits, providerID)
		}
	}
	l.limits = next
}

// limitFor reports the per-lease connection cap for providerID, or 0 when the
// provider is uncapped for this lease (or there is no lease at all).
func (l *leaseLimiter) limitFor(providerID string) int {
	if l == nil {
		return 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.limits[strings.TrimSpace(providerID)]
}
