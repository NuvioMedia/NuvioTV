package engine

import (
	"context"
	"io"
	"slices"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

func clientName(c *nntppool.Client) string { return c.Stats().Providers[0].Name }

func TestPriorityTiersBalanceWithinTierAndOrderFailover(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var pools providerPools
	lease, err := pools.acquireForRequest(ctx, ctx, []nntppool.Provider{
		{Host: "block", Name: "block", Connections: 50, SkipPing: true},
		{Host: "primary", Name: "primary", Connections: 30, SkipPing: true},
		{Host: "second", Name: "second", Connections: 10, SkipPing: true},
	}, []int{2, 0, 0}, false)
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Close()
	var names []string
	for _, client := range lease.clients {
		names = append(names, clientName(client))
	}
	if !slices.Equal(names, []string{"primary", "second", "block"}) || !slices.Equal(lease.tiers, []int{0, 0, 2}) {
		t.Fatalf("clients = %v tiers = %v, want preferred tier first in list order", names, lease.tiers)
	}
	counts := make([]int, len(lease.clients))
	for range 400 {
		order := lease.order()
		counts[order[0]]++
		if order[2] != 2 {
			t.Fatalf("order = %v, lower tier must be tried last", order)
		}
	}
	if counts[0] != 300 || counts[1] != 100 || counts[2] != 0 {
		t.Fatalf("dispatch share = %v, want [300 100 0]", counts)
	}
}

func TestLowerPriorityProviderOnlyServesMissingArticles(t *testing.T) {
	primary, err := nntpserver.New(nntpserver.Config{ArticleSize: 1024, Missing: map[string]struct{}{"gone@test": {}}})
	if err != nil {
		t.Fatal(err)
	}
	defer primary.Close()
	backup, err := nntpserver.New(nntpserver.Config{ArticleSize: 1024})
	if err != nil {
		t.Fatal(err)
	}
	defer backup.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var pools providerPools
	// The backup has more capacity and is listed first; priority still wins.
	lease, err := pools.acquireForRequest(ctx, ctx, []nntppool.Provider{
		{Host: backup.Addr(), Connections: 8, SkipPing: true},
		{Host: primary.Addr(), Connections: 1, SkipPing: true},
	}, []int{1, 0}, false)
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Close()
	for range 5 {
		if _, err := lease.BodyStream(ctx, "present@test", io.Discard); err != nil {
			t.Fatal(err)
		}
	}
	if got := backup.Counters().Bodies; got != 0 {
		t.Fatalf("backup BODY calls = %d, want 0 while the preferred provider has the article", got)
	}
	if _, err := lease.BodyStream(ctx, "gone@test", io.Discard); err != nil {
		t.Fatalf("missing article was not recovered from the backup: %v", err)
	}
	if got := backup.Counters().Bodies; got != 1 {
		t.Fatalf("backup BODY calls = %d, want 1", got)
	}
}

func TestProviderPrioritiesParseAndSkipBackupPrewarm(t *testing.T) {
	servers := []string{"nntps://a.test:563/20?priority=0", "nntps://b.test/20?priority=3", "nntp://c.test/4"}
	priorities, err := ProviderPriorities(servers)
	if err != nil || !slices.Equal(priorities, []int{0, 3, 0}) {
		t.Fatalf("priorities = %v, %v", priorities, err)
	}
	providers, err := Providers(servers, Config{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if providers[0].MinConnections == 0 || providers[1].MinConnections != 0 || providers[2].MinConnections == 0 {
		t.Fatalf("prewarm = %d %d %d, want only preferred providers prewarmed",
			providers[0].MinConnections, providers[1].MinConnections, providers[2].MinConnections)
	}
	if providers[1].Connections != 20 {
		t.Fatalf("connections = %d, priority query must not affect the allowance", providers[1].Connections)
	}
	for _, bad := range []string{"nntp://x.test/4?priority=-1", "nntp://x.test/4?priority=100", "nntp://x.test/4?priority=high"} {
		if _, err := Providers([]string{bad}, Config{}, nil); err == nil {
			t.Fatalf("%s was accepted", bad)
		}
	}
}

func TestGlobalConnectionLimitFavoursThePreferredTier(t *testing.T) {
	connections := func(max int, servers ...string) []int {
		t.Helper()
		providers, err := Providers(servers, Config{MaxConnections: max}, nil)
		if err != nil {
			t.Fatal(err)
		}
		var got []int
		for _, p := range providers {
			got = append(got, p.Connections)
		}
		return got
	}
	for _, tc := range []struct {
		name    string
		max     int
		servers []string
		want    []int
	}{
		{"one tier keeps the even split", 10, []string{"nntp://a.test/20", "nntp://b.test/20"}, []int{5, 5}},
		{"backups keep one connection", 10, []string{"nntp://a.test/20", "nntp://b.test/20", "nntp://c.test/20?priority=1"}, []int{4, 5, 1}},
		{"backups take what the preferred tier cannot use", 10, []string{"nntp://a.test/2", "nntp://c.test/20?priority=1"}, []int{2, 8}},
		{"list order does not outrank priority", 6, []string{"nntp://c.test/20?priority=2", "nntp://b.test/20?priority=1", "nntp://a.test/20"}, []int{1, 1, 4}},
		{"no limit keeps allowances", 0, []string{"nntp://a.test/20", "nntp://c.test/30?priority=1"}, []int{20, 30}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := connections(tc.max, tc.servers...); !slices.Equal(got, tc.want) {
				t.Fatalf("connections = %v, want %v", got, tc.want)
			}
		})
	}
	if _, err := Providers([]string{"nntp://a.test/20", "nntp://c.test/20?priority=1"}, Config{MaxConnections: 1}, nil); err == nil {
		t.Fatal("a limit below one connection per server was accepted")
	}
}
