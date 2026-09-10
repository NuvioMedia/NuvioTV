package unpack

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"errors"
	"io"
	"testing"
)

func TestVirtualStreamLiveCount(t *testing.T) {
	before := LiveVirtualStreams()
	stream := NewVirtualStream(context.Background(), nil, 0, 0)
	if got := LiveVirtualStreams(); got != before+1 {
		_ = stream.Close()
		t.Fatalf("expected %d live virtual streams, got %d", before+1, got)
	}
	if err := stream.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
	if got := LiveVirtualStreams(); got != before {
		t.Fatalf("expected %d live virtual streams after close, got %d", before, got)
	}
}

// streamSizedFile records the stream-size hint NewVirtualStream hands out.
type streamSizedFile struct {
	*memoryUnpackableFile
	streamBytes int64
}

func (f *streamSizedFile) SetPlaybackStreamBytes(n int64) { f.streamBytes = n }

// A volume sizes its read-ahead window against the file it stores a slice of,
// so the virtual stream must tell each one how long the whole stream is.
func TestNewVirtualStreamHintsStreamSizeToVolumes(t *testing.T) {
	vols := []*streamSizedFile{
		{memoryUnpackableFile: &memoryUnpackableFile{name: "part1", data: []byte("abc")}},
		{memoryUnpackableFile: &memoryUnpackableFile{name: "part2", data: []byte("defg")}},
	}
	const total = int64(1) << 40
	stream := NewVirtualStream(context.Background(), []virtualPart{
		{VirtualStart: 0, VirtualEnd: 3, VolFile: vols[0]},
		{VirtualStart: 3, VirtualEnd: 7, VolFile: vols[1]},
	}, total, 0)
	defer stream.Close()

	for i, v := range vols {
		if v.streamBytes != total {
			t.Errorf("volume %d stream-size hint = %d, want %d", i, v.streamBytes, total)
		}
	}
}

func TestVirtualStreamReadsAcrossPartBoundaries(t *testing.T) {
	stream := NewVirtualStream(context.Background(), []virtualPart{
		{VirtualStart: 0, VirtualEnd: 3, VolFile: &memoryUnpackableFile{name: "part1", data: []byte("abc")}},
		{VirtualStart: 3, VirtualEnd: 7, VolFile: &memoryUnpackableFile{name: "part2", data: []byte("defg")}},
	}, 7, 0)
	defer stream.Close()

	got, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("ReadAll returned error: %v", err)
	}
	if string(got) != "abcdefg" {
		t.Fatalf("expected concatenated stream %q, got %q", "abcdefg", string(got))
	}
}

func TestVirtualStreamSeekNearEOFReturnsRemainingBytesThenEOF(t *testing.T) {
	stream := NewVirtualStream(context.Background(), []virtualPart{
		{VirtualStart: 0, VirtualEnd: 3, VolFile: &memoryUnpackableFile{name: "part1", data: []byte("abc")}},
		{VirtualStart: 3, VirtualEnd: 7, VolFile: &memoryUnpackableFile{name: "part2", data: []byte("defg")}},
	}, 7, 0)
	defer stream.Close()

	if _, err := stream.Seek(-2, io.SeekEnd); err != nil {
		t.Fatalf("Seek returned error: %v", err)
	}

	buf := make([]byte, 4)
	n, err := stream.Read(buf)
	if err != nil {
		t.Fatalf("Read returned error: %v", err)
	}
	if n != 2 {
		t.Fatalf("expected to read 2 bytes near EOF, got %d", n)
	}
	if string(buf[:n]) != "fg" {
		t.Fatalf("expected remaining bytes %q, got %q", "fg", string(buf[:n]))
	}

	n, err = stream.Read(buf)
	if n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("expected EOF after tail read, got (%d, %v)", n, err)
	}
}

