package engine

// Password-protected stored RAR archives. The key schedules follow
// javi11/rardecode's archive15.go (RAR4: AES-128, 2^18 SHA-1 rounds) and
// archive50.go (RAR5: AES-256, PBKDF2-HMAC-SHA256). File data is one AES-CBC
// stream from the first part's IV, continuing across volumes, so a selected
// file maps onto volume extents exactly like stored plaintext and is decrypted
// per read like an encrypted 7z entry. With -hp every header after the
// archive's own is encrypted too: RAR5 prefixes each with a 16-byte IV under
// the key of the volume's encryption header, RAR4 with an 8-byte salt.

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"sync"
	"unicode/utf16"
)

var (
	ErrRARPasswordMissing = errors.New("encrypted RAR needs a password, but the NZB has none")
	ErrRARWrongPassword   = errors.New("the NZB password does not open this RAR archive")
)

// rarCrypt holds encryption parameters read from a header. For RAR5 data it is
// the file header's crypt record; for an RAR5 archive encryption header iv is
// unused; RAR4 only has a salt.
type rarCrypt struct {
	version int // 4 or 5
	kdf     int // RAR5: log2 PBKDF2 iterations
	salt    []byte
	iv      []byte
	check   []byte // RAR5 password check value (8 bytes + 4 checksum), optional
}

func roundUp16(n int64) int64 { return (n + aes.BlockSize - 1) / aes.BlockSize * aes.BlockSize }

// Derived keys are expensive (2^15+ HMAC or 2^18 SHA-1 rounds) and every volume
// of a set repeats the same parameters, so a few results are kept.
var rarKeyCache = struct {
	sync.Mutex
	entries []rarKeyEntry
}{}

type rarKeyEntry struct {
	id         string
	key, extra []byte
}

func cachedRARKey(id string, derive func() ([]byte, []byte, error)) ([]byte, []byte, error) {
	rarKeyCache.Lock()
	for _, e := range rarKeyCache.entries {
		if e.id == id {
			rarKeyCache.Unlock()
			return e.key, e.extra, nil
		}
	}
	rarKeyCache.Unlock()
	key, extra, err := derive()
	if err != nil {
		return nil, nil, err
	}
	rarKeyCache.Lock()
	rarKeyCache.entries = append([]rarKeyEntry{{id, key, extra}}, rarKeyCache.entries...)
	rarKeyCache.entries = rarKeyCache.entries[:min(len(rarKeyCache.entries), 8)]
	rarKeyCache.Unlock()
	return key, extra, nil
}

// rar5Keys returns the AES-256 key and the 12-byte password check value.
// Up to 2^24 HMAC rounds, so cancellation is checked as for 7z keys.
func rar5Keys(ctx context.Context, password string, salt []byte, kdf int) ([]byte, []byte, error) {
	if kdf > 24 || len(salt) == 0 {
		return nil, nil, errors.New("unsupported RAR5 key derivation")
	}
	id := fmt.Sprintf("5/%x/%d/%x", salt, kdf, sha256.Sum256([]byte(password)))
	return cachedRARKey(id, func() ([]byte, []byte, error) {
		prf := hmac.New(sha256.New, []byte(password))
		prf.Write(salt)
		prf.Write([]byte{0, 0, 0, 1})
		t := prf.Sum(nil)
		u := append([]byte(nil), t...)
		var out [3][]byte
		for i, rounds := range []int{1<<kdf - 1, 16, 16} {
			for ; rounds > 0; rounds-- {
				if rounds&1023 == 0 {
					if err := ctx.Err(); err != nil {
						return nil, nil, err
					}
				}
				prf.Reset()
				prf.Write(u)
				u = prf.Sum(u[:0])
				for j := range u {
					t[j] ^= u[j]
				}
			}
			out[i] = append([]byte(nil), t...)
		}
		check := out[2]
		for i, v := range check[8:] {
			check[i&7] ^= v
		}
		sum := sha256.Sum256(check[:8])
		return out[0], append(check[:8:8], sum[:4]...), nil
	})
}

