package engine

// Adapted from AltMount internal/usenet/flight.go (MIT, Javier Blanco).
// A shared progressive article serves independent readers. This variant adds a
// hard, reusable allocation budget and reference-owned cancellation. Condition
// variables avoid allocating a new notification channel for every decoded chunk.

import (
	"bytes"
	"container/list"
	"context"
	"errors"
	"fmt"
	"io"
	"sync"

	"github.com/javi11/nntppool/v4"
)

const maxArticleBytes = 16 << 20

var errPrefetchYield = errors.New("prefetch yielded to playback memory reserve")

type bodyClient interface {
	BodyStreamPriority(context.Context, string, io.Writer, ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error)
	BodyStream(context.Context, string, io.Writer, ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error)
}

type article struct {
	mu          sync.Mutex
	cond        *sync.Cond
	data        []byte
	ready       int
	written     int // current NNTP attempt; ready is the published prefix across retries.
	meta        nntppool.YEncMeta
	err         error
	done        bool
	ctx         context.Context
	cancel      context.CancelFunc
	store       *Store
	id          string
	refs        int // protected by Store.mu
	lru         *list.Element
	reserved    int64
	speculative bool
}

type Store struct {
	mu                     sync.Mutex
	cond                   *sync.Cond
	client                 bodyClient
	ctx                    context.Context
	cancel                 context.CancelFunc
	entries                map[string]*article
	lru                    list.List
	free                   map[int][][]byte
	allocated, used, limit int64
	active, peak           int
	closed                 bool
	wg                     sync.WaitGroup
	stats                  storeStats
	nzb                    *nzbSnapshot // Metadata descriptor, owned by this session.
}

type storeStats struct {
	Requests      int `json:"requests"`
	CacheHits     int `json:"cacheHits"`
	SharedJoins   int `json:"sharedJoins"`
	Downloads     int `json:"downloads"`
	Cancellations int `json:"cancellations"`
}

// A cancelled pool request can finish its protocol drain after BodyStream has
// returned. Seal both callbacks before recycling/retrying the article so a late
// yEnc header or decoded chunk cannot mutate the next attempt's shared slab.
type attemptWriter struct {
	mu      sync.Mutex
	active  bool
	article *article
	onMeta  func(nntppool.YEncMeta)
}

func (w *attemptWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.active {
		return len(p), nil
	}
	return w.article.Write(p)
}
func (w *attemptWriter) metadata(m nntppool.YEncMeta) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.active {
		w.onMeta(m)
	}
}
func (w *attemptWriter) seal() { w.mu.Lock(); w.active = false; w.mu.Unlock() }

func NewStore(ctx context.Context, client bodyClient, limit int64) *Store {
	ctx, cancel := context.WithCancel(ctx)
	s := &Store{client: client, ctx: ctx, cancel: cancel, limit: limit, entries: make(map[string]*article), free: make(map[int][][]byte)}
	s.cond = sync.NewCond(&s.mu)
	return s
}

func (s *Store) readAheadLimit() int64 { return min(int64(32<<20), s.limit*3/8) }

func (s *Store) acquire(id string, speculative bool) *article {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.stats.Requests++
	if a := s.entries[id]; a != nil {
		a.mu.Lock()
		failed := a.err != nil
		done := a.done
		a.mu.Unlock()
		if failed {
			delete(s.entries, id)
		} else {
			if done {
				s.stats.CacheHits++
			} else {
				s.stats.SharedJoins++
			}
			if a.lru != nil {
				s.lru.Remove(a.lru)
				a.lru = nil
			}
			if !speculative {
				a.speculative = false
			}
			a.refs++
			return a
		}
	}
	// Bound pending work as well as buffers. Speculation leaves room for
	// concurrent demand readers and may be skipped under memory pressure.
	if speculative && s.active >= int(s.limit/(1<<20))*3/4 {
		return nil
	}
	ctx, cancel := context.WithCancel(s.ctx)
	a := &article{ctx: ctx, cancel: cancel, store: s, id: id, refs: 1, speculative: speculative}
	a.cond = sync.NewCond(&a.mu)
	s.entries[id] = a
	s.stats.Downloads++
	s.active++
	s.peak = max(s.peak, s.active)
	s.wg.Add(1)
	go s.fetch(a, speculative)
	return a
}

func (s *Store) release(a *article) {
	s.releaseReader(a, true)
}

// Header discovery deliberately hands articles between short-lived readers
// (signature, main header, file header). Let those bodies finish into the LRU
// instead of repeatedly cancelling/refetching the same header. Playback leases
// always use release, even when they inherited an article from discovery.
func (s *Store) releaseHeader(a *article) {
	s.releaseReader(a, false)
}

