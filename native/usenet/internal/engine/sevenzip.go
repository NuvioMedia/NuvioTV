package engine

// Stored ("copy") 7z archives, optionally AES-256 encrypted, are mapped onto
// NZB volumes like stored RAR data: there is no decompression path. Only the
// archive headers (which may themselves be LZMA-compressed and encrypted) are
// parsed with javi11/sevenzip, the library StreamNZB uses for the same
// releases. Payload bytes keep flowing through the article store; an encrypted
// file is decrypted per Read with AES-CBC, so Range requests stay random access.

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"unicode/utf16"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/rarname"
	"github.com/javi11/sevenzip"
)

var (
	ErrCompressed7z      = errors.New("compressed 7z is not supported; use a Stored/uncompressed release")
	Err7zPasswordMissing = errors.New("encrypted 7z needs a password, but the NZB has none")
	Err7zWrongPassword   = errors.New("the NZB password does not open this 7z archive")
	err7zVolumeLayout    = errors.New("7z volume sizes are not uniform")
)

var sevenZipMagic = []byte{'7', 'z', 0xbc, 0xaf, 0x27, 0x1c}

func is7zHead(head []byte) bool { return bytes.HasPrefix(head, sevenZipMagic) }

// sevenZipVolume places one NZB file in the concatenated archive. Middle
// volume sizes are predicted from their neighbours; verified records whether
// the volume's own yEnc size has confirmed that prediction.
type sevenZipVolume struct {
	file        *File
	start, size int64
	verified    bool
}

func exactSize(f *File) (int64, bool) {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.size, f.exact
}

// sevenZipLayout needs exact decoded sizes: NZB wire sizes are estimates. `7z -v`
// writes equal volumes plus a shorter last one, so the first two and last two
// volumes are probed and the rest are predicted, then checked as they are read.
// A disagreement between the probes falls back to probing every volume.
func sevenZipLayout(ctx context.Context, files []*File, probeAll bool) ([]sevenZipVolume, error) {
	n := len(files)
	probe := func(indexes []int) error {
		var wg sync.WaitGroup
		var mu sync.Mutex
		var first error
		limit := make(chan struct{}, 8)
		for _, i := range indexes {
			if _, ok := exactSize(files[i]); ok {
				continue
			}
			wg.Add(1)
			limit <- struct{}{}
			go func(f *File) {
				defer wg.Done()
				defer func() { <-limit }()
				if _, err := sniff(ctx, f); err != nil {
					mu.Lock()
					if first == nil {
						first = err
					}
					mu.Unlock()
				}
			}(files[i])
		}
		wg.Wait()
		return first
	}
	anchors := []int{0}
	if n > 1 {
		anchors = append(anchors, n-1)
	}
	if n > 2 {
		anchors = append(anchors, 1)
	}
	if n > 3 {
		anchors = append(anchors, n-2)
	}
	if err := probe(anchors); err != nil {
		return nil, err
	}
	middle, _ := exactSize(files[min(1, n-1)])
	uniform := true
	if n > 3 {
		last, _ := exactSize(files[n-2])
		uniform = last == middle
	}
	if !uniform || probeAll {
		all := make([]int, n)
		for i := range all {
			all[i] = i
		}
		if err := probe(all); err != nil {
			return nil, err
		}
	}
	vols := make([]sevenZipVolume, n)
	start := int64(0)
	for i, f := range files {
		size, ok := exactSize(f)
		if !ok {
			size = middle
		}
		if size <= 0 {
			return nil, err7zVolumeLayout
		}
		vols[i] = sevenZipVolume{file: f, start: start, size: size, verified: ok}
		start += size
	}
	return vols, nil
}

// sevenZipArchive is the io.ReaderAt the header parser sees. It only touches
// the few articles holding the signature header and the trailing header block.
type sevenZipArchive struct {
	ctx     context.Context
	vols    []sevenZipVolume
	size    int64
	mu      sync.Mutex
	readers map[int]*FileReader
	layout  error
}

func newSevenZipArchive(ctx context.Context, vols []sevenZipVolume) *sevenZipArchive {
	last := vols[len(vols)-1]
	return &sevenZipArchive{ctx: ctx, vols: vols, size: last.start + last.size, readers: make(map[int]*FileReader)}
}

