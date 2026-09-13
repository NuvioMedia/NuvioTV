package unpack

import (
	"context"
	"fmt"
	"io"
	"io/fs"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type NZBFS struct {
	ctx   context.Context
	files map[string]UnpackableFile
}

func NewNZBFSFromMapCtx(ctx context.Context, files map[string]UnpackableFile) *NZBFS {
	if ctx == nil {
		ctx = context.Background()
	}
	return &NZBFS{ctx: ctx, files: files}
}

func (n *NZBFS) Open(name string) (fs.File, error) {
	f, ok := n.files[name]
	if !ok {
		f, ok = n.files[filepath.Base(name)]
	}
	if !ok {
		// Try volume-number matching for multi-volume archives to handle padded digits mismatches (e.g. part10 vs part010)
		reqPrefix := archivePrefix(name)
		if volNum := GetRARVolumeNumber(name); volNum >= 0 {
			for k, file := range n.files {
				if GetRARVolumeNumber(k) == volNum && archivePrefix(k) == reqPrefix {
					f = file
					ok = true
					break
				}
			}
		} else if volNum7z := Get7zVolumeNumber(name); volNum7z >= 0 {
			for k, file := range n.files {
				if Get7zVolumeNumber(k) == volNum7z && archivePrefix(k) == reqPrefix {
					f = file
					ok = true
					break
				}
			}
		}
	}
	if !ok {
		return nil, fs.ErrNotExist
	}

	stream, err := f.OpenStreamCtx(n.ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to open stream: %w", err)
	}

	fw := &fileWrapper{
		ctx:    n.ctx,
		stream: stream,
		file:   f,
		name:   ExtractFilename(f.Name()),
		size:   f.Size(),
	}
	if IsArchiveScanIOTraceEnabled(n.ctx) {
		return wrapFileForScanTrace(fw), nil
	}
	return fw, nil
}

type fileWrapper struct {
	ctx    context.Context
	stream io.ReadSeekCloser
	file   UnpackableFile
	name   string
	size   int64

	readMu  sync.Mutex
	readPos int64
}

func (fw *fileWrapper) Stat() (fs.FileInfo, error) {
	return &fileInfo{name: fw.name, size: fw.size}, nil
}

// Read serves sequential archive parsers via ReadAt so virtual file offsets stay
// aligned with the detected segment map (SegmentReader sequential reads can stop early).
func (fw *fileWrapper) Read(p []byte) (int, error) {
	fw.readMu.Lock()
	off := fw.readPos
	fw.readMu.Unlock()

	n, err := readAtCtx(fw.ctx, fw.file, p, off)
	if n > 0 {
		fw.readMu.Lock()
		fw.readPos += int64(n)
		fw.readMu.Unlock()
	}
	return n, err
}

// Seek tracks readPos arithmetically to stay consistent with the ReadAt-based
// Read path. It must NOT delegate to fw.stream: Read serves bytes via
// fw.file.ReadAt(readPos) and never advances the stream, so the stream's own
// position is stale. A relative (SeekCurrent) delegate — which is how archive
// parsers discard data blocks — would then compute from the wrong base and
// corrupt readPos, making the next read land at the wrong offset.
//
// We also must not clamp the target: callers such as rardecode set their own
// internal offset to the requested value regardless of the returned position,
// so clamping here would desync the next relative seek. Reads past EOF are
// handled by ReadAt returning io.EOF.
func (fw *fileWrapper) Seek(off int64, whence int) (int64, error) {
	fw.readMu.Lock()
	defer fw.readMu.Unlock()

	var target int64
	switch whence {
	case io.SeekStart:
		target = off
	case io.SeekCurrent:
		target = fw.readPos + off
	case io.SeekEnd:
		target = fw.size + off
	default:
		return fw.readPos, fmt.Errorf("invalid whence %d", whence)
	}
	if target < 0 {
		return fw.readPos, fmt.Errorf("negative seek position %d", target)
	}
	fw.readPos = target
	return target, nil
}
func (fw *fileWrapper) Close() error { return fw.stream.Close() }
func (fw *fileWrapper) ReadAt(p []byte, off int64) (int, error) {
	reader, err := fw.file.OpenReaderAt(fw.ctx, off)
	if err != nil {
		return 0, err
	}
	defer reader.Close()

	n, readErr := io.ReadFull(reader, p)
	if readErr == io.ErrUnexpectedEOF {
		return n, io.EOF
	}
	return n, readErr
}

type fileInfo struct {
	name string
	size int64
}

func (fi *fileInfo) Name() string       { return fi.name }
func (fi *fileInfo) Size() int64        { return fi.size }
func (fi *fileInfo) Mode() fs.FileMode  { return 0444 }
func (fi *fileInfo) ModTime() time.Time { return time.Time{} }
func (fi *fileInfo) IsDir() bool        { return false }
func (fi *fileInfo) Sys() interface{}   { return nil }

func archivePrefix(filename string) string {
	lower := strings.ToLower(ExtractFilename(filename))
	if idx := strings.Index(lower, ".part"); idx >= 0 && strings.HasSuffix(lower, ".rar") {
		return lower[:idx]
	}
	if len(lower) >= 4 && lower[len(lower)-4:len(lower)-2] == ".r" {
		suffix := lower[len(lower)-2:]
		if suffix[0] >= '0' && suffix[0] <= '9' && suffix[1] >= '0' && suffix[1] <= '9' {
			return lower[:len(lower)-4]
		}
	}
	if strings.HasSuffix(lower, ".rar") {
		return lower[:len(lower)-4]
	}
	if idx := strings.LastIndex(lower, ".7z."); idx >= 0 {
		return lower[:idx]
	}
	if strings.HasSuffix(lower, ".7z") {
		return lower[:len(lower)-3]
	}
	if len(lower) >= 4 {
		ext := lower[len(lower)-4:]
		if ext[0] == '.' && ext[1] >= '0' && ext[1] <= '9' && ext[2] >= '0' && ext[2] <= '9' && ext[3] >= '0' && ext[3] <= '9' {
			return lower[:len(lower)-4]
		}
	}
	return lower
}
