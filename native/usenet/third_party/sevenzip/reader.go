// Package sevenzip provides read access to 7-zip archives.
package sevenzip

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	iofs "io/fs"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/bodgit/plumbing"
	"github.com/javi11/sevenzip/internal/pool"
	"github.com/javi11/sevenzip/internal/util"
	"github.com/spf13/afero"
	"go4.org/readerutil"
)

var (
	errFormat          = errors.New("sevenzip: not a valid 7-zip file")
	errChecksum        = errors.New("sevenzip: checksum error")
	errTooMuch         = errors.New("sevenzip: too much data")
	errNegativeSize    = errors.New("sevenzip: size cannot be negative")
	errOneHeaderStream = errors.New("sevenzip: expected only one folder in header stream")
)

// ReadError is used to wrap read I/O errors.
type ReadError struct {
	// Encrypted is a hint that there is encryption involved.
	Encrypted bool
	Err       error
}

func (e ReadError) Error() string {
	return fmt.Sprintf("sevenzip: read error: %v", e.Err)
}

func (e ReadError) Unwrap() error {
	return e.Err
}

// A Reader serves content from a 7-Zip archive.
type Reader struct {
	ctx   context.Context
	r     io.ReaderAt
	start int64
	end   int64
	si    *streamsInfo
	p     string
	File  []*File
	pool  []pool.Pooler

	fileListOnce sync.Once
	fileList     []fileListEntry
}

// A ReadCloser is a [Reader] that must be closed when no longer needed.
type ReadCloser struct {
	f []afero.File
	Reader
}

// A File is a single file in a 7-Zip archive. The file information is in the
// embedded [FileHeader]. The file content can be accessed by calling
// [File.Open].
type File struct {
	FileHeader
	zip    *Reader
	folder int
	offset int64
}

type fileReader struct {
	rc util.SizeReadSeekCloser
	f  *File
	n  int64
}

func (fr *fileReader) Stat() (iofs.FileInfo, error) {
	return headerFileInfo{&fr.f.FileHeader}, nil
}

func (fr *fileReader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}

	if fr.n <= 0 {
		return 0, io.EOF
	}

	if int64(len(p)) > fr.n {
		p = p[0:fr.n]
	}

	n, err := fr.rc.Read(p)
	fr.n -= int64(n)

	if err != nil && !errors.Is(err, io.EOF) {
		e := &ReadError{
			Err: err,
		}

		if frc, ok := fr.rc.(*folderReadCloser); ok {
			e.Encrypted = frc.hasEncryption
		}

		return n, e
	}

	return n, err //nolint:wrapcheck
}

func (fr *fileReader) Close() error {
	if fr.rc == nil {
		return nil
	}

	offset, err := fr.rc.Seek(0, io.SeekCurrent)
	if err != nil {
		return fmt.Errorf("sevenzip: error seeking current position: %w", err)
	}

	if offset == fr.rc.Size() { // EOF reached
		if err := fr.rc.Close(); err != nil {
			return fmt.Errorf("sevenzip: error closing: %w", err)
		}
	} else {
		f := fr.f
		if _, err := f.zip.pool[f.folder].Put(offset, fr.rc); err != nil {
			return fmt.Errorf("sevenzip: error adding to pool: %w", err)
		}
	}

	fr.rc = nil

	return nil
}

// Open returns an [io.ReadCloser] that provides access to the [File]'s
// contents. Multiple files may be read concurrently.
func (f *File) Open() (io.ReadCloser, error) {
	if f.isEmptyStream || f.isEmptyFile {
		// Return empty reader for directory or empty file
		return io.NopCloser(bytes.NewReader(nil)), nil
	}

	rc, _ := f.zip.pool[f.folder].Get(f.offset)
	if rc == nil {
		var (
			encrypted bool
			err       error
		)

		rc, _, encrypted, err = f.zip.folderReader(f.zip.si, f.folder)
		if err != nil {
			return nil, &ReadError{
				Encrypted: encrypted,
				Err:       err,
			}
		}
	}

	if _, err := rc.Seek(f.offset, io.SeekStart); err != nil {
		e := &ReadError{
			Err: err,
		}

		if fr, ok := rc.(*folderReadCloser); ok {
			e.Encrypted = fr.hasEncryption
		}

		return nil, e
	}

	return &fileReader{
		rc: rc,
		f:  f,
		n:  int64(f.UncompressedSize), //nolint:gosec
	}, nil
}

