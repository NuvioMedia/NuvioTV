package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/loader"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/media/unpack"
	"streamnzb/pkg/usenet/nntp"
	usenetpool "streamnzb/pkg/usenet/pool"
)

const (
	maxNZBSize                = 64 << 20
	sessionSegmentCacheMB     = 64
	providerValidationTimeout = 15 * time.Second
)

type createSessionRequest struct {
	NZBURL          string   `json:"nzbUrl"`
	Servers         []string `json:"servers"`
	FileIndex       *int     `json:"fileIdx,omitempty"`
	FileMustInclude string   `json:"fileMustInclude,omitempty"`
	Season          int      `json:"season,omitempty"`
	Episode         int      `json:"episode,omitempty"`
}

type engineSession struct {
	id       string
	created  time.Time
	accessed atomic.Int64

	ctx      context.Context
	cancel   context.CancelFunc
	clients  []*nntp.ClientPool
	cache    usenetpool.SegmentCache
	files    []*loader.File
	document *nzb.NZB
	target   unpack.EpisodeTarget

	openMu    sync.Mutex
	blueprint unpack.Blueprint
	closeOnce sync.Once
}

func newEngineSession(request createSessionRequest, httpClient *http.Client) (*engineSession, error) {
	providers, err := parseProviders(request.Servers)
	if err != nil {
		return nil, err
	}
	nzbBytes, err := downloadNZB(request.NZBURL, httpClient)
	if err != nil {
		return nil, err
	}

	parseCtx, parseCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer parseCancel()
	document, err := nzb.ParseWithContext(parseCtx, bytes.NewReader(nzbBytes))
	if err != nil {
		return nil, fmt.Errorf("failed to parse NZB")
	}
	if strings.TrimSpace(request.FileMustInclude) != "" {
		if _, err := compileFilePattern(request.FileMustInclude); err != nil {
			return nil, err
		}
	}

	id, err := newSessionID()
	if err != nil {
		return nil, fmt.Errorf("failed to create session")
	}
	clients := make([]*nntp.ClientPool, 0, len(providers))
	providerConfigs := make([]usenetpool.ProviderConfig, 0, len(providers))
	for index, provider := range providers {
		client := nntp.NewClientPool(
			provider.host,
			provider.port,
			provider.useTLS,
			provider.username,
			provider.password,
			provider.connections,
		)
		clients = append(clients, client)
		providerConfigs = append(providerConfigs, usenetpool.ProviderConfig{
			ID:         fmt.Sprintf("provider-%d", index+1),
			Priority:   index,
			IsBackup:   index > 0,
			ClientPool: client,
		})
	}
	if err := validateProviderClients(clients); err != nil {
		shutdownClients(clients)
		return nil, err
	}
	segmentCache := usenetpool.NewMemorySegmentCacheWithBudget(
		usenetpool.NewSegmentCacheBudget(sessionSegmentCacheMB),
	)
	pool, err := usenetpool.NewPool(&usenetpool.Config{
		Providers:    providerConfigs,
		SegmentCache: segmentCache,
	})
	if err != nil {
		shutdownClients(clients)
		return nil, fmt.Errorf("failed to initialize NNTP providers")
	}

	ctx, cancel := context.WithCancel(context.Background())
	target := unpack.EpisodeTarget{
		Season:          request.Season,
		Episode:         request.Episode,
		FileMustInclude: request.FileMustInclude,
	}
	if request.FileIndex != nil {
		target.HasFileIndex = true
		target.FileIndex = *request.FileIndex
	}
	contentSeason, contentEpisode := request.Season, request.Episode
	if target.HasFileIndex || strings.TrimSpace(target.FileMustInclude) != "" {
		contentSeason, contentEpisode = 0, 0
	}
	contentFiles := document.GetSessionContentFilesForEpisode(contentSeason, contentEpisode, 0)
	if len(contentFiles) == 0 {
		cancel()
		shutdownClients(clients)
		return nil, fmt.Errorf("NZB contains no playable content")
	}
	fetcher := pool.SubsetForLease(id, nil, nil)
	estimator := loader.NewSegmentSizeEstimator()
	files := make([]*loader.File, 0, len(contentFiles))
	for _, info := range contentFiles {
		file := loader.NewFile(ctx, info.File, estimator, fetcher)
		file.SetOwnerSessionID(id)
		files = append(files, file)
	}

	exists, statErr := verifyRequiredArchivesExist(ctx, files)
	switch {
	case errors.Is(statErr, errFirstSegmentUnavailable):
		cancel()
		shutdownClients(clients)
		segmentCache.Purge()
		logger.Warn("NNTP release rejected during preflight", "session", id, "err", statErr)
		return nil, statErr
	case statErr != nil:
		logger.Warn("NNTP preflight inconclusive; continuing", "session", id, "err", statErr)
	case !exists:
		cancel()
		shutdownClients(clients)
		segmentCache.Purge()
		err := fmt.Errorf("archive volume segment unavailable: %w", errFirstSegmentUnavailable)
		logger.Warn("NNTP release rejected during preflight", "session", id, "err", err)
		return nil, err
	}

	now := time.Now()
	session := &engineSession{
		id:       id,
		created:  now,
		ctx:      ctx,
		cancel:   cancel,
		clients:  clients,
		cache:    segmentCache,
		files:    files,
		document: document,
		target:   target,
	}
	session.touch()
	return session, nil
}

