package engine

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type closedPool struct{}

func (closedPool) Close() error { return nil }

// Memory goes back to the OS only once the last session is closed: a session
// that keeps playing must not pay for a forced collection.
func TestDeletingTheLastSessionReturnsMemory(t *testing.T) {
	freed := make(chan struct{}, 4)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, http.DefaultClient)
	app.freeOSMemory = func() { freed <- struct{}{} }
	app.idleMemoryDelay = 10 * time.Millisecond
	defer app.Close()
	for _, id := range []string{"a", "b"} {
		sctx, scancel := context.WithCancel(ctx)
		app.sessions[id] = &Session{ctx: sctx, cancel: scancel, store: NewStore(sctx, nil, 1<<20), pool: closedPool{}}
	}
	remove := func(id string) {
		t.Helper()
		r := httptest.NewRequest(http.MethodDelete, "/sessions/"+id, nil)
		r.Header.Set("Authorization", "Bearer secret")
		w := httptest.NewRecorder()
		app.ServeHTTP(w, r)
		if w.Code != http.StatusNoContent {
			t.Fatalf("DELETE %s = %d", id, w.Code)
		}
	}

	remove("a")
	select {
	case <-freed:
		t.Fatal("memory was released while a session was still open")
	case <-time.After(100 * time.Millisecond):
	}
	remove("b")
	select {
	case <-freed:
	case <-time.After(2 * time.Second):
		t.Fatal("memory was not released after the last session closed")
	}
	remove("b")
	select {
	case <-freed:
		t.Fatal("deleting an unknown session released memory again")
	case <-time.After(100 * time.Millisecond):
	}
}
