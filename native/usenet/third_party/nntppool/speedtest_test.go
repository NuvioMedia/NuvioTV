package nntppool

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// makeBodyFactory returns a ConnFactory whose server responds to BODY commands
// with yEnc-encoded data, or with a configurable status code.
func makeBodyFactory(t *testing.T, statusCode int) ConnFactory {
	t.Helper()
	body := bytes.Repeat([]byte("X"), 256)
	return func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := string(buf[:n])
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240101120000\r\n"))
				} else if strings.HasPrefix(cmd, "BODY") {
					if statusCode == 430 {
						_, _ = server.Write([]byte("430 no such article\r\n"))
					} else {
						_, _ = server.Write(yencSinglePart(body, "test.bin"))
					}
				}
			}
		}()
		return client, nil
	}
}

func testNZBReader(messageIDs ...string) *bytes.Buffer {
	var buf bytes.Buffer
	buf.WriteString(`<?xml version="1.0" encoding="UTF-8" ?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
<file poster="test" date="1234567890" subject="test">
<groups><group>alt.test</group></groups>
<segments>`)
	for i, id := range messageIDs {
		fmt.Fprintf(&buf, `<segment bytes="256" number="%d">%s</segment>`, i+1, id)
	}
	buf.WriteString(`</segments>
</file>
</nzb>`)
	return &buf
}

func TestSpeedTest_BasicFlow(t *testing.T) {
	factory := makeBodyFactory(t, 222)
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 2},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	result, err := c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader: testNZBReader("seg1@test", "seg2@test", "seg3@test"),
	})
	if err != nil {
		t.Fatalf("SpeedTest: %v", err)
	}

	if result.SegmentsDone != 3 {
		t.Errorf("SegmentsDone = %d, want 3", result.SegmentsDone)
	}
	if result.SegmentsTotal != 3 {
		t.Errorf("SegmentsTotal = %d, want 3", result.SegmentsTotal)
	}
	if result.DecodedBytes <= 0 {
		t.Errorf("DecodedBytes = %d, want > 0", result.DecodedBytes)
	}
	if len(result.Providers) == 0 {
		t.Error("Providers should be populated")
	}
}

func TestSpeedTest_MaxSegments(t *testing.T) {
	factory := makeBodyFactory(t, 222)
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 2},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	ids := make([]string, 10)
	for i := range ids {
		ids[i] = fmt.Sprintf("seg%d@test", i+1)
	}

	result, err := c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader:   testNZBReader(ids...),
		MaxSegments: 3,
	})
	if err != nil {
		t.Fatalf("SpeedTest: %v", err)
	}
	if result.SegmentsTotal != 3 {
		t.Errorf("SegmentsTotal = %d, want 3", result.SegmentsTotal)
	}
	if result.SegmentsDone != 3 {
		t.Errorf("SegmentsDone = %d, want 3", result.SegmentsDone)
	}
}

func TestSpeedTest_OnProgress(t *testing.T) {
	// Use a factory with a small delay to ensure the progress ticker fires.
	body := bytes.Repeat([]byte("X"), 256)
	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := string(buf[:n])
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240101120000\r\n"))
				} else if strings.HasPrefix(cmd, "BODY") {
					time.Sleep(400 * time.Millisecond) // slow down to allow progress ticks
					_, _ = server.Write(yencSinglePart(body, "test.bin"))
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	var progressCalls atomic.Int64
	result, err := c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader: testNZBReader("s1@test", "s2@test", "s3@test", "s4@test"),
		OnProgress: func(p SpeedTestProgress) {
			progressCalls.Add(1)
		},
	})
	if err != nil {
		t.Fatalf("SpeedTest: %v", err)
	}
	if result.SegmentsDone != 4 {
		t.Errorf("SegmentsDone = %d, want 4", result.SegmentsDone)
	}
	if progressCalls.Load() == 0 {
		t.Error("OnProgress should have been called at least once")
	}
}

