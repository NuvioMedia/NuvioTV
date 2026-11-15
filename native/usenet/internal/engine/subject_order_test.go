package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// subjectPost is one NZB file of an obfuscated upload: its hex subject name,
// its subject number (0 = none), the real name in its yEnc headers and its data.
type subjectPost struct {
	number int
	yenc   string
	data   []byte
}

func setupSubjectPosts(t *testing.T, posts []subjectPost, total int) ([]*File, *nntpserver.Server, string, map[string][]byte) {
	t.Helper()
	var x strings.Builder
	x.WriteString(`<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">`)
	articles := map[string][]byte{}
	for i, p := range posts {
		var sizes []int
		for left := len(p.data); left > 0; left -= min(left, 1024) {
			sizes = append(sizes, min(left, 1024))
		}
		name := fmt.Sprintf("%032x", i*7919+1)
		subject := fmt.Sprintf(`"%s" yEnc (1/%d)`, name, len(sizes))
		if p.number > 0 {
			subject = fmt.Sprintf(`[%d/%d] - "%s" yEnc %d (1/%d)`, p.number, total, name, len(p.data), len(sizes))
		}
		fmt.Fprintf(&x, `<file subject='%s' date="1"><groups><group>alt.test</group></groups><segments>`, subject)
		off := 0
		for j, size := range sizes {
			id := fmt.Sprintf("so%d-s%d@test", i, j)
			wire := encodePart(p.yenc, p.data[off:off+size], int64(off), int64(len(p.data)), j+1, len(sizes))
			articles[id] = wire
			fmt.Fprintf(&x, `<segment bytes="%d" number="%d">%s</segment>`, len(wire), j+1, id)
			off += size
		}
		x.WriteString(`</segments></file>`)
	}
	x.WriteString(`</nzb>`)
	return parseSubjectPosts(t, x.String(), articles)
}

func parseSubjectPosts(t *testing.T, xml string, articles map[string][]byte) ([]*File, *nntpserver.Server, string, map[string][]byte) {
	t.Helper()
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
	return files, s, xml, articles
}

// subjectSet splits store.7z into equal 2 KiB volumes (two articles each) and
// posts them in subject order as perm arranges them: post p carries volume perm[p].
func subjectSet(t *testing.T, stem string, perm func(int) int) ([]subjectPost, int) {
	t.Helper()
	vols := splitVolumes(sevenZipFixture(t, "store.7z"), func(int) string { return "" }, 1024, repeat(2048, 1000)...)
	n := len(vols)
	posts := make([]subjectPost, n)
	for p := range posts {
		v := perm(p)
		posts[p] = subjectPost{number: p + 1, yenc: fmt.Sprintf("%s.7z.%03d", stem, v+1), data: vols[v].data}
	}
	return posts, n
}

func identity(i int) int { return i }

func selectCountingBodies(t *testing.T, files []*File, s *nntpserver.Server) (*Content, int64) {
	t.Helper()
	before := s.Counters().Bodies
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	return c, s.Counters().Bodies - before
}

func TestSubjectOrdered7zSetSkipsReadingEveryVolume(t *testing.T) {
	posts, n := subjectSet(t, "sKip0rd3r", identity)
	files, s, _, _ := setupSubjectPosts(t, posts, n)
	c, bodies := selectCountingBodies(t, files, s)
	if c.Name != "movie.mkv" || !bytes.Equal(readAllContent(t, c), moviePayload()) {
		t.Fatalf("selected %q, content mismatch", c.Name)
	}
	// The full read alone asks for every volume's first article.
	if bodies >= int64(n) {
		t.Fatalf("Select read %d articles for %d volumes", bodies, n)
	}
}

