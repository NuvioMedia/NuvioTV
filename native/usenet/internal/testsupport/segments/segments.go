// Package segments builds deterministic segment payloads and identifiers for
// use by streaming/connection tests.
//
// The fakepool client (internal/testsupport/fakepool) returns whatever bytes
// you configure for a given message-ID; this package supplies a consistent
// naming scheme and a recoverable payload format so that a test which
// downloads segments through the streaming pipeline can verify that the
// reassembled bytes match what was injected.
//
// # Naming
//
// MessageID(i) returns "altmount-test-seg-NNNNNN@fake" — six-digit zero-padded
// so lexicographic sort matches segment order. Tests pin this format so the
// fakepool behaviors and the segment-range builder agree without sharing a
// channel.
//
// # Payload shape
//
// Payload(i, size) returns a deterministic byte slice of the requested size.
// The first 32 bytes encode the segment index in ASCII (left-justified, NUL
// padded), so a hexdump of any cached buffer instantly shows which segment
// it came from. The remaining bytes are a repeating pattern derived from i,
// cheap to generate and stable across runs.
//
// # File reassembly
//
// FileBytes(n, size) returns the concatenation of Payload(0..n-1, size).
// Tests that read a virtual file end-to-end through the streaming pipeline
// can compare the bytes they receive to this slice to catch ordering or
// boundary bugs.
package segments

import (
	"fmt"
)

// MessageIDPrefix is the constant prefix used for all generated message-IDs.
// Exposed so fakepool setup loops can sanity-check inputs.
const MessageIDPrefix = "altmount-test-seg-"

// MessageID returns the canonical fake message-ID for the i-th segment.
func MessageID(i int) string {
	return fmt.Sprintf("%s%06d@fake", MessageIDPrefix, i)
}

// Payload returns a deterministic byte slice for segment i. Bytes 0..31
// encode the index in ASCII; the remainder follow a stable cheap pattern.
// Length is always exactly size. A size <= 0 returns nil.
func Payload(i, size int) []byte {
	if size <= 0 {
		return nil
	}
	out := make([]byte, size)
	header := fmt.Sprintf("seg-%06d", i)
	copy(out, header)
	// Fill remainder with a repeating byte derived from i. Using i directly
	// (mod 251 — a prime under 256) gives every segment a unique fill byte
	// for small i, which makes hexdumps trivially distinguishable.
	fill := byte((i % 251) + 1)
	for j := len(header); j < size; j++ {
		out[j] = fill
	}
	return out
}

// FileBytes returns the concatenation of Payload(0..n-1, size). The result
// is the expected output of a sequential read across n segments.
func FileBytes(n, size int) []byte {
	if n <= 0 || size <= 0 {
		return nil
	}
	out := make([]byte, 0, n*size)
	for i := 0; i < n; i++ {
		out = append(out, Payload(i, size)...)
	}
	return out
}
