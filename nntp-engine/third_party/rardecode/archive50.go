package rardecode

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"errors"
	"hash"
	"hash/crc32"
	"io"
	"math/bits"
	"slices"
	"time"
)

const (
	block5Arc  = 1
	block5File = 2

	block5Encrypt = 4
	block5End     = 5

	block5HasExtra     = 0x0001
	block5HasData      = 0x0002
	block5DataNotFirst = 0x0008
	block5DataNotLast  = 0x0010

	endArc5NotLast = 0x0001

	enc5CheckPresent = 0x0001

	arc5MultiVol = 0x0001
	arc5VolNum   = 0x0002
	arc5Solid    = 0x0004

	file5IsDir          = 0x0001
	file5HasUnixMtime   = 0x0002
	file5HasCRC32       = 0x0004
	file5UnpSizeUnknown = 0x0008

	file5CompAlgorithm = 0x0000003F
	file5CompSolid     = 0x00000040
	file5CompMethod    = 0x00000380
	file5CompDictSize  = 0x00007C00
	file5CompDictFract = 0x000F8000
	file5CompV5Compat  = 0x00100000

	file5EncCheckPresent = 0x0001
	file5EncUseMac       = 0x0002

	file5ExtraTimeIsUnixTime = 0x01
	file5ExtraTimeHasMTime   = 0x02
	file5ExtraTimeHasCTime   = 0x04
	file5ExtraTimeHasATime   = 0x08
	file5ExtraTimeHasUnixNS  = 0x10

	cacheSize50   = 4
	maxPbkdf2Salt = 64
	pwCheckSize   = 8
	maxKdfCount   = 24

	maxDictSize = 0x1000000000
)

var (
	ErrBadPassword          = errors.New("rardecode: incorrect password")
	ErrCorruptEncryptData   = errors.New("rardecode: corrupt encryption data")
	ErrUnknownEncryptMethod = errors.New("rardecode: unknown encryption method")
	ErrPlatformIntSize      = errors.New("rardecode: platform integer size too small")
	ErrDictionaryTooLarge   = errors.New("rardecode: decode dictionary too large")
	ErrBadVolumeNumber      = errors.New("rardecode: bad volume number")
	ErrNoArchiveBlock       = errors.New("rardecode: missing archive block")
)

type extra struct {
	ftype uint64
	data  readBuf
}

type blockHeader50 struct {
	htype    uint64
	flags    uint64
	data     readBuf
	extra    []extra
	dataSize int64
}

type leHash32 struct {
	hash.Hash32
}

func (h leHash32) Sum(b []byte) []byte {
	s := h.Sum32()
	return append(b, byte(s), byte(s>>8), byte(s>>16), byte(s>>24))
}

func newLittleEndianCRC32() hash.Hash {
	return leHash32{crc32.NewIEEE()}
}

type archive50 struct {
	pass     []byte
	blockKey []byte
	multi    bool
	solid    bool
	keyCache [cacheSize50]struct {
		kdfCount int
		salt     []byte
		keys     [][]byte
	}
}

func (a *archive50) useOldNaming() bool {
	return false
}

func calcKeys50(pass, salt []byte, kdfCount int) [][]byte {
	if len(salt) > maxPbkdf2Salt {
		salt = salt[:maxPbkdf2Salt]
	}
	keys := make([][]byte, 3)
	if len(keys) == 0 {
		return keys
	}

	prf := hmac.New(sha256.New, pass)
	_, _ = prf.Write(salt)
	_, _ = prf.Write([]byte{0, 0, 0, 1})

	t := prf.Sum(nil)
	u := slices.Clone(t)

	kdfCount--

	for i, iter := range []int{kdfCount, 16, 16} {
		for iter > 0 {
			prf.Reset()
			_, _ = prf.Write(u)
			u = prf.Sum(u[:0])
			for j := range u {
				t[j] ^= u[j]
			}
			iter--
		}
		keys[i] = slices.Clone(t)
	}

	pwcheck := keys[2]
	for i, v := range pwcheck[pwCheckSize:] {
		pwcheck[i&(pwCheckSize-1)] ^= v
	}
	pwcheck = pwcheck[:pwCheckSize]

	sum := sha256.Sum256(pwcheck)
	pwcheck = append(pwcheck, sum[:4]...)
	keys[2] = pwcheck

	return keys
}

