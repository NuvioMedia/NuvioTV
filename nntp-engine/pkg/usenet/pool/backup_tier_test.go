package pool

import (
	"context"
	"errors"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
)

// tieredPool builds one primary and one backup provider, each backed by its own
// counting server, so a test can see exactly which tier was charged.
func tieredPool(t *testing.T, primaryMissing, backupMissing bool) (*Pool, *countingNNTPServer, *countingNNTPServer, func()) {
	t.Helper()
	primarySrv, primaryHost, primaryPort, stopPrimary := startCountingNNTPServer(t, primaryMissing)
	backupSrv, backupHost, backupPort, stopBackup := startCountingNNTPServer(t, backupMissing)

	primaryPool := nntp.NewClientPool(primaryHost, primaryPort, false, "user", "pass", 4)
	backupPool := nntp.NewClientPool(backupHost, backupPort, false, "user", "pass", 4)

	p, err := NewPool(&Config{Providers: []ProviderConfig{
		{ID: "primary", Priority: 0, ClientPool: primaryPool},
		{ID: "backup", Priority: 1, IsBackup: true, ClientPool: backupPool},
	}})
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}
	return p, primarySrv, backupSrv, func() {
		primaryPool.Shutdown()
		backupPool.Shutdown()
		stopPrimary()
		stopBackup()
	}
}

// The whole point of the tier: the first-segment race is the primaries', so a
// metered backup is not charged for an article a primary already has.
func TestFetchSegmentFirstDoesNotRaceBackup(t *testing.T) {
	p, primary, backup, cleanup := tieredPool(t, false, false)
	defer cleanup()

	if _, err := p.FetchSegmentFirst(context.Background(), &nzb.Segment{ID: "seg-1"}, []string{"alt.binaries.test"}); err != nil {
		t.Fatalf("fetch first segment: %v", err)
	}
	if got := primary.bodies.Load(); got != 1 {
		t.Fatalf("expected 1 body from the primary, got %d", got)
	}
	if got := backup.bodies.Load(); got != 0 {
		t.Fatalf("expected the backup to stay out of the race, got %d bodies", got)
	}
}

func TestFetchSegmentDoesNotUseBackupWhilePrimaryServes(t *testing.T) {
	p, primary, backup, cleanup := tieredPool(t, false, false)
	defer cleanup()

	if _, err := p.FetchSegment(context.Background(), &nzb.Segment{ID: "seg-2"}, []string{"alt.binaries.test"}); err != nil {
		t.Fatalf("fetch segment: %v", err)
	}
	if got := primary.bodies.Load(); got != 1 {
		t.Fatalf("expected 1 body from the primary, got %d", got)
	}
	if got := backup.bodies.Load(); got != 0 {
		t.Fatalf("expected the backup to be untouched, got %d bodies", got)
	}
}

// ...and once the primary has missed, the backup is what saves the segment.
func TestFetchSegmentFallsBackToBackupTier(t *testing.T) {
	p, primary, backup, cleanup := tieredPool(t, true, false)
	defer cleanup()

	data, err := p.FetchSegment(context.Background(), &nzb.Segment{ID: "seg-3"}, []string{"alt.binaries.test"})
	if err != nil {
		t.Fatalf("expected the backup to serve the segment: %v", err)
	}
	if len(data.Body) == 0 {
		t.Fatal("expected a decoded body from the backup")
	}
	if got := primary.bodies.Load(); got != 1 {
		t.Fatalf("expected the primary to be tried once, got %d", got)
	}
	if got := backup.bodies.Load(); got != 1 {
		t.Fatalf("expected exactly one backup body, got %d", got)
	}
}

func TestFetchSegmentFirstFallsBackToBackupTier(t *testing.T) {
	p, _, backup, cleanup := tieredPool(t, true, false)
	defer cleanup()

	data, err := p.FetchSegmentFirst(context.Background(), &nzb.Segment{ID: "seg-4"}, []string{"alt.binaries.test"})
	if err != nil {
		t.Fatalf("expected the backup to serve the first segment: %v", err)
	}
	if len(data.Body) == 0 {
		t.Fatal("expected a decoded body from the backup")
	}
	if got := backup.bodies.Load(); got != 1 {
		t.Fatalf("expected exactly one backup body, got %d", got)
	}
}