func openReader(fs afero.Fs, name string) (io.ReaderAt, int64, []afero.File, error) {
	f, err := fs.Open(filepath.Clean(name))
	if err != nil {
		return nil, 0, nil, fmt.Errorf("sevenzip: error opening: %w", err)
	}

	info, err := f.Stat()
	if err != nil {
		err = errors.Join(err, f.Close())

		return nil, 0, nil, fmt.Errorf("sevenzip: error retrieving file info: %w", err)
	}

	var reader io.ReaderAt = f

	size := info.Size()
	files := []afero.File{f}

	if ext := filepath.Ext(name); ext == ".001" {
		sr := []readerutil.SizeReaderAt{io.NewSectionReader(f, 0, size)}

		for i := 2; true; i++ {
			f, err := fs.Open(fmt.Sprintf("%s.%03d", strings.TrimSuffix(name, ext), i))
			if err != nil {
				if errors.Is(err, iofs.ErrNotExist) {
					break
				}

				errs := make([]error, 0, len(files)+1)
				errs = append(errs, err)

				for _, file := range files {
					errs = append(errs, file.Close())
				}

				return nil, 0, nil, fmt.Errorf("sevenzip: error opening: %w", errors.Join(errs...))
			}

			files = append(files, f)

			info, err = f.Stat()
			if err != nil {
				errs := make([]error, 0, len(files)+1)
				errs = append(errs, err)

				for _, file := range files {
					errs = append(errs, file.Close())
				}

				return nil, 0, nil, fmt.Errorf("sevenzip: error retrieving file info: %w", errors.Join(errs...))
			}

			sr = append(sr, io.NewSectionReader(f, 0, info.Size()))
		}

		mr := readerutil.NewMultiReaderAt(sr...)
		reader, size = mr, mr.Size()
	}

	return reader, size, files, nil
}

// OpenReaderWithPassword will open the 7-zip file specified by name using
// password as the basis of the decryption key and return a [*ReadCloser]. If
// name has a ".001" suffix it is assumed there are multiple volumes and each
// sequential volume will be opened. An optional custom filesystem can be provided;
// if not specified, the default OS filesystem is used.
func OpenReaderWithPassword(name, password string, fs ...afero.Fs) (*ReadCloser, error) {
	// Use provided filesystem or default to OS filesystem
	filesystem := afero.NewOsFs()
	if len(fs) > 0 && fs[0] != nil {
		filesystem = fs[0]
	}

	reader, size, files, err := openReader(filesystem, name)
	if err != nil {
		return nil, err
	}

	r := new(ReadCloser)
	r.p = password

	if err := r.init(reader, size); err != nil {
		errs := make([]error, 0, len(files)+1)
		errs = append(errs, err)

		for _, file := range files {
			errs = append(errs, file.Close())
		}

		return nil, fmt.Errorf("sevenzip: error initialising: %w", errors.Join(errs...))
	}

	r.f = files

	return r, nil
}

// OpenReader will open the 7-zip file specified by name and return a
// [*ReadCloser]. If name has a ".001" suffix it is assumed there are multiple
// volumes and each sequential volume will be opened. An optional custom filesystem
// can be provided; if not specified, the default OS filesystem is used.
func OpenReader(name string, fs ...afero.Fs) (*ReadCloser, error) {
	return OpenReaderWithPassword(name, "", fs...)
}

// NewReaderWithPassword returns a new [*Reader] reading from r using password
// as the basis of the decryption key, which is assumed to have the given size
// in bytes.
func NewReaderWithPassword(r io.ReaderAt, size int64, password string) (*Reader, error) {
	return NewReaderWithContext(context.Background(), r, size, password)
}

// NewReaderWithContext supports cancellation of metadata I/O and encrypted
// header key derivation. Existing constructors retain upstream signatures.
func NewReaderWithContext(ctx context.Context, r io.ReaderAt, size int64, password string) (*Reader, error) {
	if size < 0 {
		return nil, errNegativeSize
	}

	zr := new(Reader)
	zr.p = password
	zr.ctx = ctx
	r = contextReaderAt{ctx: ctx, r: r}

	if err := zr.init(r, size); err != nil {
		return nil, err
	}

	return zr, nil
}

