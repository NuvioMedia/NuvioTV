package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// testdata/sevenzip archives were made with 7-Zip 24 from moviePayload:
//
//	7z a -mx0 -mhe=on  -p"Pässwörd 7z" aes_hdr.7z movie.mkv
//	7z a -mx0 -mhe=off -p"Pässwörd 7z" aes.7z movie.mkv
//	7z a -mx0 store.7z movie.mkv
//	7z a -m0=Copy -ms=100f10m -mhe=on -p"Pässwörd 7z" aes_solid.7z a.dat movie.mkv
//	7z a -mx1 -mhe=on -p"Pässwörd 7z" lzma.7z movie.mkv
//
// a.dat (1001 bytes) shares aes_solid's folder, so movie.mkv starts mid-block.
const sevenZipTestPassword = "Pässwörd 7z"

func moviePayload() []byte {
	b := payload(100003)
	copy(b, []byte{0x1a, 0x45, 0xdf, 0xa3})
	return b
}

func sevenZipFixture(t testing.TB, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "testdata", "sevenzip", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// splitVolumes cuts an archive into volumes of the given sizes (the last one
// takes the rest), each posted as articles of at most article bytes.
func splitVolumes(archive []byte, name func(int) string, article int, sizes ...int) []inputFile {
	var out []inputFile
	for i := 0; len(archive) > 0; i++ {
		n := len(archive)
		if i < len(sizes) && sizes[i] < n {
			n = sizes[i]
		}
		vol := inputFile{name: name(i), data: archive[:n]}
		for left := n; left > 0; left -= article {
			vol.sizes = append(vol.sizes, min(left, article))
		}
		out = append(out, vol)
		archive = archive[n:]
	}
	return out
}

func repeat(size, count int) []int {
	s := make([]int, count)
	for i := range s {
		s[i] = size
	}
	return s
}

func fixtureWithPassword(in []inputFile, password string) (string, map[string][]byte) {
	xml, articles := fixture(in)
	if password != "" {
		i := strings.IndexByte(xml, '>') + 1
		xml = xml[:i] + `<head><meta type="title">x</meta><meta type="password">` + password + `</meta></head>` + xml[i:]
	}
	return xml, articles
}

func setupWithPassword(t testing.TB, in []inputFile, password string) []*File {
	t.Helper()
	xml, articles := fixtureWithPassword(in, password)
	s, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := nntppool.NewClient(context.Background(), []nntppool.Provider{{Host: s.Addr(), Connections: 4, Inflight: 4, StreamInflight: 4, SkipPing: true}}, nntppool.WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	store := NewStore(context.Background(), pool, 32<<20)
	t.Cleanup(func() { store.Close(); pool.Close(); s.Close() })
	files, err := ParseNZB(strings.NewReader(xml), store)
	if err != nil {
		t.Fatal(err)
	}
	return files
}

func readAllContent(t testing.TB, c *Content) []byte {
	t.Helper()
	r := c.Reader(context.Background(), 2)
	defer r.Close()
	got, err := io.ReadAll(r)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

func TestParseNZBPassword(t *testing.T) {
	files := setupWithPassword(t, []inputFile{{"a.mkv", payload(10), nil}, {"b.mkv", payload(10), nil}}, sevenZipTestPassword)
	for _, f := range files {
		if f.password != sevenZipTestPassword {
			t.Fatalf("password = %q", f.password)
		}
	}
	if files := setupWithPassword(t, []inputFile{{"a.mkv", payload(10), nil}}, ""); files[0].password != "" {
		t.Fatal("password invented")
	}
}

func TestSevenZipStoredStreams(t *testing.T) {
	want := moviePayload()
	numbered := func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }
	obfuscated := func(i int) string { return fmt.Sprintf("q8Zt3kW1xPa7Rv%02d", i) }
	for _, archive := range []string{"aes_hdr.7z", "aes.7z", "store.7z", "aes_solid.7z"} {
		data := sevenZipFixture(t, archive)
		password := sevenZipTestPassword
		if archive == "store.7z" {
			password = ""
		}
		layouts := map[string][]inputFile{
			"single":     {{name: "Release.7z", data: data, sizes: []int{30000, 30000, len(data) - 60000}}},
			"uniform":    splitVolumes(data, numbered, 7000, repeat(16384, 10)...),
			"obfuscated": splitVolumes(data, obfuscated, 9000, repeat(20000, 10)...),
			"irregular":  splitVolumes(data, numbered, 5000, 30000, 12000, 25000, 12000),
			"two":        splitVolumes(data, numbered, 50000, 60000),
		}
		for layout, vols := range layouts {
			t.Run(archive+"/"+layout, func(t *testing.T) {
				files := setupWithPassword(t, vols, password)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				if c.Name != "movie.mkv" || c.Size != int64(len(want)) {
					t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
				}
				if archive == "aes_solid.7z" && (c.aes == nil || c.aes.fileRel != 1001) {
					t.Fatal("solid fixture does not exercise a mid-block entry")
				}
				if got := readAllContent(t, c); !bytes.Equal(got, want) {
					t.Fatalf("sequential read mismatch (%d bytes)", len(got))
				}
				rng := rand.New(rand.NewSource(int64(len(layout))))
				r := c.Reader(context.Background(), 1)
				defer r.Close()
				for i := 0; i < 200; i++ {
					off := rng.Int63n(c.Size)
					buf := make([]byte, 1+rng.Intn(40000))
					if i%7 == 0 {
						off = c.Size - int64(len(buf)) + rng.Int63n(int64(len(buf))) // tail and padding
					}
					if _, err := r.Seek(off, io.SeekStart); err != nil {
						t.Fatal(err)
					}
					n, err := io.ReadFull(r, buf)
					if err != nil && !(errors.Is(err, io.ErrUnexpectedEOF) && off+int64(n) == c.Size) {
						t.Fatalf("read at %d: %v", off, err)
					}
					if !bytes.Equal(buf[:n], want[off:off+int64(n)]) {
						t.Fatalf("seek read mismatch at %d (+%d)", off, n)
					}
				}
			})
		}
	}
}

func TestSevenZipRejectsUnstreamableArchives(t *testing.T) {
	for _, tc := range []struct {
		archive, password string
		want              error
	}{
		{"lzma.7z", sevenZipTestPassword, ErrCompressed7z},
		{"aes_hdr.7z", "", Err7zPasswordMissing},
		{"aes_hdr.7z", "wrong", Err7zWrongPassword},
		{"aes.7z", "", Err7zPasswordMissing},
		{"aes.7z", "wrong", Err7zWrongPassword},
	} {
		t.Run(tc.archive+"/"+tc.password, func(t *testing.T) {
			data := sevenZipFixture(t, tc.archive)
			files := setupWithPassword(t, splitVolumes(data, func(i int) string { return fmt.Sprintf("x.7z.%03d", i+1) }, 8000, repeat(32768, 4)...), tc.password)
			if _, err := Select(context.Background(), files, Selection{}); !errors.Is(err, tc.want) {
				t.Fatalf("err = %v, want %v", err, tc.want)
			}
		})
	}
}

// Middle volume sizes are predicted. A volume that disagrees must fail the
// read instead of shifting every later offset into garbage plaintext.
func TestSevenZipVolumePredictionIsChecked(t *testing.T) {
	data := sevenZipFixture(t, "aes_hdr.7z")
	// Volumes 2 and 5 (the probed ones) share a size; volume 3 does not.
	vols := splitVolumes(data, func(i int) string { return fmt.Sprintf("x.7z.%03d", i+1) }, 6000, 16384, 16384, 20000, 12768, 16384)
	files := setupWithPassword(t, vols, sevenZipTestPassword)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	if _, err := io.ReadAll(r); !errors.Is(err, err7zVolumeLayout) {
		t.Fatalf("err = %v, want %v", err, err7zVolumeLayout)
	}
}

func TestSevenZipPasswordSurvivesNZBCache(t *testing.T) {
	data := sevenZipFixture(t, "aes_hdr.7z")
	original := setupWithPassword(t, splitVolumes(data, func(i int) string { return fmt.Sprintf("x.7z.%03d", i+1) }, 8000, repeat(32768, 4)...), sevenZipTestPassword)
	cache, key := newNZBCache(t.TempDir()), strings.Repeat("b", 64)
	saveIndexed(t, cache, key, original)
	store := NewStore(context.Background(), original[0].store.client, 32<<20)
	defer store.Close()
	files := cachedFiles(t, cache, key, store)
	if files[0].password != sevenZipTestPassword {
		t.Fatalf("cached password = %q", files[0].password)
	}
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if got := readAllContent(t, c); !bytes.Equal(got, moviePayload()) {
		t.Fatal("cached 7z read mismatch")
	}
}

func TestDerive7zKeyMatchesSevenZip(t *testing.T) {
	// Cross-checked by the fixtures above; this pins the no-hash mode too.
	key := derive7zKey("ab", []byte{1, 2}, 0)
	if !bytes.Equal(key[:6], []byte{1, 2, 'a', 0, 'b', 0}) || len(key) != 32 {
		t.Fatalf("key = %x", key)
	}
}

// The player's view: open a session from a password-protected NZB, then read
// it through HTTP ranges with the MKV startup warmup enabled.
func TestSevenZipSessionRanges(t *testing.T) {
	want := moviePayload()
	vols := splitVolumes(sevenZipFixture(t, "aes_hdr.7z"), func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }, 7000, repeat(16384, 10)...)
	for _, tc := range []struct {
		password string
		status   int
	}{{sevenZipTestPassword, 200}, {"", 422}} {
		xml, articles := fixtureWithPassword(vols, tc.password)
		provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
		if err != nil {
			t.Fatal(err)
		}
		defer provider.Close()
		nzb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
		defer nzb.Close()
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		app := NewServer(ctx, "secret", nil, nzb.Client())
		defer app.Close()
		srv := httptest.NewServer(app)
		defer srv.Close()
		b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"},
			Config: Config{AllowPrivateNetwork: true, FastMKVStartup: true}})
		r, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
		r.Header.Set("Authorization", "Bearer secret")
		resp, err := srv.Client().Do(r)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != tc.status {
			t.Fatalf("open status %d: %s", resp.StatusCode, body)
		}
		if tc.status != 200 {
			if !strings.Contains(string(body), Err7zPasswordMissing.Error()) {
				t.Fatalf("open error %q is not actionable", body)
			}
			continue
		}
		var session struct {
			Path string
			Size int64
		}
		if err := json.Unmarshal(body, &session); err != nil || session.Size != int64(len(want)) || !strings.HasSuffix(session.Path, "/movie.mkv") {
			t.Fatalf("session %s: %v", body, err)
		}
		for _, rng := range []struct {
			header   string
			from, to int64
		}{{"", 0, int64(len(want))}, {"bytes=1000-2000", 1000, 2001}, {"bytes=-777", int64(len(want)) - 777, int64(len(want))}, {"bytes=50001-", 50001, int64(len(want))}} {
			req, _ := http.NewRequest(http.MethodGet, srv.URL+session.Path, nil)
			if rng.header != "" {
				req.Header.Set("Range", rng.header)
			}
			resp, err := srv.Client().Do(req)
			if err != nil {
				t.Fatal(err)
			}
			got, err := io.ReadAll(resp.Body)
			resp.Body.Close()
			if err != nil || !bytes.Equal(got, want[rng.from:rng.to]) {
				t.Fatalf("range %q: status=%d len=%d err=%v", rng.header, resp.StatusCode, len(got), err)
			}
		}
	}
}

