package rardecode

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"errors"
	"hash"
	"io"
	"io/fs"
	"math"
	"sync"
	"time"
)

const (
	HostOSUnknown = 0
	HostOSMSDOS   = 1
	HostOSOS2     = 2
	HostOSWindows = 3
	HostOSUnix    = 4
	HostOSMacOS   = 5
	HostOSBeOS    = 6
)

const (
	maxPassword = int(128)
)

var (
	ErrShortFile        = errors.New("rardecode: decoded file too short")
	ErrInvalidFileBlock = errors.New("rardecode: invalid file block")
	ErrUnexpectedArcEnd = errors.New("rardecode: unexpected end of archive")
	ErrBadFileChecksum  = errors.New("rardecode: bad file checksum")
	ErrSolidOpen        = errors.New("rardecode: solid files don't support Open")
	ErrUnknownVersion   = errors.New("rardecode: unknown archive version")
)

type FileHeader struct {
	Name             string
	IsDir            bool
	Solid            bool
	Stored           bool
	Encrypted        bool
	HeaderEncrypted  bool
	HostOS           byte
	Attributes       int64
	PackedSize       int64
	UnPackedSize     int64
	UnKnownSize      bool
	ModificationTime time.Time
	CreationTime     time.Time
	AccessTime       time.Time
	Version          int
}

func (f *FileHeader) Mode() fs.FileMode {
	var m fs.FileMode

	if f.IsDir {
		m = fs.ModeDir
	}
	if f.HostOS == HostOSWindows {
		if f.IsDir {
			m |= 0777
		} else if f.Attributes&1 > 0 {
			m |= 0444
		} else {
			m |= 0666
		}
		return m
	}

	m |= fs.FileMode(f.Attributes) & fs.ModePerm

	if f.HostOS != HostOSUnix {
		return m
	}

	if f.Attributes&0x200 != 0 {
		m |= fs.ModeSticky
	}
	if f.Attributes&0x400 != 0 {
		m |= fs.ModeSetgid
	}
	if f.Attributes&0x800 != 0 {
		m |= fs.ModeSetuid
	}

	if f.Attributes&0xF000 == 0xA000 {
		m |= fs.ModeSymlink
	}
	return m
}

type byteReader interface {
	io.Reader
	io.ByteReader
}

type archiveFile interface {
	byteReader
	io.WriterTo
	writeToN(w io.Writer, n int64) (int64, error)
	currFile() *fileBlockHeader
	nextFile() (*fileBlockList, error)
	newArchiveFile(blocks *fileBlockList) (archiveFile, error)
	Stat() (fs.FileInfo, error)
}

type archiveFileSeeker interface {
	archiveFile
	io.Seeker
}

type fileCloser struct {
	archiveFile
	io.Closer
}

type fileSeekCloser struct {
	archiveFileSeeker
	io.Closer
}

type errorFile struct {
	archiveFile
	err error
}

func (ef *errorFile) ReadByte() (byte, error)                      { return 0, ef.err }
func (ef *errorFile) Read(p []byte) (int, error)                   { return 0, ef.err }
func (ef *errorFile) WriteTo(w io.Writer) (int64, error)           { return 0, ef.err }
func (ef *errorFile) writeToN(w io.Writer, n int64) (int64, error) { return 0, ef.err }

type fileBlockList struct {
	mu     sync.RWMutex
	blocks []*fileBlockHeader
}

func (fl *fileBlockList) firstBlock() *fileBlockHeader {
	fl.mu.RLock()
	defer fl.mu.RUnlock()
	return fl.blocks[0]
}

func (fl *fileBlockList) lastBlock() *fileBlockHeader {
	fl.mu.RLock()
	defer fl.mu.RUnlock()
	return fl.blocks[len(fl.blocks)-1]
}

func (fl *fileBlockList) findBlock(offset int64) *fileBlockHeader {
	fl.mu.RLock()
	defer fl.mu.RUnlock()
	for _, h := range fl.blocks {
		if offset < h.PackedSize || (offset == h.PackedSize && h.last) {
			return h
		}
		offset -= h.PackedSize
	}
	return nil
}

func (fl *fileBlockList) addBlock(h *fileBlockHeader) {
	fl.mu.Lock()
	defer fl.mu.Unlock()
	if len(fl.blocks) == h.blocknum {
		fl.blocks = append(fl.blocks, h)
	}
}

