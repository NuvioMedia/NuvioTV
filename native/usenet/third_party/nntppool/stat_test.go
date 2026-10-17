package nntppool

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// makeStatByIDFactory returns a ConnFactory whose mock server answers STAT
// commands based on message-id. replies maps a bare message-id to the status
// line to return; ids absent from the map get a 430. Every received command
// (except DATE pings) is appended to cmdLog under mu.
func makeStatByIDFactory(t testing.TB, mu *sync.Mutex, cmdLog *[]string, replies map[string]string) ConnFactory {
	if t != nil {
		t.Helper()
	}
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
				cmd := strings.TrimRight(string(buf[:n]), "\r\n")
				if strings.HasPrefix(cmd, "DATE") {
					_, _ = server.Write([]byte("111 20240101000000\r\n"))
					continue
				}
				if mu != nil {
					mu.Lock()
					*cmdLog = append(*cmdLog, cmd)
					mu.Unlock()
				}
				reply := "430 no such article"
				if strings.HasPrefix(cmd, "STAT ") {
					id := strings.Trim(strings.TrimPrefix(cmd, "STAT "), "<>")
					if r, ok := replies[id]; ok {
						reply = r
					}
				}
				_, _ = fmt.Fprintf(server, "%s\r\n", reply)
			}
		}()
		return client, nil
	}
}

// collectStat drains a StatMany/StatAsync channel into a map keyed by message-id.
func collectStat(ch <-chan StatManyResult) map[string]StatManyResult {
	m := make(map[string]StatManyResult)
	for r := range ch {
		m[r.MessageID] = r
	}
	return m
}

func TestStatMany_AllExist(t *testing.T) {
	ids := []string{"a@h", "b@h", "c@h"}
	replies := map[string]string{
		"a@h": "223 1 <a@h> exists",
		"b@h": "223 2 <b@h> exists",
		"c@h": "223 3 <c@h> exists",
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 3,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{}))
	if len(got) != len(ids) {
		t.Fatalf("got %d results, want %d", len(got), len(ids))
	}
	for _, id := range ids {
		r, ok := got[id]
		if !ok {
			t.Fatalf("missing result for %s", id)
		}
		if r.Err != nil {
			t.Errorf("%s: unexpected err %v", id, r.Err)
		}
		if r.Result == nil {
			t.Fatalf("%s: nil result", id)
		}
		if r.Result.MessageID != id {
			t.Errorf("%s: MessageID = %q", id, r.Result.MessageID)
		}
	}
}

func TestStatMany_MissingIsNonFatal(t *testing.T) {
	ids := []string{"hit@h", "miss@h"}
	replies := map[string]string{
		"hit@h": "223 1 <hit@h> exists",
		// miss@h absent => 430
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 2,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{}))
	if len(got) != 2 {
		t.Fatalf("got %d results, want 2", len(got))
	}
	if r := got["hit@h"]; r.Err != nil || r.Result == nil {
		t.Errorf("hit: err=%v result=%v", r.Err, r.Result)
	}
	miss := got["miss@h"]
	if !errors.Is(miss.Err, ErrArticleNotFound) {
		t.Errorf("miss: err = %v, want ErrArticleNotFound", miss.Err)
	}
	if miss.Result != nil {
		t.Errorf("miss: result = %v, want nil", miss.Result)
	}
}

func TestStatReportsSuccessfulProviderAfterFallback(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{
		{
			Host:        "primary.example:119",
			Factory:     makeStatByIDFactory(t, nil, nil, nil),
			Connections: 1,
		},
		{
			Host: "backup.example:119",
			Factory: makeStatByIDFactory(t, nil, nil, map[string]string{
				"x@h": "223 1 <x@h> exists",
			}),
			Connections: 1,
			Backup:      true,
		},
	}, WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	result, err := c.Stat(context.Background(), "x@h")
	if err != nil {
		t.Fatal(err)
	}
	if result.Provider != "backup.example:119" {
		t.Fatalf("Provider = %q, want backup.example:119", result.Provider)
	}
}

func TestStatMany_Completeness(t *testing.T) {
	const total = 200
	ids := make([]string, total)
	replies := make(map[string]string, total)
	for i := range ids {
		id := fmt.Sprintf("seg%d@h", i)
		ids[i] = id
		replies[id] = fmt.Sprintf("223 %d <%s> exists", i, id)
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 8,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{Concurrency: 16}))
	if len(got) != total {
		t.Fatalf("got %d unique results, want %d", len(got), total)
	}
	for _, id := range ids {
		if r, ok := got[id]; !ok || r.Result == nil {
			t.Fatalf("bad/missing result for %s: %+v", id, r)
		}
	}
}

