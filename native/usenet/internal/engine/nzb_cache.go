package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	nzbCacheBytes       = 256 << 20
	nzbCacheEntryBytes  = maxNZBBytes
	nzbCacheEntries     = 256
	nzbCacheLifetime    = 14 * 24 * time.Hour // Idle lifetime, renewed on a valid hit.
	nzbCacheReserveStep = 1 << 20
)

// Only NZB documents go to disk. Each hit is parsed into fresh File objects
// belonging to the new Store; article buffers and mutable layouts are not shared.
type nzbCache struct {
	mu      sync.Mutex
	dir     string
	filling bool
}

func newNZBCache(dir string) *nzbCache {
	if dir == "" {
		return nil
	}
	c := &nzbCache{dir: dir}
	// One sidecar owns this private directory. Remove unfinished writes left by
	// an earlier process, without creating a directory when caching is disabled.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if !e.IsDir() && strings.HasPrefix(e.Name(), "nzb-") && strings.HasSuffix(e.Name(), ".part") {
			_ = os.Remove(filepath.Join(dir, e.Name()))
		}
	}
	c.prune(0)
	return c
}

func nzbCacheKey(req *http.Request, scope string) string {
	// JSON sorts header keys. Request URLs and headers contribute only to the
	// digest; they are not written as cache metadata alongside the NZB document.
	b, _ := json.Marshal(struct {
		Scope, URL string
		Headers    http.Header
	}{scope, req.URL.String(), req.Header})
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func (c *nzbCache) read(key string, store *Store) ([]*File, string, int64) {
	if c == nil {
		return nil, "disabled", 0
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	path := filepath.Join(c.dir, key+".nzb")
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, "missing", 0
		}
		return nil, "disk_error", 0
	}
	reason := "invalid"
	info, err := f.Stat()
	if err == nil && info.Mode().IsRegular() && info.Size() > 0 && info.Size() <= nzbCacheEntryBytes &&
		time.Since(info.ModTime()) >= 0 && time.Since(info.ModTime()) < nzbCacheLifetime {
		files, parseErr := ParseNZB(f, store)
		f.Close()
		if parseErr == nil {
			now := time.Now()
			_ = os.Chtimes(path, now, now)
			return files, "hit", info.Size()
		}
	} else {
		f.Close()
		if err == nil && time.Since(info.ModTime()) >= nzbCacheLifetime {
			reason = "expired"
		}
	}
	_ = os.Remove(path)
	return nil, reason, 0
}

// Called under mu, or during construction before the server starts. Reserve
// the active fill's next growth step, including bytes already in its .part file.
// The single-fill guard ensures there is only one temporary file to account for.
func (c *nzbCache) prune(reserve int64) bool {
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return false
	}
	var files []os.FileInfo
	var size int64
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || len(name) != 68 || !strings.HasSuffix(name, ".nzb") {
			continue
		}
		if _, err := hex.DecodeString(name[:64]); err != nil {
			continue
		}
		info, err := e.Info()
		if err != nil {
			return false
		}
		age := time.Since(info.ModTime())
		if age < 0 || age >= nzbCacheLifetime || info.Size() > nzbCacheEntryBytes {
			if os.Remove(filepath.Join(c.dir, name)) == nil {
				continue
			}
		}
		files = append(files, info)
		size += info.Size()
	}
	sort.Slice(files, func(i, j int) bool { return files[i].ModTime().Before(files[j].ModTime()) })
	count := len(files)
	slots := nzbCacheEntries
	if reserve > 0 {
		slots--
	}
	for _, f := range files {
		if size+reserve <= nzbCacheBytes && count <= slots {
			break
		}
		if os.Remove(filepath.Join(c.dir, f.Name())) == nil {
			size -= f.Size()
			count--
		}
	}
	return size+reserve <= nzbCacheBytes && count <= slots
}

type nzbCacheFill struct {
	cache    *nzbCache
	file     *os.File
	key      string
	written  int64
	reserved int64
	failure  string
}

func (c *nzbCache) begin(key string) (*nzbCacheFill, string) {
	if c == nil {
		return nil, "disabled"
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.filling {
		return nil, "busy"
	}
	if os.MkdirAll(c.dir, 0700) != nil {
		return nil, "disk_error"
	}
	f, err := os.CreateTemp(c.dir, "nzb-*.part")
	if err != nil {
		return nil, "disk_error"
	}
	c.filling = true
	return &nzbCacheFill{cache: c, file: f, key: key}, ""
}

func (f *nzbCacheFill) Write(p []byte) (int, error) {
	if f.failure == "" {
		if int64(len(p)) > nzbCacheEntryBytes-f.written {
			f.failure = "oversized"
		} else {
			needed := f.written + int64(len(p))
			if needed > f.reserved {
				reserve := min(int64(nzbCacheEntryBytes), ((needed+nzbCacheReserveStep-1)/nzbCacheReserveStep)*nzbCacheReserveStep)
				f.cache.mu.Lock()
				ok := f.cache.prune(reserve)
				f.cache.mu.Unlock()
				if ok {
					f.reserved = reserve
				} else {
					f.failure = "disk_error"
				}
			}
			if f.failure == "" {
				n, err := f.file.Write(p)
				f.written += int64(n)
				if err != nil || n != len(p) {
					f.failure = "disk_error"
				}
			}
		}
	}
	// Cache failures and documents too large to cache never fail playback.
	return len(p), nil
}

func (f *nzbCacheFill) finish(valid bool) string {
	err := f.file.Close()
	f.cache.mu.Lock()
	defer f.cache.mu.Unlock()
	defer os.Remove(f.file.Name())
	f.cache.filling = false
	if f.failure != "" {
		return f.failure
	}
	if err != nil {
		return "disk_error"
	}
	if !valid || f.written == 0 {
		return "invalid"
	}
	if os.Rename(f.file.Name(), filepath.Join(f.cache.dir, f.key+".nzb")) != nil {
		return "disk_error"
	}
	return "saved"
}
