package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"github.com/javi11/nntppool/v4"
	"hash/crc32"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

type interruptedBody struct {
	calls     int
	payload   []byte
	late      bool
	oldWriter io.Writer
	oldMeta   func(nntppool.YEncMeta)
}

func (c *interruptedBody) BodyStream(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	return c.BodyStreamPriority(ctx, id, w, meta...)
}
func (c *interruptedBody) BodyStreamPriority(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	c.calls++
	meta[0](nntppool.YEncMeta{FileSize: int64(len(c.payload)), FileName: "fixture.mkv"})
	if c.calls == 1 {
		c.oldWriter = w
		c.oldMeta = meta[0]
		w.Write(c.payload[:500])
		return nil, io.ErrUnexpectedEOF
	}
	w.Write(c.payload[:400])
	if c.late {
		c.oldMeta(nntppool.YEncMeta{FileSize: int64(len(c.payload)), FileName: "fixture.mkv"})
		c.oldWriter.Write([]byte("obsolete attempt must be discarded"))
	}
	w.Write(c.payload[400:])
	return &nntppool.ArticleBody{Encoding: nntppool.EncodingYEnc}, nil
}
func TestPartialArticleReplayDoesNotDuplicatePublishedBytes(t *testing.T) {
	client := &interruptedBody{payload: payload(1024), late: true}
	store := NewStore(context.Background(), client, 1<<20)
	defer store.Close()
	a := store.acquire("part@test", false)
	defer store.release(a)
	if _, e := a.metadata(context.Background()); e != nil {
		t.Fatal(e)
	}
	got := make([]byte, 1024)
	n, e := a.readAt(context.Background(), got, 0)
	if e != nil || n != len(got) || !bytes.Equal(got, client.payload) || client.calls != 2 {
		t.Fatalf("partial replay: n=%d err=%v calls=%d", n, e, client.calls)
	}
}

