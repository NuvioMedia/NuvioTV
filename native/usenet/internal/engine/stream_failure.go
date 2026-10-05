package engine

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"sync"
	"sync/atomic"

	"github.com/javi11/nntppool/v4"
)

// Only definitive data/provider errors retire a session. Cancellation, socket
// errors and timeouts retain ordinary retry behaviour.
func permanentStreamFailure(err error) string {
	switch {
	case errors.Is(err, nntppool.ErrArticleNotFound):
		return "missing-article"
	case errors.Is(err, errHoleLimit):
		return "hole-limit"
	case errors.Is(err, errInvalidArticle), errors.Is(err, ErrCompressedRAR), errors.Is(err, ErrEncryptedRAR),
		errors.Is(err, err7zVolumeLayout):
		return "invalid-article"
	case errors.Is(err, errProviderAuthentication):
		return "provider-authentication"
	case errors.Is(err, errProviderQuota):
		return "provider-quota"
	default:
		return ""
	}
}

type streamFailure struct {
	mu   sync.Mutex
	kind string
}

func (f *streamFailure) get() string { f.mu.Lock(); defer f.mu.Unlock(); return f.kind }
func (f *streamFailure) record(err error) {
	kind := permanentStreamFailure(err)
	if kind == "" {
		return
	}
	f.mu.Lock()
	if f.kind == "" {
		f.kind = kind
	}
	f.mu.Unlock()
}

type failureReader struct {
	io.ReadSeeker
	session  *Session
	mu       sync.Mutex
	err      error
	response *deferredStreamResponse
}

func (r *failureReader) Read(p []byte) (int, error) {
	n, err := r.ReadSeeker.Read(p)
	if n > 0 {
		r.response.mediaReady.Store(true)
	}
	if err != nil && err != io.EOF {
		r.mu.Lock()
		r.err = err
		r.mu.Unlock()
		r.session.failure.record(err)
	}
	return n, err
}

func (r *failureReader) lastError() error { r.mu.Lock(); defer r.mu.Unlock(); return r.err }

// ServeContent writes success headers before its first body read and discards
// CopyN's error. Delay committing those headers until actual bytes are written:
// a first-read failure can still be returned as an HTTP error. A later failure
// necessarily truncates this response, but retires the session for every reopen.
type deferredStreamResponse struct {
	http.ResponseWriter
	status     int
	committed  bool
	mediaReady atomic.Bool
	prefix     bytes.Buffer
}

func (w *deferredStreamResponse) WriteHeader(status int) {
	if w.status == 0 {
		w.status = status
	}
}
func (w *deferredStreamResponse) commit() {
	if w.committed {
		return
	}
	w.committed = true
	if w.status == 0 {
		w.status = http.StatusOK
	}
	w.ResponseWriter.WriteHeader(w.status)
}
func (w *deferredStreamResponse) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	// Multipart boundaries may be written before ServeContent reads any media.
	// Keep that small preamble pending too, so it cannot disguise an empty range.
	if !w.committed && !w.mediaReady.Load() && (w.status == http.StatusOK || w.status == http.StatusPartialContent) && w.prefix.Len()+len(p) <= 16<<10 {
		return w.prefix.Write(p)
	}
	w.commit()
	if w.prefix.Len() > 0 {
		if _, err := w.prefix.WriteTo(w.ResponseWriter); err != nil {
			return 0, err
		}
	}
	return w.ResponseWriter.Write(p)
}

func streamHTTPError(w http.ResponseWriter, kind string, status int) {
	for _, key := range []string{"Content-Length", "Content-Range", "Accept-Ranges", "Content-Type"} {
		w.Header().Del(key)
	}
	w.Header().Set("X-Usenet-Failure", kind)
	// Fixed descriptions avoid leaking article IDs, provider addresses or secrets.
	http.Error(w, "Usenet stream unavailable ("+kind+")", status)
}
