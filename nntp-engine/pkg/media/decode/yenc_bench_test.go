package decode

import (
	"bytes"
	"math/rand"
	"testing"
)

// Benchmarks for the article decode path (issue #198).
//
// The corpus is fixed and deterministic: one 768000-byte segment — the size a
// typical release posts — of pseudorandom data seeded from a constant, so the
// yEnc escape density matches real (already-compressed) media payloads, with
// bytes that encode to '.' sprinkled through so line-leading dots occur at a
// realistic rate.
//
// Input is the article in canonical wire form, which is what nntp.Client.Body
// hands the decoder. Results report MB/s over the DECODED payload plus "s/GB",
// the CPU seconds per gigabyte of decoded output that the zurg comparison in
// issue #198 is quoted in.

const benchPayloadSize = 768000

func benchCorpus(tb testing.TB) (payload, wire []byte) {
	tb.Helper()
	rng := rand.New(rand.NewSource(198))
	payload = make([]byte, benchPayloadSize)
	rng.Read(payload)
	for i := 0; i < benchPayloadSize; i += 211 {
		payload[i] = 0x04
	}
	return payload, buildWire(tb, payload, false)
}

// reportSpeed converts the elapsed time into CPU seconds per GB of decoded
// payload.
func reportSpeed(b *testing.B, decoded int) {
	b.SetBytes(int64(decoded))
	secPerGB := b.Elapsed().Seconds() / (float64(decoded) * float64(b.N) / (1 << 30))
	b.ReportMetric(secPerGB, "s/GB")
}

// BenchmarkDecodeToBytes is the production decode path end to end, as
// pool.fetchSegmentOnce runs it.
func BenchmarkDecodeToBytes(b *testing.B) {
	payload, wire := benchCorpus(b)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		frame, err := DecodeToBytes(bytes.NewReader(wire))
		if err != nil {
			b.Fatal(err)
		}
		if len(frame.Data) != len(payload) {
			b.Fatalf("decoded %d bytes, want %d", len(frame.Data), len(payload))
		}
	}
	reportSpeed(b, len(payload))
}
