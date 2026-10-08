package nntppool

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mnightingale/rapidyenc"
)

// --- PostHeaders tests ---

func TestPostHeaders_WriteTo(t *testing.T) {
	t.Run("required fields only", func(t *testing.T) {
		h := PostHeaders{
			From:       "user@example.com",
			Subject:    "Test article",
			Newsgroups: []string{"alt.test"},
		}
		var buf bytes.Buffer
		n, err := h.WriteTo(&buf)
		if err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}
		if int64(buf.Len()) != n {
			t.Errorf("WriteTo returned n=%d, buf.Len()=%d", n, buf.Len())
		}

		got := buf.String()
		if !strings.Contains(got, "From: user@example.com\r\n") {
			t.Error("missing From header")
		}
		if !strings.Contains(got, "Subject: Test article\r\n") {
			t.Error("missing Subject header")
		}
		if !strings.Contains(got, "Newsgroups: alt.test\r\n") {
			t.Error("missing Newsgroups header")
		}
		if !strings.HasSuffix(got, "\r\n\r\n") {
			t.Error("missing blank line terminator")
		}
		// Should not have Message-ID when empty.
		if strings.Contains(got, "Message-ID") {
			t.Error("unexpected Message-ID header")
		}
	})

	t.Run("all fields", func(t *testing.T) {
		h := PostHeaders{
			From:       "User <user@example.com>",
			Subject:    "Test article",
			Newsgroups: []string{"alt.test", "alt.test2"},
			MessageID:  "<unique123@example.com>",
			Extra: map[string][]string{
				"Organization": {"Test Org"},
				"X-No-Archive": {"yes"},
			},
		}
		var buf bytes.Buffer
		_, err := h.WriteTo(&buf)
		if err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}

		got := buf.String()
		if !strings.Contains(got, "From: User <user@example.com>\r\n") {
			t.Error("missing From header")
		}
		if !strings.Contains(got, "Newsgroups: alt.test, alt.test2\r\n") {
			t.Error("missing/wrong Newsgroups header")
		}
		if !strings.Contains(got, "Message-ID: <unique123@example.com>\r\n") {
			t.Error("missing Message-ID header")
		}
		if !strings.Contains(got, "Organization: Test Org\r\n") {
			t.Error("missing Organization header")
		}
		if !strings.Contains(got, "X-No-Archive: yes\r\n") {
			t.Error("missing X-No-Archive header")
		}
	})

	t.Run("auto Date header", func(t *testing.T) {
		h := PostHeaders{
			From:       "user@example.com",
			Subject:    "Test",
			Newsgroups: []string{"alt.test"},
		}
		var buf bytes.Buffer
		if _, err := h.WriteTo(&buf); err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}
		got := buf.String()
		if !strings.Contains(got, "Date: ") {
			t.Error("expected auto-generated Date header")
		}
		// RFC1123 format ends with timezone abbreviation like "UTC".
		if !strings.Contains(got, " UTC\r\n") {
			t.Errorf("Date header not in RFC1123 format: %q", got)
		}
	})

	t.Run("explicit Date field", func(t *testing.T) {
		fixed := time.Date(2024, 1, 2, 15, 4, 5, 0, time.UTC)
		h := PostHeaders{
			From:       "user@example.com",
			Subject:    "Test",
			Newsgroups: []string{"alt.test"},
			Date:       fixed,
		}
		var buf bytes.Buffer
		if _, err := h.WriteTo(&buf); err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}
		want := "Date: " + fixed.Format(time.RFC1123) + "\r\n"
		if !strings.Contains(buf.String(), want) {
			t.Errorf("want %q, got %q", want, buf.String())
		}
	})

	t.Run("Date in Extra is not overwritten", func(t *testing.T) {
		h := PostHeaders{
			From:       "user@example.com",
			Subject:    "Test",
			Newsgroups: []string{"alt.test"},
			Extra:      map[string][]string{"Date": {"Mon, 02 Jan 2024 15:04:05 +0000"}},
		}
		var buf bytes.Buffer
		if _, err := h.WriteTo(&buf); err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}
		got := buf.String()
		if strings.Count(got, "Date: ") != 1 {
			t.Errorf("expected exactly one Date header, got: %q", got)
		}
		if !strings.Contains(got, "Date: Mon, 02 Jan 2024 15:04:05 +0000\r\n") {
			t.Error("caller-supplied Date was overwritten")
		}
	})

	t.Run("validation rejects missing required fields", func(t *testing.T) {
		cases := []struct {
			name    string
			h       PostHeaders
			wantSub string
		}{
			{"empty From", PostHeaders{Subject: "s", Newsgroups: []string{"a.b"}}, "From"},
			{"whitespace From", PostHeaders{From: "   ", Subject: "s", Newsgroups: []string{"a.b"}}, "From"},
			{"empty Subject", PostHeaders{From: "u@x", Newsgroups: []string{"a.b"}}, "Subject"},
			{"no newsgroups", PostHeaders{From: "u@x", Subject: "s"}, "newsgroup"},
			{"empty newsgroup", PostHeaders{From: "u@x", Subject: "s", Newsgroups: []string{""}}, "newsgroup"},
			{"bad newsgroup char", PostHeaders{From: "u@x", Subject: "s", Newsgroups: []string{"alt test"}}, "newsgroup"},
		}
		for _, tc := range cases {
			t.Run(tc.name, func(t *testing.T) {
				_, err := tc.h.WriteTo(io.Discard)
				if err == nil {
					t.Fatal("expected error, got nil")
				}
				if !strings.Contains(err.Error(), tc.wantSub) {
					t.Errorf("error %q does not mention %q", err, tc.wantSub)
				}
			})
		}
	})

	t.Run("rejects CRLF injection in headers", func(t *testing.T) {
		cases := []PostHeaders{
			{From: "u@x\r\nX-Injected: yes", Subject: "s", Newsgroups: []string{"a.b"}},
			{From: "u@x", Subject: "s\r\nX-Injected: yes", Newsgroups: []string{"a.b"}},
			{From: "u@x", Subject: "s", Newsgroups: []string{"a.b"}, MessageID: "<id\r\nX-Injected: yes>"},
			{From: "u@x", Subject: "s", Newsgroups: []string{"a.b"}, Extra: map[string][]string{"X-Foo": {"bar\r\nX-Injected: yes"}}},
		}
		for i, h := range cases {
			if _, err := h.WriteTo(io.Discard); err == nil {
				t.Errorf("case %d: expected error, got nil", i)
			}
		}
	})

	t.Run("rejects malformed Message-ID", func(t *testing.T) {
		cases := []string{"no-angle-brackets@x", "<missing-close@x", "missing-open@x>", "<has space@x>"}
		for _, mid := range cases {
			h := PostHeaders{From: "u@x", Subject: "s", Newsgroups: []string{"a.b"}, MessageID: mid}
			if _, err := h.WriteTo(io.Discard); err == nil {
				t.Errorf("Message-ID %q: expected error, got nil", mid)
			}
		}
	})

	t.Run("RFC 2047 encodes non-ASCII Subject", func(t *testing.T) {
		h := PostHeaders{
			From:       "user@example.com",
			Subject:    "Café résumé",
			Newsgroups: []string{"alt.test"},
		}
		var buf bytes.Buffer
		if _, err := h.WriteTo(&buf); err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}
		got := buf.String()
		if !strings.Contains(got, "=?utf-8?b?") && !strings.Contains(got, "=?UTF-8?b?") {
			t.Errorf("Subject not RFC 2047 encoded: %q", got)
		}
	})

	t.Run("folds long Subject", func(t *testing.T) {
		long := "Subject" + strings.Repeat(" word", 250) // ~1.2K bytes
		h := PostHeaders{
			From:       "user@example.com",
			Subject:    long,
			Newsgroups: []string{"alt.test"},
		}
		var buf bytes.Buffer
		if _, err := h.WriteTo(&buf); err != nil {
			t.Fatalf("WriteTo error: %v", err)
		}
		got := buf.String()
		if !strings.Contains(got, "\r\n\t") {
			t.Error("expected folded continuation \\r\\n\\t in long Subject")
		}
		// No physical line should exceed 998 octets.
		for line := range strings.SplitSeq(got, "\r\n") {
			if len(line) > 998 {
				t.Errorf("line exceeds 998 octets: %d", len(line))
			}
		}
	})
}

