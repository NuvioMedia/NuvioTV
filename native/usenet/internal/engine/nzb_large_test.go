package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
)

// Lowers what parseNZB holds in memory, so a small fixture stands for a document past the real bounds.
func withHeldNZBLimits(t *testing.T, bytes, segments int64) {
	t.Helper()
	oldBytes, oldSegments := heldNZBBytes, heldNZBSegments
	heldNZBBytes, heldNZBSegments = bytes, segments
	t.Cleanup(func() { heldNZBBytes, heldNZBSegments = oldBytes, oldSegments })
}

// A season pack whose episodes differ, three articles each.
func largePackFixture() (string, map[string][]byte, map[string][]byte) {
	files := []inputFile{}
	data := map[string][]byte{}
	for e := 1; e <= 3; e++ {
		b := mkvPayload(300000)
		b[1000] = byte(e)
		name := fmt.Sprintf("Show.S01E%02d.1080p.mkv", e)
		files = append(files, inputFile{name: name, data: b})
		data[name] = b
	}
	xml, articles := fixture(files)
	return xml, articles, data
}

func serveNZB(t *testing.T, xml string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	t.Cleanup(srv.Close)
	return srv
}

func segmentIDs(t *testing.T, f *File) []string {
	t.Helper()
	if err := f.loadSegments(); err != nil {
		t.Fatal(err)
	}
	ids := make([]string, len(f.segments))
	for i, s := range f.segments {
		ids[i] = s.id
	}
	return ids
}

// Past the in-memory bounds, by segment count or by size, a document is read
// into the cache index one file at a time and each file is read back from it.
func TestNZBPastTheHeldBoundsIsReadIntoTheCacheIndex(t *testing.T) {
	xml, _, _ := largePackFixture()
	reference, err := ParseNZB(strings.NewReader(xml), nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, bound := range []struct {
		name            string
		bytes, segments int64
	}{{"segments", maxNZBBytes, 4}, {"bytes", 700, maxNZBSegments}} {
		t.Run(bound.name, func(t *testing.T) {
			withHeldNZBLimits(t, bound.bytes, bound.segments)
			srv := serveNZB(t, xml)
			if _, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, false, nil, nil, "1", "", nil); err == nil {
				t.Fatal("without the NZB cache a document past the bounds must still fail")
			}
			// Created first so it is removed after the store closes its index file.
			cache := newNZBCache(t.TempDir())
			store := NewStore(context.Background(), stalledBody{}, 32<<20)
			t.Cleanup(store.Close)
			trace := newStartupTrace()
			files, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, store, false, cache, nil, "1", "", trace)
			if err != nil {
				t.Fatal(err)
			}
			if trace.nzbCache == nil || trace.nzbCache.Write != "saved" || trace.nzbCache.Format != "indexed" {
				t.Fatalf("cache diagnostic = %+v", trace.nzbCache)
			}
			if len(files) != len(reference) {
				t.Fatalf("files = %d, want %d", len(files), len(reference))
			}
			for i, f := range files {
				if f.cached == nil || f.segments != nil {
					t.Fatalf("%s holds its segments instead of reading them from the index", f.Name)
				}
				if f.Name != reference[i].Name || f.Index != reference[i].Index || f.Size() != reference[i].Size() {
					t.Fatalf("file %d = %s/%d/%d, want %s/%d/%d", i, f.Name, f.Index, f.Size(), reference[i].Name, reference[i].Index, reference[i].Size())
				}
				want := make([]string, len(reference[i].segments))
				for j, s := range reference[i].segments {
					want[j] = s.id
				}
				if got := segmentIDs(t, f); strings.Join(got, ",") != strings.Join(want, ",") {
					t.Fatalf("%s segments = %v, want %v", f.Name, got, want)
				}
			}
			// The next open is a plain cache hit.
			again, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, store, false, cache, nil, "1", "", nil)
			if err != nil || len(again) != len(reference) {
				t.Fatalf("cache hit = %d files, %v", len(again), err)
			}
		})
	}
}

// Within the bounds nothing changes: the document is parsed in memory and indexed after.
func TestNZBWithinTheHeldBoundsIsParsedAsBefore(t *testing.T) {
	xml, _, _ := largePackFixture()
	files, spilled, err := parseNZB(strings.NewReader(xml), nil, &recordingSpill{})
	if err != nil || spilled {
		t.Fatalf("spilled = %v, %v", spilled, err)
	}
	for _, f := range files {
		if len(f.segments) == 0 {
			t.Fatalf("%s lost its segments", f.Name)
		}
	}
}

