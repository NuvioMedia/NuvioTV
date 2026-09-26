package engine

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"path"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/javi11/nntppool/v4"
)

type OpenRequest struct {
	CacheScope string            `json:"cacheScope,omitempty"`
	NZBURL     string            `json:"nzbUrl"`
	Servers    []string          `json:"servers"`
	Headers    map[string]string `json:"headers,omitempty"`
	Selection
	Config Config `json:"config"`
}

type Session struct {
	content   *Content
	store     *Store
	pool      *nntppool.Client
	ctx       context.Context
	cancel    context.CancelFunc
	ahead     int
	subtitles []*File
	warmup    *startupWarmup
	trace     *startupTrace
}

func (s *Session) Close() { s.cancel(); s.warmup.Close(); s.store.Close(); s.pool.Close() }

type Server struct {
	ctx      context.Context
	token    string
	roots    *x509.CertPool
	client   *http.Client
	mu       sync.Mutex
	sessions map[string]*Session
	opening  bool
	nzbCache *nzbCache
}

func NewServer(ctx context.Context, token string, roots *x509.CertPool, client *http.Client) *Server {
	return &Server{ctx: ctx, token: token, roots: roots, client: client, sessions: make(map[string]*Session)}
}

// Configure once before serving. The directory comes from the Android bootstrap,
// never from an addon URL or HTTP request.
func (s *Server) SetNZBCacheDir(dir string) { s.nzbCache = newNZBCache(dir) }
func RandomToken() string {
	var b [24]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b[:])
}
func (s *Server) Close() {
	s.mu.Lock()
	sessions := s.sessions
	s.sessions = make(map[string]*Session)
	s.mu.Unlock()
	for _, v := range sessions {
		v.Close()
	}
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// Control uses a bearer token; players use an unguessable capability path,
	// allowing both ExoPlayer and mpv to perform concurrent Range requests.
	if strings.HasPrefix(r.URL.Path, "/stream/") {
		s.stream(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/subtitle/") {
		s.subtitle(w, r)
		return
	}
	if subtle.ConstantTimeCompare([]byte(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")), []byte(s.token)) != 1 {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	switch {
	case r.URL.Path == "/health" && r.Method == http.MethodGet:
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"protocol":1,"ready":true}`)
	case r.URL.Path == "/sessions" && r.Method == http.MethodPost:
		s.open(w, r)
	case strings.HasPrefix(r.URL.Path, "/sessions/") && strings.HasSuffix(r.URL.Path, "/diagnostics") && r.Method == http.MethodGet:
		id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/sessions/"), "/diagnostics")
		s.mu.Lock()
		v := s.sessions[id]
		s.mu.Unlock()
		if v == nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(v.diagnostics())
	case strings.HasPrefix(r.URL.Path, "/sessions/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(r.URL.Path, "/sessions/")
		s.mu.Lock()
		v := s.sessions[id]
		delete(s.sessions, id)
		idle := len(s.sessions) == 0 && !s.opening
		s.mu.Unlock()
		if v != nil {
			v.Close()
			if idle {
				debug.FreeOSMemory()
			}
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) open(w http.ResponseWriter, r *http.Request) {
	trace := newStartupTrace()
	s.mu.Lock()
	if s.opening || len(s.sessions) >= 2 {
		s.mu.Unlock()
		http.Error(w, "playback session busy", http.StatusConflict)
		return
	}
	s.opening = true
	s.mu.Unlock()
	defer func() { s.mu.Lock(); s.opening = false; s.mu.Unlock() }()
	var req OpenRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10)).Decode(&req); err != nil {
		http.Error(w, "invalid stream request", http.StatusBadRequest)
		return
	}
	t, err := req.Config.Tuning()
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	providers, err := Providers(req.Servers, req.Config, s.roots)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	ctx, cancel := context.WithCancel(s.ctx)
	// Cancelling the open HTTP request tears down its work. Once committed,
	// session lifetime is independent of this one request.
	stop := context.AfterFunc(r.Context(), cancel)
	pool, err := nntppool.NewClient(ctx, providers, nntppool.WithStatProbe(false))
	if err != nil {
		stop()
		cancel()
		http.Error(w, "could not create NNTP pool", 502)
		return
	}
	store := NewStore(ctx, pool, t.CacheBytes)
	v := &Session{store: store, pool: pool, ctx: ctx, cancel: cancel, ahead: t.ReadAhead, trace: trace}
	trace.mark("pool_created")
	success := false
	defer func() {
		stop()
		if !success {
			v.Close()
		}
	}()
	var cache *nzbCache
	if req.Config.CacheNZB {
		cache = s.nzbCache
	}
	files, err := fetchNZB(ctx, s.client, req.NZBURL, req.Headers, store, req.Config.FastNZBFetch, cache, req.CacheScope, trace)
	trace.mark("nzb_loaded")
	if err == nil {
		v.content, err = Select(ctx, files, req.Selection)
	}
	trace.mark("content_selected")
	if err != nil {
		// Network errors can contain URLs/message IDs. Only our actionable
		// archive/selector errors are suitable for display or diagnostics.
		msg := "Usenet stream could not be opened"
		if errors.Is(err, ErrCompressedRAR) || errors.Is(err, ErrEncryptedRAR) {
			msg = err.Error()
		} else if !strings.ContainsAny(err.Error(), "<>@") && !strings.Contains(err.Error(), "://") {
			msg = err.Error()
		}
		http.Error(w, msg, http.StatusUnprocessableEntity)
		return
	}
	if !stop() || ctx.Err() != nil {
		return
	}
	v.subtitles = standaloneSubtitles(files, req.Selection)
	connections := 0
	for _, p := range providers {
		connections += p.Connections
	}
	v.startMKVWarmup(req.Config.FastMKVStartup, connections)
	id := RandomToken()
	s.mu.Lock()
	s.sessions[id] = v
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	trace.mark("session_ready")
	err = json.NewEncoder(w).Encode(map[string]any{"id": id, "path": "/stream/" + id + "/" + path.Base(v.content.Name), "filename": v.content.Name, "size": v.content.Size, "subtitles": subtitleManifest(id, v.subtitles), "startup": trace.snapshot()})
	if err != nil {
		s.mu.Lock()
		delete(s.sessions, id)
		s.mu.Unlock()
		return
	}
	success = true
}

func (s *Server) stream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		w.WriteHeader(405)
		return
	}
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/stream/"), "/", 2)
	s.mu.Lock()
	session := s.sessions[parts[0]]
	s.mu.Unlock()
	if session == nil {
		http.NotFound(w, r)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	stop := context.AfterFunc(session.ctx, cancel)
	defer stop()
	reader := session.content.Reader(ctx, session.ahead)
	reader.warmup = session.warmup
	defer reader.Close()
	// ServeContent implements suffix, open-ended, multiple and unsatisfiable
	// ranges, HEAD, exact Content-Length and Content-Range semantics. Every
	// request has its own cursor; no player can mutate another reader's seek.
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, session.content.Name, time.Time{}, session.trace.reader(reader))
}
