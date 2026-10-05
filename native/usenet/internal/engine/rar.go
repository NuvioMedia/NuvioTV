package engine

// Stored RAR header mapping adapted from AltMount's archive/rar processor and
// javi11/rardecode's archive15.go, archive50.go and archive_info.go. See licenses.
// There is deliberately no eager archive iterator or decompression path.

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var ErrCompressedRAR = errors.New("compressed RAR is not supported; use a Stored/uncompressed release")
var ErrEncryptedRAR = errors.New("this RAR encryption method is not supported")
var errNotRAR = errors.New("not a supported RAR archive")

type rarBlock struct {
	name                                string
	data, packed, unpacked, next        int64
	file, directory, before, after, end bool
	volume                              int
	version                             int
	main, template                      bool
	firstVolume                         bool // Main header of a set's first volume.
	mainLayout                          rarMainLayout
	packedWidth                         int
	crypt                               *rarCrypt // Encrypted file data.
	headerCrypt                         *rarCrypt // Following headers are encrypted (-hp).
	headerPrefix                        int64     // On-disk IV/salt before an encrypted header.
}

type rarMainLayout struct {
	size                   int64
	sizeWidth, volumeWidth int
}

type rarVolume struct {
	file     *File
	version  int
	start    int64 // First header to walk: past any RAR5 archive encryption header.
	template bool
	layout   rarMainLayout
	headers  *rarHeaderCrypt
}

// enableHeaderCrypt switches the volume to encrypted headers after its RAR5
// archive encryption header or RAR4 -hp main header.
func (v *rarVolume) enableHeaderCrypt(ctx context.Context, c *rarCrypt) error {
	h, err := newRARHeaderCrypt(ctx, c, v.file.password)
	if err != nil {
		return err
	}
	v.headers = h
	return nil
}

// first returns the main header, enabling header decryption on the way.
func (v *rarVolume) first(ctx context.Context) (rarBlock, error) {
	b, err := v.block(ctx, v.start)
	if err == nil && b.headerCrypt != nil && !b.main {
		if err = v.enableHeaderCrypt(ctx, b.headerCrypt); err != nil {
			return b, err
		}
		v.start = b.next
		b, err = v.block(ctx, v.start)
	}
	return b, err
}

func openRAR(ctx context.Context, f *File) (*rarVolume, error) {
	r := f.headerReader(ctx)
	defer r.Close()
	version, start, err := rarSignature(r)
	if err != nil {
		return nil, err
	}
	return &rarVolume{file: f, start: start, version: version}, nil
}

// rarSignature returns the format version and the first header's offset.
func rarSignature(r io.ReaderAt) (int, int64, error) {
	var sig [8]byte
	if err := readFullAt(r, sig[:7], 0); err != nil {
		return 0, 0, err
	}
	if bytes.Equal(sig[:7], []byte("Rar!\x1a\x07\x00")) {
		return 4, 7, nil
	}
	if bytes.Equal(sig[:7], []byte("Rar!\x1a\x07\x01")) {
		if err := readFullAt(r, sig[7:], 7); err != nil {
			return 0, 0, err
		}
		if sig[7] == 0 {
			return 5, 8, nil
		}
	}
	return 0, 0, errNotRAR
}

func (v *rarVolume) block(ctx context.Context, pos int64) (rarBlock, error) {
	r := v.file.headerReader(ctx)
	defer r.Close()
	if pos >= v.file.Size() {
		return rarBlock{end: true}, nil
	}
	if v.headers != nil {
		return v.encryptedBlock(ctx, r, pos)
	}
	if v.version == 4 {
		return rar4Block(r, v.file.Size(), pos)
	}
	return rar5Block(r, v.file.Size(), pos)
}