func (fl *fileBlockList) isDir() bool {
	fl.mu.RLock()
	defer fl.mu.RUnlock()
	return fl.blocks[0].IsDir
}

func (fl *fileBlockList) hasFileHash() bool {
	fl.mu.RLock()
	defer fl.mu.RUnlock()
	return fl.blocks[0].hash != nil
}

func (fl *fileBlockList) removeFileHash() {
	fl.mu.Lock()
	defer fl.mu.Unlock()
	h := *fl.blocks[0]
	h.hash = nil
	fl.blocks[0] = &h
}

func newFileBlockList(blocks ...*fileBlockHeader) *fileBlockList {
	return &fileBlockList{blocks: blocks}
}

type packedFileReader struct {
	v          volume
	h          *fileBlockHeader
	dr         *decodeReader
	offset     int64
	blocks     *fileBlockList
	opt        *options
	peekedNext *fileBlockHeader
	exhausted  bool
}

// isListEndOfData reports whether err means the current (available) volume data ended.
// Used in tolerant listing to return a split file discovered in the mounted volume(s).
//
// ErrBadHeaderCRC / ErrCorruptBlockHeader are tolerated here as well: when only the
// first volume of a multi-volume set is mounted, a file whose data block continues
// into a sibling volume can leave a trailing region that rardecode tries to parse as
// the next block header. Without the continuation volume those bytes are not a valid
// header and fail the CRC check. Since the file entry we care about has already been
// recorded (guarded by len(blocks) > 0 at the call sites), treat this as the end of
// the available data rather than aborting discovery.
func isListEndOfData(err error) bool {
	return err == io.EOF ||
		err == errVolumeOrArchiveEnd ||
		err == io.ErrUnexpectedEOF ||
		err == ErrUnexpectedArcEnd ||
		err == ErrBadHeaderCRC ||
		err == ErrCorruptBlockHeader ||
		errors.Is(err, fs.ErrNotExist)
}

func (f *packedFileReader) init(blocks *fileBlockList) error {
	h := blocks.firstBlock()
	f.h = h
	f.blocks = blocks
	f.offset = 0
	return nil
}

func (f *packedFileReader) Stat() (fs.FileInfo, error) {
	if f.h == nil {
		return nil, fs.ErrInvalid
	}
	return fileInfo{h: f.h}, nil
}

func (f *packedFileReader) nextBlock() error {
	if f.h == nil {
		return io.EOF
	}
	if f.h.last {
		return io.EOF
	}
	h, err := f.v.nextBlock()
	if err != nil {
		if err == io.EOF {

			return ErrUnexpectedArcEnd
		} else if err == errVolumeOrArchiveEnd {
			return ErrMultiVolume
		}
		return err
	}
	if h.first || h.Name != f.h.Name {
		return ErrInvalidFileBlock
	}
	h.packedOff = f.h.packedOff + f.h.PackedSize
	h.blocknum = f.h.blocknum + 1
	f.h = h
	f.offset = h.dataOff
	f.blocks.addBlock(h)
	return nil
}

func (f *packedFileReader) nextFile() (*fileBlockList, error) {
	if f.exhausted {
		return nil, io.EOF
	}

	var err error
	for err == nil {
		err = f.nextBlock()
	}
	if err != io.EOF {
		if f.opt != nil && f.opt.listTolerant && f.blocks != nil && len(f.blocks.blocks) > 0 && isListEndOfData(err) {
			f.exhausted = true
			return f.blocks, nil
		}
		return nil, err
	}

	var h *fileBlockHeader

	if f.peekedNext != nil {
		h = f.peekedNext
		f.peekedNext = nil
	} else {
		h, err = f.v.nextBlock()
		if err != nil {
			if err == errVolumeOrArchiveEnd {
				err = io.EOF
			}
			return nil, err
		}
	}

	if !h.first {
		// Opening a mid-set volume on its own lands on a continuation block.
		// Under listFromAnyVolume that is the expected entry point, so accept
		// it — but only as the very first entry: a continuation block appearing
		// after a file has already been listed still means a malformed archive.
		if f.opt == nil || !f.opt.listFromAnyVolume || f.blocks != nil {
			return nil, ErrInvalidFileBlock
		}
	}
	blocks := newFileBlockList(h)
	err = f.init(blocks)
	if err != nil {
		return nil, err
	}

	for !f.h.last {
		if f.opt != nil && f.opt.streamLazyVolumes {
			break
		}
		nextH, err := f.v.nextBlock()
		if err != nil {
			if f.opt != nil && f.opt.listTolerant && len(blocks.blocks) > 0 && isListEndOfData(err) {
				f.exhausted = true
				return blocks, nil
			}
			if err == io.EOF || err == errVolumeOrArchiveEnd {

				return nil, ErrUnexpectedArcEnd
			}
			return nil, err
		}

		if nextH.first || nextH.Name != f.h.Name {

			f.peekedNext = nextH
			break
		}

		nextH.packedOff = f.h.packedOff + f.h.PackedSize
		nextH.blocknum = f.h.blocknum + 1
		f.h = nextH
		f.offset = nextH.dataOff
		blocks.addBlock(nextH)
	}

	return blocks, nil
}