// Records what parseNZB hands the cache index, without a cache.
type recordingSpill struct {
	started bool
	names   []string
	refuse  int // fail after this many files, when positive
}

func (s *recordingSpill) startIndex() { s.started = true }
func (s *recordingSpill) appendIndexed(f *File) bool {
	if s.refuse > 0 && len(s.names) >= s.refuse {
		return false
	}
	s.names = append(s.names, f.Name)
	return true
}

// A damaged file stays out of the index but is still listed, so selection reports it.
func TestNZBReadIntoTheIndexKeepsDamagedFilesListed(t *testing.T) {
	withHeldNZBLimits(t, maxNZBBytes, 4)
	xml, _, _ := largePackFixture()
	// Episode 2 loses its second segment number: its metadata is damaged.
	damaged := strings.Replace(xml, `number="2"`, `number="7"`, 2)
	damaged = strings.Replace(damaged, `number="7"`, `number="2"`, 1)
	spill := &recordingSpill{}
	files, spilled, err := parseNZB(strings.NewReader(damaged), nil, spill)
	if err != nil || !spilled || !spill.started {
		t.Fatalf("spilled = %v, started = %v, %v", spilled, spill.started, err)
	}
	if len(files) != 3 || files[1].damaged == nil || strings.Join(spill.names, ",") != files[0].Name+","+files[2].Name {
		t.Fatalf("indexed %v, damaged %v", spill.names, files[1].damaged)
	}
	for _, f := range files {
		if f.segments != nil {
			t.Fatalf("%s still holds segments", f.Name)
		}
	}
	bound, err := withDamaged([]*File{{Name: files[0].Name, Index: 0, cached: &cachedNZBFile{}}, {Name: files[2].Name, Index: 2, cached: &cachedNZBFile{}}}, files)
	if err != nil || len(bound) != 3 || bound[1] != files[1] {
		t.Fatalf("bound = %v, %v", bound, err)
	}
	if _, err := withDamaged(files, files); err != errNZBTooLargeToHold {
		t.Fatal("files without a bound index must not be served")
	}
}

// When the index cannot take the document, opening it fails instead of serving files without segments.
func TestNZBThatTheIndexCannotTakeFailsToOpen(t *testing.T) {
	withHeldNZBLimits(t, maxNZBBytes, 4)
	xml, _, _ := largePackFixture()
	if _, _, err := parseNZB(strings.NewReader(xml), nil, &recordingSpill{refuse: 1}); err == nil {
		t.Fatal("a refused index must fail the document")
	}
	if _, _, err := parseNZB(strings.NewReader(xml), nil, nil); err == nil || !strings.Contains(err.Error(), "segment metadata limit") {
		t.Fatalf("without an index: %v", err)
	}
}

// End to end: an episode of a pack past the bounds opens and streams its own bytes.
func TestEpisodeOfAPackPastTheHeldBoundsPlays(t *testing.T) {
	withHeldNZBLimits(t, maxNZBBytes, 4)
	xml, articles, data := largePackFixture()
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	nzb := serveNZB(t, xml)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, nzb.Client())
	app.SetNZBCacheDir(t.TempDir())
	defer app.Close()
	srv := httptest.NewServer(app)
	defer srv.Close()
	b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"},
		Selection: Selection{Season: 1, Episode: 2}, Config: Config{AllowPrivateNetwork: true, CacheNZB: true}})
	r, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
	r.Header.Set("Authorization", "Bearer secret")
	resp, err := srv.Client().Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("open = %d %s", resp.StatusCode, body)
	}
	var opened struct {
		Path     string `json:"path"`
		Filename string `json:"filename"`
		Startup  struct {
			NZBCache struct {
				Write  string `json:"write"`
				Format string `json:"format"`
			} `json:"nzbCache"`
		} `json:"startup"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&opened); err != nil {
		t.Fatal(err)
	}
	if opened.Filename != "Show.S01E02.1080p.mkv" || opened.Startup.NZBCache.Write != "saved" {
		t.Fatalf("opened %+v", opened)
	}
	stream, err := srv.Client().Get(srv.URL + opened.Path)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Body.Close()
	got, err := io.ReadAll(stream.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, data["Show.S01E02.1080p.mkv"]) {
		t.Fatalf("streamed %d bytes, not episode 2", len(got))
	}
}
