package rapidyenc

import (
	"bytes"
	"crypto/rand"
	"github.com/stretchr/testify/require"
	"hash/crc32"
	"io"
	"testing"
)

type encoderCase struct {
	name     string
	input    []byte
	expected []byte
}

func TestEncoderSimple(t *testing.T) {
	cases := []encoderCase{
		{"NUL", []byte("\x00"), []byte("\x2a")},
		{"SPACE", []byte("\x20"), []byte("\x4a")},
		{"ESCAPE", []byte("\xF6"), []byte("\x3D\x60")},                // Ends with <space> so must be escaped
		{"ESCAPE_NOT_FIRST", []byte("H\xF6"), []byte("\x72\x3D\x60")}, // Ends with <space> and not the first column, so must be escaped
		{"Hello World", []byte("Hello World"), []byte("\x72\x8F\x96\x96\x99\x4A\x81\x99\x9C\x96\x8E")},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			input := bytes.NewReader(tc.input)

			encoded := new(bytes.Buffer)
			w, err := NewEncoder(encoded, Meta{
				FileName:   "filename",
				FileSize:   input.Size(),
				PartSize:   input.Size(),
				PartNumber: 1,
				TotalParts: 1,
			})
			require.NoError(t, err)
			_, err = io.Copy(w, input)
			require.NoError(t, err)
			err = w.Close()
			require.NoError(t, err)

			// Check contains the expected encoded value
			expected := append([]byte("\r\n"), append(tc.expected, []byte("\r\n")[:]...)...)
			require.True(t, bytes.Contains(encoded.Bytes(), expected))

			// Check that we can decode it back again
			decoded := new(bytes.Buffer)
			r := NewDecoder(encoded)
			_, err = io.Copy(decoded, r)
			require.NoError(t, err)
			require.Equal(t, tc.input, decoded.Bytes())
			require.Equal(t, int64(len(tc.input)), r.Meta.PartSize)
			require.Equal(t, crc32.ChecksumIEEE(tc.input), r.Meta.Hash)
			require.Equal(t, int64(len(tc.input)), r.Meta.End())
		})
	}
}

func BenchmarkEncoder(b *testing.B) {
	raw := make([]byte, 1024*1024)
	_, err := rand.Read(raw)
	require.NoError(b, err)

	r := bytes.NewReader(raw)

	meta := Meta{
		FileName:   "filename",
		FileSize:   int64(len(raw)),
		PartSize:   int64(len(raw)),
		PartNumber: 1,
		TotalParts: 1,
	}

	enc, err := NewEncoder(io.Discard, meta)
	require.NoError(b, err)

	b.ResetTimer()
	for b.Loop() {
		_, err = io.Copy(enc, r)
		require.NoError(b, err)
		err = enc.Close()
		require.NoError(b, err)
		_, err = r.Seek(0, io.SeekStart)
		require.NoError(b, err)
		enc.Reset(io.Discard, meta)
	}
}
