package rapidyenc

/*
#include "rapidyenc.h"

// Like `rapidyenc_decode_incremental` but handle the pointer arithmetic
RapidYencDecoderEnd rapidyenc_decode_incremental_go(const void* src, void* dest, size_t src_length, size_t* n_src, size_t* n_dest, RapidYencDecoderState* state) {
    const void* in_ptr = src;
    void* out_ptr = dest;

    RapidYencDecoderEnd ended = rapidyenc_decode_incremental(&in_ptr, &out_ptr, src_length, state);
    *n_src = (uintptr_t)in_ptr - (uintptr_t)src;
    *n_dest = (uintptr_t)out_ptr - (uintptr_t)dest;

	return ended;
}
*/
import "C"
import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"hash/crc32"
	"io"
	"strconv"
	"sync"
	"unsafe"
)

type Decoder struct {
	r    io.Reader
	Meta DecodedMeta

	body  bool
	begin bool
	part  bool
	end   bool
	crc   bool

	State       State
	format      Format
	actualSize  int64
	expectedCrc uint32
	hash        hash.Hash32

	err error

	// remainder contains bytes that have been read from r but were not sufficient to copy out via Read
	remainder []byte
}

func NewDecoder(r io.Reader) *Decoder {
	return &Decoder{
		r:    r,
		hash: crc32.NewIEEE(),
	}
}

var (
	ErrDataMissing    = errors.New("no binary data")
	ErrDataCorruption = errors.New("data corruption detected") // io.EOF or ".\r\n" reached before =yend
	ErrCrcMismatch    = errors.New("crc32 mismatch")
	ErrUU             = errors.New("data is uuencoded")
)

func (d *Decoder) Read(p []byte) (int, error) {
	if d.err != nil {
		return 0, d.metaError()
	}

	// Restore previously read data
	if len(p) < len(d.remainder) {
		return 0, fmt.Errorf("[rapidyenc] remainder larger than reader: %d < %d", len(p), len(d.remainder))
	}
	nremainder := copy(p, d.remainder)
	d.remainder = d.remainder[nremainder:]

	// Use p as scratch space
	var n int
	n, d.err = d.r.Read(p[nremainder:])
	p = p[:n+nremainder]
	dst := p

	var decoded int
	if d.body && d.format == FormatYenc {
		nd, ns, err := d.decodeYenc(dst, p)
		if err != nil {
			return nd, err
		}
		p = p[ns:]
		dst = dst[nd:]
		decoded += nd
	}

	// Line by line processing
	if !d.body {
		for {
			line, after, found := bytes.Cut(p, []byte("\r\n"))
			if !found {
				break
			}
			p = after

			if bytes.Equal(line, []byte(".")) {
				d.err = io.EOF
				break
			}

			if d.format == FormatUnknown {
				d.format = detectFormat(line)
			}

			if d.format == FormatYenc {
				d.processYenc(line)
				if d.body {
					nd, ns, err := d.decodeYenc(dst, p)
					p = p[ns:]
					dst = dst[nd:]
					decoded += nd
					if err != nil {
						return decoded, err
					}
					if d.body {
						break
					}
				}
			} else if d.format == FormatUU {
				// TODO: does not uudecode, for now just copies encoded data
				copy(dst, line)
				copy(dst[len(line):], "\r\n")
				nd := len(line) + 2
				dst = dst[nd:]
				decoded += nd
			}
		}
	}

	// Save remainder; small amount of data that doesn't have \r\n
	d.remainder = append(d.remainder, p...)

	if d.err == io.EOF {
		return decoded, d.metaError()
	}

	return decoded, nil
}