func (a *sevenZipArchive) ReadAt(p []byte, off int64) (int, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if off < 0 {
		return 0, errors.New("negative 7z offset")
	}
	n := 0
	for len(p) > 0 && off < a.size {
		i := sort.Search(len(a.vols), func(i int) bool { return a.vols[i].start+a.vols[i].size > off })
		v := &a.vols[i]
		r := a.readers[i]
		if r == nil {
			r = v.file.headerReader(a.ctx)
			a.readers[i] = r
		}
		chunk := p[:min(int64(len(p)), v.start+v.size-off)]
		k, err := r.ReadAt(chunk, off-v.start)
		if !v.verified {
			if size, ok := exactSize(v.file); ok {
				if size != v.size {
					a.layout = err7zVolumeLayout
					return n, a.layout
				}
				v.verified = true
			}
		}
		n += k
		off += int64(k)
		p = p[k:]
		if k < len(chunk) {
			if err == nil || err == io.EOF {
				err = io.ErrUnexpectedEOF
			}
			return n, err
		}
	}
	if len(p) > 0 {
		return n, io.EOF
	}
	return n, nil
}

func (a *sevenZipArchive) Close() {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, r := range a.readers {
		r.Close()
	}
	a.readers = nil
}

type sevenZipEntry struct {
	name        string
	info        sevenzip.FileInfo
	folderStart int64 // archive offset of the entry's folder (its first packed byte).
}

type sevenZipSet struct {
	vols     []sevenZipVolume
	password string
	entries  []sevenZipEntry
	keys     map[string]cipher.Block // 2^19 SHA-256 rounds per salt; derive once.
}

// open7zSet reads the archive directory. The volumes are ordered by the caller.
// A wrong size prediction before the trailing header misplaces it, which the
// parser reports as anything from EOF to an undecryptable header, so a failed
// open with predicted volumes is retried once with every volume measured.
func open7zSet(ctx context.Context, files []*File) (*sevenZipSet, error) {
	vols, err := sevenZipLayout(ctx, files, false)
	if err != nil {
		return nil, err
	}
	set, err := open7zLayout(ctx, files, vols)
	if err == nil || ctx.Err() != nil {
		return set, err
	}
	for _, v := range vols {
		if !v.verified {
			if vols, err = sevenZipLayout(ctx, files, true); err != nil {
				return nil, err
			}
			return open7zLayout(ctx, files, vols)
		}
	}
	return nil, err
}

func open7zLayout(ctx context.Context, files []*File, vols []sevenZipVolume) (*sevenZipSet, error) {
	var err error
	archive := newSevenZipArchive(ctx, vols)
	defer archive.Close()
	password := files[0].password
	r, err := sevenzip.NewReaderWithContext(ctx, archive, archive.size, password)
	if err != nil {
		switch {
		case ctx.Err() != nil:
			return nil, ctx.Err()
		case archive.layout != nil:
			return nil, archive.layout
		}
		if errors.Is(err, sevenzip.ErrResourceLimit) {
			return nil, fmt.Errorf("%w: %v", errInvalidArticle, err)
		}
		var readErr *sevenzip.ReadError
		if errors.As(err, &readErr) && readErr.Encrypted {
			if password == "" {
				return nil, Err7zPasswordMissing
			}
			return nil, Err7zWrongPassword
		}
		// Article reads surface their own (possibly permanent) failures.
		for e := errors.Unwrap(err); e != nil; e = errors.Unwrap(e) {
			if errors.Is(e, errInvalidArticle) || permanentStreamFailure(e) != "" {
				return nil, e
			}
		}
		return nil, fmt.Errorf("invalid 7z archive: %s", strings.TrimPrefix(err.Error(), "sevenzip: "))
	}
	infos, err := r.ListFilesWithOffsets()
	if err != nil {
		return nil, fmt.Errorf("invalid 7z archive: %s", strings.TrimPrefix(err.Error(), "sevenzip: "))
	}
	// Files of one folder are listed in folder order, so the first one starts
	// the folder. AES-CBC chains from the folder IV, not from each file.
	folderStart := map[int]int64{}
	set := &sevenZipSet{vols: vols, password: password, keys: make(map[string]cipher.Block)}
	for _, info := range infos {
		if _, seen := folderStart[info.FolderIndex]; !seen {
			folderStart[info.FolderIndex] = info.Offset
		}
		name := path.Base(strings.ReplaceAll(info.Name, "\\", "/"))
		set.entries = append(set.entries, sevenZipEntry{name: name, info: info, folderStart: folderStart[info.FolderIndex]})
	}
	return set, nil
}

