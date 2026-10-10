package engine

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/javi11/nntppool/v4"
)

// Zero overrides follow the profile/provider configuration. Memory is a hard
// article-buffer budget; Go's runtime memory limit is set separately by main.
type Config struct {
	Profile               string `json:"profile"`
	ReadAhead             int    `json:"readAhead"`
	MaxConnections        int    `json:"maxConnections"`
	FastMKVStartup        bool   `json:"fastMkvStartup"`
	FastNZBFetch          bool   `json:"fastNzbFetch"`
	CacheNZB              bool   `json:"cacheNzb"`
	AllowPrivateNetwork   bool   `json:"allowPrivateNetwork"`
	HoleFilling           bool   `json:"holeFilling"`
	MaxMissingArticles    int    `json:"maxMissingArticles"`
	MaxConsecutiveMissing int    `json:"maxConsecutiveMissing"`
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
	if c.MaxMissingArticles < 0 || c.MaxMissingArticles > 50 || c.MaxConsecutiveMissing < 0 || c.MaxConsecutiveMissing > 10 {
		return t, fmt.Errorf("invalid hole filling limits")
	}
	if c.HoleFilling && c.MaxMissingArticles > 0 && c.MaxConsecutiveMissing > c.MaxMissingArticles {
		return t, fmt.Errorf("consecutive missing articles must not exceed the total limit")
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
	priorities, err := ProviderPriorities(servers)
	if err != nil {
		return nil, err
	}
	preferred := slices.Min(priorities)
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
		if priorities[i] > preferred {
			// Lower-priority providers only serve failover; do not hold idle sockets.
			p.MinConnections = 0
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
		p.Factory = providerDial(p.Host, p.TLSConfig, cfg.AllowPrivateNetwork)
		ps = append(ps, p)
		total += connections
		if total > 4096 {
			return nil, fmt.Errorf("total NNTP connection allowance exceeds 4096")
		}
	}
	return ps, nil
}

// ProviderPriorities reads each server's optional ?priority=N (0 when absent).
// Lower values are preferred; servers with equal values share article traffic.
func ProviderPriorities(servers []string) ([]int, error) {
	priorities := make([]int, len(servers))
	for i, raw := range servers {
		u, err := url.Parse(raw)
		if err != nil {
			return nil, fmt.Errorf("invalid Usenet server %d", i+1)
		}
		if s := u.Query().Get("priority"); s != "" {
			n, err := strconv.Atoi(s)
			if err != nil || n < 0 || n > 99 {
				return nil, fmt.Errorf("invalid Usenet server priority")
			}
			priorities[i] = n
		}
	}
	return priorities, nil
}