// encryptedBlock parses a -hp header in plaintext coordinates, then moves its
// offsets past the IV/salt prefix and the AES padding stored on disk.
func (v *rarVolume) encryptedBlock(ctx context.Context, r io.ReaderAt, pos int64) (rarBlock, error) {
	view, prefix, err := v.headers.view(ctx, r, pos)
	if err != nil {
		return rarBlock{}, err
	}
	var b rarBlock
	if v.version == 4 {
		b, err = rar4Block(view, 1<<62, 0)
	} else {
		b, err = rar5Block(view, 1<<62, 0)
	}
	if err != nil {
		if strings.Contains(err.Error(), "CRC mismatch") || strings.Contains(err.Error(), "header size") || strings.Contains(err.Error(), "header length") {
			// Decrypted garbage: the password is wrong.
			return b, ErrRARWrongPassword
		}
		return b, err
	}
	b.data = pos + prefix + roundUp16(b.data)
	b.next = b.data + b.packed
	b.headerPrefix = prefix
	if b.packed < 0 || b.packed > v.file.Size()-b.data {
		return b, errors.New("RAR data exceeds volume")
	}
	return b, nil
}

func rar4Block(r io.ReaderAt, volumeSize, pos int64) (rarBlock, error) {
	var b rarBlock
	b.volume = -1
	base := make([]byte, 7)
	if err := readFullAt(r, base, pos); err != nil {
		return b, err
	}
	n := int(binary.LittleEndian.Uint16(base[5:7]))
	if n < 7 {
		return b, errors.New("invalid RAR4 header size")
	}
	h := make([]byte, n)
	copy(h, base)
	if err := readFullAt(r, h[7:], pos+7); err != nil {
		return b, err
	}
	if uint16(crc32.ChecksumIEEE(h[2:])) != binary.LittleEndian.Uint16(h) {
		return b, errors.New("RAR4 header CRC mismatch")
	}
	flags := binary.LittleEndian.Uint16(h[3:5])
	kind := h[2]
	b.main = kind == 0x73
	b.firstVolume = b.main && flags&0x0100 != 0
	b.template = (b.main && n == 13 && flags&0x40 == 0) || kind == 0x74
	b.data = pos + int64(n)
	b.next = b.data
	if flags&0x8000 != 0 {
		if len(h) < 11 {
			return b, io.ErrUnexpectedEOF
		}
		b.packed = int64(binary.LittleEndian.Uint32(h[7:11]))
	}
	if kind == 0x73 && flags&0x80 != 0 {
		b.headerCrypt = &rarCrypt{version: 4}
	}
	if kind == 0x7b {
		b.end = true
		return b, nil
	}
	if kind == 0x74 {
		if len(h) < 32 {
			return b, io.ErrUnexpectedEOF
		}
		b.file = true
		b.before = flags&1 != 0
		b.after = flags&2 != 0
		b.directory = flags&0xe0 == 0xe0
		if h[25] != 0x30 {
			return b, ErrCompressedRAR
		}
		b.packed = int64(binary.LittleEndian.Uint32(h[7:11]))
		b.unpacked = int64(binary.LittleEndian.Uint32(h[11:15]))
		namePos := 32
		nameLen := int(binary.LittleEndian.Uint16(h[26:28]))
		if flags&0x100 != 0 {
			if len(h) < 40 {
				return b, io.ErrUnexpectedEOF
			}
			b.packed |= int64(binary.LittleEndian.Uint32(h[32:36])) << 32
			b.unpacked |= int64(binary.LittleEndian.Uint32(h[36:40])) << 32
			namePos = 40
		}
		if nameLen > len(h)-namePos {
			return b, io.ErrUnexpectedEOF
		}
		name := h[namePos : namePos+nameLen]
		// The ANSI prefix of RAR4 Unicode names remains suitable for media
		// selection; RAR5 filenames are UTF-8. Never use archive names as paths.
		if j := bytes.IndexByte(name, 0); j >= 0 {
			name = name[:j]
		}
		b.name = string(name)
		if flags&4 != 0 {
			// RAR 3.x AES needs the salt; older RAR 2.x encryption is not supported.
			saltPos := namePos + nameLen
			if flags&0x400 == 0 || len(h) < saltPos+8 {
				return b, ErrEncryptedRAR
			}
			b.crypt = &rarCrypt{version: 4, salt: append([]byte(nil), h[saltPos:saltPos+8]...)}
		}
	}
	if b.packed < 0 || b.packed > volumeSize-b.data {
		return b, errors.New("RAR4 data exceeds volume")
	}
	b.next = b.data + b.packed
	return b, nil
}

type vintReader struct {
	b   []byte
	pos int
	err error
}