// rar4Keys returns the AES-128 key and IV for an 8-byte salt.
func rar4Keys(ctx context.Context, password string, salt []byte) ([]byte, []byte, error) {
	id := fmt.Sprintf("4/%x/%x", salt, sha256.Sum256([]byte(password)))
	return cachedRARKey(id, func() ([]byte, []byte, error) {
		var p []byte
		for _, u := range utf16.Encode([]rune(password)) {
			p = append(p, byte(u), byte(u>>8))
		}
		p = append(p, salt...)
		const rounds = 0x40000
		h := sha1.New()
		iv := make([]byte, aes.BlockSize)
		var counter [3]byte
		var sum []byte
		for i := 0; i < rounds; i++ {
			if i&1023 == 0 {
				if err := ctx.Err(); err != nil {
					return nil, nil, err
				}
			}
			h.Write(p)
			counter[0], counter[1], counter[2] = byte(i), byte(i>>8), byte(i>>16)
			h.Write(counter[:])
			if i%(rounds/16) == 0 {
				sum = h.Sum(sum[:0])
				iv[i/(rounds/16)] = sum[19]
			}
		}
		key := h.Sum(nil)[:16]
		for k := key; len(k) >= 4; k = k[4:] {
			k[0], k[1], k[2], k[3] = k[3], k[2], k[1], k[0]
		}
		return key, iv, nil
	})
}

// rarDataCipher prepares decryption of a selected file's data.
func rarDataCipher(ctx context.Context, c *rarCrypt, password string) (*cbcCipher, error) {
	if password == "" {
		return nil, ErrRARPasswordMissing
	}
	var key, iv []byte
	var err error
	if c.version == 5 {
		var check []byte
		if key, check, err = rar5Keys(ctx, password, c.salt, c.kdf); err != nil {
			return nil, err
		}
		if len(c.check) == 12 && !bytes.Equal(c.check, check) {
			return nil, ErrRARWrongPassword
		}
		iv = c.iv
	} else if key, iv, err = rar4Keys(ctx, password, c.salt); err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	if len(iv) != aes.BlockSize {
		return nil, ErrEncryptedRAR
	}
	return &cbcCipher{block: block, iv: iv}, nil
}

// rarHeaderCrypt decrypts the headers of one -hp volume.
type rarHeaderCrypt struct {
	version  int
	block    cipher.Block // RAR5: one key per volume encryption header.
	password string       // RAR4: each header carries its own salt.
}

func newRARHeaderCrypt(ctx context.Context, c *rarCrypt, password string) (*rarHeaderCrypt, error) {
	if password == "" {
		return nil, ErrRARPasswordMissing
	}
	h := &rarHeaderCrypt{version: c.version, password: password}
	if c.version == 5 {
		key, check, err := rar5Keys(ctx, password, c.salt, c.kdf)
		if err != nil {
			return nil, err
		}
		if len(c.check) == 12 && !bytes.Equal(c.check, check) {
			return nil, ErrRARWrongPassword
		}
		if h.block, err = aes.NewCipher(key); err != nil {
			return nil, err
		}
	}
	return h, nil
}

// view returns a plaintext reader for the header stored at pos and the size of
// its prefix (IV or salt) on disk.
func (h *rarHeaderCrypt) view(ctx context.Context, r io.ReaderAt, pos int64) (io.ReaderAt, int64, error) {
	if h.version == 5 {
		iv := make([]byte, aes.BlockSize)
		if err := readFullAt(r, iv, pos); err != nil {
			return nil, 0, err
		}
		return &cbcView{r: r, block: h.block, iv: iv, base: pos + aes.BlockSize}, aes.BlockSize, nil
	}
	salt := make([]byte, 8)
	if err := readFullAt(r, salt, pos); err != nil {
		return nil, 0, err
	}
	key, iv, err := rar4Keys(ctx, h.password, salt)
	if err != nil {
		return nil, 0, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, 0, err
	}
	return &cbcView{r: r, block: block, iv: iv, base: pos + 8}, 8, nil
}

// cbcView decrypts an AES-CBC stream starting at base, block by block.
type cbcView struct {
	r     io.ReaderAt
	block cipher.Block
	iv    []byte
	base  int64
}

func (v *cbcView) ReadAt(p []byte, off int64) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	first := off / aes.BlockSize * aes.BlockSize
	from := first
	if first > 0 {
		from -= aes.BlockSize
	}
	to := roundUp16(off + int64(len(p)))
	buf := make([]byte, to-from)
	if err := readFullAt(v.r, buf, v.base+from); err != nil {
		return 0, err
	}
	iv := v.iv
	if first > 0 {
		iv = buf[:aes.BlockSize]
		buf = buf[aes.BlockSize:]
	}
	cipher.NewCBCDecrypter(v.block, append([]byte(nil), iv...)).CryptBlocks(buf, buf)
	return copy(p, buf[off-first:]), nil
}