func (a *archive50) getKeys(kdfCount int, salt, check []byte) ([][]byte, error) {
	var keys [][]byte

	if kdfCount > maxKdfCount {
		return nil, ErrCorruptEncryptData
	}
	kdfCount = 1 << uint(kdfCount)

	for _, v := range a.keyCache {
		if kdfCount == v.kdfCount && bytes.Equal(salt, v.salt) {
			keys = v.keys
			break
		}
	}
	if keys == nil {

		keys = calcKeys50(a.pass, salt, kdfCount)

		copy(a.keyCache[1:], a.keyCache[:])
		a.keyCache[0].kdfCount = kdfCount
		a.keyCache[0].salt = slices.Clone(salt)
		a.keyCache[0].keys = keys
	}

	if check != nil && !bytes.Equal(check, keys[2]) {
		return nil, ErrBadPassword
	}
	return keys, nil
}

func (a *archive50) parseFileEncryptionRecord(b readBuf, f *fileBlockHeader) error {
	f.Encrypted = true
	if ver := b.uvarint(); ver != 0 {
		return ErrUnknownEncryptMethod
	}
	flags := b.uvarint()
	if len(b) < 33 {
		return ErrCorruptEncryptData
	}
	kdfCount := int(b.byte())
	salt := slices.Clone(b.bytes(16))
	f.iv = slices.Clone(b.bytes(16))

	f.salt = salt
	f.kdfCount = 1 << uint(kdfCount)

	var check []byte
	if flags&file5EncCheckPresent > 0 {
		if len(b) < 12 {
			return ErrCorruptEncryptData
		}
		check = slices.Clone(b.bytes(12))
	}
	useMac := flags&file5EncUseMac > 0

	if a.pass == nil || !(f.first || (f.last && useMac)) {
		return nil
	}
	keys, err := a.getKeys(kdfCount, salt, check)
	if err != nil {
		return err
	}
	f.key = keys[0]
	if useMac {
		f.hashKey = keys[1]
	}
	return nil
}

func readWinFiletime(b *readBuf) (time.Time, error) {
	if len(*b) < 8 {
		return time.Time{}, ErrCorruptFileHeader
	}

	t := b.uint64() - 116444736000000000
	t *= 100
	sec, nsec := bits.Div64(0, t, uint64(time.Second))
	return time.Unix(int64(sec), int64(nsec)), nil
}

func readUnixTime(b *readBuf) (time.Time, error) {
	if len(*b) < 4 {
		return time.Time{}, ErrCorruptFileHeader
	}
	return time.Unix(int64(b.uint32()), 0), nil
}

func readUnixNanoseconds(b *readBuf) (time.Duration, error) {
	if len(*b) < 4 {
		return 0, ErrCorruptFileHeader
	}
	d := time.Duration(b.uint32() & 0x3fffffff)
	if d >= time.Second {
		return 0, ErrCorruptFileHeader
	}
	return d, nil
}

func (a *archive50) parseFilePrecisionTimeRecord(b *readBuf, f *fileBlockHeader) error {
	var err error
	flags := b.uvarint()
	isUnixTime := flags&file5ExtraTimeIsUnixTime > 0
	if flags&file5ExtraTimeHasMTime > 0 {
		if isUnixTime {
			f.ModificationTime, err = readUnixTime(b)
		} else {
			f.ModificationTime, err = readWinFiletime(b)
		}
		if err != nil {
			return err
		}
	}
	if flags&file5ExtraTimeHasCTime > 0 {
		if isUnixTime {
			f.CreationTime, err = readUnixTime(b)
		} else {
			f.CreationTime, err = readWinFiletime(b)
		}
		if err != nil {
			return err
		}
	}
	if flags&file5ExtraTimeHasATime > 0 {
		if isUnixTime {
			f.AccessTime, err = readUnixTime(b)
		} else {
			f.AccessTime, err = readWinFiletime(b)
		}
		if err != nil {
			return err
		}
	}
	if isUnixTime && flags&file5ExtraTimeHasUnixNS > 0 {
		if flags&file5ExtraTimeHasMTime > 0 {
			ns, err := readUnixNanoseconds(b)
			if err != nil {
				return err
			}
			f.ModificationTime = f.ModificationTime.Add(ns)
		}
		if flags&file5ExtraTimeHasCTime > 0 {
			ns, err := readUnixNanoseconds(b)
			if err != nil {
				return err
			}
			f.CreationTime = f.CreationTime.Add(ns)
		}
		if flags&file5ExtraTimeHasATime > 0 {
			ns, err := readUnixNanoseconds(b)
			if err != nil {
				return err
			}
			f.AccessTime = f.AccessTime.Add(ns)
		}
	}
	return nil
}