func (f *packedFileReader) currFile() *fileBlockHeader { return f.h }

func (f *packedFileReader) Read(p []byte) (int, error) {
	for {
		n, err := f.v.Read(p)
		if err == io.EOF {
			err = f.nextBlock()
		}
		if n > 0 || err != nil {
			f.offset += int64(n)
			return n, err
		}
	}
}

func (f *packedFileReader) ReadByte() (byte, error) {
	for {
		b, err := f.v.ReadByte()
		if err == nil {
			f.offset++
			return b, nil
		}
		if err == io.EOF {
			err = f.nextBlock()
			if err == nil {
				continue
			}
		}
		return b, err
	}
}

func (f *packedFileReader) writeToN(w io.Writer, n int64) (int64, error) {
	if n == 0 {
		return 0, nil
	}
	var err error
	var tot int64
	todo := n
	for todo != 0 && err == nil {
		var l int64
		l, err = f.v.writeToAtMost(w, todo)
		if todo > 0 {
			todo -= l
		}
		tot += int64(l)
		if err == nil && todo != 0 {
			err = f.nextBlock()
		}
	}
	if todo <= 0 && err == io.EOF {
		err = nil
	}
	return tot, err
}

func (f *packedFileReader) WriteTo(w io.Writer) (int64, error) {
	return f.writeToN(w, -1)
}

func (pr *packedFileReader) newArchiveFileFrom(r archiveFile, blocks *fileBlockList) (archiveFile, error) {
	h := blocks.firstBlock()
	err := pr.init(blocks)
	if err != nil {
		return nil, err
	}
	if len(h.errs) > 0 {
		if len(h.errs) == 1 {
			err = h.errs[0]
		} else {
			err = errors.Join(h.errs...)
		}
		return &errorFile{archiveFile: r, err: err}, nil
	}
	if h.Encrypted {
		if h.key == nil {
			return &errorFile{archiveFile: r, err: ErrArchivedFileEncrypted}, nil
		}
		r, err = newAesDecryptFileReader(r, h.key, h.iv)
		if err != nil {
			return nil, err
		}
	}

	if h.decVer > 0 {
		if pr.dr == nil {
			pr.dr = new(decodeReader)
		}

		if !h.UnKnownSize && h.winSize > h.UnPackedSize {
			h.winSize = h.UnPackedSize
		}
		if h.winSize > maxDictSize || h.winSize > pr.opt.maxDictSize {
			return nil, ErrDictionaryTooLarge
		}
		if h.winSize > math.MaxInt {
			return nil, ErrPlatformIntSize
		}
		err := pr.dr.init(r, h.decVer, int(h.winSize), !h.Solid, h.arcSolid, h.UnPackedSize)
		if err != nil {
			return nil, err
		}
		r = pr.dr
	}
	if h.UnPackedSize >= 0 && !h.UnKnownSize {

		r = newLimitedReader(r, h.UnPackedSize)
	}
	if h.hash != nil && !pr.opt.skipCheck {
		r = newChecksumReader(r, h.hash(), blocks.removeFileHash)
	}
	return r, nil
}

func (pr *packedFileReader) newArchiveFile(blocks *fileBlockList) (archiveFile, error) {
	return pr.newArchiveFileFrom(pr, blocks)
}

type packedFileReadSeeker struct {
	packedFileReader
}

func (f *packedFileReadSeeker) openBlock(h *fileBlockHeader, offset int64) (int64, error) {
	err := f.v.openBlock(h.volnum, h.dataOff+offset, h.PackedSize-offset)
	if err != nil {
		return 0, err
	}
	f.h = h
	f.offset = h.packedOff + offset
	return f.offset, nil
}

func (f *packedFileReadSeeker) openNextBlock(h *fileBlockHeader) error {
	_, err := f.openBlock(h, h.PackedSize)
	if err != nil {
		return err
	}
	return f.nextBlock()
}

