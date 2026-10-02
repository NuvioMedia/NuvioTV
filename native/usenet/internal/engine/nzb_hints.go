package engine

// This sidecar contains numbers and names only: no EBML, RAR header, article or
// media bytes. Hints are scoped to the immutable directory digest and are
// disposable. Fresh yEnc metadata remains authoritative.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"time"
)

const nzbHintBytes = 128 << 10

type nzbAnchor struct {
	Index int   `json:"i"`
	Begin int64 `json:"b"`
	End   int64 `json:"e"`
}

type nzbFileHint struct {
	Index   int         `json:"i"`
	Size    int64       `json:"s"`
	Name    string      `json:"n,omitempty"`
	Anchors []nzbAnchor `json:"a"`
	Used    int64       `json:"u"`
}

type nzbCuesHint struct {
	Key    string `json:"k"`
	Offset int64  `json:"o"`
	Used   int64  `json:"u"`
}

type nzbHints struct {
	Version    int           `json:"v"`
	Generation [32]byte      `json:"g"`
	Files      []nzbFileHint `json:"f"`
	Cues       []nzbCuesHint `json:"c"`
}

func (d *nzbSnapshot) readHints() nzbHints {
	result := nzbHints{Version: 1, Generation: d.generation}
	path := filepath.Join(d.cache.dir, d.key+".hints")
	if f, err := os.Open(path); err == nil {
		info, err := f.Stat()
		if err == nil && info.Size() > 32 && info.Size() <= nzbHintBytes {
			b := make([]byte, int(info.Size()))
			if _, err := f.ReadAt(b, 0); err == nil {
				hash := sha256.Sum256(b[32:])
				var hints nzbHints
				if bytes.Equal(hash[:], b[:32]) && json.Unmarshal(b[32:], &hints) == nil && hints.Version == 1 && hints.Generation == d.generation && len(hints.Files) <= 32 && len(hints.Cues) <= 16 {
					result = hints
				}
			}
		}
		f.Close()
	}
	return result
}

func (d *nzbSnapshot) bind(files []*File, store *Store) {
	d.bound = files
	d.hints = d.readHints()
	store.mu.Lock()
	if store.closed {
		store.mu.Unlock()
		d.Close()
		return
	}
	previous := store.nzb
	store.nzb = d
	store.mu.Unlock()
	if previous != nil {
		previous.Close()
	}
}

