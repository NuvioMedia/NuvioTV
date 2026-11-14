package engine

import (
	"context"
	"errors"
	"fmt"
	"sync"

	"github.com/javi11/nntppool/v4"
)

var errInvalidArticle = errors.New("invalid Usenet article or layout")
var errHoleLimit = errors.New("Usenet hole filling limit exceeded")

// All spans are bracketed by fresh yEnc metadata, never NZB wire-size estimates.
// The policy belongs to the selected playback, so RAR volumes, overlapping HTTP
// requests and seeks share a budget. Discovery and speculative readers cannot
// spend it. Payloads are generated directly into the caller's buffer.
type holeSpan struct {
	first, last int
	begin, end  int64
}

type holePolicy struct {
	mu                      sync.Mutex
	maxTotal, maxRun, count int
	spans                   map[*File][]holeSpan
}

func newHolePolicy(c Config) *holePolicy {
	if !c.HoleFilling {
		return nil
	}
	total, run := c.MaxMissingArticles, c.MaxConsecutiveMissing
	if total == 0 {
		total = 5
	}
	if run == 0 {
		run = 2
	}
	return &holePolicy{maxTotal: total, maxRun: min(run, total), spans: make(map[*File][]holeSpan)}
}

func (p *holePolicy) lookup(f *File, off int64) (holeSpan, bool) {
	if p == nil {
		return holeSpan{}, false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, span := range p.spans[f] {
		if off >= span.begin && off < span.end {
			return span, true
		}
	}
	return holeSpan{}, false
}

func (p *holePolicy) accept(f *File, span holeSpan) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, old := range p.spans[f] {
		if old.first == span.first && old.last == span.last {
			if old.begin != span.begin || old.end != span.end {
				return errInvalidArticle
			}
			return nil
		}
		if span.first <= old.last && span.last >= old.first {
			return errInvalidArticle
		}
	}
	n := span.last - span.first + 1
	if n > p.maxRun || p.count+n > p.maxTotal {
		return errHoleLimit
	}
	p.spans[f] = append(p.spans[f], span)
	p.count += n
	return nil
}

func (r *FileReader) holeAt(i int) (holeSpan, error) {
	p := r.f.store.holes
	span := holeSpan{first: i, last: i}
	// Segment zero carries container/volume headers and is never replaceable.
	if i == 0 {
		return span, nntppool.ErrArticleNotFound
	}
	learn := func(j int) (segment, error) {
		a := r.get(j, false)
		if a == nil {
			return segment{}, context.Canceled
		}
		m, err := a.metadata(r.ctx)
		if err != nil {
			return segment{}, err
		}
		if err := r.f.learn(j, m); err != nil {
			return segment{}, fmt.Errorf("%w: %v", errInvalidArticle, err)
		}
		r.f.mu.RLock()
		defer r.f.mu.RUnlock()
		return r.f.segments[j], nil
	}
	for j := i - 1; ; j-- {
		seg, err := learn(j)
		if err == nil {
			span.begin = seg.end
			break
		}
		if !errors.Is(err, nntppool.ErrArticleNotFound) {
			return span, err
		}
		if j == 0 {
			return span, err
		}
		span.first = j
		if span.last-span.first+1 > p.maxRun {
			return span, errHoleLimit
		}
	}
	for j := i + 1; ; j++ {
		if j == len(r.f.segments) {
			// The preceding fresh article established the authoritative file size.
			span.end = r.f.Size()
			break
		}
		seg, err := learn(j)
		if err == nil {
			span.end = seg.begin
			break
		}
		if !errors.Is(err, nntppool.ErrArticleNotFound) {
			return span, err
		}
		span.last = j
		if span.last-span.first+1 > p.maxRun {
			return span, errHoleLimit
		}
	}
	if span.end <= span.begin || span.end-span.begin > int64(span.last-span.first+1)*maxArticleBytes {
		return span, errInvalidArticle
	}
	return span, nil
}

func fillHole(p []byte, off int64, span holeSpan) (int, error) {
	n := min(int64(len(p)), span.end-off)
	clear(p[:n])
	return int(n), nil
}
