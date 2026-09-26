package engine

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// Compare production's short drain with the pool default over authenticated
// TCP. Socket reuse is a policy tradeoff; both must deliver exact seek bytes.
func TestProviderSeekDrainPolicy(t *testing.T) {
	for _, tc := range []struct {
		name        string
		size        int
		poolDefault bool
		conns       int64
	}{
		{"production ordinary article", 768 << 10, false, 2},
		{"default ordinary article", 768 << 10, true, 1},
		{"production large article", 4 << 20, false, 2},
		{"default large article", 4 << 20, true, 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			want := payload(2 * tc.size)
			xml, articles := fixture([]inputFile{{"seek.mkv", want, []int{tc.size, tc.size}}})
			server, err := nntpserver.New(nntpserver.Config{Articles: articles, BandwidthPerConn: 4 << 20, RequireAuth: true})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { server.Close() })
			providers, err := Providers([]string{"nntp://user:pass@" + server.Addr() + "/1"}, Config{}, nil)
			if err != nil {
				t.Fatal(err)
			}
			if tc.poolDefault {
				providers[0].AbortDrainBytes = 0
			}
			pool, err := nntppool.NewClient(context.Background(), providers, nntppool.WithStatProbe(false))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { pool.Close() })
			store := NewStore(context.Background(), pool, 64<<20)
			t.Cleanup(store.Close)
			files, err := ParseNZB(strings.NewReader(xml), store)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			r := files[0].Reader(ctx, 0)
			defer r.Close()
			prefix := make([]byte, 32<<10)
			if _, err := io.ReadFull(r, prefix); err != nil || !bytes.Equal(prefix, want[:len(prefix)]) {
				t.Fatalf("initial prefix: %v", err)
			}
			old := r.leases[0]
			old.mu.Lock()
			done := old.done
			old.mu.Unlock()
			if done {
				t.Fatal("fixture completed before seek; cancellation was not exercised")
			}
			if _, err := r.Seek(int64(tc.size), io.SeekStart); err != nil {
				t.Fatal(err)
			}
			if old.ctx.Err() == nil {
				t.Fatal("seek did not cancel the abandoned demand body")
			}
			got := make([]byte, tc.size)
			if _, err := io.ReadFull(r, got); err != nil || !bytes.Equal(got, want[tc.size:]) {
				t.Fatalf("body after seek: %v", err)
			}
			if got := server.Counters().Conns; got != tc.conns {
				t.Fatalf("connections after seek = %d, want %d", got, tc.conns)
			}
		})
	}
}

// Publish a header and a prefix, then stall until the last reader cancels.
type stalledBody struct{}

func (c stalledBody) BodyStream(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	meta[0](nntppool.YEncMeta{FileSize: 1 << 20})
	w.Write([]byte("progressive bytes"))
	<-ctx.Done()
	return nil, ctx.Err()
}
func (c stalledBody) BodyStreamPriority(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	return c.BodyStream(ctx, id, w, meta...)
}

func TestLastDemandLeaseCancelsInFlightBody(t *testing.T) {
	store := NewStore(context.Background(), stalledBody{}, 32<<20)
	defer store.Close()
	a := store.acquire("shared", false)
	b := store.acquire("shared", false)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := a.metadata(ctx); err != nil {
		t.Fatal(err)
	}
	store.release(a)
	if a.ctx.Err() != nil {
		t.Fatal("cancelled an overlapping live reader")
	}
	store.release(b)
	select {
	case <-a.ctx.Done():
	case <-ctx.Done():
		t.Fatal("unowned demand body was not cancelled")
	}
	store.Close()
	if store.used != 0 || store.allocated != 0 {
		t.Fatal("cancelled body retained its slab")
	}
}

func TestHeaderHandoffReusesBodyButPlaybackCanCancelIt(t *testing.T) {
	store := NewStore(context.Background(), stalledBody{}, 32<<20)
	defer store.Close()
	a := store.acquire("header", false)
	store.releaseHeader(a)
	if a.ctx.Err() != nil {
		t.Fatal("header handoff cancelled the shared body")
	}
	b := store.acquire("header", false)
	if a != b {
		t.Fatal("header handoff started a duplicate body")
	}
	store.release(b)
	if a.ctx.Err() == nil {
		t.Fatal("playback could not cancel a body inherited from discovery")
	}
}

func TestReadAheadWindowCountAndByteCaps(t *testing.T) {
	for _, tc := range []struct {
		name        string
		limit, size int64
		ahead, want int
		known       bool
	}{
		{"low large", 32 << 20, 4 << 20, 24, 3, true},
		{"balanced large", 64 << 20, 4 << 20, 24, 6, true},
		{"throughput large", 128 << 20, 4 << 20, 24, 8, true},
		{"small count cap", 64 << 20, 768 << 10, 16, 16, false},
		{"wire estimates", 64 << 20, 5 << 20, 24, 4, false},
		{"disabled", 64 << 20, 4 << 20, 0, 0, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := &File{store: &Store{limit: tc.limit}, segments: make([]segment, 40)}
			for i := range f.segments {
				f.segments[i] = segment{wire: tc.size, begin: int64(i) * tc.size, end: int64(i+1) * tc.size, known: tc.known}
			}
			r := f.Reader(context.Background(), tc.ahead)
			if got := r.windowEnd(0); got != tc.want {
				t.Fatalf("window=%d want=%d", got, tc.want)
			}
			if got := r.windowEnd(39); got != 39 {
				t.Fatalf("window extends past EOF: %d", got)
			}
		})
	}
}

func TestSeekReleasesObsoleteDemandBeforeNextRead(t *testing.T) {
	store := NewStore(context.Background(), stalledBody{}, 32<<20)
	defer store.Close()
	f := &File{store: store, size: 4 << 20, exact: true, prefix: []int64{0, 1 << 20, 2 << 20, 3 << 20, 4 << 20}}
	for i := 0; i < 4; i++ {
		f.segments = append(f.segments, segment{id: fmt.Sprint(i), wire: 1 << 20})
	}
	r := f.Reader(context.Background(), 1)
	defer r.Close()
	a := r.get(0, false)
	b := r.get(1, true)
	if _, err := r.Seek(3<<20, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	if a.ctx.Err() == nil || b.ctx.Err() == nil || len(r.leases) != 0 {
		t.Fatal("seek kept obsolete downloads until the next read")
	}
}

func TestLargeArticlesSurviveRepeatedSeekCancellation(t *testing.T) {
	const size = 4 << 20
	want := payload(8 * size)
	files, store, _ := setup(t, []inputFile{{"large.mkv", want, []int{size, size, size, size, size, size, size, size}}}, time.Millisecond, 16, 4)
	store.limit = 64 << 20
	content, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	r := content.Reader(context.Background(), 24)
	defer r.Close()
	buf := make([]byte, 64<<10)
	for _, off := range []int64{0, 6*size + 19, size + 97, 5*size + 31, 2*size + 17, 7*size + 23, 0} {
		if _, err := r.Seek(off, io.SeekStart); err != nil {
			t.Fatal(err)
		}
		if _, err := io.ReadFull(r, buf); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(buf, want[off:off+int64(len(buf))]) {
			t.Fatalf("corrupt seek at %d", off)
		}
	}
	if _, err := r.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	for off := 0; off < len(want); off += len(buf) {
		if _, err := io.ReadFull(r, buf); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(buf, want[off:off+len(buf)]) {
			t.Fatalf("corrupt sequential read at %d", off)
		}
	}
}
