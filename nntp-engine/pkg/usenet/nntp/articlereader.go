package nntp

import (
	"bufio"
	"bytes"
	"errors"
	"io"
)

// articleReader streams a dot-terminated NNTP multi-line body in canonical wire
// form and nothing else: CRLF line endings, dot-stuffing left INTACT, and the
// terminating "." line consumed but not emitted.
//
// It replaces the textproto.DotReader the body path used to hand to the yEnc
// decoder, which cost three passes over every article: DotReader un-stuffed it
// with a per-byte state machine (5.1 s CPU/GB on its own), a second per-byte
// pass rewrote bare-LF to CRLF and re-escaped the very dots DotReader had just
// removed, and the decoded output was copied twice more — 8.5 s CPU/GB and
// 3.8 MB of allocation per 768 KB article, of which the SIMD decode itself was
// 0.7 s/GB. Handing the decoder the untouched wire removes all of it: this
// reader hops line to line with bytes.IndexByte and copies whole runs of
// already-canonical lines in one memmove, so the common all-CRLF article costs
// one scan and one copy per buffer fill.
//
// Not un-stuffing is the point, not an oversight. rapidyenc expects a raw,
// still-stuffed article and does its own un-stuffing; a yEnc data byte 0x04
// encodes to '.', so a line-leading data dot removed here and removed again by
// the decoder silently drops one real byte per such line.
//
// Bare-LF articles — which some posters emit and the decoder cannot parse,
// since it needs CRLF to find line boundaries, the "=y" control and the "=yend"
// trailer — are repaired to CRLF as they stream past. That is protocol
// normalization, not decoder knowledge: NNTP mandates CRLF, and this is the
// layer that owns what came off the socket.
type articleReader struct {
	br *bufio.Reader

	// lastOut is the last byte handed to the caller. It is the only state this
	// reader needs and it starts at '\n', because a body begins at a line
	// start: "at a line start" is exactly "the previous emitted byte was '\n'",
	// and "lone LF needing repair" is exactly "the byte before this '\n' was
	// not '\r'".
	lastOut byte

	// need is how many bytes must be buffered before the next step can tell a
	// line-leading '.' that opens the terminator from one that is stuffed data.
	need int

	// pend holds a '\n' that had to be split from the '\r' inserted ahead of
	// it because the caller's buffer had exactly one byte of room left.
	pend  byte
	pendN int

	done bool
	err  error
}

func newArticleReader(br *bufio.Reader) *articleReader {
	return &articleReader{br: br, lastOut: '\n'}
}

func (a *articleReader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if a.pendN > 0 {
		p[0] = a.pend
		a.pendN = 0
		a.lastOut = p[0]
		return 1, nil
	}
	if a.err != nil {
		return 0, a.err
	}
	if a.done {
		return 0, io.EOF
	}
	for {
		n, err := a.step(p)
		if n > 0 {
			return n, nil
		}
		if err != nil {
			a.err = err
			return 0, err
		}
		if a.done {
			return 0, io.EOF
		}
	}
}

// step processes one bufio fill worth of body bytes into p. It may return
// (0, nil), meaning "no progress yet, call again" — the caller loops.
func (a *articleReader) step(p []byte) (int, error) {
	want := a.need
	if want < 1 {
		want = 1
	}
	a.need = 0

	buf, peekErr := a.br.Peek(want)
	if peekErr == nil {
		if n := a.br.Buffered(); n > len(buf) {
			buf, _ = a.br.Peek(n)
		}
	}
	if len(buf) == 0 {
		if errors.Is(peekErr, io.EOF) {
			// The body ended without its "." line: the article is truncated.
			return 0, io.ErrUnexpectedEOF
		}
		return 0, peekErr
	}
	atEOF := peekErr != nil

	out, consumed, run, i := 0, 0, 0, 0

	// flush copies the pending verbatim run buf[run:end] into p. It reports
	// false when p filled up first, leaving the rest of the run for a later
	// call.
	flush := func(end int) bool {
		if end <= run {
			return true
		}
		span := buf[run:end]
		if room := len(p) - out; len(span) > room {
			span = span[:room]
		}
		copy(p[out:], span)
		out += len(span)
		run += len(span)
		consumed = run
		return run == end
	}

	undecided := false
	for i < len(buf) {
		if out >= len(p) {
			break
		}

		prev := a.lastOut
		if i > 0 {
			prev = buf[i-1]
		}

		if prev == '\n' && buf[i] == '.' {
			// Either the terminator line or a stuffed data line; deciding needs
			// the next byte, and the byte after that for the CRLF form.
			if i+1 >= len(buf) {
				undecided = true
				break
			}
			if buf[i+1] == '\n' {
				if !flush(i) {
					break
				}
				consumed = i + 2
				a.done = true
				break
			}
			if buf[i+1] == '\r' {
				if i+2 >= len(buf) {
					undecided = true
					break
				}
				if buf[i+2] == '\n' {
					if !flush(i) {
						break
					}
					consumed = i + 3
					a.done = true
					break
				}
			}
			// Stuffed data line: stays in the run, untouched.
		}

		j := bytes.IndexByte(buf[i:], '\n')
		if j < 0 {
			// Line continues past this fill; the whole tail is verbatim.
			i = len(buf)
			break
		}
		nl := i + j

		prevNL := a.lastOut
		if nl > 0 {
			prevNL = buf[nl-1]
		}
		if prevNL == '\r' {
			i = nl + 1
			continue
		}

		// Lone LF: emit the run up to it, then a proper CRLF.
		if !flush(nl) {
			break
		}
		room := len(p) - out
		if room == 0 {
			// No room left for the repair; the run is flushed, so the LF stays
			// unconsumed and is handled on the next call.
			break
		}
		if room >= 2 {
			p[out], p[out+1] = '\r', '\n'
			out += 2
		} else {
			p[out] = '\r'
			out++
			a.pend, a.pendN = '\n', 1
		}
		consumed = nl + 1
		run = consumed
		i = consumed
		if a.pendN > 0 {
			break
		}
	}

	if !a.done {
		flush(i)
		if undecided && consumed == i {
			a.need = 3
		}
	}
	if consumed > 0 {
		_, _ = a.br.Discard(consumed)
	}
	if out > 0 {
		a.lastOut = p[out-1]
	}
	if out == 0 && consumed == 0 && !a.done && atEOF {
		return 0, io.ErrUnexpectedEOF
	}
	return out, nil
}