func (s *Store) releaseReader(a *article, cancelDemand bool) {
	if a == nil {
		return
	}
	s.mu.Lock()
	a.refs--
	if a.refs == 0 {
		a.mu.Lock()
		done := a.done
		failed := a.err != nil
		a.mu.Unlock()
		if s.closed || failed || (!done && (cancelDemand || a.speculative)) {
			if !done {
				s.stats.Cancellations++
			}
			// Demand is also obsolete when its final reader leaves. Overlapping
			// readers still own references; completed articles remain cacheable.
			a.cancel()
			if s.entries[a.id] == a {
				delete(s.entries, a.id)
			}
			if done {
				s.recycleLocked(a)
			}
		} else if done {
			// refs counts current reader leases, not whether these bytes were
			// played. Header discovery and separate Range requests release leases
			// too. Keep a completed article addressable until allocation needs its
			// slab; immediate recycling retains the same RAM in free, loses a
			// possible cache hit, and cannot reclaim an unfinished producer safely.
			a.lru = s.lru.PushFront(a)
		}
	}
	s.cond.Broadcast()
	s.mu.Unlock()
}

func (s *Store) recycleLocked(a *article) {
	if a.lru != nil {
		s.lru.Remove(a.lru)
		a.lru = nil
	}
	if s.entries[a.id] == a {
		delete(s.entries, a.id)
	}
	if a.reserved != 0 {
		if s.closed {
			s.allocated -= a.reserved
		} else {
			s.free[len(a.data)] = append(s.free[len(a.data)], a.data)
		}
		s.used -= a.reserved
		a.data = nil
		a.reserved = 0
	}
}

func (s *Store) allocate(a *article, size int64) error {
	if size <= 0 || size > maxArticleBytes {
		return fmt.Errorf("unsupported article size (maximum 16 MiB)")
	}
	n := 64 << 10
	for int64(n) < size {
		n *= 2
	}
	s.mu.Lock()
	stop := context.AfterFunc(a.ctx, func() { s.mu.Lock(); s.cond.Broadcast(); s.mu.Unlock() })
	defer stop()
	defer s.mu.Unlock()
	if a.reserved != 0 {
		if int64(n) <= a.reserved {
			return nil
		}
		return errors.New("changed yEnc article size during retry")
	}
	for {
		if err := a.ctx.Err(); err != nil {
			return err
		}
		budget := s.limit
		if a.speculative {
			budget = budget * 3 / 4
		}
		if s.used+int64(n) <= budget {
			break
		}
		if tail := s.lru.Back(); tail != nil {
			// Even speculative read-ahead evicts idle history before yielding.
			s.recycleLocked(tail.Value.(*article))
			continue
		}
		if a.speculative {
			return errPrefetchYield
		}
		s.cond.Wait()
	}
	if bufs := s.free[n]; len(bufs) > 0 {
		a.data = bufs[len(bufs)-1]
		s.free[n] = bufs[:len(bufs)-1]
	} else {
		// Free differently sized idle slabs before allocating. allocated includes
		// both cached and pooled buffers; it never exceeds the configured budget.
		for k, bufs := range s.free {
			if s.allocated+int64(n) > s.limit {
				s.allocated -= int64(k * len(bufs))
				delete(s.free, k)
			}
		}
		a.data = make([]byte, n)
		s.allocated += int64(n)
	}
	a.reserved = int64(n)
	s.used += int64(n)
	return nil
}

func (s *Store) fetch(a *article, speculative bool) {
	defer s.wg.Done()
	metaFn := func(m nntppool.YEncMeta) {
		size := m.PartSize
		if size == 0 && m.Part == 0 {
			size = m.FileSize
		}
		err := s.allocate(a, size)
		a.mu.Lock()
		if a.meta.FileSize != 0 && (a.meta.FileSize != m.FileSize || a.meta.PartBegin != m.PartBegin || a.meta.PartSize != m.PartSize) {
			err = errors.New("article metadata changed during retry")
		}
		a.meta = m
		if a.err == nil {
			a.err = err
		}
		a.cond.Broadcast()
		a.mu.Unlock()
	}
	var b *nntppool.ArticleBody
	var err error
	// The pool cannot replay a partially written io.Writer. Our shared slab can:
	// compare the published prefix and append only new bytes. This also recovers
	// live neighbours when a seek cancels a pipelined body on their connection.
	for attempt := 0; attempt < 3; attempt++ {
		a.mu.Lock()
		a.written = 0
		a.mu.Unlock()
		writer := &attemptWriter{active: true, article: a, onMeta: metaFn}
		if speculative {
			b, err = s.client.BodyStream(a.ctx, a.id, writer, writer.metadata)
		} else {
			b, err = s.client.BodyStreamPriority(a.ctx, a.id, writer, writer.metadata)
		}
		writer.seal()
		a.mu.Lock()
		// The pool already handles provider fallback for missing articles.
		// Replaying its terminal miss only repeats the same lookup.
		permanent := a.err != nil || errors.Is(err, nntppool.ErrArticleNotFound)
		a.mu.Unlock()
		if err == nil || a.ctx.Err() != nil || permanent {
			break
		}
	}
	a.mu.Lock()
	if a.err == nil {
		a.err = err
	}
	if a.err == nil && (b == nil || b.Encoding != nntppool.EncodingYEnc) {
		a.err = errors.New("article is not yEnc encoded")
	}
	if a.err == nil && b.ExpectedCRC != 0 && !b.CRCValid {
		a.err = errors.New("article CRC mismatch")
	}
	if a.err == nil {
		expected := a.meta.PartSize
		if expected == 0 {
			expected = a.meta.FileSize
		}
		if int64(a.ready) != expected || int64(a.written) != expected {
			a.err = fmt.Errorf("short yEnc payload (%d/%d, attempt %d): %w", a.ready, expected, a.written, io.ErrUnexpectedEOF)
		}
	}
	a.done = true
	a.cond.Broadcast()
	a.mu.Unlock()
	s.mu.Lock()
	s.active--
	if a.refs == 0 {
		if a.err != nil || s.closed {
			s.recycleLocked(a)
		} else if a.lru == nil {
			a.lru = s.lru.PushFront(a)
		}
	}
	s.cond.Broadcast()
	s.mu.Unlock()
}