func (f *packedFileReadSeeker) packedSize() (int64, error) {
	h := f.blocks.lastBlock()
	if h.last {
		return h.packedOff + h.PackedSize, nil
	}
	err := f.openNextBlock(h)
	for err == nil {
		err = f.nextBlock()
	}
	if err != io.EOF {
		return 0, err
	}
	if !f.h.last {
		return 0, ErrInvalidFileBlock
	}
	return f.h.packedOff + f.h.PackedSize, nil
}

func (f *packedFileReadSeeker) Seek(offset int64, whence int) (int64, error) {

	switch whence {
	case io.SeekStart:
	case io.SeekCurrent:
		offset += f.offset
	case io.SeekEnd:
		size, err := f.packedSize()
		if err != nil {
			return 0, err
		}
		offset += size
	default:
		return 0, fs.ErrInvalid
	}
	if offset < 0 {
		return 0, fs.ErrInvalid
	}

	h := f.blocks.findBlock(offset)
	if h != nil {
		offset -= h.packedOff
		return f.openBlock(h, offset)
	}
	h = f.blocks.lastBlock()
	if h.last {
		return 0, fs.ErrInvalid
	}
	err := f.openNextBlock(h)
	for err == nil {
		off := offset - h.packedOff
		if off < f.h.PackedSize || (off == f.h.PackedSize && f.h.last) {
			return f.openBlock(f.h, off)
		} else if h.last {
			return 0, fs.ErrInvalid
		}
		err = f.nextBlock()
	}
	if err == io.EOF {
		return 0, fs.ErrInvalid
	}
	return 0, err
}

func (pr *packedFileReadSeeker) newArchiveFile(blocks *fileBlockList) (archiveFile, error) {
	return pr.newArchiveFileFrom(pr, blocks)
}

func newPackedFileReader(v volume, opts *options) archiveFile {
	pr := packedFileReader{v: v, opt: opts}
	if v.canSeek() {
		return &packedFileReadSeeker{pr}
	}
	return &pr
}

type limitedReader struct {
	archiveFile
	size   int64
	offset int64
}

func (l *limitedReader) Read(p []byte) (int, error) {
	diff := l.size - l.offset
	if diff <= 0 {
		return 0, io.EOF
	}
	if int64(len(p)) > diff {
		p = p[0:diff]
	}
	n, err := l.archiveFile.Read(p)
	l.offset += int64(n)
	if err == io.EOF {
		if l.offset < l.size {
			return n, ErrShortFile
		}
		return n, nil
	}
	return n, err
}

func (l *limitedReader) ReadByte() (byte, error) {
	if l.offset >= l.size {
		return 0, io.EOF
	}
	b, err := l.archiveFile.ReadByte()
	if err != nil {
		if err == io.EOF {
			return 0, ErrShortFile
		}
		return 0, err
	}
	l.offset++
	return b, nil
}

func (l *limitedReader) writeToN(w io.Writer, n int64) (int64, error) {
	diff := l.size - l.offset
	m, err := l.archiveFile.writeToN(w, min(diff, n))
	l.offset += m
	if m < n && err == nil {
		err = io.EOF
	}
	return m, err
}

func (l *limitedReader) WriteTo(w io.Writer) (int64, error) {
	diff := l.size - l.offset
	n, err := l.archiveFile.writeToN(w, diff)
	l.offset += n
	return n, err
}

type limitedReadSeeker struct {
	limitedReader
	sr io.Seeker
}

func (l *limitedReadSeeker) Seek(offset int64, whence int) (int64, error) {

	switch whence {
	case io.SeekStart:
	case io.SeekCurrent:
		offset += l.offset
	case io.SeekEnd:
		offset += l.size
	default:
		return 0, fs.ErrInvalid
	}
	if offset < 0 || offset > l.size {
		return 0, fs.ErrInvalid
	}
	return l.sr.Seek(offset, io.SeekStart)
}

func newLimitedReader(f archiveFile, size int64) archiveFile {
	lr := limitedReader{archiveFile: f, size: size}
	if sr, ok := f.(io.Seeker); ok {
		return &limitedReadSeeker{limitedReader: lr, sr: sr}
	}
	return &lr
}

type checksumReader struct {
	archiveFile
	hash    hash.Hash
	success func()
	eofErr  error
}