func (s *cachedNZBFile) restoreHints(f *File) {
	s.doc.hintMu.Lock()
	var hint *nzbFileHint
	for _, h := range s.doc.hints.Files {
		if h.Index == f.Index {
			hint = &h
			break
		}
	}
	s.doc.hintMu.Unlock()
	if hint == nil || hint.Size <= 0 || len(hint.Name) > 4096 || len(hint.Anchors) == 0 || len(hint.Anchors) > 128 {
		return
	}
	last := nzbAnchor{Index: -1}
	for _, a := range hint.Anchors {
		if a.Index <= last.Index || a.Index >= s.record.Count || a.Begin < last.End || a.End <= a.Begin || a.End > hint.Size ||
			(a.Index == 0 && a.Begin != 0) || (a.Index == s.record.Count-1 && a.End != hint.Size) ||
			(last.Index >= 0 && a.Index == last.Index+1 && a.Begin != last.End) {
			return
		}
		last = a
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.exact {
		return
	}
	for _, a := range hint.Anchors {
		seg := &f.segments[a.Index]
		seg.begin, seg.end, seg.known = a.Begin, a.End, true
		seg.hint = true
		f.known = append(f.known, a.Index)
	}
	f.size, f.exact, f.hintSize, f.recoveredName = hint.Size, true, true, hint.Name
	f.learnedAt = hint.Used
}

func (c *Content) startupCache() (*nzbSnapshot, string) {
	f, offset := c.direct, int64(0)
	if f == nil {
		c.mu.RLock()
		if len(c.parts) > 0 {
			f, offset = c.parts[0].file, c.parts[0].offset
		}
		c.mu.RUnlock()
	}
	if f == nil || f.cached == nil {
		return nil, ""
	}
	b, _ := json.Marshal(struct {
		File         [32]byte
		Name         string
		Size, Offset int64
	}{f.cached.record.Hash, c.Name, c.Size, offset})
	hash := sha256.Sum256(b)
	return f.cached.doc, hex.EncodeToString(hash[:])
}

func (d *nzbSnapshot) cuesHint(key string, size int64) (int64, bool) {
	if d == nil {
		return 0, false
	}
	d.hintMu.Lock()
	defer d.hintMu.Unlock()
	for _, h := range d.hints.Cues {
		if h.Key == key && h.Offset >= 0 && h.Offset < size {
			return h.Offset, true
		}
	}
	return 0, false
}

func (d *nzbSnapshot) rememberCues(key string, offset int64) {
	if d == nil {
		return
	}
	d.hintMu.Lock()
	defer d.hintMu.Unlock()
	for i, h := range d.hints.Cues {
		if h.Key == key {
			d.hints.Cues = append(d.hints.Cues[:i], d.hints.Cues[i+1:]...)
			break
		}
	}
	d.hints.Cues = append([]nzbCuesHint{{Key: key, Offset: offset, Used: time.Now().UnixNano()}}, d.hints.Cues...)
	d.hints.Cues = d.hints.Cues[:min(len(d.hints.Cues), 16)]
}

// Save after useful startup work and at session close, never once per article.
// Hold hintMu to serialize these small writes, not the cache lock while taking
// file snapshots. Entry growth and temporary bytes share the existing disk cap.
func (d *nzbSnapshot) flushHints() {
	if d == nil {
		return
	}
	d.hintMu.Lock()
	defer d.hintMu.Unlock()
	hints := d.hints
	byIndex := make(map[int]nzbFileHint)
	for _, h := range hints.Files {
		byIndex[h.Index] = h
	}
	for _, f := range d.bound {
		f.mu.RLock()
		if f.exact && len(f.known) > 0 {
			h := nzbFileHint{Index: f.Index, Size: f.size, Name: f.recoveredName, Used: f.learnedAt}
			if len(h.Name) > 4096 {
				h.Name = ""
			}
			// Keep head/tail anchors plus a sparse sample through the rest.
			for j, i := range f.known {
				if len(f.known) <= 128 || j < 16 || j >= len(f.known)-16 || (j-16)%max(1, (len(f.known)-32+95)/96) == 0 {
					s := f.segments[i]
					h.Anchors = append(h.Anchors, nzbAnchor{Index: i, Begin: s.begin, End: s.end})
				}
			}
			byIndex[f.Index] = h
		}
		f.mu.RUnlock()
	}
	c := d.cache
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.filling || c.invalid[d.key] || c.active[d.key] == 0 {
		return
	}
	// Merge another live session's completed work before replacing the sidecar.
	latest := d.readHints()
	for _, h := range latest.Files {
		if previous, ok := byIndex[h.Index]; !ok || h.Used > previous.Used {
			byIndex[h.Index] = h
		}
	}
	byContent := make(map[string]nzbCuesHint)
	for _, h := range hints.Cues {
		byContent[h.Key] = h
	}
	for _, h := range latest.Cues {
		if previous, ok := byContent[h.Key]; !ok || h.Used > previous.Used {
			byContent[h.Key] = h
		}
	}
	hints.Cues = nil
	for _, h := range byContent {
		hints.Cues = append(hints.Cues, h)
	}
	sort.Slice(hints.Cues, func(i, j int) bool { return hints.Cues[i].Used > hints.Cues[j].Used })
	hints.Cues = hints.Cues[:min(len(hints.Cues), 16)]
	hints.Files = nil
	for _, h := range byIndex {
		hints.Files = append(hints.Files, h)
	}
	sort.Slice(hints.Files, func(i, j int) bool { return hints.Files[i].Used > hints.Files[j].Used })
	hints.Files = hints.Files[:min(len(hints.Files), 32)]
	if len(hints.Files) == 0 && len(hints.Cues) == 0 {
		return
	}
	var data []byte
	for {
		b, err := json.Marshal(hints)
		if err != nil {
			return
		}
		if len(b)+32 <= nzbHintBytes {
			hash := sha256.Sum256(b)
			data = append(hash[:], b...)
			break
		}
		if len(hints.Files) == 0 {
			return
		}
		hints.Files = hints.Files[:len(hints.Files)-1]
	}
	// Cache clearing/OS eviction must not recreate orphan hints.
	if _, err := os.Stat(filepath.Join(c.dir, d.key+".nzb")); err != nil {
		return
	}
	if !c.pruneSpace(int64(len(data)), false) {
		return
	}
	f, err := os.CreateTemp(c.dir, "nzb-*.part")
	if err != nil {
		return
	}
	defer os.Remove(f.Name())
	n, err := f.Write(data)
	closeErr := f.Close()
	if err != nil || n != len(data) || closeErr != nil {
		return
	}
	if os.Rename(f.Name(), filepath.Join(c.dir, d.key+".hints")) == nil {
		d.hints = hints
	}
}
