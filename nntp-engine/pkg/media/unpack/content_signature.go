package unpack

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
)

// Content signatures answer "what is this file?" from its first bytes, for
// releases whose NZB subjects are random hashes and therefore say nothing.
// Every check here is a fixed magic number at a known offset, unlike
// InspectContainerHeader in probe.go, which searches 256 KB of an assumed media
// container for track markers. This one only classifies.

// contentSignatureBytes is how much of a file the signature scan reads. It
// matches PAR2's "MD5 of the first 16k" file hash, so a single read serves both
// the signature check and the PAR2 identity lookup in deobfuscate.go.
const contentSignatureBytes = 16 << 10

type contentKind int

const (
	contentUnknown contentKind = iota
	contentPAR2
	contentRAR
	content7z
	contentMatroska
	contentMP4
	contentAVI
)

func (k contentKind) String() string {
	switch k {
	case contentPAR2:
		return "par2"
	case contentRAR:
		return "rar"
	case content7z:
		return "7z"
	case contentMatroska:
		return "matroska"
	case contentMP4:
		return "mp4"
	case contentAVI:
		return "avi"
	}
	return "unknown"
}

// ext is the extension that makes the rest of the pipeline route a file of this
// kind correctly — the RAR scanner, the 7z identifier, the PAR2 collector and
// the direct-media selector all key off the name.
func (k contentKind) ext() string {
	switch k {
	case contentPAR2:
		return ExtPar2
	case contentRAR:
		return ExtRar
	case content7z:
		return Ext7z
	case contentMatroska:
		return ExtMkv
	case contentMP4:
		return ExtMp4
	case contentAVI:
		return ExtAvi
	}
	return ""
}

var (
	rar4Signature = []byte("Rar!\x1a\x07\x00")
	rar5Signature = []byte("Rar!\x1a\x07\x01\x00")
	sevenZipMagic = []byte{'7', 'z', 0xBC, 0xAF, 0x27, 0x1C}
	matroskaMagic = []byte{0x1A, 0x45, 0xDF, 0xA3}
)

// detectContentKind classifies header (the first bytes of a file) by signature.
func detectContentKind(header []byte) contentKind {
	if len(header) < 8 {
		return contentUnknown
	}
	switch {
	case bytes.HasPrefix(header, par2PacketMagic):
		return contentPAR2
	case bytes.HasPrefix(header, rar5Signature), bytes.HasPrefix(header, rar4Signature):
		return contentRAR
	case bytes.HasPrefix(header, sevenZipMagic):
		return content7z
	case bytes.HasPrefix(header, matroskaMagic):
		return contentMatroska
	case len(header) >= 12 && bytes.Equal(header[4:8], []byte("ftyp")):
		return contentMP4
	case len(header) >= 12 && bytes.Equal(header[0:4], []byte("RIFF")) && bytes.Equal(header[8:12], []byte("AVI ")):
		return contentAVI
	}
	return contentUnknown
}

// rarHeaderInfo is what a RAR volume's own header states about its place in the
// set. For an obfuscated set this is the only in-band ordering that exists: the
// filenames carry none.
type rarHeaderInfo struct {
	// volume is the 0-based position in the volume set, or -1 when the format
	// does not record one (RAR4 stores the order in the filename only).
	volume int
	// isFirst reports that this volume can open the set.
	isFirst bool
}

// parseRARHeader reads the archive header that follows a RAR signature.
// RAR5 states the volume number outright; RAR4 only flags which volume is
// first, so callers must fall back to NZB order for the rest.
func parseRARHeader(header []byte) (rarHeaderInfo, bool) {
	switch {
	case bytes.HasPrefix(header, rar5Signature):
		return parseRAR5MainHeader(header[len(rar5Signature):])
	case bytes.HasPrefix(header, rar4Signature):
		return parseRAR4MainHeader(header[len(rar4Signature):])
	}
	return rarHeaderInfo{volume: -1}, false
}

