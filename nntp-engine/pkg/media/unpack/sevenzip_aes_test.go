package unpack

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"io"
	"testing"
)

func TestAES7zStreamRoundTripAndSeek(t *testing.T) {
	plain := bytes.Repeat([]byte("abcdefghijklmnop"), 64) // 1 KiB, block-aligned
	password := "test-password"
	key, err := derive7zAESKey(password, nil, 0)
	if err != nil {
		t.Fatalf("derive7zAESKey: %v", err)
	}
	iv := bytes.Repeat([]byte{0x22}, aes.BlockSize)

	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatalf("NewCipher: %v", err)
	}

	ciphertext := make([]byte, len(plain))
	enc := cipher.NewCBCEncrypter(block, iv)
	enc.CryptBlocks(ciphertext, plain)

	src := bytes.NewReader(ciphertext)
	bp := &SevenZipBlueprint{
		MainFileName:  "movie.mkv",
		TotalSize:     int64(len(plain)),
		PackedSize:    int64(len(ciphertext)),
		AESSalt:       nil,
		AESIV:         append([]byte(nil), iv...),
		KDFIterations: 0,
	}

	stream, err := newAES7zStream(nopSeekCloser{Reader: src}, password, bp)
	if err != nil {
		t.Fatalf("newAES7zStream: %v", err)
	}

	head := make([]byte, 32)
	if _, err := io.ReadFull(stream, head); err != nil {
		t.Fatalf("read head: %v", err)
	}
	if !bytes.Equal(head, plain[:32]) {
		t.Fatalf("unexpected head bytes")
	}

	if _, err := stream.Seek(512, io.SeekStart); err != nil {
		t.Fatalf("seek: %v", err)
	}
	mid := make([]byte, 32)
	if _, err := io.ReadFull(stream, mid); err != nil {
		t.Fatalf("read mid: %v", err)
	}
	if !bytes.Equal(mid, plain[512:544]) {
		t.Fatalf("unexpected mid bytes")
	}
}

func TestDerive7zAESKeyUsesSaltAndPassword(t *testing.T) {
	salt := make([]byte, 8)
	if _, err := rand.Read(salt); err != nil {
		t.Fatalf("rand: %v", err)
	}

	a, err := derive7zAESKey("secret", salt, 4)
	if err != nil {
		t.Fatalf("derive7zAESKey: %v", err)
	}
	b, err := derive7zAESKey("secret", salt, 4)
	if err != nil {
		t.Fatalf("derive7zAESKey repeat: %v", err)
	}
	if !bytes.Equal(a, b) {
		t.Fatal("expected deterministic key derivation")
	}
	if len(a) != 32 {
		t.Fatalf("expected 32-byte key, got %d", len(a))
	}
}

type nopSeekCloser struct {
	*bytes.Reader
}

func (n nopSeekCloser) Close() error { return nil }