// NewReader returns a new [*Reader] reading from r, which is assumed to have
// the given size in bytes.
func NewReader(r io.ReaderAt, size int64) (*Reader, error) {
	return NewReaderWithPassword(r, size, "")
}

// Open opens the named file in the 7-zip archive, using the semantics of
// [fs.FS.Open]: paths are always slash separated, with no leading / or ../
// elements.
func (z *Reader) Open(name string) (iofs.File, error) {
	z.initFileList()

	if !iofs.ValidPath(name) {
		return nil, &iofs.PathError{Op: "open", Path: name, Err: iofs.ErrInvalid}
	}

	e := z.openLookup(name)
	if e == nil {
		return nil, &iofs.PathError{Op: "open", Path: name, Err: iofs.ErrNotExist}
	}

	if e.isDir {
		return &openDir{e, z.openReadDir(name), 0}, nil
	}

	rc, err := e.file.Open()
	if err != nil {
		return nil, err
	}

	return rc.(iofs.File), nil //nolint:forcetypeassert
}

func (z *Reader) folderReader(si *streamsInfo, f int) (*folderReadCloser, uint32, bool, error) {
	// Create a SectionReader covering all of the streams data
	ctx := z.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	return si.folderReaderWithContext(ctx, io.NewSectionReader(z.r, z.start, z.end-z.start), f, z.p)
}

const (
	chunkSize   = 4096
	searchLimit = 1 << 20 // 1 MiB
)

func findSignature(r io.ReaderAt, search []byte) ([]int64, error) {
	chunk := make([]byte, chunkSize+len(search))
	offsets := make([]int64, 0, 2)

	for offset := int64(0); offset < searchLimit; offset += chunkSize {
		n, err := r.ReadAt(chunk, offset)

		for i := 0; ; {
			idx := bytes.Index(chunk[i:n], search)
			if idx == -1 {
				break
			}

			offsets = append(offsets, offset+int64(i+idx))
			if offsets[0] == 0 {
				// If signature is at the beginning, return immediately, it's a regular archive
				return offsets, nil
			}

			i += idx + 1
		}

		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}

			return nil, fmt.Errorf("sevenzip: error reading chunk: %w", err)
		}
	}

	return offsets, nil
}

