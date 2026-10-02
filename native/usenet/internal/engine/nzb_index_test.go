package engine

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/javi11/nntppool/v4"
)

func saveIndexed(t testing.TB, cache *nzbCache, key string, files []*File) {
	t.Helper()
	fill, why := cache.begin(key)
	if fill == nil {
		t.Fatal(why)
	}
	if got := fill.indexed(files); got != "saved" {
		t.Fatal(got)
	}
}

func cachedFiles(t testing.TB, cache *nzbCache, key string, store *Store) []*File {
	t.Helper()
	files, why, _ := cache.read(key, store)
	if why != "hit" {
		t.Fatal(why)
	}
	return files
}

func TestIndexedNZBLazySelectionAndRanges(t *testing.T) {
	in := []inputFile{{"show.S01E01.mkv", payload(256 << 10), nil}, {"show.S01E02.mkv", payload(400 << 10), []int{73 << 10, 100 << 10, 227 << 10}}, {"show.S01E02.srt", []byte("1\n00:00:01,000 --> 00:00:02,000\nHello\n"), nil}}
	original, source, _ := setup(t, in, 0, 8, 4)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("a", 64)
	saveIndexed(t, cache, key, original)
	store := NewStore(context.Background(), source.client, 32<<20)
	defer store.Close()
	files := cachedFiles(t, cache, key, store)
	for i, f := range files {
		if f.Index != i || f.Name != in[i].name || len(f.segments) != 0 {
			t.Fatal("lookup inflated or reordered files")
		}
	}
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil {
		t.Fatal(err)
	}
	if len(files[0].segments) != 0 || len(files[1].segments) != 3 || len(files[2].segments) != 0 {
		t.Fatal("selection inflated unrequested files")
	}
	for _, off := range []int64{0, 80 << 10, c.Size - 1024} {
		r := c.Reader(context.Background(), 0)
		r.Seek(off, io.SeekStart)
		got := make([]byte, 1024)
		_, err := io.ReadFull(r, got)
		r.Close()
		if err != nil || !bytes.Equal(got, in[1].data[off:off+1024]) {
			t.Fatalf("range %d: %v", off, err)
		}
	}
	r := files[2].Reader(context.Background(), 0)
	got, err := io.ReadAll(r)
	r.Close()
	if err != nil || !bytes.Equal(got, in[2].data) {
		t.Fatalf("lazy subtitle: %v", err)
	}
	if original[1].exact || len(original[1].known) != 0 {
		t.Fatal("mutable layout leaked into original")
	}
}

func TestIndexedNZBColdOpenReleasesUnselectedTables(t *testing.T) {
	xml, _ := fixture([]inputFile{{"one.mkv", payload(128), nil}, {"two.mkv", payload(128), nil}})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	defer srv.Close()
	store := NewStore(context.Background(), stalledBody{}, 32<<20)
	defer store.Close()
	files, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, store, false, newNZBCache(t.TempDir()), "", nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		if len(f.segments) != 0 || f.cached == nil || f.cached.doc.recover == nil {
			t.Fatal("cold open retained full tables or lost lazy recovery")
		}
	}
	if err := files[0].loadSegments(); err != nil {
		t.Fatal(err)
	}
	if len(files[0].segments) != 1 || len(files[1].segments) != 0 {
		t.Fatal("cold lazy load crossed files")
	}
}

