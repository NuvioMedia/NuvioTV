package rardecode

import (
	"bytes"
	"crypto/sha1"
	"errors"
	"hash/crc32"
	"io"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
)

const (
	blockArc     = 0x73
	blockFile    = 0x74
	blockComment = 0x75
	blockService = 0x7a
	blockEnd     = 0x7b

	blockHasData = 0x8000

	arcVolume    = 0x0001
	arcComment   = 0x0002
	arcSolid     = 0x0008
	arcNewNaming = 0x0010
	arcEncrypted = 0x0080

	fileSplitBefore = 0x0001
	fileSplitAfter  = 0x0002
	fileEncrypted   = 0x0004
	fileSolid       = 0x0010
	fileWindowMask  = 0x00e0
	fileLargeData   = 0x0100
	fileUnicode     = 0x0200
	fileSalt        = 0x0400
	fileVersion     = 0x0800
	fileExtTime     = 0x1000

	endArcNotLast = 0x0001

	saltSize    = 8
	cacheSize30 = 4
	hashRounds  = 0x40000
)

var (
	ErrUnsupportedDecoder = errors.New("rardecode: unsupported decoder version")
)

type blockHeader15 struct {
	htype    byte
	flags    uint16
	data     readBuf
	dataSize int64
}

type archive15 struct {
	multi     bool
	solid     bool
	encrypted bool
	oldNaming bool
	pass      []uint16
	keyCache  [cacheSize30]struct {
		salt []byte
		key  []byte
		iv   []byte
	}
}

func (a *archive15) useOldNaming() bool {
	return a.oldNaming
}

func calcAes30Params(pass []uint16, salt []byte) (key, iv []byte) {
	p := make([]byte, 0, len(pass)*2+len(salt))
	for _, v := range pass {
		p = append(p, byte(v), byte(v>>8))
	}
	p = append(p, salt...)

	hash := sha1.New()
	iv = make([]byte, 16)
	s := make([]byte, hash.Size())
	b := s[:3]
	for i := 0; i < hashRounds; i++ {

		_, _ = hash.Write(p)
		b[0], b[1], b[2] = byte(i), byte(i>>8), byte(i>>16)
		_, _ = hash.Write(b)
		if i%(hashRounds/16) == 0 {
			s = hash.Sum(s[:0])
			iv[i/(hashRounds/16)] = s[4*4+3]
		}
	}
	key = hash.Sum(s[:0])
	key = key[:16]

	for k := key; len(k) >= 4; k = k[4:] {
		k[0], k[1], k[2], k[3] = k[3], k[2], k[1], k[0]
	}
	return key, iv
}

func parseDosTime(t uint32) time.Time {
	n := int(t)
	sec := n & 0x1f << 1
	min := n >> 5 & 0x3f
	hr := n >> 11 & 0x1f
	day := n >> 16 & 0x1f
	mon := time.Month(n >> 21 & 0x0f)
	yr := n>>25&0x7f + 1980
	return time.Date(yr, mon, day, hr, min, sec, 0, time.Local)
}

func decodeName(buf []byte) string {
	i := bytes.IndexByte(buf, 0)
	if i < 0 {
		return string(buf)
	}

	name := buf[:i]
	encName := readBuf(buf[i+1:])
	if len(encName) < 2 {
		return ""
	}
	highByte := uint16(encName.byte()) << 8
	flags := encName.byte()
	flagBits := 8
	var wchars []uint16
	for len(wchars) < len(name) && len(encName) > 0 {
		if flagBits == 0 {
			flags = encName.byte()
			flagBits = 8
			if len(encName) == 0 {
				break
			}
		}
		switch flags >> 6 {
		case 0:
			wchars = append(wchars, uint16(encName.byte()))
		case 1:
			wchars = append(wchars, uint16(encName.byte())|highByte)
		case 2:
			if len(encName) < 2 {
				break
			}
			wchars = append(wchars, encName.uint16())
		case 3:
			n := encName.byte()
			b := name[len(wchars):]
			if l := int(n&0x7f) + 2; l < len(b) {
				b = b[:l]
			}
			if n&0x80 > 0 {
				if len(encName) < 1 {
					break
				}
				ec := encName.byte()
				for _, c := range b {
					wchars = append(wchars, uint16(c+ec)|highByte)
				}
			} else {
				for _, c := range b {
					wchars = append(wchars, uint16(c))
				}
			}
		}
		flags <<= 2
		flagBits -= 2
	}
	return string(utf16.Decode(wchars))
}