//nolint:cyclop,funlen,gocognit,gocyclo,maintidx
func (z *Reader) init(r io.ReaderAt, size int64) (err error) {
	h := crc32.NewIEEE()
	tra := plumbing.TeeReaderAt(r, h)

	var (
		signature = []byte{'7', 'z', 0xbc, 0xaf, 0x27, 0x1c}
		offsets   []int64
	)

	offsets, err = findSignature(r, signature)
	if err != nil {
		return err
	}

	if len(offsets) == 0 {
		return errFormat
	}

	var (
		sr    *io.SectionReader
		off   int64
		start startHeader
	)

	for _, off = range offsets {
		sr = io.NewSectionReader(tra, off, size-off) // Will only read first 32 bytes

		var sh signatureHeader
		if err = binary.Read(sr, binary.LittleEndian, &sh); err != nil {
			return fmt.Errorf("sevenzip: error reading signature header: %w", err)
		}

		z.r = r

		h.Reset()

		if err = binary.Read(sr, binary.LittleEndian, &start); err != nil {
			return fmt.Errorf("sevenzip: error reading start header: %w", err)
		}

		// CRC of the start header should match
		if util.CRC32Equal(h.Sum(nil), sh.CRC) {
			break
		}

		err = errChecksum
	}

	if err != nil {
		return err
	}

	// Work out where we are in the file (32, avoiding magic numbers)
	if z.start, err = sr.Seek(0, io.SeekCurrent); err != nil {
		return fmt.Errorf("sevenzip: error seeking current position: %w", err)
	}

	// Seek over the streams
	if z.end, err = sr.Seek(int64(start.Offset), io.SeekCurrent); err != nil { //nolint:gosec
		return fmt.Errorf("sevenzip: error seeking over streams: %w", err)
	}

	z.start += off
	z.end += off
	if start.Size > maxHeaderBytes || z.end < z.start || z.end > size || start.Size > uint64(size-z.end) {
		return ErrResourceLimit
	}

	h.Reset()

	// Bound bufio.Reader otherwise it can read trailing garbage which screws up the CRC check
	br := bufio.NewReader(io.NewSectionReader(tra, z.end, int64(start.Size))) //nolint:gosec

	var (
		id          byte
		header      *header
		streamsInfo *streamsInfo
	)

	if id, err = br.ReadByte(); err != nil {
		return fmt.Errorf("sevenzip: error reading header id: %w", err)
	}

	switch id {
	case idHeader:
		if header, err = readHeader(br); err != nil {
			return err
		}
	case idEncodedHeader:
		if streamsInfo, err = readStreamsInfo(br); err != nil {
			return err
		}
	default:
		return errUnexpectedID
	}

	// If there's more data to read, we've not parsed this correctly. This
	// won't break with trailing data as the bufio.Reader was bounded
	if n, _ := io.CopyN(io.Discard, br, 1); n != 0 {
		return errTooMuch
	}

	// CRC should match the one from the start header
	if !util.CRC32Equal(h.Sum(nil), start.CRC) {
		return errChecksum
	}

	// If the header was encoded we should have sufficient information now
	// to decode it
	if streamsInfo != nil {
		if streamsInfo.Folders() != 1 {
			return errOneHeaderStream
		}
		if streamsInfo.unpackInfo.folder[0].unpackSize() > maxHeaderBytes {
			return ErrResourceLimit
		}

		var (
			fr        *folderReadCloser
			crc       uint32
			encrypted bool
		)

		fr, crc, encrypted, err = z.folderReader(streamsInfo, 0)
		if err != nil {
			return &ReadError{
				Encrypted: encrypted,
				Err:       err,
			}
		}

		defer func() {
			err = errors.Join(err, fr.Close())
		}()

		if header, err = readEncodedHeader(util.ByteReadCloser(fr)); err != nil {
			return &ReadError{
				Encrypted: fr.hasEncryption,
				Err:       err,
			}
		}

		if crc != 0 && !util.CRC32Equal(fr.Checksum(), crc) {
			return errChecksum
		}
	}

	z.si = header.streamsInfo

	// spew.Dump(header)
	filesPerStream := make(map[int]int, z.si.Folders())

	if header.filesInfo != nil {
		folder, offset := 0, int64(0)
		z.File = make([]*File, 0, len(header.filesInfo.file))
		j := 0

		for _, fh := range header.filesInfo.file {
			if !fh.isEmptyStream && (z.si == nil || z.si.unpackInfo == nil || len(z.si.unpackInfo.folder) == 0) {
				return errUnexpectedID
			}
			f := new(File)
			f.zip = z
			f.FileHeader = fh

			if f.FileHeader.FileInfo().IsDir() && !strings.HasSuffix(f.Name, "/") {
				f.Name += "/"
			}

			if !fh.isEmptyStream && !fh.isEmptyFile {
				f.folder, _, _, err = header.streamsInfo.fileFolderAndSize(j)
				if err != nil {
					return err
				}

				// Make an exported copy of the folder index
				f.Stream = f.folder

				filesPerStream[f.folder]++

				if f.folder != folder {
					offset = 0
				}

				f.offset = offset
				offset += int64(f.UncompressedSize) //nolint:gosec
				folder = f.folder
				j++
			}

			z.File = append(z.File, f)
		}
	}

	// spew.Dump(filesPerStream)

	z.pool = make([]pool.Pooler, z.si.Folders())
	for i := range z.pool {
		var newPool pool.Constructor = pool.NewNoopPool

		if filesPerStream[i] > 1 {
			newPool = pool.NewPool
		}

		if z.pool[i], err = newPool(); err != nil {
			return err
		}
	}

	return nil
}

// Volumes returns the list of volumes that have been opened as part of the
// current archive.
func (rc *ReadCloser) Volumes() []string {
	volumes := make([]string, len(rc.f))
	for idx, f := range rc.f {
		volumes[idx] = f.Name()
	}

	return volumes
}

// Close closes the 7-zip file or volumes, rendering them unusable for I/O.
func (rc *ReadCloser) Close() error {
	errs := make([]error, 0, len(rc.f))

	for _, f := range rc.f {
		errs = append(errs, f.Close())
	}

	err := errors.Join(errs...)
	if err != nil {
		err = fmt.Errorf("sevenzip: error closing: %w", err)
	}

	return err
}