func TestVirtualStreamSeekToEndReturnsEOFOnRead(t *testing.T) {
	stream := NewVirtualStream(context.Background(), []virtualPart{
		{VirtualStart: 0, VirtualEnd: 3, VolFile: &memoryUnpackableFile{name: "part1", data: []byte("abc")}},
		{VirtualStart: 3, VirtualEnd: 7, VolFile: &memoryUnpackableFile{name: "part2", data: []byte("defg")}},
	}, 7, 0)
	defer stream.Close()

	if _, err := stream.Seek(0, io.SeekEnd); err != nil {
		t.Fatalf("Seek returned error: %v", err)
	}

	buf := make([]byte, 1)
	n, err := stream.Read(buf)
	if n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("expected EOF after seek to end, got (%d, %v)", n, err)
	}
}

func TestEncryptedVirtualStreamCorrectDecryptionAndSeeking(t *testing.T) {
	// AES block size is 16. Use a 64-byte plaintext.
	plaintext := []byte("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	key := []byte("0123456789abcdef0123456789abcdef") // 32 bytes (AES-256)
	iv := []byte("fedcba9876543210")                  // 16 bytes

	// Encrypt plaintext using AES-256-CBC
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatalf("NewCipher: %v", err)
	}
	ciphertext := make([]byte, len(plaintext))
	mode := cipher.NewCBCEncrypter(block, iv)
	mode.CryptBlocks(ciphertext, plaintext)

	// Split ciphertext into two virtual parts:
	// Part 1: virtual offsets [0, 30), file name "vol1"
	// Part 2: virtual offsets [30, 64), file name "vol2"
	parts := []virtualPart{
		{
			VirtualStart: 0,
			VirtualEnd:   30,
			VolFile:      &memoryUnpackableFile{name: "vol1", data: ciphertext[:30]},
			VolOffset:    0,
			AesKey:       key,
			AesIV:        iv,
		},
		{
			VirtualStart: 30,
			VirtualEnd:   64,
			VolFile:      &memoryUnpackableFile{name: "vol2", data: ciphertext[30:]},
			VolOffset:    0,
			AesKey:       key,
			AesIV:        iv,
		},
	}

	stream := NewEncryptedVirtualStream(context.Background(), parts, int64(len(plaintext)), int64(len(ciphertext)), key, iv, 0)
	defer stream.Close()

	// 1. Verify sequential Read
	buf := make([]byte, len(plaintext))
	if _, err := io.ReadFull(stream, buf); err != nil {
		t.Fatalf("io.ReadFull: %v", err)
	}
	if !bytes.Equal(buf, plaintext) {
		t.Fatalf("decrypted sequential output mismatch:\ngot:  %q\nwant: %q", string(buf), string(plaintext))
	}

	// 2. Verify Seek and Read (instant random-access decryption)
	seeks := []struct {
		offset int64
		length int
		want   string
	}{
		{offset: 0, length: 16, want: "0123456789abcdef"},
		{offset: 16, length: 16, want: "0123456789abcdef"},
		{offset: 32, length: 16, want: "0123456789abcdef"},
		{offset: 48, length: 16, want: "0123456789abcdef"},
		// Unaligned seek & read
		{offset: 5, length: 10, want: "56789abcde"},
		{offset: 25, length: 15, want: "9abcdef01234567"},
		{offset: 40, length: 20, want: "89abcdef0123456789ab"},
	}

	for _, tc := range seeks {
		if _, err := stream.Seek(tc.offset, io.SeekStart); err != nil {
			t.Fatalf("Seek to %d: %v", tc.offset, err)
		}
		readBuf := make([]byte, tc.length)
		if _, err := io.ReadFull(stream, readBuf); err != nil {
			t.Fatalf("ReadFull at offset %d: %v", tc.offset, err)
		}
		if string(readBuf) != tc.want {
			t.Errorf("at offset %d: got %q, want %q", tc.offset, string(readBuf), tc.want)
		}
	}
}

