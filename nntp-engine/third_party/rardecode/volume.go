package rardecode

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

var (
	ErrVerMismatch      = errors.New("rardecode: volume version mistmatch")
	ErrArchiveNameEmpty = errors.New("rardecode: archive name empty")
	ErrFileNameRequired = errors.New("rardecode: filename required for multi volume archive")
	ErrInvalidHeaderOff = errors.New("rardecode: invalid filed header offset")

	defaultFS = osFS{}
)

const (
	DefaultMaxDictionarySize = 4 << 30
)

type osFS struct{}

func (fs osFS) Open(name string) (fs.File, error) {
	return os.Open(name)
}

type options struct {
	bsize                int
	maxDictSize          int64
	fs                   fs.FS
	pass                 *string
	skipCheck            bool
	skipVolumeCheck      bool
	openCheck            bool
	parallelRead         bool
	listTolerant         bool
	listFromAnyVolume    bool
	streamLazyVolumes    bool
	maxConcurrentVolumes int
}

type Option func(*options)

func BufferSize(size int) Option {
	return func(o *options) { o.bsize = size }
}

func MaxDictionarySize(size int64) Option {
	return func(o *options) { o.maxDictSize = size }
}

func FileSystem(fs fs.FS) Option {
	return func(o *options) { o.fs = fs }
}

func Password(pass string) Option {
	return func(o *options) { o.pass = &pass }
}

func SkipCheck(o *options) { o.skipCheck = true }

func SkipVolumeCheck(o *options) { o.skipVolumeCheck = true }

func OpenFSCheck(o *options) { o.openCheck = true }

func ParallelRead(enable bool) Option {
	return func(o *options) { o.parallelRead = enable }
}

// ListTolerant makes listing return a split file found in the available volume(s)
// instead of failing when continuation volumes are missing. Intended for listing
// a single first volume of a multi-volume set to discover file names/layout.
func ListTolerant(o *options) { o.listTolerant = true }

// ListFromAnyVolume makes listing accept a volume that begins part-way through
// a file. Every volume of a multi-volume set except the first opens on a
// continuation block (RAR5 "data not first", RAR3 "split before"), which
// listing otherwise rejects as ErrInvalidFileBlock — so a mid-set volume can
// only be enumerated on its own with this set. The leading entry reports just
// the data held by the mounted volume; its Name and UnPackedSize are still the
// whole file's, since a split file repeats both in every volume header.
// Intended for discovering which files a single arbitrary volume carries.
func ListFromAnyVolume(o *options) { o.listFromAnyVolume = true }

// StreamLazyVolumes defers opening continuation volumes until their packed data is
// read. Use when streaming a split file across many volumes so Next() does not
// have to scan every volume before the first Read.
func StreamLazyVolumes(o *options) { o.streamLazyVolumes = true }

func MaxConcurrentVolumes(n int) Option {
	return func(o *options) { o.maxConcurrentVolumes = n }
}

func getOptions(opts []Option) *options {
	opt := &options{
		fs:          defaultFS,
		maxDictSize: DefaultMaxDictionarySize,
	}
	for _, f := range opts {
		f(opt)
	}

	if opt.pass != nil {
		runes := []rune(*opt.pass)
		if len(runes) > maxPassword {
			pw := string(runes[:maxPassword])
			opt.pass = &pw
		}
	}
	return opt
}

type volume interface {
	byteReader
	writeToAtMost(w io.Writer, n int64) (int64, error)
	nextBlock() (*fileBlockHeader, error)
	nextBlockHeaderOnly() (*fileBlockHeader, error)
	openBlock(volnum int, offset, size int64) error
	canSeek() bool
}

type readerVolume struct {
	br  *bufVolumeReader
	n   int64
	num int
	ver int
	arc archiveBlockReader
	opt *options
}

func (v *readerVolume) init(r io.Reader, volnum int) error {
	var err error
	if v.br == nil {
		v.br, err = newBufVolumeReader(r, v.opt.bsize)
	} else {
		err = v.br.Reset(r)
	}
	if err != nil {
		return err
	}
	if v.arc == nil {
		switch v.br.ver {
		case archiveVersion15:
			v.arc = newArchive15(v.opt.pass)
		case archiveVersion50:
			v.arc = newArchive50(v.opt.pass)
		default:
			return ErrUnknownVersion
		}
		v.ver = v.br.ver
	} else if v.ver != v.br.ver {
		return ErrVerMismatch
	}
	n, err := v.arc.init(v.br)
	if err != nil {
		return err
	}
	v.num = volnum
	if n >= 0 && n != volnum && !v.opt.skipVolumeCheck {
		return ErrBadVolumeNumber
	}
	return nil
}

func (v *readerVolume) nextBlock() (*fileBlockHeader, error) {
	if v.n > 0 {
		err := v.br.Discard(v.n)
		if err != nil {
			return nil, err
		}
		v.n = 0
	}
	f, err := v.arc.nextBlock(v.br)
	if err != nil {
		return nil, err
	}
	f.volnum = v.num
	f.dataOff = v.br.off
	v.n = f.PackedSize
	return f, nil
}