func TestStatMany_ContextCancel(t *testing.T) {
	const total = 500
	ids := make([]string, total)
	replies := make(map[string]string, total)
	for i := range ids {
		id := fmt.Sprintf("seg%d@h", i)
		ids[i] = id
		replies[id] = fmt.Sprintf("223 %d <%s> exists", i, id)
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 4,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ch := c.StatMany(ctx, ids, StatManyOptions{Concurrency: 4})
	// Read a few then cancel.
	count := 0
	for range ch {
		count++
		if count == 5 {
			cancel()
		}
	}
	// Channel must close (loop exits) without panic/deadlock. We don't assert an
	// exact count — cancellation is racy — only that it terminates.
	if count == 0 {
		t.Fatal("expected at least some results before cancel")
	}
}

func TestStatMany_ProviderTargeting(t *testing.T) {
	var mu1, mu2 sync.Mutex
	var p1Cmds, p2Cmds []string
	replies := map[string]string{"x@h": "223 1 <x@h> exists"}

	c, err := NewClient(context.Background(), []Provider{
		{
			Host:        "provider-one:119",
			Factory:     makeStatByIDFactory(t, &mu1, &p1Cmds, replies),
			Connections: 2,
		},
		{
			Host:        "provider-two:119",
			Factory:     makeStatByIDFactory(t, &mu2, &p2Cmds, replies),
			Connections: 2,
		},
	}, WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(),
		[]string{"x@h"}, StatManyOptions{Provider: "provider-two:119"}))
	if r := got["x@h"]; r.Err != nil || r.Result == nil {
		t.Fatalf("x@h: err=%v result=%v", r.Err, r.Result)
	}

	mu1.Lock()
	defer mu1.Unlock()
	mu2.Lock()
	defer mu2.Unlock()
	if len(p1Cmds) != 0 {
		t.Errorf("provider-one received %v, want none (targeted provider-two)", p1Cmds)
	}
	if len(p2Cmds) == 0 {
		t.Errorf("provider-two received no STAT commands")
	}
}

func TestStatMany_UnknownProvider(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, map[string]string{"a@h": "223 1 <a@h> exists"}),
		Connections: 1,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(),
		[]string{"a@h"}, StatManyOptions{Provider: "does-not-exist"}))
	if r := got["a@h"]; r.Err == nil {
		t.Errorf("want error for unknown provider, got result %v", r.Result)
	}
}

// TestStatMany_SkipOmitsResult pins the contract: a skipped id emits no STAT
// command and no result, while every non-skipped id still gets exactly one.
func TestStatMany_SkipOmitsResult(t *testing.T) {
	var mu sync.Mutex
	var cmdLog []string
	ids := []string{"a@h", "b@h", "c@h"}
	replies := map[string]string{
		"a@h": "223 1 <a@h> exists",
		"b@h": "223 2 <b@h> exists",
		"c@h": "223 3 <c@h> exists",
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, &mu, &cmdLog, replies),
		Connections: 3,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{
		Skip: func(messageID string) bool { return messageID == "b@h" },
	}))

	if _, ok := got["b@h"]; ok {
		t.Errorf("skipped id b@h produced a result: %+v", got["b@h"])
	}
	for _, id := range []string{"a@h", "c@h"} {
		r, ok := got[id]
		if !ok {
			t.Fatalf("missing result for non-skipped id %s", id)
		}
		if r.Err != nil || r.Result == nil {
			t.Errorf("%s: err=%v result=%v", id, r.Err, r.Result)
		}
	}
	if len(got) != 2 {
		t.Fatalf("got %d results, want 2 (skipped id must not appear)", len(got))
	}

	mu.Lock()
	defer mu.Unlock()
	for _, cmd := range cmdLog {
		if strings.Contains(cmd, "b@h") {
			t.Errorf("skipped id b@h was STATed: %q", cmd)
		}
	}
}

