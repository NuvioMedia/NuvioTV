package loader

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"sync/atomic"

	"streamnzb/pkg/core/logger"
)

// DefaultReadAhead is how many segments ahead of the current read position
// the reader will pre-download using parallel connections. This warms the
// pool-level segment cache so subsequent Read calls don't block on I/O.
const DefaultReadAhead = 8

type SegmentReader struct {
	file    *File
	ctx     context.Context
	cancel  context.CancelFunc
	traceID uint64

	mu               sync.Mutex
	segIdx           int
	segOff           int64
	offset           int64
	closed           bool
	readAheadSize    int
	readAheadCtx     context.Context
	readAheadCancel  context.CancelFunc
	lastReadAheadSeg int // last segment index we triggered read-ahead from (-1 = none)
	// raWinFrom/raWinTo is the window this reader most recently asked
	// read-ahead to cover. When a READ establishes a disjoint new window the
	// old one is provably abandoned and its fetches are cancelled; seeks alone
	// never cancel, because ServeContent seeks to EOF and back on every
	// request before reading a byte.
	raWinFrom int
	raWinTo   int

	currentSegIdx int    // cached segment index (-1 if invalid)
	currentData   []byte // cached data slice for currentSegIdx
}

var liveSegmentReaders atomic.Int64
var nextSegmentReaderID atomic.Uint64
var liveSegmentReaderRegistry sync.Map

func LiveSegmentReaders() int64 {
	return liveSegmentReaders.Load()
}

func LiveSegmentReaderDetails() []string {
	details := make([]string, 0)
	liveSegmentReaderRegistry.Range(func(_, value any) bool {
		r, ok := value.(*SegmentReader)
		if !ok || r == nil {
			return true
		}
		details = append(details, r.traceDetail())
		return true
	})
	sort.Strings(details)
	return details
}

func NewSegmentReaderWithReadAhead(parent context.Context, f *File, startOffset int64, readAhead int) *SegmentReader {
	sr := newSegmentReader(parent, f, startOffset)
	if readAhead > 0 {
		sr.readAheadSize = readAhead
	}
	return sr
}

func NewSegmentReader(parent context.Context, f *File, startOffset int64) *SegmentReader {
	return newSegmentReader(parent, f, startOffset)
}

func newSegmentReader(parent context.Context, f *File, startOffset int64) *SegmentReader {
	if parent == nil {
		parent = context.Background()
	}

	ctx, cancel := context.WithCancel(parent)
	raCtx, raCancel := context.WithCancel(ctx)
	sr := &SegmentReader{
		file:             f,
		ctx:              ctx,
		cancel:           cancel,
		traceID:          nextSegmentReaderID.Add(1),
		offset:           startOffset,
		readAheadSize:    DefaultReadAhead,
		readAheadCtx:     raCtx,
		readAheadCancel:  raCancel,
		lastReadAheadSeg: -1,
		currentSegIdx:    -1,
	}

	idx := f.FindSegmentIndex(startOffset)
	if idx == -1 {
		if startOffset >= f.Size() {
			sr.segIdx = len(f.segments)
		} else {
			sr.segIdx = 0
		}
	} else {
		sr.segIdx = idx
		sr.segOff = startOffset - f.segments[idx].StartOffset
	}

	liveSegmentReaders.Add(1)
	liveSegmentReaderRegistry.Store(sr.traceID, sr)

	return sr
}

func (r *SegmentReader) traceDetail() string {
	r.mu.Lock()
	id := r.traceID
	segIdx := r.segIdx
	segOff := r.segOff
	offset := r.offset
	closed := r.closed
	r.mu.Unlock()

	sessionID := "unknown"
	fileName := ""
	if r.file != nil {
		fileName = r.file.Name()
		if ownerID := r.file.OwnerSessionID(); ownerID != "" {
			sessionID = ownerID
		}
	}

	return fmt.Sprintf("id=%d session=%s file=%q offset=%d seg=%d seg_off=%d closed=%t", id, sessionID, fileName, offset, segIdx, segOff, closed)
}

