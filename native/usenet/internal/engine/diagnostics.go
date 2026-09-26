package engine

import (
	"io"
	"sync"
	"time"
)

// Bounded, session-local timings. Never record URLs, credentials, message IDs,
// filenames or payloads. These clocks measure overlapping work, not an additive
// CPU breakdown. HTTP read time includes both layout discovery and NNTP waits.
type startupTrace struct {
	mu       sync.Mutex
	start    time.Time
	marks    map[string]float64
	ranges   []*rangeTiming
	nzbCache *nzbCacheDiagnostic
}

// Only fixed outcomes and a byte count; never include cache keys or URLs.
type nzbCacheDiagnostic struct {
	Lookup string `json:"lookup"`
	Reason string `json:"reason,omitempty"`
	Write  string `json:"write,omitempty"`
	Format string `json:"format,omitempty"`
	Bytes  int64  `json:"bytes"`
}

func (t *startupTrace) recordNZBCache(v nzbCacheDiagnostic) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.nzbCache = &v
}

func (s *Session) diagnostics() map[string]any {
	v := s.trace.snapshot()
	if v == nil {
		v = make(map[string]any)
	}
	s.store.mu.Lock()
	v["store"] = s.store.stats
	v["articleSlabsBytes"] = s.store.allocated
	s.store.mu.Unlock()
	if s.content != nil {
		v["archiveDiscoveryMs"] = float64(s.content.layoutNS.Load()) / 1e6
		v["archiveWaitMs"] = float64(s.content.layoutWaitNS.Load()) / 1e6
		s.content.mu.RLock()
		v["archiveExtents"] = len(s.content.parts)
		s.content.mu.RUnlock()
	}
	return v
}

type rangeTiming struct {
	StartMS     float64 `json:"startMs"`
	Offset      int64   `json:"offset"`
	FirstByteMS float64 `json:"firstByteMs"`
	ReadMS      float64 `json:"readMs"`
	Bytes       int64   `json:"bytes"`
}

func newStartupTrace() *startupTrace {
	return &startupTrace{start: time.Now(), marks: make(map[string]float64)}
}

func (t *startupTrace) mark(name string) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, exists := t.marks[name]; !exists {
		t.marks[name] = float64(time.Since(t.start).Microseconds()) / 1000
	}
}

func (t *startupTrace) snapshot() map[string]any {
	if t == nil {
		return nil
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	marks := make(map[string]float64, len(t.marks))
	for k, v := range t.marks {
		marks[k] = v
	}
	ranges := make([]rangeTiming, len(t.ranges))
	for i, v := range t.ranges {
		ranges[i] = *v
	}
	v := map[string]any{"marksMs": marks, "ranges": ranges}
	if t.nzbCache != nil {
		v["nzbCache"] = *t.nzbCache
	}
	return v
}

type timingReader struct {
	*ContentReader
	trace  *startupTrace
	timing *rangeTiming
}

func (t *startupTrace) reader(r *ContentReader) io.ReadSeeker {
	if t == nil {
		return r
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.ranges) >= 24 || time.Since(t.start) > 30*time.Second {
		return r
	}
	record := &rangeTiming{StartMS: float64(time.Since(t.start).Microseconds()) / 1000, FirstByteMS: -1}
	t.ranges = append(t.ranges, record)
	return &timingReader{ContentReader: r, trace: t, timing: record}
}

func (r *timingReader) Read(p []byte) (int, error) {
	off := r.ContentReader.pos
	start := time.Now()
	n, err := r.ContentReader.Read(p)
	r.trace.mu.Lock()
	if n > 0 && r.timing.FirstByteMS < 0 {
		r.timing.Offset = off
		r.timing.FirstByteMS = float64(time.Since(r.trace.start).Microseconds()) / 1000
	}
	r.timing.ReadMS += float64(time.Since(start).Microseconds()) / 1000
	r.timing.Bytes += int64(n)
	r.trace.mu.Unlock()
	return n, err
}
