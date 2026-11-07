package sevenzip

import (
	"io"
	"math"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestFileReadCloser_Seek(t *testing.T) {
	t.Parallel()

	r, err := OpenReader(filepath.Join("testdata", "t0.7z"))
	if err != nil {
		t.Fatal(err)
	}

	defer func() {
		if err = r.Close(); err != nil {
			t.Fatal(err)
		}
	}()

	require.GreaterOrEqual(t, len(r.File), 1)

	rc, _, _, err := r.folderReader(r.si, r.File[0].folder)
	if err != nil {
		t.Fatal(err)
	}

	defer func() {
		if err = rc.Close(); err != nil {
			t.Fatal(err)
		}
	}()

	_, err = rc.Seek(0, math.MaxInt)
	assert.Equal(t, err, errInvalidWhence)

	_, err = rc.Seek(-1, io.SeekStart)
	assert.Equal(t, err, errNegativeSeek)

	n, err := rc.Seek(1, io.SeekCurrent)
	assert.Equal(t, int64(1), n)
	assert.NoError(t, err) //nolint:testifylint

	_, err = rc.Seek(-1, io.SeekCurrent)
	assert.Equal(t, err, errSeekBackwards)

	_, err = rc.Seek(int64(r.File[0].UncompressedSize), io.SeekCurrent) //nolint:gosec
	assert.Equal(t, err, errSeekEOF)

	n, err = rc.Seek(int64(r.File[0].UncompressedSize), io.SeekStart) //nolint:gosec
	assert.Equal(t, n, int64(r.File[0].UncompressedSize))             //nolint:gosec
	assert.NoError(t, err)                                            //nolint:testifylint

	n, err = rc.Seek(0, io.SeekEnd)
	assert.Equal(t, n, int64(r.File[0].UncompressedSize)) //nolint:gosec
	assert.NoError(t, err)
}