func (a *archive50) parseFileHeader(h *blockHeader50) (*fileBlockHeader, error) {
	f := new(fileBlockHeader)

	f.HeaderEncrypted = a.blockKey != nil
	f.first = h.flags&block5DataNotFirst == 0
	f.last = h.flags&block5DataNotLast == 0

	flags := h.data.uvarint()
	f.IsDir = flags&file5IsDir > 0
	f.UnKnownSize = flags&file5UnpSizeUnknown > 0
	f.UnPackedSize = int64(h.data.uvarint())
	f.PackedSize = h.dataSize
	f.Attributes = int64(h.data.uvarint())
	if flags&file5HasUnixMtime > 0 {
		if len(h.data) < 4 {
			return nil, ErrCorruptFileHeader
		}
		f.ModificationTime = time.Unix(int64(h.data.uint32()), 0)
	}
	if flags&file5HasCRC32 > 0 {
		if len(h.data) < 4 {
			return nil, ErrCorruptFileHeader
		}
		f.sum = slices.Clone(h.data.bytes(4))
		if f.first {
			f.hash = newLittleEndianCRC32
		}
	}

	flags = h.data.uvarint()
	f.Solid = flags&file5CompSolid > 0
	f.arcSolid = a.solid
	method := (flags >> 7) & 7
	if f.first && method != 0 {
		unpackver := flags & file5CompAlgorithm
		switch unpackver {
		case 0:
			f.decVer = decode50Ver
			f.winSize = 0x20000 << ((flags >> 10) & 0x0F)
		case 1:
			if flags&file5CompV5Compat > 0 {
				f.decVer = decode50Ver
			} else {
				f.decVer = decode70Ver
			}
			f.winSize = 0x20000 << ((flags >> 10) & 0x1F)
			f.winSize += f.winSize / 32 * int64((flags>>15)&0x1F)
		default:
			return nil, ErrUnknownDecoder
		}
	}
	switch h.data.uvarint() {
	case 0:
		f.HostOS = HostOSWindows
	case 1:
		f.HostOS = HostOSUnix
	default:
		f.HostOS = HostOSUnknown
	}
	nlen := int(h.data.uvarint())
	if len(h.data) < nlen {
		return nil, ErrCorruptFileHeader
	}
	f.Name = string(h.data.bytes(nlen))

	for _, e := range h.extra {
		var err error
		switch e.ftype {
		case 1:
			if encErr := a.parseFileEncryptionRecord(e.data, f); encErr != nil {
				f.errs = append(f.errs, encErr)
			}
		case 2:

		case 3:
			err = a.parseFilePrecisionTimeRecord(&e.data, f)
		case 4:
			_ = e.data.uvarint()
			f.Version = int(e.data.uvarint())
		case 5:

		case 6:

		}
		if err != nil {
			return nil, err
		}
	}
	return f, nil
}

func (a *archive50) parseEncryptionBlock(b readBuf) error {
	if a.pass == nil {
		return ErrArchiveEncrypted
	}
	if ver := b.uvarint(); ver != 0 {
		return ErrUnknownEncryptMethod
	}
	flags := b.uvarint()
	if len(b) < 17 {
		return ErrCorruptEncryptData
	}
	kdfCount := int(b.byte())
	salt := b.bytes(16)

	var check []byte
	if flags&enc5CheckPresent > 0 {
		if len(b) < 12 {
			return ErrCorruptEncryptData
		}
		check = b.bytes(12)
	}

	keys, err := a.getKeys(kdfCount, salt, check)
	if err != nil {
		return err
	}
	a.blockKey = keys[0]
	return nil
}