// nested_rar4.7z and nested_rar5.7z wrap stored RAR sets the way some indexers
// re-post scene releases (7z -mx0 -mhe=on -p"Pässwörd 7z"). The volumes come
// from rar4Volume and regularRARSet: RAR4 uses scene .rar/.r00 names with one
// short middle volume (no prediction), RAR5 is regular (predicted middles).
func TestSevenZipNestedRAR(t *testing.T) {
	rar4 := payload(30000*4 + 20000 + 12345)
	_, rar5, _ := regularRARSet(5, 7, 25000, 9999)
	for _, tc := range []struct {
		archive   string
		want      []byte
		predicted bool
	}{{"nested_rar4.7z", rar4, false}, {"nested_rar5.7z", rar5, true}} {
		t.Run(tc.archive, func(t *testing.T) {
			vols := splitVolumes(sevenZipFixture(t, tc.archive), func(i int) string { return fmt.Sprintf("x.7z.%03d", i+1) }, 9000, repeat(32768, 10)...)
			files := setupWithPassword(t, vols, sevenZipTestPassword)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != "movie.mkv" || c.Size != int64(len(tc.want)) || c.nested == nil {
				t.Fatalf("selected %q (%d bytes, nested=%t)", c.Name, c.Size, c.nested != nil)
			}
			predicted := false
			for _, ch := range c.children {
				predicted = predicted || !ch.verified
			}
			if predicted != tc.predicted {
				t.Fatalf("predicted middles = %t, want %t", predicted, tc.predicted)
			}
			rng := rand.New(rand.NewSource(7))
			r := c.Reader(context.Background(), 1)
			defer r.Close()
			for i := 0; i < 150; i++ {
				off := rng.Int63n(c.Size)
				buf := make([]byte, 1+rng.Intn(60000))
				if _, err := r.Seek(off, io.SeekStart); err != nil {
					t.Fatal(err)
				}
				n, err := io.ReadFull(r, buf)
				if err != nil && !(errors.Is(err, io.ErrUnexpectedEOF) && off+int64(n) == c.Size) {
					t.Fatalf("read at %d: %v", off, err)
				}
				if !bytes.Equal(buf[:n], tc.want[off:off+int64(n)]) {
					t.Fatalf("seek read mismatch at %d (+%d)", off, n)
				}
			}
			if got := readAllContent(t, c); !bytes.Equal(got, tc.want) {
				t.Fatalf("sequential read mismatch (%d bytes)", len(got))
			}
		})
	}
}
