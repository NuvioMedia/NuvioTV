package unpack

import (
	"context"
	"encoding/hex"

	"streamnzb/pkg/core/logger"
)

func logRARScanDebug(f UnpackableFile, err error) {
	attrs := []any{
		"err", err,
		"virtual_size", f.Size(),
		"stream_read_pos", ScanTraceLastReadPos(),
	}
	if preview, ok := readVolumeHeaderPreview(f); ok {
		attrs = append(attrs, "header_hex", preview)
	}
	logger.Debug("RAR scan debug", attrs...)
}

func readVolumeHeaderPreview(f UnpackableFile) (string, bool) {
	const previewLen = 32
	// First-segment read only: this runs on volumes whose scan just failed, so
	// their segment maps often do not exist yet, and both ReadAt and a plain
	// OpenReaderAt would build one first — without the skip-gap flag, that can
	// mean downloading the whole volume to log 32 hex bytes.
	header, err := readFileHeader(context.Background(), f)
	if err != nil || len(header) == 0 {
		return "", false
	}
	if len(header) > previewLen {
		header = header[:previewLen]
	}
	return hex.EncodeToString(header), true
}
