package engine

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// Each provider's articles, misses and bytes are counted for the session, named by
// its place in the request's server list rather than its place by priority.
func TestLeaseCountsWhatEachProviderServed(t *testing.T) {
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
	// The backup is listed first; the primary leads by priority.
	lease, err := pools.acquireForRequest(ctx, ctx, []nntppool.Provider{
		{Host: backup.Addr(), Connections: 2, SkipPing: true},
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
	if _, err := lease.BodyStream(ctx, "gone@test", io.Discard); err != nil {
		t.Fatal(err)
	}
	// A read cancelled before it starts counts for nobody.
	cancelled, stop := context.WithCancel(ctx)
	stop()
	lease.BodyStream(cancelled, "present@test", io.Discard)

	reports := lease.providerReports()
	if len(reports) != 2 {
		t.Fatalf("reports = %+v", reports)
	}
	got := map[int]providerReport{}
	for _, report := range reports {
		got[report.Server] = report
	}
	if p := got[1]; p.Articles != 5 || p.Missing != 1 || p.Errors != 0 || p.Bytes != 5*1024 {
		t.Fatalf("primary (server 1) = %+v, want 5 articles of 1024 bytes and 1 missing", p)
	}
	if b := got[0]; b.Articles != 1 || b.Missing != 0 || b.Errors != 0 || b.Bytes != 1024 {
		t.Fatalf("backup (server 0) = %+v, want the one missing article", b)
	}
}

// A duplicate account is counted under its first listing.
func TestLeaseReportsADuplicateUnderItsFirstListing(t *testing.T) {
	server, err := nntpserver.New(nntpserver.Config{ArticleSize: 512})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var pools providerPools
	provider := nntppool.Provider{Host: server.Addr(), Connections: 1, SkipPing: true}
	lease, err := pools.acquireForRequest(ctx, ctx, []nntppool.Provider{provider, provider}, []int{0, 0}, false)
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Close()
	if _, err := lease.BodyStream(ctx, "present@test", io.Discard); err != nil {
		t.Fatal(err)
	}
	reports := lease.providerReports()
	if len(reports) != 1 || reports[0].Server != 0 || reports[0].Articles != 1 {
		t.Fatalf("reports = %+v", reports)
	}
}

// Closing a session answers with what its providers did; a pool that does not
// count still answers 204 without a body.
func TestDeletingASessionReportsItsProviders(t *testing.T) {
	server, err := nntpserver.New(nntpserver.Config{ArticleSize: 256})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, &http.Client{})
	app.freeOSMemory = func() {}
	defer app.Close()
	lease, err := app.pools.acquireForRequest(ctx, ctx, []nntppool.Provider{{Host: server.Addr(), Connections: 1, SkipPing: true}}, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := lease.BodyStream(ctx, "present@test", io.Discard); err != nil {
		t.Fatal(err)
	}
	sctx, scancel := context.WithCancel(ctx)
	app.sessions["counted"] = &Session{ctx: sctx, cancel: scancel, store: NewStore(sctx, nil, 1<<20), pool: lease}
	other, ocancel := context.WithCancel(ctx)
	app.sessions["fake"] = &Session{ctx: other, cancel: ocancel, store: NewStore(other, nil, 1<<20), pool: closedPool{}}
	remove := func(id string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodDelete, "/sessions/"+id, nil)
		r.Header.Set("Authorization", "Bearer secret")
		w := httptest.NewRecorder()
		app.ServeHTTP(w, r)
		return w
	}
	w := remove("counted")
	if w.Code != http.StatusOK {
		t.Fatalf("DELETE = %d", w.Code)
	}
	var body struct {
		Providers []providerReport `json:"providers"`
	}
	if err := json.NewDecoder(w.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Providers) != 1 || body.Providers[0].Server != 0 || body.Providers[0].Articles != 1 || body.Providers[0].Bytes != 256 {
		t.Fatalf("providers = %+v", body.Providers)
	}
	if w := remove("fake"); w.Code != http.StatusNoContent || w.Body.Len() != 0 {
		t.Fatalf("DELETE without counts = %d %q", w.Code, w.Body.String())
	}
	if w := remove("unknown"); w.Code != http.StatusNoContent {
		t.Fatalf("DELETE of an unknown session = %d", w.Code)
	}
}
