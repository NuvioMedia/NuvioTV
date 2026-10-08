package nntppool

import (
	"bytes"
	"context"
	"net"
	"sync"
	"testing"
	"time"
)

type chunkRecorder struct {
	mu     sync.Mutex
	writes int
	buf    bytes.Buffer
}

func (r *chunkRecorder) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.writes++
	return r.buf.Write(p)
}

func TestClient_BodyStreamPriorityWritesDecodedBytes(t *testing.T) {
	original := bytes.Repeat([]byte("progressive body payload "), 20000)

	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if bytes.Contains(buf[:n], []byte("BODY")) {
					_, _ = server.Write(yencSinglePart(original, "test.bin"))
				} else {
					_, _ = server.Write([]byte("111 20260101120000\r\n"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{{Factory: factory, Connections: 1}})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	rec := &chunkRecorder{}
	body, err := c.BodyStreamPriority(ctx, "test@example.com", rec)
	if err != nil {
		t.Fatalf("BodyStreamPriority() error = %v", err)
	}
	if body.Bytes != nil {
		t.Fatal("streamed body must not also buffer Bytes")
	}
	if !bytes.Equal(rec.buf.Bytes(), original) {
		t.Fatalf("writer received %d bytes, want %d", rec.buf.Len(), len(original))
	}
	if rec.writes < 2 {
		t.Fatalf("expected the payload to arrive over several writes, got %d", rec.writes)
	}
	if body.BytesDecoded != len(original) {
		t.Fatalf("BytesDecoded = %d, want %d", body.BytesDecoded, len(original))
	}
}

func TestClient_BodyStreamPriorityRequiresWriter(t *testing.T) {
	c := &Client{}
	if _, err := c.BodyStreamPriority(context.Background(), "x@y", nil); err == nil {
		t.Fatal("nil writer must be rejected")
	}
}