func (a *article) Write(p []byte) (int, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	// nntppool owns cancellation/draining at the protocol layer. Never return a
	// writer error after its decoder modified the wire buffer in place: dropping
	// decoded bytes preserves framing until the pool abandons this response.
	if a.done || a.err != nil {
		return len(p), nil
	}
	if err := a.ctx.Err(); err != nil {
		a.err = err
		return len(p), nil
	}
	expected := a.meta.PartSize
	if expected == 0 {
		expected = a.meta.FileSize
	}
	if int64(len(p)) > expected-int64(a.written) {
		a.err = errors.New("yEnc payload exceeds announced part size")
		a.cond.Broadcast()
		return len(p), nil
	}
	if overlap := min(len(p), a.ready-a.written); overlap > 0 && !bytes.Equal(p[:overlap], a.data[a.written:a.written+overlap]) {
		a.err = errors.New("article payload changed during retry")
		a.cond.Broadcast()
		return len(p), nil
	}
	n := copy(a.data[a.written:], p)
	a.written += n
	a.ready = max(a.ready, a.written)
	a.cond.Broadcast()
	return n, nil
}

func (a *article) metadata(ctx context.Context) (nntppool.YEncMeta, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.meta.FileSize == 0 && a.err == nil && !a.done {
		stop := context.AfterFunc(ctx, func() { a.mu.Lock(); a.cond.Broadcast(); a.mu.Unlock() })
		defer stop()
	}
	for a.meta.FileSize == 0 && a.err == nil && !a.done && ctx.Err() == nil {
		a.cond.Wait()
	}
	if ctx.Err() != nil {
		return a.meta, ctx.Err()
	}
	if a.err != nil {
		return a.meta, a.err
	}
	if a.meta.FileSize <= 0 {
		return a.meta, errors.New("missing yEnc layout")
	}
	return a.meta, nil
}

func (a *article) readAt(ctx context.Context, p []byte, off int64) (int, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	expected := a.meta.PartSize
	if expected == 0 {
		expected = a.meta.FileSize
	}
	// Do not report a complete segment until its trailer/CRC has arrived. Early
	// playback still consumes progressive chunks without waiting for the body.
	wait := func() bool {
		return !a.done && a.err == nil && (int64(a.ready) <= off || off+int64(len(p)) >= expected)
	}
	if wait() {
		stop := context.AfterFunc(ctx, func() { a.mu.Lock(); a.cond.Broadcast(); a.mu.Unlock() })
		defer stop()
	}
	for wait() && ctx.Err() == nil {
		a.cond.Wait()
	}
	if ctx.Err() != nil {
		return 0, ctx.Err()
	}
	if a.err != nil {
		return 0, a.err
	}
	if off < 0 {
		return 0, errors.New("negative article offset")
	}
	if off >= int64(a.ready) {
		return 0, io.EOF
	}
	return copy(p, a.data[off:a.ready]), nil
}

func (s *Store) Close() {
	s.mu.Lock()
	s.closed = true
	s.cancel()
	s.cond.Broadcast()
	s.mu.Unlock()
	s.wg.Wait()
	s.mu.Lock()
	for e := s.lru.Back(); e != nil; e = s.lru.Back() {
		s.recycleLocked(e.Value.(*article))
	}
	for size, slabs := range s.free {
		s.allocated -= int64(size * len(slabs))
	}
	s.free = nil
	nzb := s.nzb
	s.nzb = nil
	// Existing readers own their slabs until release. Closing a session can race
	// an HTTP response; never invalidate memory that response is still reading.
	s.mu.Unlock()
	if nzb != nil {
		nzb.Close()
	}
}
