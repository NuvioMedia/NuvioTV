package nntppool

import (
	"fmt"
	"io"
	"net"
	"time"
)

const (
	defaultReadBufSize = 128 * 1024
	maxReadBufSize     = 8 * 1024 * 1024
)

type readBuffer struct {
	buf        []byte
	start, end int

	// Cached deadline to avoid redundant SetReadDeadline syscalls.
	lastDeadline    time.Time
	lastHasDeadline bool
	deadlineSet     bool // true after first SetReadDeadline call
}

func (rb *readBuffer) init() {
	if len(rb.buf) == 0 {
		rb.buf = make([]byte, defaultReadBufSize)
	}
}

func (rb *readBuffer) window() []byte {
	return rb.buf[rb.start:rb.end]
}

func (rb *readBuffer) advance(consumed int) {
	if consumed <= 0 {
		return
	}
	rb.start += consumed
	if rb.start >= rb.end {
		rb.start, rb.end = 0, 0
	}
}

func (rb *readBuffer) compact() {
	if rb.start == 0 || rb.start == rb.end {
		return
	}
	copy(rb.buf, rb.buf[rb.start:rb.end])
	rb.end -= rb.start
	rb.start = 0
}

func (rb *readBuffer) ensureWriteSpace() error {
	if rb.end < len(rb.buf) {
		return nil
	}
	if rb.start > 0 {
		rb.compact()
		if rb.end < len(rb.buf) {
			return nil
		}
	}

	// No space and cannot compact: grow.
	cur := len(rb.buf)
	if cur == 0 {
		cur = defaultReadBufSize
	}
	newLen := cur * 2
	if newLen > maxReadBufSize {
		newLen = maxReadBufSize
	}
	if newLen <= len(rb.buf) {
		return fmt.Errorf("nntp read buffer exceeded %d bytes", maxReadBufSize)
	}

	nb := make([]byte, newLen)
	copy(nb, rb.window())
	rb.end = rb.end - rb.start
	rb.start = 0
	rb.buf = nb
	return nil
}

func (rb *readBuffer) readMore(conn net.Conn, deadline time.Time, hasDeadline bool) (int, error) {
	// Only issue the syscall when the deadline actually changes.
	if !rb.deadlineSet || rb.lastHasDeadline != hasDeadline || (hasDeadline && !deadline.Equal(rb.lastDeadline)) {
		if hasDeadline {
			_ = conn.SetReadDeadline(deadline)
		} else {
			_ = conn.SetReadDeadline(time.Time{})
		}
		rb.lastDeadline = deadline
		rb.lastHasDeadline = hasDeadline
		rb.deadlineSet = true
	}
	if err := rb.ensureWriteSpace(); err != nil {
		return 0, err
	}
	n, err := conn.Read(rb.buf[rb.end:])
	if n > 0 {
		rb.end += n
	}
	return n, err
}

// feedUntilDone streams a response off the wire into out, decoding via feeder.
//
// deadline is consulted before every read; it receives wireBytes, the
// cumulative number of bytes already read from conn during this call. This
// lets callers distinguish "still waiting for the first response byte" (a
// time-to-first-byte bound) from "bytes are flowing" (a rolling progress/stall
// bound), and to detect progress even when the decoder consumes 0 bytes from a
// partial line.
func (rb *readBuffer) feedUntilDone(conn net.Conn, feeder streamFeeder, out io.Writer, deadline func(wireBytes int) (time.Time, bool)) error {
	rb.init()
	// A pipelined reply can already be in the previous read's buffer. Those
	// bytes must commit this attempt before decoding writes into a caller's
	// destination, otherwise the attempt timer can replay a published prefix.
	wireBytes := rb.end - rb.start

	for {
		// Ensure we have some bytes to feed.
		if rb.start == rb.end {
			rb.start, rb.end = 0, 0
			dl, ok := deadline(wireBytes)
			n, err := rb.readMore(conn, dl, ok)
			wireBytes += n
			if err != nil {
				return err
			}
		}

		deadline(wireBytes)
		consumed, done, err := feeder.Feed(rb.window(), out)
		if consumed > 0 {
			rb.advance(consumed)
		}
		if err != nil {
			return err
		}
		if done {
			return nil
		}

		// Need more data.
		// If decoder couldn't consume anything but we have buffered bytes,
		// compact them to the start so the next read appends contiguously.
		if consumed == 0 && (rb.end-rb.start) > 0 {
			rb.compact()
		}

		dl, ok := deadline(wireBytes)
		n, err := rb.readMore(conn, dl, ok)
		wireBytes += n
		if err != nil {
			return err
		}
	}
}