func TestIndexedNZBLazyRARAndPinnedEviction(t *testing.T) {
	want := payload(400 << 10)
	original, source, _ := setup(t, []inputFile{
		{"movie.part1.rar", rar4Volume("movie.mkv", want[:200<<10], len(want), 2, false), nil},
		{"movie.part2.rar", rar4Volume("movie.mkv", want[200<<10:], len(want), 1, false), nil},
		{"unused.srt", []byte("unused subtitle data"), nil},
	}, 0, 8, 4)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("b", 64)
	saveIndexed(t, cache, key, original)
	store := NewStore(context.Background(), source.client, 32<<20)
	defer store.Close()
	files := cachedFiles(t, cache, key, store)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if len(files[1].segments) != 0 || len(files[2].segments) != 0 {
		t.Fatal("RAR selection eagerly loaded continuation")
	}
	cache.mu.Lock()
	removed := cache.remove(key)
	cache.mu.Unlock()
	if removed {
		t.Fatal("evicted live metadata descriptor")
	}
	r := c.Reader(context.Background(), 0)
	got, err := io.ReadAll(r)
	r.Close()
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("lazy RAR continuation: %v", err)
	}
	if len(files[2].segments) != 0 {
		t.Fatal("inflated unused subtitle")
	}
	store.Close()
	cache.mu.Lock()
	removed = cache.remove(key)
	cache.mu.Unlock()
	if !removed {
		t.Fatal("closed store retained descriptor")
	}
}

func TestIndexedNZBLegacyMigrationAndDirectoryCorruption(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("c", 64)
	path := filepath.Join(cache.dir, key+".nzb")
	if err := os.WriteFile(path, []byte(xml), 0600); err != nil {
		t.Fatal(err)
	}
	cachedFiles(t, cache, key, nil)
	b, err := os.ReadFile(path)
	if err != nil || string(b[:8]) != nzbIndexMagic {
		t.Fatal("legacy entry was not migrated", err)
	}
	for _, offset := range []int{8, 16, 24, len(b) - 1} {
		bad := bytes.Clone(b)
		bad[offset] ^= 0xff
		os.WriteFile(path, bad, 0600)
		if _, why, _ := cache.read(key, nil); why != "invalid" {
			t.Fatalf("corruption at %d accepted: %s", offset, why)
		}
	}
}

func TestIndexedNZBCorruptRecordRecoversOnceAndChecksIdentity(t *testing.T) {
	for _, changed := range []bool{false, true} {
		t.Run(fmt.Sprint(changed), func(t *testing.T) {
			xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}, {"second.mkv", payload(128), nil}})
			var calls atomic.Int32
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				n := calls.Add(1)
				body := xml
				if changed && n > 1 {
					body = strings.ReplaceAll(body, "@test", "@else")
				}
				io.WriteString(w, body)
			}))
			defer srv.Close()
			cache := newNZBCache(t.TempDir())
			if _, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, cache, "", nil); err != nil {
				t.Fatal(err)
			}
			req, _ := http.NewRequest("GET", srv.URL, nil)
			key := nzbCacheKey(req, "")
			path := filepath.Join(cache.dir, key+".nzb")
			b, _ := os.ReadFile(path)
			// Leave the valid directory intact, corrupt each independent record.
			end := int(binary.LittleEndian.Uint64(b[8:16]))
			for i := nzbIndexHeader; i < end; i++ {
				b[i] = 0
			}
			if err := os.WriteFile(path, b, 0600); err != nil {
				t.Fatal(err)
			}
			store := NewStore(context.Background(), stalledBody{}, 32<<20)
			defer store.Close()
			files, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, store, false, cache, "", nil)
			if err != nil {
				t.Fatal(err)
			}
			var wg sync.WaitGroup
			for _, f := range files {
				wg.Add(1)
				go func(f *File) {
					defer wg.Done()
					err := f.loadSegments()
					if (err != nil) != changed {
						t.Errorf("changed=%v load=%v", changed, err)
					}
				}(f)
			}
			wg.Wait()
			if calls.Load() != 2 {
				t.Fatalf("recovery fetched %d times", calls.Load())
			}
			store.Close()
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Fatal("corrupt cache retained")
			}
		})
	}
}

