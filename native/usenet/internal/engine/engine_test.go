package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// Independent encoder: tests do not use the decoder library to generate their
// wire input. Escapes, dot stuffing and yEnc offsets cross arbitrary TCP chunks.
func encodePart(name string, p []byte, begin, total int64, part, parts int) []byte {
	var b bytes.Buffer
	fmt.Fprintf(&b, "=ybegin part=%d total=%d line=128 size=%d name=%s\r\n=ypart begin=%d end=%d\r\n", part, parts, total, name, begin+1, begin+int64(len(p)))
	col := 0
	for _, v := range p {
		v += 42
		if col >= 128 {
			b.WriteString("\r\n")
			col = 0
		}
		if col == 0 && v == '.' {
			b.WriteByte('.')
		}
		if v == 0 || v == 10 || v == 13 || v == '=' {
			b.WriteByte('=')
			v += 64
			col++
		}
		b.WriteByte(v)
		col++
	}
	fmt.Fprintf(&b, "\r\n=yend size=%d part=%d pcrc32=%08x\r\n", len(p), part, crc32.ChecksumIEEE(p))
	return b.Bytes()
}

type inputFile struct {
	name  string
	data  []byte
	sizes []int
}

func fixture(files []inputFile) (string, map[string][]byte) {
	var x strings.Builder
	x.WriteString(`<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">`)
	articles := map[string][]byte{}
	for i, f := range files {
		sizes := f.sizes
		if len(sizes) == 0 {
			for left := len(f.data); left > 0; {
				n := min(left, 128<<10)
				sizes = append(sizes, n)
				left -= n
			}
		}
		fmt.Fprintf(&x, `<file subject='"%s" yEnc (1/%d)' date="1"><groups><group>alt.test</group></groups><segments>`, f.name, len(sizes))
		off := 0
		for j, size := range sizes {
			id := fmt.Sprintf("f%d-s%d@test", i, j)
			wire := encodePart(f.name, f.data[off:off+size], int64(off), int64(len(f.data)), j+1, len(sizes))
			articles[id] = wire
			// Use genuine wire sizes, deliberately different from decoded sizes.
			fmt.Fprintf(&x, `<segment bytes="%d" number="%d">%s</segment>`, len(wire), j+1, id)
			off += size
		}
		x.WriteString(`</segments></file>`)
	}
	x.WriteString(`</nzb>`)
	return x.String(), articles
}

