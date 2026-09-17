package engine

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestLargeNZBMetadataWithLowMemoryTarget(t *testing.T) {
	const segments = 160000
	f, err := os.CreateTemp(t.TempDir(), "large-*.nzb")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	io.WriteString(f, `<nzb><file subject='"movie.mkv" yEnc (1/1)'><segments>`)
	idSuffix := strings.Repeat("x", 180) + ".invalid"
	for i := 1; i <= segments; i++ {
		if _, err := fmt.Fprintf(f, `<segment bytes="768000" number="%d">%d@%s</segment>`, i, i, idSuffix); err != nil {
			t.Fatal(err)
		}
	}
	io.WriteString(f, "</segments></file></nzb>")
	size, err := f.Seek(0, io.SeekCurrent)
	if err != nil || size <= 32<<20 {
		t.Fatalf("fixture size=%d: %v", size, err)
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	previous := debug.SetMemoryLimit(96 << 20) // Android's low-memory Go target.
	defer debug.SetMemoryLimit(previous)
	runtime.GC()
	var peak atomic.Uint64
	done := make(chan struct{})
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		ticker := time.NewTicker(time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				var stats runtime.MemStats
				runtime.ReadMemStats(&stats)
				peak.Store(max(peak.Load(), stats.HeapAlloc))
			}
		}
	}()
	start := time.Now()
	files, err := ParseNZB(f, nil)
	close(done)
	<-stopped
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 || len(files[0].segments) != segments {
		t.Fatal("lost segment metadata")
	}
	t.Logf("XML %.1f MiB, %d segments, parse %s, sampled peak Go heap %.1f MiB under 96 MiB target (host, not Android)", float64(size)/(1<<20), segments, time.Since(start), float64(peak.Load())/(1<<20))
	runtime.KeepAlive(files)
}

// Small XML comments exercise document byte limits without constructing a
// single enormous XML token or retaining artificial segment metadata.
func paddedNZB(size int) string {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	padding := size - len(xml)
	comment := "<!--" + strings.Repeat("x", 4089) + "-->"
	return xml + strings.Repeat(comment, padding/len(comment)) + strings.Repeat(" ", padding%len(comment))
}

func TestLargeNZBCachePlainAndGzip(t *testing.T) {
	// Larger than both the old 8 MiB disk cap and the old 32 MiB parser cap.
	xml := paddedNZB(40 << 20)
	for _, compressed := range []bool{false, true} {
		t.Run(fmt.Sprintf("gzip=%v", compressed), func(t *testing.T) {
			var requests atomic.Int32
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if compressed {
					w.Header().Set("Content-Encoding", "gzip")
					gz := gzip.NewWriter(w)
					io.WriteString(gz, xml)
					gz.Close()
				} else {
					// Intentionally ignore the fast-fetch compression request.
					io.WriteString(w, xml)
				}
			}))
			defer srv.Close()
			dir := t.TempDir()
			for i := 0; i < 2; i++ {
				trace := newStartupTrace()
				files, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, true, newNZBCache(dir), "profile", trace)
				if err != nil || len(files) != 1 {
					t.Fatalf("fetch: files=%d err=%v", len(files), err)
				}
				d := trace.snapshot()["nzbCache"].(nzbCacheDiagnostic)
				if (i == 0 && (d.Lookup != "miss" || d.Write != "saved")) || (i == 1 && (d.Lookup != "hit" || d.Write != "")) {
					t.Fatalf("unexpected diagnostics: %+v", d)
				}
				if d.Bytes <= 0 || d.Bytes >= int64(len(xml)) {
					t.Fatalf("wrong cached byte count: %+v", d)
				}
			}
			if requests.Load() != 1 {
				t.Fatalf("cached replay made %d HTTP requests", requests.Load())
			}
		})
	}
}

func TestNZBParserExactLimitAndOversize(t *testing.T) {
	xml := paddedNZB(maxNZBBytes)
	for _, compressed := range []bool{false, true} {
		for _, extra := range []string{"", " "} {
			t.Run(fmt.Sprintf("gzip=%v/extra=%d", compressed, len(extra)), func(t *testing.T) {
				var input io.Reader = io.MultiReader(strings.NewReader(xml), strings.NewReader(extra))
				if compressed {
					var b bytes.Buffer
					gz := gzip.NewWriter(&b)
					io.Copy(gz, input)
					gz.Close()
					input = &b
				}
				_, err := ParseNZB(input, nil)
				if extra == "" && err != nil {
					t.Fatalf("exact limit rejected: %v", err)
				}
				if extra != "" && !errors.Is(err, errNZBTooLarge) {
					t.Fatalf("wanted explicit size error, got %v", err)
				}
			})
		}
	}
}