// --- POST Client-level integration tests ---

// makePostFactory creates a ConnFactory suitable for Post tests.
// The first connection (from pingProvider) gets a greeting + DATE handler.
// Subsequent connections get a greeting + POST handler that responds with the given status flow.
// receivedArticle, if non-nil, captures the article data from the POST connection.
func makePostFactory(t *testing.T, postResponses []string, receivedArticle *bytes.Buffer) ConnFactory {
	t.Helper()
	var connCount atomic.Int32
	return func(ctx context.Context) (net.Conn, error) {
		n := connCount.Add(1)
		if n == 1 {
			// Ping connection: handle greeting + DATE.
			return mockServer(t, func(s net.Conn) {
				_, _ = s.Write([]byte("200 server ready\r\n"))
				buf := make([]byte, 4096)
				for {
					nr, err := s.Read(buf)
					if err != nil {
						return
					}
					cmd := string(buf[:nr])
					if strings.HasPrefix(cmd, "DATE") {
						_, _ = s.Write([]byte("111 20240315120000\r\n"))
					}
				}
			}), nil
		}
		// POST connection: correct NNTP two-phase protocol.
		return mockServer(t, func(s net.Conn) {
			_, _ = s.Write([]byte("200 server ready\r\n"))

			// Read "POST\r\n" command.
			cmdBuf := make([]byte, 256)
			var cmdRecv bytes.Buffer
			for {
				nr, err := s.Read(cmdBuf)
				if nr > 0 {
					cmdRecv.Write(cmdBuf[:nr])
				}
				if bytes.Contains(cmdRecv.Bytes(), []byte("POST\r\n")) {
					break
				}
				if err != nil {
					return
				}
			}

			// Send first response (340 or 440).
			if len(postResponses) > 0 {
				_, _ = fmt.Fprintf(s, "%s\r\n", postResponses[0])
			}
			if len(postResponses) == 0 || !strings.HasPrefix(postResponses[0], "340") {
				return // posting not allowed — no body expected
			}

			// Read the article body.
			bodyBuf := make([]byte, 8192)
			var recv bytes.Buffer
			for {
				nr, err := s.Read(bodyBuf)
				if nr > 0 {
					recv.Write(bodyBuf[:nr])
				}
				if bytes.Contains(recv.Bytes(), []byte("\r\n.\r\n")) {
					break
				}
				if err != nil {
					return
				}
			}

			if receivedArticle != nil {
				receivedArticle.Write(recv.Bytes())
			}

			for _, resp := range postResponses[1:] {
				_, _ = fmt.Fprintf(s, "%s\r\n", resp)
			}
		}), nil
	}
}

