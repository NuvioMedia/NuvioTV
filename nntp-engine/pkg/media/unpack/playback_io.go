package unpack

import (
	"context"
	"io"
)

type playbackReaderAt interface {
	OpenPlaybackReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error)
}

type playbackPrefetcher interface {
	PrefetchPlaybackOffset(ctx context.Context, offset int64)
}

// playbackRangePrefetcher is offered by files that can warm a bounded byte
// range, rather than a whole read-ahead window, from an offset.
type playbackRangePrefetcher interface {
	PrefetchPlaybackRange(ctx context.Context, offset, length int64)
}

func prefetchPlaybackRange(ctx context.Context, f UnpackableFile, offset, length int64) {
	if offset < 0 {
		length += offset
		offset = 0
	}
	if length <= 0 {
		return
	}
	if p, ok := f.(playbackRangePrefetcher); ok {
		p.PrefetchPlaybackRange(ctx, offset, length)
	}
}

type playbackStreamOpener interface {
	OpenPlaybackStreamCtx(ctx context.Context) (io.ReadSeekCloser, error)
}

// readerAtCtx is offered by files whose ReadAt can honor the caller's context
// (loader files, where the context carries the skip-gap map-detection flag).
type readerAtCtx interface {
	ReadAtCtx(ctx context.Context, p []byte, off int64) (int, error)
}

// readAtCtx reads via ReadAtCtx when f offers it, else plain ReadAt.
func readAtCtx(ctx context.Context, f UnpackableFile, p []byte, off int64) (int, error) {
	if r, ok := f.(readerAtCtx); ok {
		return r.ReadAtCtx(ctx, p, off)
	}
	return f.ReadAt(p, off)
}

// playbackStreamSizer is offered by files that size their read-ahead window
// against the whole stream being played rather than their own length. A volume
// of a split archive is a fixed slice of the movie, so the window has to be
// measured against the movie.
type playbackStreamSizer interface {
	SetPlaybackStreamBytes(int64)
}

func openPlaybackReaderAt(f UnpackableFile, ctx context.Context, offset int64) (io.ReadCloser, error) {
	if o, ok := f.(playbackReaderAt); ok {
		return o.OpenPlaybackReaderAt(ctx, offset)
	}
	return f.OpenReaderAt(ctx, offset)
}

// openPlaybackStream is openPlaybackReaderAt for a whole-file stream: it asks
// for the playback read-ahead window when the file offers one, and otherwise
// falls back to the scan-sized default.
func openPlaybackStream(f UnpackableFile, ctx context.Context) (io.ReadSeekCloser, error) {
	if o, ok := f.(playbackStreamOpener); ok {
		return o.OpenPlaybackStreamCtx(ctx)
	}
	return f.OpenStreamCtx(ctx)
}
