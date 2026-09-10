package pool

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/decode"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"

	"golang.org/x/sync/singleflight"
)

type countReader struct {
	io.Reader
	n int64
}

func (c *countReader) Read(p []byte) (int, error) {
	n, err := c.Reader.Read(p)
	c.n += int64(n)
	return n, err
}

var (
	ErrNoProvidersConfigured = errors.New("usenet/pool: no providers configured")
	ErrNoProvidersAvailable  = errors.New("usenet/pool: no providers available")
)

// isArticleNotFound reports whether err indicates 430 No Such Article (article missing on server).
func isArticleNotFound(err error) bool {
	return nntp.IsArticleNotFound(err)
}

// IsArticleNotFoundError reports whether err indicates 430 No Such Article.
func IsArticleNotFoundError(err error) bool {
	return nntp.IsArticleNotFound(err)
}

func shouldCacheFetchedSegment(ctx context.Context) bool {
	return ctx == nil || ctx.Err() == nil
}

type ProviderConfig struct {
	ID         string
	Priority   int
	IsBackup   bool
	ClientPool *nntp.ClientPool

	// PipelineDepth is how many article requests this provider may have
	// outstanding on one connection. Zero inherits Config.PipelineDepth; 1 or
	// negative switches pipelining off for this provider alone. The useful
	// depth follows the round-trip time to that particular server, so a local
	// primary and an overseas backup rarely want the same number.
	PipelineDepth int
}

type Config struct {
	Providers                  []ProviderConfig
	SegmentCache               SegmentCache
	PermanentMissingMaxEntries int

	// PipelineDepth is the deployment default for how many BODY commands a
	// read-ahead batch keeps outstanding on one connection, used by every
	// provider that does not set its own. Zero takes DefaultPipelineDepth; 1 or
	// negative disables pipelining. See pipeline.go.
	PipelineDepth int
}

type Pool struct {
	providers            []ProviderConfig
	cache                SegmentCache
	sf                   *singleflight.Group
	missing              *permanentMissingSegments
	providerSig          string
	articleStats         *articleStatsRegistry
	pipelineDepth        int
	consecutive430s      map[string]int
	consecutiveSuccesses map[string]int
	cooloffUntil         map[string]time.Time
	mu                   sync.RWMutex
	activeFetches        atomic.Int64

	// leases is shared with every Subset; limiter is the one this view draws
	// on, nil on the parent and on uncapped subsets.
	leases  *leaseRegistry
	limiter *leaseLimiter

	// streamBytes is shared with every Subset; leaseKey names the stream this
	// view fetches for, empty on the parent and on unattributed subsets.
	streamBytes *streamByteRegistry
	leaseKey    string
}

// streamByteRegistry tracks what each stream pulled off the providers, behind its
// own lock. A parent Pool and every Subset derived from it share one registry, so
// the counters must never be guarded by any individual Pool's mutex.
type streamByteRegistry struct {
	mu      sync.RWMutex
	byLease map[string]*streamByteCounter
}

// streamByteCounter is one stream's byte total plus the meter that turns it into a
// rate. It meters through nntp.SpeedMeter, the same type the provider pools use
// for total speed, so a stream's line and the total carry identical smoothing and
// share one timebase — otherwise the two disagree on every quiet or bursty tick.
type streamByteCounter struct {
	total atomic.Int64
	speed nntp.SpeedMeter
}

func newStreamByteRegistry() *streamByteRegistry {
	return &streamByteRegistry{byLease: make(map[string]*streamByteCounter)}
}

