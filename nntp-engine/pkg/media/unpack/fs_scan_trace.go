package unpack

import (
	"errors"
	"io"
	"io/fs"
	"sync/atomic"

	"streamnzb/pkg/core/logger"
)

var scanTraceReadPos atomic.Int64

func ScanTraceLastReadPos() int64 {
	return scanTraceReadPos.Load()
}

type segmentMapDebugInfo interface {
	FindSegmentIndex(offset int64) int
	SegmentOffsetRange(index int) (start, end int64, ok bool)
}

type scanTraceFileWrapper struct {
	inner *fileWrapper
	pos   int64
}

func wrapFileForScanTrace(fw *fileWrapper) fs.File {
	return &scanTraceFileWrapper{inner: fw}
}

func (w *scanTraceFileWrapper) Stat() (fs.FileInfo, error) { return w.inner.Stat() }
func (w *scanTraceFileWrapper) Close() error               { return w.inner.Close() }
func (w *scanTraceFileWrapper) Seek(off int64, whence int) (int64, error) {
	newPos, err := w.inner.Seek(off, whence)
	if err == nil {
		w.pos = newPos
	}
	return newPos, err
}

func (w *scanTraceFileWrapper) Read(p []byte) (int, error) {
	n, err := w.inner.Read(p)
	if n > 0 {
		w.pos += int64(n)
		scanTraceReadPos.Store(w.pos)
	}
	if shouldLogScanIOErr(err, w.pos-int64(n), w.inner.size, n, len(p)) {
		w.logIOFail("read", w.pos-int64(n), len(p), n, err)
	}
	return n, err
}

func (w *scanTraceFileWrapper) ReadAt(p []byte, off int64) (int, error) {
	n, err := w.inner.ReadAt(p, off)
	if shouldLogScanIOErr(err, off, w.inner.size, n, len(p)) {
		w.logIOFail("read_at", off, len(p), n, err)
	}
	return n, err
}

func shouldLogScanIOErr(err error, off, virtualSize int64, n, want int) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, io.EOF) && n == 0 && off >= virtualSize {
		return false
	}
	if errors.Is(err, io.EOF) && n == 0 && off < virtualSize {
		return true
	}
	return errors.Is(err, io.ErrUnexpectedEOF) ||
		(errors.Is(err, io.EOF) && n > 0 && n < want) ||
		(err != nil && n < want)
}

func (w *scanTraceFileWrapper) logIOFail(op string, off int64, want, got int, err error) {
	attrs := []any{
		"file", w.inner.name,
		"op", op,
		"offset", off,
		"want", want,
		"got", got,
		"virtual_size", w.inner.size,
		"err", err,
	}
	if dbg, ok := w.inner.file.(segmentMapDebugInfo); ok {
		idx := dbg.FindSegmentIndex(off)
		attrs = append(attrs, "segment_index", idx)
		if idx >= 0 {
			if start, end, ok := dbg.SegmentOffsetRange(idx); ok {
				attrs = append(attrs, "segment_start", start, "segment_end", end)
			}
		}
	}
	logger.Debug("RAR scan IO short read", attrs...)
}
