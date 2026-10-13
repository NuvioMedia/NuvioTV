package engine

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
)

func TestEntryCachedUnderTheFormerScopeIsAdopted(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	var calls atomic.Int32
	refuse := atomic.Bool{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if refuse.Load() {
			// nzb.life refuses an NZB downloaded in the last 24 hours.
			w.Header().Set("Retry-After", "3600")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		io.WriteString(w, xml)
	}))
	defer srv.Close()
	dir := t.TempDir()
	cache := newNZBCache(dir)
	fetch := func(scope, legacy string) error {
		_, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, cache, cache, scope, legacy, nil)
		return err
	}
	if err := fetch("7", ""); err != nil { // Cached by a build that scoped entries by profile.
		t.Fatal(err)
	}
	refuse.Store(true)
	if err := fetch("unscoped", "7"); err != nil || calls.Load() != 1 {
		t.Fatalf("former entry not adopted: %d requests, %v", calls.Load(), err)
	}
	entries, _ := filepath.Glob(filepath.Join(dir, "*.nzb"))
	if len(entries) != 1 {
		t.Fatalf("entries after adoption = %v", entries)
	}
	// Adopted under the new key: later opens need no legacy scope.
	if err := fetch("unscoped", ""); err != nil || calls.Load() != 1 {
		t.Fatalf("adopted entry missed: %d requests, %v", calls.Load(), err)
	}
	// Another profile's legacy scope does not replace an existing entry.
	if err := fetch("unscoped", "8"); err != nil || calls.Load() != 1 {
		t.Fatalf("existing entry replaced: %d requests, %v", calls.Load(), err)
	}
}

func TestAdoptionKeepsAnEntryAlreadyUnderTheNewKey(t *testing.T) {
	dir := t.TempDir()
	c := newNZBCache(dir)
	for _, name := range []string{"old.nzb", "new.nzb"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(name), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	c.adopt("old", "new")
	if b, _ := os.ReadFile(filepath.Join(dir, "new.nzb")); string(b) != "new.nzb" {
		t.Fatalf("new entry overwritten: %q", b)
	}
}