// STAT costs no article bytes, and knowing whether the backup carries the
// article is what makes it usable as a fallback at all.
func TestStatSegmentConsultsBackupTier(t *testing.T) {
	p, primary, backup, cleanup := tieredPool(t, true, false)
	defer cleanup()

	exists, err := p.StatSegment(context.Background(), "seg-5", []string{"alt.binaries.test"})
	if err != nil {
		t.Fatalf("stat segment: %v", err)
	}
	if !exists {
		t.Fatal("expected the backup to confirm the article")
	}
	if got := primary.stats.Load(); got == 0 {
		t.Fatal("expected the primary to be STATed first")
	}
	if got := backup.stats.Load(); got != 1 {
		t.Fatalf("expected exactly one backup STAT, got %d", got)
	}
}

func TestGetConnectionSkipsBackupTier(t *testing.T) {
	p, _, _, cleanup := tieredPool(t, false, false)
	defer cleanup()

	_, release, _, providerID, err := p.getConnection(context.Background(), []string{"primary"}, 999, false)
	if err == nil {
		release()
		t.Fatalf("expected no provider available, got a connection to %q", providerID)
	}
	if !errors.Is(err, ErrNoProvidersAvailable) {
		t.Fatalf("expected ErrNoProvidersAvailable, got %v", err)
	}

	conn, release, _, providerID, err := p.getConnection(context.Background(), nil, 999, true)
	if err != nil {
		t.Fatalf("expected the backup tier to hand out a connection: %v", err)
	}
	defer release()
	if conn == nil || providerID != "backup" {
		t.Fatalf("expected the backup provider, got %q", providerID)
	}
}

// A tier with no primary above it has nothing to fall back from, so the flag is
// dropped rather than leaving the view unable to fetch anything.
func TestAllBackupProvidersActAsPrimaries(t *testing.T) {
	srv, host, port, stop := startCountingNNTPServer(t, false)
	defer stop()
	cp := nntp.NewClientPool(host, port, false, "user", "pass", 2)
	defer cp.Shutdown()

	p, err := NewPool(&Config{Providers: []ProviderConfig{
		{ID: "only", Priority: 0, IsBackup: true, ClientPool: cp},
	}})
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}
	if p.HasBackupProviders() {
		t.Fatal("expected the backup flag to be dropped when every provider carries it")
	}
	if _, err := p.FetchSegmentFirst(context.Background(), &nzb.Segment{ID: "seg-6"}, []string{"alt.binaries.test"}); err != nil {
		t.Fatalf("fetch first segment: %v", err)
	}
	if got := srv.bodies.Load(); got != 1 {
		t.Fatalf("expected the lone provider to serve, got %d bodies", got)
	}
}

// A stream may select only what the parent holds back; that subset has to be
// able to serve it.
func TestSubsetOfOnlyBackupActsAsPrimary(t *testing.T) {
	p, _, backup, cleanup := tieredPool(t, false, false)
	defer cleanup()

	subset := p.Subset([]string{"backup"})
	if subset == nil {
		t.Fatal("expected a subset")
	}
	if subset.HasBackupProviders() {
		t.Fatal("expected the backup flag to be dropped in a backup-only subset")
	}
	if _, err := subset.FetchSegmentFirst(context.Background(), &nzb.Segment{ID: "seg-7"}, []string{"alt.binaries.test"}); err != nil {
		t.Fatalf("fetch first segment: %v", err)
	}
	if got := backup.bodies.Load(); got != 1 {
		t.Fatalf("expected the subset's only provider to serve, got %d bodies", got)
	}
}

// The backup tier is not counted into read-ahead's width: those connections are
// not there to be spent on ordinary streaming.
func TestFetchConcurrencyExcludesBackupTier(t *testing.T) {
	p, _, _, cleanup := tieredPool(t, false, false)
	defer cleanup()

	if got := p.FetchConcurrency(); got != 4 {
		t.Fatalf("expected only the primary's 4 connections, got %d", got)
	}
}