func (v *vintReader) num() uint64 {
	if v.err != nil {
		return 0
	}
	n, k := binary.Uvarint(v.b[v.pos:])
	if k <= 0 {
		v.err = errors.New("invalid RAR5 integer")
		return 0
	}
	v.pos += k
	return n
}
func (v *vintReader) skip(n int) {
	if n < 0 || n > len(v.b)-v.pos {
		v.err = io.ErrUnexpectedEOF
		return
	}
	v.pos += n
}

func rar5Block(r io.ReaderAt, volumeSize, pos int64) (rarBlock, error) {
	b := rarBlock{volume: -1}
	var lead [14]byte
	if err := readFullAt(r, lead[:5], pos); err != nil {
		return b, err
	}
	nvar := 1
	for lead[3+nvar]&0x80 != 0 {
		if nvar == 10 {
			return b, errors.New("invalid RAR5 header size")
		}
		if err := readFullAt(r, lead[4+nvar:5+nvar], pos+4+int64(nvar)); err != nil {
			return b, err
		}
		nvar++
	}
	size, k := binary.Uvarint(lead[4 : 4+nvar])
	if k <= 0 || size == 0 || size > 1<<20 {
		return b, errors.New("invalid RAR5 header length")
	}
	h := make([]byte, nvar+int(size))
	copy(h, lead[4:4+nvar])
	if err := readFullAt(r, h[nvar:], pos+4+int64(nvar)); err != nil {
		return b, err
	}
	if crc32.ChecksumIEEE(h) != binary.LittleEndian.Uint32(lead[:4]) {
		return b, errors.New("RAR5 header CRC mismatch")
	}
	x := vintReader{b: h[nvar:]}
	kind := x.num()
	flags := x.num()
	extra := uint64(0)
	if flags&1 != 0 {
		extra = x.num()
	}
	if flags&2 != 0 {
		start := x.pos
		b.packed = int64(x.num())
		b.packedWidth = x.pos - start
		b.template = true
	}
	b.data = pos + 4 + int64(len(h))
	b.next = b.data + b.packed
	if b.packed < 0 || b.packed > volumeSize-b.data {
		return b, errors.New("RAR5 data exceeds volume")
	}
	if kind == 4 {
		// Archive encryption header: every following header is encrypted.
		if x.num() != 0 {
			return b, ErrEncryptedRAR
		}
		ef := x.num()
		c, err := rar5CryptFields(&x, ef&1 != 0, false)
		if err != nil {
			return b, err
		}
		b.headerCrypt = c
		return b, x.err
	}
	if kind == 5 {
		b.end = true
		return b, x.err
	}
	if kind == 1 {
		b.main = true
		af := x.num()
		start := x.pos
		if af&2 != 0 {
			b.volume = int(x.num())
		} else {
			b.volume = 0
		}
		b.firstVolume = b.volume == 0
		b.mainLayout = rarMainLayout{int64(size), nvar, x.pos - start}
		b.template = flags&^uint64(5) == 0 && af == 3 &&
			extra == uint64(len(x.b)-x.pos) && rarStableMainExtra(x.b[x.pos:])
	}
	if kind == 2 {
		b.file = true
		b.before = flags&8 != 0
		b.after = flags&16 != 0
		ff := x.num()
		b.directory = ff&1 != 0
		b.unpacked = int64(x.num())
		x.num()
		if ff&8 != 0 || b.unpacked < 0 {
			return b, errors.New("RAR5 file size is unknown")
		}
		if ff&2 != 0 {
			x.skip(4)
		}
		if ff&4 != 0 {
			x.skip(4)
		}
		comp := x.num()
		if comp&0x380 != 0 {
			return b, ErrCompressedRAR
		}
		x.num()
		nn := x.num()
		if nn > uint64(len(x.b)-x.pos) {
			return b, io.ErrUnexpectedEOF
		}
		b.name = string(x.b[x.pos : x.pos+int(nn)])
		x.skip(int(nn))
		if extra > uint64(len(x.b)-x.pos) {
			return b, errors.New("invalid RAR5 extra area")
		}
		if extra > 0 {
			e := vintReader{b: x.b[len(x.b)-int(extra):]}
			for e.pos < len(e.b) && e.err == nil {
				length := e.num()
				start := e.pos
				typ := e.num()
				if length > uint64(len(e.b)-start) || length == 0 {
					return b, errors.New("invalid RAR5 extra record")
				}
				if typ == 1 {
					// File encryption record: AES-256 version 0 only.
					rec := vintReader{b: e.b[e.pos : start+int(length)]}
					if rec.num() != 0 {
						return b, ErrEncryptedRAR
					}
					ef := rec.num()
					c, err := rar5CryptFields(&rec, ef&1 != 0, true)
					if err != nil {
						return b, err
					}
					b.crypt = c
				}
				e.pos = start + int(length)
			}
			if e.err != nil {
				return b, e.err
			}
		}
	}
	return b, x.err
}

