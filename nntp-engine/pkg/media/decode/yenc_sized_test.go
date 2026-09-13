package decode

import (
	"bytes"
	"testing"
)

// A hint smaller than the article must grow transparently and still decode
// byte-identically; a generous hint must not leave the frame holding the
// over-allocation (trimExact clones past maxDecodeOverAllocation).
func TestDecodeToBytesSizedHints(t *testing.T) {
	payload, wire := benchCorpus(t)

	for _, tc := range []struct {
		name string
		hint int64
	}{
		{"no_hint", 0},
		{"exact_wire_size", int64(len(wire))},
		{"undersized", 4096},
		{"oversized", int64(len(wire)) * 4},
	} {
		t.Run(tc.name, func(t *testing.T) {
			frame, err := DecodeToBytesSized(bytes.NewReader(wire), tc.hint)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(frame.Data, payload) {
				t.Fatalf("decoded %d bytes, want %d, content mismatch", len(frame.Data), len(payload))
			}
			if over := cap(frame.Data) - len(frame.Data); over > maxDecodeOverAllocation {
				t.Fatalf("frame keeps %d bytes of spare capacity, budget-visible max is %d", over, maxDecodeOverAllocation)
			}
		})
	}
}

// The decoded frame must carry the article's declared geometry — the loader
// builds exact segment maps from it.
func TestDecodeFrameCarriesYencGeometry(t *testing.T) {
	payload, wire := benchCorpus(t)

	frame, err := DecodeToBytes(bytes.NewReader(wire))
	if err != nil {
		t.Fatal(err)
	}
	if frame.FileSize != int64(len(payload))*2 {
		t.Fatalf("FileSize = %d, want %d (the =ybegin size)", frame.FileSize, len(payload)*2)
	}
	if frame.PartOffset != 0 {
		t.Fatalf("PartOffset = %d, want 0 for part 1", frame.PartOffset)
	}

	// A continuation part reports its own exact offset.
	wire2 := buildWireAt(t, payload, int64(len(payload)))
	frame2, err := DecodeToBytes(bytes.NewReader(wire2))
	if err != nil {
		t.Fatal(err)
	}
	if frame2.PartOffset != int64(len(payload)) {
		t.Fatalf("PartOffset = %d, want %d (the =ypart begin-1)", frame2.PartOffset, len(payload))
	}
	if frame2.FileSize != int64(len(payload))*2 {
		t.Fatalf("FileSize = %d, want %d", frame2.FileSize, len(payload)*2)
	}
}
