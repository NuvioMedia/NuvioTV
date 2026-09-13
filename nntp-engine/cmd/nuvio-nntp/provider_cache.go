package main

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"sync"
	"time"

	"streamnzb/pkg/usenet/nntp"
	usenetpool "streamnzb/pkg/usenet/pool"
)

const providerClientIdleTTL = 2 * time.Minute

type cachedProviderClients struct {
	clients            []*nntp.ClientPool
	ready              chan struct{}
	err                error
	refs               int
	lastUsed           time.Time
	invalid            bool
	closed             bool
	validationDuration time.Duration
}

type providerClientCache struct {
	mu      sync.Mutex
	entries map[[sha256.Size]byte]*cachedProviderClients
	closed  bool
}

type providerClientLease struct {
	cache  *providerClientCache
	key    [sha256.Size]byte
	entry  *cachedProviderClients
	reused bool
	once   sync.Once
}

func newProviderClientCache() *providerClientCache {
	return &providerClientCache{entries: make(map[[sha256.Size]byte]*cachedProviderClients)}
}

func providerCacheKey(providers []providerEndpoint) [sha256.Size]byte {
	hash := sha256.New()
	var size [8]byte
	writeString := func(value string) {
		binary.LittleEndian.PutUint64(size[:], uint64(len(value)))
		_, _ = hash.Write(size[:])
		_, _ = hash.Write([]byte(value))
	}
	for _, provider := range providers {
		writeString(provider.host)
		writeString(provider.username)
		writeString(provider.password)
		binary.LittleEndian.PutUint64(size[:], uint64(provider.port))
		_, _ = hash.Write(size[:])
		binary.LittleEndian.PutUint64(size[:], uint64(provider.connections))
		_, _ = hash.Write(size[:])
		if provider.useTLS {
			_, _ = hash.Write([]byte{1})
		} else {
			_, _ = hash.Write([]byte{0})
		}
	}
	var key [sha256.Size]byte
	copy(key[:], hash.Sum(nil))
	return key
}

func (c *providerClientCache) acquire(providers []providerEndpoint) (*providerClientLease, error) {
	key := providerCacheKey(providers)
	now := time.Now()
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, fmt.Errorf("NNTP provider cache is closed")
	}
	if entry := c.entries[key]; entry != nil && !entry.invalid {
		entry.refs++
		entry.lastUsed = now
		c.mu.Unlock()
		return &providerClientLease{cache: c, key: key, entry: entry, reused: true}, nil
	}

	clients := make([]*nntp.ClientPool, 0, len(providers))
	for _, provider := range providers {
		clients = append(clients, nntp.NewClientPool(
			provider.host,
			provider.port,
			provider.useTLS,
			provider.username,
			provider.password,
			provider.connections,
		))
	}
	entry := &cachedProviderClients{
		clients:  clients,
		ready:    make(chan struct{}),
		refs:     1,
		lastUsed: now,
	}
	c.entries[key] = entry
	c.mu.Unlock()

	go c.validate(key, entry)
	return &providerClientLease{cache: c, key: key, entry: entry}, nil
}

func (c *providerClientCache) validate(key [sha256.Size]byte, entry *cachedProviderClients) {
	started := time.Now()
	err := validateProviderClients(entry.clients)
	c.mu.Lock()
	entry.err = err
	entry.validationDuration = time.Since(started)
	entry.invalid = err != nil
	if entry.invalid && c.entries[key] == entry {
		delete(c.entries, key)
	}
	shouldClose := entry.invalid && entry.refs == 0 && !entry.closed
	if shouldClose {
		entry.closed = true
	}
	close(entry.ready)
	c.mu.Unlock()
	if shouldClose {
		shutdownClients(entry.clients)
	}
}

func (l *providerClientLease) validationDuration() time.Duration {
	if l == nil || l.cache == nil || l.entry == nil {
		return 0
	}
	l.cache.mu.Lock()
	duration := l.entry.validationDuration
	l.cache.mu.Unlock()
	return duration
}

func (l *providerClientLease) awaitReady() error {
	if l == nil || l.entry == nil {
		return fmt.Errorf("NNTP provider lease is unavailable")
	}
	<-l.entry.ready
	l.cache.mu.Lock()
	err := l.entry.err
	l.cache.mu.Unlock()
	return err
}

func (l *providerClientLease) clients() []*nntp.ClientPool {
	if l == nil || l.entry == nil {
		return nil
	}
	return l.entry.clients
}

func (l *providerClientLease) configs() []usenetpool.ProviderConfig {
	clients := l.clients()
	configs := make([]usenetpool.ProviderConfig, 0, len(clients))
	for index, client := range clients {
		configs = append(configs, usenetpool.ProviderConfig{
			ID:         fmt.Sprintf("provider-%d", index+1),
			Priority:   index,
			IsBackup:   index > 0,
			ClientPool: client,
		})
	}
	return configs
}

func (l *providerClientLease) release() {
	if l == nil || l.cache == nil || l.entry == nil {
		return
	}
	l.once.Do(func() {
		l.cache.mu.Lock()
		if l.entry.refs > 0 {
			l.entry.refs--
		}
		l.entry.lastUsed = time.Now()
		shouldClose := l.entry.invalid && l.entry.refs == 0 && !l.entry.closed
		if shouldClose {
			l.entry.closed = true
		}
		l.cache.mu.Unlock()
		if shouldClose {
			shutdownClients(l.entry.clients)
		}
	})
}

func (c *providerClientCache) cleanupIdle(now time.Time) {
	var expired [][]*nntp.ClientPool
	c.mu.Lock()
	for key, entry := range c.entries {
		if entry.refs == 0 && now.Sub(entry.lastUsed) >= providerClientIdleTTL {
			delete(c.entries, key)
			if !entry.closed {
				entry.closed = true
				expired = append(expired, entry.clients)
			}
		}
	}
	c.mu.Unlock()
	for _, clients := range expired {
		shutdownClients(clients)
	}
}

func (c *providerClientCache) closeAll() {
	var clients [][]*nntp.ClientPool
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	for key, entry := range c.entries {
		delete(c.entries, key)
		if !entry.closed {
			entry.closed = true
			clients = append(clients, entry.clients)
		}
	}
	c.mu.Unlock()
	for _, pools := range clients {
		shutdownClients(pools)
	}
}