// TestStatMany_SkipStopsFurtherDispatch confirms Skip can be flipped mid-sweep
// to abandon the remaining ids without chunking: once it starts returning
// true, none of the later ids should ever reach the wire.
func TestStatMany_SkipStopsFurtherDispatch(t *testing.T) {
	var mu sync.Mutex
	var cmdLog []string
	const n = 50
	const cutoff = 20 // ids at/after this index are abandoned
	ids := make([]string, n)
	replies := make(map[string]string, n)
	for i := range ids {
		ids[i] = fmt.Sprintf("s%02d@h", i)
		replies[ids[i]] = fmt.Sprintf("223 %d <%s> exists", i, ids[i])
	}

	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, &mu, &cmdLog, replies),
		Connections: 2,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	var abandoned atomic.Bool
	skip := func(messageID string) bool {
		if abandoned.Load() {
			return true
		}
		// Flip once we see the id that marks the cutoff, so every id from
		// cutoff onward is abandoned (dispatch order isn't guaranteed, but the
		// cutoff id itself is always observed by the predicate before being
		// STATed, since Skip runs before dispatch).
		if messageID == ids[cutoff] {
			abandoned.Store(true)
			return true
		}
		return false
	}

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{
		Concurrency: 1, // serial dispatch makes the cutoff point deterministic
		Skip:        skip,
	}))

	for i := cutoff; i < n; i++ {
		if _, ok := got[ids[i]]; ok {
			t.Errorf("id %s at/after cutoff produced a result", ids[i])
		}
	}
	for i := range cutoff {
		if _, ok := got[ids[i]]; !ok {
			t.Errorf("id %s before cutoff is missing a result", ids[i])
		}
	}

	mu.Lock()
	defer mu.Unlock()
	for i := cutoff; i < n; i++ {
		for _, cmd := range cmdLog {
			if strings.Contains(cmd, ids[i]) {
				t.Errorf("abandoned id %s was STATed: %q", ids[i], cmd)
			}
		}
	}
}

// TestStatMany_SkipNil is a regression guard: a nil Skip must behave exactly
// as if the option didn't exist.
func TestStatMany_SkipNil(t *testing.T) {
	ids := []string{"a@h", "b@h"}
	replies := map[string]string{
		"a@h": "223 1 <a@h> exists",
		"b@h": "223 2 <b@h> exists",
	}
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 2,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{Skip: nil}))
	if len(got) != len(ids) {
		t.Fatalf("got %d results, want %d", len(got), len(ids))
	}
	for _, id := range ids {
		if r := got[id]; r.Err != nil || r.Result == nil {
			t.Errorf("%s: err=%v result=%v", id, r.Err, r.Result)
		}
	}
}

// TestStatMany_SkipConcurrencySafety exercises Skip under -race with a wide
// concurrency and a closure that reads/writes shared state behind a mutex, to
// confirm Skip is safe to call from every dispatch goroutine concurrently.
func TestStatMany_SkipConcurrencySafety(t *testing.T) {
	const n = 300
	ids := make([]string, n)
	replies := make(map[string]string, n)
	for i := range ids {
		ids[i] = fmt.Sprintf("r%03d@h", i)
		replies[ids[i]] = fmt.Sprintf("223 %d <%s> exists", i, ids[i])
	}

	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 8,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	var mu sync.Mutex
	seenBySkip := make(map[string]bool, n)
	skip := func(messageID string) bool {
		mu.Lock()
		defer mu.Unlock()
		seenBySkip[messageID] = true
		// Skip every other id (by parity of its numeric suffix) to exercise a
		// mixed skip/non-skip workload under race detection.
		var idx int
		_, _ = fmt.Sscanf(messageID, "r%03d@h", &idx)
		return idx%2 == 0
	}

	got := collectStat(c.StatMany(context.Background(), ids, StatManyOptions{
		Concurrency: 128,
		Skip:        skip,
	}))

	for i, id := range ids {
		_, hasResult := got[id]
		wantSkipped := i%2 == 0
		if wantSkipped && hasResult {
			t.Errorf("%s: expected skip, got result", id)
		}
		if !wantSkipped && !hasResult {
			t.Errorf("%s: expected result, got none", id)
		}
	}

	mu.Lock()
	defer mu.Unlock()
	if len(seenBySkip) != n {
		t.Fatalf("Skip observed %d distinct ids, want %d", len(seenBySkip), n)
	}
}

func TestStatPriority(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, map[string]string{"p@h": "223 7 <p@h> exists"}),
		Connections: 1,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	res, err := c.StatPriority(context.Background(), "p@h")
	if err != nil {
		t.Fatalf("StatPriority: %v", err)
	}
	if res.Number != 7 || res.MessageID != "p@h" {
		t.Errorf("got %+v, want Number=7 MessageID=p@h", res)
	}
}

func TestStatAsync(t *testing.T) {
	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, map[string]string{"async@h": "223 9 <async@h> exists"}),
		Connections: 1,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	select {
	case r := <-c.StatAsync(context.Background(), "async@h"):
		if r.Err != nil {
			t.Fatalf("StatAsync err: %v", r.Err)
		}
		if r.Result == nil || r.Result.Number != 9 {
			t.Errorf("got %+v, want Number=9", r.Result)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("StatAsync timed out")
	}
}