// rar5CryptFields reads KDF count, salt, optional IV and password check.
func rar5CryptFields(x *vintReader, hasCheck, hasIV bool) (*rarCrypt, error) {
	need := 1 + 16
	if hasIV {
		need += 16
	}
	if hasCheck {
		need += 12
	}
	if x.err != nil || len(x.b)-x.pos < need {
		return nil, errors.New("invalid RAR5 encryption record")
	}
	c := &rarCrypt{version: 5, kdf: int(x.b[x.pos])}
	x.pos++
	c.salt = append([]byte(nil), x.b[x.pos:x.pos+16]...)
	x.pos += 16
	if hasIV {
		c.iv = append([]byte(nil), x.b[x.pos:x.pos+16]...)
		x.pos += 16
	}
	if hasCheck {
		c.check = append([]byte(nil), x.b[x.pos:x.pos+12]...)
		x.pos += 12
	}
	return c, nil
}

type extent struct {
	file                  *File
	offset, length, start int64
}

// rarCursor skips packed data arithmetically. A split block ends this volume's
// useful headers, so its trailer is never fetched just to locate the next part.
type rarCursor struct {
	files     []*File
	index     int
	volume    *rarVolume
	pos       int64
	unordered bool
	resolved  map[int]*rarVolume
	scanned   int
}

func (c *rarCursor) resolve(ctx context.Context) (*rarVolume, error) {
	if !c.unordered {
		return openRAR(ctx, c.files[c.index])
	}
	if c.resolved == nil {
		c.resolved = make(map[int]*rarVolume)
	}
	if v := c.resolved[c.index]; v != nil {
		return v, nil
	}
	// Obfuscated RAR5 sets may lose even the NZB's volume order. Read only as
	// many main headers as needed to find this volume: the next one first, as
	// release order usually holds, then small batches so a shuffled set does
	// not cost one round trip per volume. Cache header mappings so later seeks
	// do not repeat discovery. RAR4 relies on release/NZB order.
	for size := 1; c.scanned < len(c.files); size = rarResolveBatch {
		batch := c.files[c.scanned:min(len(c.files), c.scanned+size)]
		type header struct {
			v      *rarVolume
			volume int
			err    error
		}
		headers := make([]header, len(batch))
		var wg sync.WaitGroup
		for k, f := range batch {
			wg.Add(1)
			go func() {
				defer wg.Done()
				v, e := openRAR(ctx, f)
				h := header{v: v, volume: -1, err: e}
				if e == nil && v.version == 5 {
					b, e := v.first(ctx)
					h.volume, h.err = b.volume, e
				}
				headers[k] = h
			}()
		}
		wg.Wait()
		for _, h := range headers {
			c.scanned++
			if errors.Is(h.err, errNotRAR) {
				continue // Unknown non-archive NZB entries are not volume positions.
			}
			if h.err != nil {
				return nil, h.err
			}
			i := h.volume
			if h.v.version != 5 {
				i = len(c.resolved)
			}
			if i < 0 || i >= len(c.files) || c.resolved[i] != nil {
				return nil, errors.New("ambiguous obfuscated RAR volume order")
			}
			c.resolved[i] = h.v
		}
		if v := c.resolved[c.index]; v != nil {
			return v, nil
		}
	}
	return nil, errors.New("missing obfuscated RAR volume")
}

// rarResolveBatch bounds the main headers read together for an unordered set.
const rarResolveBatch = 8