func payload(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte((i*131 + i/257) % 256)
	}
	return b
}
func setup(t testing.TB, in []inputFile, latency time.Duration, connections, pipeline int) ([]*File, *Store, *nntpserver.Server) {
	t.Helper()
	xml, articles := fixture(in)
	s, err := nntpserver.New(nntpserver.Config{Articles: articles, RTT: latency})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := nntppool.NewClient(context.Background(), []nntppool.Provider{{Host: s.Addr(), Connections: connections, Inflight: pipeline, StreamInflight: pipeline, SkipPing: true}}, nntppool.WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	store := NewStore(context.Background(), pool, 32<<20)
	t.Cleanup(func() { store.Close(); pool.Close(); s.Close() })
	files, err := ParseNZB(strings.NewReader(xml), store)
	if err != nil {
		t.Fatal(err)
	}
	return files, store, s
}

func TestMissingArticleIsNotRetried(t *testing.T) {
	for _, speculative := range []bool{false, true} {
		t.Run(fmt.Sprintf("speculative=%t", speculative), func(t *testing.T) {
			_, store, server := setup(t, []inputFile{{"movie.mkv", payload(1024), nil}}, 0, 1, 1)
			a := store.acquire("missing@test", speculative)
			if a == nil {
				t.Fatal("article acquisition was rejected")
			}
			defer store.release(a)
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if _, err := a.metadata(ctx); !errors.Is(err, nntppool.ErrArticleNotFound) {
				t.Fatalf("metadata error = %v, want ErrArticleNotFound", err)
			}
			if calls := server.Counters().Bodies; calls != 1 {
				t.Fatalf("BODY calls = %d, want 1", calls)
			}
		})
	}
}

func TestRangesVariableYEncAndConcurrentReaders(t *testing.T) {
	want := payload(130003 + 512001 + 730019 + 37011)
	f, store, server := setup(t, []inputFile{{"movie.mkv", want, []int{130003, 512001, 730019, 37011}}}, time.Millisecond, 4, 4)
	c, e := Select(context.Background(), f, Selection{})
	if e != nil {
		t.Fatal(e)
	}
	if c.Size != int64(len(want)) {
		t.Fatalf("size %d", c.Size)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rd := c.Reader(r.Context(), 4)
		defer rd.Close()
		http.ServeContent(w, r, c.Name, time.Time{}, rd)
	}))
	defer srv.Close()
	var wg sync.WaitGroup
	for _, span := range [][2]int{{0, 65535}, {129999, 800000}, {len(want) - 100, len(want) - 1}, {640000, 641023}} {
		wg.Add(1)
		go func(span [2]int) {
			defer wg.Done()
			req, _ := http.NewRequest("GET", srv.URL, nil)
			req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", span[0], span[1]))
			resp, e := http.DefaultClient.Do(req)
			if e != nil {
				t.Error(e)
				return
			}
			defer resp.Body.Close()
			got, e := io.ReadAll(resp.Body)
			if e != nil || resp.StatusCode != 206 || !bytes.Equal(got, want[span[0]:span[1]+1]) {
				t.Errorf("range %v: status=%d size=%d error=%v", span, resp.StatusCode, len(got), e)
			}
		}(span)
	}
	wg.Wait()
	if server.Counters().Stats != 0 {
		t.Fatal("unexpected NNTP probe")
	}
	store.mu.Lock()
	allocated := store.allocated
	store.mu.Unlock()
	if allocated > 32<<20 {
		t.Fatal("RAM budget exceeded")
	}
}

func TestActualNNTPPipeline(t *testing.T) {
	for _, depth := range []int{2, 4, 8} {
		t.Run(fmt.Sprintf("depth%d", depth), func(t *testing.T) {
			want := payload(16 * 128 << 10)
			f, _, s := setup(t, []inputFile{{"pipeline.mkv", want, nil}}, 20*time.Millisecond, 1, depth)
			r := f[0].Reader(context.Background(), depth*2)
			defer r.Close()
			got, e := io.ReadAll(r)
			if e != nil || !bytes.Equal(got, want) {
				t.Fatalf("read len=%d err=%v", len(got), e)
			}
			if peak := s.Counters().PeakInflight; peak < int64(depth) {
				t.Fatalf("pipeline not filled: peak=%d, depth=%d", peak, depth)
			} else {
				t.Logf("one NNTP connection: pipeline peak=%d; exact payload verified", peak)
			}
			if s.Counters().Stats != 0 {
				t.Fatal("separate NNTP probes")
			}
		})
	}
}

func rar4Volume(name string, data []byte, total int, flags uint16, compressed bool) []byte {
	var out bytes.Buffer
	out.WriteString("Rar!\x1a\x07\x00")
	add := func(h []byte) { binary.LittleEndian.PutUint16(h, uint16(crc32.ChecksumIEEE(h[2:]))); out.Write(h) }
	main := make([]byte, 13)
	main[2] = 0x73
	binary.LittleEndian.PutUint16(main[5:], 13)
	add(main)
	h := make([]byte, 32+len(name))
	h[2] = 0x74
	binary.LittleEndian.PutUint16(h[3:], flags|0x8000)
	binary.LittleEndian.PutUint16(h[5:], uint16(len(h)))
	binary.LittleEndian.PutUint32(h[7:], uint32(len(data)))
	binary.LittleEndian.PutUint32(h[11:], uint32(total))
	h[25] = 0x30
	if compressed {
		h[25] = 0x33
	}
	binary.LittleEndian.PutUint16(h[26:], uint16(len(name)))
	copy(h[32:], name)
	add(h)
	out.Write(data)
	end := make([]byte, 7)
	end[2] = 0x7b
	binary.LittleEndian.PutUint16(end[5:], 7)
	add(end)
	return out.Bytes()
}

