package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"mime"
	"net/http"
	"path/filepath"
	"strings"
	"time"

	"streamnzb/pkg/core/logger"
)

const maxCreateRequestSize = 128 << 10

const managementTokenHeader = "X-Nuvio-Token"

type apiServer struct {
	registry *sessionRegistry
	baseURL  string
	token    string
	server   *http.Server
}

type createSessionResponse struct {
	ID        string `json:"id"`
	StreamURL string `json:"streamUrl"`
}

type sessionStats struct {
	DownloadedBytes int64 `json:"downloadedBytes"`
	DownloadSpeed   int64 `json:"downloadSpeed"`
	Connections     int   `json:"connections"`
}

type errorResponse struct {
	Error string `json:"error"`
}

func newAPIServer(registry *sessionRegistry, baseURL, token string) *apiServer {
	return &apiServer{registry: registry, baseURL: baseURL, token: token}
}

func (s *apiServer) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/shutdown", s.handleShutdown)
	mux.HandleFunc("/v1/sessions", s.handleSessions)
	mux.HandleFunc("/v1/sessions/", s.handleSession)
	return mux
}

func (s *apiServer) handleHealth(w http.ResponseWriter, request *http.Request) {
	if !s.authorizeManagement(w, request) {
		return
	}
	if request.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *apiServer) handleShutdown(w http.ResponseWriter, request *http.Request) {
	if !s.authorizeManagement(w, request) {
		return
	}
	if request.Method != http.MethodPost {
		writeMethodNotAllowed(w, http.MethodPost)
		return
	}
	w.WriteHeader(http.StatusNoContent)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if s.server != nil {
			_ = s.server.Shutdown(ctx)
		}
	}()
}

func (s *apiServer) handleSessions(w http.ResponseWriter, request *http.Request) {
	if !s.authorizeManagement(w, request) {
		return
	}
	if request.Method != http.MethodPost {
		writeMethodNotAllowed(w, http.MethodPost)
		return
	}
	request.Body = http.MaxBytesReader(w, request.Body, maxCreateRequestSize)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var payload createSessionRequest
	if err := decoder.Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "invalid session request")
		return
	}

	session, err := s.registry.create(payload)
	if err != nil {
		logger.Warn("Failed to create NNTP session", "err", err)
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, createSessionResponse{
		ID:        session.id,
		StreamURL: fmt.Sprintf("%s/v1/sessions/%s/stream", s.baseURL, session.id),
	})
}

func (s *apiServer) handleSession(w http.ResponseWriter, request *http.Request) {
	remainder := strings.Trim(strings.TrimPrefix(request.URL.Path, "/v1/sessions/"), "/")
	parts := strings.Split(remainder, "/")
	if len(parts) == 0 || parts[0] == "" {
		http.NotFound(w, request)
		return
	}

	id := parts[0]
	if len(parts) == 1 {
		if !s.authorizeManagement(w, request) {
			return
		}
		switch request.Method {
		case http.MethodGet:
			session, ok := s.registry.get(id, false)
			if !ok {
				http.NotFound(w, request)
				return
			}
			writeJSON(w, http.StatusOK, session.stats())
		case http.MethodDelete:
			if !s.registry.delete(id) {
				http.NotFound(w, request)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			writeMethodNotAllowed(w, http.MethodGet, http.MethodDelete)
		}
		return
	}

	if len(parts) == 2 && parts[1] == "stream" {
		s.handleStream(w, request, id)
		return
	}
	http.NotFound(w, request)
}

func (s *apiServer) authorizeManagement(w http.ResponseWriter, request *http.Request) bool {
	provided := request.Header.Get(managementTokenHeader)
	if len(provided) != len(s.token) || subtle.ConstantTimeCompare([]byte(provided), []byte(s.token)) != 1 {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	return true
}

func (s *apiServer) handleStream(w http.ResponseWriter, request *http.Request, id string) {
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		writeMethodNotAllowed(w, http.MethodGet, http.MethodHead)
		return
	}
	session, ok := s.registry.get(id, true)
	if !ok {
		http.NotFound(w, request)
		return
	}

	stream, name, _, err := session.openMedia(request.Context())
	if err != nil {
		logger.Warn("Failed to prepare NNTP media", "session", id, "err", err)
		writeError(w, http.StatusBadGateway, "failed to prepare NZB media")
		return
	}
	defer stream.Close()

	if contentType := mime.TypeByExtension(filepath.Ext(name)); contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, request, name, time.Time{}, stream)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}

func writeMethodNotAllowed(w http.ResponseWriter, methods ...string) {
	w.Header().Set("Allow", strings.Join(methods, ", "))
	writeError(w, http.StatusMethodNotAllowed, "method not allowed")
}
