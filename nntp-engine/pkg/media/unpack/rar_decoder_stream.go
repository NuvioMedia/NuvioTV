package unpack

import (
	"context"
	"fmt"
	"io"
	"strings"

	"streamnzb/pkg/core/logger"

	"github.com/javi11/rardecode/v2"
)

type decoderReadSeekCloser struct {
	rc     io.ReadCloser
	seeker io.Seeker
	size   int64
	pos    int64
}

func (d *decoderReadSeekCloser) Read(p []byte) (int, error) {
	n, err := d.rc.Read(p)
	if n > 0 {
		d.pos += int64(n)
	}
	return n, err
}

func (d *decoderReadSeekCloser) Seek(offset int64, whence int) (int64, error) {
	if d.seeker == nil {
		return 0, fmt.Errorf("rardecode stream: seeker unavailable")
	}

	var target int64
	switch whence {
	case io.SeekStart:
		target = offset
	case io.SeekCurrent:
		target = d.pos + offset
	case io.SeekEnd:
		if d.size <= 0 {
			abs, err := d.seeker.Seek(offset, whence)
			if err != nil {
				return 0, err
			}
			d.pos = abs
			return abs, nil
		}
		target = d.size + offset
	default:
		return 0, fmt.Errorf("invalid whence %d", whence)
	}

	if target < 0 {
		return 0, fmt.Errorf("seek before start")
	}
	if d.size > 0 && target > d.size {
		return 0, fmt.Errorf("seek past end")
	}

	// http.ServeContent probes size via Seek(0, SeekEnd) then rewinds with
	// Seek(0, SeekStart). rardecode cannot SeekStart to multi-volume EOF without
	// walking every packed block; answer EOF from blueprint size instead.
	if whence == io.SeekEnd && d.size > 0 {
		d.pos = target
		return target, nil
	}

	abs, err := d.seeker.Seek(target, io.SeekStart)
	if err != nil {
		return 0, err
	}
	d.pos = abs
	return abs, nil
}

func (d *decoderReadSeekCloser) Close() error {
	if d.rc == nil {
		return nil
	}
	return d.rc.Close()
}

func volumeFilesFromBlueprint(bp *ArchiveBlueprint) (map[string]UnpackableFile, string) {
	files := make(map[string]UnpackableFile, len(bp.Parts))
	var firstVol string
	for i, p := range bp.Parts {
		name := ExtractFilename(p.VolFile.Name())
		files[name] = p.VolFile
		if i == 0 {
			firstVol = name
		}
	}
	return files, firstVol
}

func streamRARFromDecoder(ctx context.Context, bp *ArchiveBlueprint, password string) (io.ReadSeekCloser, string, int64, error) {
	if err := contextErr(ctx); err != nil {
		return nil, "", 0, err
	}
	if bp == nil || len(bp.Parts) < 2 {
		return nil, "", 0, fmt.Errorf("rardecode stream: need multi-volume blueprint")
	}

	volFiles, firstVol := volumeFilesFromBlueprint(bp)
	if firstVol == "" {
		return nil, "", 0, fmt.Errorf("rardecode stream: empty first volume")
	}

	playCtx := playbackSegmentMapCtx(ctx)
	fsys := NewNZBFSFromMapCtx(playCtx, volFiles)
	opts := []rardecode.Option{
		rardecode.FileSystem(fsys),
		rardecode.BufferSize(scanVolumeBufferSize),
		rardecode.ParallelRead(false),
		rardecode.SkipVolumeCheck,
		rardecode.ListTolerant,
		// Hashed STORE blocks are wrapped in checksumReader when skipCheck is off;
		// that wrapper is read-only and openArchiveFile then returns a non-seeker.
		rardecode.SkipCheck,
	}
	if password != "" {
		opts = append(opts, rardecode.Password(password))
	}

	files, err := rardecode.List(firstVol, opts...)
	if err != nil {
		return nil, "", 0, fmt.Errorf("rardecode list: %w", err)
	}

	lowerTarget := strings.ToLower(bp.MainFileName)
	for _, f := range files {
		if strings.ToLower(f.Name) != lowerTarget {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return nil, "", 0, fmt.Errorf("rardecode open %q: %w", f.Name, err)
		}
		seeker, ok := rc.(io.Seeker)
		if !ok {
			_ = rc.Close()
			return nil, "", 0, fmt.Errorf("rardecode open %q: stream is not seekable (volume reader or post-open checksum wrapper)", f.Name)
		}
		size := bp.TotalSize
		if f.UnPackedSize > 0 {
			size = f.UnPackedSize
		}
		logger.Trace("Opened rardecode media stream", "file", f.Name, "size", size, "volumes", len(volFiles))
		return &decoderReadSeekCloser{rc: rc, seeker: seeker, size: size}, f.Name, size, nil
	}
	return nil, "", 0, fmt.Errorf("rardecode stream: file %q not in archive", bp.MainFileName)
}
