package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestLoopbackStreamServesRangeFromNNTPArticle(t *testing.T) {
	media := append([]byte{0x1a, 0x45, 0xdf, 0xa3}, bytes.Repeat([]byte("nuvio-nntp"), 256)...)
	nntpAddress, _, _, stopNNTP := startFakeNNTPServer(t, media)
	defer stopNNTP()

	nzbServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/x-nzb")
		_, _ = io.WriteString(w, fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
  <file poster="test" date="1" subject="&quot;video.mkv&quot; yEnc">
    <groups><group>alt.binaries.test</group></groups>
    <segments><segment bytes="%d" number="1">article@test</segment></segments>
  </file>
</nzb>`, len(media)))
	}))
	defer nzbServer.Close()

	host, port, err := net.SplitHostPort(nntpAddress)
	if err != nil {
		t.Fatal(err)
	}
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	session, err := registry.create(createSessionRequest{
		NZBURL:          nzbServer.URL,
		Servers:         []string{fmt.Sprintf("nntp://user:password@%s:%s/2", host, port)},
		FileMustInclude: "/\\.mkv$/i",
	})
	if err != nil {
		t.Fatal(err)
	}

	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	request := httptest.NewRequest(
		http.MethodGet,
		"/v1/sessions/"+session.id+"/stream",
		nil,
	)
	request.Header.Set("Range", "bytes=0-31")
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)

	if response.Code != http.StatusPartialContent {
		t.Fatalf("status = %d, body = %q", response.Code, response.Body.String())
	}
	if !bytes.Equal(response.Body.Bytes(), media[:32]) {
		t.Fatalf("range bytes differ: %x", response.Body.Bytes())
	}
	if got := response.Header().Get("Content-Range"); !strings.HasPrefix(got, "bytes 0-31/") {
		t.Fatalf("Content-Range = %q", got)
	}
}

func TestSessionCacheReusesDownloadedArticle(t *testing.T) {
	media := append([]byte{0x1a, 0x45, 0xdf, 0xa3}, bytes.Repeat([]byte("cached-nntp"), 128)...)
	nntpAddress, bodyRequests, _, stopNNTP := startFakeNNTPServer(t, media)
	defer stopNNTP()

	nzbServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
  <file poster="test" date="1" subject="&quot;video.mkv&quot; yEnc">
    <groups><group>alt.binaries.test</group></groups>
    <segments><segment bytes="%d" number="1">article@test</segment></segments>
  </file>
</nzb>`, len(media)))
	}))
	defer nzbServer.Close()

	host, port, err := net.SplitHostPort(nntpAddress)
	if err != nil {
		t.Fatal(err)
	}
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	session, err := registry.create(createSessionRequest{
		NZBURL:  nzbServer.URL,
		Servers: []string{fmt.Sprintf("nntp://user:password@%s:%s/2", host, port)},
	})
	if err != nil {
		t.Fatal(err)
	}

	for attempt := 0; attempt < 2; attempt++ {
		got, downloadErr := session.files[0].DownloadSegment(context.Background(), 0)
		if downloadErr != nil {
			t.Fatal(downloadErr)
		}
		if !bytes.Equal(got, media) {
			t.Fatalf("attempt %d returned different media", attempt+1)
		}
	}
	if got := bodyRequests(); got != 1 {
		t.Fatalf("BODY requests = %d, want 1 (second read must use session cache)", got)
	}
}