func (v *readerVolume) nextBlockHeaderOnly() (*fileBlockHeader, error) {

	if v.n > 0 {
		err := v.br.Discard(v.n)
		if err != nil {
			return nil, err
		}
		v.n = 0
	}

	f, err := v.arc.nextBlock(v.br)
	if err != nil {
		return nil, err
	}
	f.volnum = v.num
	f.dataOff = v.br.off
	v.n = f.PackedSize
	return f, nil
}

func (v *readerVolume) Read(p []byte) (int, error) {
	if v.n == 0 {
		return 0, io.EOF
	}
	if v.n < int64(len(p)) {
		p = p[:v.n]
	}
	n, err := v.br.Read(p)
	v.n -= int64(n)
	if err == io.EOF && v.n > 0 {
		err = io.ErrUnexpectedEOF
	}
	return n, err
}

func (v *readerVolume) ReadByte() (byte, error) {
	if v.n == 0 {
		return 0, io.EOF
	}
	b, err := v.br.ReadByte()
	if err == nil {
		v.n--
	} else if err == io.EOF && v.n > 0 {
		err = io.ErrUnexpectedEOF
	}
	return b, err
}

func (v *readerVolume) writeToAtMost(w io.Writer, n int64) (int64, error) {
	if n == 0 {
		return 0, nil
	}
	if n > 0 {
		n = min(n, v.n)
	} else {
		n = v.n
	}
	l, err := v.br.writeToN(w, n)
	v.n -= l
	return l, err
}

func (v *readerVolume) canSeek() bool {
	return v.br.canSeek()
}

func (v *readerVolume) openBlock(volnum int, offset, size int64) error {
	if v.num != volnum && !v.opt.skipVolumeCheck {
		return ErrBadVolumeNumber
	}
	err := v.br.seek(offset)
	if err != nil {
		return err
	}
	v.n = size
	return nil
}

func newVolume(r io.Reader, opt *options, volnum int) (*readerVolume, error) {
	v := &readerVolume{opt: opt}
	err := v.init(r, volnum)
	if err != nil {
		return nil, err
	}
	return v, nil
}

type fileVolume struct {
	*readerVolume
	f  fs.File
	vm *volumeManager
}

func (v *fileVolume) Close() error { return v.f.Close() }

func (v *fileVolume) open(volnum int) error {
	err := v.Close()
	if err != nil {
		return err
	}
	f, err := v.vm.openVolumeFile(volnum)
	if err != nil {
		return err
	}
	err = v.readerVolume.init(f, volnum)
	if err != nil {
		f.Close()
		return err
	}
	v.f = f
	return nil
}

func (v *fileVolume) openBlock(volnum int, offset, size int64) error {
	if v.num != volnum {
		err := v.open(volnum)
		if err != nil {
			return err
		}
	}
	return v.readerVolume.openBlock(volnum, offset, size)
}

func (v *fileVolume) openNext() error { return v.open(v.num + 1) }

func (v *fileVolume) nextBlock() (*fileBlockHeader, error) {
	for {
		h, err := v.readerVolume.nextBlock()
		if err == nil {
			return h, nil
		}
		if err == ErrMultiVolume {
			err = v.openNext()
			if err != nil {
				return nil, err
			}
		} else if err == errVolumeOrArchiveEnd {
			err = v.openNext()
			if err != nil {

				if errors.Is(err, fs.ErrNotExist) {
					return nil, io.EOF
				}
				return nil, err
			}
		} else {
			return nil, err
		}
	}
}

func (v *fileVolume) nextBlockHeaderOnly() (*fileBlockHeader, error) {
	for {
		h, err := v.readerVolume.nextBlockHeaderOnly()
		if err == nil {
			return h, nil
		}
		if err == ErrMultiVolume {
			err = v.openNext()
			if err != nil {
				return nil, err
			}
		} else if err == errVolumeOrArchiveEnd {
			err = v.openNext()
			if err != nil {

				if errors.Is(err, fs.ErrNotExist) {
					return nil, io.EOF
				}
				return nil, err
			}
		} else {
			return nil, err
		}
	}
}

func nextNewVolName(file string) string {
	var inDigit bool
	var m []int
	for i, c := range file {
		if c >= '0' && c <= '9' {
			if !inDigit {
				m = append(m, i)
				inDigit = true
			}
		} else if inDigit {
			m = append(m, i)
			inDigit = false
		}
	}
	if inDigit {
		m = append(m, len(file))
	}
	if l := len(m); l >= 4 {

		m = m[l-4 : l]
		if strings.Contains(file[m[1]:m[2]], ".") || !strings.Contains(file[:m[0]], ".") {

			m = m[2:]
		}
	}

	lo, hi := m[0], m[1]
	n, err := strconv.Atoi(file[lo:hi])
	if err != nil {
		n = 0
	} else {
		n++
	}

	vol := fmt.Sprintf("%0"+fmt.Sprint(hi-lo)+"d", n)
	return file[:lo] + vol + file[hi:]
}

