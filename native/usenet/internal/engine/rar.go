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
var ErrEncryptedRAR = errors.New("encrypted RAR is not supported")
var errNotRAR = errors.New("not a supported RAR archive")

type rarBlock struct {
	name                                string
	data, packed, unpacked, next        int64
	file, directory, before, after, end bool
	volume                              int
	version                             int
	main, template                      bool
	mainLayout                          rarMainLayout
	packedWidth                         int
}

type rarMainLayout struct {
	size                   int64
	sizeWidth, volumeWidth int
}

type rarVolume struct {
	file     *File
	version  int
	start    int64
	template bool
	layout   rarMainLayout
}

func openRAR(ctx context.Context, f *File) (*rarVolume, error) {
	r := f.headerReader(ctx)
	defer r.Close()
	var sig [8]byte
	if err := readFullAt(r, sig[:7], 0); err != nil {
		return nil, err
	}
	v := &rarVolume{file: f, start: 7, version: 4}
	if bytes.Equal(sig[:7], []byte("Rar!\x1a\x07\x00")) {
		return v, nil
	}
	if bytes.Equal(sig[:7], []byte("Rar!\x1a\x07\x01")) {
		if err := readFullAt(r, sig[7:], 7); err != nil {
			return nil, err
		}
		if sig[7] == 0 {
			v.start = 8
			v.version = 5
			return v, nil
		}
	}
	return nil, errNotRAR
}

func (v *rarVolume) block(ctx context.Context, pos int64) (rarBlock, error) {
	r := v.file.headerReader(ctx)
	defer r.Close()
	if pos >= v.file.Size() {
		return rarBlock{end: true}, nil
	}
	if v.version == 4 {
		return rar4Block(r, pos)
	}
	return rar5Block(r, pos)
}

func rar4Block(r *FileReader, pos int64) (rarBlock, error) {
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
		return b, ErrEncryptedRAR
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
		if flags&4 != 0 {
			return b, ErrEncryptedRAR
		}
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
	}
	if b.packed < 0 || b.packed > r.f.Size()-b.data {
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

func rar5Block(r *FileReader, pos int64) (rarBlock, error) {
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
	if b.packed < 0 || b.packed > r.f.Size()-b.data {
		return b, errors.New("RAR5 data exceeds volume")
	}
	if kind == 4 {
		return b, ErrEncryptedRAR
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
				if typ == 1 {
					return b, ErrEncryptedRAR
				}
				if length > uint64(len(e.b)-start) || length == 0 {
					return b, errors.New("invalid RAR5 extra record")
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
	// many main headers as needed to find this volume; cache header mappings so
	// later seeks do not repeat discovery. RAR4 relies on release/NZB order.
	for c.scanned < len(c.files) {
		i := c.scanned
		v, e := openRAR(ctx, c.files[i])
		if errors.Is(e, errNotRAR) {
			c.scanned++
			continue
		}
		if e != nil {
			return nil, e
		}
		if v.version == 5 {
			b, e := v.block(ctx, v.start)
			if e != nil {
				return nil, e
			}
			i = b.volume
		} else {
			// Unknown non-archive NZB entries are not volume positions.
			i = len(c.resolved)
		}
		c.scanned++
		if i < 0 || i >= len(c.files) || c.resolved[i] != nil {
			return nil, errors.New("ambiguous obfuscated RAR volume order")
		}
		c.resolved[i] = v
		if i == c.index {
			return v, nil
		}
	}
	return nil, errors.New("missing obfuscated RAR volume")
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
	layoutNS     atomic.Int64
	layoutWaitNS atomic.Int64
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
		if b.packed <= 0 || b.packed > c.Size-start {
			return errors.New("invalid RAR continuation size")
		}
		if !b.after && start+b.packed != c.Size {
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
		}
		packed := b.packed - delta
		if packed <= 0 || packed >= c.Size-start ||
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
		last.packed <= 0 || last.packed != c.Size-start {
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
}

func (c *Content) Reader(ctx context.Context, ahead int) *ContentReader {
	return &ContentReader{content: c, ctx: ctx, ahead: ahead}
}

func (r *ContentReader) primeBoundary(e extent) {
	end := e.start + e.length
	if r.content.direct != nil || r.ahead <= 0 || r.boundary != nil || end >= r.content.Size {
		return
	}
	window := min(e.file.store.readAheadLimit(), int64(r.ahead)*max(int64(1), e.file.Size()/int64(len(e.file.segments))))
	if end-r.pos > window {
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
	e := extent{file: c.direct, length: c.Size}
	if c.direct == nil {
		var err error
		e, err = c.part(r.ctx, r.pos)
		if err != nil {
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
	n, err := r.current.ReadAt(p[:min(int64(len(p)), e.start+e.length-r.pos)], e.offset+r.pos-e.start)
	r.pos += int64(n)
	if err == nil {
		r.primeBoundary(e)
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
func (r *ContentReader) Close() error { r.cancelBoundary(); r.closeCurrent(); return nil }

func isVideo(name string) bool {
	name = strings.ToLower(name)
	for _, ext := range []string{".mkv", ".mp4", ".avi", ".ts", ".m2ts", ".mov", ".wmv", ".m4v", ".webm", ".mpg", ".mpeg", ".vob"} {
		if strings.HasSuffix(name, ext) {
			return true
		}
	}
	return false
}