// derive7zKey is 7-Zip's AES-256 key schedule: SHA-256 over 2^cycles rounds of
// salt || UTF-16LE(password) || little-endian round counter.
func derive7zKey(ctx context.Context, password string, salt []byte, iterations int) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	units := utf16.Encode([]rune(password))
	secret := append([]byte(nil), salt...)
	for _, u := range units {
		secret = binary.LittleEndian.AppendUint16(secret, u)
	}
	key := make([]byte, sha256.Size)
	if iterations == 0 {
		copy(key, secret)
		return key, nil
	}
	h := sha256.New()
	var counter [8]byte
	for i := uint64(0); i < uint64(iterations); i++ {
		if i&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		h.Write(secret)
		binary.LittleEndian.PutUint64(counter[:], i)
		h.Write(counter[:])
	}
	return h.Sum(key[:0]), nil
}

type cbcCipher struct {
	block   cipher.Block
	iv      []byte // folder IV: chains only into the folder's first block.
	fileRel int64  // selected file's offset inside its folder's plaintext.
}

// content maps one entry onto the volumes without any I/O. Content offsets are
// the entry's own plaintext offsets; Copy+AES keeps ciphertext byte positions
// identical, so an encrypted entry's parts start up to 31 bytes early, at the
// AES block that chains into its first byte, and end with its last AES block.
func (z *sevenZipSet) content(ctx context.Context, e sevenZipEntry, name string) (*Content, error) {
	info := e.info
	if info.Compressed {
		return nil, ErrCompressed7z
	}
	size := int64(info.Size)
	low, high := int64(0), size
	var crypt *cbcCipher
	if info.Encrypted {
		if z.password == "" {
			return nil, Err7zPasswordMissing
		}
		if len(info.AESIV) != aes.BlockSize || info.KDFIterations < 0 || info.KDFIterations > 1<<24 {
			return nil, fmt.Errorf("%w: unsupported 7z AES parameters", errInvalidArticle)
		}
		id := fmt.Sprintf("%x/%d", info.AESSalt, info.KDFIterations)
		block := z.keys[id]
		if block == nil {
			key, err := derive7zKey(ctx, z.password, info.AESSalt, info.KDFIterations)
			if err != nil {
				return nil, err
			}
			if block, err = aes.NewCipher(key); err != nil {
				return nil, err
			}
			z.keys[id] = block
		}
		rel := info.Offset - e.folderStart
		crypt = &cbcCipher{block: block, iv: info.AESIV, fileRel: rel}
		first := rel / aes.BlockSize * aes.BlockSize
		low = max(0, first-aes.BlockSize) - rel
		high = (rel+size+aes.BlockSize-1)/aes.BlockSize*aes.BlockSize - rel
		if info.PackedSize > 0 && rel+high > int64(info.PackedSize) {
			return nil, errors.New("invalid 7z AES stream size")
		}
	}
	last := z.vols[len(z.vols)-1]
	if size <= 0 || info.Offset+low < 0 || info.Offset+high > last.start+last.size {
		return nil, errors.New("invalid 7z entry size")
	}
	c := &Content{Name: name, Size: size, complete: true, aes: crypt}
	for _, v := range z.vols {
		from, to := max(info.Offset+low, v.start), min(info.Offset+high, v.start+v.size)
		if from >= to {
			continue
		}
		c.parts = append(c.parts, extent{v.file, from - v.start, to - from, from - info.Offset})
		if !v.verified {
			if c.pending == nil {
				c.pending = make(map[*File]int64)
			}
			c.pending[v.file] = v.size
		}
	}
	return c, nil
}