// rarFirstVolume reports whether f opens a RAR set whose first file is name.
// Used to find a first volume posted under another set's name.
func rarFirstVolume(ctx context.Context, f *File) (string, bool) {
	v, err := openRAR(ctx, f)
	if err != nil {
		return "", false
	}
	main, err := v.first(ctx)
	if err != nil || !main.main || !main.firstVolume || main.next <= v.start {
		return "", false
	}
	for pos := main.next; ; {
		b, err := v.block(ctx, pos)
		if err != nil || b.end {
			return "", false
		}
		if b.file && !b.directory {
			return b.name, !b.before
		}
		if b.next <= pos {
			return "", false
		}
		pos = b.next
	}
}

// rarFileName returns the first file named in volume f, from its headers.
func rarFileName(ctx context.Context, f *File) string {
	v, err := openRAR(ctx, f)
	if err != nil {
		return ""
	}
	if _, err := v.first(ctx); err != nil {
		return ""
	}
	for pos := v.start; ; {
		b, err := v.block(ctx, pos)
		if err != nil || b.end {
			return ""
		}
		if b.file && !b.directory {
			return b.name
		}
		if b.next <= pos {
			return ""
		}
		pos = b.next
	}
}
func (c *rarCursor) next(ctx context.Context) (rarBlock, *File, error) {
	for c.index < len(c.files) {
		if c.volume == nil {
			v, e := c.resolve(ctx)
			if e != nil {
				return rarBlock{}, nil, e
			}
			c.volume = v
			c.pos = v.start
		}
		b, e := c.volume.block(ctx, c.pos)
		if e != nil {
			return b, nil, e
		}
		if b.headerCrypt != nil {
			if e := c.volume.enableHeaderCrypt(ctx, b.headerCrypt); e != nil {
				return b, nil, e
			}
			if !b.main {
				// RAR5 archive encryption header: the main header follows.
				if b.next <= c.pos {
					return b, nil, errors.New("RAR header does not advance")
				}
				c.pos, c.volume.start = b.next, b.next
				continue
			}
		}
		f := c.volume.file
		b.version = c.volume.version
		if b.main && b.volume >= 0 && b.volume != c.index {
			return b, nil, errors.New("RAR volume number does not match sequence")
		}
		if b.main && c.pos == c.volume.start {
			c.volume.template = b.template
			c.volume.layout = b.mainLayout
		} else if b.file {
			b.template = b.template && c.volume.template
			b.mainLayout = c.volume.layout
			c.volume.template = false
		} else {
			c.volume.template = false
		}
		if b.end {
			c.index++
			c.volume = nil
			continue
		}
		if b.next <= c.pos {
			return b, nil, errors.New("RAR header does not advance")
		}
		c.pos = b.next
		if b.after {
			c.index++
			c.volume = nil
		}
		if b.file {
			return b, f, nil
		}
	}
	return rarBlock{}, nil, io.EOF
}

type Content struct {
	Name         string
	Size         int64
	direct       *File
	mu           sync.RWMutex
	layoutGate   chan struct{} // cancellable discovery, independent of mapped reads.
	parts        []extent
	cursor       *rarCursor
	complete     bool
	predicted    *rarPrediction
	aes          *cbcCipher                  // Stored AES (7z or RAR): parts hold ciphertext.
	padded       bool                        // Encrypted RAR: packed data is Size rounded up to 16.
	pending      map[*File]int64             // Predicted 7z volume sizes not yet confirmed.
	unusable     error                       // Listed 7z entry that cannot be streamed.
	prepare      func(context.Context) error // Selected-only work: nested layout, RAR keys.
	nested       *nestedRAR                  // RAR set inside a 7z: children map onto its volumes.
	children     []nestedChild
	layoutNS     atomic.Int64
	layoutWaitNS atomic.Int64
}

// payloadSize is the packed byte count of the selected data in its volumes.
func (c *Content) payloadSize() int64 {
	if c.padded {
		return roundUp16(c.Size)
	}
	return c.Size
}

