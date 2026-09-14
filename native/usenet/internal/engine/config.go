package engine

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/javi11/nntppool/v4"
)

// Zero overrides follow the profile/provider configuration. Memory is a hard
// article-buffer budget; Go's runtime memory limit is set separately by main.
type Config struct {
	Profile        string `json:"profile"`
	ReadAhead      int    `json:"readAhead"`
	MaxConnections int    `json:"maxConnections"`
	FastMKVStartup bool   `json:"fastMkvStartup"`
	FastNZBFetch   bool   `json:"fastNzbFetch"`
	CacheNZB       bool   `json:"cacheNzb"`
}

type Tuning struct {
	CacheBytes                              int64
	ReadAhead, Pipeline, DefaultConnections int
}

func (c Config) Tuning() (Tuning, error) {
	t := Tuning{64 << 20, 16, 4, 16}
	switch c.Profile {
	case "", "balanced":
	case "low-memory":
		t = Tuning{32 << 20, 8, 2, 8}
	case "throughput":
		t = Tuning{128 << 20, 32, 4, 32}
	default:
		return t, fmt.Errorf("unknown performance profile")
	}
	if c.ReadAhead < 0 || c.ReadAhead > 512 || c.MaxConnections < 0 || c.MaxConnections > 4096 {
		return t, fmt.Errorf("invalid Usenet settings")
	}
	if c.ReadAhead > 0 {
		t.ReadAhead = c.ReadAhead
	}
	return t, nil
}

func Providers(servers []string, cfg Config, roots *x509.CertPool) ([]nntppool.Provider, error) {
	t, err := cfg.Tuning()
	if err != nil {
		return nil, err
	}
	if len(servers) == 0 {
		return nil, fmt.Errorf("the addon did not supply any Usenet servers")
	}
	if len(servers) > 64 {
		return nil, fmt.Errorf("too many Usenet servers")
	}
	ps := make([]nntppool.Provider, 0, len(servers))
	remaining := cfg.MaxConnections
	total := 0
	for i, raw := range servers {
		u, e := url.Parse(raw)
		if e != nil || (u.Scheme != "nntp" && u.Scheme != "nntps") || u.Hostname() == "" {
			return nil, fmt.Errorf("invalid Usenet server %d", i+1)
		}
		port := u.Port()
		if port == "" {
			if u.Scheme == "nntps" {
				port = "563"
			} else {
				port = "119"
			}
		}
		pn, e := strconv.Atoi(port)
		if e != nil || pn < 1 || pn > 65535 {
			return nil, fmt.Errorf("invalid NNTP port")
		}
		connections := t.DefaultConnections
		if s := strings.Trim(u.Path, "/"); s != "" {
			connections, e = strconv.Atoi(s)
			if e != nil || connections < 1 || connections > 4096 {
				return nil, fmt.Errorf("invalid NNTP connection allowance")
			}
		}
		if cfg.MaxConnections > 0 {
			// Retain each provider for missing-article failover. Divide the global
			// allowance across providers instead of silently removing backups.
			if cfg.MaxConnections < len(servers) {
				return nil, fmt.Errorf("Max Connections must allow at least one per server")
			}
			connections = min(connections, max(1, remaining/(len(servers)-i)))
			remaining -= connections
		}
		// Bound abandoned downloads on shared links: draining an entire old
		// read-ahead window can delay seeks even when it preserves the sockets.
		p := nntppool.Provider{
			Host: net.JoinHostPort(u.Hostname(), port), Name: fmt.Sprintf("server-%d", i+1),
			Connections: connections, Inflight: t.Pipeline, StreamInflight: t.Pipeline,
			MinConnections: min(connections, t.ReadAhead+1),
			SkipPing:       true, IdleTimeout: 45 * time.Second, StallTimeout: 12 * time.Second,
			AbortDrainBytes: 64 << 10,
		}
		if u.User != nil {
			p.Auth.Username = u.User.Username()
			p.Auth.Password, _ = u.User.Password()
		}
		if strings.ContainsAny(p.Auth.Username+p.Auth.Password, "\r\n\x00") {
			return nil, fmt.Errorf("invalid NNTP credentials")
		}
		if u.Scheme == "nntps" {
			p.TLSConfig = &tls.Config{ServerName: u.Hostname(), RootCAs: roots, MinVersion: tls.VersionTLS12, ClientSessionCache: tls.NewLRUClientSessionCache(connections)}
		}
		ps = append(ps, p)
		total += connections
		if total > 4096 {
			return nil, fmt.Errorf("total NNTP connection allowance exceeds 4096")
		}
	}
	return ps, nil
}