func TestStatCapacity(t *testing.T) {
	replies := map[string]string{}
	tests := []struct {
		name      string
		providers []Provider
		want      int
	}{
		{
			name: "sums connections times stat depth across providers",
			providers: []Provider{
				{Factory: makeStatByIDFactory(t, nil, nil, replies), Connections: 3, Inflight: 2, StatInflight: 100},
				{Factory: makeStatByIDFactory(t, nil, nil, replies), Connections: 2, StatInflight: 50, Backup: true},
			},
			want: 3*100 + 2*50,
		},
		{
			name: "floors at minStatConcurrency",
			providers: []Provider{
				{Factory: makeStatByIDFactory(t, nil, nil, replies), Connections: 1, Inflight: 1},
			},
			want: minStatConcurrency,
		},
		{
			name: "caps at maxStatConcurrency",
			providers: []Provider{
				{Factory: makeStatByIDFactory(t, nil, nil, replies), Connections: 100, StatInflight: 100},
			},
			want: maxStatConcurrency,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, err := NewClient(context.Background(), tt.providers)
			if err != nil {
				t.Fatal(err)
			}
			defer func() { _ = c.Close() }()

			if got := c.StatCapacity(); got != tt.want {
				t.Errorf("StatCapacity() = %d, want %d", got, tt.want)
			}
		})
	}
}

// TestStatMany_CompletenessUnderWideConcurrency pins the property the dispatch
// rewrite must preserve: every id yields exactly one result, with no duplicates
// and none dropped, even when workers vastly outnumber the ids they share.
func TestStatMany_CompletenessUnderWideConcurrency(t *testing.T) {
	const n = 500
	ids := make([]string, n)
	replies := make(map[string]string, n)
	for i := range ids {
		ids[i] = fmt.Sprintf("m%04d@h", i)
		replies[ids[i]] = fmt.Sprintf("223 %d <%s> exists", i, ids[i])
	}

	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 4,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	seen := make(map[string]int, n)
	for res := range c.StatMany(context.Background(), ids, StatManyOptions{Concurrency: 256}) {
		seen[res.MessageID]++
	}

	if len(seen) != n {
		t.Fatalf("got %d distinct results, want %d", len(seen), n)
	}
	for id, count := range seen {
		if count != 1 {
			t.Errorf("%s: %d results, want exactly 1", id, count)
		}
	}
}

// TestStatMany_CancelMidSweepClosesChannel pins the documented contract: on
// cancellation dispatch stops, in-flight checks are cancelled, and the channel
// is closed, so a caller ranging over it always terminates.
func TestStatMany_CancelMidSweepClosesChannel(t *testing.T) {
	const n = 2000
	ids := make([]string, n)
	replies := make(map[string]string, n)
	for i := range ids {
		ids[i] = fmt.Sprintf("c%04d@h", i)
		replies[ids[i]] = fmt.Sprintf("223 %d <%s> exists", i, ids[i])
	}

	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(t, nil, nil, replies),
		Connections: 2,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ch := c.StatMany(ctx, ids, StatManyOptions{Concurrency: 64})

	got := 0
	for range ch {
		got++
		if got == 10 {
			cancel()
		}
	}
	// Reaching here at all is the assertion: the range terminated, so the
	// channel was closed rather than leaked.
	if got >= n {
		t.Fatalf("drained all %d results despite cancelling after 10", n)
	}
	if ctx.Err() == nil {
		t.Fatal("context should be cancelled")
	}
}

// BenchmarkStatManyDispatch isolates dispatch cost: every STAT is answered
// immediately, so what it measures is the hand-off machinery, not the wire.
func BenchmarkStatManyDispatch(b *testing.B) {
	const n = 4096
	ids := make([]string, n)
	replies := make(map[string]string, n)
	for i := range ids {
		ids[i] = fmt.Sprintf("b%04d@h", i)
		replies[ids[i]] = fmt.Sprintf("223 %d <%s> exists", i, ids[i])
	}

	c, err := NewClient(context.Background(), []Provider{{
		Factory:     makeStatByIDFactory(nil, nil, nil, replies),
		Connections: 8,
	}})
	if err != nil {
		b.Fatal(err)
	}
	defer func() { _ = c.Close() }()

	b.ResetTimer()
	for range b.N {
		for range c.StatMany(context.Background(), ids, StatManyOptions{Concurrency: n}) {
		}
	}
}