// parseRAR5MainHeader walks the main archive header:
// CRC32, header size, header type, header flags, [extra size], [data size],
// archive flags, [volume number].
func parseRAR5MainHeader(b []byte) (rarHeaderInfo, bool) {
	info := rarHeaderInfo{volume: -1}
	if len(b) < 4 {
		return info, false
	}
	p := b[4:] // skip header CRC32

	if _, p = readRARVint(p); p == nil { // header size
		return info, false
	}
	headerType, p := readRARVint(p)
	if p == nil || headerType != 1 { // 1 = main archive header
		return info, false
	}
	headerFlags, p := readRARVint(p)
	if p == nil {
		return info, false
	}
	if headerFlags&0x0001 != 0 { // extra area size
		if _, p = readRARVint(p); p == nil {
			return info, false
		}
	}
	if headerFlags&0x0002 != 0 { // data area size
		if _, p = readRARVint(p); p == nil {
			return info, false
		}
	}
	archiveFlags, p := readRARVint(p)
	if p == nil {
		return info, false
	}
	// 0x0001 = part of a volume set, 0x0002 = volume number field present
	// (absent on the first volume, which is therefore volume 0).
	if archiveFlags&0x0002 == 0 {
		info.volume = 0
		info.isFirst = true
		return info, true
	}
	volume, p := readRARVint(p)
	if p == nil {
		return info, false
	}
	info.volume = int(volume)
	info.isFirst = volume == 0
	return info, true
}

// parseRAR4MainHeader reads MAIN_HEAD: HEAD_CRC, HEAD_TYPE, HEAD_FLAGS,
// HEAD_SIZE. Only the flags matter — RAR4 has no volume-number field.
func parseRAR4MainHeader(b []byte) (rarHeaderInfo, bool) {
	info := rarHeaderInfo{volume: -1}
	if len(b) < 7 || b[2] != 0x73 { // 0x73 = MAIN_HEAD
		return info, false
	}
	flags := binary.LittleEndian.Uint16(b[3:5])
	const (
		mhdVolume      = 0x0001
		mhdFirstVolume = 0x0100
	)
	switch {
	case flags&mhdVolume == 0:
		// Not a volume set at all: a single .rar opens itself.
		info.volume = 0
		info.isFirst = true
	case flags&mhdFirstVolume != 0:
		info.volume = 0
		info.isFirst = true
	}
	return info, true
}

// readRARVint decodes RAR5's variable-length integer: 7 bits per byte, low
// byte first, high bit set while more bytes follow. It returns the remaining
// input, or nil when the value is truncated or implausibly long.
func readRARVint(b []byte) (uint64, []byte) {
	var value uint64
	for i := 0; i < 10 && i < len(b); i++ {
		value |= uint64(b[i]&0x7F) << (7 * i)
		if b[i]&0x80 == 0 {
			return value, b[i+1:]
		}
	}
	return 0, nil
}

// firstSegmentReader is the optional capability for reading a file's opening
// bytes without mapping the whole file first.
type firstSegmentReader interface {
	ReadFirstSegment(ctx context.Context) ([]byte, error)
}

// readFileHeader reads the first contentSignatureBytes of f. A short file is
// returned whole.
//
// It prefers ReadFirstSegment because the generic ReadAt path must resolve an
// arbitrary offset, and resolving one means probing every segment of the file
// to learn its decoded size. Paying that to look at 16 KiB is ruinous on a
// release being identified from its headers: seven files cost thousands of
// segment probes instead of seven articles.
func readFileHeader(ctx context.Context, f UnpackableFile) ([]byte, error) {
	if f == nil {
		return nil, io.EOF
	}
	if r, ok := f.(firstSegmentReader); ok {
		data, err := r.ReadFirstSegment(ctx)
		if err != nil {
			return nil, err
		}
		if len(data) == 0 {
			return nil, io.EOF
		}
		if len(data) > contentSignatureBytes {
			data = data[:contentSignatureBytes]
		}
		return data, nil
	}
	buf := make([]byte, contentSignatureBytes)
	n, err := f.ReadAt(buf, 0)
	if n > 0 {
		return buf[:n], nil
	}
	if err == nil {
		err = io.EOF
	}
	return nil, err
}