func TestPostYenc_Success(t *testing.T) {
	var received bytes.Buffer
	factory := makePostFactory(t, []string{"340 send article", "240 article posted ok"}, &received)

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "yEnc test",
		Newsgroups: []string{"alt.binaries.test"},
		MessageID:  "<yenc-test@example.com>",
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

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := c.PostYenc(ctx, headers, bytes.NewReader(data), meta)
	if err != nil {
		t.Fatalf("PostYenc error: %v", err)
	}
	if result.StatusCode != 240 {
		t.Errorf("StatusCode = %d, want 240", result.StatusCode)
	}

	// Verify yEnc markers are present.
	got := received.String()
	if !strings.Contains(got, "=ybegin") {
		t.Error("missing =ybegin header")
	}
	if !strings.Contains(got, "=yend") {
		t.Error("missing =yend trailer")
	}
	if !strings.Contains(got, "name=test.bin") {
		t.Error("missing filename in yEnc header")
	}
}

func TestPostYenc_MultiPart(t *testing.T) {
	var received bytes.Buffer
	factory := makePostFactory(t, []string{"340 send article", "240 article posted ok"}, &received)

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "yEnc multipart test (1/3)",
		Newsgroups: []string{"alt.binaries.test"},
	}

	partData := bytes.Repeat([]byte("X"), 500)
	meta := rapidyenc.Meta{
		FileName:   "multi.bin",
		FileSize:   1500,
		PartNumber: 1,
		TotalParts: 3,
		Offset:     0,
		PartSize:   500,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := c.PostYenc(ctx, headers, bytes.NewReader(partData), meta)
	if err != nil {
		t.Fatalf("PostYenc error: %v", err)
	}
	if result.StatusCode != 240 {
		t.Errorf("StatusCode = %d, want 240", result.StatusCode)
	}

	got := received.String()
	if !strings.Contains(got, "=ybegin") {
		t.Error("missing =ybegin header")
	}
	if !strings.Contains(got, "=ypart") {
		t.Error("missing =ypart header for multipart")
	}
	if !strings.Contains(got, "=yend") {
		t.Error("missing =yend trailer")
	}
	if !strings.Contains(got, "total=3") {
		t.Error("missing total=3 in yEnc header")
	}
}