func (r *SegmentReader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}

	total := 0
	for total < len(p) {
		n, err := r.readInto(p[total:])
		total += n
		if err != nil {
			if err == io.EOF && total > 0 {
				return total, nil
			}
			if total > 0 && err != io.EOF {
				return total, err
			}
			return total, err
		}
	}
	return total, nil
}

func (r *SegmentReader) readInto(p []byte) (int, error) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return 0, io.ErrClosedPipe
	}
	if r.segIdx >= len(r.file.segments) || r.offset >= r.file.Size() {
		r.logEOFBeforeVirtualSize()
		r.mu.Unlock()
		return 0, io.EOF
	}
	segIdx := r.segIdx
	segOff := r.segOff
	r.mu.Unlock()

	decodedLen := r.file.segmentDecodedLen(segIdx)
	if segOff >= decodedLen {
		r.mu.Lock()
		r.segIdx++
		r.segOff = 0
		r.currentSegIdx = -1
		r.currentData = nil
		r.mu.Unlock()
		return r.readInto(p)
	}

	var data []byte
	var err error

	r.mu.Lock()
	if r.currentSegIdx == segIdx && r.currentData != nil {
		data = r.currentData
	}
	r.mu.Unlock()

	if data == nil {
		logger.Trace("SegmentReader readInto: calling DownloadSegment", "file", r.file.Name(), "segIdx", segIdx, "segOff", segOff)
		data, err = r.file.DownloadSegment(r.ctx, segIdx)
		logger.Trace("SegmentReader readInto: DownloadSegment returned", "file", r.file.Name(), "segIdx", segIdx, "err", err, "dataLen", len(data))
		if err != nil {
			return 0, err
		}
		if err := r.file.verifyMappedSegmentLength(segIdx, data); err != nil {
			return 0, err
		}
		r.mu.Lock()
		if !r.closed && r.segIdx == segIdx {
			r.currentSegIdx = segIdx
			r.currentData = data
		}
		r.mu.Unlock()
	}

	remain := decodedLen - segOff
	avail := int64(len(data)) - segOff
	if avail < 0 {
		avail = 0
	}
	toCopy := int64(len(p))
	if remain < toCopy {
		toCopy = remain
	}
	if avail < toCopy {
		toCopy = avail
	}
	if toCopy <= 0 {
		if remain > 0 && avail == 0 {
			return 0, fmt.Errorf("segment %d data shorter than mapped size (have %d decoded %d at off %d)",
				segIdx, len(data), decodedLen, segOff)
		}
		r.mu.Lock()
		r.segIdx++
		r.segOff = 0
		r.currentSegIdx = -1
		r.currentData = nil
		r.mu.Unlock()
		return r.readInto(p)
	}

	n := copy(p, data[segOff:segOff+toCopy])

	r.mu.Lock()
	r.segOff += int64(n)
	r.offset += int64(n)
	if r.segOff >= decodedLen {
		r.segIdx++
		r.segOff = 0
		r.currentSegIdx = -1
		r.currentData = nil
	}
	currentSeg := r.segIdx
	r.mu.Unlock()

	r.triggerReadAhead(currentSeg)

	return n, nil
}

func (r *SegmentReader) logEOFBeforeVirtualSize() {
	if r.file == nil || r.offset >= r.file.Size() {
		return
	}
	logger.Debug("SegmentReader EOF before virtual size",
		"file", r.file.Name(),
		"offset", r.offset,
		"virtual_size", r.file.Size(),
		"seg_idx", r.segIdx,
		"seg_off", r.segOff)
}

