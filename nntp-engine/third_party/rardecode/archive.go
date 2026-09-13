package rardecode

import (
	"errors"
	"hash"
)

const (
	_ = iota
	decode20Ver
	decode29Ver
	decode50Ver
	decode70Ver

	archiveVersion15 = 0
	archiveVersion50 = 1
)

var (
	ErrCorruptBlockHeader    = errors.New("rardecode: corrupt block header")
	ErrCorruptFileHeader     = errors.New("rardecode: corrupt file header")
	ErrBadHeaderCRC          = errors.New("rardecode: bad header crc")
	ErrUnknownDecoder        = errors.New("rardecode: unknown decoder version")
	ErrDecoderOutOfData      = errors.New("rardecode: decoder expected more data than is in packed file")
	ErrArchiveEncrypted      = errors.New("rardecode: archive encrypted, password required")
	ErrArchivedFileEncrypted = errors.New("rardecode: archived files encrypted, password required")
	ErrMultiVolume           = errors.New("rardecode: multi-volume archive continues in next file")
	errVolumeOrArchiveEnd    = errors.New("rardecode: archive or volume end")
)

type readBuf []byte

func (b *readBuf) byte() byte {
	v := (*b)[0]
	*b = (*b)[1:]
	return v
}

func (b *readBuf) uint16() uint16 {
	v := uint16((*b)[0]) | uint16((*b)[1])<<8
	*b = (*b)[2:]
	return v
}

func (b *readBuf) uint32() uint32 {
	v := uint32((*b)[0]) | uint32((*b)[1])<<8 | uint32((*b)[2])<<16 | uint32((*b)[3])<<24
	*b = (*b)[4:]
	return v
}

func (b *readBuf) uint64() uint64 {
	v := uint64((*b)[0]) | uint64((*b)[1])<<8 | uint64((*b)[2])<<16 | uint64((*b)[3])<<24 |
		uint64((*b)[4])<<32 | uint64((*b)[5])<<40 | uint64((*b)[6])<<48 | uint64((*b)[7])<<56
	*b = (*b)[8:]
	return v
}

func (b *readBuf) bytes(n int) []byte {
	v := (*b)[:n]
	*b = (*b)[n:]
	return v
}

func (b *readBuf) uvarint() uint64 {
	var x uint64
	var s uint
	for i, n := range *b {
		if n < 0x80 {
			*b = (*b)[i+1:]
			return x | uint64(n)<<s
		}
		x |= uint64(n&0x7f) << s
		s += 7

	}

	*b = (*b)[len(*b):]
	return 0
}

type fileBlockHeader struct {
	first     bool
	last      bool
	arcSolid  bool
	dataOff   int64
	packedOff int64
	blocknum  int
	volnum    int
	winSize   int64
	hash      func() hash.Hash
	hashKey   []byte
	sum       []byte
	decVer    int
	key       []byte
	iv        []byte
	salt      []byte
	kdfCount  int
	errs      []error
	FileHeader
}

type archiveBlockReader interface {
	init(br *bufVolumeReader) (int, error)
	nextBlock(br *bufVolumeReader) (*fileBlockHeader, error)
	useOldNaming() bool
}