func (c *Content) extend(ctx context.Context, off int64) error {
	for {
		c.mu.RLock()
		last := c.parts[len(c.parts)-1]
		finished := c.complete || (off >= 0 && last.start+last.length > off)
		c.mu.RUnlock()
		if finished {
			return nil
		}
		layoutStart := time.Now()
		b, f, e := c.cursor.next(ctx)
		c.layoutNS.Add(time.Since(layoutStart).Nanoseconds())
		if e != nil {
			return fmt.Errorf("missing RAR continuation: %w", e)
		}
		if !b.before || b.name != c.Name || b.unpacked != c.Size {
			return errors.New("RAR continuation does not match selected file")
		}
		start := last.start + last.length
		if b.packed <= 0 || b.packed > c.payloadSize()-start {
			return errors.New("invalid RAR continuation size")
		}
		if !b.after && start+b.packed != c.payloadSize() {
			return errors.New("incomplete stored RAR data")
		}
		c.mu.Lock()
		c.parts = append(c.parts, extent{f, b.data, b.packed, start})
		c.complete = !b.after
		c.mu.Unlock()
		// Selection must leave the cursor at the real end of an unselected
		// entry. Only playback may replace discovery with a predicted layout.
		if off >= 0 && b.after && len(c.parts) == 2 {
			c.predictRAR(ctx, b, f)
		}
	}
}

func rarVintLen(n int64) int {
	width := 1
	for n >= 128 {
		n >>= 7
		width++
	}
	return width
}

// Quick Open locator offsets are commonly reserved with padded integers. Their
// values change, but the reserved space can be reused by the template. Other
// main-header metadata and recovery records do not qualify for this fast path.
func rarStableMainExtra(extra []byte) bool {
	x := vintReader{b: extra}
	for x.pos < len(x.b) && x.err == nil {
		n := x.num()
		start := x.pos
		if n == 0 || n > uint64(len(x.b)-start) || x.num() != 1 || x.num() != 1 {
			return false
		}
		x.num() // Quick Open offset; its value does not locate the file payload.
		if x.pos != start+int(n) {
			return false
		}
	}
	return x.err == nil
}

// Predictions assume equal decoded volume sizes and a repeated continuation
// header. NZB sizes are wire estimates, so they must never supply byte offsets.
// The final header independently checks the total; each intermediate header is
// checked on demand before its predicted extent can be used by a reader.
type rarPrediction struct {
	volumeSize int64
	version    int
	verified   []bool
}

func (c *Content) predictRAR(ctx context.Context, b rarBlock, f *File) {
	layoutStart := time.Now()
	defer func() { c.layoutNS.Add(time.Since(layoutStart).Nanoseconds()) }()
	cur := c.cursor
	volumeSize := f.Size()
	if cur.unordered || cur.index != 2 || len(cur.files) < 4 || !b.template ||
		f != cur.files[1] || cur.files[0].Size() != volumeSize {
		return
	}
	parts := append([]extent(nil), c.parts...)
	start := parts[1].start + parts[1].length
	for i := 2; i < len(cur.files)-1; i++ {
		delta := int64(0)
		if b.version == 5 {
			// Volume numbers are zero-based: part129 is the first two-byte index.
			main := b.mainLayout
			delta = int64(max(main.volumeWidth, rarVintLen(int64(i))) - main.volumeWidth)
			delta += int64(max(main.sizeWidth, rarVintLen(main.size+delta)) - main.sizeWidth)
			if b.headerPrefix > 0 {
				// An encrypted main header is stored padded to the AES block.
				plain := 4 + int64(main.sizeWidth) + main.size
				delta = roundUp16(plain+delta) - roundUp16(plain)
			}
		}
		packed := b.packed - delta
		if packed <= 0 || packed >= c.payloadSize()-start ||
			(b.version == 5 && b.packedWidth == rarVintLen(b.packed) && rarVintLen(packed) != b.packedWidth) {
			return
		}
		file := cur.files[i]
		file.mu.RLock()
		wrongSize := file.exact && file.size != volumeSize
		file.mu.RUnlock()
		if wrongSize {
			return
		}
		parts = append(parts, extent{file, b.data + delta, packed, start})
		start += packed
	}
	// The last header may use a different packed-size width, hashes or extra
	// fields, and the selected file need not consume all of the last volume.
	lastCursor := rarCursor{files: cur.files, index: len(cur.files) - 1}
	last, file, err := lastCursor.next(ctx)
	if err != nil || ctx.Err() != nil || last.version != b.version || !last.before || last.after ||
		last.directory || last.name != c.Name || last.unpacked != c.Size ||
		last.packed <= 0 || last.packed != c.payloadSize()-start {
		return
	}
	parts = append(parts, extent{file, last.data, last.packed, start})
	prediction := &rarPrediction{volumeSize: volumeSize, version: b.version, verified: make([]bool, len(parts))}
	prediction.verified[0], prediction.verified[1], prediction.verified[len(parts)-1] = true, true, true
	c.mu.Lock()
	c.parts, c.predicted, c.complete = parts, prediction, true
	c.mu.Unlock()
}

