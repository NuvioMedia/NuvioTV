package nntppool

import (
	"bytes"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

type commitCheckingFeeder struct{ committed *bool }

func (f commitCheckingFeeder) Feed(in []byte, out io.Writer) (int, bool, error) {
	if !*f.committed {
		return 0, false, errors.New("caller writer observed bytes before the buffered attempt committed")
	}
	n, e := out.Write(in)
	return n, true, e
}

// Regression for a later pipelined reply already present in the read slab.
// Before this fix the attempt timer could abandon/replay it while Feed wrote
// a decoded prefix, since progress was only observed on the next socket read.
func TestBufferedReplyCommitsBeforeWriting(t *testing.T) {
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()
	payload := []byte("already buffered reply")
	rb := readBuffer{buf: payload, end: len(payload)}
	committed := false
	var output bytes.Buffer
	e := rb.feedUntilDone(conn, commitCheckingFeeder{&committed}, &output, func(n int) (time.Time, bool) {
		if n > 0 {
			committed = true
		}
		return time.Time{}, false
	})
	if e != nil {
		t.Fatal(e)
	}
	if !bytes.Equal(output.Bytes(), payload) {
		t.Fatal("buffered response changed")
	}
}
