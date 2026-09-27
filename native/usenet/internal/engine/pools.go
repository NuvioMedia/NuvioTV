package engine

import (
	"context"
	"errors"
	"io"
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
}

var errProviderAuthentication = errors.New("Usenet provider authentication failed; check the addon credentials")
var errProviderQuota = errors.New("Usenet provider download quota exceeded")

type sharedProvider struct {
	client *nntppool.Client
	refs   int
}
type providerPools struct {
	mu      sync.Mutex
	entries map[providerKey]*sharedProvider
}
type poolLease struct {
	owner   *providerPools
	keys    []providerKey
	clients []*nntppool.Client
	next    atomic.Uint64
	once    sync.Once
}

func (p *providerPools) acquire(ctx context.Context, providers []nntppool.Provider) (*poolLease, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.entries == nil {
		p.entries = make(map[providerKey]*sharedProvider)
	}
	l := &poolLease{owner: p}
	seen := make(map[providerKey]bool)
	for _, provider := range providers {
		key := providerKey{provider.Host, provider.Auth.Username, provider.Auth.Password, provider.TLSConfig != nil}
		if seen[key] {
			continue
		}
		seen[key] = true
		entry := p.entries[key]
		if entry == nil {
			client, err := nntppool.NewClient(ctx, []nntppool.Provider{provider}, nntppool.WithStatProbe(false))
			if err != nil {
				p.releaseLocked(l.keys)
				return nil, err
			}
			entry = &sharedProvider{client: client}
			p.entries[key] = entry
		}
		entry.refs++
		l.keys = append(l.keys, key)
		l.clients = append(l.clients, entry.client)
	}
	return l, nil
}

func (p *providerPools) releaseLocked(keys []providerKey) {
	for _, key := range keys {
		entry := p.entries[key]
		entry.refs--
		if entry.refs == 0 {
			// Finish closing before a new lease can dial the same account.
			entry.client.Close()
			delete(p.entries, key)
		}
	}
}
func (l *poolLease) Close() error {
	l.once.Do(func() {
		l.owner.mu.Lock()
		defer l.owner.mu.Unlock()
		l.owner.releaseLocked(l.keys)
	})
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
func (l *poolLease) body(ctx context.Context, id string, out io.Writer, priority bool, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	start := int(l.next.Add(1)-1) % len(l.clients)
	var failures []error
	allMissing := true
	allAuth, allQuota := true, true
	for i := range l.clients {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		client := l.clients[(start+i)%len(l.clients)]
		writer := &providerWriter{w: out, meta: meta}
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
		failures = append(failures, err)
	}
	if allMissing {
		return nil, nntppool.ErrArticleNotFound
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