func TestVirtualStreamSeekToDifferentPartAndRead(t *testing.T) {
	stream := NewVirtualStream(context.Background(), []virtualPart{
		{VirtualStart: 0, VirtualEnd: 3, VolFile: &memoryUnpackableFile{name: "part1", data: []byte("abc")}},
		{VirtualStart: 3, VirtualEnd: 7, VolFile: &memoryUnpackableFile{name: "part2", data: []byte("defg")}},
		{VirtualStart: 7, VirtualEnd: 12, VolFile: &memoryUnpackableFile{name: "part3", data: []byte("hijkl")}},
	}, 12, 0)
	defer stream.Close()

	if _, err := stream.Seek(4, io.SeekStart); err != nil {
		t.Fatalf("Seek to 4 failed: %v", err)
	}

	buf := make([]byte, 5)
	n, err := stream.Read(buf)
	if err != nil {
		t.Fatalf("Read failed: %v", err)
	}
	if n != 3 {
		t.Fatalf("expected to read 3 bytes, got %d", n)
	}
	if string(buf[:n]) != "efg" {
		t.Fatalf("expected %q, got %q", "efg", string(buf[:n]))
	}

	n, err = stream.Read(buf)
	if err != nil {
		t.Fatalf("Read failed: %v", err)
	}
	if n != 5 {
		t.Fatalf("expected to read 5 bytes, got %d", n)
	}
	if string(buf[:n]) != "hijkl" {
		t.Fatalf("expected %q, got %q", "hijkl", string(buf[:n]))
	}
}

type seekableMemoryReader struct {
	*bytes.Reader
}

func (s *seekableMemoryReader) Close() error { return nil }

type seekableMemoryUnpackableFile struct {
	name string
	data []byte
}

func (f *seekableMemoryUnpackableFile) Name() string            { return f.name }
func (f *seekableMemoryUnpackableFile) Size() int64             { return int64(len(f.data)) }
func (f *seekableMemoryUnpackableFile) EnsureSegmentMap() error { return nil }
func (f *seekableMemoryUnpackableFile) OpenStream() (io.ReadSeekCloser, error) {
	return &nopReadSeekCloser{Reader: bytes.NewReader(f.data)}, nil
}
func (f *seekableMemoryUnpackableFile) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	return &nopReadSeekCloser{Reader: bytes.NewReader(f.data)}, nil
}
func (f *seekableMemoryUnpackableFile) OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	if offset < 0 {
		offset = 0
	}
	if offset > int64(len(f.data)) {
		offset = int64(len(f.data))
	}
	r := bytes.NewReader(f.data)
	_, _ = r.Seek(offset, io.SeekStart)
	return &seekableMemoryReader{Reader: r}, nil
}
func (f *seekableMemoryUnpackableFile) ReadAt(p []byte, off int64) (int, error) {
	return bytes.NewReader(f.data).ReadAt(p, off)
}

func TestVirtualStreamAbsoluteSeekInsideSamePart(t *testing.T) {
	fileData := []byte("abcdefghijklmnopqrstuvwxyz")
	f := &seekableMemoryUnpackableFile{name: "part1", data: fileData}
	parts := []virtualPart{
		{VirtualStart: 0, VirtualEnd: int64(len(fileData)), VolFile: f, VolOffset: 0},
	}
	stream := NewVirtualStream(context.Background(), parts, int64(len(fileData)), 0)
	defer stream.Close()

	// Read first 5 bytes ("abcde")
	buf := make([]byte, 5)
	if _, err := io.ReadFull(stream, buf); err != nil {
		t.Fatalf("ReadFull: %v", err)
	}
	if string(buf) != "abcde" {
		t.Fatalf("expected abcde, got %s", string(buf))
	}

	// Seek to offset 10 ("klmno...")
	if _, err := stream.Seek(10, io.SeekStart); err != nil {
		t.Fatalf("Seek: %v", err)
	}

	// Verify that it reads "klmno"
	if _, err := io.ReadFull(stream, buf); err != nil {
		t.Fatalf("ReadFull after seek: %v", err)
	}
	if string(buf) != "klmno" {
		t.Fatalf("expected klmno, got %s", string(buf))
	}

	// Seek back to offset 2 ("cdefg...")
	if _, err := stream.Seek(2, io.SeekStart); err != nil {
		t.Fatalf("Seek back: %v", err)
	}
	if _, err := io.ReadFull(stream, buf); err != nil {
		t.Fatalf("ReadFull after seek back: %v", err)
	}
	if string(buf) != "cdefg" {
		t.Fatalf("expected cdefg, got %s", string(buf))
	}
}
