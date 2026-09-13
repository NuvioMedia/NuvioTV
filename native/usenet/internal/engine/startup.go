package engine

import (
	"context"
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Startup owns a few article leases independently of HTTP Range readers. They
// survive the extractor's head -> Cues -> head round trip, but never a session
// close or this deadline. Ordinary playback cancellation remains unchanged.
const startupLifetime = 8 * time.Second

type startupWarmup struct {
	cancel     context.CancelFunc
	headCancel context.CancelFunc
	cuesOffset atomic.Int64
	wg         sync.WaitGroup
}

// A distant media read hands buffering to the active reader's existing window.
// Keep header leases for index probes (including unadvertised EOF indexes).
func (w *startupWarmup) observeRead(off, size int64) {
	if w == nil || off <= 32<<20 || off >= size-(4<<20) {
		return
	}
	if cues := w.cuesOffset.Load(); cues >= 0 && off >= cues-(4<<20) && off < cues+(4<<20) {
		return
	}
	w.headCancel()
}

func (w *startupWarmup) Close() {
	if w != nil {
		w.cancel()
		w.wg.Wait()
	}
}

func (s *Session) startMKVWarmup(enabled bool, connections int) {
	if !enabled || !strings.EqualFold(pathExtension(s.content.Name), ".mkv") {
		return
	}
	// At most two speculative producers. A one/two-connection account cannot
	// spare them without competing directly with startup demand.
	if connections < 3 {
		s.trace.mark("warmup_skipped_connection_limit")
		return
	}
	ctx, cancel := context.WithTimeout(s.ctx, startupLifetime)
	headCtx, headCancel := context.WithCancel(ctx)
	w := &startupWarmup{cancel: cancel, headCancel: headCancel}
	w.cuesOffset.Store(-1)
	s.warmup = w
	s.trace.mark("warmup_started")
	cues := make(chan int64, 1)
	// Each side retains <= 1/8 of the article budget, capped at 4 MiB, and
	// <= 4 articles including layout corrections. Slab sizes, not payload bytes,
	// count towards this limit; the Store still enforces its global hard limit.
	for _, tail := range []bool{false, true} {
		w.wg.Add(1)
		go func(tail bool) {
			defer w.wg.Done()
			workerCtx := ctx
			if !tail {
				workerCtx = headCtx
				defer headCancel()
			}
			ctx := workerCtx
			p := startupPins{store: s.store, budget: min(s.store.limit/8, 4<<20)}
			defer p.close()
			off := int64(0)
			exactCues := false
			if tail {
				off = s.content.Size - 1
				timer := time.NewTimer(80 * time.Millisecond)
				select {
				case target, ok := <-cues:
					if ok {
						off, exactCues = target, true
					}
				case <-timer.C:
				case <-ctx.Done():
					timer.Stop()
					return
				}
				timer.Stop()
			}
			steps := 2
			for step := 0; step < steps && off >= 0 && off < s.content.Size; step++ {
				a, e, seg, err := p.at(ctx, s.content, off)
				if err != nil {
					break
				}
				if !tail && step == 0 {
					// Parse only a bounded prefix. Malformed/unusual EBML simply
					// keeps the tail heuristic; no full-file indexing is attempted.
					prefix := make([]byte, min(int64(256<<10), e.start+e.length-off, seg.end-e.offset))
					n := 0
					for n < len(prefix) {
						got, er := a.readAt(ctx, prefix[n:], e.offset-seg.begin+int64(n))
						n += got
						if pos, ok := mkvCuesOffset(prefix[:n], s.content.Size); ok {
							w.cuesOffset.Store(pos)
							cues <- pos
							s.trace.mark("cues_located")
							break
						}
						if er != nil || got == 0 {
							break
						}
					}
				}
				// Wait through the trailer/CRC before declaring a cache-ready
				// article. Playback can meanwhile consume its progressive prefix.
				var last [1]byte
				if _, err = a.readAt(ctx, last[:], seg.end-seg.begin-1); err != nil {
					break
				}
				if tail {
					s.trace.mark("tail_article_ready")
					if exactCues {
						s.trace.mark("cues_article_ready")
						off = min(e.start+e.length, e.start+seg.end-e.offset)
					} else {
						off = e.start + seg.begin - e.offset - 1
						select {
						case target, ok := <-cues:
							start, end := e.start+seg.begin-e.offset, e.start+seg.end-e.offset
							if ok {
								exactCues = true
								if target < start || target >= end {
									off = target
									// A pointer received after the second heuristic article
									// must still be fetched, within the same pin budget.
									steps = max(steps, step+2)
								} else {
									s.trace.mark("cues_article_ready")
									off = min(e.start+e.length, end)
								}
							}
						default:
						}
					}
				} else {
					s.trace.mark("head_article_ready")
					off = min(e.start+e.length, e.start+seg.end-e.offset)
				}
			}
			if !tail {
				close(cues)
			} else if !exactCues {
				// A tiny final article may finish before the head's SeekHead is
				// available. Still try the exact pointer within the same byte cap.
				select {
				case target, ok := <-cues:
					if ok {
						if a, _, seg, err := p.at(ctx, s.content, target); err == nil {
							var last [1]byte
							if _, err = a.readAt(ctx, last[:], seg.end-seg.begin-1); err == nil {
								s.trace.mark("cues_article_ready")
							}
						}
					}
				case <-ctx.Done():
				}
			}
			// Completed bytes stay pinned only during the startup grace period.
			// A failed prefetch never fails opening or reading the stream.
			<-ctx.Done()
		}(tail)
	}
}

func pathExtension(name string) string {
	if i := strings.LastIndexByte(name, '.'); i >= 0 {
		return name[i:]
	}
	return ""
}

type startupPins struct {
	store    *Store
	budget   int64
	used     int64
	articles []*article
}

func (p *startupPins) close() {
	for _, a := range p.articles {
		p.store.release(a)
	}
}

func slabSize(size int64) int64 {
	n := int64(64 << 10)
	for n < size && n < maxArticleBytes {
		n *= 2
	}
	return n
}

func (p *startupPins) at(ctx context.Context, c *Content, off int64) (*article, extent, segment, error) {
	e := extent{file: c.direct, length: c.Size}
	if c.direct == nil {
		var err error
		e, err = c.part(ctx, off)
		if err != nil {
			return nil, e, segment{}, err
		}
	}
	f := e.file
	pos := e.offset + off - e.start
	lo, hi := 0, len(f.segments)-1
	for lo <= hi {
		if ctx.Err() != nil {
			return nil, e, segment{}, ctx.Err()
		}
		i := f.locate(pos, lo, hi)
		if i < 0 {
			break
		}
		f.mu.RLock()
		seg := f.segments[i]
		f.mu.RUnlock()
		estimate := seg.wire
		if seg.known {
			estimate = seg.end - seg.begin
		}
		if len(p.articles) >= 4 || estimate > maxArticleBytes || slabSize(estimate) > p.budget-p.used {
			return nil, e, seg, errPrefetchYield
		}
		a := p.store.acquire(seg.id, true)
		if a == nil {
			return nil, e, seg, errPrefetchYield
		}
		m, err := a.metadata(ctx)
		if err != nil {
			p.store.release(a)
			return nil, e, seg, err
		}
		p.store.mu.Lock()
		reserved := a.reserved
		p.store.mu.Unlock()
		if reserved > p.budget-p.used {
			p.store.release(a)
			return nil, e, seg, errPrefetchYield
		}
		p.used += reserved
		p.articles = append(p.articles, a)
		if err = f.learn(i, m); err != nil {
			return nil, e, seg, err
		}
		f.mu.RLock()
		seg = f.segments[i]
		f.mu.RUnlock()
		if pos < seg.begin {
			hi = i - 1
			continue
		}
		if pos >= seg.end {
			lo = i + 1
			continue
		}
		return a, e, seg, nil
	}
	return nil, e, segment{}, io.EOF
}

// Minimal bounded EBML traversal: Segment -> SeekHead -> Seek(Cues).
// SeekPosition is relative to the Segment payload, not the file or SeekHead.
func mkvCuesOffset(prefix []byte, size int64) (int64, bool) {
	pos := 0
	for pos < len(prefix) {
		id, body, end, err := ebmlElement(prefix, pos, true)
		if err != nil {
			return 0, false
		}
		if id == 0x18538067 {
			base := body
			for pos = body; pos < len(prefix); {
				id, body, end, err = ebmlElement(prefix, pos, false)
				if err != nil || end > len(prefix) {
					return 0, false
				}
				if id == 0x1F43B675 {
					return 0, false
				}
				if id == 0x114D9B74 {
					for j := body; j < end; {
						sid, sb, se, er := ebmlElement(prefix[:end], j, false)
						if er != nil || se > end {
							return 0, false
						}
						if sid == 0x4DBB {
							var target, offset uint64
							hasOffset := false
							for k := sb; k < se; {
								cid, cb, ce, er := ebmlElement(prefix[:se], k, false)
								if er != nil || ce > se || ce-cb > 8 {
									return 0, false
								}
								var value uint64
								for _, b := range prefix[cb:ce] {
									value = value<<8 | uint64(b)
								}
								if cid == 0x53AB {
									target = value
								}
								if cid == 0x53AC {
									offset = value
									hasOffset = true
								}
								k = ce
							}
							if target == 0x1C53BB6B && hasOffset && size > int64(base) && offset < uint64(size-int64(base)) {
								return int64(base) + int64(offset), true
							}
						}
						j = se
					}
				}
				pos = end
			}
			return 0, false
		}
		if end > len(prefix) {
			return 0, false
		}
		pos = end
	}
	return 0, false
}

var errEBML = errors.New("incomplete or invalid EBML prefix")

func ebmlElement(b []byte, pos int, allowSegment bool) (uint64, int, int, error) {
	read := func(id bool) (uint64, int, bool) {
		if pos >= len(b) || b[pos] == 0 {
			return 0, 0, false
		}
		width, mask := 1, byte(0x80)
		for b[pos]&mask == 0 {
			width++
			mask >>= 1
		}
		if width > 8 || (id && width > 4) || width > len(b)-pos {
			return 0, 0, false
		}
		v := uint64(b[pos])
		if !id {
			v &= uint64(mask - 1)
		}
		for _, c := range b[pos+1 : pos+width] {
			v = v<<8 | uint64(c)
		}
		pos += width
		return v, width, true
	}
	id, _, ok := read(true)
	if !ok {
		return 0, 0, 0, errEBML
	}
	n, width, ok := read(false)
	if !ok {
		return 0, 0, 0, errEBML
	}
	if allowSegment && id == 0x18538067 {
		return id, pos, len(b), nil
	}
	if n == uint64(1)<<(7*width)-1 || n > uint64(len(b)-pos) {
		return 0, 0, 0, errEBML
	}
	return id, pos, pos + int(n), nil
}