// A wrong password only shows up as garbage plaintext when the archive headers
// are not encrypted. Known containers start with a fixed signature.
func (c *Content) checkPassword(ctx context.Context) error {
	if c.aes == nil {
		return nil
	}
	ext := strings.ToLower(pathExtension(c.Name))
	if ext != ".mkv" && ext != ".webm" && ext != ".mp4" && ext != ".m4v" && ext != ".mov" && ext != ".avi" {
		return nil
	}
	r := c.Reader(ctx, 0)
	defer r.Close()
	head := make([]byte, min(16, c.Size))
	if _, err := io.ReadFull(r, head); err != nil {
		return err
	}
	if !bytes.HasPrefix(head, []byte{0x1a, 0x45, 0xdf, 0xa3}) && !(len(head) >= 8 && string(head[4:8]) == "ftyp") &&
		!bytes.HasPrefix(head, []byte("RIFF")) {
		if c.padded {
			return ErrRARWrongPassword
		}
		return Err7zWrongPassword
	}
	return nil
}

// verifyVolume confirms a predicted volume size before its extent is used.
// Called with layoutGate held.
func (c *Content) verifyVolume(ctx context.Context, off int64) error {
	c.mu.RLock()
	i := sort.Search(len(c.parts), func(i int) bool { return c.parts[i].start+c.parts[i].length > off })
	if i == len(c.parts) {
		c.mu.RUnlock()
		return nil
	}
	f := c.parts[i].file
	want, pending := c.pending[f]
	c.mu.RUnlock()
	if !pending {
		return nil
	}
	if _, ok := exactSize(f); !ok {
		if _, err := sniff(ctx, f); err != nil {
			return err
		}
	}
	if size, ok := exactSize(f); !ok || size != want {
		return err7zVolumeLayout
	}
	c.mu.Lock()
	delete(c.pending, f)
	c.mu.Unlock()
	return nil
}

// readDecrypted serves plaintext from the AES-CBC ciphertext extents. Each
// call reads the block before the first requested one as its IV.
func (r *ContentReader) readDecrypted(p []byte) (int, error) {
	c := r.content
	z := c.aes
	want := min(int64(len(p)), c.Size-r.pos, 1<<20)
	rel := z.fileRel + r.pos
	first := rel / aes.BlockSize * aes.BlockSize
	from := first
	if first > 0 {
		from -= aes.BlockSize
	}
	to := (rel + want + aes.BlockSize - 1) / aes.BlockSize * aes.BlockSize
	need := int(to - from)
	if cap(r.cipher) < need {
		r.cipher = make([]byte, need)
	}
	buf := r.cipher[:need]
	got := 0
	var err error
	for got < need && err == nil {
		var n int
		n, err = r.readRaw(buf[got:], from-z.fileRel+int64(got))
		got += n
		if n == 0 && err == nil {
			err = io.ErrNoProgress
		}
	}
	ivLen := int(first - from)
	blocks := (got - ivLen) / aes.BlockSize * aes.BlockSize
	if blocks <= 0 {
		if err == nil || err == io.EOF {
			err = errInvalidArticle
		}
		return 0, err
	}
	iv := z.iv
	if ivLen > 0 {
		iv = buf[:ivLen]
	}
	plain := buf[ivLen : ivLen+blocks]
	// CryptBlocks may run in place; the IV block before it is left untouched.
	cipher.NewCBCDecrypter(z.block, append([]byte(nil), iv...)).CryptBlocks(plain, plain)
	skip := rel - first
	n := min(int64(len(plain))-skip, want)
	if n <= 0 {
		if err == nil || err == io.EOF {
			err = errInvalidArticle
		}
		return 0, err
	}
	copy(p, plain[skip:skip+n])
	r.pos += n
	// A failure after a decrypted prefix is reported by the next Read.
	return int(n), nil
}

