package sevenzip

import (
	"context"
	"io"

	"github.com/javi11/sevenzip/internal/util"
)

// Device-side directory parsing only: these bounds apply before allocation.
const maxHeaderBytes = 8 << 20
const maxMetadataEntries = 10000
const maxFolderStreams = 64
const maxCoderProperties = 1024

var ErrResourceLimit = util.ErrResourceLimit

type contextReaderAt struct {
	ctx context.Context
	r   io.ReaderAt
}

func (r contextReaderAt) ReadAt(p []byte, off int64) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.r.ReadAt(p, off)
}
