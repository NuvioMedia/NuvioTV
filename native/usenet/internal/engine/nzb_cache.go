package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
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

// Only NZB metadata goes to disk; article buffers are never serialized.
type nzbCache struct {
	mu      sync.Mutex
	dir     string
	filling bool
	active  map[string]int
	invalid map[string]bool
	refused map[string]time.Time
}

const (
	// Without Retry-After a refused NZB waits this long; nzb.life asks 24 h for a repeat download.
	nzbRefusalDefault = 5 * time.Minute
	nzbRefusalMax     = 24 * time.Hour
)

func newNZBCache(dir string) *nzbCache {
	if dir == "" {
		return nil
	}
	c := &nzbCache{dir: dir, active: make(map[string]int), invalid: make(map[string]bool), refused: make(map[string]time.Time)}
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
	// The User-Agent names the app version, which must not empty the cache on
	// every update; it never changes which document a server returns.
	headers := req.Header.Clone()
	headers.Del("User-Agent")
	b, _ := json.Marshal(struct {
		Scope, URL string
		Headers    http.Header
	}{scope, req.URL.String(), headers})
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// adopt renames an entry stored under [legacyKey] to [key] when [key] has none, so a client that
// changes its scope keeps what it cached. Without this, every NZB is fetched once more after the
// change, and nzb.life refuses an NZB downloaded in the last 24 hours.
func (c *nzbCache) adopt(legacyKey, key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.active[legacyKey] > 0 {
		return
	}
	target := filepath.Join(c.dir, key+".nzb")
	if _, err := os.Stat(target); err == nil {
		return
	}
	if os.Rename(filepath.Join(c.dir, legacyKey+".nzb"), target) == nil {
		_ = os.Rename(filepath.Join(c.dir, legacyKey+".hints"), filepath.Join(c.dir, key+".hints"))
		delete(c.invalid, legacyKey)
	}
}

// refusedUntil reports when an NZB an indexer refused with 429 may be asked for again.
// A refusal is kept in memory and, when [persist] (the NZB cache is on), on disk, so
// the app's retries, source fallback and a restarted sidecar do not ask again sooner.
func (c *nzbCache) refusedUntil(key string, persist bool) time.Time {
	if c == nil {
		return time.Time{}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	until := c.refused[key]
	if until.IsZero() && persist {
		if b, err := os.ReadFile(filepath.Join(c.dir, key+".retry")); err == nil {
			if sec, err := strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64); err == nil {
				until = time.Unix(sec, 0)
			}
		}
	}
	if !until.IsZero() && !time.Now().Before(until) {
		delete(c.refused, key)
		_ = os.Remove(filepath.Join(c.dir, key+".retry"))
		return time.Time{}
	}
	return until
}

func (c *nzbCache) refuse(key string, until time.Time, persist bool) {
	if c == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.refused[key] = until
	if persist && os.MkdirAll(c.dir, 0o700) == nil {
		_ = os.WriteFile(filepath.Join(c.dir, key+".retry"), []byte(strconv.FormatInt(until.Unix(), 10)), 0o600)
	}
}

// refusalDelay reads Retry-After as seconds or an HTTP date, bounded to a day.
func refusalDelay(header string, now time.Time) time.Duration {
	d := nzbRefusalDefault
	if s, err := strconv.ParseInt(strings.TrimSpace(header), 10, 64); err == nil && s > 0 {
		d = time.Duration(min(s, int64(nzbRefusalMax/time.Second))) * time.Second
	} else if t, err := http.ParseTime(header); err == nil && t.After(now) {
		d = t.Sub(now)
	}
	return min(d, nzbRefusalMax)
}

func (c *nzbCache) read(key string, store *Store) ([]*File, string, int64) {
	if c == nil {
		return nil, "disabled", 0
	}
	c.mu.Lock()
	if c.invalid[key] {
		c.mu.Unlock()
		return nil, "invalid", 0
	}
	path := filepath.Join(c.dir, key+".nzb")
	f, err := os.Open(path)
	if err != nil {
		c.mu.Unlock()
		if os.IsNotExist(err) {
			return nil, "missing", 0
		}
		return nil, "disk_error", 0
	}
	reason := "invalid"
	info, err := f.Stat()
	if err == nil && info.Mode().IsRegular() && info.Size() > 0 && info.Size() <= nzbCacheEntryBytes &&
		time.Since(info.ModTime()) >= 0 && time.Since(info.ModTime()) < nzbCacheLifetime {
		var magic [8]byte
		_, _ = f.ReadAt(magic[:], 0)
		if string(magic[:]) == nzbIndexMagic {
			doc, indexErr := c.readIndex(f, key, info.Size())
			if indexErr == nil {
				now := time.Now()
				_ = os.Chtimes(path, now, now)
				c.mu.Unlock()
				files := doc.files(store)
				if store == nil {
					defer doc.Close()
					for _, file := range files {
						if file.loadSegments() != nil {
							return nil, "invalid", 0
						}
					}
				} else {
					doc.bind(files, store)
				}
				return files, "hit", info.Size()
			}
			f.Close()
			c.remove(key)
			c.mu.Unlock()
			return nil, "invalid", 0
		}
		// Legacy XML/gzip entries pay for parsing once, then migrate in place.
		c.mu.Unlock()
		files, parseErr := ParseNZB(f, store)
		f.Close()
		if parseErr == nil {
			now := time.Now()
			_ = os.Chtimes(path, now, now)
			if fill, _ := c.begin(key); fill != nil {
				if fill.indexed(files) == "saved" {
					files = c.bindSaved(key, files, store)
				}
			}
			return files, "hit", info.Size()
		}
		c.mu.Lock()
	} else {
		f.Close()
		if err == nil && time.Since(info.ModTime()) >= nzbCacheLifetime {
			reason = "expired"
		}
	}
	c.remove(key)
	c.mu.Unlock()
	return nil, reason, 0
}

// Called under mu. Active snapshots are pinned so their disk usage stays in
// the budget and a later lazy volume read survives ordinary cache eviction.
func (c *nzbCache) remove(key string) bool {
	if c.active[key] > 0 {
		return false
	}
	if err := os.Remove(filepath.Join(c.dir, key+".hints")); err != nil && !os.IsNotExist(err) {
		return false
	}
	if err := os.Remove(filepath.Join(c.dir, key+".nzb")); err != nil && !os.IsNotExist(err) {
		return false
	}
	delete(c.invalid, key)
	return true
}

func (c *nzbCache) bindSaved(key string, files []*File, store *Store) []*File {
	if store == nil {
		return files
	}
	c.mu.Lock()
	f, err := os.Open(filepath.Join(c.dir, key+".nzb"))
	var doc *nzbSnapshot
	if err == nil {
		if info, e := f.Stat(); e == nil {
			doc, err = c.readIndex(f, key, info.Size())
		} else {
			err = e
		}
		if err != nil {
			f.Close()
		}
	}
	c.mu.Unlock()
	if doc != nil {
		// Release the full cold parse too. Otherwise the descriptor would keep
		// every unselected episode's segment table alive for the whole session.
		files = doc.files(store)
		doc.bind(files, store)
	}
	return files
}

// Called under mu, or during construction before the server starts. Reserve
// the active fill's next growth step, including bytes already in its .part file.
// The single-fill guard ensures there is only one temporary file to account for.
func (c *nzbCache) prune(reserve int64) bool {
	return c.pruneSpace(reserve, reserve > 0)
}

func (c *nzbCache) pruneSpace(reserve int64, newEntry bool) bool {
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return false
	}
	var files []os.FileInfo
	var size int64
	weights := make(map[string]int64)
	for _, e := range entries {
		name := e.Name()
		if !e.IsDir() && len(name) == 70 && strings.HasSuffix(name, ".retry") {
			// Refusals last at most a day; one older than that has expired unread.
			if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > nzbRefusalMax {
				_ = os.Remove(filepath.Join(c.dir, name))
			}
			continue
		}
		if !e.IsDir() && len(name) == 70 && strings.HasSuffix(name, ".hints") {
			if _, err := os.Stat(filepath.Join(c.dir, name[:64]+".nzb")); os.IsNotExist(err) {
				if err := os.Remove(filepath.Join(c.dir, name)); err != nil && !os.IsNotExist(err) {
					return false
				}
			}
		}
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
		if age < 0 || age >= nzbCacheLifetime || info.Size() > nzbCacheEntryBytes || c.invalid[name[:64]] {
			if c.remove(name[:64]) {
				continue
			}
		}
		files = append(files, info)
		weight := info.Size()
		if hints, err := os.Stat(filepath.Join(c.dir, name[:64]+".hints")); err == nil {
			weight += hints.Size()
		}
		weights[name] = weight
		size += weight
	}
	sort.Slice(files, func(i, j int) bool { return files[i].ModTime().Before(files[j].ModTime()) })
	count := len(files)
	slots := nzbCacheEntries
	if newEntry {
		slots--
	}
	for _, f := range files {
		if size+reserve <= nzbCacheBytes && count <= slots {
			break
		}
		if c.remove(f.Name()[:64]) {
			size -= weights[f.Name()]
			count--
		}
	}
	return size+reserve <= nzbCacheBytes && count <= slots
}

type nzbCacheFill struct {
	ioTime   time.Duration
	ctx      context.Context
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
	if c.active[key] > 0 {
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
	start := time.Now()
	defer func() { f.ioTime += time.Since(start) }()
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
	start := time.Now()
	defer func() { f.ioTime += time.Since(start) }()
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
	if f.ctx != nil && f.ctx.Err() != nil {
		return "cancelled"
	}
	if f.cache.active[f.key] > 0 {
		return "busy"
	}
	if os.Rename(f.file.Name(), filepath.Join(f.cache.dir, f.key+".nzb")) != nil {
		return "disk_error"
	}
	delete(f.cache.invalid, f.key)
	_ = os.Remove(filepath.Join(f.cache.dir, f.key+".hints"))
	return "saved"
}
