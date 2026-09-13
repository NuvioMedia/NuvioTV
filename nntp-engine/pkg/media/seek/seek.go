package seek

import (
	"bytes"
	"fmt"
	"io"
	"strings"
)

const (
	MaxBytesToRead = 2 * 1024 * 1024
)

type StreamStartInfo struct {
	HeaderValid   bool
	DurationSec   float64
	DurationKnown bool
}

func InspectStreamStart(stream io.ReadSeeker, size int64, filename string, maxBytes int) (StreamStartInfo, error) {
	var info StreamStartInfo
	if size <= 0 {
		return info, fmt.Errorf("invalid size %d", size)
	}
	if maxBytes <= 0 {
		maxBytes = MaxBytesToRead
	}

	readSize := maxBytes
	if size < int64(readSize) {
		readSize = int(size)
	}
	buf := make([]byte, readSize)
	n, err := io.ReadFull(stream, buf)
	if err != nil && err != io.ErrUnexpectedEOF {
		return info, err
	}
	buf = buf[:n]

	if _, err := stream.Seek(0, io.SeekStart); err != nil {
		return info, err
	}

	info.HeaderValid = ValidateContainerHeader(buf, filename)
	switch formatFromFilename(filename) {
	case "mp4":
		info.DurationSec, info.DurationKnown = durationFromMP4(buf)
	case "mkv":
		info.DurationSec, info.DurationKnown = durationFromMKV(buf)
	}

	return info, nil
}

func formatFromFilename(filename string) string {
	lower := strings.ToLower(filename)
	switch {
	case strings.HasSuffix(lower, ".mp4"), strings.HasSuffix(lower, ".m4v"), strings.HasSuffix(lower, ".mov"):
		return "mp4"
	case strings.HasSuffix(lower, ".mkv"), strings.HasSuffix(lower, ".webm"):
		return "mkv"
	default:
		return ""
	}
}

// EBML header ID (MKV/WebM) - first element in file.
var ebmlHeaderID = []byte{0x1A, 0x45, 0xDF, 0xA3}

// ftyp box type (MP4/MOV).
var ftypBox = []byte{'f', 't', 'y', 'p'}

// ValidateContainerHeader checks that data starts with valid container headers for the given filename.
// Used by the play probe to ensure RAR/7z/direct streams have readable, valid headers before serving.
func ValidateContainerHeader(data []byte, filename string) bool {
	if len(data) < 8 {
		return false
	}
	switch formatFromFilename(filename) {
	case "mkv":
		return len(data) >= 4 && bytes.HasPrefix(data, ebmlHeaderID)
	case "mp4":
		// MP4: optional 4-byte size (big-endian) then "ftyp", or "ftyp" at 0
		if bytes.HasPrefix(data, ftypBox) {
			return true
		}
		if len(data) >= 8 && bytes.Equal(data[4:8], ftypBox) {
			return true
		}
		return false
	default:
		// AVI, etc.: no strict check; caller may use probe size only to trigger segment read
		return true
	}
}