func (r *streamByteRegistry) counter(leaseKey string) *streamByteCounter {
	r.mu.RLock()
	counter := r.byLease[leaseKey]
	r.mu.RUnlock()
	if counter != nil {
		return counter
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if counter = r.byLease[leaseKey]; counter == nil {
		counter = &streamByteCounter{}
		r.byLease[leaseKey] = counter
	}
	return counter
}

func (r *streamByteRegistry) add(leaseKey string, n int64) {
	if r == nil || leaseKey == "" || n <= 0 {
		return
	}
	r.counter(leaseKey).total.Add(n)
}

func (r *streamByteRegistry) snapshot() map[string]int64 {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make(map[string]int64, len(r.byLease))
	for leaseKey, counter := range r.byLease {
		out[leaseKey] = counter.total.Load()
	}
	return out
}

// speeds samples every stream's meter. Stateful, like ClientPool.GetSpeed: each
// call closes the window the next one opens, so only the stats tick may call it.
func (r *streamByteRegistry) speeds() map[string]float64 {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	counters := make(map[string]*streamByteCounter, len(r.byLease))
	for leaseKey, counter := range r.byLease {
		counters[leaseKey] = counter
	}
	r.mu.RUnlock()

	out := make(map[string]float64, len(counters))
	for leaseKey, counter := range counters {
		out[leaseKey] = counter.speed.Rate(counter.total.Load())
	}
	return out
}

const (
	// providerCooloff430Threshold: consecutive 430s after which a provider enters
	// a cooloff window — it is skipped in the parallel first-segment race instead
	// of paying a wasted round-trip on every new volume of a release it does not
	// carry. getConnection's demote-to-pass-2 behavior is unchanged.
	providerCooloff430Threshold = 5
	// providerCooloffDuration: how long the provider is skipped before one probe
	// attempt is allowed again (which re-arms the cooloff if it 430s again).
	providerCooloffDuration = 60 * time.Second
	// statHedgeDelay: how long StatSegment waits on the leading provider before
	// consulting the rest anyway. Short enough that a stalled provider does not
	// hold up validation, long enough that a healthy one answers alone.
	statHedgeDelay = 150 * time.Millisecond
	// statConcurrencyDivisor / bounds: a STAT normally costs one connection on
	// the leading provider, so the per-caller budget is a fraction of that
	// provider's pool. Callers check several files at once, so this is
	// deliberately a small slice of capacity rather than most of it.
	statConcurrencyDivisor = 16
	minStatConcurrency     = 2
	maxStatConcurrency     = 8
)

type providerArticleCounter struct {
	host             string
	availableCount   atomic.Int64
	unavailableCount atomic.Int64
}

// articleStatsRegistry holds per-provider article counters behind its own lock.
// A parent Pool and every Subset derived from it share one registry, so the
// counters must never be guarded by any individual Pool's mutex.
type articleStatsRegistry struct {
	mu       sync.RWMutex
	counters map[string]*providerArticleCounter
}

func newArticleStatsRegistry(providers []ProviderConfig) *articleStatsRegistry {
	counters := make(map[string]*providerArticleCounter, len(providers))
	for i := range providers {
		providerID := strings.TrimSpace(providers[i].ID)
		if providerID == "" {
			continue
		}
		host := ""
		if providers[i].ClientPool != nil {
			host = providers[i].ClientPool.Host()
		}
		counters[providerID] = &providerArticleCounter{host: host}
	}
	return &articleStatsRegistry{counters: counters}
}

// counter returns the counter for providerID, creating it with host from
// resolveHost when absent.
func (r *articleStatsRegistry) counter(providerID string, resolveHost func(string) string) *providerArticleCounter {
	r.mu.RLock()
	c := r.counters[providerID]
	r.mu.RUnlock()
	if c != nil {
		return c
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if c = r.counters[providerID]; c != nil {
		return c
	}
	host := ""
	if resolveHost != nil {
		host = resolveHost(providerID)
	}
	c = &providerArticleCounter{host: host}
	r.counters[providerID] = c
	return c
}

func (r *articleStatsRegistry) snapshot() []ProviderArticleStats {
	r.mu.RLock()
	defer r.mu.RUnlock()
	ids := make([]string, 0, len(r.counters))
	for providerID := range r.counters {
		ids = append(ids, providerID)
	}
	sort.Strings(ids)
	out := make([]ProviderArticleStats, 0, len(ids))
	for _, providerID := range ids {
		counter := r.counters[providerID]
		if counter == nil {
			continue
		}
		out = append(out, ProviderArticleStats{
			ProviderID:       providerID,
			Host:             counter.host,
			AvailableCount:   counter.availableCount.Load(),
			UnavailableCount: counter.unavailableCount.Load(),
		})
	}
	return out
}

type ProviderArticleStats struct {
	ProviderID       string
	Host             string
	AvailableCount   int64
	UnavailableCount int64
}

type permanentMissingSegments struct {
	mu         sync.RWMutex
	m          map[string]time.Time
	maxEntries int
}

const defaultPermanentMissingMaxEntries = 50000

func newPermanentMissingSegments(maxEntries int) *permanentMissingSegments {
	if maxEntries <= 0 {
		maxEntries = defaultPermanentMissingMaxEntries
	}
	return &permanentMissingSegments{
		m:          make(map[string]time.Time),
		maxEntries: maxEntries,
	}
}

func (p *permanentMissingSegments) has(key string) bool {
	p.mu.RLock()
	_, ok := p.m[key]
	p.mu.RUnlock()
	return ok
}

func (p *permanentMissingSegments) delete(key string) {
	p.mu.Lock()
	delete(p.m, key)
	p.mu.Unlock()
}

func (p *permanentMissingSegments) add(key string) {
	now := time.Now()
	p.mu.Lock()
	p.m[key] = now
	for len(p.m) > p.maxEntries {
		var oldestKey string
		var oldest time.Time
		for k, insertedAt := range p.m {
			if oldestKey == "" || insertedAt.Before(oldest) {
				oldestKey = k
				oldest = insertedAt
			}
		}
		if oldestKey == "" {
			break
		}
		delete(p.m, oldestKey)
	}
	p.mu.Unlock()
}

func providerSignature(providers []ProviderConfig) string {
	ids := make([]string, 0, len(providers))
	for i := range providers {
		if id := strings.TrimSpace(providers[i].ID); id != "" {
			ids = append(ids, id)
		}
	}
	return strings.Join(ids, ",")
}

func (p *Pool) missingKey(messageID string) string {
	return p.providerSig + "|" + strings.TrimSpace(messageID)
}

func (p *Pool) isKnownMissing(messageID string) bool {
	if p == nil || p.missing == nil {
		return false
	}
	key := p.missingKey(messageID)
	return p.missing.has(key)
}

func (p *Pool) markKnownMissing(messageID string) {
	if p == nil || p.missing == nil {
		return
	}
	key := p.missingKey(messageID)
	p.missing.add(key)
}

func (p *Pool) clearKnownMissing(messageID string) {
	if p == nil || p.missing == nil {
		return
	}
	key := p.missingKey(messageID)
	p.missing.delete(key)
}

type attemptedProvidersError struct {
	err   error
	hosts []string
}

func (e *attemptedProvidersError) Error() string {
	if e == nil || e.err == nil {
		return ""
	}
	return e.err.Error()
}

func (e *attemptedProvidersError) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.err
}

// AttemptedProviderHosts returns the provider hosts that were actually attempted before err was returned.
func AttemptedProviderHosts(err error) []string {
	var attemptedErr *attemptedProvidersError
	if !errors.As(err, &attemptedErr) || attemptedErr == nil || len(attemptedErr.hosts) == 0 {
		return nil
	}
	return append([]string(nil), attemptedErr.hosts...)
}

func wrapAttemptedProviders(err error, hosts []string) error {
	if err == nil {
		return nil
	}
	hosts = appendUniqueHosts(nil, hosts...)
	if len(hosts) == 0 {
		return err
	}
	return &attemptedProvidersError{
		err:   err,
		hosts: hosts,
	}
}

func appendUniqueHosts(dst []string, hosts ...string) []string {
	for _, host := range hosts {
		host = strings.TrimSpace(host)
		if host == "" {
			continue
		}
		exists := false
		for _, existing := range dst {
			if existing == host {
				exists = true
				break
			}
		}
		if !exists {
			dst = append(dst, host)
		}
	}
	return dst
}

func (p *Pool) attemptedAllProviderIDs(attemptedIDs []string) bool {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if len(p.providers) == 0 {
		return false
	}
	seen := make(map[string]struct{}, len(attemptedIDs))
	for _, id := range attemptedIDs {
		id = strings.TrimSpace(id)
		if id == "" {
			continue
		}
		seen[id] = struct{}{}
	}
	if len(seen) == 0 {
		return false
	}
	for i := range p.providers {
		id := strings.TrimSpace(p.providers[i].ID)
		if id == "" {
			continue
		}
		if _, ok := seen[id]; !ok {
			return false
		}
	}
	return true
}

type PoolProviderTraceSnapshot struct {
	ID     string
	Host   string
	Total  int
	Idle   int
	Active int
}

type PoolTraceSnapshot struct {
	InFlightFetches int64
	Cache           CacheStats
	Providers       []PoolProviderTraceSnapshot
}

func (s PoolTraceSnapshot) CacheSummary() string {
	if s.Cache.BudgetMax > 0 {
		return fmt.Sprintf("entries=%d bytes=%d budget=%d/%d", s.Cache.Entries, s.Cache.Bytes, s.Cache.BudgetCurrent, s.Cache.BudgetMax)
	}
	return fmt.Sprintf("entries=%d bytes=%d", s.Cache.Entries, s.Cache.Bytes)
}

func (s PoolTraceSnapshot) ProviderSummary() string {
	if len(s.Providers) == 0 {
		return "none"
	}
	parts := make([]string, 0, len(s.Providers))
	for _, provider := range s.Providers {
		parts = append(parts, fmt.Sprintf("%s(host=%s total=%d idle=%d active=%d)", provider.ID, provider.Host, provider.Total, provider.Idle, provider.Active))
	}
	return strings.Join(parts, "; ")
}

func cacheStats(cache SegmentCache) CacheStats {
	if statser, ok := cache.(segmentCacheStatser); ok {
		return statser.Stats()
	}
	return CacheStats{}
}

// normalizeBackupTier clears the backup flag when every provider carries it. A
// backup tier with no primary above it has nothing to fall back from, and left
// as-is the view could serve nothing at all: every fetch path asks the primary
// tier first and only reaches for backups once that tier is spent.
func normalizeBackupTier(providers []ProviderConfig) {
	for i := range providers {
		if !providers[i].IsBackup {
			return
		}
	}
	for i := range providers {
		providers[i].IsBackup = false
	}
}

func NewPool(cfg *Config) (*Pool, error) {
	if cfg == nil || len(cfg.Providers) == 0 {
		return nil, ErrNoProvidersConfigured
	}
	providers := make([]ProviderConfig, len(cfg.Providers))
	copy(providers, cfg.Providers)
	normalizeBackupTier(providers)
	sort.Slice(providers, func(i, j int) bool {
		// A backup is behind every primary by definition, whatever priority
		// number it carries: the tier decides the order, priority only orders
		// within a tier.
		if providers[i].IsBackup != providers[j].IsBackup {
			return !providers[i].IsBackup
		}
		return providers[i].Priority < providers[j].Priority
	})
	cache := cfg.SegmentCache
	if cache == nil {
		cache = NoopSegmentCache()
	}
	return &Pool{
		providers:            providers,
		cache:                cache,
		sf:                   &singleflight.Group{},
		missing:              newPermanentMissingSegments(cfg.PermanentMissingMaxEntries),
		providerSig:          providerSignature(providers),
		articleStats:         newArticleStatsRegistry(providers),
		pipelineDepth:        pipelineDepthOrDefault(cfg.PipelineDepth),
		consecutive430s:      make(map[string]int),
		consecutiveSuccesses: make(map[string]int),
		leases:               newLeaseRegistry(),
		streamBytes:          newStreamByteRegistry(),
	}, nil
}

// recordStreamBytes attributes n wire bytes to the stream this view fetches for.
// Read-ahead lands here too: it is issued through the same lease-scoped subset as
// the read that triggered it, so a stream is charged for what it prefetches.
func (p *Pool) recordStreamBytes(n int64) {
	if p == nil {
		return
	}
	p.streamBytes.add(p.leaseKey, n)
}

// streamByteTotals totals the wire bytes pulled per stream since the pool was
// built. Unexported: the rate is what leaves the package; this is what the
// attribution tests assert on.
func (p *Pool) streamByteTotals() map[string]int64 {
	if p == nil {
		return nil
	}
	return p.streamBytes.snapshot()
}

// StreamSpeeds reports each stream's current rate in Mbps, metered exactly as
// provider speed is. Sampling advances the meters, so call it once per tick.
func (p *Pool) StreamSpeeds() map[string]float64 {
	if p == nil {
		return nil
	}
	return p.streamBytes.speeds()
}

func (p *Pool) recordArticleResult(providerID string, available bool) {
	providerID = strings.TrimSpace(providerID)
	if p == nil || providerID == "" {
		return
	}
	if p.articleStats == nil {
		return
	}
	counter := p.articleStats.counter(providerID, func(id string) string {
		for i := range p.providers {
			if p.providers[i].ID == id && p.providers[i].ClientPool != nil {
				return p.providers[i].ClientPool.Host()
			}
		}
		return ""
	})
	if available {
		counter.availableCount.Add(1)
		return
	}
	counter.unavailableCount.Add(1)
}

// RecordProviderArticleResult records an article operation outcome for a provider.
// available=true increments available count; false increments missing count.
func (p *Pool) RecordProviderArticleResult(providerID string, available bool) {
	p.recordArticleResult(providerID, available)
}

func (p *Pool) record430Error(providerID string) {
	providerID = strings.TrimSpace(providerID)
	if p == nil || providerID == "" {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.consecutive430s == nil {
		p.consecutive430s = make(map[string]int)
	}
	p.consecutive430s[providerID]++
	count := p.consecutive430s[providerID]

	if p.consecutiveSuccesses == nil {
		p.consecutiveSuccesses = make(map[string]int)
	}
	p.consecutiveSuccesses[providerID] = 0

	if count >= providerCooloff430Threshold {
		if p.cooloffUntil == nil {
			p.cooloffUntil = make(map[string]time.Time)
		}
		until := time.Now().Add(providerCooloffDuration)
		if until.After(p.cooloffUntil[providerID]) {
			p.cooloffUntil[providerID] = until
			logger.Debug("provider 430 cooloff armed", "provider", providerID, "consecutive_430s", count, "duration", providerCooloffDuration)
		}
	}

	logger.Trace("provider demotion increment consecutive 430", "provider", providerID, "count", count)
}

func (p *Pool) recordSuccess(providerID string) {
	providerID = strings.TrimSpace(providerID)
	if p == nil || providerID == "" {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.consecutiveSuccesses == nil {
		p.consecutiveSuccesses = make(map[string]int)
	}
	p.consecutiveSuccesses[providerID]++

	if p.consecutiveSuccesses[providerID] >= 10 {
		if p.consecutive430s != nil && p.consecutive430s[providerID] > 0 {
			logger.Debug("provider demotion reset consecutive 430 after 10 consecutive successes", "provider", providerID)
			p.consecutive430s[providerID] = 0
		}
		if p.cooloffUntil != nil {
			delete(p.cooloffUntil, providerID)
		}
	}
}

// providerInCooloff reports whether the provider is inside its 430 cooloff window.
func (p *Pool) providerInCooloff(providerID string) bool {
	if p == nil {
		return false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	until, ok := p.cooloffUntil[providerID]
	return ok && time.Now().Before(until)
}

// providerBenched reports whether the provider should sit out the first-choice
// pass — either it is inside a 430 cooloff, or the provider rejected our
// credentials the last time we dialled it.
//
// Blocked is deliberately a bench and not an exclusion. A provider whose
// account we cannot log into contributes nothing, but the alternative to trying
// it can be "nobody serves this segment", and a rejected AUTHINFO costs one
// failed connect where a missing segment costs the stream. Last resort still
// beats no resort — and if the account came back, the attempt clears the
// verdict on the spot.
func (p *Pool) providerBenched(providerID string) bool {
	if p == nil {
		return false
	}
	if p.providerInCooloff(providerID) {
		return true
	}
	return false
}

// HasBackupProviders reports whether this view holds a provider kept back for
// failover. Callers use it to decide whether "no provider could serve" is the
// end of the road or only the end of the primary tier.
func (p *Pool) HasBackupProviders() bool {
	if p == nil {
		return false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	for i := range p.providers {
		if p.providers[i].IsBackup {
			return true
		}
	}
	return false
}

// IsBackupProvider reports whether the named provider is held back for
// failover, so a caller that asks for one connection by name can request the
// tier it actually lives in.
func (p *Pool) IsBackupProvider(providerID string) bool {
	if p == nil {
		return false
	}
	providerID = strings.TrimSpace(providerID)
	p.mu.RLock()
	defer p.mu.RUnlock()
	for i := range p.providers {
		if p.providers[i].ID == providerID {
			return p.providers[i].IsBackup
		}
	}
	return false
}

func (p *Pool) ProviderArticleStats() []ProviderArticleStats {
	if p == nil || p.articleStats == nil {
		return nil
	}
	return p.articleStats.snapshot()
}

// logSegmentDecodeFailure records a yEnc decode failure. A cancelled fetch
// context means we tore the read down ourselves, which is routine during
// parallel races and seeks, so that logs at Trace; anything else is a real
// decode failure and logs at Debug.
func logSegmentDecodeFailure(ctx context.Context, providerID, messageID string, err error, rawBytes int64) {
	ctxErr := ctx.Err()
	attrs := []any{
		"provider", providerID,
		"err", err,
		"message_id", messageID,
		"raw_body_bytes", rawBytes,
		"ctx_err", ctxErr,
	}
	if ctxErr != nil {
		logger.Trace("fetch segment decode aborted", attrs...)
		return
	}
	logger.Debug("fetch segment decode failed", attrs...)
}

func (p *Pool) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (SegmentData, error) {
	messageID := strings.TrimSpace(segment.ID)
	if messageID == "" {
		return SegmentData{}, fmt.Errorf("empty segment message ID")
	}
	if p.sf == nil {
		p.mu.Lock()
		if p.sf == nil {
			p.sf = &singleflight.Group{}
		}
		p.mu.Unlock()
	}

	v, err, _ := p.sf.Do(messageID, func() (interface{}, error) {
		return p.fetchSegmentOnce(ctx, messageID, segment, groups)
	})
	if err != nil {
		return SegmentData{}, err
	}
	return v.(SegmentData), nil
}

// FetchSegmentFirst races the primary providers for the first segment (e.g.
// segment 0). It returns as soon as one provider succeeds, or the last error if
// all fail, so an article missing everywhere is settled in one round trip
// instead of a walk down the list.
//
// Backups sit the race out. Every racer downloads the whole article, so racing
// a metered backup would charge it for the first segment of every file of every
// release rather than for the ones the primaries could not deliver. They are
// walked one at a time, in order, only once every primary has failed.
func (p *Pool) FetchSegmentFirst(ctx context.Context, segment *nzb.Segment, groups []string) (SegmentData, error) {
	messageID := strings.TrimSpace(segment.ID)
	if messageID == "" {
		return SegmentData{}, fmt.Errorf("empty segment message ID")
	}
	if p.isKnownMissing(messageID) {
		return SegmentData{}, fmt.Errorf("fetch segment %s: 430 No Such Article (cached)", messageID)
	}
	if data, ok := p.cache.Get(messageID); ok {
		logger.Trace("fetch segment cache hit", "message_id", messageID)
		return data, nil
	}

	fetchCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()

	p.mu.RLock()
	providers := p.providers
	p.mu.RUnlock()

	// Exclude set for each provider: all other provider IDs so getConnection returns that provider.
	providerIDs := make([]string, len(providers))
	for i := range providers {
		providerIDs[i] = providers[i].ID
	}

	primaries := make([]int, 0, len(providers))
	for i := range providers {
		if providers[i].IsBackup {
			continue
		}
		primaries = append(primaries, i)
	}

	// Skip benched providers: racing a provider that provably does not carry
	// this release — or that will not let us log in — pays a wasted round-trip
	// on every new volume. If every primary is benched, race them all anyway
	// rather than failing.
	eligible := make([]int, 0, len(primaries))
	for _, i := range primaries {
		if p.providerBenched(providers[i].ID) {
			logger.Trace("fetch segment first: skipping benched provider", "provider", providers[i].ID)
			continue
		}
		eligible = append(eligible, i)
	}
	if len(eligible) == 0 {
		eligible = primaries
	}

	type segResult struct {
		data       SegmentData
		err        error
		host       string
		providerID string
	}
	ch := make(chan segResult, len(eligible))

	for _, i := range eligible {
		exclude := make([]string, 0, len(providers)-1)
		for j := range providerIDs {
			if j != i {
				exclude = append(exclude, providerIDs[j])
			}
		}
		go func(exclude []string) {
			conn, release, discard, providerID, err := p.getConnection(fetchCtx, exclude, 999, false)
			if err != nil {
				ch <- segResult{err: err}
				return
			}
			host := p.Host(providerID)

			// Connection leak guard: if fetchCtx is cancelled (e.g. another provider succeeded
			// or the caller gave up), discard the connection to interrupt the blocking read.
			stopWatch := make(chan struct{})
			go func() {
				select {
				case <-fetchCtx.Done():
					discard()
				case <-stopWatch:
				}
			}()
			defer func() {
				close(stopWatch)
				release()
			}()

			data, err := p.fetchArticleBody(fetchCtx, conn, discard, providerID, host, messageID, groups, segment.Bytes)
			ch <- segResult{data: data, err: err, host: host, providerID: providerID}
		}(exclude)
	}

	var lastErr error
	var lastTransientErr error
	var attempted []string
	var articleNotFoundErr error
	sawNonArticleNotFound := false
	for range eligible {
		res := <-ch
		attempted = appendUniqueHosts(attempted, res.host)
		if res.err == nil {
			p.recordArticleResult(res.providerID, true)
			p.recordSuccess(res.providerID)
			if err := p.storeFetched(fetchCtx, messageID, res.data); err != nil {
				cancel()
				return SegmentData{}, err
			}
			cancel()
			logger.Trace("fetch segment ok (parallel)", "message_id", messageID, "size", res.data.Size)
			return res.data, nil
		}
		lastErr = res.err
		if isArticleNotFound(res.err) {
			p.recordArticleResult(res.providerID, false)
			p.record430Error(res.providerID)
			if articleNotFoundErr == nil {
				articleNotFoundErr = res.err
			}
			// FetchSegmentFirst uses fixed one-provider workers; provider IDs are
			// not surfaced in results, so we cannot prove all providers were
			// attempted here. Do not mark permanent-missing from this fast path.
			continue
		}
		sawNonArticleNotFound = true
		lastTransientErr = res.err
	}

	// The primary tier is spent, which is the one thing a backup exists for.
	data, tier, ok := p.fetchFromBackups(fetchCtx, messageID, groups, segment.Bytes)
	attempted = appendUniqueHosts(attempted, tier.hosts...)
	if ok {
		if err := p.storeFetched(fetchCtx, messageID, data); err != nil {
			return SegmentData{}, err
		}
		logger.Trace("fetch segment ok (backup)", "message_id", messageID, "size", data.Size)
		return data, nil
	}
	if tier.notFoundErr != nil && articleNotFoundErr == nil {
		articleNotFoundErr = tier.notFoundErr
	}
	if tier.otherErr {
		sawNonArticleNotFound = true
		if tier.transientErr != nil {
			lastTransientErr = tier.transientErr
		}
	}
	if tier.lastErr != nil {
		lastErr = tier.lastErr
	}

	if articleNotFoundErr != nil && !sawNonArticleNotFound {
		return SegmentData{}, wrapAttemptedProviders(fmt.Errorf("fetch segment %s: %w", messageID, articleNotFoundErr), attempted)
	}
	if lastErr != nil {
		// A mixed outcome — some providers said 430, another failed weaker —
		// is not a proven hole. Surface the transient failure so the caller
		// retries rather than reading the wrapped 430 as missing-everywhere.
		if sawNonArticleNotFound && lastTransientErr != nil && isArticleNotFound(lastErr) {
			lastErr = lastTransientErr
		}
		return SegmentData{}, wrapAttemptedProviders(fmt.Errorf("fetch segment %s: failed after retries: %w", messageID, lastErr), attempted)
	}
	return SegmentData{}, fmt.Errorf("fetch segment %s: failed after retries", messageID)
}

// fetchArticleBody runs GROUP + BODY + yEnc decode on a leased connection and
// charges the wire bytes to the stream whether or not the decode worked. A
// failed decode leaves unread body bytes on the wire, so the connection is
// discarded here rather than handed back; every other outcome leaves the
// caller's release/discard bookkeeping alone.
func (p *Pool) fetchArticleBody(ctx context.Context, conn *nntp.Client, discard func(), providerID, host, messageID string, groups []string, sizeHint int64) (SegmentData, error) {
	p.activeFetches.Add(1)
	defer p.activeFetches.Add(-1)

	if len(groups) > 0 {
		if err := conn.Group(groups[0]); err != nil {
			if nntp.IsBenignDisconnect(err) || ctx.Err() != nil {
				logger.Trace("fetch segment group cancelled", "provider", providerID, "err", err)
			} else {
				logger.Debug("fetch segment group failed", "provider", providerID, "err", err)
			}
			return SegmentData{}, err
		}
	}

	r, err := conn.Body(messageID)
	if err != nil {
		switch {
		case isArticleNotFound(err):
			// 430 per-provider is expected during failover; the aggregated
			// "missing on all providers" summary is logged once by the caller.
			logger.Trace("fetch segment body 430", "provider", providerID, "message_id", messageID)
		case nntp.IsBenignDisconnect(err) || ctx.Err() != nil:
			logger.Trace("fetch segment body cancelled", "provider", providerID, "err", err)
		default:
			logger.Debug("fetch segment body failed", "provider", providerID, "err", err)
		}
		return SegmentData{}, err
	}

	cr := &countReader{Reader: r}
	frame, err := decode.DecodeToBytesSized(cr, sizeHint)
	// Close ensures EndResponse is called even if decode stopped before EOF.
	r.Close()
	// Charged whether or not the decode succeeded: the bytes crossed the wire.
	p.recordStreamBytes(cr.n)
	if err != nil {
		discard()
		logSegmentDecodeFailure(ctx, providerID, messageID, err, cr.n)
		return SegmentData{}, err
	}

	return SegmentData{
		Body:           frame.Data,
		Size:           int64(len(frame.Data)),
		ProviderHost:   host,
		FileName:       frame.FileName,
		YencFileSize:   frame.FileSize,
		YencPartOffset: frame.PartOffset,
	}, nil
}

// storeFetched caches a freshly fetched segment and clears any missing marker.
// It reports the context's error when the fetch outlived the caller that wanted
// it, in which case nothing is cached.
func (p *Pool) storeFetched(ctx context.Context, messageID string, data SegmentData) error {
	if !shouldCacheFetchedSegment(ctx) {
		return ctx.Err()
	}
	cached := data
	cached.ProviderHost = ""
	p.cache.Set(messageID, cached)
	p.clearKnownMissing(messageID)
	return nil
}

// tierResult is what one walk of the backup tier saw, so the caller can fold it
// into its own accounting: which providers answered, and whether what came back
// was a definitive 430 or something weaker.
type tierResult struct {
	hosts       []string
	providerIDs []string
	notFoundErr error
	lastErr     error
	otherErr    bool
	// transientErr keeps the last non-430 failure so a mixed outcome can be
	// reported as transient rather than as a proven hole.
	transientErr error
}

// fetchFromBackups walks the backup tier one provider at a time, in order, and
// returns the first body it gets. It runs only once the primaries have had
// their turn, so a metered account is charged for the segments they could not
// deliver and for nothing else. A view with no backups returns immediately.
func (p *Pool) fetchFromBackups(ctx context.Context, messageID string, groups []string, sizeHint int64) (SegmentData, tierResult, bool) {
	var tier tierResult
	if !p.HasBackupProviders() {
		return SegmentData{}, tier, false
	}

	var exclude []string
	for {
		conn, release, discard, providerID, err := p.getConnection(ctx, exclude, 999, true)
		if err != nil {
			if !errors.Is(err, ErrNoProvidersAvailable) {
				tier.lastErr = err
				tier.otherErr = true
				tier.transientErr = err
			}
			return SegmentData{}, tier, false
		}
		host := p.Host(providerID)
		tier.hosts = appendUniqueHosts(tier.hosts, host)
		tier.providerIDs = appendUniqueHosts(tier.providerIDs, providerID)
		// Excluded whatever happens next: one turn per backup, no second lap.
		exclude = append(exclude, providerID)

		data, err := func() (SegmentData, error) {
			// Interrupt pending body read if session is closed/cancelled.
			stopWatch := make(chan struct{})
			go func() {
				select {
				case <-ctx.Done():
					discard()
				case <-stopWatch:
				}
			}()
			defer func() {
				close(stopWatch)
				release()
			}()

			return p.fetchArticleBody(ctx, conn, discard, providerID, host, messageID, groups, sizeHint)
		}()
		if err == nil {
			p.recordArticleResult(providerID, true)
			p.recordSuccess(providerID)
			return data, tier, true
		}

		tier.lastErr = err
		if isArticleNotFound(err) {
			p.recordArticleResult(providerID, false)
			p.record430Error(providerID)
			if tier.notFoundErr == nil {
				tier.notFoundErr = err
			}
			continue
		}
		tier.otherErr = true
		tier.transientErr = err
	}
}

func (p *Pool) fetchSegmentOnce(ctx context.Context, messageID string, segment *nzb.Segment, groups []string) (SegmentData, error) {
	if p.isKnownMissing(messageID) {
		return SegmentData{}, fmt.Errorf("fetch segment %s: 430 No Such Article (cached)", messageID)
	}
	if data, ok := p.cache.Get(messageID); ok {
		logger.Trace("fetch segment cache hit", "message_id", messageID)
		return data, nil
	}

	fetchCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()

	// The retry loop walks primaries only; the backup tier gets a single ordered
	// pass afterwards, so backups must not be counted into the rounds here or
	// the loop keeps re-asking primaries that already answered 430.
	p.mu.RLock()
	primaryCount := 0
	for i := range p.providers {
		if !p.providers[i].IsBackup {
			primaryCount++
		}
	}
	p.mu.RUnlock()

	var exclude []string
	var lastErr error
	var lastTransientErr error
	var attempted []string
	var attemptedIDs []string
	var articleNotFoundErr error
	sawNonArticleNotFound := false
	maxAttempts := primaryCount
	if maxAttempts < 3 {
		maxAttempts = 3
	}
	for attempt := 0; attempt < maxAttempts; attempt++ {
		conn, release, discard, providerID, err := p.getConnection(fetchCtx, exclude, 999, false)
		if err != nil {
			if errors.Is(err, ErrNoProvidersAvailable) && len(exclude) > 0 {
				if articleNotFoundErr != nil && !sawNonArticleNotFound && primaryCount > 0 && len(exclude) >= primaryCount {
					break
				}
				exclude = nil
				continue
			}
			// Nothing in the primary tier can serve. The backup pass below is
			// the last word on whether this segment is reachable at all; carry
			// the reason so a pool that handed out nothing still says so rather
			// than reporting a bare "failed after retries".
			if !errors.Is(err, ErrNoProvidersAvailable) {
				return SegmentData{}, err
			}
			lastErr = err
			break
		}
		host := p.Host(providerID)
		attempted = appendUniqueHosts(attempted, host)
		attemptedIDs = appendUniqueHosts(attemptedIDs, providerID)

		data, err := func() (SegmentData, error) {
			// Interrupt pending body read if session is closed/cancelled.
			stopWatch := make(chan struct{})
			go func() {
				select {
				case <-fetchCtx.Done():
					discard()
				case <-stopWatch:
				}
			}()
			defer func() {
				close(stopWatch)
				release()
			}()

			return p.fetchArticleBody(fetchCtx, conn, discard, providerID, host, messageID, groups, segment.Bytes)
		}()
		if err != nil {
			lastErr = err
			if isArticleNotFound(err) {
				p.recordArticleResult(providerID, false)
				p.record430Error(providerID)
				if articleNotFoundErr == nil {
					articleNotFoundErr = err
				}
			} else {
				sawNonArticleNotFound = true
				lastTransientErr = err
			}
			exclude = append(exclude, providerID)
			continue
		}
		p.recordArticleResult(providerID, true)
		p.recordSuccess(providerID)

		if err := p.storeFetched(fetchCtx, messageID, data); err != nil {
			return SegmentData{}, err
		}
		logger.Trace("fetch segment ok", "message_id", messageID, "size", data.Size)
		return data, nil
	}

	// The primary tier is spent, which is the one thing a backup exists for.
	data, tier, ok := p.fetchFromBackups(fetchCtx, messageID, groups, segment.Bytes)
	attempted = appendUniqueHosts(attempted, tier.hosts...)
	attemptedIDs = appendUniqueHosts(attemptedIDs, tier.providerIDs...)
	if ok {
		if err := p.storeFetched(fetchCtx, messageID, data); err != nil {
			return SegmentData{}, err
		}
		logger.Trace("fetch segment ok (backup)", "message_id", messageID, "size", data.Size)
		return data, nil
	}
	if tier.notFoundErr != nil && articleNotFoundErr == nil {
		articleNotFoundErr = tier.notFoundErr
	}
	if tier.otherErr {
		sawNonArticleNotFound = true
		if tier.transientErr != nil {
			lastTransientErr = tier.transientErr
		}
	}
	if tier.lastErr != nil {
		lastErr = tier.lastErr
	}

	if articleNotFoundErr != nil && !sawNonArticleNotFound {
		if p.attemptedAllProviderIDs(attemptedIDs) {
			p.markKnownMissing(messageID)
		}
		// One summary line per genuinely-missing segment, instead of one per provider attempt.
		logger.Debug("fetch segment missing on all providers", "message_id", messageID, "providers", attempted)
		return SegmentData{}, wrapAttemptedProviders(fmt.Errorf("fetch segment %s: %w", messageID, articleNotFoundErr), attempted)
	}
	if lastErr != nil {
		// A mixed outcome — some providers said 430, another failed weaker —
		// is not a proven hole. Surface the transient failure so the caller
		// retries rather than reading the wrapped 430 as missing-everywhere.
		if sawNonArticleNotFound && lastTransientErr != nil && isArticleNotFound(lastErr) {
			lastErr = lastTransientErr
		}
		return SegmentData{}, wrapAttemptedProviders(fmt.Errorf("fetch segment %s: failed after retries: %w", messageID, lastErr), attempted)
	}
	return SegmentData{}, fmt.Errorf("fetch segment %s: failed after retries", messageID)
}

// StatSegment checks whether the article exists on any provider (STAT only, no body).
// Returns (true, nil) if found, (false, nil) if 430 on all providers, (false, err) on other errors.
// Use this before opening a stream to fail fast when the first segment is missing.
func (p *Pool) StatSegment(ctx context.Context, messageID string, groups []string) (exists bool, err error) {
	messageID = strings.TrimSpace(messageID)
	if messageID == "" {
		return false, fmt.Errorf("empty segment message ID")
	}
	if p.isKnownMissing(messageID) {
		return false, nil
	}

	statCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	p.mu.RLock()
	providers := p.providers
	p.mu.RUnlock()

	providerIDs := make([]string, len(providers))
	for i := range providers {
		providerIDs[i] = providers[i].ID
	}

	type statResult struct {
		exists     bool
		err        error
		host       string
		providerID string
	}
	ch := make(chan statResult, len(providers))

	// A pool with nothing in it cannot prove an article is missing. Report it
	// as inconclusive so the caller fails open instead of poisoning a release.
	if len(providers) == 0 {
		return false, ErrNoProvidersAvailable
	}

	probe := func(i int) {
		exclude := make([]string, 0, len(providers)-1)
		for j := range providerIDs {
			if j != i {
				exclude = append(exclude, providerIDs[j])
			}
		}
		// STAT costs a connection and no bytes, so the backup tier answers here
		// on the same terms as any other provider: knowing whether it carries
		// the article is exactly what makes it usable as a fallback, and what
		// lets a unanimous 430 be cached.
		useBackup := providers[i].IsBackup
		go func(exclude []string) {
			conn, release, discard, providerID, getErr := p.getConnection(statCtx, exclude, 999, useBackup)
			if getErr != nil {
				ch <- statResult{err: getErr}
				return
			}
			host := p.Host(providerID)

			// Watchdog: if the context is cancelled while we are waiting for
			// StatArticle (or Group), call discard() so the connection is closed
			// and the pool slot is freed immediately instead of leaking until the
			// 30-second statCtx deadline expires.
			stopWatch := make(chan struct{})
			go func() {
				select {
				case <-statCtx.Done():
					discard()
				case <-stopWatch:
				}
			}()

			var doRelease = true
			defer func() {
				close(stopWatch)
				if doRelease {
					release()
				}
				// discard() is called by the watchdog when context is done;
				// if we're here normally the watchdog exits via stopWatch.
			}()

			if len(groups) > 0 {
				if groupErr := conn.Group(groups[0]); groupErr != nil {
					// A sibling STAT winning the race cancels statCtx and discards this
					// connection mid-command; the resulting error is expected noise.
					if nntp.IsBenignDisconnect(groupErr) || statCtx.Err() != nil {
						logger.Trace("stat segment group cancelled", "provider", providerID, "err", groupErr)
					} else {
						logger.Debug("stat segment group failed", "provider", providerID, "err", groupErr)
					}
					doRelease = false
					discard()
					ch <- statResult{err: groupErr, host: host, providerID: providerID}
					return
				}
			}
			exists, statErr := conn.StatArticle(messageID)
			if statErr != nil {
				if nntp.IsBenignDisconnect(statErr) || statCtx.Err() != nil {
					logger.Trace("stat segment cancelled", "provider", providerID, "err", statErr)
				} else {
					logger.Debug("stat segment failed", "provider", providerID, "err", statErr)
				}
				doRelease = false
				discard()
				ch <- statResult{err: statErr, host: host, providerID: providerID}
				return
			}
			ch <- statResult{exists: exists, host: host, providerID: providerID}
		}(exclude)
	}

	// Providers are consulted in order, not all at once. The leading provider
	// answers the overwhelming majority of STATs by itself, so a blanket
	// fan-out multiplies connection demand by the provider count for no gain
	// and queues up the smaller pools first. The rest join as soon as the
	// leader cannot confirm the article, or once it has been slow enough that
	// waiting costs more than the extra connections.
	launched := 0
	launchRemaining := func() {
		for launched < len(providers) {
			probe(launched)
			launched++
		}
	}
	probe(0)
	launched = 1

	hedge := time.NewTimer(statHedgeDelay)
	defer hedge.Stop()

	var lastErr error
	var attempted []string
	var attemptedIDs []string
	sawNotFound := false
	sawError := false
	for collected := 0; collected < launched || launched < len(providers); {
		var res statResult
		select {
		case <-hedge.C:
			launchRemaining()
			continue
		case res = <-ch:
		}
		collected++
		attempted = appendUniqueHosts(attempted, res.host)
		// Use the id the worker reported rather than reverse-mapping from the
		// host: two providers can share a hostname (two accounts on the same
		// backbone), and matching by host collapses them onto whichever comes
		// first. That left attemptedAllProviderIDs permanently unsatisfiable,
		// so unanimous 430s were never cached and dead articles were re-STATed
		// on every pass.
		if res.providerID != "" {
			attemptedIDs = appendUniqueHosts(attemptedIDs, res.providerID)
		}
		if res.err == nil && res.exists {
			p.recordArticleResult(res.providerID, true)
			p.clearKnownMissing(messageID)
			cancel()
			logger.Trace("stat segment ok", "message_id", messageID)
			return true, nil
		}
		// The leader could not settle it. Bring in every remaining provider at
		// once so a genuinely missing article still resolves in one more round
		// trip rather than walking the list one provider at a time.
		launchRemaining()
		if res.err != nil {
			if isArticleNotFound(res.err) {
				p.recordArticleResult(res.providerID, false)
			}
			lastErr = res.err
			sawError = true
			continue
		}
		if !res.exists {
			p.recordArticleResult(res.providerID, false)
			sawNotFound = true
		}
	}
	if lastErr != nil {
		return false, wrapAttemptedProviders(fmt.Errorf("stat segment %s: %w", messageID, lastErr), attempted)
	}
	if p.attemptedAllProviderIDs(attemptedIDs) && sawNotFound && !sawError {
		p.markKnownMissing(messageID)
	}
	logger.Trace("stat segment not found (430)", "message_id", messageID)
	return false, nil
}

// FetchConcurrency reports how many segment fetches this view can keep on the
// wire at once: the connections its non-backup providers offer, capped by the
// per-stream lease limit when one applies. It is the width read-ahead can fan
// out to before extra goroutines only queue up inside getConnection — which is
// what tells read-ahead whether spending depth on a connection is free or
// whether it is competing with a connection it could have had instead.
func (p *Pool) FetchConcurrency() int {
	if p == nil {
		return 0
	}
	p.mu.RLock()
	defer p.mu.RUnlock()

	total := 0
	for _, prov := range p.providers {
		if prov.IsBackup || prov.ClientPool == nil {
			continue
		}
		conns := prov.ClientPool.MaxConn()
		if limit := p.limiter.limitFor(prov.ID); limit > 0 && limit < conns {
			conns = limit
		}
		total += conns
	}
	return total
}

// StatConcurrency reports how many STATs a single caller should run against
// this pool at once. Staged fan-out means a STAT normally occupies one
// connection on the leading provider, so the budget is derived from that
// provider's pool size and kept small: validation checks many files in
// parallel, and each of those calls gets this same budget.
func (p *Pool) StatConcurrency() int {
	p.mu.RLock()
	defer p.mu.RUnlock()

	leading := 0
	for _, prov := range p.providers {
		if prov.IsBackup || prov.ClientPool == nil {
			continue
		}
		if conns := prov.ClientPool.MaxConn(); conns > leading {
			leading = conns
		}
	}
	budget := leading / statConcurrencyDivisor
	if budget < minStatConcurrency {
		return minStatConcurrency
	}
	if budget > maxStatConcurrency {
		return maxStatConcurrency
	}
	return budget
}

func (p *Pool) getConnection(ctx context.Context, exclude []string, maxPriority int, useBackup bool) (client *nntp.Client, release, discard func(), providerID string, err error) {
	p.mu.RLock()
	providers := p.providers
	p.mu.RUnlock()

	excludeSet := make(map[string]bool)
	for _, id := range exclude {
		excludeSet[id] = true
	}

	// Two passes. Pass 1 takes healthy providers — anyone not inside an active
	// 430 cooloff window and not blocked on credentials. Pass 2 falls back to
	// benched providers as a last resort. The cooloff is time-boxed and self-healing: a raw
	// consecutive-430 count skip was PERMANENT for a benched provider (it can
	// only reset via successes, which require being selected), so one
	// bad-release 430 storm funneled all traffic onto a single provider until
	// restart.
	// A provider skipped only because this lease is at its connection cap is
	// remembered rather than failed: if no other provider can serve, waiting for
	// one of our own permits is better than reporting no providers available.
	var cappedOut *ProviderConfig

	for _, wantCooloff := range []bool{false, true} {
		for i := range providers {
			prov := &providers[i]
			if excludeSet[prov.ID] {
				continue
			}
			if prov.Priority > maxPriority {
				continue
			}
			if prov.IsBackup != useBackup {
				continue
			}
			if p.providerBenched(prov.ID) != wantCooloff {
				continue
			}

			releasePermit, permitted := p.tryPermit(prov.ID)
			if !permitted {
				if cappedOut == nil {
					cappedOut = prov
				}
				continue
			}

			c, ok := prov.ClientPool.TryGet(ctx)
			if !ok {
				var getErr error
				c, getErr = prov.ClientPool.Get(ctx)
				if getErr != nil {
					releasePermit()
					if errors.Is(getErr, context.Canceled) {
						return nil, nil, nil, "", getErr
					}
					continue
				}
			}
			return p.leaseConnection(c, prov, releasePermit)
		}
	}

	if cappedOut != nil {
		releasePermit, permitted := p.waitPermit(ctx, cappedOut.ID)
		if !permitted {
			return nil, nil, nil, "", ctx.Err()
		}
		c, err := cappedOut.ClientPool.Get(ctx)
		if err != nil {
			releasePermit()
			return nil, nil, nil, "", err
		}
		return p.leaseConnection(c, cappedOut, releasePermit)
	}

	return nil, nil, nil, "", ErrNoProvidersAvailable
}

// tryPermit takes a per-lease permit without waiting. Uncapped views always
// succeed with a no-op release.
func (p *Pool) tryPermit(providerID string) (func(), bool) {
	if p.limiter == nil {
		return func() {}, true
	}
	return p.limiter.tryAcquire(providerID)
}

// waitPermit blocks for a per-lease permit until ctx ends.
func (p *Pool) waitPermit(ctx context.Context, providerID string) (func(), bool) {
	if p.limiter == nil {
		return func() {}, true
	}
	return p.limiter.acquire(ctx, providerID)
}

// leaseConnection wraps a checked-out client so returning it also returns the
// lease permit, exactly once, whichever way the caller finishes with it.
func (p *Pool) leaseConnection(c *nntp.Client, prov *ProviderConfig, releasePermit func()) (*nntp.Client, func(), func(), string, error) {
	clientPool := prov.ClientPool
	pid := prov.ID
	var once sync.Once
	release := func() {
		once.Do(func() {
			clientPool.Put(c)
			releasePermit()
		})
	}
	discard := func() {
		once.Do(func() {
			clientPool.Discard(c)
			releasePermit()
		})
	}
	return c, release, discard, pid, nil
}

func (p *Pool) GetConnection(ctx context.Context, exclude []string, maxPriority int, useBackup bool) (client *nntp.Client, release, discard func(), providerID string, err error) {
	return p.getConnection(ctx, exclude, maxPriority, useBackup)
}

func (p *Pool) DiscardConnection(client *nntp.Client, pool *nntp.ClientPool) {
	if client != nil && pool != nil {
		pool.Discard(client)
	}
}

// PurgeCache drops all entries from the segment cache and resets budget accounting.
// Call when no sessions are active so the GC can reclaim the segment memory.
func (p *Pool) PurgeCache() {
	p.cache.Purge()
	logger.Trace("pool PurgeCache: segment cache purged")
}

func (p *Pool) TraceSnapshot() PoolTraceSnapshot {
	p.mu.RLock()
	providers := make([]ProviderConfig, len(p.providers))
	copy(providers, p.providers)
	cache := p.cache
	p.mu.RUnlock()

	snapshot := PoolTraceSnapshot{
		InFlightFetches: p.activeFetches.Load(),
		Cache:           cacheStats(cache),
		Providers:       make([]PoolProviderTraceSnapshot, 0, len(providers)),
	}
	for _, provider := range providers {
		clientPool := provider.ClientPool
		if clientPool == nil {
			continue
		}
		snapshot.Providers = append(snapshot.Providers, PoolProviderTraceSnapshot{
			ID:     provider.ID,
			Host:   clientPool.Host(),
			Total:  clientPool.TotalConnections(),
			Idle:   clientPool.IdleConnections(),
			Active: clientPool.ActiveConnections(),
		})
	}
	return snapshot
}

func (p *Pool) CountProviders() int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return len(p.providers)
}

func (p *Pool) ProviderOrder() []string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	ids := make([]string, 0, len(p.providers))
	for i := range p.providers {
		ids = append(ids, p.providers[i].ID)
	}
	return ids
}

func (p *Pool) ProviderHosts() []string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	hosts := make([]string, 0, len(p.providers))
	for i := range p.providers {
		if h := p.providers[i].ClientPool.Host(); h != "" {
			hosts = append(hosts, h)
		}
	}
	return hosts
}

// SubsetForLease is Subset with a per-lease connection cap. leaseKey identifies
// whose budget this is (a stream username); limits maps provider ID to the most
// connections that lease may hold at once. All subsets sharing a leaseKey draw
// on one budget, so a stream cannot sidestep its cap by holding several views.
func (p *Pool) SubsetForLease(leaseKey string, providerIDs []string, limits map[string]int) *Pool {
	subset := p.Subset(providerIDs)
	if subset == nil {
		return nil
	}
	subset.limiter = p.leases.limiter(leaseKey, limits)
	subset.leaseKey = strings.TrimSpace(leaseKey)
	return subset
}

func (p *Pool) Subset(providerIDs []string) *Pool {
	if p == nil {
		return nil
	}
	p.mu.RLock()
	defer p.mu.RUnlock()

	var subset []ProviderConfig
	if len(providerIDs) == 0 {
		subset = make([]ProviderConfig, len(p.providers))
		copy(subset, p.providers)
	} else {
		byID := make(map[string]ProviderConfig, len(p.providers))
		for i := range p.providers {
			byID[p.providers[i].ID] = p.providers[i]
		}
		subset = make([]ProviderConfig, 0, len(providerIDs))
		for _, id := range providerIDs {
			id = strings.TrimSpace(id)
			if id == "" {
				continue
			}
			if cfg, ok := byID[id]; ok {
				cfg.Priority = len(subset)
				subset = append(subset, cfg)
			}
		}
	}

	if len(subset) == 0 {
		return nil
	}
	// A stream may select only what the parent holds back for failover. That
	// view has no tier above it, so the flag is dropped rather than leaving the
	// stream with nothing it is allowed to fetch from.
	normalizeBackupTier(subset)

	return &Pool{
		providers:            subset,
		cache:                p.cache,
		sf:                   p.sf,
		missing:              p.missing,
		providerSig:          providerSignature(subset),
		articleStats:         p.articleStats,
		pipelineDepth:        p.pipelineDepth,
		consecutive430s:      make(map[string]int),
		consecutiveSuccesses: make(map[string]int),
		leases:               p.leases,
		streamBytes:          p.streamBytes,
		leaseKey:             p.leaseKey,
	}
}

func (p *Pool) Host(providerID string) string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	for i := range p.providers {
		if p.providers[i].ID == providerID {
			return p.providers[i].ClientPool.Host()
		}
	}
	return ""
}
