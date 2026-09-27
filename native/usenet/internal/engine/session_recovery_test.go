package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

func TestReplacementSharesConnectionAndReconcilesLostDelete(t *testing.T) {
	want := payload(512 << 10)
	xml, articles := fixture([]inputFile{{"movie.mkv", want, nil}})
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	nzb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	defer nzb.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, nzb.Client())
	defer app.Close()
	srv := httptest.NewServer(app)
	defer srv.Close()
	open := func(keep string, scope string) (string, string) {
		t.Helper()
		b, _ := json.Marshal(OpenRequest{KeepSessionID: &keep, CacheScope: scope, NZBURL: nzb.URL,
			Servers: []string{"nntp://" + provider.Addr() + "/1"}})
		r, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
		r.Header.Set("Authorization", "Bearer secret")
		resp, err := srv.Client().Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var result struct{ ID, Path string }
		if resp.StatusCode != 200 {
			b, _ := io.ReadAll(resp.Body)
			t.Fatalf("open: %d %s", resp.StatusCode, b)
		}
		if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
			t.Fatal(err)
		}
		return result.ID, result.Path
	}
	read := func(path string) {
		t.Helper()
		resp, err := srv.Client().Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		got, err := io.ReadAll(resp.Body)
		if err != nil || !bytes.Equal(got, want) {
			t.Fatalf("read: status=%d len=%d err=%v", resp.StatusCode, len(got), err)
		}
	}
	first, firstPath := open("", "one")
	second, secondPath := open(first, "two")
	read(firstPath) // Preparing the replacement did not cancel existing playback.
	read(secondPath)
	if got := provider.Counters().Conns; got != 1 {
		t.Fatalf("replacement opened %d connections; allowance is 1", got)
	}
	// Simulate a DELETE that never arrived. The next open retains only second.
	third, thirdPath := open(second, "three")
	app.mu.Lock()
	_, stale := app.sessions[first]
	count := len(app.sessions)
	app.mu.Unlock()
	if stale || count != 2 {
		t.Fatalf("orphan retained: stale=%v sessions=%d", stale, count)
	}
	read(secondPath)
	read(thirdPath)
	// A lost open response is likewise collected by retaining the known owner.
	_, fourthPath := open(second, "four")
	app.mu.Lock()
	_, stale = app.sessions[third]
	app.mu.Unlock()
	if stale {
		t.Fatal("lost-response session retained")
	}
	read(fourthPath)
	if got := provider.Counters().Conns; got != 1 {
		t.Fatalf("reconciliation redialed shared provider: %d", got)
	}
}

func TestPartiallyOverlappingProviderListsShareSocketsAndFailOver(t *testing.T) {
	_, articles := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	missing, err := nntpserver.New(nntpserver.Config{Missing: map[string]struct{}{"f0-s0@test": {}}})
	if err != nil {
		t.Fatal(err)
	}
	defer missing.Close()
	available, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer available.Close()
	var pools providerPools
	a := nntppool.Provider{Host: missing.Addr(), Connections: 1, MinConnections: 1, SkipPing: true}
	b := nntppool.Provider{Host: available.Addr(), Connections: 1, MinConnections: 1, SkipPing: true}
	first, err := pools.acquire(context.Background(), []nntppool.Provider{a})
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := pools.acquire(context.Background(), []nntppool.Provider{a, b, a})
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if first.clients[0] != second.clients[0] || len(second.clients) != 2 {
		t.Fatal("overlapping/duplicate provider was not shared")
	}
	var got bytes.Buffer
	if _, err := second.BodyStreamPriority(context.Background(), "f0-s0@test", &got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.Bytes(), payload(1024)) {
		t.Fatal("missing-article fallback corrupted payload")
	}
	first.Close()
	got.Reset()
	if _, err := second.BodyStream(context.Background(), "f0-s0@test", &got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.Bytes(), payload(1024)) {
		t.Fatal("closing one lease interrupted the other")
	}
	if missing.Counters().Conns != 1 || available.Counters().Conns != 1 {
		t.Fatal("provider allowance exceeded")
	}
	second.Close()
	if len(pools.entries) != 0 {
		t.Fatal("last lease retained providers")
	}
}

func TestExplicitUnknownVideoDoesNotProbeUnrelatedMissingArticle(t *testing.T) {
	want := append([]byte{0x1a, 0x45, 0xdf, 0xa3}, payload(1024)...)
	xml, articles := fixture([]inputFile{{"unavailable", payload(1024), nil}, {"selected", want, nil}})
	delete(articles, "f0-s0@test")
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles, Missing: map[string]struct{}{"f0-s0@test": {}}})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	var pools providerPools
	ps, err := Providers([]string{"nntp://" + provider.Addr() + "/1"}, Config{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	lease, err := pools.acquire(context.Background(), ps)
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Close()
	store := NewStore(context.Background(), lease, 32<<20)
	defer store.Close()
	files, err := ParseNZB(strings.NewReader(xml), store)
	if err != nil {
		t.Fatal(err)
	}
	index := 1
	content, err := Select(context.Background(), files, Selection{FileIdx: &index})
	if err != nil {
		t.Fatal(err)
	}
	r := content.Reader(context.Background(), 0)
	defer r.Close()
	got, err := io.ReadAll(r)
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("selected read: %v", err)
	}
	if got := provider.Counters().Bodies; got != 1 {
		t.Fatalf("probed unrelated file: %d BODY requests", got)
	}
}