func TestIndexedNZBMKVHintsSurviveRestartWithoutPayloads(t *testing.T) {
	const articleSize = 256 << 10
	want := payload(6 * articleSize)
	cues := int64(3*articleSize - 64)
	copy(want, cuesPrefix(uint64(cues-10)))
	original, source, _ := setup(t, []inputFile{{"movie.mkv", want, []int{articleSize, articleSize, articleSize, articleSize, articleSize, articleSize}}}, 0, 8, 4)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("d", 64)
	saveIndexed(t, cache, key, original)
	for pass := 0; pass < 2; pass++ {
		store := NewStore(context.Background(), source.client, 32<<20)
		defer store.Close()
		files := cachedFiles(t, newNZBCache(cache.dir), key, store)
		if err := files[0].loadSegments(); err != nil {
			t.Fatal(err)
		}
		if pass == 1 && (!files[0].exact || len(files[0].known) < 3) {
			t.Fatal("decoded layout hints not restored")
		}
		content, err := Select(context.Background(), files, Selection{})
		if err != nil {
			t.Fatal(err)
		}
		s := &Session{ctx: context.Background(), content: content, store: store, trace: newStartupTrace()}
		s.startMKVWarmup(true, 8)
		defer s.warmup.Close()
		deadline := time.Now().Add(3 * time.Second)
		for {
			marks := s.trace.snapshot()["marksMs"].(map[string]float64)
			_, head := marks["head_article_ready"]
			_, cue := marks["cues_article_ready"]
			if head && cue {
				if _, hit := marks["cues_cache_hit"]; hit != (pass == 1) {
					t.Fatalf("pass %d marks %v", pass, marks)
				}
				break
			}
			if time.Now().After(deadline) {
				t.Fatal("warmup timed out", marks)
			}
			time.Sleep(time.Millisecond)
		}
		s.warmup.Close()
		store.Close()
	}
	entries, _ := os.ReadDir(cache.dir)
	if len(entries) != 2 {
		t.Fatalf("unexpected artifacts: %d", len(entries))
	}
	for _, e := range entries {
		b, _ := os.ReadFile(filepath.Join(cache.dir, e.Name()))
		if len(b) >= 8192 || bytes.Contains(b, want[100:200]) || bytes.Contains(b, []byte("=ybegin")) {
			t.Fatal("payload or oversized metadata persisted")
		}
	}
}

func TestIndexedNZBRejectsMalformedDirectoryRecords(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	files, _ := ParseNZB(strings.NewReader(xml), nil)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("f", 64)
	saveIndexed(t, cache, key, files)
	path := filepath.Join(cache.dir, key+".nzb")
	b, _ := os.ReadFile(path)
	offset := binary.LittleEndian.Uint64(b[8:16])
	for _, mutate := range []func(*nzbRecord){
		func(r *nzbRecord) { r.Count = 500001 },
		func(r *nzbRecord) { r.Index = -1 },
		func(r *nzbRecord) { r.Offset++ },
		func(r *nzbRecord) { r.Length = 1 << 62 },
		func(r *nzbRecord) { r.Bytes = maxNZBBytes + 1 },
		func(r *nzbRecord) { r.Size = -1 },
	} {
		var records []nzbRecord
		json.Unmarshal(b[offset:], &records)
		mutate(&records[0])
		directory, _ := json.Marshal(records)
		bad := append(bytes.Clone(b[:offset]), directory...)
		binary.LittleEndian.PutUint64(bad[16:24], uint64(len(directory)))
		hash := sha256.Sum256(directory)
		copy(bad[24:56], hash[:])
		os.WriteFile(path, bad, 0600)
		if _, why, _ := cache.read(key, nil); why != "invalid" {
			t.Fatal("malformed directory accepted")
		}
	}
}