// select7z offers every entry of a 7z set to consider, in archive order and
// with archive-wide indexes, like RAR entries. An entry that cannot be
// streamed is still offered, so selectors see the real inventory, but carries
// the reason in unusable. A nil Content without error means no entry matched.
func select7z(ctx context.Context, vols []*File, s Selection, index *int, consider func(*Content, int, string) (bool, error)) (*Content, error) {
	set, err := open7zSet(ctx, vols)
	if err != nil {
		return nil, err
	}
	var largest *Content
	entries := make([]*Content, len(set.entries))
	for i, e := range set.entries {
		c, err := set.content(ctx, e, e.name)
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if err != nil {
			c = &Content{Name: e.name, Size: int64(e.info.Size), unusable: err}
		}
		entries[i] = c
		matched, err := consider(c, *index, "")
		*index++
		if err != nil {
			return nil, err
		}
		if matched {
			return c.usable(ctx)
		}
		if _, volume := rarname.SetKey(e.name); c.unusable == nil && !volume && !archiveName(e.name) &&
			(largest == nil || c.Size > largest.Size) {
			largest = c
		}
	}
	// Scene releases are re-wrapped whole: a stored RAR set inside the 7z.
	var nestedErr error
	for _, group := range nestedRARGroups(set.entries) {
		var groupErr error
		vols := make([]*Content, len(group))
		for i, j := range group {
			vols[i] = entries[j]
			if vols[i].unusable != nil && groupErr == nil {
				groupErr = vols[i].unusable
			}
		}
		if groupErr != nil {
			nestedErr = groupErr
			continue
		}
		c, err := selectNestedRAR(ctx, vols, index, consider)
		if err != nil {
			nestedErr = err
			continue
		}
		if c != nil {
			return c, nil
		}
	}
	// Obfuscated uploads can hide the inner name too. Without any selector the
	// largest stream is the video, as for an extensionless direct file.
	if s.FileIdx == nil && s.FileMustInclude == "" && s.Episode == 0 && largest != nil &&
		!isVideo(largest.Name) && !isSubtitle(strings.ToLower(largest.Name)) && largest.Size >= 64<<20 {
		largest.Name += ".mkv"
		return largest.usable(ctx)
	}
	return nil, nestedErr
}

func archiveName(name string) bool {
	name = strings.ToLower(name)
	for _, ext := range []string{".7z", ".zip", ".par2", ".sfv", ".nfo", ".srr"} {
		if strings.HasSuffix(name, ext) {
			return true
		}
	}
	return false
}

func (c *Content) usable(ctx context.Context) (*Content, error) {
	if c.unusable != nil {
		return nil, c.unusable
	}
	if c.prepare != nil {
		if err := c.prepare(ctx); err != nil {
			return nil, err
		}
		c.prepare = nil
	}
	if err := c.checkPassword(ctx); err != nil {
		return nil, err
	}
	return c, nil
}

var sevenZipVolumeName = regexp.MustCompile(`(?i)^(.*)\.7z\.(\d+)$`)

// obfuscated7zVolumes picks the volumes of a 7z set from obfuscated NZB files
// that follow its first volume. Posters obfuscate the PAR2 files too and
// interleave them, and their yEnc headers often keep the real volume names.
// Every candidate's first article is read (a few at a time): PAR2 files are
// dropped by signature, and when yEnc names number the volumes they decide
// membership and order. Otherwise release order stands.
func obfuscated7zVolumes(ctx context.Context, files []*File) ([]*File, error) {
	heads := make([][]byte, len(files))
	errs := make([]error, len(files))
	var wg sync.WaitGroup
	limit := make(chan struct{}, 8)
	for i, f := range files {
		wg.Add(1)
		limit <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-limit }()
			heads[i], errs[i] = sniff(ctx, f)
		}()
	}
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if errs[0] != nil {
		return nil, errs[0]
	}
	recovered := func(f *File) string {
		f.mu.RLock()
		defer f.mu.RUnlock()
		return f.recoveredName
	}
	stem := ""
	if m := sevenZipVolumeName.FindStringSubmatch(recovered(files[0])); m != nil {
		stem = strings.ToLower(m[1])
	}
	type volume struct {
		f *File
		n int
	}
	var vols []volume
	for i, f := range files {
		if bytes.HasPrefix(heads[i], []byte("PAR2\x00PKT")) {
			continue
		}
		if stem != "" {
			m := sevenZipVolumeName.FindStringSubmatch(recovered(f))
			if m == nil || strings.ToLower(m[1]) != stem {
				continue
			}
			n, _ := strconv.Atoi(m[2])
			vols = append(vols, volume{f, n})
			continue
		}
		if errs[i] != nil {
			return nil, errs[i]
		}
		vols = append(vols, volume{f, i})
	}
	sort.SliceStable(vols, func(i, j int) bool { return vols[i].n < vols[j].n })
	out := make([]*File, len(vols))
	for i, v := range vols {
		out[i] = v.f
	}
	return out, nil
}
