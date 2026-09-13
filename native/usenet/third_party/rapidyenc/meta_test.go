package rapidyenc

import (
	"github.com/stretchr/testify/require"
	"testing"
)

func TestMetaValidation(t *testing.T) {
	m := Meta{}

	require.ErrorIs(t, m.validate(), errFileNameEmpty)
	m.FileName = "foobar"

	require.ErrorIs(t, m.validate(), errFileSize)
	m.FileSize = 1000

	require.ErrorIs(t, m.validate(), errPartNumber)
	m.PartNumber = 1

	require.ErrorIs(t, m.validate(), errTotalParts)
	m.TotalParts = 10

	m.Offset = -1
	require.ErrorIs(t, m.validate(), errOffset)
	m.Offset = 0

	require.ErrorIs(t, m.validate(), errPartSize)
	m.PartSize = 100

	require.NoError(t, m.validate())
}