func (d *Decoder) metaError() error {
	if len(d.remainder) > 0 {
		return io.ErrUnexpectedEOF
	}
	if d.format == FormatUU {
		return ErrUU
	}
	if !d.begin {
		return fmt.Errorf("[rapidyenc] end of article without finding \"=ybegin\" header: %w", ErrDataMissing)
	}
	if !d.end {
		return fmt.Errorf("[rapidyenc] end of article without finding \"=yend\" trailer: %w", ErrDataCorruption)
	}
	if (!d.part && d.Meta.FileSize != d.actualSize) || (d.part && d.Meta.PartSize != d.actualSize) {
		return fmt.Errorf("[rapidyenc] expected size %d but got %d: %w", d.Meta.PartSize, d.actualSize, ErrDataCorruption)
	}
	if d.crc && d.expectedCrc != d.Meta.Hash {
		return fmt.Errorf("[rapidyenc] expected decoded data to have CRC32 hash %#08x but got %#08x: %w", d.expectedCrc, d.Meta.Hash, ErrCrcMismatch)
	}
	return io.EOF
}

func (d *Decoder) decodeYenc(dst, src []byte) (int, int, error) {
	nd, ns, end, err := DecodeIncremental(dst, src, &d.State)
	if err != nil {
		return 0, 0, fmt.Errorf("[rapidyenc] failed to decode incremental data: %w", err)
	}

	if _, err := d.hash.Write(dst[:nd]); err != nil {
		return 0, 0, fmt.Errorf("[rapidyenc] failed to hash data: %w", err)
	}
	d.actualSize += int64(nd)

	if end == EndControl {
		d.body = false
		return nd, ns - 2, nil
	}

	if end == EndArticle {
		d.body = false
		return nd, ns - 3, io.EOF
	}

	if d.State == StateCRLFEQ {
		// Special case: found "\r\n=" but no more data - might be start of =yend
		d.State = StateCRLF
		return nd, ns - 1, nil
	}

	return nd, ns, nil
}

func (d *Decoder) processYenc(line []byte) {
	var err error
	if bytes.HasPrefix(line, []byte("=ybegin ")) {
		d.begin = true
		d.Meta.FileSize, _ = extractInt(line, []byte(" size="))
		d.Meta.FileName, _ = extractString(line, []byte(" name="))
		if d.Meta.PartNumber, err = extractInt(line, []byte(" part=")); err != nil {
			d.body = true
			d.Meta.PartSize = d.Meta.FileSize
		}
		d.Meta.TotalParts, _ = extractInt(line, []byte(" total="))
	} else if bytes.HasPrefix(line, []byte("=ypart ")) {
		d.part = true
		d.body = true
		var begin int64
		if begin, err = extractInt(line, []byte(" begin=")); err == nil {
			d.Meta.Offset = begin - 1
		}
		if end, err := extractInt(line, []byte(" end=")); err == nil && begin > 0 {
			d.Meta.PartSize = end - d.Meta.Offset
		}
	} else if bytes.HasPrefix(line, []byte("=yend ")) {
		d.end = true
		if d.part {
			if crc, err := extractCRC(line, []byte(" pcrc32=")); err == nil {
				d.expectedCrc = crc
				d.crc = true
			}
		} else if crc, err := extractCRC(line, []byte(" crc32=")); err == nil {
			d.expectedCrc = crc
			d.crc = true
		}
		d.Meta.PartSize, _ = extractInt(line, []byte(" size="))
		d.Meta.Hash = d.hash.Sum32()
	}
}

func detectFormat(line []byte) Format {
	if bytes.HasPrefix(line, []byte("=ybegin ")) {
		return FormatYenc
	}

	length := len(line)
	if (length == 60 || length == 61) && line[0] == 'M' {
		return FormatUU
	}

	if bytes.HasPrefix(line, []byte("begin ")) {
		ok := true
		line := bytes.TrimSpace(line[len("begin "):])

		for pos := 0; pos < len(line); pos++ {
			char := line[pos]
			if char == ' ' {
				break
			}

			if char < '0' || char > '7' {
				ok = false
				break
			}
		}
		if ok {
			return FormatUU
		}
	}

	return FormatUnknown
}

type Format int

const (
	FormatUnknown Format = iota
	FormatYenc
	FormatUU
)

