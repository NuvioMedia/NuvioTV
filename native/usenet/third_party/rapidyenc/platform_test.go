package rapidyenc

import (
	"github.com/stretchr/testify/assert"
	"testing"
)

func TestVersion(t *testing.T) {
	assert.NotEqual(t, Version(), "0.0.0")
}

func TestDecodeKernel(t *testing.T) {
	assert.NotEqual(t, DecodeKernel(), "unknown")
}

func TestEncodeKernel(t *testing.T) {
	assert.NotEqual(t, EncodeKernel(), "unknown")
}
