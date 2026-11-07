package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"testing"
	"time"
)

func cuesPrefix(offset uint64) []byte {
	// EBML header (empty), unknown-size Segment, SeekHead/Seek with an
	// eight-byte SeekPosition. The Segment payload begins at byte 10.
	b := []byte{0x1a, 0x45, 0xdf, 0xa3, 0x80, 0x18, 0x53, 0x80, 0x67, 0xff,
		0x11, 0x4d, 0x9b, 0x74, 0x95, 0x4d, 0xbb, 0x92,
		0x53, 0xab, 0x84, 0x1c, 0x53, 0xbb, 0x6b, 0x53, 0xac, 0x88}
	return binary.BigEndian.AppendUint64(b, offset)
}

func TestCuesOffsetBoundedAndSegmentRelative(t *testing.T) {
	b := cuesPrefix(5000)
	if got, ok := mkvCuesOffset(b, 10000); !ok || got != 5010 {
		t.Fatalf("offset=%d found=%v", got, ok)
	}
	for i := 0; i < len(b); i++ {
		if _, ok := mkvCuesOffset(b[:i], 10000); ok {
			t.Fatalf("truncated prefix accepted: %d", i)
		}
	}
	for _, invalid := range [][]byte{cuesPrefix(^uint64(0)), cuesPrefix(10000), {0}, {0xff, 0xff}, bytes.Repeat([]byte{0}, 256<<10)} {
		if _, ok := mkvCuesOffset(invalid, 10000); ok {
			t.Fatal("invalid Cues pointer accepted")
		}
	}
}

