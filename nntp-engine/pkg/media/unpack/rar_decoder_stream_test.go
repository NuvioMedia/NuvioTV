package unpack

import (
	"bytes"
	"errors"
	"io"
	"testing"
)

type seekEndFailingSeeker struct {
	*bytes.Reader
	seekCalls int
}

func (s *seekEndFailingSeeker) Seek(offset int64, whence int) (int64, error) {
	s.seekCalls++
	if whence == io.SeekEnd {
		return 0, errors.New("packedSize scan failed")
	}
	return s.Reader.Seek(offset, whence)
}

func TestDecoderReadSeekCloserSeekEndUsesKnownSize(t *testing.T) {
	data := []byte("0123456789")
	underlying := &seekEndFailingSeeker{Reader: bytes.NewReader(data)}
	stream := &decoderReadSeekCloser{
		rc:     io.NopCloser(underlying),
		seeker: underlying,
		size:   int64(len(data)),
	}

	pos, err := stream.Seek(0, io.SeekEnd)
	if err != nil {
		t.Fatalf("Seek(0, SeekEnd) returned error: %v", err)
	}
	if pos != int64(len(data)) {
		t.Fatalf("SeekEnd position = %d, want %d", pos, len(data))
	}
	if underlying.seekCalls != 0 {
		t.Fatalf("SeekEnd should not call underlying seeker, calls = %d", underlying.seekCalls)
	}

	pos, err = stream.Seek(0, io.SeekStart)
	if err != nil {
		t.Fatalf("Seek(0, SeekStart) returned error: %v", err)
	}
	if pos != 0 {
		t.Fatalf("SeekStart position = %d, want 0", pos)
	}
}

func TestDecoderReadSeekCloserMatchesServeContentSizeProbe(t *testing.T) {
	data := []byte("0123456789abcdef")
	underlying := &seekEndFailingSeeker{Reader: bytes.NewReader(data)}
	stream := &decoderReadSeekCloser{
		rc:     io.NopCloser(underlying),
		seeker: underlying,
		size:   int64(len(data)),
	}

	size, err := stream.Seek(0, io.SeekEnd)
	if err != nil {
		t.Fatalf("size probe SeekEnd returned error: %v", err)
	}
	if _, err := stream.Seek(0, io.SeekStart); err != nil {
		t.Fatalf("rewind SeekStart returned error: %v", err)
	}
	if size != int64(len(data)) {
		t.Fatalf("size probe = %d, want %d", size, len(data))
	}
}
