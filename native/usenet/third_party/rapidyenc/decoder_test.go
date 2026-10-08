package rapidyenc

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"fmt"
	"io"
	"os"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestDecode(t *testing.T) {
	space := bytes.Repeat([]byte(" "), 800000)

	cases := []struct {
		name string
		raw  string
		crc  uint32
	}{
		{"foobar", "foobar", 0x9EF61F95},
		{"0x20", string(space), 0x31f365e7},
		{"special", "\x04\x04\x04\x04", 0xca2ee18a},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw := []byte(tc.raw)

			encoded, err := body(raw)
			require.NoError(t, err)

			dec := NewDecoder(encoded)
			b := bytes.NewBuffer(nil)
			n, err := io.Copy(b, dec)
			require.Equal(t, int64(len(raw)), n)
			require.NoError(t, err)
			require.Equal(t, raw, b.Bytes())
			require.Equal(t, tc.crc, dec.Meta.Hash)
			require.Equal(t, int64(len(raw)), dec.Meta.End())
		})
	}
}

func TestDecodeUU(t *testing.T) {
	cases := []struct {
		name string
		path string
	}{
		{"logo_full", "testdata/logo_full.uu"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f, err := os.Open(tc.path)
			require.NoError(t, err)
			defer f.Close()

			raw, err := io.ReadAll(f)
			require.NoError(t, err)

			dec := NewDecoder(bytes.NewReader(raw))
			b := bytes.NewBuffer(nil)
			_, err = io.Copy(b, dec)
			require.Error(t, err, ErrUU)
			require.Equal(t, raw, b.Bytes()) // uudecode is not implemented; just test it is unchanged
		})
	}
}

// TestSplitReads splits "=y" header lines across reads
func TestSplitReads(t *testing.T) {
	cases := []struct {
		name string
		raw  string
	}{
		{"foobar", "foobar"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw := []byte(tc.raw)

			encoded, err := body(raw)
			require.NoError(t, err)

			r, w := io.Pipe()

			go func() {
				scanner := bufio.NewScanner(encoded)
				scanner.Split(func(data []byte, atEOF bool) (advance int, token []byte, err error) {
					if bytes.Equal(data[:2], []byte("=y")) {
						return 1, []byte("="), nil
					}

					if line := bytes.Index(data, []byte("\r\n")); line != -1 {
						return line + 2, data[:line+2], nil
					}

					if atEOF {
						return 0, nil, io.EOF
					}

					return 0, nil, nil
				})

				for scanner.Scan() {
					if _, err := w.Write(scanner.Bytes()); err != nil {
						panic(err)
					}
				}

				if err := w.Close(); err != nil {
					panic(err)
				}
			}()

			dec := NewDecoder(r)
			b := bytes.NewBuffer(nil)
			n, err := io.Copy(b, dec)
			require.Equal(t, int64(len(raw)), n)
			require.NoError(t, err)
			require.Equal(t, raw, b.Bytes())
			require.Equal(t, int64(len(raw)), dec.Meta.End())
		})
	}
}

func BenchmarkDecoder(b *testing.B) {
	raw := make([]byte, 1024*1024)
	_, err := rand.Read(raw)
	require.NoError(b, err)

	r, err := body(raw)
	require.NoError(b, err)

	b.ResetTimer()
	for b.Loop() {
		dec := NewDecoder(r)
		_, err = io.Copy(io.Discard, dec)
		require.NoError(b, err)
		_, err = r.Seek(0, io.SeekStart)
		require.NoError(b, err)
	}
}

func body(raw []byte) (io.ReadSeeker, error) {
	w := new(bytes.Buffer)

	enc, err := NewEncoder(w, Meta{
		FileName:   "filename",
		FileSize:   int64(len(raw)),
		PartSize:   int64(len(raw)),
		PartNumber: 1,
		TotalParts: 1,
	})
	if err != nil {
		return nil, err
	}

	if _, err := io.Copy(enc, bytes.NewReader(raw)); err != nil {
		return nil, err
	}
	if err := enc.Close(); err != nil {
		return nil, err
	}

	return bytes.NewReader(w.Bytes()), nil
}

func TestExtractString(t *testing.T) {
	cases := []struct {
		raw      string
		expected string
	}{
		{"", ""},
		{"foo", "foo"},
		{"name=bar", "name=bar"},
		{"foo bar", "foo bar"},
		{"before\x00after", "before"},
	}

	for _, tc := range cases {
		t.Run(tc.raw, func(t *testing.T) {
			b := []byte(fmt.Sprintf("=ybegin part=1 line=128 size=128 name=%s\r\n", tc.raw))
			i, err := extractString(b, []byte(" name="))
			require.NoError(t, err)
			require.Equal(t, tc.expected, i)
		})
	}
}

func TestExtractCRC(t *testing.T) {
	cases := []struct {
		raw      string
		expected uint32
	}{
		{"ffffffffa95d3e50", 0xa95d3e50},
		{"fffffffa95d3e50", 0xa95d3e50},
		{"ffffffa95d3e50", 0xa95d3e50},
		{"fffffa95d3e50", 0xa95d3e50},
		{"ffffa95d3e50", 0xa95d3e50},
		{"fffa95d3e50", 0xa95d3e50},
		{"ffa95d3e50", 0xa95d3e50},
		{"fa95d3e50", 0xa95d3e50},
		{"a95d3e50", 0xa95d3e50},
		{"a95d3e5", 0xa95d3e5},
		{"a95d3e", 0xa95d3e},
		{"a95d3", 0xa95d3},
		{"a95d", 0xa95d},
		{"a95", 0xa95},
		{"a9", 0xa9},
		{"a", 0xa},
		{"", 0},
		{"12345678 ", 0x12345678}, // space at end
	}

	for _, tc := range cases {
		t.Run(tc.raw, func(t *testing.T) {
			b := []byte(fmt.Sprintf("pcrc32=%s", tc.raw))
			i, err := extractCRC(b, []byte("pcrc32="))
			require.NoError(t, err)
			require.Equal(t, tc.expected, i)
		})
	}
}
