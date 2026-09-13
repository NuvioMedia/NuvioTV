package unpack

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"sync"

	"golang.org/x/text/encoding/unicode"
	"golang.org/x/text/transform"
)

const (
	aes7zBlockSize          = aes.BlockSize
	aes7zPlainBufferSize    = 4096
	aes7zCipherBufferBlocks = 8
)

func derive7zAESKey(password string, salt []byte, iterations int) ([]byte, error) {
	b := bytes.NewBuffer(salt)

	utf16le := unicode.UTF16(unicode.LittleEndian, unicode.IgnoreBOM)
	t := transform.NewWriter(b, utf16le.NewEncoder())
	if _, err := t.Write([]byte(password)); err != nil {
		return nil, fmt.Errorf("encode 7z password: %w", err)
	}

	key := make([]byte, sha256.Size)
	if iterations == 0 {
		copy(key, b.Bytes())
		return key, nil
	}

	h := sha256.New()
	data := b.Bytes()
	for i := uint64(0); i < uint64(iterations); i++ {
		if _, err := h.Write(data); err != nil {
			return nil, err
		}
		if err := binary.Write(h, binary.LittleEndian, i); err != nil {
			return nil, err
		}
	}
	copy(key, h.Sum(nil))
	return key, nil
}

type aes7zStream struct {
	src    io.ReadSeekCloser
	key    []byte
	baseIV []byte
	limit  int64

	mu          sync.Mutex
	pos         int64
	plainBuf    []byte
	plainStart  int
	plainEnd    int
	cipherBuf   []byte
	decrypter   cipher.BlockMode
	pendingSeek *int64
	closed      bool
}

func newAES7zStream(src io.ReadSeekCloser, password string, bp *SevenZipBlueprint) (*aes7zStream, error) {
	if bp == nil {
		return nil, errors.New("missing 7z blueprint")
	}
	if len(bp.AESIV) != aes7zBlockSize {
		return nil, fmt.Errorf("invalid AES IV length: %d", len(bp.AESIV))
	}
	if password == "" {
		return nil, fmt.Errorf("password-protected 7z (file: %s) -- password required from NZB head", bp.MainFileName)
	}

	key, err := derive7zAESKey(password, bp.AESSalt, bp.KDFIterations)
	if err != nil {
		return nil, err
	}

	baseIV := append([]byte(nil), bp.AESIV...)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create AES cipher: %w", err)
	}

	s := &aes7zStream{
		src:       src,
		key:       key,
		baseIV:    baseIV,
		limit:     bp.TotalSize,
		plainBuf:  make([]byte, aes7zPlainBufferSize),
		cipherBuf: make([]byte, aes7zBlockSize*aes7zCipherBufferBlocks),
		decrypter: cipher.NewCBCDecrypter(block, append([]byte(nil), baseIV...)),
	}
	return s, nil
}