func TestLazyMultipartRARAndCompressedRejection(t *testing.T) {
	want := payload(600000)
	first := rar4Volume("Show.S01E02.mkv", want[:300000], len(want), 2, false)
	second := rar4Volume("Show.S01E02.mkv", want[300000:], len(want), 1, false)
	f, _, server := setup(t, []inputFile{{"pack.part01.rar", first, nil}, {"pack.part02.rar", second, nil}}, 0, 4, 4)
	c, err := Select(context.Background(), f, Selection{Season: 1, Episode: 2})
	if err != nil {
		t.Fatal(err)
	}
	if n := server.Counters().Bodies; n != 1 {
		t.Fatalf("startup fetched %d articles, want one archive header article", n)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	r.Seek(299980, io.SeekStart)
	got := make([]byte, 50)
	if _, e := io.ReadFull(r, got); e != nil || !bytes.Equal(got, want[299980:300030]) {
		t.Fatalf("split read: %v", e)
	}
	r.Seek(-100, io.SeekEnd)
	got, e := io.ReadAll(r)
	if e != nil || !bytes.Equal(got, want[len(want)-100:]) {
		t.Fatalf("tail seek: %v", e)
	}
	f2, _, _ := setup(t, []inputFile{{"compressed.rar", rar4Volume("movie.mkv", want, len(want), 0, true), nil}}, 0, 2, 2)
	if _, e := Select(context.Background(), f2, Selection{}); e != ErrCompressedRAR {
		t.Fatalf("compression accepted: %v", e)
	}
}

func TestAltMountArchiveFixtures(t *testing.T) {
	for _, dir := range []string{"rar_single", "rar_multi", "rar_widthmismatch"} {
		t.Run(dir, func(t *testing.T) {
			paths, e := filepath.Glob(filepath.Join("..", "..", "testdata", dir, "*"))
			if e != nil {
				t.Fatal(e)
			}
			if len(paths) == 0 {
				t.Fatal("missing upstream fixture")
			}
			var in []inputFile
			for _, p := range paths {
				b, e := os.ReadFile(p)
				if e != nil {
					t.Fatal(e)
				}
				in = append(in, inputFile{filepath.Base(p), b, nil})
			}
			f, _, _ := setup(t, in, 0, 4, 4)
			c, e := Select(context.Background(), f, Selection{FileMustInclude: ".*"})
			if e != nil {
				t.Fatal(e)
			}
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			n, e := io.Copy(io.Discard, r)
			if e != nil || n != c.Size {
				t.Fatalf("read %d/%d: %v", n, c.Size, e)
			}
		})
	}
}

func TestCancellationAndSharedFlight(t *testing.T) {
	want := payload(2 << 20)
	f, store, _ := setup(t, []inputFile{{"shared.mkv", want, nil}}, 10*time.Millisecond, 4, 4)
	ctx, cancel := context.WithCancel(context.Background())
	a := f[0].Reader(ctx, 8)
	b := f[0].Reader(context.Background(), 0)
	buf := make([]byte, 64)
	if _, e := a.Read(buf); e != nil {
		t.Fatal(e)
	}
	if _, e := b.Read(buf); e != nil {
		t.Fatal(e)
	}
	cancel()
	a.Close()
	got, e := io.ReadAll(b)
	b.Close()
	if e != nil || !bytes.Equal(got, want[64:]) {
		t.Fatalf("cancel damaged other reader: %v", e)
	}
	store.mu.Lock()
	for _, entry := range store.entries {
		if entry.refs != 0 {
			t.Error("leaked reader lease")
		}
	}
	store.mu.Unlock()
}

func TestProviderAllowances(t *testing.T) {
	p, e := Providers([]string{"nntps://user:p%40ss@example.com:563/80"}, Config{}, nil)
	if e != nil {
		t.Fatal(e)
	}
	if p[0].Connections != 80 || p[0].Auth.Password != "p@ss" || p[0].Inflight <= 1 {
		t.Fatalf("provider allowance not respected: %+v", p[0])
	}
}
