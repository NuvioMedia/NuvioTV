package rardecode

import (
	"crypto/aes"
	"crypto/cipher"
	"io"
)

type cipherBlockReader struct {
	br     byteReader
	mode   cipher.BlockMode
	inbuf  []byte
	outbuf []byte
	block  []byte
}

func (cr *cipherBlockReader) fillOutbuf() error {
	l := len(cr.inbuf)
	_, err := io.ReadFull(cr.br, cr.block[l:])
	if err != nil {
		return err
	}
	cr.mode.CryptBlocks(cr.block, cr.block)
	cr.outbuf = cr.block
	return nil
}

func (cr *cipherBlockReader) ReadByte() (byte, error) {
	if len(cr.outbuf) == 0 {
		err := cr.fillOutbuf()
		if err != nil {
			return 0, err
		}
	}
	b := cr.outbuf[0]
	cr.outbuf = cr.outbuf[1:]
	return b, nil
}

func (cr *cipherBlockReader) Read(p []byte) (int, error) {
	var n int
	if len(cr.outbuf) > 0 {
		n = copy(p, cr.outbuf)
		cr.outbuf = cr.outbuf[n:]
		return n, nil
	}
	blockSize := cr.mode.BlockSize()
	if len(p) < blockSize {

		err := cr.fillOutbuf()
		if err != nil {
			return 0, err
		}
		n = copy(p, cr.outbuf)
		cr.outbuf = cr.outbuf[n:]
		return n, nil
	}

	p = p[:len(p)-(len(p)%blockSize)]
	l := len(cr.inbuf)
	if l > 0 {
		copy(p, cr.inbuf)
		cr.inbuf = nil
	}
	n, err := io.ReadAtLeast(cr.br, p[l:], blockSize-l)
	if err != nil {
		return 0, err
	}
	n += l
	p = p[:n]
	n -= n % blockSize
	if n != len(p) {
		l = copy(cr.block, p[n:])
		cr.inbuf = cr.block[:l]
		p = p[:n]
	}
	cr.mode.CryptBlocks(p, p)
	return n, nil
}

func (cr *cipherBlockReader) writeToN(w io.Writer, n int64) (int64, error) {
	if n == 0 {
		return 0, nil
	}
	var tot int64
	var err error
	for tot != n && err == nil {
		if len(cr.outbuf) == 0 {
			err = cr.fillOutbuf()
			if err != nil {
				break
			}
		}
		buf := cr.outbuf
		if n > 0 {
			todo := n - tot
			if todo < int64(len(buf)) {
				buf = buf[:todo]
			}
		}
		var l int
		l, err = w.Write(buf)
		tot += int64(l)
		cr.outbuf = cr.outbuf[l:]
	}
	if n < 0 && err == io.EOF {
		err = nil
	}
	return tot, err
}

func (cr *cipherBlockReader) WriteTo(w io.Writer) (int64, error) {
	return cr.writeToN(w, -1)
}

func newCipherBlockReader(r byteReader, mode cipher.BlockMode) *cipherBlockReader {
	return &cipherBlockReader{
		br:    r,
		mode:  mode,
		block: make([]byte, mode.BlockSize()),
	}
}

func newAesDecryptReader(r byteReader, key, iv []byte) (*cipherBlockReader, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	mode := cipher.NewCBCDecrypter(block, iv)
	return newCipherBlockReader(r, mode), nil
}

type cipherBlockFileReader struct {
	archiveFile
	cbr *cipherBlockReader
}

func (cr *cipherBlockFileReader) ReadByte() (byte, error) {
	return cr.cbr.ReadByte()
}

func (cr *cipherBlockFileReader) Read(p []byte) (int, error) {
	return cr.cbr.Read(p)
}

func (cr *cipherBlockFileReader) WriteTo(w io.Writer) (int64, error) {
	return cr.cbr.WriteTo(w)
}

func (cr *cipherBlockFileReader) writeToN(w io.Writer, n int64) (int64, error) {
	return cr.cbr.writeToN(w, n)
}

func newAesDecryptFileReader(r archiveFile, key, iv []byte) (*cipherBlockFileReader, error) {
	cbr, err := newAesDecryptReader(r, key, iv)
	if err != nil {
		return nil, err
	}
	return &cipherBlockFileReader{archiveFile: r, cbr: cbr}, nil
}
