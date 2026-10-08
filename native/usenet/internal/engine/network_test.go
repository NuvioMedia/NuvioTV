package engine

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestUsenetNetworkAddresses(t *testing.T) {
	for _, raw := range []string{"127.0.0.1", "::1", "::ffff:127.0.0.1", "10.2.3.4", "172.16.1.2", "192.168.1.2", "fd12::1", "100.64.1.2"} {
		ip := netip.MustParseAddr(raw)
		if allowedAddress(ip, false) || !allowedAddress(ip, true) {
			t.Errorf("self-hosted policy: %s", raw)
		}
	}
	for _, raw := range []string{"0.0.0.0", "::", "169.254.169.254", "fe80::1", "fe80::1%eth0", "224.0.0.1", "ff02::1", "::ffff:169.254.169.254", "64:ff9b::7f00:1", "2002:7f00:1::", "198.18.0.1", "192.0.2.1", "2001:db8::1"} {
		ip := netip.MustParseAddr(raw)
		if allowedAddress(ip, false) || allowedAddress(ip, true) {
			t.Errorf("unsafe address accepted: %s", raw)
		}
	}
	for _, raw := range []string{"8.8.8.8", "2606:4700:4700::1111"} {
		if !allowedAddress(netip.MustParseAddr(raw), false) {
			t.Errorf("public address rejected: %s", raw)
		}
	}
}

func TestGuardedDialValidatesResolvedTarget(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	_, port, _ := net.SplitHostPort(listener.Addr().String())
	for _, host := range []string{"127.0.0.1", "localhost"} {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		conn, err := guardedDial(false)(ctx, "tcp4", net.JoinHostPort(host, port))
		cancel()
		if conn != nil {
			conn.Close()
			t.Fatal("private target connected")
		}
		if !errors.Is(err, errPrivateNetwork) {
			t.Fatalf("%s: %v", host, err)
		}
	}
	conn, err := guardedDial(true)(context.Background(), "tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
}

func TestNZBRedirectStripsSecretsAcrossOrigins(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	var reached atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reached.Store(true)
		for _, key := range []string{"Authorization", "Cookie", "X-Api-Key", "Referer", "Proxy-Authorization"} {
			if r.Header.Get(key) != "" {
				t.Errorf("forwarded %s", key)
			}
		}
		io.WriteString(w, xml)
	}))
	defer target.Close()
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "secret" {
			t.Error("lost same-origin header")
		}
		if r.URL.Path == "/start" {
			http.Redirect(w, r, "/next", 302)
		} else {
			http.Redirect(w, r, target.URL, 302)
		}
	}))
	defer source.Close()
	client := nzbHTTPClient(source.Client(), true)
	defer client.CloseIdleConnections()
	_, err := FetchNZB(context.Background(), client, source.URL+"/start", map[string]string{
		"Authorization": "Bearer secret", "Cookie": "key=secret", "X-Api-Key": "secret", "Referer": "https://example.com/?key=secret", "Proxy-Authorization": "secret",
	}, nil, true)
	if err != nil || !reached.Load() {
		t.Fatalf("redirect: %v", err)
	}
}

func TestNZBRedirectPolicy(t *testing.T) {
	request := func(raw string) *http.Request { r, _ := http.NewRequest("GET", raw, nil); return r }
	for _, raw := range []string{"http://example.com/nzb", "file:///tmp/nzb", "https://user:pass@example.com/nzb"} {
		if nzbRedirect(request(raw), []*http.Request{request("https://example.com/start")}) == nil {
			t.Errorf("allowed %s", raw)
		}
	}
	r := request("https://example.com/return")
	r.Header.Set("X-Api-Key", "secret")
	if err := nzbRedirect(r, []*http.Request{request("https://example.com/start"), request("https://cdn.example.com/next")}); err != nil || r.Header.Get("X-Api-Key") != "" {
		t.Fatal("secret reintroduced after cross-origin hop")
	}
}

func TestNZBPublicClientBlocksLocalHTTP(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer srv.Close()
	client := nzbHTTPClient(srv.Client(), false)
	defer client.CloseIdleConnections()
	_, err := FetchNZB(context.Background(), client, srv.URL, nil, nil, true)
	if !errors.Is(err, errPrivateNetwork) || calls.Load() != 0 {
		t.Fatalf("request reached local service: %v", err)
	}
	providers, err := Providers([]string{"nntp://" + strings.TrimPrefix(srv.URL, "http://")}, Config{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := providers[0].Factory(context.Background())
	if conn != nil {
		conn.Close()
		t.Fatal("NNTP bypassed policy")
	}
	if !errors.Is(err, errPrivateNetwork) {
		t.Fatal(err)
	}
}