func TestPostYencTo_TargetsNamedBackupProvider(t *testing.T) {
	var primaryReceived bytes.Buffer
	var backupReceived bytes.Buffer

	c, err := NewClient(context.Background(), []Provider{
		{
			Name:        "primary",
			Host:        "primary.example:119",
			Factory:     makePostFactory(t, []string{"340 send article", "240 article posted ok"}, &primaryReceived),
			Connections: 1,
		},
		{
			Name:        "backup",
			Host:        "backup.example:119",
			Factory:     makePostFactory(t, []string{"340 send article", "240 article posted ok"}, &backupReceived),
			Connections: 1,
			Backup:      true,
		},
	})
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "targeted yEnc test",
		Newsgroups: []string{"alt.binaries.test"},
		MessageID:  "<targeted-yenc-test@example.com>",
	}
	data := bytes.Repeat([]byte("ABCDEFGHIJ"), 100)
	meta := rapidyenc.Meta{
		FileName:   "test.bin",
		FileSize:   int64(len(data)),
		PartNumber: 1,
		TotalParts: 1,
		PartSize:   int64(len(data)),
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := c.PostYencTo(ctx, "backup", headers, bytes.NewReader(data), meta)
	if err != nil {
		t.Fatalf("PostYencTo error: %v", err)
	}
	if result.StatusCode != 240 {
		t.Errorf("StatusCode = %d, want 240", result.StatusCode)
	}
	if primaryReceived.Len() != 0 {
		t.Errorf("primary provider unexpectedly received %d bytes", primaryReceived.Len())
	}
	if !strings.Contains(backupReceived.String(), "=ybegin") {
		t.Error("backup provider did not receive the article")
	}
}

func TestPostYencTo_ProviderNotFound(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{
			Host:        "primary.example:119",
			Factory:     makePostFactory(t, []string{"340 send article", "240 article posted ok"}, nil),
			Connections: 1,
		},
	})
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	_, err = c.PostYencTo(
		context.Background(),
		"missing.example:119",
		PostHeaders{},
		bytes.NewReader(nil),
		rapidyenc.Meta{},
	)
	if err == nil || !strings.Contains(err.Error(), `provider "missing.example:119" not found`) {
		t.Fatalf("PostYencTo error = %v, want provider not found", err)
	}
}

