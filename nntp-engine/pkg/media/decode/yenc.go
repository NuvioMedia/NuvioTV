package decode

import (
	"errors"
	"io"
	"regexp"
	"strconv"

	"github.com/javi11/rapidyenc"
)

var sizeMismatchRE = regexp.MustCompile(`expected size (\d+) but got (\d+)`)

type Frame struct {
	Data     []byte
	FileName string
	// FileSize is the whole decoded file's size as "=ybegin size=" declared
	// it, and PartOffset this article's exact decoded start offset within that
	// file ("=ypart begin=" - 1; 0 for a single-part post). Together they are
	// the file's segment map, written down by the poster — the loader can read
	// it instead of measuring articles and scaling. FileSize == 0 means the
	// headers carried no usable geometry.
	FileSize   int64
	PartOffset int64
}

const maxDecodeSizeTolerance = 256

// defaultDecodeSizeHint sizes the buffer when the caller has no NZB byte count
// to offer: the ~700-800 KB article the typical release posts.
const defaultDecodeSizeHint = 768 << 10

// maxDecodeOverAllocation is how much unused capacity a decoded frame may keep.
// The segment cache budget tracks len() while the heap retains cap(), so a
// frame that over-allocated more than this is copied to an exactly-sized slice
// before it can pin memory the budget never sees. An accurate hint (the NZB's
// encoded size, ~2-3% above the decoded payload) stays well under it.
const maxDecodeOverAllocation = 64 << 10

// DecodeToBytes decodes one yEnc article body.
//
// r must yield the article in canonical NNTP wire form: CRLF line endings, dot
// stuffing still in place, no terminator. That is exactly what
// nntp.Client.Body returns, and it is what rapidyenc expects — it does its own
// dot-unstuffing and needs CRLF to find line boundaries, the "=y" control and
// the "=yend" trailer. Un-stuffing before this point drops one real byte per
// line that legitimately begins with '.' (yEnc encodes data byte 0x04 to '.').
func DecodeToBytes(r io.Reader) (*Frame, error) {
	return DecodeToBytesSized(r, 0)
}

// DecodeToBytesSized is DecodeToBytes with a capacity hint: the article's
// encoded size as the NZB declares it. yEnc encoding only ever expands, so the
// encoded size bounds the decoded payload and the whole article decodes into
// one allocation — the grow-and-clone path a hintless bytes.Buffer paid cost a
// third of the decode CPU and allocated 3.7x the payload per article. The
// decoder also scratches the raw wire bytes through the same buffer, which is
// one more reason the ENCODED size is the right capacity. Zero means unknown
// and falls back to a typical article size.
func DecodeToBytesSized(r io.Reader, sizeHint int64) (*Frame, error) {
	dec := rapidyenc.NewDecoder(r)
	if sizeHint <= 0 {
		sizeHint = defaultDecodeSizeHint
	}
	buf := make([]byte, sizeHint)
	n := 0
	var err error
	for {
		// The decoder needs room for a raw read plus its undecoded remainder;
		// starving it below that stalls in-place decoding. Only a hint smaller
		// than the actual article ever grows.
		if len(buf)-n < 1024 {
			grown := make([]byte, len(buf)*2)
			copy(grown, buf[:n])
			buf = grown
		}
		var m int
		m, err = dec.Read(buf[n:])
		n += m
		if err != nil {
			break
		}
	}
	if errors.Is(err, io.EOF) {
		return frameWithMeta(dec, buf, n), nil
	}
	if sub := sizeMismatchRE.FindStringSubmatch(err.Error()); len(sub) == 3 {
		expected, _ := strconv.ParseInt(sub[1], 10, 64)
		got, _ := strconv.ParseInt(sub[2], 10, 64)
		shortfall := expected - got
		if shortfall > 0 && shortfall <= maxDecodeSizeTolerance && int64(n) == got {
			// Keep the actually-decoded bytes. The =yend "size" is frequently a
			// nominal/rounded value the poster wrote (e.g. 768000) while the real
			// payload is a few bytes smaller; the decoded bytes are the true file
			// content. Padding up to the declared size would splice phantom bytes
			// at every segment boundary and corrupt the concatenated archive.
			return frameWithMeta(dec, buf, n), nil
		}
	}
	return nil, err
}

// frameWithMeta builds the decoded frame plus the yEnc header geometry. A
// multipart article only ever decodes data after its "=ypart" line was parsed,
// so a successful decode means Meta.Offset is the real part offset; a
// single-part post has no "=ypart" and its offset is legitimately zero.
func frameWithMeta(dec *rapidyenc.Decoder, buf []byte, n int) *Frame {
	return &Frame{
		Data:       trimExact(buf, n),
		FileName:   dec.Meta.FileName,
		FileSize:   dec.Meta.FileSize,
		PartOffset: dec.Meta.Offset,
	}
}

// trimExact returns buf's first n bytes with at most maxDecodeOverAllocation
// of spare capacity, copying to an exactly-sized slice when the backing array
// over-allocated past that.
func trimExact(buf []byte, n int) []byte {
	if n == 0 {
		return nil
	}
	if cap(buf)-n <= maxDecodeOverAllocation {
		return buf[:n:n]
	}
	out := make([]byte, n)
	copy(out, buf[:n])
	return out
}