func TestNZBHintsAreAdvisoryButFreshLayoutConflictsFail(t *testing.T) {
	f := &File{size: 300, exact: true, hintSize: true, known: []int{0, 1, 2}, segments: []segment{
		{begin: 0, end: 100, known: true, hint: true},
		{begin: 100, end: 200, known: true, hint: true},
		{begin: 200, end: 300, known: true, hint: true},
	}}
	meta := func(part, begin, size int64) nntppool.YEncMeta {
		return nntppool.YEncMeta{FileSize: 300, Part: part, Total: 3, PartBegin: begin, PartSize: size}
	}
	if err := f.learn(0, meta(1, 0, 100)); err != nil {
		t.Fatal(err)
	}
	if err := f.learn(2, meta(3, 210, 90)); err != nil {
		t.Fatal("stale persisted anchor failed playback", err)
	}
	if f.segments[1].known || !f.segments[0].known || !f.segments[2].known {
		t.Fatal("hint invalidation lost fresh anchors")
	}
	if err := f.learn(0, meta(1, 0, 110)); err == nil {
		t.Fatal("conflicting fresh metadata accepted")
	}
}

func TestNZBHintCorruptionGenerationAndBounds(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	original, _ := ParseNZB(strings.NewReader(xml), nil)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("1", 64)
	saveIndexed(t, cache, key, original)
	for _, mode := range []string{"checksum", "generation", "anchor", "oversized"} {
		t.Run(mode, func(t *testing.T) {
			store := NewStore(context.Background(), stalledBody{}, 32<<20)
			files := cachedFiles(t, cache, key, store)
			generation := files[0].cached.doc.generation
			store.Close()
			hints := nzbHints{Version: 1, Generation: generation, Files: []nzbFileHint{{Index: 0, Size: 128, Anchors: []nzbAnchor{{Index: 0, Begin: 0, End: 128}}}}}
			switch mode {
			case "generation":
				hints.Generation[0] ^= 1
			case "anchor":
				hints.Files[0].Anchors[0].End = 129
			case "oversized":
				hints.Files[0].Name = strings.Repeat("x", nzbHintBytes)
			}
			b, _ := json.Marshal(hints)
			hash := sha256.Sum256(b)
			b = append(hash[:], b...)
			if mode == "checksum" {
				b[0] ^= 1
			}
			os.WriteFile(filepath.Join(cache.dir, key+".hints"), b, 0600)
			store = NewStore(context.Background(), stalledBody{}, 32<<20)
			defer store.Close()
			f := cachedFiles(t, cache, key, store)[0]
			if err := f.loadSegments(); err != nil || f.exact {
				t.Fatal("bad hints affected NZB", err)
			}
		})
	}
}

func TestNZBHintBudgetEvictionAndOrphanCleanup(t *testing.T) {
	cache := newNZBCache(t.TempDir())
	for i := 0; i < 4; i++ {
		path := cachePath(cache, i)
		f, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := f.Truncate(nzbCacheEntryBytes - (32 << 10)); err != nil {
			t.Fatal(err)
		}
		f.Close()
		if err := os.WriteFile(strings.TrimSuffix(path, ".nzb")+".hints", make([]byte, 64<<10), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if !cache.prune(0) {
		t.Fatal("prune")
	}
	entries, _ := os.ReadDir(cache.dir)
	var total int64
	for _, e := range entries {
		info, _ := e.Info()
		total += info.Size()
	}
	if total > nzbCacheBytes || len(entries) != 6 {
		t.Fatalf("hints not counted/evicted with parent: %d / %d", total, len(entries))
	}
	orphan := filepath.Join(cache.dir, strings.Repeat("9", 64)+".hints")
	os.WriteFile(orphan, []byte("orphan"), 0600)
	newNZBCache(cache.dir)
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatal("orphan hint retained")
	}
}

func TestIndexedNZBClosedSessionDoesNotInvalidateCache(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(128), nil}})
	original, _ := ParseNZB(strings.NewReader(xml), nil)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("2", 64)
	saveIndexed(t, cache, key, original)
	store := NewStore(context.Background(), stalledBody{}, 32<<20)
	files := cachedFiles(t, cache, key, store)
	store.Close()
	if err := files[0].loadSegments(); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	cachedFiles(t, cache, key, nil)
}

