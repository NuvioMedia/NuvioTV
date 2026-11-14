package nntppool

import (
	"bytes"
	"context"
	"testing"
	"time"

	"github.com/mnightingale/rapidyenc"
)

// TestPostYenc_NineMainProviders_RoundRobinDispatch reproduces a panic in
// doSendPost's DispatchRoundRobin case: cumulative dispatch weights were
// computed into a fixed-size [8]int array, so any client configured with 9
// or more main (non-backup) providers caused an
// "index out of range [8] with length 8" panic inside the goroutine spawned
// by sendPost — an unrecoverable crash of the whole process on every
// PostYenc/PostYencTo call. This test uses 9 main providers to trigger that
// path; before the fix it panics, after the fix PostYenc completes normally.
func TestPostYenc_NineMainProviders_RoundRobinDispatch(t *testing.T) {
	const numProviders = 9

	providers := make([]Provider, numProviders)
	for i := range providers {
		var received bytes.Buffer
		providers[i] = Provider{
			Factory:     makePostFactory(t, []string{"340 send article", "240 article posted ok"}, &received),
			Connections: 1,
		}
	}

	c, err := NewClient(context.Background(), providers)
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "nine provider dispatch test",
		Newsgroups: []string{"alt.binaries.test"},
		MessageID:  "<nine-provider-test@example.com>",
	}

	data := bytes.Repeat([]byte("ABCDEFGHIJ"), 10) // 100 bytes
	meta := rapidyenc.Meta{
		FileName:   "test.bin",
		FileSize:   int64(len(data)),
		PartNumber: 1,
		TotalParts: 1,
		Offset:     0,
		PartSize:   int64(len(data)),
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	result, err := c.PostYenc(ctx, headers, bytes.NewReader(data), meta)
	if err != nil {
		t.Fatalf("PostYenc error: %v", err)
	}
	if result.StatusCode != 240 {
		t.Errorf("StatusCode = %d, want 240", result.StatusCode)
	}
}