type providerValidationResult struct {
	index int
	err   error
}

func validateProviderClients(clients []*nntp.ClientPool) error {
	ctx, cancel := context.WithTimeout(context.Background(), providerValidationTimeout)
	defer cancel()

	results := make(chan providerValidationResult, len(clients))
	for index, client := range clients {
		go func() {
			results <- providerValidationResult{index: index, err: client.ValidateContext(ctx)}
		}()
	}

	errs := make([]error, len(clients))
	for range clients {
		select {
		case result := <-results:
			if result.err == nil {
				return nil
			}
			errs[result.index] = result.err
		case <-ctx.Done():
			for index, err := range errs {
				if err != nil {
					return fmt.Errorf("NNTP provider %d connection failed: %w", index+1, err)
				}
			}
			return fmt.Errorf("NNTP provider validation failed: %w", ctx.Err())
		}
	}

	for index, err := range errs {
		if err != nil {
			return fmt.Errorf("NNTP provider %d connection failed: %w", index+1, err)
		}
	}
	return fmt.Errorf("no NNTP providers available")
}

func (s *engineSession) openMedia(ctx context.Context) (unpack.ReadSeekCloser, string, int64, error) {
	s.openMu.Lock()
	defer s.openMu.Unlock()
	if s.ctx.Err() != nil {
		return nil, "", 0, fmt.Errorf("session is closed")
	}

	files := make([]unpack.UnpackableFile, len(s.files))
	for index := range s.files {
		files[index] = s.files[index]
	}
	password := ""
	if s.document != nil {
		password = s.document.Password()
	}
	stream, name, size, blueprint, err := unpack.GetMediaStreamForEpisode(
		ctx,
		files,
		s.blueprint,
		password,
		s.target,
	)
	if err != nil {
		return nil, "", 0, err
	}
	s.blueprint = blueprint
	s.touch()
	return stream, name, size, nil
}

func (s *engineSession) stats() sessionStats {
	var totalBytes int64
	var speedMbps float64
	var activeConnections int
	for _, client := range s.clients {
		totalBytes += int64(client.TotalMegabytes() * 1024 * 1024)
		speedMbps += client.GetSpeed()
		activeConnections += client.ActiveConnections()
	}
	return sessionStats{
		DownloadedBytes: totalBytes,
		DownloadSpeed:   int64(speedMbps * 1024 * 1024 / 8),
		Connections:     activeConnections,
	}
}

func (s *engineSession) touch() {
	s.accessed.Store(time.Now().UnixNano())
}

func (s *engineSession) lastAccess() time.Time {
	return time.Unix(0, s.accessed.Load())
}

func (s *engineSession) close() {
	s.closeOnce.Do(func() {
		s.cancel()
		shutdownClients(s.clients)
		if s.cache != nil {
			s.cache.Purge()
		}
	})
}

func shutdownClients(clients []*nntp.ClientPool) {
	for _, client := range clients {
		client.Shutdown()
	}
}

func downloadNZB(rawURL string, client *http.Client) ([]byte, error) {
	trimmed := strings.TrimSpace(rawURL)
	parsed, err := url.Parse(trimmed)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Hostname() == "" {
		return nil, fmt.Errorf("invalid NZB URL")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, trimmed, nil)
	if err != nil {
		return nil, fmt.Errorf("invalid NZB URL")
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("failed to download NZB")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to download NZB (HTTP %d)", response.StatusCode)
	}
	payload, err := io.ReadAll(io.LimitReader(response.Body, maxNZBSize+1))
	if err != nil {
		return nil, fmt.Errorf("failed to read NZB")
	}
	if len(payload) == 0 {
		return nil, fmt.Errorf("downloaded NZB is empty")
	}
	if len(payload) > maxNZBSize {
		return nil, fmt.Errorf("downloaded NZB exceeds the 64 MiB limit")
	}
	return payload, nil
}