func readExtTimes(f *fileBlockHeader, b *readBuf) {
	if len(*b) < 2 {
		return
	}
	flags := b.uint16()

	ts := []*time.Time{&f.ModificationTime, &f.CreationTime, &f.AccessTime}

	for i, t := range ts {
		n := flags >> uint((3-i)*4)
		if n&0x8 == 0 {
			continue
		}
		if i != 0 {
			if len(*b) < 4 {
				return
			}
			*t = parseDosTime(b.uint32())
		}
		if n&0x4 > 0 {
			*t = t.Add(time.Second)
		}
		n &= 0x3
		if n == 0 {
			continue
		}
		if len(*b) < int(n) {
			return
		}

		d := time.Duration(0)
		for j := 3 - n; j < n; j++ {
			d |= time.Duration(b.byte()) << (j * 8)
		}
		d *= 100
		*t = t.Add(d)
	}
}

func (a *archive15) getKeys(salt []byte) (key, iv []byte) {

	for _, v := range a.keyCache {
		if bytes.Equal(v.salt[:], salt) {
			return v.key, v.iv
		}
	}
	key, iv = calcAes30Params(a.pass, salt)

	copy(a.keyCache[1:], a.keyCache[:])
	a.keyCache[0].salt = slices.Clone(salt)
	a.keyCache[0].key = key
	a.keyCache[0].iv = iv

	return key, iv
}

func (a *archive15) parseFileHeader(h *blockHeader15) (*fileBlockHeader, error) {
	f := new(fileBlockHeader)

	f.first = h.flags&fileSplitBefore == 0
	f.last = h.flags&fileSplitAfter == 0

	f.Solid = h.flags&fileSolid > 0
	f.arcSolid = a.solid
	f.Encrypted = h.flags&fileEncrypted > 0
	f.HeaderEncrypted = a.encrypted
	f.IsDir = h.flags&fileWindowMask == fileWindowMask
	if !f.IsDir {
		f.winSize = 0x10000 << ((h.flags & fileWindowMask) >> 5)
	}

	b := h.data
	if len(b) < 21 {
		return nil, ErrCorruptFileHeader
	}

	f.PackedSize = h.dataSize
	f.UnPackedSize = int64(b.uint32())
	f.HostOS = b.byte() + 1
	if f.HostOS > HostOSBeOS {
		f.HostOS = HostOSUnknown
	}
	f.sum = slices.Clone(b.bytes(4))

	f.ModificationTime = parseDosTime(b.uint32())
	unpackver := b.byte()
	method := b.byte() - 0x30
	namesize := int(b.uint16())
	f.Attributes = int64(b.uint32())
	if h.flags&fileLargeData > 0 {
		if len(b) < 8 {
			return nil, ErrCorruptFileHeader
		}
		_ = b.uint32()
		f.UnPackedSize |= int64(b.uint32()) << 32
		f.UnKnownSize = f.UnPackedSize == -1
	} else if int32(f.UnPackedSize) == -1 {
		f.UnKnownSize = true
		f.UnPackedSize = -1
	}
	if len(b) < namesize {
		return nil, ErrCorruptFileHeader
	}
	name := b.bytes(namesize)
	if h.flags&fileUnicode == 0 {
		f.Name = string(name)
	} else {
		f.Name = decodeName(name)
	}

	f.Name = strings.Replace(f.Name, "\\", "/", -1)

	if h.flags&fileVersion > 0 {

		i := strings.LastIndex(f.Name, ";")
		if i > 0 {
			j, err := strconv.Atoi(f.Name[i+1:])
			if err == nil && j >= 0 {
				f.Version = j
				f.Name = f.Name[:i]
			}
		}
	}

	var salt []byte
	if h.flags&fileSalt > 0 {
		if len(b) < saltSize {
			return nil, ErrCorruptFileHeader
		}
		salt = slices.Clone(b.bytes(saltSize))

		f.salt = salt
		f.kdfCount = hashRounds
	}
	if h.flags&fileExtTime > 0 {
		readExtTimes(f, &b)
	}

	if !f.first {
		return f, nil
	}

	if f.Encrypted && len(salt) == saltSize && a.pass != nil {
		f.key, f.iv = a.getKeys(salt)
	}
	f.hash = newLittleEndianCRC32
	if method != 0 {
		switch unpackver {
		case 15:
			return nil, ErrUnsupportedDecoder
		case 20, 26:
			f.decVer = decode20Ver
		case 29:
			f.decVer = decode29Ver
		default:
			return nil, ErrUnknownDecoder
		}
	}
	return f, nil
}