func (s *aes7zStream) Read(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return 0, io.ErrClosedPipe
	}
	if len(p) == 0 {
		return 0, nil
	}
	if s.pendingSeek != nil {
		if err := s.seekInternal(*s.pendingSeek); err != nil {
			return 0, err
		}
		s.pendingSeek = nil
	}
	if s.pos >= s.limit {
		return 0, io.EOF
	}

	toReturn := int64(len(p))
	if remaining := s.limit - s.pos; toReturn > remaining {
		toReturn = remaining
		p = p[:toReturn]
	}

	totalCopied := 0
	if s.plainEnd > s.plainStart {
		have := s.plainEnd - s.plainStart
		take := have
		if int64(take) > toReturn {
			take = int(toReturn)
		}
		copy(p, s.plainBuf[s.plainStart:s.plainStart+take])
		s.plainStart += take
		s.pos += int64(take)
		totalCopied += take
		toReturn -= int64(take)
		if s.plainStart == s.plainEnd {
			s.plainStart = 0
			s.plainEnd = 0
		}
		if toReturn == 0 || s.pos >= s.limit {
			return totalCopied, nil
		}
		p = p[totalCopied:]
	}

	for toReturn > 0 && s.pos < s.limit {
		// A short read at the ciphertext tail still carries the final blocks:
		// ErrUnexpectedEOF means "fewer than a full buffer", not "nothing".
		// Discarding it dropped the last partial buffer of every archive whose
		// payload was not a multiple of the read size, truncating the stream
		// short of its advertised length.
		cipherRead, err := io.ReadFull(s.src, s.cipherBuf)
		if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
			return totalCopied, err
		}
		if cipherRead == 0 {
			if totalCopied > 0 {
				return totalCopied, nil
			}
			return 0, io.EOF
		}
		if cipherRead%aes7zBlockSize != 0 {
			return totalCopied, fmt.Errorf("7z ciphertext not block-aligned (read %d bytes)", cipherRead)
		}

		decryptOffset := 0
		for decryptOffset < cipherRead {
			plainSpace := len(s.plainBuf) - s.plainEnd
			if plainSpace < aes7zBlockSize && s.plainStart > 0 {
				existing := s.plainEnd - s.plainStart
				if existing > 0 {
					copy(s.plainBuf, s.plainBuf[s.plainStart:s.plainEnd])
				}
				s.plainStart = 0
				s.plainEnd = existing
				plainSpace = len(s.plainBuf) - s.plainEnd
			}

			processBytes := cipherRead - decryptOffset
			if aligned := plainSpace &^ (aes7zBlockSize - 1); aligned < processBytes {
				processBytes = aligned
			}
			if processBytes == 0 {
				break
			}

			s.decrypter.CryptBlocks(s.plainBuf[s.plainEnd:s.plainEnd+processBytes], s.cipherBuf[decryptOffset:decryptOffset+processBytes])
			decryptOffset += processBytes
			s.plainEnd += processBytes
		}

		if s.plainEnd > s.plainStart {
			have := s.plainEnd - s.plainStart
			take := have
			if int64(take) > toReturn {
				take = int(toReturn)
			}
			copy(p, s.plainBuf[s.plainStart:s.plainStart+take])
			s.plainStart += take
			s.pos += int64(take)
			totalCopied += take
			toReturn -= int64(take)
			p = p[take:]
			if s.plainStart == s.plainEnd {
				s.plainStart = 0
				s.plainEnd = 0
			}
		}
	}

	if totalCopied == 0 && s.pos >= s.limit {
		return 0, io.EOF
	}
	return totalCopied, nil
}

func (s *aes7zStream) Seek(offset int64, whence int) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return 0, io.ErrClosedPipe
	}

	var target int64
	switch whence {
	case io.SeekStart:
		target = offset
	case io.SeekCurrent:
		if s.pendingSeek != nil {
			target = *s.pendingSeek + offset
		} else {
			target = s.pos + offset
		}
	case io.SeekEnd:
		target = s.limit + offset
	default:
		return 0, errors.New("invalid whence")
	}
	if target < 0 || target > s.limit {
		return 0, errors.New("seek out of bounds")
	}

	s.pendingSeek = &target
	s.plainStart = 0
	s.plainEnd = 0
	return target, nil
}

func (s *aes7zStream) seekInternal(offset int64) error {
	if offset < 0 || offset > s.limit {
		return errors.New("seek out of bounds")
	}

	blockIndex := offset / aes7zBlockSize
	blockOffset := int(offset % aes7zBlockSize)

	cipherPos := int64(0)
	if blockIndex > 0 {
		cipherPos = (blockIndex - 1) * aes7zBlockSize
	}
	if _, err := s.src.Seek(cipherPos, io.SeekStart); err != nil {
		return fmt.Errorf("seek ciphertext stream: %w", err)
	}

	iv := make([]byte, aes7zBlockSize)
	if blockIndex > 0 {
		if _, err := io.ReadFull(s.src, iv); err != nil {
			return fmt.Errorf("read IV block for 7z seek: %w", err)
		}
	} else {
		copy(iv, s.baseIV)
	}

	block, err := aes.NewCipher(s.key)
	if err != nil {
		return fmt.Errorf("create AES cipher for seek: %w", err)
	}
	s.decrypter = cipher.NewCBCDecrypter(block, iv)
	s.plainStart = 0
	s.plainEnd = 0
	s.pos = offset - int64(blockOffset)

	if blockOffset > 0 {
		blockData := make([]byte, aes7zBlockSize)
		if _, err := io.ReadFull(s.src, blockData); err != nil {
			return fmt.Errorf("read target block for 7z seek: %w", err)
		}
		plainBlock := make([]byte, aes7zBlockSize)
		s.decrypter.CryptBlocks(plainBlock, blockData)
		remainder := aes7zBlockSize - blockOffset
		copy(s.plainBuf[:remainder], plainBlock[blockOffset:])
		s.plainStart = 0
		s.plainEnd = remainder
		s.pos = offset
	}

	return nil
}

func (s *aes7zStream) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true
	return s.src.Close()
}

func packed7zSize(bp *SevenZipBlueprint) int64 {
	if bp.PackedSize > 0 {
		return bp.PackedSize
	}
	return (bp.TotalSize + aes7zBlockSize - 1) / aes7zBlockSize * aes7zBlockSize
}