func nextOldVolName(file string) string {

	i := strings.LastIndex(file, ".")

	b := []byte(file[i+1:])

	if len(b) < 3 || b[1] < '0' || b[1] > '9' || b[2] < '0' || b[2] > '9' {
		return file[:i+2] + "00"
	}

	for j := 2; j >= 0; j-- {
		if b[j] != '9' {
			b[j]++
			break
		}

		if j == 0 {

			b[j] = 'A'
		} else {

			b[j] = '0'
		}
	}
	return file[:i+1] + string(b)
}

func hasDigits(s string) bool {
	for _, c := range s {
		if c >= '0' && c <= '9' {
			return true
		}
	}
	return false
}

func fixFileExtension(file string) string {

	i := strings.LastIndex(file, ".")
	if i < 0 {

		return file + ".rar"
	}
	ext := strings.ToLower(file[i+1:])

	if ext == "" || ext == "exe" || ext == "sfx" {
		file = file[:i+1] + "rar"
	}
	return file
}

type volumeManager struct {
	dir string
	opt *options

	mu    sync.Mutex
	files []string
	old   bool
}

func (vm *volumeManager) Files() []string {
	vm.mu.Lock()
	defer vm.mu.Unlock()
	return vm.files
}

func (vm *volumeManager) GetVolumePath(volnum int) string {
	vm.mu.Lock()
	defer vm.mu.Unlock()
	if volnum < len(vm.files) {
		return filepath.Join(vm.dir, vm.files[volnum])
	}
	return ""
}

func (vm *volumeManager) tryNewName(file string) (fs.File, error) {

	name := nextNewVolName(file)
	f, err := vm.opt.fs.Open(vm.dir + name)
	if !errors.Is(err, fs.ErrNotExist) {
		vm.files = append(vm.files, name)
		return f, err
	}

	name = nextOldVolName(file)
	f, oldErr := vm.opt.fs.Open(vm.dir + name)
	if !errors.Is(oldErr, fs.ErrNotExist) {
		vm.old = true
		vm.files = append(vm.files, name)
		return f, oldErr
	}
	return nil, err
}

func (vm *volumeManager) openVolumeFile(volnum int) (fs.File, error) {
	vm.mu.Lock()
	defer vm.mu.Unlock()

	var file string

	if volnum < len(vm.files) {
		return vm.opt.fs.Open(vm.dir + vm.files[volnum])
	}
	file = vm.files[len(vm.files)-1]
	if len(vm.files) == 1 {
		file = fixFileExtension(file)
		if !vm.old && hasDigits(file) {
			return vm.tryNewName(file)
		}
		vm.old = true
	}
	for len(vm.files) <= volnum {
		if vm.old {
			file = nextOldVolName(file)
		} else {
			file = nextNewVolName(file)
		}
		vm.files = append(vm.files, file)
	}
	return vm.opt.fs.Open(vm.dir + file)
}

func (vm *volumeManager) newVolume(volnum int) (*fileVolume, error) {
	f, err := vm.openVolumeFile(volnum)
	if err != nil {
		return nil, err
	}
	v, err := newVolume(f, vm.opt, volnum)
	if err != nil {
		f.Close()
		return nil, err
	}
	mv := &fileVolume{
		readerVolume: v,
		f:            f,
		vm:           vm,
	}
	return mv, nil
}

func (vm *volumeManager) openBlockOffset(h *fileBlockHeader, offset int64) (*fileVolume, error) {
	v, err := vm.newVolume(h.volnum)
	if err != nil {
		return nil, err
	}
	if h.dataOff < v.br.off {
		v.Close()
		return nil, ErrInvalidHeaderOff
	}
	err = v.br.Discard(h.dataOff - v.br.off + offset)
	v.n = h.PackedSize - offset
	if err != nil {
		v.Close()
		return nil, err
	}
	return v, nil
}

func (vm *volumeManager) openArchiveFile(blocks *fileBlockList) (fs.File, error) {
	h := blocks.firstBlock()
	if h.Solid {
		return nil, ErrSolidOpen
	}
	v, err := vm.openBlockOffset(h, 0)
	if err != nil {
		return nil, err
	}
	pr := newPackedFileReader(v, vm.opt)
	f, err := pr.newArchiveFile(blocks)
	if err != nil {
		v.Close()
		return nil, err
	}
	if sr, ok := f.(archiveFileSeeker); ok {
		return &fileSeekCloser{archiveFileSeeker: sr, Closer: v}, nil
	}
	return &fileCloser{archiveFile: f, Closer: v}, nil
}

func openVolume(filename string, opts *options) (*fileVolume, error) {
	dir, file := filepath.Split(filename)
	vm := &volumeManager{
		dir:   dir,
		files: []string{file},
		opt:   opts,
	}
	v, err := vm.newVolume(0)
	if err != nil {
		return nil, err
	}
	vm.old = v.arc.useOldNaming()
	return v, nil
}