func cachePath(c *nzbCache, i int) string {
	return filepath.Join(c.dir, fmt.Sprintf("%064x.nzb", i))
}

func TestNZBCacheIdleExpiryAndLRU(t *testing.T) {
	c := newNZBCache(t.TempDir())
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	old := time.Now().Add(-13 * 24 * time.Hour)
	for i := 0; i < nzbCacheEntries; i++ {
		p := cachePath(c, i)
		if err := os.WriteFile(p, []byte(xml), 0600); err != nil {
			t.Fatal(err)
		}
		age := old.Add(time.Duration(i) * time.Second)
		if err := os.Chtimes(p, age, age); err != nil {
			t.Fatal(err)
		}
	}
	if _, status, _ := c.read(fmt.Sprintf("%064x", 0), nil); status != "hit" {
		t.Fatalf("13-day-old entry should still hit: %s", status)
	}
	info, err := os.Stat(cachePath(c, 0))
	if err != nil || time.Since(info.ModTime()) > time.Minute {
		t.Fatalf("cache hit did not renew idle expiry: %v", err)
	}
	fill, _ := c.begin(fmt.Sprintf("%064x", nzbCacheEntries))
	if fill == nil {
		t.Fatal("begin")
	}
	fill.Write([]byte(xml))
	if got := fill.finish(true); got != "saved" {
		t.Fatal(got)
	}
	if _, err := os.Stat(cachePath(c, 0)); err != nil {
		t.Fatal("recent hit evicted", err)
	}
	if _, err := os.Stat(cachePath(c, 1)); !os.IsNotExist(err) {
		t.Fatal("oldest unused entry retained")
	}
	entries, _ := os.ReadDir(c.dir)
	if len(entries) != nzbCacheEntries {
		t.Fatalf("entry count=%d", len(entries))
	}
}

func TestNZBCacheReservesActualGrowth(t *testing.T) {
	c := newNZBCache(t.TempDir())
	// Sparse placeholders avoid writing a quarter GiB just to test accounting.
	// Entries are never parsed in this test.
	for i := 0; i < 4; i++ {
		f, err := os.Create(cachePath(c, i))
		if err != nil {
			t.Fatal(err)
		}
		if err := f.Truncate(nzbCacheEntryBytes - (1 << 20)); err != nil {
			t.Fatal(err)
		}
		f.Close()
	}
	fill, _ := c.begin(fmt.Sprintf("%064x", 1000))
	if fill == nil {
		t.Fatal("begin")
	}
	if got := fill.finish(false); got != "invalid" {
		t.Fatal(got)
	}
	entries, _ := os.ReadDir(c.dir)
	if len(entries) != 4 {
		t.Fatal("empty aborted fill evicted entries")
	}
	fill, _ = c.begin(fmt.Sprintf("%064x", 1000))
	if fill == nil {
		t.Fatal("begin")
	}
	fill.Write([]byte("small"))
	if got := fill.finish(true); got != "saved" {
		t.Fatal(got)
	}
	entries, _ = os.ReadDir(c.dir)
	if len(entries) != 5 {
		t.Fatal("small fill reserved a full 64 MiB entry")
	}
	fill, _ = c.begin(fmt.Sprintf("%064x", 1001))
	if fill == nil {
		t.Fatal("begin")
	}
	chunk := make([]byte, 1<<20)
	for i := 0; i < 64; i++ {
		fill.Write(chunk)
		entries, _ := os.ReadDir(c.dir)
		var size int64
		for _, e := range entries {
			info, err := e.Info()
			if err != nil {
				t.Fatal(err)
			}
			size += info.Size()
		}
		if size > nzbCacheBytes {
			t.Fatalf("temporary files exceeded disk cap: %d", size)
		}
	}
	if got := fill.finish(true); got != "saved" {
		t.Fatal(got)
	}
}

func TestNZBCacheDiagnosticsDisabledAndDiskFailure(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	defer srv.Close()
	blocked := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocked, []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, c := range []*nzbCache{nil, newNZBCache(blocked)} {
		trace := newStartupTrace()
		_, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, c, "profile", trace)
		if err != nil {
			t.Fatalf("optional caching broke playback: %v", err)
		}
		d := trace.snapshot()["nzbCache"].(nzbCacheDiagnostic)
		if c == nil && d.Lookup != "disabled" {
			t.Fatalf("%+v", d)
		}
		if c != nil && (d.Lookup != "miss" || d.Write != "disk_error") {
			t.Fatalf("%+v", d)
		}
	}
}