func TestNZBHintSessionsMergeAndRemainBounded(t *testing.T) {
	xml, _ := fixture([]inputFile{{"one.mkv", payload(128), nil}, {"two.mkv", payload(128), nil}})
	original, _ := ParseNZB(strings.NewReader(xml), nil)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("3", 64)
	saveIndexed(t, cache, key, original)
	var stores []*Store
	for i := 0; i < 2; i++ {
		store := NewStore(context.Background(), stalledBody{}, 32<<20)
		defer store.Close()
		files := cachedFiles(t, cache, key, store)
		stores = append(stores, store)
		f := files[i]
		if err := f.loadSegments(); err != nil {
			t.Fatal(err)
		}
		if err := f.learn(0, nntppool.YEncMeta{FileSize: 128}); err != nil {
			t.Fatal(err)
		}
		for j := 0; j < 20; j++ {
			store.nzb.rememberCues(fmt.Sprint(i, "-", j), int64(j))
		}
	}
	var wg sync.WaitGroup
	for _, s := range stores {
		wg.Add(1)
		go func(s *Store) { defer wg.Done(); s.nzb.flushHints() }(s)
	}
	wg.Wait()
	hints := stores[0].nzb.readHints()
	if len(hints.Files) != 2 || len(hints.Cues) != 16 {
		t.Fatalf("merged hints: files=%d cues=%d", len(hints.Files), len(hints.Cues))
	}
	if _, err := decodeNZBDirectory([]byte("[" + strings.Repeat("{},", 10000) + "{}]")); err == nil {
		t.Fatal("unbounded directory accepted")
	}
}

func benchmarkNZBXML(files, segments int) string {
	var b strings.Builder
	b.WriteString("<nzb>")
	for i := 0; i < files; i++ {
		fmt.Fprintf(&b, `<file subject='"episode-%d.mkv" yEnc (1/1)'><segments>`, i)
		for j := 0; j < segments; j++ {
			fmt.Fprintf(&b, `<segment bytes="768000" number="%d">%d-%d@%s.invalid</segment>`, j+1, i, j, strings.Repeat("x", 180))
		}
		b.WriteString("</segments></file>")
	}
	b.WriteString("</nzb>")
	return b.String()
}

func BenchmarkNZBCachedStartup(b *testing.B) {
	for _, tc := range []struct {
		name            string
		files, segments int
	}{{"season", 100, 1600}, {"single", 1, 160000}} {
		b.Run(tc.name, func(b *testing.B) {
			xml := benchmarkNZBXML(tc.files, tc.segments)
			var compressed bytes.Buffer
			gz := gzip.NewWriter(&compressed)
			io.WriteString(gz, xml)
			gz.Close()
			files, err := ParseNZB(strings.NewReader(xml), nil)
			if err != nil {
				b.Fatal(err)
			}
			cache, key := newNZBCache(b.TempDir()), strings.Repeat("e", 64)
			saveIndexed(b, cache, key, files)
			b.Run("xml", func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					if _, err := ParseNZB(strings.NewReader(xml), nil); err != nil {
						b.Fatal(err)
					}
				}
			})
			b.Run("gzip_xml", func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					if _, err := ParseNZB(bytes.NewReader(compressed.Bytes()), nil); err != nil {
						b.Fatal(err)
					}
				}
			})
			b.Run("indexed_selected_file", func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					store := NewStore(context.Background(), stalledBody{}, 32<<20)
					files := cachedFiles(b, cache, key, store)
					if err := files[len(files)-1].loadSegments(); err != nil {
						b.Fatal(err)
					}
					store.Close()
				}
			})
			info, _ := os.Stat(filepath.Join(cache.dir, key+".nzb"))
			b.Logf("XML=%d bytes, gzip XML=%d bytes, indexed=%d bytes", len(xml), compressed.Len(), info.Size())
		})
	}
}
