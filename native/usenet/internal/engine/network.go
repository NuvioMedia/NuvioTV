package engine

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"syscall"
	"time"
)

var errPrivateNetwork = errors.New("local Usenet addresses require Allow self-hosted servers in Usenet settings")

// Also exclude shared, documentation, benchmark and transition address space.
// Check the actual dial targets, not only URL spelling, to prevent rebinding.
var nonPublicPrefixes = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"), netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("64:ff9b:1::/48"),
	netip.MustParsePrefix("100::/64"), netip.MustParsePrefix("2001::/23"),
	netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("::/96"),
	netip.MustParsePrefix("2002::/16"), netip.MustParsePrefix("3fff::/20"),
}

func allowedAddress(ip netip.Addr, allowPrivate bool) bool {
	ip = ip.Unmap()
	// Link-local (including cloud metadata), unspecified and multicast addresses
	// are never necessary for a configured self-hosted endpoint.
	if !ip.IsValid() || ip.Zone() != "" || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return false
	}
	if allowPrivate && (ip.IsPrivate() || ip.IsLoopback() || netip.MustParsePrefix("100.64.0.0/10").Contains(ip)) {
		return true
	}
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() {
		return false
	}
	for _, prefix := range nonPublicPrefixes {
		if prefix.Contains(ip) {
			return false
		}
	}
	return true
}

func guardedDial(allowPrivate bool) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		// ControlContext receives the resolved numeric address immediately before
		// connect. This preserves Happy Eyeballs without a second DNS lookup.
		dialer := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second,
			ControlContext: func(_ context.Context, _, target string, _ syscall.RawConn) error {
				host, _, err := net.SplitHostPort(target)
				if err != nil {
					return errPrivateNetwork
				}
				ip, err := netip.ParseAddr(host)
				if err != nil || !allowedAddress(ip, allowPrivate) {
					return errPrivateNetwork
				}
				return nil
			},
		}
		return dialer.DialContext(ctx, network, address)
	}
}

func providerDial(host string, config *tls.Config, allowPrivate bool) func(context.Context) (net.Conn, error) {
	return func(ctx context.Context) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		conn, err := guardedDial(allowPrivate)(ctx, "tcp", host)
		if err != nil || config == nil {
			return conn, err
		}
		secure := tls.Client(conn, config)
		if err := secure.HandshakeContext(ctx); err != nil {
			conn.Close()
			return nil, err
		}
		return secure, nil
	}
}

func sameOrigin(a, b *url.URL) bool {
	port := func(u *url.URL) string {
		if p := u.Port(); p != "" {
			return p
		}
		if u.Scheme == "https" {
			return "443"
		}
		return "80"
	}
	return a.Scheme == b.Scheme && strings.EqualFold(a.Hostname(), b.Hostname()) && port(a) == port(b)
}

func nzbRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return errors.New("too many NZB redirects")
	}
	if req.URL.Scheme != "http" && req.URL.Scheme != "https" || req.URL.User != nil {
		return errors.New("invalid NZB redirect")
	}
	if len(via) > 0 && via[len(via)-1].URL.Scheme == "https" && req.URL.Scheme != "https" {
		return errors.New("insecure NZB redirect")
	}
	// Go forwards custom headers, and even Authorization to subdomains. Keep
	// addon headers only while the entire chain remains on the original origin.
	for _, previous := range via {
		if !sameOrigin(req.URL, previous.URL) {
			req.Header = make(http.Header)
			req.Header.Set("Accept-Encoding", "gzip")
			break
		}
	}
	return nil
}

func nzbHTTPClient(base *http.Client, allowPrivate bool) *http.Client {
	client := *base
	transport, ok := base.Transport.(*http.Transport)
	if !ok {
		transport = http.DefaultTransport.(*http.Transport)
	}
	t := transport.Clone()
	t.Proxy = nil // A proxy could resolve/dial an unchecked private target.
	t.DialContext = guardedDial(allowPrivate)
	t.DialTLSContext = nil
	t.DialTLS = nil
	client.Transport = t
	client.CheckRedirect = nzbRedirect
	client.Jar = nil
	if client.Timeout == 0 {
		client.Timeout = 2 * time.Minute
	}
	return &client
}