func TestSessionCloseWithLeasedReaders(t *testing.T) {
	f, store, _ := setup(t, []inputFile{{"close.mkv", payload(8 << 20), nil}}, time.Millisecond, 8, 4)
	var readers []*FileReader
	for i := 0; i < 8; i++ {
		r := f[0].Reader(context.Background(), 8)
		r.Seek(int64(i*128<<10), io.SeekStart)
		if _, e := r.Read(make([]byte, 64)); e != nil {
			t.Fatal(e)
		}
		readers = append(readers, r)
	}
	store.Close()
	for _, r := range readers {
		r.Close()
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.used != 0 || store.allocated != 0 {
		t.Fatalf("closed store retains leased buffers: used=%d allocated=%d", store.used, store.allocated)
	}
}

func TestSmallBudgetConcurrentSeekAndEviction(t *testing.T) {
	want := payload(48 << 20)
	f, store, _ := setup(t, []inputFile{{"eviction.mkv", want, nil}}, time.Millisecond, 8, 4)
	var wg sync.WaitGroup
	for k := 0; k < 4; k++ {
		wg.Add(1)
		go func(k int) {
			defer wg.Done()
			r := f[0].Reader(context.Background(), 64)
			defer r.Close()
			buf := make([]byte, 64<<10)
			for j := 0; j < 32; j++ {
				off := ((j*7919 + k*15485863) % (len(want) - len(buf)))
				r.Seek(int64(off), io.SeekStart)
				_, e := io.ReadFull(r, buf)
				if e != nil || !bytes.Equal(buf, want[off:off+len(buf)]) {
					t.Errorf("seek %d: %v", off, e)
					return
				}
			}
		}(k)
	}
	wg.Wait()
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.allocated > store.limit {
		t.Fatal("allocation budget exceeded")
	}
}

func rar5Volume(name string, data []byte, total int, flags uint64, compressed bool) []byte {
	var out bytes.Buffer
	out.WriteString("Rar!\x1a\x07\x01\x00")
	nums := func(values ...uint64) []byte {
		var b []byte
		for _, v := range values {
			b = binary.AppendUvarint(b, v)
		}
		return b
	}
	add := func(h []byte) {
		p := append(nums(uint64(len(h))), h...)
		out.Write(binary.LittleEndian.AppendUint32(nil, crc32.ChecksumIEEE(p)))
		out.Write(p)
	}
	if flags&8 != 0 {
		add(nums(1, 0, 3, 1))
	} else {
		add(nums(1, 0, 1))
	}
	comp := uint64(0)
	if compressed {
		comp = 1 << 7
	}
	h := nums(2, flags|2, uint64(len(data)), 0, uint64(total), 0, comp, 1, uint64(len(name)))
	h = append(h, []byte(name)...)
	add(h)
	out.Write(data)
	add(nums(5, 0, 0))
	return out.Bytes()
}

func TestRAR5ObfuscatedAndSeasonSelection(t *testing.T) {
	want := payload(600000)
	f, _, server := setup(t, []inputFile{{"a92cf00", rar5Volume("Show.S02E03.mkv", want[:300000], len(want), 16, false), nil}, {"b835fff", rar5Volume("Show.S02E03.mkv", want[300000:], len(want), 8, false), nil}}, 0, 4, 4)
	c, e := Select(context.Background(), f, Selection{Season: 2, Episode: 3})
	if e != nil {
		t.Fatal(e)
	}
	if server.Counters().Bodies != 1 {
		t.Fatal("obfuscated archive resolution was eager")
	}
	r := c.Reader(context.Background(), 8)
	defer r.Close()
	got, e := io.ReadAll(r)
	if e != nil || !bytes.Equal(got, want) {
		t.Fatalf("RAR5 read: %v", e)
	}
	compressed, _, _ := setup(t, []inputFile{{"compressed.rar", rar5Volume("movie.mkv", want, len(want), 0, true), nil}}, 0, 2, 2)
	if _, e := Select(context.Background(), compressed, Selection{}); e != ErrCompressedRAR {
		t.Fatalf("RAR5 compression not rejected: %v", e)
	}
}

func TestSeasonPackSkipsUnselectedPayload(t *testing.T) {
	first := rar4Volume("Show.S01E01.mkv", payload(1<<20), 1<<20, 0, false)
	want := payload(300000)
	second := rar4Volume("Show.S01E02.mkv", want, len(want), 0, false)
	// Two file records in one archive, removing the first END and second main.
	pack := append(append([]byte{}, first[:len(first)-7]...), second[20:]...)
	f, _, server := setup(t, []inputFile{{"season.rar", pack, nil}}, 0, 4, 4)
	c, e := Select(context.Background(), f, Selection{Season: 1, Episode: 2})
	if e != nil {
		t.Fatal(e)
	}
	if server.Counters().Bodies > 3 {
		t.Fatalf("unselected episode payload downloaded: %d articles", server.Counters().Bodies)
	}
	r := c.Reader(context.Background(), 4)
	defer r.Close()
	got, e := io.ReadAll(r)
	if e != nil || !bytes.Equal(got, want) {
		t.Fatalf("season selection: %v", e)
	}
}

func TestArchiveHeaderCRCRejected(t *testing.T) {
	v := rar4Volume("movie.mkv", payload(10000), 10000, 0, false)
	v[30] ^= 1
	f, _, _ := setup(t, []inputFile{{"corrupt.rar", v, nil}}, 0, 2, 2)
	if _, e := Select(context.Background(), f, Selection{}); e == nil {
		t.Fatal("corrupt archive header accepted")
	}
}

func TestObfuscatedRAR5UnorderedVolumes(t *testing.T) {
	want := payload(600000)
	f, _, _ := setup(t, []inputFile{{"randomB", rar5Volume("Movie.mkv", want[300000:], len(want), 8, false), nil}, {"randomA", rar5Volume("Movie.mkv", want[:300000], len(want), 16, false), nil}}, 0, 4, 4)
	c, e := Select(context.Background(), f, Selection{})
	if e != nil {
		t.Fatal(e)
	}
	r := c.Reader(context.Background(), 4)
	defer r.Close()
	got, e := io.ReadAll(r)
	if e != nil || !bytes.Equal(got, want) {
		t.Fatalf("unordered RAR: %v", e)
	}
}

func TestVolumeBoundaryLookaheadAndSeekCancellation(t *testing.T) {
	want := payload(600000)
	f, _, server := setup(t, []inputFile{{"pack.part01.rar", rar4Volume("Movie.mkv", want[:300000], len(want), 2, false), nil}, {"pack.part02.rar", rar4Volume("Movie.mkv", want[300000:], len(want), 1, false), nil}}, time.Millisecond, 4, 4)
	c, e := Select(context.Background(), f, Selection{})
	if e != nil {
		t.Fatal(e)
	}
	if server.Counters().Bodies != 1 {
		t.Fatal("startup should remain lazy")
	}
	r := c.Reader(context.Background(), 4)
	defer r.Close()
	if _, e := r.Read(make([]byte, 64)); e != nil {
		t.Fatal(e)
	}
	if r.boundary == nil {
		t.Fatal("upcoming volume was not primed")
	}
	select {
	case <-r.boundary.done:
	case <-time.After(2 * time.Second):
		t.Fatal("boundary prefetch stalled")
	}
	if r.boundary.err != nil {
		t.Fatal(r.boundary.err)
	}
	r.Seek(299980, io.SeekStart)
	if r.boundary != nil {
		t.Fatal("seek retained obsolete boundary work")
	}
	got := make([]byte, 50)
	if _, e := io.ReadFull(r, got); e != nil || !bytes.Equal(got, want[299980:300030]) {
		t.Fatalf("primed split read: %v", e)
	}
}

func TestStandaloneSubtitlesAreLazyAndMemoryBacked(t *testing.T) {
	sub := []byte("1\n00:00:00,000 --> 00:00:02,000\nUsenet subtitle fixture\n")
	f, store, provider := setup(t, []inputFile{{"Movie.mkv", payload(300000), nil}, {"Movie.en.srt", sub, nil}}, 0, 4, 4)
	c, e := Select(context.Background(), f, Selection{})
	if e != nil {
		t.Fatal(e)
	}
	subs := standaloneSubtitles(f, Selection{})
	if len(subs) != 1 {
		t.Fatal("standalone subtitle missing")
	}
	if provider.Counters().Bodies != 1 {
		t.Fatal("subtitle article fetched before subtitle was selected")
	}
	s := NewServer(context.Background(), "control-token", nil, http.DefaultClient)
	s.sessions["fixture"] = &Session{content: c, store: store, ctx: context.Background(), subtitles: subs}
	server := httptest.NewServer(s)
	defer server.Close()
	response, e := http.Get(server.URL + "/subtitle/fixture/0/Movie.en.srt")
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	got, e := io.ReadAll(response.Body)
	if e != nil || !bytes.Equal(got, sub) || response.StatusCode != 200 {
		t.Fatalf("subtitle: %s, %v", got, e)
	}
	if response.Header.Get("Cache-Control") != "no-store" {
		t.Fatal("subtitle allowed a disk cache")
	}
	if provider.Counters().Bodies != 2 {
		t.Fatal("subtitle fetched unrelated articles")
	}
}
