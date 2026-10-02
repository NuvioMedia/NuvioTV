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
	mu        sync.Mutex
	start     time.Time
	marks     map[string]float64
	durations map[string]float64
	ranges    []*rangeTiming
	nzbCache  *nzbCacheDiagnostic
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
	v["streamFailure"] = s.failure.get()
	if holes := s.store.holes; holes != nil {
		holes.mu.Lock()
		v["filledArticles"] = holes.count
		holes.mu.Unlock()
	}
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
	Failure     string  `json:"failure,omitempty"`
}

func newStartupTrace() *startupTrace {
	return &startupTrace{start: time.Now(), marks: make(map[string]float64), durations: make(map[string]float64)}
}

func (t *startupTrace) duration(name string, elapsed time.Duration) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.durations[name] = float64(elapsed.Microseconds()) / 1000
}

// Measure blocking reads without buffering the document again. Parsing and
// download overlap: parse work is wall time outside Body.Read, not CPU time.
type nzbTimingReader struct {
	r       io.Reader
	elapsed time.Duration
}

func (r *nzbTimingReader) Read(p []byte) (int, error) {
	start := time.Now()
	n, err := r.r.Read(p)
	r.elapsed += time.Since(start)
	return n, err
}

func parseNZBTraced(r io.Reader, store *Store, trace *startupTrace) ([]*File, error) {
	if trace == nil {
		return ParseNZB(r, store)
	}
	trace.mark("nzb_parse_started")
	start := time.Now()
	body := &nzbTimingReader{r: r}
	files, err := ParseNZB(body, store)
	trace.duration("nzb_body_read", body.elapsed)
	trace.duration("nzb_parse_work", time.Since(start)-body.elapsed)
	trace.mark("nzb_parse_finished")
	return files, err
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
	durations := make(map[string]float64, len(t.durations))
	for k, d := range t.durations {
		durations[k] = d
	}
	v := map[string]any{"marksMs": marks, "durationsMs": durations, "ranges": ranges}
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
	if r.timing.FirstByteMS < 0 {
		r.timing.Offset = off
	}
	if n > 0 && r.timing.FirstByteMS < 0 {
		r.timing.Offset = off
		r.timing.FirstByteMS = float64(time.Since(r.trace.start).Microseconds()) / 1000
		if _, exists := r.trace.marks["first_media_bytes"]; !exists {
			r.trace.marks["first_media_bytes"] = r.timing.FirstByteMS
		}
	}
	r.timing.ReadMS += float64(time.Since(start).Microseconds()) / 1000
	r.timing.Bytes += int64(n)
	if err != nil && err != io.EOF {
		r.timing.Failure = permanentStreamFailure(err)
		if r.timing.Failure == "" {
			r.timing.Failure = "temporary-read-error"
		}
	}
	r.trace.mu.Unlock()
	return n, err
}