func (cr *checksumReader) eofError() error {
	if cr.eofErr != nil {
		return cr.eofErr
	}

	h := cr.currFile()
	sum := cr.hash.Sum(nil)
	if len(h.hashKey) > 0 {
		mac := hmac.New(sha256.New, h.hashKey)
		_, _ = mac.Write(sum)
		sum = mac.Sum(sum[:0])
		if len(h.sum) == 4 {

			for i, v := range sum[4:] {
				sum[i&3] ^= v
			}
			sum = sum[:4]
		}
	}
	if !bytes.Equal(sum, h.sum) {
		cr.eofErr = ErrBadFileChecksum
	} else {
		cr.eofErr = io.EOF
		if cr.success != nil {
			cr.success()
		}
	}
	return cr.eofErr
}

func (cr *checksumReader) Read(p []byte) (int, error) {
	n, err := cr.archiveFile.Read(p)
	if n > 0 {
		if n, err = cr.hash.Write(p[:n]); err != nil {
			return n, err
		}
	}
	if err != io.EOF {
		return n, err
	}
	return n, cr.eofError()
}

func (cr *checksumReader) ReadByte() (byte, error) {
	b, err := cr.archiveFile.ReadByte()
	if err != nil {
		if err != io.EOF {
			return 0, err
		}
		return 0, cr.eofError()
	}
	_, err = cr.hash.Write([]byte{b})
	if err != nil {
		return 0, err
	}
	return b, err
}

func (cr *checksumReader) writeToN(w io.Writer, n int64) (int64, error) {
	panic("checksumReader.writeToN should never be called")
}

func (cr *checksumReader) WriteTo(w io.Writer) (int64, error) {
	mw := io.MultiWriter(w, cr.hash)
	n, err := cr.archiveFile.WriteTo(mw)
	if err == nil || err == io.EOF {
		err = cr.eofError()
	}
	if err != nil && err != io.EOF {
		return n, err
	}
	return n, nil
}

func newChecksumReader(f archiveFile, h hash.Hash, success func()) *checksumReader {
	return &checksumReader{archiveFile: f, hash: h, success: success}
}

type Reader struct {
	f archiveFile
}

func (r *Reader) Read(p []byte) (int, error)         { return r.f.Read(p) }
func (r *Reader) ReadByte() (byte, error)            { return r.f.ReadByte() }
func (r *Reader) WriteTo(w io.Writer) (int64, error) { return r.f.WriteTo(w) }

func (r *Reader) Next() (*FileHeader, error) {
	blocks, err := r.f.nextFile()
	if err != nil {
		return nil, err
	}
	r.f, err = r.f.newArchiveFile(blocks)
	if err != nil {
		return nil, err
	}
	h := blocks.firstBlock()
	h.FileHeader.Stored = h.decVer == 0
	return &h.FileHeader, nil
}

func newReader(v volume, opts *options) Reader {
	pr := newPackedFileReader(v, opts)
	return Reader{f: pr}
}

func NewReader(r io.Reader, opts ...Option) (*Reader, error) {
	options := getOptions(opts)
	v, err := newVolume(r, options, 0)
	if err != nil {
		return nil, err
	}
	rdr := newReader(v, options)
	return &rdr, nil
}

type ReadCloser struct {
	Reader
	cl io.Closer
	vm *volumeManager
}

func (rc *ReadCloser) Close() error { return rc.cl.Close() }

func (rc *ReadCloser) Volumes() []string {
	return rc.vm.Files()
}

func OpenReader(name string, opts ...Option) (*ReadCloser, error) {
	options := getOptions(opts)
	v, err := openVolume(name, options)
	if err != nil {
		return nil, err
	}
	rc := &ReadCloser{vm: v.vm, cl: v}
	rc.Reader = newReader(v, options)
	return rc, nil
}

type File struct {
	FileHeader
	blocks *fileBlockList
	vm     *volumeManager
}

func (f *File) Open() (io.ReadCloser, error) {
	return f.vm.openArchiveFile(f.blocks)
}

func List(name string, opts ...Option) ([]*File, error) {
	vm, fileBlocks, err := listFileBlocks(name, opts)
	if err != nil {
		return nil, err
	}
	var fl []*File
	for _, blocks := range fileBlocks {
		h := blocks.firstBlock()
		h.FileHeader.Stored = h.decVer == 0
		f := &File{
			FileHeader: h.FileHeader,
			blocks:     blocks,
			vm:         vm,
		}
		fl = append(fl, f)
	}
	return fl, nil
}
