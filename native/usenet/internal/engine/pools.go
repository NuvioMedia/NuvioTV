package engine

import (
	"context"
	"errors"
	"io"
	"sort"
	"sync"
	"sync/atomic"

	"github.com/javi11/nntppool/v4"
)

// Sessions share sockets, not article buffers or cancellation. Keying each
// provider separately also covers reordered and partially overlapping lists.
// Tuning changes take effect when the last owner releases that provider.
type providerKey struct {
	host, username, password string
	tls                      bool
	allowPrivate             bool
}

var errProviderAuthentication = errors.New("Usenet provider authentication failed; check the addon credentials")
var errProviderQuota = errors.New("Usenet provider download quota exceeded")

type sharedProvider struct {
	client *nntppool.Client
	refs   int
	ready  chan struct{} // Non-nil while creating or closing this account.
}
type providerPools struct {
	mu      sync.Mutex
	entries map[providerKey]*sharedProvider
}
type poolLease struct {
	trace        *startupTrace
	requestOnce  sync.Once
	metadataOnce sync.Once
	owner        *providerPools
	keys         []providerKey
	clients      []*nntppool.Client
	tiers        []int // Ascending priority of each client; equal tiers share load.
	next         atomic.Uint64
	once         sync.Once
}

func (p *providerPools) acquire(ctx context.Context, providers []nntppool.Provider, allowPrivate ...bool) (*poolLease, error) {
	return p.acquireForRequest(ctx, ctx, providers, nil, len(allowPrivate) > 0 && allowPrivate[0])
}

// priorities parallels providers (missing entries are 0). The first listing of
// a duplicate account decides its tier.
func (p *providerPools) acquireForRequest(lifetime, wait context.Context, providers []nntppool.Provider, priorities []int, allowPrivate bool) (*poolLease, error) {
	l := &poolLease{owner: p}
	seen := make(map[providerKey]bool)
	for i, provider := range providers {
		key := providerKey{provider.Host, provider.Auth.Username, provider.Auth.Password, provider.TLSConfig != nil, allowPrivate}
		if seen[key] {
			continue
		}
		seen[key] = true
		entry, err := p.acquireProvider(lifetime, wait, key, provider)
		if err != nil {
			l.Close()
			return nil, err
		}
		l.keys = append(l.keys, key)
		l.clients = append(l.clients, entry.client)
		tier := 0
		if i < len(priorities) {
			tier = priorities[i]
		}
		l.tiers = append(l.tiers, tier)
	}
	// Stable: list order remains the failover order within a tier.
	order := make([]int, len(l.clients))
	for i := range order {
		order[i] = i
	}
	sort.SliceStable(order, func(a, b int) bool { return l.tiers[order[a]] < l.tiers[order[b]] })
	clients, tiers := make([]*nntppool.Client, len(order)), make([]int, len(order))
	for i, j := range order {
		clients[i], tiers[i] = l.clients[j], l.tiers[j]
	}
	l.clients, l.tiers = clients, tiers
	return l, nil
}

func (p *providerPools) acquireProvider(lifetime, wait context.Context, key providerKey, provider nntppool.Provider) (*sharedProvider, error) {
	for {
		if err := wait.Err(); err != nil {
			return nil, err
		}
		p.mu.Lock()
		if p.entries == nil {
			p.entries = make(map[providerKey]*sharedProvider)
		}
		entry := p.entries[key]
		if entry != nil && entry.ready != nil {
			ready := entry.ready
			p.mu.Unlock()
			select {
			case <-ready:
				continue
			case <-wait.Done():
				return nil, wait.Err()
			}
		}
		if entry != nil {
			entry.refs++
			p.mu.Unlock()
			return entry, nil
		}
		entry = &sharedProvider{ready: make(chan struct{}), refs: 1}
		p.entries[key] = entry
		p.mu.Unlock()
		client, err := nntppool.NewClient(lifetime, []nntppool.Provider{provider}, nntppool.WithStatProbe(false))
		p.mu.Lock()
		entry.client = client
		close(entry.ready)
		entry.ready = nil
		if err != nil {
			delete(p.entries, key)
		}
		p.mu.Unlock()
		return entry, err
	}
}

func (p *providerPools) release(keys []providerKey) {
	closing := make(map[providerKey]*sharedProvider)
	p.mu.Lock()
	for _, key := range keys {
		entry := p.entries[key]
		entry.refs--
		if entry.refs == 0 {
			// A per-account barrier prevents duplicate sockets while letting
			// unrelated accounts acquire/release during slow network shutdown.
			entry.ready = make(chan struct{})
			closing[key] = entry
		}
	}
	p.mu.Unlock()
	for key, entry := range closing {
		entry.client.Close()
		p.mu.Lock()
		delete(p.entries, key)
		close(entry.ready)
		p.mu.Unlock()
	}
}
func (l *poolLease) Close() error {
	l.once.Do(func() { l.owner.release(l.keys) })
	return nil
}

