package nntppool

import (
	"bytes"
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/mnightingale/rapidyenc"
)

// TestPostYenc_RespectsContextDeadlineWhenProviderStalled reproduces a
// permanent hang in doSendPost: once a *Request has been handed off to a
// provider group's buffered g.reqCh, the dispatch loop waits on
// `resp, ok := <-innerCh` with no select guard at all. If the provider's
// connection can never be established (e.g. a permanently stalled dial),
// innerCh is never written to or closed, and the receive blocks forever —
// completely ignoring the caller's context deadline/cancellation.
//
// This is modeled with a single provider whose ConnFactory blocks on
// <-ctx.Done() (simulating a dial that never completes) and only returns
// once the provider group's own context is cancelled (i.e. on Client.Close()),
// never in response to the caller's request context.
func TestPostYenc_RespectsContextDeadlineWhenProviderStalled(t *testing.T) {
	stalledFactory := ConnFactory(func(ctx context.Context) (net.Conn, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	})

	c, err := NewClient(context.Background(), []Provider{
		{
			Factory:     stalledFactory,
			Connections: 1,
			SkipPing:    true,
		},
	})
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "yEnc test",
		Newsgroups: []string{"alt.binaries.test"},
		MessageID:  "<yenc-dispatch-timeout@example.com>",
	}

	data := bytes.Repeat([]byte("ABCDEFGHIJ"), 100) // 1000 bytes
	meta := rapidyenc.Meta{
		FileName:   "test.bin",
		FileSize:   int64(len(data)),
		PartNumber: 1,
		TotalParts: 1,
		Offset:     0,
		PartSize:   int64(len(data)),
	}

	const callerTimeout = 300 * time.Millisecond
	ctx, cancel := context.WithTimeout(context.Background(), callerTimeout)
	defer cancel()

	type result struct {
		res *PostResult
		err error
	}

	done := make(chan result, 1)
	start := time.Now()
	go func() {
		res, err := c.PostYenc(ctx, headers, bytes.NewReader(data), meta)
		done <- result{res: res, err: err}
	}()

	select {
	case r := <-done:
		elapsed := time.Since(start)
		// Should return promptly after the context deadline, not hang
		// indefinitely and not return suspiciously early either.
		if elapsed > callerTimeout+2*time.Second {
			t.Fatalf("PostYenc took %v to return, expected close to the %v context deadline", elapsed, callerTimeout)
		}
		if r.err == nil {
			t.Fatalf("PostYenc returned no error, want context.DeadlineExceeded-flavored error (result=%+v)", r.res)
		}
		if !errors.Is(r.err, context.DeadlineExceeded) {
			t.Fatalf("PostYenc error = %v, want errors.Is(err, context.DeadlineExceeded)", r.err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("PostYenc did not respect context deadline — likely blocked forever")
	}
}