// triggerReadAhead fires off background downloads for the next readAheadSize
// segments starting from fromSeg. It is idempotent: calling it repeatedly with
// the same fromSeg is a no-op. A new window disjoint from this reader's
// previous one means the previous one was abandoned by a seek, and its
// still-running fetches are cancelled so they stop occupying connections the
// new window needs. Overlapping windows — sequential progress, small seeks —
// cancel nothing.
func (r *SegmentReader) triggerReadAhead(fromSeg int) {
	if r.readAheadSize <= 0 {
		return
	}

	r.mu.Lock()
	if r.closed || fromSeg == r.lastReadAheadSeg {
		r.mu.Unlock()
		return
	}
	r.lastReadAheadSeg = fromSeg
	raCtx := r.readAheadCtx
	end := fromSeg + r.readAheadSize
	if totalSegs := len(r.file.segments); end > totalSegs {
		end = totalSegs
	}
	oldFrom, oldTo := r.raWinFrom, r.raWinTo
	r.raWinFrom, r.raWinTo = fromSeg, end
	r.mu.Unlock()

	if oldTo > oldFrom && (fromSeg >= oldTo || end <= oldFrom) {
		r.file.cancelAbandonedReadAheadIn(oldFrom, oldTo)
	}

	r.file.ReadAheadRange(raCtx, fromSeg, end)
}

func (r *SegmentReader) Seek(offset int64, whence int) (int64, error) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return 0, io.ErrClosedPipe
	}

	var target int64
	switch whence {
	case io.SeekStart:
		target = offset
	case io.SeekCurrent:
		target = r.offset + offset
	case io.SeekEnd:
		target = r.file.Size() + offset
	default:
		r.mu.Unlock()
		return 0, errors.New("invalid whence")
	}

	if target < 0 {
		r.mu.Unlock()
		return 0, errors.New("seek out of bounds")
	}
	if fileSize := r.file.Size(); target > fileSize {
		// Be tolerant of callers that seek slightly past EOF (some archive
		// parsers probe layout this way). Clamp to EOF instead of failing.
		target = fileSize
	}

	if target == r.offset {
		r.mu.Unlock()
		return target, nil
	}

	// A seek that stays inside the current segment keeps everything: the cached
	// segment bytes and the in-flight read-ahead are exactly as useful at the
	// new position. Demuxers wiggle constantly — and the encrypted-RAR path
	// used to step back one AES block per read — so treating every small seek
	// as a window reset held those streams to a serial fetch.
	if target < r.file.Size() {
		if idx := r.file.FindSegmentIndex(target); idx != -1 && idx == r.segIdx {
			r.offset = target
			r.segOff = target - r.file.segments[idx].StartOffset
			r.mu.Unlock()
			return target, nil
		}
	}

	r.offset = target
	r.currentSegIdx = -1
	r.currentData = nil
	if target >= r.file.Size() {
		r.segIdx = len(r.file.segments)
		r.segOff = 0
	} else {
		idx := r.file.FindSegmentIndex(target)
		if idx == -1 {
			r.segIdx = len(r.file.segments)
			r.segOff = 0
		} else {
			r.segIdx = idx
			r.segOff = target - r.file.segments[idx].StartOffset
		}
	}

	// Cancel pending read-ahead goroutines and create a fresh context so
	// new read-ahead starts from the seek target.
	r.readAheadCancel()
	raCtx, raCancel := context.WithCancel(r.ctx)
	r.readAheadCtx = raCtx
	r.readAheadCancel = raCancel
	r.lastReadAheadSeg = -1

	r.mu.Unlock()

	return target, nil
}

func (r *SegmentReader) Close() error {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil
	}
	r.closed = true
	r.currentSegIdx = -1
	r.currentData = nil
	winFrom, winTo := r.raWinFrom, r.raWinTo
	r.raWinFrom, r.raWinTo = 0, 0
	r.readAheadCancel()
	r.mu.Unlock()

	// Read-ahead runs on the file context, so cancelling this reader's own
	// context leaves its window downloading. Hand the window over rather than
	// killing it: serving is per-request, and the next request is as likely to
	// be the rest of the same read as a seek away from it.
	r.file.abandonReadAheadWindow(winFrom, winTo)

	logger.Debug("loader SegmentReader Close", "file", r.file.Name())
	r.cancel()
	liveSegmentReaderRegistry.Delete(r.traceID)
	liveSegmentReaders.Add(-1)
	return nil
}

func isContextErr(err error) bool {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	s := err.Error()
	return strings.Contains(s, "canceled") || strings.Contains(s, "cancelled")
}