func (a *archive15) parseArcBlock(h *blockHeader15) error {
	a.encrypted = h.flags&arcEncrypted > 0
	a.multi = h.flags&arcVolume > 0
	a.oldNaming = h.flags&arcNewNaming == 0
	a.solid = h.flags&arcSolid > 0
	if a.encrypted && a.pass == nil {
		return ErrArchiveEncrypted
	}
	return nil
}

func (a *archive15) readBlockHeader(r byteReader) (*blockHeader15, error) {
	if a.encrypted {
		if a.pass == nil {
			return nil, ErrArchiveEncrypted
		}
		salt := make([]byte, saltSize)
		_, err := io.ReadFull(r, salt)
		if err != nil {
			return nil, err
		}
		key, iv := a.getKeys(salt)
		r, err = newAesDecryptReader(r, key, iv)
		if err != nil {
			return nil, err
		}
	}
	sizeBuf := make([]byte, 7)
	_, err := io.ReadFull(r, sizeBuf)
	if err != nil {
		if err == io.EOF && a.encrypted {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	b := readBuf(sizeBuf)
	crc := b.uint16()
	h := new(blockHeader15)
	h.htype = b.byte()
	h.flags = b.uint16()
	size := int(b.uint16())
	if h.htype == blockArc && h.flags&arcComment > 0 {

		if size < 13 {
			return nil, ErrCorruptBlockHeader
		}
		size = 13
	} else if size < 7 {
		return nil, ErrCorruptBlockHeader
	}

	h.data = make([]byte, size)
	copy(h.data, sizeBuf)
	_, err = io.ReadFull(r, h.data[7:])
	if err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	hash := crc32.NewIEEE()
	if h.htype == blockComment {
		if size < 13 {
			return nil, ErrCorruptBlockHeader
		}
		_, _ = hash.Write(h.data[2:13])
	} else {
		_, _ = hash.Write(h.data[2:])
	}
	if crc != uint16(hash.Sum32()) {
		return nil, ErrBadHeaderCRC
	}
	h.data = h.data[7:]
	if h.flags&blockHasData > 0 {
		if len(h.data) < 4 {
			return nil, ErrCorruptBlockHeader
		}
		h.dataSize = int64(h.data.uint32())
	}
	if (h.htype == blockService || h.htype == blockFile) && h.flags&fileLargeData > 0 {
		if len(h.data) < 25 {
			return nil, ErrCorruptBlockHeader
		}
		b := h.data[21:25]
		h.dataSize |= int64(b.uint32()) << 32
	}
	return h, nil
}

func (a *archive15) init(br *bufVolumeReader) (int, error) {
	a.encrypted = false
	h, err := a.readBlockHeader(br)
	if err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
	} else if h.htype != blockArc {
		err = ErrNoArchiveBlock
	} else {
		err = a.parseArcBlock(h)
	}
	return -1, err
}

func (a *archive15) nextBlock(br *bufVolumeReader) (*fileBlockHeader, error) {
	for {

		h, err := a.readBlockHeader(br)
		if err != nil {
			if err == io.EOF {
				return nil, errVolumeOrArchiveEnd
			}
			return nil, err
		}
		switch h.htype {
		case blockFile:
			return a.parseFileHeader(h)
		case blockEnd:
			if h.flags&endArcNotLast == 0 || !a.multi {
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

func newArchive15(password *string) *archive15 {
	a := &archive15{}
	if password != nil {
		a.pass = utf16.Encode([]rune(*password))
	}
	return a
}
