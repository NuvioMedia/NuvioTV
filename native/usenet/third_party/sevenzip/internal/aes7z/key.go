package aes7z

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"sync"

	lru "github.com/hashicorp/golang-lru/v2"
	"github.com/javi11/sevenzip/internal/util"
	"golang.org/x/text/encoding/unicode"
	"golang.org/x/text/transform"
)

type cacheKey struct {
	password string
	cycles   int
	salt     string // []byte isn't comparable
}

const cacheSize = 10

//nolint:gochecknoglobals
var once = sync.OnceValues(func() (*lru.Cache[cacheKey, []byte], error) {
	return lru.New[cacheKey, []byte](cacheSize)
})

func calculateKey(password string, cycles int, salt []byte) ([]byte, error) {
	return calculateKeyContext(context.Background(), password, cycles, salt)
}

func calculateKeyContext(ctx context.Context, password string, cycles int, salt []byte) ([]byte, error) {
	// 19 is the usual 7-Zip cost. 63 is the format's direct-key mode.
	if cycles < 0 || (cycles > 24 && cycles != 63) {
		return nil, fmt.Errorf("%w: unsupported key derivation cost", util.ErrResourceLimit)
	}
	if len(password) > 1024 {
		return nil, fmt.Errorf("%w: password exceeds limit", util.ErrResourceLimit)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	cache, err := once()
	if err != nil {
		return nil, fmt.Errorf("aes7z: error creating cache: %w", err)
	}

	ck := cacheKey{
		password: password,
		cycles:   cycles,
		salt:     hex.EncodeToString(salt),
	}

	if key, ok := cache.Get(ck); ok {
		return key, nil
	}

	b := bytes.NewBuffer(salt)

	// Convert password to UTF-16LE
	utf16le := unicode.UTF16(unicode.LittleEndian, unicode.IgnoreBOM)
	t := transform.NewWriter(b, utf16le.NewEncoder())
	_, _ = t.Write([]byte(password))

	key := make([]byte, sha256.Size)
	if cycles == 0x3f {
		copy(key, b.Bytes())
	} else {
		h := sha256.New()
		for i := range uint64(1 << cycles) {
			if i&1023 == 0 {
				if err := ctx.Err(); err != nil {
					return nil, err
				}
			}
			// These will never error
			_, _ = h.Write(b.Bytes())
			_ = binary.Write(h, binary.LittleEndian, i)
		}

		copy(key, h.Sum(nil))
	}

	_ = cache.Add(ck, key)

	return key, nil
}
