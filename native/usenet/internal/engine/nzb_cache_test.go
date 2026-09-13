package engine

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestNZBCachePersistsAndSeparatesHeadersAndProfiles(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	var requests atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		io.WriteString(w, xml)
	}))
	defer srv.Close()
	dir := t.TempDir()
	cache := newNZBCache(dir)
	read := func(c *nzbCache, scope, auth string) *File {
		t.Helper()
		store := NewStore(context.Background(), stalledBody{}, 32<<20)
		t.Cleanup(store.Close)
		files, err := fetchNZB(context.Background(), srv.Client(), srv.URL, map[string]string{"Authorization": auth}, store, false, c, scope, nil)
		if err != nil {
			t.Fatal(err)
		}
		return files[0]
	}
	a := read(cache, "1", "private-a")
	b := read(newNZBCache(dir), "1", "private-a")
	if requests.Load() != 1 || a == b || a.store == b.store {
		t.Fatal("cache must survive a new engine and bind metadata to a fresh store")
	}
	read(cache, "2", "private-a")
	read(cache, "1", "private-b")
	if requests.Load() != 3 {
		t.Fatal("cache crossed a profile or credential boundary")
	}
	read(nil, "1", "private-a")
	read(nil, "1", "private-a")
	if requests.Load() != 5 {
		t.Fatal("disabled cache must fetch normally")
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 3 {
		t.Fatalf("cache files=%d", len(entries))
	}
	for _, e := range entries {
		if len(e.Name()) != 68 || !strings.HasSuffix(e.Name(), ".nzb") {
			t.Fatalf("unexpected cache file %s", e.Name())
		}
	}
}

func TestNZBCacheExpiryAndCorruptionFallBack(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(512), nil}})
	var requests atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1); io.WriteString(w, xml) }))
	defer srv.Close()
	cache := newNZBCache(t.TempDir())
	read := func() {
		t.Helper()
		if _, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, cache, "1", nil); err != nil {
			t.Fatal(err)
		}
	}
	read()
	entries, _ := os.ReadDir(cache.dir)
	file := filepath.Join(cache.dir, entries[0].Name())
	old := time.Now().Add(-nzbCacheLifetime - time.Hour)
	if err := os.Chtimes(file, old, old); err != nil {
		t.Fatal(err)
	}
	read()
	if requests.Load() != 2 {
		t.Fatal("expired NZB reused")
	}
	if err := os.WriteFile(file, []byte("broken XML"), 0600); err != nil {
		t.Fatal(err)
	}
	read()
	if requests.Load() != 3 {
		t.Fatal("corrupt NZB did not refetch")
	}
}

func TestNZBCacheBoundsAndAbandonedWrites(t *testing.T) {
	cache := newNZBCache(t.TempDir())
	for i := 0; i < 36; i++ {
		req, _ := http.NewRequest("GET", "https://indexer.invalid/"+strings.Repeat("x", i), nil)
		fill, _ := cache.begin(nzbCacheKey(req, "1"))
		if fill == nil {
			t.Fatal("cannot create cache entry")
		}
		fill.Write(make([]byte, 1<<20))
		fill.finish(true)
	}
	entries, _ := os.ReadDir(cache.dir)
	var total int64
	for _, e := range entries {
		info, _ := e.Info()
		total += info.Size()
	}
	if total > nzbCacheBytes || len(entries) > nzbCacheEntries {
		t.Fatalf("unbounded cache: %d bytes / %d files", total, len(entries))
	}
	fill, _ := cache.begin(strings.Repeat("a", 64))
	fill.Write(make([]byte, nzbCacheEntryBytes+1))
	fill.finish(true)
	if _, err := os.Stat(filepath.Join(cache.dir, strings.Repeat("a", 64)+".nzb")); !os.IsNotExist(err) {
		t.Fatal("oversized entry committed")
	}
	fill, _ = cache.begin(strings.Repeat("b", 64))
	fill.Write([]byte("partial"))
	fill.finish(false)
	if _, err := os.Stat(filepath.Join(cache.dir, strings.Repeat("b", 64)+".nzb")); !os.IsNotExist(err) {
		t.Fatal("abandoned entry committed")
	}
	partial := filepath.Join(cache.dir, "nzb-crash.part")
	os.WriteFile(partial, []byte("partial"), 0600)
	newNZBCache(cache.dir)
	if _, err := os.Stat(partial); !os.IsNotExist(err) {
		t.Fatal("process restart retained partial write")
	}
}