func TestStartupPinsSurviveRangeRoundTripAndRelease(t *testing.T) {
	for _, archive := range []bool{false, true} {
		t.Run(map[bool]string{false: "direct", true: "stored_rar"}[archive], func(t *testing.T) {
			want := payload(2 << 20)
			copy(want, cuesPrefix(uint64(len(want)-1000-10)))
			in := []inputFile{{"movie.mkv", want, nil}}
			if archive {
				in = []inputFile{{"movie.part1.rar", rar4Volume("movie.mkv", want[:1<<20], len(want), 2, false), nil}, {"movie.part2.rar", rar4Volume("movie.mkv", want[1<<20:], len(want), 1, false), nil}}
			}
			files, store, _ := setup(t, in, 10*time.Millisecond, 8, 4)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			content, err := Select(ctx, files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			s := &Session{ctx: ctx, content: content, store: store, trace: newStartupTrace()}
			s.startMKVWarmup(true, 8)
			defer s.warmup.Close()
			deadline := time.Now().Add(3 * time.Second)
			for {
				marks := s.trace.snapshot()["marksMs"].(map[string]float64)
				_, head := marks["head_article_ready"]
				_, tail := marks["tail_article_ready"]
				if head && tail {
					break
				}
				if time.Now().After(deadline) {
					t.Fatalf("warmup did not finish: %v", marks)
				}
				time.Sleep(time.Millisecond)
			}
			for _, off := range []int64{0, int64(len(want) - 512), 32} {
				r := content.Reader(ctx, 0)
				r.Seek(off, io.SeekStart)
				buf := make([]byte, 512)
				_, err = io.ReadFull(r, buf)
				r.Close()
				if err != nil || !bytes.Equal(buf, want[off:off+512]) {
					t.Fatalf("round trip at %d: %v", off, err)
				}
			}
			store.mu.Lock()
			hits := store.stats.CacheHits
			allocated := store.allocated
			store.mu.Unlock()
			if hits < 3 || allocated > store.limit {
				t.Fatalf("hits=%d allocated=%d", hits, allocated)
			}
			s.warmup.Close()
			store.mu.Lock()
			defer store.mu.Unlock()
			for _, a := range store.entries {
				if a.refs != 0 {
					t.Fatal("startup retained a reader after cancellation")
				}
			}
		})
	}
}

func TestStartupDisabledNonMKVAndSmallPool(t *testing.T) {
	for _, tc := range []struct {
		enabled     bool
		name        string
		connections int
	}{{false, "movie.mkv", 8}, {true, "movie.mp4", 8}, {true, "movie.mkv", 1}, {true, "movie.mkv", 2}} {
		s := &Session{content: &Content{Name: tc.name}, trace: newStartupTrace()}
		s.startMKVWarmup(tc.enabled, tc.connections)
		if s.warmup != nil {
			t.Fatalf("unexpected warmup: %+v", tc)
		}
	}
}

func TestStartupCancellationDoesNotCancelOverlappingDemand(t *testing.T) {
	store := NewStore(context.Background(), stalledBody{}, 32<<20)
	defer store.Close()
	p := startupPins{store: store}
	a := store.acquire("shared", true)
	p.articles = append(p.articles, a)
	demand := store.acquire("shared", false)
	p.close()
	if demand.ctx.Err() != nil {
		t.Fatal("warmup cancelled a live player")
	}
	store.release(demand)
	if demand.ctx.Err() == nil {
		t.Fatal("last lease failed to cancel")
	}
}

func TestStartupOversizedArticleYields(t *testing.T) {
	files, store, _ := setup(t, []inputFile{{"large.mkv", payload(5 << 20), []int{5 << 20}}}, 0, 4, 2)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	p := startupPins{store: store, budget: 2 << 20}
	defer p.close()
	if _, _, _, err = p.at(context.Background(), c, 0); err != errPrefetchYield {
		t.Fatalf("expected budget yield: %v", err)
	}
	if len(p.articles) != 0 {
		t.Fatal("oversized article was pinned")
	}
}

func TestStartupCuesBeforeEOFAndForwardBoundary(t *testing.T) {
	const articleSize = 256 << 10
	want := payload(6 * articleSize)
	cues := int64(3*articleSize - 64)
	copy(want, cuesPrefix(uint64(cues-10)))
	files, store, _ := setup(t, []inputFile{{"movie.mkv", want,
		[]int{articleSize, articleSize, articleSize, articleSize, articleSize, articleSize}}}, 0, 8, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	s := &Session{ctx: context.Background(), content: c, store: store, trace: newStartupTrace()}
	s.startMKVWarmup(true, 8)
	defer s.warmup.Close()
	deadline := time.Now().Add(3 * time.Second)
	for {
		store.mu.Lock()
		a := store.entries["f0-s3@test"]
		done := false
		if a != nil {
			a.mu.Lock()
			done = a.done && a.err == nil
			a.mu.Unlock()
		}
		_, wrongTail := store.entries["f0-s5@test"]
		store.mu.Unlock()
		if wrongTail {
			t.Fatal("fetched EOF despite an immediately available Cues pointer")
		}
		if done {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("did not warm the article following Cues")
		}
		time.Sleep(time.Millisecond)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	r.Seek(cues, io.SeekStart)
	b := make([]byte, 128)
	if _, err := io.ReadFull(r, b); err != nil || !bytes.Equal(b, want[cues:cues+128]) {
		t.Fatalf("Cues boundary read: %v", err)
	}
	if _, ok := s.trace.snapshot()["marksMs"].(map[string]float64)["cues_article_ready"]; !ok {
		t.Fatal("exact Cues readiness was not recorded")
	}
}

func TestStartupHeadYieldsToResumeButNotIndex(t *testing.T) {
	for _, tc := range []struct {
		name  string
		off   int64
		yield bool
	}{
		{"head", 64 << 10, false},
		{"cues", 800 << 20, false},
		{"cues_continuation", 801 << 20, false},
		{"eof_probe", (1 << 30) - 1024, false},
		{"resume", 400 << 20, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			w := &startupWarmup{headCancel: cancel}
			w.cuesOffset.Store(800 << 20)
			w.observeRead(tc.off, 1<<30)
			if (ctx.Err() != nil) != tc.yield {
				t.Fatalf("head cancellation=%v", ctx.Err())
			}
		})
	}
}