func (a *archive50) parseArcBlock(h *blockHeader50) int {
	flags := h.data.uvarint()
	a.multi = flags&arc5MultiVol > 0
	a.solid = flags&arc5Solid > 0
	if flags&arc5VolNum > 0 {
		return int(h.data.uvarint())
	}
	return -1
}

func (a *archive50) readBlockHeader(r byteReader) (*blockHeader50, error) {
	if a.blockKey != nil {

		if a.pass == nil {
			return nil, ErrArchiveEncrypted
		}
		iv := make([]byte, 16)
		_, err := io.ReadFull(r, iv)
		if err != nil {
			return nil, err
		}
		r, err = newAesDecryptReader(r, a.blockKey, iv)
		if err != nil {
			return nil, err
		}
	}

	sizeBuf := make([]byte, 7)
	_, err := io.ReadFull(r, sizeBuf)
	if err != nil {
		return nil, err
	}
	b := readBuf(sizeBuf)
	crc := b.uint32()
	size := int(b.uvarint())

	if size < len(b) {
		return nil, ErrCorruptBlockHeader
	}

	const maxHeaderSize = 1 << 20
	if size > maxHeaderSize {
		return nil, ErrCorruptBlockHeader
	}

	buf := make([]byte, 3+size-len(b))
	copy(buf, sizeBuf[4:])
	_, err = io.ReadFull(r, buf[3:])
	if err != nil {
		return nil, err
	}

	hash := crc32.NewIEEE()
	_, _ = hash.Write(buf)
	if crc != hash.Sum32() {
		return nil, ErrBadHeaderCRC
	}

	b = buf[3-len(b):]
	h := new(blockHeader50)
	h.htype = b.uvarint()
	h.flags = b.uvarint()

	var extraSize int
	if h.flags&block5HasExtra > 0 {
		extraSize = int(b.uvarint())
	}
	if h.flags&block5HasData > 0 {
		h.dataSize = int64(b.uvarint())
	}
	if len(b) < extraSize {
		return nil, ErrCorruptBlockHeader
	}
	h.data = b.bytes(len(b) - extraSize)

	for len(b) > 0 {
		size = int(b.uvarint())
		if len(b) < size {
			return nil, ErrCorruptBlockHeader
		}
		data := readBuf(b.bytes(size))
		ftype := data.uvarint()
		h.extra = append(h.extra, extra{ftype, data})
	}

	return h, nil
}

func (a *archive50) mustReadBlockHeader(r byteReader) (*blockHeader50, error) {
	h, err := a.readBlockHeader(r)
	if err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	return h, nil
}

func (a *archive50) init(br *bufVolumeReader) (int, error) {
	a.blockKey = nil
	volnum := -1
	h, err := a.mustReadBlockHeader(br)
	if err != nil {
		return volnum, err
	}
	if h.htype == block5Encrypt {
		err = a.parseEncryptionBlock(h.data)
		if err != nil {
			return volnum, err
		}
		h, err = a.mustReadBlockHeader(br)
		if err != nil {
			return volnum, err
		}
	}
	if h.htype != block5Arc {
		return volnum, ErrNoArchiveBlock
	}
	volnum = a.parseArcBlock(h)
	return volnum, nil
}

func (a *archive50) nextBlock(br *bufVolumeReader) (*fileBlockHeader, error) {
	for {

		h, err := a.mustReadBlockHeader(br)
		if err != nil {
			return nil, err
		}
		switch h.htype {
		case block5File:
			return a.parseFileHeader(h)
		case block5End:
			flags := h.data.uvarint()
			if flags&endArc5NotLast == 0 || !a.multi {
				return nil, io.EOF
			}
			return nil, ErrMultiVolume
		default:
			if h.dataSize > 0 {
				err = br.Discard(h.dataSize)
				if err != nil {
					return nil, err
				}
			}
		}
	}
}

func newArchive50(password *string) *archive50 {
	a := &archive50{}
	if password != nil {
		a.pass = []byte(*password)
	}
	return a
}
