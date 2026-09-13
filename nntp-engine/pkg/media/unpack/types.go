package unpack

import (
	"context"
	"io"
)

type UnpackableFile interface {
	Name() string
	Size() int64
	EnsureSegmentMap() error
	OpenStream() (io.ReadSeekCloser, error)
	OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error)
	OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error)
	ReadAt(p []byte, off int64) (int, error)
}