func TestSpeedTest_ContextCancellation(t *testing.T) {
	// Slow factory that delays responses.
	factory := func(ctx context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer func() { _ = server.Close() }()
			_, _ = server.Write([]byte("200 server ready\r\n"))
			buf := make([]byte, 4096)
			for {
				n, err := server.Read(buf)
				if err != nil {
					return
				}
				cmd := string(buf[:n])
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240101120000\r\n"))
					continue
				}
				// Never respond to BODY — simulate a very slow server.
				select {
				case <-ctx.Done():
					return
				case <-time.After(10 * time.Second):
				}
			}
		}()
		return client, nil
	}

	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()

	ids := make([]string, 10)
	for i := range ids {
		ids[i] = fmt.Sprintf("seg%d@test", i+1)
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		result, _ := c.SpeedTest(ctx, SpeedTestOptions{
			NZBReader: testNZBReader(ids...),
		})
		// The key assertion: SpeedTest must return promptly after cancellation
		// and decoded bytes should be zero since the server never responded.
		if result != nil && result.DecodedBytes != 0 {
			t.Errorf("expected DecodedBytes=0, got %d", result.DecodedBytes)
		}
	}()

	select {
	case <-done:
		// Good — returned without hanging.
	case <-time.After(5 * time.Second):
		t.Fatal("SpeedTest did not return after context cancellation")
	}
}

func TestSpeedTest_MissingArticles(t *testing.T) {
	factory := makeBodyFactory(t, 430)
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 2},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	result, err := c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader: testNZBReader("miss1@test", "miss2@test"),
	})
	if err != nil {
		t.Fatalf("SpeedTest: %v", err)
	}
	if result.Missing <= 0 {
		t.Errorf("Missing = %d, want > 0", result.Missing)
	}
}

func TestSpeedTest_EmptyNZB(t *testing.T) {
	factory := makeBodyFactory(t, 222)
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	emptyNZB := bytes.NewBufferString(`<?xml version="1.0" encoding="UTF-8" ?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"></nzb>`)

	_, err = c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader: emptyNZB,
	})
	if err == nil {
		t.Fatal("expected error for empty NZB")
	}
}

func TestSpeedTest_NZBReaderOverridesURL(t *testing.T) {
	factory := makeBodyFactory(t, 222)
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	result, err := c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBURL:    "http://invalid.example.com/does-not-exist.nzb",
		NZBReader: testNZBReader("seg1@test"),
	})
	if err != nil {
		t.Fatalf("SpeedTest should use NZBReader over invalid NZBURL: %v", err)
	}
	if result.SegmentsDone != 1 {
		t.Errorf("SegmentsDone = %d, want 1", result.SegmentsDone)
	}
}

func TestSpeedTest_ProviderName(t *testing.T) {
	factory1 := makeBodyFactory(t, 222)
	factory2 := makeBodyFactory(t, 222)

	c, err := NewClient(context.Background(), []Provider{
		{Host: "host1:119", Factory: factory1, Connections: 1},
		{Host: "host2:119", Factory: factory2, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	result, err := c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader:    testNZBReader("seg1@test", "seg2@test"),
		ProviderName: "host1:119",
	})
	if err != nil {
		t.Fatalf("SpeedTest: %v", err)
	}
	if len(result.Providers) != 1 {
		t.Fatalf("Providers count = %d, want 1", len(result.Providers))
	}
	if result.Providers[0].Name != "host1:119" {
		t.Errorf("Provider name = %q, want %q", result.Providers[0].Name, "host1:119")
	}
}

func TestSpeedTest_ProviderNameNotFound(t *testing.T) {
	factory := makeBodyFactory(t, 222)
	c, err := NewClient(context.Background(), []Provider{
		{Factory: factory, Connections: 1},
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	defer func() { _ = c.Close() }()

	_, err = c.SpeedTest(context.Background(), SpeedTestOptions{
		NZBReader:    testNZBReader("seg1@test"),
		ProviderName: "nonexistent:119",
	})
	if err == nil {
		t.Fatal("expected error for nonexistent provider name")
	}
}