// --- NNTPConnection-level POST test ---

func TestNNTPConnection_PostTwoPhase(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		// Read "POST\r\n" command.
		buf := make([]byte, 256)
		var recv bytes.Buffer
		for {
			n, err := s.Read(buf)
			if n > 0 {
				recv.Write(buf[:n])
			}
			if bytes.Contains(recv.Bytes(), []byte("POST\r\n")) {
				break
			}
			if err != nil {
				return
			}
		}

		// Send 340 before reading the body (correct NNTP protocol order).
		_, _ = s.Write([]byte("340 send article\r\n"))

		// Now read the article body.
		recv.Reset()
		bodyBuf := make([]byte, 8192)
		for {
			n, err := s.Read(bodyBuf)
			if n > 0 {
				recv.Write(bodyBuf[:n])
			}
			if bytes.Contains(recv.Bytes(), []byte("\r\n.\r\n")) {
				break
			}
			if err != nil {
				return
			}
		}

		_, _ = s.Write([]byte("240 article posted\r\n"))
	})

	reqCh := make(chan *Request, 1)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	// Build a simple article payload.
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("From: test@example.com\r\nSubject: Test\r\nNewsgroups: alt.test\r\n\r\nBody text\r\n.\r\n"))
		_ = pw.Close()
	}()

	respCh := make(chan Response, 1)
	reqCh <- &Request{
		Ctx:         context.Background(),
		Payload:     []byte("POST\r\n"),
		RespCh:      respCh,
		PayloadBody: pr,
		PostMode:    true,
	}

	go nc.Run()

	select {
	case resp := <-respCh:
		if resp.Err != nil {
			t.Fatalf("response error = %v", resp.Err)
		}
		if resp.StatusCode != 240 {
			t.Errorf("StatusCode = %d, want 240", resp.StatusCode)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for response")
	}

	_ = nc.Close()
}

// TestPostYenc_RoundRobinDispatch verifies that concurrent PostYenc calls are
// spread across all provider connections rather than serialised on provider 0.
func TestPostYenc_RoundRobinDispatch(t *testing.T) {
	const providers = 3
	const postsPerProvider = 4
	const total = providers * postsPerProvider

	// One atomic counter per provider to track how many POSTs it handled.
	postCounts := make([]atomic.Int32, providers)

	makeFactory := func(idx int) ConnFactory {
		var connCount atomic.Int32
		return func(ctx context.Context) (net.Conn, error) {
			n := connCount.Add(1)
			if n == 1 {
				// Ping connection.
				return mockServer(t, func(s net.Conn) {
					_, _ = s.Write([]byte("200 server ready\r\n"))
					buf := make([]byte, 256)
					for {
						nr, err := s.Read(buf)
						if err != nil {
							return
						}
						if strings.HasPrefix(string(buf[:nr]), "DATE") {
							_, _ = s.Write([]byte("111 20240315120000\r\n"))
						}
					}
				}), nil
			}
			// POST connection: accept and count each article.
			return mockServer(t, func(s net.Conn) {
				_, _ = s.Write([]byte("200 server ready\r\n"))
				for {
					var recv bytes.Buffer
					buf := make([]byte, 256)
					for {
						nr, err := s.Read(buf)
						if nr > 0 {
							recv.Write(buf[:nr])
						}
						if bytes.Contains(recv.Bytes(), []byte("POST\r\n")) {
							break
						}
						if err != nil {
							return
						}
					}
					_, _ = fmt.Fprintf(s, "340 send article\r\n")

					var body bytes.Buffer
					bodyBuf := make([]byte, 8192)
					for {
						nr, err := s.Read(bodyBuf)
						if nr > 0 {
							body.Write(bodyBuf[:nr])
						}
						if bytes.Contains(body.Bytes(), []byte("\r\n.\r\n")) {
							break
						}
						if err != nil {
							return
						}
					}
					postCounts[idx].Add(1)
					_, _ = fmt.Fprintf(s, "240 article posted ok\r\n")
				}
			}), nil
		}
	}

	provs := make([]Provider, providers)
	for i := range provs {
		provs[i] = Provider{Factory: makeFactory(i), Connections: 2}
	}

	c, err := NewClient(context.Background(), provs)
	if err != nil {
		t.Fatalf("NewClient error: %v", err)
	}
	defer func() { _ = c.Close() }()

	headers := PostHeaders{
		From:       "user@example.com",
		Subject:    "dispatch test",
		Newsgroups: []string{"alt.test"},
	}
	meta := rapidyenc.Meta{
		FileName:   "test.bin",
		FileSize:   100,
		PartNumber: 1,
		TotalParts: 1,
		PartSize:   100,
	}
	data := bytes.Repeat([]byte("A"), 100)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	type result struct{ err error }
	results := make(chan result, total)
	for range total {
		go func() {
			_, err := c.PostYenc(ctx, headers, bytes.NewReader(data), meta)
			results <- result{err}
		}()
	}
	for range total {
		r := <-results
		if r.err != nil {
			t.Errorf("PostYenc error: %v", r.err)
		}
	}

	// Every provider must have handled at least one POST.
	for i := range postCounts {
		if got := postCounts[i].Load(); got == 0 {
			t.Errorf("provider %d received 0 POST requests; posts were not distributed", i)
		} else {
			t.Logf("provider %d received %d POST requests", i, got)
		}
	}
}