// Called with layoutGate held. Keep the original cursor at part3 so a failed
// prediction can be discarded without losing the authoritative prefix.
func (c *Content) verifyRARPart(ctx context.Context, off int64) error {
	c.mu.RLock()
	prediction := c.predicted
	i := sort.Search(len(c.parts), func(i int) bool { return c.parts[i].start+c.parts[i].length > off })
	if prediction == nil || i == len(c.parts) || prediction.verified[i] {
		c.mu.RUnlock()
		return nil
	}
	part := c.parts[i]
	c.mu.RUnlock()
	layoutStart := time.Now()
	cur := rarCursor{files: c.cursor.files, index: i}
	b, f, err := cur.next(ctx)
	c.layoutNS.Add(time.Since(layoutStart).Nanoseconds())
	if err != nil {
		return fmt.Errorf("checking RAR continuation: %w", err)
	}
	if f == part.file && b.version == prediction.version && b.before && b.after &&
		!b.directory && b.name == c.Name && b.unpacked == c.Size &&
		b.data == part.offset && b.packed == part.length && f.Size() == prediction.volumeSize {
		c.mu.Lock()
		prediction.verified[i] = true
		c.mu.Unlock()
		return nil
	}
	c.mu.Lock()
	c.parts, c.predicted, c.complete = c.parts[:2], nil, false
	c.mu.Unlock()
	return c.extend(ctx, off)
}

func (c *Content) mappedPart(off int64) (extent, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	i := sort.Search(len(c.parts), func(i int) bool { return c.parts[i].start+c.parts[i].length > off })
	if i < len(c.parts) && off >= c.parts[i].start {
		if c.predicted != nil && !c.predicted.verified[i] {
			return extent{}, false
		}
		if _, pending := c.pending[c.parts[i].file]; pending {
			return extent{}, false
		}
		return c.parts[i], true
	}
	return extent{}, false
}
func (c *Content) part(ctx context.Context, off int64) (extent, error) {
	if p, ok := c.mappedPart(off); ok {
		return p, nil
	}
	c.mu.Lock()
	if c.layoutGate == nil {
		c.layoutGate = make(chan struct{}, 1)
	}
	gate := c.layoutGate
	c.mu.Unlock()
	waitStart := time.Now()
	select {
	case gate <- struct{}{}:
		c.layoutWaitNS.Add(time.Since(waitStart).Nanoseconds())
		defer func() { <-gate }()
	case <-ctx.Done():
		return extent{}, ctx.Err()
	}
	if err := c.extend(ctx, off); err != nil {
		return extent{}, err
	}
	if err := c.verifyRARPart(ctx, off); err != nil {
		return extent{}, err
	}
	if err := c.verifyVolume(ctx, off); err != nil {
		return extent{}, err
	}
	if p, ok := c.mappedPart(off); ok {
		return p, nil
	}
	return extent{}, io.EOF
}

type boundaryRead struct {
	cancel context.CancelFunc
	done   chan struct{}
	target int64
	reader *FileReader
	err    error
}
type ContentReader struct {
	warmup        *startupWarmup
	content       *Content
	ctx           context.Context
	ahead         int
	current       *FileReader
	pos           int64
	boundary      *boundaryRead
	currentCancel context.CancelFunc
	cipher        []byte         // Reused ciphertext buffer for encrypted 7z content.
	inner         *ContentReader // Current 7z entry reader of nested RAR content.
}

func (c *Content) Reader(ctx context.Context, ahead int) *ContentReader {
	return &ContentReader{content: c, ctx: ctx, ahead: ahead}
}

