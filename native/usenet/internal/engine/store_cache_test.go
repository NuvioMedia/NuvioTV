package engine

import (
	"bytes"
	"context"
	"fmt"
	"testing"
)

func TestReleasedArticlesAreReusedAndYieldToReadAhead(t *testing.T) {
	const segment = 1 << 20
	want := payload(5 * segment)
	_, store, server := setup(t, []inputFile{{"movie.mkv", want, []int{segment, segment, segment, segment, segment}}}, 0, 2, 4)
	store.limit = 4 * segment
	read := func(index int, speculative bool) {
		t.Helper()
		a := store.acquire(fmt.Sprintf("f0-s%d@test", index), speculative)
		if a == nil {
			t.Fatal("idle cache blocked read-ahead")
		}
		defer store.release(a)
		ctx := context.Background()
		if _, err := a.metadata(ctx); err != nil {
			t.Fatal(err)
		}
		got := make([]byte, segment)
		n, err := a.readAt(ctx, got, 0)
		if err != nil || n != segment || !bytes.Equal(got, want[index*segment:(index+1)*segment]) {
			t.Fatalf("article %d: %d bytes, %v", index, n, err)
		}
	}
	for i := 0; i < 4; i++ {
		read(i, false)
	}
	read(0, false) // A later HTTP Range/header reader reacquires a released article.
	if got := server.Counters().Bodies; got != 4 {
		t.Fatalf("released article downloaded again: %d BODYs", got)
	}
	read(4, true) // Speculation must evict history, not stall behind it.
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.used > store.limit*3/4 || store.allocated != 4*segment {
		t.Fatalf("cache failed to yield/reuse slabs: used=%d allocated=%d", store.used, store.allocated)
	}
	if store.entries["f0-s0@test"] == nil || store.entries["f0-s1@test"] != nil {
		t.Fatal("idle history did not evict in LRU order")
	}
	if got := server.Counters().Bodies; got != 5 {
		t.Fatalf("unexpected repeat transfers: %d BODYs", got)
	}
}