func newSessionID() (string, error) {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return hex.EncodeToString(buffer), nil
}

type sessionRegistry struct {
	mu          sync.RWMutex
	sessions    map[string]*engineSession
	maxSessions int
	ttl         time.Duration
	httpClient  *http.Client
	stopCh      chan struct{}
	stopOnce    sync.Once
}

func newSessionRegistry(maxSessions int, ttl time.Duration) *sessionRegistry {
	client := &http.Client{
		Timeout: 65 * time.Second,
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return fmt.Errorf("too many redirects")
			}
			if request.URL.Scheme != "http" && request.URL.Scheme != "https" {
				return fmt.Errorf("unsupported redirect")
			}
			return nil
		},
	}
	registry := &sessionRegistry{
		sessions:    make(map[string]*engineSession),
		maxSessions: maxSessions,
		ttl:         ttl,
		httpClient:  client,
		stopCh:      make(chan struct{}),
	}
	go registry.cleanupLoop()
	return registry
}

func (r *sessionRegistry) create(request createSessionRequest) (*engineSession, error) {
	session, err := newEngineSession(request, r.httpClient)
	if err != nil {
		return nil, err
	}

	r.mu.Lock()
	r.sessions[session.id] = session
	evicted := r.evictLocked(session.id)
	r.mu.Unlock()
	for _, old := range evicted {
		old.close()
	}
	return session, nil
}

func (r *sessionRegistry) get(id string, touch bool) (*engineSession, bool) {
	r.mu.RLock()
	session, ok := r.sessions[id]
	r.mu.RUnlock()
	if ok && touch {
		session.touch()
	}
	return session, ok
}

func (r *sessionRegistry) delete(id string) bool {
	r.mu.Lock()
	session, ok := r.sessions[id]
	if ok {
		delete(r.sessions, id)
	}
	r.mu.Unlock()
	if ok {
		session.close()
	}
	return ok
}

func (r *sessionRegistry) evictLocked(keepID string) []*engineSession {
	now := time.Now()
	type candidate struct {
		id      string
		session *engineSession
	}
	candidates := make([]candidate, 0, len(r.sessions))
	for id, session := range r.sessions {
		if id != keepID && now.Sub(session.lastAccess()) > r.ttl {
			delete(r.sessions, id)
			candidates = append(candidates, candidate{id: id, session: session})
		}
	}
	if len(r.sessions) > r.maxSessions {
		remaining := make([]candidate, 0, len(r.sessions))
		for id, session := range r.sessions {
			if id != keepID {
				remaining = append(remaining, candidate{id: id, session: session})
			}
		}
		sort.Slice(remaining, func(i, j int) bool {
			return remaining[i].session.lastAccess().Before(remaining[j].session.lastAccess())
		})
		for _, item := range remaining {
			if len(r.sessions) <= r.maxSessions {
				break
			}
			delete(r.sessions, item.id)
			candidates = append(candidates, item)
		}
	}

	evicted := make([]*engineSession, 0, len(candidates))
	seen := make(map[string]bool)
	for _, item := range candidates {
		if !seen[item.id] {
			seen[item.id] = true
			evicted = append(evicted, item.session)
		}
	}
	return evicted
}

func (r *sessionRegistry) closeAll() {
	r.stopOnce.Do(func() { close(r.stopCh) })
	r.mu.Lock()
	sessions := make([]*engineSession, 0, len(r.sessions))
	for id, session := range r.sessions {
		delete(r.sessions, id)
		sessions = append(sessions, session)
	}
	r.mu.Unlock()
	for _, session := range sessions {
		session.close()
	}
}

func (r *sessionRegistry) cleanupLoop() {
	interval := time.Minute
	if r.ttl > 0 && r.ttl/4 < interval {
		interval = r.ttl / 4
	}
	if interval <= 0 {
		interval = time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			r.cleanupExpired()
		case <-r.stopCh:
			return
		}
	}
}

func (r *sessionRegistry) cleanupExpired() {
	if r.ttl <= 0 {
		return
	}
	now := time.Now()
	r.mu.Lock()
	expired := make([]*engineSession, 0)
	for id, session := range r.sessions {
		if now.Sub(session.lastAccess()) > r.ttl {
			delete(r.sessions, id)
			expired = append(expired, session)
		}
	}
	r.mu.Unlock()
	for _, session := range expired {
		session.close()
	}
}