func (r *ContentReader) primeBoundary(e extent, pos int64) {
	end := e.start + e.length
	if r.content.direct != nil || r.ahead <= 0 || r.boundary != nil || end >= r.content.Size {
		return
	}
	window := min(e.file.store.readAheadLimit(), int64(r.ahead)*max(int64(1), e.file.Size()/int64(len(e.file.segments))))
	if end-pos > window {
		return
	}
	ctx, cancel := context.WithCancel(r.ctx)
	work := &boundaryRead{cancel: cancel, done: make(chan struct{}), target: end}
	r.boundary = work
	go func() {
		defer close(work.done)
		next, err := r.content.part(ctx, end)
		if err != nil {
			work.err = err
			return
		}
		work.reader = next.file.Reader(ctx, r.ahead)
		var one [1]byte
		_, work.err = work.reader.ReadAt(one[:], next.offset)
	}()
}
func (r *ContentReader) cancelBoundary() {
	if work := r.boundary; work != nil {
		work.cancel()
		<-work.done
		if work.reader != nil {
			work.reader.Close()
		}
		r.boundary = nil
	}
}
func (r *ContentReader) closeCurrent() {
	if r.currentCancel != nil {
		r.currentCancel()
		r.currentCancel = nil
	}
	if r.current != nil {
		r.current.Close()
		r.current = nil
	}
}
func (r *ContentReader) Read(p []byte) (int, error) {
	c := r.content
	if len(p) > 0 {
		r.warmup.observeRead(r.pos, c.Size)
	}
	if r.pos >= c.Size {
		return 0, io.EOF
	}
	if c.nested != nil {
		return r.readNested(p)
	}
	if c.aes != nil {
		return r.readDecrypted(p)
	}
	n, err := r.readRaw(p, r.pos)
	r.pos += int64(n)
	return n, err
}

// readRaw reads mapped payload bytes at a content offset. Encrypted 7z content
// also maps the ciphertext just outside [0, Size) that its AES blocks need.
func (r *ContentReader) readRaw(p []byte, pos int64) (int, error) {
	c := r.content
	e := extent{file: c.direct, length: c.Size}
	if c.direct == nil {
		var err error
		e, err = c.part(r.ctx, pos)
		if err != nil {
			if errors.Is(err, io.EOF) {
				err = fmt.Errorf("%w: incomplete RAR continuation: %v", errInvalidArticle, err)
			}
			return 0, err
		}
	}
	if r.current == nil || r.current.f != e.file {
		r.closeCurrent()
		if work := r.boundary; work != nil && work.target == e.start {
			select {
			case <-work.done:
				if work.err == nil && work.reader != nil {
					r.current = work.reader
					r.currentCancel = work.cancel
					r.boundary = nil
				}
			default:
			}
		}
		if r.current == nil {
			r.cancelBoundary()
			r.current = e.file.Reader(r.ctx, r.ahead)
		}
	}
	r.current.fill = isVideo(c.Name)
	n, err := r.current.ReadAt(p[:min(int64(len(p)), e.start+e.length-pos)], e.offset+pos-e.start)
	if len(p) > 0 && n == 0 && (err == io.EOF || err == nil) {
		err = errInvalidArticle
	}
	if err == nil {
		r.primeBoundary(e, pos+int64(n))
	}
	return n, err
}
func (r *ContentReader) Seek(off int64, w int) (int64, error) {
	switch w {
	case io.SeekStart:
	case io.SeekCurrent:
		off += r.pos
	case io.SeekEnd:
		off += r.content.Size
	default:
		return r.pos, errors.New("invalid seek")
	}
	if off < 0 {
		return r.pos, errors.New("negative seek")
	}
	if off != r.pos {
		r.cancelBoundary()
		r.closeCurrent()
	}
	r.pos = off
	return off, nil
}
func (r *ContentReader) Close() error {
	r.cancelBoundary()
	r.closeCurrent()
	if r.inner != nil {
		r.inner.Close()
		r.inner = nil
	}
	return nil
}

func isVideo(name string) bool {
	name = strings.ToLower(name)
	for _, ext := range []string{".mkv", ".mp4", ".avi", ".ts", ".m2ts", ".mov", ".wmv", ".m4v", ".webm", ".mpg", ".mpeg", ".vob"} {
		if strings.HasSuffix(name, ext) {
			return true
		}
	}
	return false
}
