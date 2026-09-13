package rardecode

import (
	"bytes"
	"errors"
	"io"
	"io/fs"
)

const (
	maxSfxSize   = 0x100000
	sigPrefix    = "Rar!\x1A\x07"
	sigPrefixLen = len(sigPrefix)

	maxEmptyReads = 100

	minBufSize     = 32
	defaultBufSize = 4096
)

var (
	ErrNoSig        = errors.New("rardecode: RAR signature not found")
	ErrNegativeRead = errors.New("rardecode: negative read from Reader")
)

type bufVolumeReader struct {
	r   io.Reader
	sr  io.Seeker
	buf []byte
	i   int
	n   int
	off int64
	err error
	ver int
}

func (br *bufVolumeReader) readErr() error {
	err := br.err
	br.err = nil
	return err
}

func (br *bufVolumeReader) fill() error {
	if br.err != nil {
		return br.readErr()
	}
	br.i = 0
	for i := 0; i < maxEmptyReads; i++ {
		br.n, br.err = br.r.Read(br.buf)
		if br.n > 0 {
			return nil
		}
		if br.n < 0 {
			return ErrNegativeRead
		}
		if br.err != nil {
			return br.readErr()
		}
	}
	return io.ErrNoProgress
}

func (br *bufVolumeReader) canSeek() bool {
	return br.sr != nil
}

func (br *bufVolumeReader) seek(offset int64) error {
	if br.sr == nil {
		return fs.ErrInvalid
	}
	start := br.off - int64(br.i)
	end := start + int64(br.n)
	if offset >= start && offset <= end {
		diff := offset - br.off
		br.off += diff
		br.i += int(diff)
		return nil
	}
	_, err := br.sr.Seek(offset, io.SeekStart)
	if err != nil {
		return err
	}
	br.i = 0
	br.n = 0
	br.off = offset
	br.err = nil
	return nil
}

func (br *bufVolumeReader) Read(p []byte) (int, error) {
	if br.i == br.n {
		err := br.fill()
		if err != nil {
			return 0, err
		}
	}
	n := copy(p, br.buf[br.i:br.n])
	br.i += n
	br.off += int64(n)
	return n, nil
}

func (br *bufVolumeReader) ReadByte() (byte, error) {
	if br.i == br.n {
		err := br.fill()
		if err != nil {
			return 0, err
		}
	}
	c := br.buf[br.i]
	br.i++
	br.off++
	return c, nil
}

func (br *bufVolumeReader) Discard(n int64) error {
	buffered := int64(br.n - br.i)
	if buffered >= n {
		br.i += int(n)
		br.off += n
		return nil
	}

	n -= buffered
	br.i = 0
	br.n = 0
	br.off += buffered

	if br.sr != nil {
		_, err := br.sr.Seek(n, io.SeekCurrent)
		if err != nil {
			return err
		}
		br.off += n
		return nil
	}

	if n > 1<<20 {

		const largeDiscardBufSize = 1 << 16
		buf := make([]byte, largeDiscardBufSize)
		for n > 0 {
			toRead := n
			if toRead > int64(len(buf)) {
				toRead = int64(len(buf))
			}
			read, err := io.ReadFull(br.r, buf[:toRead])
			br.off += int64(read)
			n -= int64(read)
			if err != nil {
				if err == io.EOF || err == io.ErrUnexpectedEOF {

					return nil
				}
				return err
			}
		}
		return nil
	}

	written, err := io.CopyN(io.Discard, br.r, n)
	br.off += written
	return err
}

func (br *bufVolumeReader) writeToN(w io.Writer, n int64) (int64, error) {
	if n == 0 {
		return 0, nil
	}
	var err error
	todo := n
	startOffset := br.off
	for todo != 0 && err == nil {
		if br.i == br.n {
			err = br.fill()
			if err != nil {
				break
			}
		}
		buf := br.buf[br.i:br.n]
		if todo > 0 && todo < int64(len(buf)) {
			buf = buf[:todo]
		}
		var l int
		l, err = w.Write(buf)
		br.i += l
		br.off += int64(l)
		if todo > 0 {
			todo -= int64(l)
		}
	}
	if todo < 0 && err == io.EOF {
		err = nil
	}
	return br.off - startOffset, nil
}

func (br *bufVolumeReader) findSig() (int, error) {
	for br.off <= maxSfxSize {
		if br.i == br.n {
			err := br.fill()
			if err != nil {
				return 0, err
			}
		}
		n := bytes.IndexByte(br.buf[br.i:br.n], sigPrefix[0])
		if n < 0 {

			br.off += int64(br.n - br.i)
			br.i = br.n
			continue
		}
		br.i += n
		br.off += int64(n)

		buffered := br.n - br.i
		if buffered < sigPrefixLen+2 {
			br.n = copy(br.buf, br.buf[br.i:br.n])
			br.i = 0
			l, err := io.ReadAtLeast(br.r, br.buf[br.n:], sigPrefixLen+2-buffered)
			br.n += l
			if err != nil {
				if errors.Is(err, io.ErrUnexpectedEOF) {
					err = ErrNoSig
				}
				return 0, err
			}
		}
		if !bytes.HasPrefix(br.buf[br.i:br.n], []byte(sigPrefix)) {
			br.i++
			br.off++
			continue
		}
		br.i += sigPrefixLen
		br.off += int64(sigPrefixLen)

		ver := int(br.buf[br.i])
		if ver == 0 {
			br.i++
			br.off++
		} else if br.buf[br.i+1] == 0 {
			br.i += 2
			br.off += 2
		} else {
			continue
		}
		return ver, nil
	}
	return 0, ErrNoSig
}

func (br *bufVolumeReader) Reset(r io.Reader) error {
	br.r = r
	br.sr, _ = r.(io.Seeker)
	br.i = 0
	br.n = 0
	br.off = 0
	br.err = nil
	ver, err := br.findSig()
	if err != nil {
		if errors.Is(err, io.EOF) {
			return ErrNoSig
		}
		return err
	}
	br.ver = ver
	return nil
}

func newBufVolumeReader(r io.Reader, size int) (*bufVolumeReader, error) {
	if size == 0 {
		size = defaultBufSize
	} else {
		size = max(minBufSize, size)
	}
	br := &bufVolumeReader{
		buf: make([]byte, size),
	}
	err := br.Reset(r)
	if err != nil {
		return nil, err
	}
	return br, nil
}
