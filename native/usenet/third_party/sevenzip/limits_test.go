package sevenzip

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"testing"
	"time"
)

func TestMetadataAllocationsAreBounded(t *testing.T) {
	// The format encodes uint64-max in nine bytes. Reject it before make().
	huge := append([]byte{0xff}, bytes.Repeat([]byte{0xff}, 8)...)
	for name, parse := range map[string]func(*bytes.Reader) error{
		"files":    func(r *bytes.Reader) error { _, err := readFilesInfo(r); return err },
		"coders":   func(r *bytes.Reader) error { _, err := readFolder(r); return err },
		"sizes":    func(r *bytes.Reader) error { _, err := readSizes(r, ^uint64(0)); return err },
		"booleans": func(r *bytes.Reader) error { _, err := readBool(r, ^uint64(0)); return err },
	} {
		t.Run(name, func(t *testing.T) {
			if err := parse(bytes.NewReader(huge)); !errors.Is(err, ErrResourceLimit) {
				t.Fatalf("got %v", err)
			}
		})
	}
}

func FuzzReaderMetadata(f *testing.F) {
	f.Add([]byte{1, 5, 0, 0, 0})
	f.Add(append([]byte{1, 5, 0xff}, bytes.Repeat([]byte{0xff}, 8)...))
	f.Add([]byte{1, 4, 6, 0, 1, 9, 0, 0, 7, 11, 1, 0, 1, 1, 0, 12, 0, 0, 0, 5, 1, 0, 0})
	// Encoded headers require packed stream metadata before decoding a folder.
	f.Add([]byte{0x17, 7, 11, 1, 0, 1, 1, 0, 12, 5, 0, 0})
	f.Fuzz(func(t *testing.T, header []byte) {
		if len(header) > 64<<10 {
			t.Skip()
		}
		start := make([]byte, 20)
		binary.LittleEndian.PutUint64(start[8:], uint64(len(header)))
		binary.LittleEndian.PutUint32(start[16:], crc32.ChecksumIEEE(header))
		archive := []byte{'7', 'z', 0xbc, 0xaf, 0x27, 0x1c, 0, 4}
		archive = binary.LittleEndian.AppendUint32(archive, crc32.ChecksumIEEE(start))
		archive = append(archive, start...)
		archive = append(archive, header...)
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		defer cancel()
		_, _ = NewReaderWithContext(ctx, bytes.NewReader(archive), int64(len(archive)), "password")
	})
}

func TestReaderCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := NewReaderWithContext(ctx, bytes.NewReader(nil), 0, "password"); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled archive open: %v", err)
	}
}
