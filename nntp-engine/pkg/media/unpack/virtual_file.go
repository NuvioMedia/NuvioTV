package unpack

import (
	"context"
	"io"
)

type VirtualFile struct {
	name  string
	size  int64
	parts []virtualPart
}

func NewVirtualFile(name string, size int64, parts []virtualPart) *VirtualFile {
	return &VirtualFile{name: name, size: size, parts: parts}
}

func (f *VirtualFile) Name() string { return f.name }
func (f *VirtualFile) Size() int64  { return f.size }

func (f *VirtualFile) EnsureSegmentMap() error { return nil }

func (f *VirtualFile) OpenStream() (io.ReadSeekCloser, error) {
	return NewVirtualStream(context.Background(), f.parts, f.size, 0), nil
}

func (f *VirtualFile) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	return NewVirtualStream(ctx, f.parts, f.size, 0), nil
}

func (f *VirtualFile) OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	return NewVirtualStream(ctx, f.parts, f.size, offset), nil
}

func (f *VirtualFile) ReadAt(p []byte, off int64) (int, error) {
	s := NewVirtualStream(context.Background(), f.parts, f.size, 0)
	defer s.Close()

	if _, err := s.Seek(off, io.SeekStart); err != nil {
		return 0, err
	}

	n, err := io.ReadFull(s, p)
	if err == io.ErrUnexpectedEOF {
		return n, io.EOF
	}
	return n, err
}
