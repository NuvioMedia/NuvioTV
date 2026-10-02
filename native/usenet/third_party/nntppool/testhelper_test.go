package nntppool

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mnightingale/rapidyenc"
)

// mockNNTPResponse builds a full NNTP response byte sequence.
// For multiline responses, bodyLines are terminated with ".\r\n".
func mockNNTPResponse(statusLine string, bodyLines ...string) []byte {
	var buf bytes.Buffer
	buf.WriteString(statusLine)
	buf.WriteString("\r\n")
	for _, line := range bodyLines {
		buf.WriteString(line)
		buf.WriteString("\r\n")
	}
	if len(bodyLines) > 0 || isMultilineStatus(statusLine) {
		buf.WriteString(".\r\n")
	}
	return buf.Bytes()
}

func isMultilineStatus(status string) bool {
	if len(status) < 3 {
		return false
	}
	code := 0
	for i := range 3 {
		if status[i] < '0' || status[i] > '9' {
			return false
		}
		code = code*10 + int(status[i]-'0')
	}
	return isMultiline(code)
}

// yencSinglePart produces a complete 222 BODY response with a yEnc single-part encoded payload.
func yencSinglePart(data []byte, fileName string) []byte {
	var encoded bytes.Buffer
	enc, err := rapidyenc.NewEncoder(&encoded, rapidyenc.Meta{
		FileName:   fileName,
		FileSize:   int64(len(data)),
		PartNumber: 1,
		TotalParts: 1,
		Offset:     0,
		PartSize:   int64(len(data)),
	})
	if err != nil {
		panic(fmt.Sprintf("yencSinglePart: NewEncoder: %v", err))
	}
	if _, err := enc.Write(data); err != nil {
		panic(fmt.Sprintf("yencSinglePart: Write: %v", err))
	}
	if err := enc.Close(); err != nil {
		panic(fmt.Sprintf("yencSinglePart: Close: %v", err))
	}

	var buf bytes.Buffer
	buf.WriteString("222 0 <test@example.com> body\r\n")
	buf.Write(encoded.Bytes())
	buf.WriteString(".\r\n")
	return buf.Bytes()
}

// yencMultiPart produces a complete 222 BODY response with a yEnc multi-part encoded payload.
func yencMultiPart(data []byte, fileName string, part, total int, offset int64) []byte {
	var encoded bytes.Buffer
	enc, err := rapidyenc.NewEncoder(&encoded, rapidyenc.Meta{
		FileName:   fileName,
		FileSize:   int64(len(data) * total), // approximate total
		PartNumber: int64(part),
		TotalParts: int64(total),
		Offset:     offset,
		PartSize:   int64(len(data)),
	})
	if err != nil {
		panic(fmt.Sprintf("yencMultiPart: NewEncoder: %v", err))
	}
	if _, err := enc.Write(data); err != nil {
		panic(fmt.Sprintf("yencMultiPart: Write: %v", err))
	}
	if err := enc.Close(); err != nil {
		panic(fmt.Sprintf("yencMultiPart: Close: %v", err))
	}

	var buf bytes.Buffer
	buf.WriteString("222 0 <test@example.com> body\r\n")
	buf.Write(encoded.Bytes())
	buf.WriteString(".\r\n")
	return buf.Bytes()
}

// mockServer creates a net.Pipe pair and runs handler on the server side.
// Returns the client-side conn. The server goroutine is cleaned up via t.Cleanup.
func mockServer(t *testing.T, handler func(net.Conn)) net.Conn {
	t.Helper()
	client, server := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		handler(server)
		_ = server.Close()
	}()
	t.Cleanup(func() {
		_ = client.Close()
		<-done
	})
	return client
}

// mockFeeder implements streamFeeder for readBuffer tests.
type mockFeeder struct {
	feedFunc func(in []byte, out io.Writer) (consumed int, done bool, err error)
}

func (m *mockFeeder) Feed(in []byte, out io.Writer) (consumed int, done bool, err error) {
	return m.feedFunc(in, out)
}

// slowBodyFactory returns a ConnFactory for a HEALTHY server that answers every
// BODY with respond() — after `delay`. This is the real-world slow-spool-lookup
// shape: aged articles have been measured taking ~7.5s to answer on a healthy
// Newshosting connection while the TTFB EWMA (cache-hot serving) derives a 2s
// window.
//
// Each BODY is answered from its own goroutine so the read loop never blocks on
// answering, exactly like a real server whose spool lookup runs while the
// connection stays responsive. (net.Pipe is unbuffered — a server that slept
// inline would deadlock a client command against its own pending answer.)
func slowBodyFactory(delay time.Duration, respond func() []byte) ConnFactory {
	return func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			_, _ = server.Write([]byte("200 ready\r\n"))
			buf := make([]byte, 4096)
			var wmu sync.Mutex
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				if strings.HasPrefix(string(buf[:n]), "BODY") {
					go func() {
						time.Sleep(delay)
						wmu.Lock()
						defer wmu.Unlock()
						_, _ = server.Write(respond())
					}()
				}
			}
		}()
		return client, nil
	}
}

// noSuchArticle and agedArticle are the two responses the escalation tests need
// from slowBodyFactory: a definitive 430, and a real article body.
func noSuchArticle() []byte { return []byte("430 No Such Article\r\n") }

func agedArticle() []byte {
	return yencSinglePart(bytes.Repeat([]byte("X"), 256), "aged.bin")
}