// A pool may return on cancellation before its wire drain finishes. Seal each
// provider's callbacks before considering another provider, and never replay
// into the caller after publishing metadata or bytes. Store owns partial replay.
type providerWriter struct {
	mu                sync.Mutex
	w                 io.Writer
	meta              []func(nntppool.YEncMeta)
	sealed, committed bool
}

func (w *providerWriter) Write(b []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.sealed {
		return len(b), nil
	}
	w.committed = true
	return w.w.Write(b)
}
func (w *providerWriter) metadata(m nntppool.YEncMeta) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.sealed {
		return
	}
	w.committed = true
	for _, f := range w.meta {
		f(m)
	}
}
func (l *poolLease) firstClient() int { return l.pick(0, l.tierEnd(0)) }

// tierEnd returns the end of the priority tier that starts at from.
func (l *poolLease) tierEnd(from int) int {
	end := from + 1
	for end < len(l.clients) && l.tiers[end] == l.tiers[from] {
		end++
	}
	return end
}

// pick chooses where traffic starts within clients[from:to], weighted by each
// provider's available connection capacity.
func (l *poolLease) pick(from, to int) int {
	if to-from <= 1 {
		return from
	}
	cumulative := make([]int, to-from)
	total := 0
	for i, client := range l.clients[from:to] {
		for _, provider := range client.Stats().Providers {
			if !provider.QuotaExceeded {
				total += max(1, provider.AvailableSlots)
			}
		}
		cumulative[i] = total
	}
	if total == 0 {
		return from
	}
	slot := int(l.next.Add(1) % uint64(total))
	return from + sort.SearchInts(cumulative, slot+1)
}

// order lists the clients to try for one article: each tier in priority order,
// starting inside a tier at a capacity-weighted pick.
func (l *poolLease) order() []int {
	order := make([]int, 0, len(l.clients))
	for from := 0; from < len(l.clients); {
		to := l.tierEnd(from)
		start := l.pick(from, to)
		for i := range to - from {
			order = append(order, from+(start-from+i)%(to-from))
		}
		from = to
	}
	return order
}

func (l *poolLease) body(ctx context.Context, id string, out io.Writer, priority bool, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	var failures []error
	allMissing := true
	allAuth, allQuota := true, true
	allPrivate := true
	for _, index := range l.order() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		client := l.clients[index]
		l.requestOnce.Do(func() { l.trace.mark("first_nntp_request") })
		observedMeta := func(m nntppool.YEncMeta) {
			l.metadataOnce.Do(func() { l.trace.mark("first_nntp_metadata") })
			for _, callback := range meta {
				callback(m)
			}
		}
		writer := &providerWriter{w: out, meta: []func(nntppool.YEncMeta){observedMeta}}
		var body *nntppool.ArticleBody
		var err error
		if priority {
			body, err = client.BodyStreamPriority(ctx, id, writer, writer.metadata)
		} else {
			body, err = client.BodyStream(ctx, id, writer, writer.metadata)
		}
		writer.mu.Lock()
		writer.sealed = true
		committed := writer.committed
		writer.mu.Unlock()
		if err == nil || committed {
			return body, err
		}
		allMissing = allMissing && errors.Is(err, nntppool.ErrArticleNotFound)
		allAuth = allAuth && (errors.Is(err, nntppool.ErrAuthRejected) || errors.Is(err, nntppool.ErrAuthRequired))
		allQuota = allQuota && errors.Is(err, nntppool.ErrQuotaExceeded)
		allPrivate = allPrivate && errors.Is(err, errPrivateNetwork)
		failures = append(failures, err)
	}
	if allMissing {
		return nil, nntppool.ErrArticleNotFound
	}
	if allPrivate {
		return nil, errPrivateNetwork
	}
	if allAuth {
		return nil, errProviderAuthentication
	}
	if allQuota {
		return nil, errProviderQuota
	}
	// Do not label a mixed transient failure as a permanent article miss.
	var transient []error
	for _, err := range failures {
		if !errors.Is(err, nntppool.ErrArticleNotFound) {
			transient = append(transient, err)
		}
	}
	return nil, errors.Join(transient...)
}
func (l *poolLease) BodyStream(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	return l.body(ctx, id, w, false, meta...)
}
func (l *poolLease) BodyStreamPriority(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	return l.body(ctx, id, w, true, meta...)
}
