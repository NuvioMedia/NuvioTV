package nntp

import (
	"bufio"
	"bytes"
	"io"
	"math/rand"
	"strings"
	"testing"
)

// readAll drains an articleReader over wire, using the given bufio size and
// per-Read buffer size, and returns what it emitted plus what was left
// unconsumed on the underlying reader (which must be exactly the bytes that
// follow the terminator — the connection has to stay in sync for the next
// command).
func readAll(t testing.TB, wire string, bufSize, readSize int) (out string, rest string, err error) {
	t.Helper()
	br := bufio.NewReaderSize(strings.NewReader(wire), bufSize)
	a := newArticleReader(br)

	var buf bytes.Buffer
	p := make([]byte, readSize)
	for {
		n, rerr := a.Read(p)
		buf.Write(p[:n])
		if rerr != nil {
			if rerr == io.EOF {
				break
			}
			leftover, _ := io.ReadAll(br)
			return buf.String(), string(leftover), rerr
		}
	}
	leftover, _ := io.ReadAll(br)
	return buf.String(), string(leftover), nil
}

func TestArticleReaderCanonicalizes(t *testing.T) {
	cases := []struct {
		name string
		wire string
		want string
		rest string
	}{
		{
			name: "crlf body passes through untouched",
			wire: "=ybegin line=128\r\nabcdef\r\n=yend\r\n.\r\n",
			want: "=ybegin line=128\r\nabcdef\r\n=yend\r\n",
		},
		{
			name: "bare LF is repaired to CRLF",
			wire: "=ybegin line=128\nabcdef\n=yend\n.\n",
			want: "=ybegin line=128\r\nabcdef\r\n=yend\r\n",
		},
		{
			name: "mixed line endings",
			wire: "one\r\ntwo\nthree\r\n.\r\n",
			want: "one\r\ntwo\r\nthree\r\n",
		},
		{
			name: "dot stuffing is preserved, not removed",
			wire: "..leading dot\r\n...two dots\r\n.\r\n",
			want: "..leading dot\r\n...two dots\r\n",
		},
		{
			name: "stuffed dot line with bare LF",
			wire: "..leading dot\n.\n",
			want: "..leading dot\r\n",
		},
		{
			name: "empty body",
			wire: ".\r\n",
			want: "",
		},
		{
			name: "dot inside a line is ordinary data",
			wire: "a.b.c\r\n.\r\n",
			want: "a.b.c\r\n",
		},
		{
			name: "terminator leaves the next response untouched",
			wire: "body\r\n.\r\n222 0 <next>\r\n",
			want: "body\r\n",
			rest: "222 0 <next>\r\n",
		},
		{
			name: "bare-LF terminator leaves the next response untouched",
			wire: "body\n.\n222 0 <next>\r\n",
			want: "body\r\n",
			rest: "222 0 <next>\r\n",
		},
		{
			name: "line without trailing newline before terminator",
			wire: "body\r\n.\r\n",
			want: "body\r\n",
		},
	}

	// Buffer and read sizes chosen to land splits inside line endings, inside
	// the terminator, and mid-run.
	bufSizes := []int{16, 17, 64, 4096}
	readSizes := []int{1, 2, 3, 5, 64, 4096}

	for _, tc := range cases {
		for _, bufSize := range bufSizes {
			for _, readSize := range readSizes {
				got, rest, err := readAll(t, tc.wire, bufSize, readSize)
				if err != nil {
					t.Fatalf("%s (buf=%d read=%d): %v", tc.name, bufSize, readSize, err)
				}
				if got != tc.want {
					t.Errorf("%s (buf=%d read=%d):\n got %q\nwant %q", tc.name, bufSize, readSize, got, tc.want)
				}
				if rest != tc.rest {
					t.Errorf("%s (buf=%d read=%d): leftover %q, want %q", tc.name, bufSize, readSize, rest, tc.rest)
				}
			}
		}
	}
}

// TestArticleReaderTruncated covers a provider that drops the connection
// mid-body: the caller must see an error, never a clean EOF that would look
// like a complete (but short) article.
func TestArticleReaderTruncated(t *testing.T) {
	for _, wire := range []string{"body\r\nmore", "body\r\n", "body\r\n.", "body\r\n.\r"} {
		for _, bufSize := range []int{8, 16, 4096} {
			_, _, err := readAll(t, wire, bufSize, 64)
			if err != io.ErrUnexpectedEOF {
				t.Errorf("wire %q (buf=%d): err = %v, want ErrUnexpectedEOF", wire, bufSize, err)
			}
		}
	}
}

// TestArticleReaderBareLFMatchesCRLF is the contract in one assertion: a
// bare-LF article and its CRLF twin must canonicalize to identical bytes, at
// every buffer/read split. This is the coverage that used to live in the decode
// package as "bare LF must not lose bytes" — bare-LF repair moved here, so the
// regression test did too.
func TestArticleReaderBareLFMatchesCRLF(t *testing.T) {
	rng := rand.New(rand.NewSource(198))
	var crlf bytes.Buffer
	for i := 0; i < 400; i++ {
		line := make([]byte, 1+rng.Intn(130))
		for j := range line {
			// Printable bytes plus a deliberate share of line-leading dots.
			line[j] = byte(33 + rng.Intn(90))
		}
		if i%17 == 0 {
			line[0] = '.' // as stuffed on the wire
			line = append([]byte{'.'}, line...)
		}
		crlf.Write(line)
		crlf.WriteString("\r\n")
	}
	crlfWire := crlf.String() + ".\r\n"
	bareWire := strings.ReplaceAll(crlfWire, "\r\n", "\n")

	for _, bufSize := range []int{16, 64, 512, 4096} {
		for _, readSize := range []int{1, 3, 100, 4096} {
			gotCRLF, _, err := readAll(t, crlfWire, bufSize, readSize)
			if err != nil {
				t.Fatalf("crlf (buf=%d read=%d): %v", bufSize, readSize, err)
			}
			gotBare, _, err := readAll(t, bareWire, bufSize, readSize)
			if err != nil {
				t.Fatalf("bare (buf=%d read=%d): %v", bufSize, readSize, err)
			}
			if gotCRLF != gotBare {
				t.Fatalf("buf=%d read=%d: bare-LF output differs from CRLF output (%d vs %d bytes)",
					bufSize, readSize, len(gotBare), len(gotCRLF))
			}
			if gotCRLF != crlf.String() {
				t.Fatalf("buf=%d read=%d: CRLF article was modified in transit", bufSize, readSize)
			}
		}
	}
}
