package rardecode

import (
	"errors"
	"io"
)

const (
	minWindowSize    = 0x40000
	maxQueuedFilters = 8192
)

var (
	ErrTooManyFilters   = errors.New("rardecode: too many filters")
	ErrInvalidFilter    = errors.New("rardecode: invalid filter")
	ErrMultipleDecoders = errors.New("rardecode: multiple decoders in a single archive not supported")
)

type filter func(b []byte, offset int64) ([]byte, error)

type filterBlock struct {
	length int
	offset int
	filter filter
}

type decoder interface {
	init(r byteReader, reset bool, size int64, ver int)
	fill(dr *decodeReader) error
	version() int
}

type decodeReader struct {
	archiveFile
	tot    int64
	outbuf []byte
	buf    []byte
	fl     []*filterBlock
	dec    decoder
	err    error
	solid  bool

	win  []byte
	size int
	r    int
	w    int
}

func (d *decodeReader) init(f archiveFile, ver int, size int, reset, arcSolid bool, unPackedSize int64) error {
	d.outbuf = nil
	d.tot = 0
	d.err = nil
	d.solid = arcSolid
	if reset {
		d.fl = nil
	}
	d.archiveFile = f

	size = max(size, minWindowSize)
	if size > len(d.win) {
		b := make([]byte, size)
		if reset {
			d.w = 0
		} else if len(d.win) > 0 {
			n := copy(b, d.win[d.w:])
			n += copy(b[n:], d.win[:d.w])
			d.w = n
		}
		d.win = b
		d.size = size
	} else if reset {
		clear(d.win[:])
		d.w = 0
	}
	d.r = d.w

	if d.dec == nil {
		switch ver {
		case decode29Ver:
			d.dec = new(decoder29)
		case decode50Ver, decode70Ver:
			d.dec = new(decoder50)
		case decode20Ver:
			d.dec = new(decoder20)
		default:
			return ErrUnknownDecoder
		}
	} else if d.dec.version() != ver {
		return ErrMultipleDecoders
	}
	d.dec.init(f, reset, unPackedSize, ver)
	return nil
}

func (d *decodeReader) notFull() bool { return d.w < d.size }

func (d *decodeReader) writeByte(c byte) {
	d.win[d.w] = c
	d.w++
}

func (d *decodeReader) copyBytes(length, offset int) {
	length %= d.size
	if length < 0 {
		length += d.size
	}

	i := (d.w - offset) % d.size
	if i < 0 {
		i += d.size
	}
	iend := i + length
	if i > d.w {
		if iend > d.size {
			iend = d.size
		}
		n := copy(d.win[d.w:], d.win[i:iend])
		d.w += n
		length -= n
		if length == 0 {
			return
		}
		iend = length
		i = 0
	}
	if iend <= d.w {
		n := copy(d.win[d.w:], d.win[i:iend])
		d.w += n
		return
	}
	for length > 0 && d.w < d.size {
		d.win[d.w] = d.win[i]
		d.w++
		i++
		length--
	}
}

func (d *decodeReader) queueFilter(f *filterBlock) error {
	if len(d.fl) >= maxQueuedFilters {
		return ErrTooManyFilters
	}

	f.offset += d.w - d.r

	for _, fb := range d.fl {
		if f.offset < fb.offset {

			return ErrInvalidFilter
		}
		f.offset -= fb.offset
	}

	f.offset %= d.size
	if f.offset < 0 {
		f.offset += d.size
	}
	f.length %= d.size
	if f.length < 0 {
		f.length += d.size
	}
	d.fl = append(d.fl, f)
	return nil
}

func (d *decodeReader) readErr() error {
	err := d.err
	d.err = nil
	return err
}

func (d *decodeReader) fill() error {
	if d.err != nil {
		return d.readErr()
	}
	if d.w == d.size {

		d.r = 0
		d.w = 0
	}
	d.err = d.dec.fill(d)
	if d.w == d.r {
		return d.readErr()
	}
	return nil
}

func (d *decodeReader) bufBytes(n int) ([]byte, error) {
	if cap(d.buf) < n {
		d.buf = make([]byte, n)
	}

	ns := 0
	for {
		nn := copy(d.buf[ns:n], d.win[d.r:d.w])
		d.r += nn
		ns += nn
		if ns == n {
			break
		}
		if err := d.fill(); err != nil {
			return nil, err
		}
	}
	return d.buf[:n], nil
}

func (d *decodeReader) processFilters() ([]byte, error) {
	f := d.fl[0]
	flen := f.length

	b, err := d.bufBytes(flen)
	if err != nil {
		return nil, err
	}
	for {
		d.fl = d.fl[1:]

		b, err = f.filter(b, d.tot)
		if err != nil {
			return nil, err
		}
		if len(d.fl) == 0 {
			d.fl = nil
			return b, nil
		}

		f = d.fl[0]
		if f.offset != 0 {

			f.offset -= flen
			return b, nil
		}
		if f.length != len(b) {
			return nil, ErrInvalidFilter
		}
	}
}

func (d *decodeReader) decode() error {

	if d.w == d.r {
		err := d.fill()
		if err != nil {
			return err
		}
	}
	n := d.w - d.r

	if len(d.fl) == 0 {
		d.outbuf = d.win[d.r:d.w]
		d.r = d.w
		d.tot += int64(n)
		return nil
	}

	f := d.fl[0]
	if f.offset < 0 {
		return ErrInvalidFilter
	}
	if f.offset > 0 {

		n = min(f.offset, n)
		d.outbuf = d.win[d.r : d.r+n]
		d.r += n
		f.offset -= n
		d.tot += int64(n)
		return nil
	}

	var err error
	d.outbuf, err = d.processFilters()
	if err != nil {
		return err
	}
	if cap(d.outbuf) > cap(d.buf) {

		d.buf = d.outbuf
	}
	d.tot += int64(len(d.outbuf))
	return nil
}

func (d *decodeReader) Read(p []byte) (int, error) {
	if len(d.outbuf) == 0 {
		err := d.decode()
		if err != nil {
			return 0, err
		}
	}
	n := copy(p, d.outbuf)
	d.outbuf = d.outbuf[n:]
	return n, nil
}

func (d *decodeReader) ReadByte() (byte, error) {
	if len(d.outbuf) == 0 {
		err := d.decode()
		if err != nil {
			return 0, err
		}
	}
	b := d.outbuf[0]
	d.outbuf = d.outbuf[1:]
	return b, nil
}

func (d *decodeReader) writeToN(w io.Writer, n int64) (int64, error) {
	if n == 0 {
		return 0, nil
	}
	var tot int64
	var err error
	for tot != n && err == nil {
		if len(d.outbuf) == 0 {
			err = d.decode()
			if err != nil {
				break
			}
		}
		buf := d.outbuf
		if n > 0 {
			todo := n - tot
			if todo < int64(len(buf)) {
				buf = buf[:todo]
			}
		}
		var l int
		l, err = w.Write(buf)
		tot += int64(l)
		d.outbuf = d.outbuf[l:]
	}
	if n < 0 && err == io.EOF {
		err = nil
	}
	return tot, err
}

func (d *decodeReader) WriteTo(w io.Writer) (int64, error) {
	return d.writeToN(w, -1)
}

func (d *decodeReader) nextFile() (*fileBlockList, error) {
	if d.solid {
		_, err := io.Copy(io.Discard, d)
		if err != nil {
			return nil, err
		}
	}
	return d.archiveFile.nextFile()
}
