package nntppool

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestHandshakeHonoursCancellation(t *testing.T) {
	for _, phase := range []string{"greeting", "user", "password"} {
		t.Run(phase, func(t *testing.T) {
			client, server := net.Pipe()
			defer client.Close()
			defer server.Close()
			reached := make(chan struct{})
			go func() {
				if phase != "greeting" {
					fmt.Fprint(server, "200 ready\r\n")
					reader := bufio.NewReader(server)
					reader.ReadString('\n')
					if phase == "password" {
						fmt.Fprint(server, "381 password required\r\n")
						reader.ReadString('\n')
					}
				}
				close(reached)
			}()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() {
				_, err := newNNTPConnectionFromConn(ctx, client, 1, nil, nil, Auth{Username: "user", Password: "pass"}, "", nil, nil)
				done <- err
			}()
			select {
			case <-reached:
			case <-time.After(time.Second):
				t.Fatal("handshake did not reach test phase")
			}
			cancel()
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("cancelled handshake succeeded")
				}
			case <-time.After(250 * time.Millisecond):
				server.Close()
				<-done
				t.Fatal("cancelled handshake remained blocked on provider")
			}
		})
	}
}

func TestWarmProviderCloseInterruptsGreeting(t *testing.T) {
	connected := make(chan net.Conn, 1)
	factory := func(context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		connected <- server
		return client, nil
	}
	c, err := NewClient(context.Background(), []Provider{{Factory: factory, Connections: 1, MinConnections: 1, SkipPing: true}})
	if err != nil {
		t.Fatal(err)
	}
	server := <-connected
	defer server.Close()
	done := make(chan struct{})
	go func() { c.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(250 * time.Millisecond):
		server.Close()
		<-done
		t.Fatal("pool shutdown waited indefinitely for greeting")
	}
}

func decodeBodyForTest(t *testing.T, wire []byte) (*ArticleBody, error) {
	t.Helper()
	var meta NNTPResponse
	var body bytes.Buffer
	_, done, err := meta.Feed(append([]byte(nil), wire...), &body)
	if err != nil || !done {
		t.Fatalf("response decode: done=%v err=%v", done, err)
	}
	ch := make(chan Response, 1)
	ch <- Response{StatusCode: meta.StatusCode, Status: meta.Message, Meta: meta, Body: body}
	return (&Client{}).finishBody("test@example.com", nil, ch)
}

func TestBodyRejectsInvalidYEncCompletion(t *testing.T) {
	wire := yencSinglePart([]byte("test payload"), "movie.mkv")
	trailer := bytes.Index(wire, []byte("=yend "))
	for _, tc := range []struct {
		name string
		wire []byte
	}{
		{"missing-trailer", append(append([]byte(nil), wire[:trailer]...), []byte(".\r\n")...)},
		{"wrong-trailer-size", bytes.Replace(wire, []byte("=yend size=12"), []byte("=yend size=13"), 1)},
		{"zero-checksum-mismatch", []byte(strings.ReplaceAll(string(wire[:trailer])+"=yend size=12 crc32=00000000\r\n.\r\n", "pcrc32=", "crc32="))},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := decodeBodyForTest(t, tc.wire); err == nil {
				t.Fatal("malformed yEnc body accepted as successful")
			}
		})
	}
}

func TestBodyAcceptsOneByteMultipart(t *testing.T) {
	body, err := decodeBodyForTest(t, yencMultiPart([]byte("A"), "movie.mkv", 1, 2, 0))
	if err != nil || body.YEnc.PartSize != 1 {
		t.Fatalf("valid inclusive one-byte range: body=%+v err=%v", body, err)
	}
}

func TestBodyAcceptsOptionalAndZeroCRC(t *testing.T) {
	for _, data := range [][]byte{[]byte("test payload"), {0x9d, 0x0a, 0xd9, 0x6d}} {
		wire := yencSinglePart(data, "movie.mkv")
		body, err := decodeBodyForTest(t, wire)
		if err != nil || !body.CRCValid {
			t.Fatalf("valid checksum including zero must be verified: body=%+v err=%v", body, err)
		}
		trailer := bytes.Index(wire, []byte("=yend "))
		noCRC := append(append([]byte(nil), wire[:trailer]...), []byte(fmt.Sprintf("=yend size=%d\r\n.\r\n", len(data)))...)
		if _, err := decodeBodyForTest(t, noCRC); err != nil {
			t.Fatalf("yEnc without optional checksum rejected: %v", err)
		}
	}
}

func TestResponsePreambleIsBounded(t *testing.T) {
	var r NNTPResponse
	r.Feed([]byte("222 article follows\r\n"), io.Discard)
	// Short, continuously arriving lines never hit the socket buffer or stall cap.
	for i := 0; i < 20000; i++ {
		if _, _, err := r.Feed([]byte("unencoded preamble\r\n"), io.Discard); err != nil {
			return
		}
	}
	t.Fatalf("retained %d lines without a metadata limit", len(r.Lines))
}

func TestAuthResponseSemanticClassification(t *testing.T) {
	for _, tc := range []struct {
		code    int
		message string
		want    error
	}{
		{481, "481 Authentication failed", ErrAuthRejected},
		{502, "502 Invalid username or password", ErrAuthRejected},
		{481, "481 Maximum connections reached", ErrMaxConnections},
		{482, "482 Too many connections for your user", ErrMaxConnections},
	} {
		err := &authResponseError{Command: "AUTHINFO PASS", StatusCode: tc.code, Message: tc.message}
		if !errors.Is(err, tc.want) {
			t.Errorf("%q does not match %v", tc.message, tc.want)
		}
		if tc.want == ErrMaxConnections && errors.Is(err, ErrAuthRejected) {
			t.Errorf("connection ceiling incorrectly marks credentials rejected: %s", err)
		}
	}
}

func TestWarmAuthenticationFailureIsReturnedWithoutReconnectStorm(t *testing.T) {
	var logins atomic.Int32
	rejected := make(chan struct{}, 1)
	factory := func(context.Context) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer server.Close()
			fmt.Fprint(server, "200 ready\r\n")
			r := bufio.NewReader(server)
			if _, err := r.ReadString('\n'); err != nil {
				return
			}
			fmt.Fprint(server, "381 password required\r\n")
			if _, err := r.ReadString('\n'); err != nil {
				return
			}
			logins.Add(1)
			fmt.Fprint(server, "481 Authentication failed\r\n")
			select {
			case rejected <- struct{}{}:
			default:
			}
		}()
		return client, nil
	}
	c, err := NewClient(context.Background(), []Provider{{Factory: factory, Connections: 1, MinConnections: 1, SkipPing: true, Auth: Auth{Username: "user", Password: "wrong"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	select {
	case <-rejected:
	case <-time.After(time.Second):
		t.Fatal("provider did not reject login")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if _, err := c.Body(ctx, "test@example.com"); !errors.Is(err, ErrAuthRejected) {
		t.Errorf("warm authentication failure lost: %v", err)
	}
	// Wait past the existing retry interval: permanent credential failures must
	// stay disabled for this pool; changing credentials creates a new provider.
	select {
	case <-rejected:
		t.Fatal("reconnected with credentials already rejected")
	case <-time.After(1100 * time.Millisecond):
	}
	if logins.Load() != 1 {
		t.Fatalf("login attempts=%d", logins.Load())
	}
}