// TestNNTPConnection_PostRejected verifies that a 440 "posting not allowed"
// response does not cause protocol desync: the article body must NOT be sent
// to the server, and the pipe-writer goroutine must be unblocked cleanly.
func TestNNTPConnection_PostRejected(t *testing.T) {
	conn := mockServer(t, func(s net.Conn) {
		_, _ = s.Write([]byte("200 server ready\r\n"))

		// Read "POST\r\n".
		buf := make([]byte, 256)
		var recv bytes.Buffer
		for {
			n, err := s.Read(buf)
			if n > 0 {
				recv.Write(buf[:n])
			}
			if bytes.Contains(recv.Bytes(), []byte("POST\r\n")) {
				break
			}
			if err != nil {
				return
			}
		}

		// Reject posting immediately — no body should follow.
		_, _ = s.Write([]byte("440 posting not allowed\r\n"))

		// Ensure no stray bytes arrive (give the client a moment to potentially
		// misbehave before we close).
		_ = s.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
		extra := make([]byte, 256)
		n, _ := s.Read(extra)
		if n > 0 {
			t.Errorf("unexpected bytes after 440: %q", extra[:n])
		}
	})

	reqCh := make(chan *Request, 1)
	nc, err := newNNTPConnectionFromConn(context.Background(), conn, 1, reqCh, nil, Auth{}, "", nil, nil)
	if err != nil {
		t.Fatalf("connection error = %v", err)
	}

	pr, pw := io.Pipe()
	// Writer goroutine must unblock even though the body is rejected.
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		_, _ = pw.Write([]byte("From: test@example.com\r\nSubject: Test\r\nNewsgroups: alt.test\r\n\r\nBody text\r\n.\r\n"))
		_ = pw.Close()
	}()

	respCh := make(chan Response, 1)
	reqCh <- &Request{
		Ctx:         context.Background(),
		Payload:     []byte("POST\r\n"),
		RespCh:      respCh,
		PayloadBody: pr,
		PostMode:    true,
	}

	go nc.Run()

	select {
	case resp := <-respCh:
		if resp.StatusCode != 440 {
			t.Errorf("StatusCode = %d, want 440", resp.StatusCode)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for response")
	}

	select {
	case <-writerDone:
	case <-time.After(5 * time.Second):
		t.Fatal("pipe-writer goroutine not unblocked after 440")
	}

	_ = nc.Close()
}