// State is the current Decoder State, the values refer to the previously seen
// characters in the stream, which influence how some sequences need to be handled.
//
// The shorthands represent:
// CR (\r), LF (\n), EQ (=), DT (.)
type State int

const (
	StateCRLF     = State(C.RYDEC_STATE_CRLF)
	StateEQ       = State(C.RYDEC_STATE_EQ)
	StateCR       = State(C.RYDEC_STATE_CR)
	StateNone     = State(C.RYDEC_STATE_NONE)
	StateCRLFDT   = State(C.RYDEC_STATE_CRLFDT)
	StateCRLFDTCR = State(C.RYDEC_STATE_CRLFDTCR)
	StateCRLFEQ   = State(C.RYDEC_STATE_CRLFEQ) // may actually be "\r\n.=" in raw Decoder
)

// End is the State for incremental decoding, whether the end of the yEnc data was reached
type End int

const (
	EndNone    = End(C.RYDEC_END_NONE)    // end not reached
	EndControl = End(C.RYDEC_END_CONTROL) // \r\n=y sequence found, src points to byte after 'y'
	EndArticle = End(C.RYDEC_END_ARTICLE) // \r\n.\r\n sequence found, src points to byte after last '\n'
)

var (
	errDestinationTooSmall = errors.New("destination must be at least the length of source")
)

var decodeInitOnce sync.Once

func maybeInitDecode() {
	decodeInitOnce.Do(func() {
		C.rapidyenc_decode_init()
	})
}

// DecodeIncremental stops decoding when a yEnc/NNTP end sequence is found
func DecodeIncremental(dst, src []byte, state *State) (nDst, nSrc int, end End, err error) {
	maybeInitDecode()

	if len(src) == 0 {
		return 0, 0, EndNone, nil
	}

	if len(dst) < len(src) {
		return 0, 0, 0, errDestinationTooSmall
	}

	var cnSrc, cnDest C.size_t

	result := End(C.rapidyenc_decode_incremental_go(
		unsafe.Pointer(&src[0]),
		unsafe.Pointer(&dst[0]),
		C.size_t(len(src)),
		&cnSrc,
		&cnDest,
		(*C.RapidYencDecoderState)(unsafe.Pointer(state)),
	))

	return int(cnDest), int(cnSrc), result, nil
}

func extractString(data, substr []byte) (string, error) {
	start := bytes.Index(data, substr)
	if start == -1 {
		return "", fmt.Errorf("substr not found: %s", substr)
	}

	data = data[start+len(substr):]
	if end := bytes.IndexAny(data, "\x00\r\n"); end != -1 {
		return string(data[:end]), nil
	}

	return string(data), nil
}

func extractInt(data, substr []byte) (int64, error) {
	start := bytes.Index(data, substr)
	if start == -1 {
		return 0, fmt.Errorf("substr not found: %s", substr)
	}

	data = data[start+len(substr):]
	if end := bytes.IndexAny(data, "\x00\x20\r\n"); end != -1 {
		return strconv.ParseInt(string(data[:end]), 10, 64)
	}

	return strconv.ParseInt(string(data), 10, 64)
}

var (
	errCrcNotfound = errors.New("crc not found")
)

// extractCRC converts a hexadecimal representation of a crc32 hash
func extractCRC(data, substr []byte) (uint32, error) {
	start := bytes.Index(data, substr)
	if start == -1 {
		return 0, errCrcNotfound
	}

	data = data[start+len(substr):]
	end := bytes.IndexAny(data, "\x00\x20\r\n")
	if end != -1 {
		data = data[:end]
	}

	// Take up to the last 8 characters
	parsed := data[len(data)-min(8, len(data)):]

	// Left pad unexpected length with 0
	if len(parsed) != 8 {
		padded := []byte("00000000")
		copy(padded[8-len(parsed):], parsed)
		parsed = padded
	}

	_, err := hex.Decode(parsed, parsed)
	return binary.BigEndian.Uint32(parsed), err
}
