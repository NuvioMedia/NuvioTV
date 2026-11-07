package engine

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRefusedNZBIsNotAskedForAgainUntilRetryAfter(t *testing.T) {
	for _, persist := range []bool{true, false} {
		t.Run(map[bool]string{true: "cache-on", false: "cache-off"}[persist], func(t *testing.T) {
			var calls atomic.Int32
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				w.Header().Set("Retry-After", "38059")
				http.Error(w, `<error code="429" description="This NZB was already downloaded in the last 24 hours."/>`, http.StatusTooManyRequests)
			}))
			defer srv.Close()
			dir := t.TempDir()
			refusals := newNZBCache(dir)
			var cache *nzbCache
			if persist {
				cache = refusals
			}
			fetch := func(r *nzbCache, c *nzbCache) error {
				_, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, c, r, "unscoped", "", nil)
				return err
			}
			err := fetch(refusals, cache)
			if err == nil || !strings.Contains(err.Error(), "HTTP 429") || !strings.Contains(err.Error(), "10h34m") {
				t.Fatalf("first refusal = %v", err)
			}
			// The app's auto-retry and source fallback ask again at once.
			if err := fetch(refusals, cache); err == nil || calls.Load() != 1 {
				t.Fatalf("refused NZB asked for again: %d requests, %v", calls.Load(), err)
			}
			retries, _ := filepath.Glob(filepath.Join(dir, "*.retry"))
			if persist != (len(retries) == 1) {
				t.Fatalf("persisted refusals = %v with cache on=%t", retries, persist)
			}
			// A sidecar restarted after idling keeps the refusal only when the cache is on.
			restarted := newNZBCache(dir)
			var restartedCache *nzbCache
			if persist {
				restartedCache = restarted
			}
			_ = fetch(restarted, restartedCache)
			if want := map[bool]int32{true: 1, false: 2}[persist]; calls.Load() != want {
				t.Fatalf("after restart %d requests, want %d", calls.Load(), want)
			}
		})
	}
}

func TestExpiredRefusalAllowsTheNZBAgain(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()
	dir := t.TempDir()
	c := newNZBCache(dir)
	_, _ = fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, c, c, "", "", nil)
	for key := range c.refused {
		c.refused[key] = time.Now().Add(-time.Second)
		if err := os.WriteFile(filepath.Join(dir, key+".retry"), []byte("1"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	_, _ = fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, c, c, "", "", nil)
	if calls.Load() != 2 {
		t.Fatalf("expired refusal still blocks: %d requests", calls.Load())
	}
}

func TestRefusalDelay(t *testing.T) {
	now := time.Date(2026, 10, 12, 1, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		header string
		want   time.Duration
	}{
		{"120", 2 * time.Minute},
		{"38059", 38059 * time.Second},
		{now.Add(90 * time.Minute).Format(http.TimeFormat), 90 * time.Minute},
		{"", nzbRefusalDefault},
		{"soon", nzbRefusalDefault},
		{"-5", nzbRefusalDefault},
		{"999999999", nzbRefusalMax},
	} {
		if got := refusalDelay(tc.header, now); got != tc.want {
			t.Errorf("refusalDelay(%q) = %s, want %s", tc.header, got, tc.want)
		}
	}
}