type fileListEntry struct {
	name  string
	file  *File
	isDir bool
	isDup bool
}

type fileInfoDirEntry interface {
	iofs.FileInfo
	iofs.DirEntry
}

func (e *fileListEntry) Name() string {
	_, elem := split(e.name)

	return elem
}

func (e *fileListEntry) Size() int64         { return 0 }
func (e *fileListEntry) Mode() iofs.FileMode { return iofs.ModeDir | 0o555 }
func (e *fileListEntry) Type() iofs.FileMode { return iofs.ModeDir }
func (e *fileListEntry) IsDir() bool         { return true }
func (e *fileListEntry) Sys() interface{}    { return nil }

func (e *fileListEntry) ModTime() time.Time {
	if e.file == nil {
		return time.Time{}
	}

	return e.file.Modified.UTC()
}

func (e *fileListEntry) Info() (iofs.FileInfo, error) { return e, nil }

func (e *fileListEntry) stat() (fileInfoDirEntry, error) {
	if e.isDup {
		return nil, errors.New(e.name + ": duplicate entries in 7-zip file") //nolint:err113
	}

	if !e.isDir {
		return headerFileInfo{&e.file.FileHeader}, nil
	}

	return e, nil
}

func toValidName(name string) string {
	name = strings.ReplaceAll(name, `\`, `/`)

	p := strings.TrimPrefix(path.Clean(name), "/")

	for strings.HasPrefix(p, "../") {
		p = p[len("../"):]
	}

	return p
}

//nolint:cyclop,funlen
func (z *Reader) initFileList() {
	z.fileListOnce.Do(func() {
		files := make(map[string]int)
		knownDirs := make(map[string]int)

		dirs := make(map[string]struct{})

		for _, file := range z.File {
			isDir := len(file.Name) > 0 && file.Name[len(file.Name)-1] == '/'

			name := toValidName(file.Name)
			if name == "" {
				continue
			}

			if idx, ok := files[name]; ok {
				z.fileList[idx].isDup = true

				continue
			}

			if idx, ok := knownDirs[name]; ok {
				z.fileList[idx].isDup = true

				continue
			}

			for dir := path.Dir(name); dir != "."; dir = path.Dir(dir) {
				dirs[dir] = struct{}{}
			}

			idx := len(z.fileList)
			entry := fileListEntry{
				name:  name,
				file:  file,
				isDir: isDir,
			}
			z.fileList = append(z.fileList, entry)

			if isDir {
				knownDirs[name] = idx
			} else {
				files[name] = idx
			}
		}

		for dir := range dirs {
			if _, ok := knownDirs[dir]; !ok {
				if idx, ok := files[dir]; ok {
					z.fileList[idx].isDup = true
				} else {
					entry := fileListEntry{
						name:  dir,
						file:  nil,
						isDir: true,
					}
					z.fileList = append(z.fileList, entry)
				}
			}
		}

		sort.Slice(z.fileList, func(i, j int) bool { return fileEntryLess(z.fileList[i].name, z.fileList[j].name) })
	})
}

func fileEntryLess(x, y string) bool {
	xdir, xelem := split(x)
	ydir, yelem := split(y)

	return xdir < ydir || xdir == ydir && xelem < yelem
}

func split(name string) (dir, elem string) {
	if len(name) > 0 && name[len(name)-1] == '/' {
		name = name[:len(name)-1]
	}

	i := len(name) - 1
	for i >= 0 && name[i] != '/' {
		i--
	}

	if i < 0 {
		return ".", name
	}

	return name[:i], name[i+1:]
}

//nolint:gochecknoglobals
var dotFile = &fileListEntry{name: "./", isDir: true}

func (z *Reader) openLookup(name string) *fileListEntry {
	if name == "." {
		return dotFile
	}

	dir, elem := split(name)

	files := z.fileList
	i := sort.Search(len(files), func(i int) bool {
		idir, ielem := split(files[i].name)

		return idir > dir || idir == dir && ielem >= elem
	})

	if i < len(files) {
		fname := files[i].name
		if fname == name || len(fname) == len(name)+1 && fname[len(name)] == '/' && fname[:len(name)] == name {
			return &files[i]
		}
	}

	return nil
}

func (z *Reader) openReadDir(dir string) []fileListEntry {
	files := z.fileList

	i := sort.Search(len(files), func(i int) bool {
		idir, _ := split(files[i].name)

		return idir >= dir
	})

	j := sort.Search(len(files), func(j int) bool {
		jdir, _ := split(files[j].name)

		return jdir > dir
	})

	return files[i:j]
}

type openDir struct {
	e      *fileListEntry
	files  []fileListEntry
	offset int
}

func (d *openDir) Close() error                 { return nil }
func (d *openDir) Stat() (iofs.FileInfo, error) { return d.e.stat() }

var errIsDirectory = errors.New("is a directory")

func (d *openDir) Read([]byte) (int, error) {
	return 0, &iofs.PathError{Op: "read", Path: d.e.name, Err: errIsDirectory}
}

func (d *openDir) ReadDir(count int) ([]iofs.DirEntry, error) {
	n := len(d.files) - d.offset
	if count > 0 && n > count {
		n = count
	}

	if n == 0 {
		if count <= 0 {
			return nil, nil
		}

		return nil, io.EOF
	}

	list := make([]iofs.DirEntry, n)
	for i := range list {
		s, err := d.files[d.offset+i].stat()
		if err != nil {
			return nil, err
		}

		list[i] = s
	}

	d.offset += n

	return list, nil
}

// extractAESParams extracts AES encryption parameters from a folder's coders.
// Returns the salt, IV, and number of KDF iterations, plus a boolean indicating if AES was found.
// The 7-zip AES format stores these in the coder properties:
//
//	Byte 0: 0xC0 | (cycles & 0x3F) | (saltSize-derived) | (ivSize-derived)
//	Byte 1: Additional size information
//	Bytes 2+: Salt bytes followed by IV bytes
func extractAESParams(folder *folder) (salt []byte, iv []byte, iterations int, hasAES bool) {
	if folder == nil {
		return nil, nil, 0, false
	}

	for _, coder := range folder.coder {
		// Check for AES-256 coder ID: 0x06 0xF1 0x07 0x01
		if len(coder.id) == 4 &&
			coder.id[0] == 0x06 && coder.id[1] == 0xf1 &&
			coder.id[2] == 0x07 && coder.id[3] == 0x01 {

			// Need at least 2 bytes for AES properties header
			if len(coder.properties) < 2 {
				continue
			}

			// Parse AES property format from 7-zip
			// Byte 0: bits 0-5 = cycles, bit 6 = IV flag, bit 7 = salt flag
			// Byte 1: bits 0-3 = IV size, bits 4-7 = salt size
			saltSize := int(coder.properties[0]>>7&1 + coder.properties[1]>>4)
			ivSize := int(coder.properties[0]>>6&1 + coder.properties[1]&0x0f)
			cycles := int(coder.properties[0] & 0x3f)

			// Verify we have enough bytes for salt and IV
			if len(coder.properties) < 2+saltSize+ivSize {
				continue
			}

			// Extract salt (if present)
			if saltSize > 0 {
				salt = make([]byte, saltSize)
				copy(salt, coder.properties[2:2+saltSize])
			}

			// Extract IV and pad to AES block size (16 bytes)
			iv = make([]byte, 16) // AES block size
			if ivSize > 0 {
				copy(iv, coder.properties[2+saltSize:2+saltSize+ivSize])
			}

			// Calculate iterations: 2^cycles
			if cycles == 0x3f {
				// Special case: 0x3f means no hashing, direct key
				iterations = 0
			} else {
				iterations = 1 << uint(cycles)
			}

			hasAES = true
			return
		}
	}

	return nil, nil, 0, false
}

// ListFilesWithOffsets returns information about files in the archive including their
// absolute offsets and sizes. All files are included in the result, with flags indicating
// whether they are compressed or encrypted.
//
// The Offset field represents the absolute byte position from the start of the archive file
// where the file's data begins. For uncompressed, non-encrypted files, this offset can be
// used for direct random access reads without decompression overhead.
//
// For compressed or encrypted files, the offset is still provided but direct extraction
// is not possible - use the standard File.Open() method instead.
//
// For encrypted files, AES encryption parameters (salt, IV, KDF iterations) are populated
// to enable external streaming and seeking. See FileInfo documentation for usage details.
func (z *Reader) ListFilesWithOffsets() ([]FileInfo, error) {
	if z.si == nil {
		return nil, errors.New("sevenzip: no streams info available")
	}

	var result []FileInfo

	// Track which folders use compression or encryption
	folderCompressed := make(map[int]bool)
	folderEncrypted := make(map[int]bool)
	folderAESParams := make(map[int]struct {
		salt       []byte
		iv         []byte
		iterations int
	})

	// Check each folder for compression and encryption
	if z.si.unpackInfo != nil {
		for folderIdx, folder := range z.si.unpackInfo.folder {
			// Extract AES parameters if encrypted
			salt, iv, iterations, hasAES := extractAESParams(folder)
			if hasAES {
				folderEncrypted[folderIdx] = true
				folderAESParams[folderIdx] = struct {
					salt       []byte
					iv         []byte
					iterations int
				}{salt, iv, iterations}
			}

			// Check for compression (non-AES coders)
			for _, coder := range folder.coder {
				// Check if this is the copy (no compression) method
				if len(coder.id) == 1 && coder.id[0] == 0x00 {
					// This is copy/store method - no compression
					continue
				}

				// Skip AES coder (already handled above)
				if len(coder.id) == 4 &&
					coder.id[0] == 0x06 && coder.id[1] == 0xf1 &&
					coder.id[2] == 0x07 && coder.id[3] == 0x01 {
					continue
				}

				// Any other coder means compression
				folderCompressed[folderIdx] = true
			}
		}
	}

	// Get volume path for multi-volume support
	volumePath := ""
	if rc, ok := z.r.(*io.SectionReader); ok {
		// Single file archive - try to get path from ReadCloser
		_ = rc // Path not directly available from SectionReader
	}
	// Try to get from ReadCloser wrapper if available
	type volumeGetter interface {
		Volumes() []string
	}
	if vg, ok := interface{}(z).(volumeGetter); ok {
		volumes := vg.Volumes()
		if len(volumes) > 0 {
			volumePath = volumes[0]
		}
	}

	// Process each file
	for _, file := range z.File {
		// Skip empty files and directories
		if file.FileHeader.isEmptyStream || file.FileHeader.isEmptyFile {
			continue
		}

		if file.FileHeader.FileInfo().IsDir() {
			continue
		}

		// Check if the file's folder is compressed or encrypted
		isCompressed := folderCompressed[file.folder]
		isEncrypted := folderEncrypted[file.folder]

		// Calculate absolute offset for the file
		// This is z.start (where packed data begins in the file) plus the folder's offset
		// in the packed stream plus the file's offset within the folder
		var absoluteOffset int64
		var packedSize uint64
		if z.si.packInfo != nil {
			folderOffset := z.si.folderOffset(file.folder)
			absoluteOffset = z.start + folderOffset + file.offset

			// Calculate packed size for this folder
			// Sum up all packed stream sizes for this folder
			if z.si.unpackInfo != nil && file.folder < len(z.si.unpackInfo.folder) {
				folder := z.si.unpackInfo.folder[file.folder]
				packedOffset := 0
				for i := 0; i < file.folder; i++ {
					packedOffset += len(z.si.unpackInfo.folder[i].packed)
				}
				for i := 0; i < len(folder.packed); i++ {
					if packedOffset+i < len(z.si.packInfo.size) {
						packedSize += z.si.packInfo.size[packedOffset+i]
					}
				}
			}
		}

		info := FileInfo{
			Name:        file.FileHeader.Name,
			Offset:      absoluteOffset,
			Size:        file.FileHeader.UncompressedSize,
			Compressed:  isCompressed,
			Encrypted:   isEncrypted,
			FolderIndex: file.folder,
			PackedSize:  packedSize,
			VolumePath:  volumePath,
		}

		// Add AES parameters if encrypted
		if isEncrypted {
			if params, ok := folderAESParams[file.folder]; ok {
				info.AESSalt = params.salt
				info.AESIV = params.iv
				info.KDFIterations = params.iterations
			}
		}

		result = append(result, info)
	}

	return result, nil
}