func TestProviderConnectionsAreReusedAcrossSessions(t *testing.T) {
	media := append([]byte{0x1a, 0x45, 0xdf, 0xa3}, bytes.Repeat([]byte("reused-nntp"), 128)...)
	nntpAddress, _, connections, stopNNTP := startFakeNNTPServer(t, media)
	defer stopNNTP()

	nzbServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
  <file poster="test" date="1" subject="&quot;video.mkv&quot; yEnc">
    <groups><group>alt.binaries.test</group></groups>
    <segments><segment bytes="%d" number="1">article@test</segment></segments>
  </file>
</nzb>`, len(media)))
	}))
	defer nzbServer.Close()

	host, port, err := net.SplitHostPort(nntpAddress)
	if err != nil {
		t.Fatal(err)
	}
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	request := createSessionRequest{
		NZBURL:  nzbServer.URL,
		Servers: []string{fmt.Sprintf("nntp://user:password@%s:%s/2", host, port)},
	}
	first, err := registry.create(request)
	if err != nil {
		t.Fatal(err)
	}
	if !registry.delete(first.id) {
		t.Fatal("first session was not deleted")
	}
	opened := connections()
	second, err := registry.create(request)
	if err != nil {
		t.Fatal(err)
	}
	if got := connections(); got != opened {
		t.Fatalf("NNTP connections after second session = %d, want reused total %d", got, opened)
	}
	registry.delete(second.id)
}

func TestSessionCreationRejectsMissingFirstSegment(t *testing.T) {
	nntpAddress, stopNNTP := startFakeMissingNNTPServer(t)
	defer stopNNTP()

	nzbServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `<?xml version="1.0" encoding="UTF-8"?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
  <file poster="test" date="1" subject="&quot;video.mkv&quot; yEnc">
    <groups><group>alt.binaries.test</group></groups>
    <segments><segment bytes="1024" number="1">missing@test</segment></segments>
  </file>
</nzb>`)
	}))
	defer nzbServer.Close()

	host, port, err := net.SplitHostPort(nntpAddress)
	if err != nil {
		t.Fatal(err)
	}
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	_, err = registry.create(createSessionRequest{
		NZBURL:  nzbServer.URL,
		Servers: []string{fmt.Sprintf("nntp://user:password@%s:%s/2", host, port)},
	})
	if !errors.Is(err, errFirstSegmentUnavailable) {
		t.Fatalf("registry.create() error = %v, want definitive 430", err)
	}
}

func TestSessionCreationRejectsProviderAuthenticationFailure(t *testing.T) {
	nntpAddress, stopNNTP := startRejectingNNTPServer(t)
	defer stopNNTP()

	nzbServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `<?xml version="1.0" encoding="UTF-8"?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
  <file poster="test" date="1" subject="&quot;video.mkv&quot; yEnc">
    <groups><group>alt.binaries.test</group></groups>
    <segments><segment bytes="1024" number="1">article@test</segment></segments>
  </file>
</nzb>`)
	}))
	defer nzbServer.Close()

	host, port, err := net.SplitHostPort(nntpAddress)
	if err != nil {
		t.Fatal(err)
	}
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	_, err = registry.create(createSessionRequest{
		NZBURL:  nzbServer.URL,
		Servers: []string{fmt.Sprintf("nntp://user:wrong@%s:%s/2", host, port)},
	})
	if err == nil || !strings.Contains(err.Error(), "502") || !strings.Contains(err.Error(), "Authentication Failed") {
		t.Fatalf("expected provider authentication failure, got %v", err)
	}
}

func startFakeNNTPServer(t *testing.T, media []byte) (string, func() int64, func() int64, func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var bodyCount atomic.Int64
	var connectionCount atomic.Int64
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			connectionCount.Add(1)
			go serveFakeNNTPConnection(connection, media, &bodyCount)
		}
	}()
	return listener.Addr().String(), bodyCount.Load, connectionCount.Load, func() {
		_ = listener.Close()
		<-done
	}
}

func startFakeMissingNNTPServer(t *testing.T) (string, func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			go serveMissingNNTPConnection(connection)
		}
	}()
	return listener.Addr().String(), func() {
		_ = listener.Close()
		<-done
	}
}

func startRejectingNNTPServer(t *testing.T) (string, func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			go func(connection net.Conn) {
				defer connection.Close()
				reader := bufio.NewReader(connection)
				writer := bufio.NewWriter(connection)
				_, _ = writer.WriteString("200 fake NNTP ready\r\n")
				_ = writer.Flush()
				for {
					line, readErr := reader.ReadString('\n')
					if readErr != nil {
						return
					}
					if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(line)), "AUTHINFO") {
						_, _ = writer.WriteString("502 Authentication Failed\r\n")
						_ = writer.Flush()
						return
					}
				}
			}(connection)
		}
	}()
	return listener.Addr().String(), func() {
		_ = listener.Close()
		<-done
	}
}

func serveMissingNNTPConnection(connection net.Conn) {
	defer connection.Close()
	reader := bufio.NewReader(connection)
	writer := bufio.NewWriter(connection)
	_, _ = writer.WriteString("200 fake NNTP ready\r\n")
	_ = writer.Flush()
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		command := strings.ToUpper(strings.TrimSpace(line))
		switch {
		case strings.HasPrefix(command, "AUTHINFO USER"):
			_, _ = writer.WriteString("381 password required\r\n")
		case strings.HasPrefix(command, "AUTHINFO PASS"):
			_, _ = writer.WriteString("281 authentication accepted\r\n")
		case strings.HasPrefix(command, "GROUP"):
			_, _ = writer.WriteString("211 1 1 1 alt.binaries.test\r\n")
		case strings.HasPrefix(command, "STAT"):
			_, _ = writer.WriteString("430 no such article\r\n")
		case strings.HasPrefix(command, "QUIT"):
			_, _ = writer.WriteString("205 closing connection\r\n")
			_ = writer.Flush()
			return
		default:
			_, _ = writer.WriteString("500 unsupported command\r\n")
		}
		_ = writer.Flush()
	}
}

func serveFakeNNTPConnection(connection net.Conn, media []byte, bodyCount *atomic.Int64) {
	defer connection.Close()
	reader := bufio.NewReader(connection)
	writer := bufio.NewWriter(connection)
	_, _ = writer.WriteString("200 fake NNTP ready\r\n")
	_ = writer.Flush()
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		command := strings.ToUpper(strings.TrimSpace(line))
		switch {
		case strings.HasPrefix(command, "AUTHINFO USER"):
			_, _ = writer.WriteString("381 password required\r\n")
		case strings.HasPrefix(command, "AUTHINFO PASS"):
			_, _ = writer.WriteString("281 authentication accepted\r\n")
		case strings.HasPrefix(command, "GROUP"):
			_, _ = writer.WriteString("211 1 1 1 alt.binaries.test\r\n")
		case strings.HasPrefix(command, "STAT"):
			_, _ = writer.WriteString("223 1 <article@test>\r\n")
		case strings.HasPrefix(command, "BODY"):
			bodyCount.Add(1)
			_, _ = writer.WriteString("222 1 <article@test>\r\n")
			_, _ = writer.Write(encodeYEncArticle(media))
		case strings.HasPrefix(command, "QUIT"):
			_, _ = writer.WriteString("205 closing connection\r\n")
			_ = writer.Flush()
			return
		default:
			_, _ = writer.WriteString("500 unsupported command\r\n")
		}
		_ = writer.Flush()
	}
}

func encodeYEncArticle(data []byte) []byte {
	const lineLength = 128
	var body bytes.Buffer
	column := 0
	for _, value := range data {
		encoded := byte((int(value) + 42) & 0xff)
		escaped := encoded == 0x00 || encoded == 0x0a || encoded == 0x0d || encoded == 0x3d
		width := 1
		if escaped {
			width = 2
		}
		if column+width > lineLength {
			body.WriteString("\r\n")
			column = 0
		}
		if escaped {
			body.WriteByte('=')
			body.WriteByte(byte((int(encoded) + 64) & 0xff))
			column += 2
		} else {
			body.WriteByte(encoded)
			column++
		}
	}

	var article bytes.Buffer
	fmt.Fprintf(&article, "=ybegin line=%d size=%d name=video.mkv\r\n", lineLength, len(data))
	article.Write(body.Bytes())
	fmt.Fprintf(&article, "\r\n=yend size=%d crc32=%08x\r\n.\r\n", len(data), crc32.ChecksumIEEE(data))
	return article.Bytes()
}