func TestSubjectOrderedVolumeOrderIsVerifiedOnRead(t *testing.T) {
	// The full read takes this upload in its yEnc names' order instead.
	t.Run("disagreeing sample", func(t *testing.T) {
		posts, n := subjectSet(t, "sAmpl3", func(i int) int {
			switch i {
			case 1:
				return 2
			case 2:
				return 1
			}
			return i
		})
		files, _, _, _ := setupSubjectPosts(t, posts, n)
		selectAndRead(t, files, "movie.mkv", moviePayload())
	})
	t.Run("volume between samples", func(t *testing.T) {
		posts, n := subjectSet(t, "b3tw33n", identity)
		swap := 0
		for k := 2; k < n-3 && swap == 0; k++ {
			if !sampledPosition(k, n) && !sampledPosition(k+1, n) {
				swap = k
			}
		}
		if swap == 0 {
			t.Fatal("no unsampled pair")
		}
		posts[swap].data, posts[swap+1].data = posts[swap+1].data, posts[swap].data
		posts[swap].yenc, posts[swap+1].yenc = posts[swap+1].yenc, posts[swap].yenc
		files, _, xml, articles := setupSubjectPosts(t, posts, n)
		c, err := Select(context.Background(), files, Selection{})
		if err != nil {
			t.Fatal(err)
		}
		r := c.Reader(context.Background(), 0)
		_, err = io.ReadAll(r)
		r.Close()
		if !errors.Is(err, err7zVolumeOrder) || permanentStreamFailure(err) != "" {
			t.Fatalf("read error = %v (%q), want a volume order error that keeps the release", err, permanentStreamFailure(err))
		}
		// The next open of the same upload orders it by every volume's name.
		again, _, _, _ := parseSubjectPosts(t, xml, articles)
		selectAndRead(t, again, "movie.mkv", moviePayload())
	})
}

func sampledPosition(k, n int) bool {
	if k <= 1 || k >= n-2 {
		return true
	}
	for i := 1; i < subjectOrderSamples; i++ {
		if i*(n-1)/subjectOrderSamples == k {
			return true
		}
	}
	return false
}

// Uploads outside the conditions keep reading every volume's name first, and
// still play.
func TestSubjectOrderKeepsFullReadOtherwise(t *testing.T) {
	par2 := append([]byte("PAR2\x00PKT"), payload(5000)...)
	cases := map[string]func(t *testing.T) ([]subjectPost, int){
		"gap in numbering": func(t *testing.T) ([]subjectPost, int) {
			posts, n := subjectSet(t, "g4pN0", identity)
			for p := n / 2; p < n; p++ {
				posts[p].number++
			}
			return posts, n + 1
		},
		"PAR2 after the volumes": func(t *testing.T) ([]subjectPost, int) {
			posts, n := subjectSet(t, "p4rAft3r", identity)
			posts = append(posts, subjectPost{n + 1, "p4rAft3r.7z.vol00+01.par2", par2}, subjectPost{n + 2, "p4rAft3r.7z.vol01+02.par2", par2})
			return posts, n + 2
		},
		"PAR2 among the volumes": func(t *testing.T) ([]subjectPost, int) {
			posts, n := subjectSet(t, "p4rAm0ng", identity)
			at := n / 3
			posts = append(posts[:at:at], append([]subjectPost{{0, "p4rAm0ng.7z.vol00+01.par2", par2}}, posts[at:]...)...)
			for p := range posts {
				posts[p].number = p + 1
			}
			return posts, n + 1
		},
		"unnumbered subjects": func(t *testing.T) ([]subjectPost, int) {
			posts, n := subjectSet(t, "uNnumb3r", identity)
			for p := range posts {
				posts[p].number = 0
			}
			return posts, n
		},
		"few volumes": func(t *testing.T) ([]subjectPost, int) {
			vols := splitVolumes(sevenZipFixture(t, "store.7z"), func(int) string { return "" }, 1024, repeat(8192, 100)...)
			var posts []subjectPost
			for i, v := range vols {
				posts = append(posts, subjectPost{i + 1, fmt.Sprintf("f3wV0l.7z.%03d", i+1), v.data})
			}
			return posts, len(vols)
		},
	}
	for name, build := range cases {
		t.Run(name, func(t *testing.T) {
			posts, total := build(t)
			files, s, _, _ := setupSubjectPosts(t, posts, total)
			c, bodies := selectCountingBodies(t, files, s)
			if c.Name != "movie.mkv" || !bytes.Equal(readAllContent(t, c), moviePayload()) {
				t.Fatalf("selected %q, content mismatch", c.Name)
			}
			volumes := 0
			for _, p := range posts {
				if !bytes.HasPrefix(p.data, []byte("PAR2")) {
					volumes++
				}
			}
			if bodies < int64(volumes) {
				t.Fatalf("Select read %d articles for %d volumes; want every volume's first article", bodies, volumes)
			}
		})
	}
}
