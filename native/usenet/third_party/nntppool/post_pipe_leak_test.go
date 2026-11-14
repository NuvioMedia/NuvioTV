package nntppool

import (
	"bytes"
	"context"
	"net"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/mnightingale/rapidyenc"
)

// TestPostYenc_NoMainProviders_DoesNotLeakPipeWriterGoroutine reproduces a
// goroutine leak in postYenc: when doSendPost fails before any connection
// ever reads from the pipe (e.g. because there are zero main providers), the
// pipe-writer goroutine started by postYenc blocks forever inside
// headers.WriteTo(pw), because nothing ever closes the read side (pr) of the
// pipe. Closing only the write side (pw) does not unblock a write that is
// waiting for a reader.
//
// We reach the "zero main providers" state via NewClient (which requires at
// least one non-backup provider to succeed) followed by RemoveProvider,
// which empties c.mainGroups without touching doSendPost's dispatch-weight
// computation.
func TestPostYenc_NoMainProviders_DoesNotLeakPipeWriterGoroutine(t *testing.T) {
	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() { _ = server.Close() }()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Name: "main", Factory: factory, Connections: 1, SkipPing: true},
	})
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	if err := c.RemoveProvider("main"); err != nil {
		t.Fatalf("RemoveProvider error: %v", err)
	}

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "leak test",
		Newsgroups: []string{"alt.test"},
	}
	data := bytes.Repeat([]byte("A"), 1000)
	meta := rapidyenc.Meta{
		FileName:   "test.bin",
		FileSize:   int64(len(data)),
		PartNumber: 1,
		TotalParts: 1,
		Offset:     0,
		PartSize:   int64(len(data)),
	}

	before := runtime.NumGoroutine()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := c.PostYenc(ctx, headers, bytes.NewReader(data), meta)
	if err == nil {
		t.Fatalf("expected error, got result: %+v", result)
	}
	if !strings.Contains(err.Error(), "no main providers") {
		t.Fatalf("expected error containing %q, got: %v", "no main providers", err)
	}

	// Give any leaked goroutine time to settle (or, pre-fix, to still be
	// sitting blocked in the pipe write).
	time.Sleep(300 * time.Millisecond)

	buf := make([]byte, 1<<20)
	n := runtime.Stack(buf, true)
	stack := string(buf[:n])

	// The pipe-writer goroutine created inside postYenc is an anonymous
	// function literal; it shows up in the goroutine dump as
	// "nntppool.(*Client).postYenc.func1".
	const leakedFrame = "postYenc.func1"
	if strings.Contains(stack, leakedFrame) {
		t.Errorf("leaked pipe-writer goroutine still present in stack dump (frame %q found):\n%s", leakedFrame, stack)
	}

	after := runtime.NumGoroutine()
	// Allow a little slack for unrelated background goroutines (GC, etc.)
	// but the leaked goroutine should not persist.
	if after > before+2 {
		t.Errorf("goroutine count did not return to baseline: before=%d after=%d", before, after)
	}
}
