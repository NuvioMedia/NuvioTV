package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

func damagedSetup(t *testing.T, in []inputFile, damaged ...int) []*File {
	t.Helper()
	xml, articles := fixture(in)
	for _, i := range damaged {
		before := fmt.Sprintf(`number="2">f%d-s1@test`, i)
		if !strings.Contains(xml, before) {
			t.Fatalf("file %d needs at least two segments", i)
		}
		xml = strings.Replace(xml, before, fmt.Sprintf(`number="3">f%d-s1@test`, i), 1)
	}
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
		t.Fatalf("ParseNZB rejected the release: %v", err)
	}
	return files
}

func readContent(t *testing.T, c *Content) []byte {
	t.Helper()
	r := c.Reader(context.Background(), 4)
	defer r.Close()
	got, err := io.ReadAll(r)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

func twoSegments(n int) []int { return []int{n / 2, n - n/2} }

func TestDamagedSideFileDoesNotRejectRelease(t *testing.T) {
	movie := payload(200000)
	files := damagedSetup(t, []inputFile{
		{"Movie.2025.mkv", movie, nil},
		{"Movie.2025.nfo", payload(2000), twoSegments(2000)},
		{"Movie.2025.par2", payload(2000), twoSegments(2000)},
		{"Movie.2025.srt", payload(2000), twoSegments(2000)},
	}, 1, 2, 3)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "Movie.2025.mkv" || !bytes.Equal(readContent(t, c), movie) {
		t.Fatalf("selected %q with wrong payload", c.Name)
	}
	if subs := standaloneSubtitles(files, Selection{}); len(subs) != 0 {
		t.Fatalf("damaged subtitle offered: %v", subs[0].Name)
	}
}

func TestDamagedSelectedVideoStillFails(t *testing.T) {
	files := damagedSetup(t, []inputFile{
		{"Movie.2025.mkv", payload(200000), twoSegments(200000)},
		{"Movie.2025.sample.mkv", payload(20000), nil},
		{"Movie.2025.Featurette.mkv", payload(30000), nil},
	}, 0)
	if _, err := Select(context.Background(), files, Selection{}); !errors.Is(err, errDamagedNZBFile) {
		t.Fatalf("Select error = %v, want damaged NZB file", err)
	}
}

func TestDamagedOtherEpisodeIsSkipped(t *testing.T) {
	ep2 := payload(100000)
	files := damagedSetup(t, []inputFile{
		{"Show.S01E01.mkv", payload(100000), twoSegments(100000)},
		{"Show.S01E02.mkv", ep2, nil},
	}, 0)
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "Show.S01E02.mkv" || !bytes.Equal(readContent(t, c), ep2) {
		t.Fatalf("selected %q with wrong payload", c.Name)
	}
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 1}); !errors.Is(err, errDamagedNZBFile) {
		t.Fatalf("damaged requested episode error = %v", err)
	}
}

func TestDamagedObfuscatedEpisodeIsNotUsedAsFallback(t *testing.T) {
	files := damagedSetup(t, []inputFile{{"8d2f1b.mkv", payload(100000), twoSegments(100000)}}, 0)
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2}); !errors.Is(err, errDamagedNZBFile) {
		t.Fatalf("Select error = %v, want damaged NZB file", err)
	}
}

func TestBrokenRARSetSkippedForNamedEpisode(t *testing.T) {
	ep1 := payload(1000)
	ep2 := payload(1000)
	files := damagedSetup(t, []inputFile{
		{"ep1.part01.rar", rar4Volume("Show.S01E01.mkv", ep1[:500], len(ep1), 2, false), nil},
		{"ep1.part03.rar", rar4Volume("Show.S01E01.mkv", ep1[500:], len(ep1), 1, false), nil},
		{"ep2.rar", rar4Volume("Show.S01E02.mkv", ep2, len(ep2), 0, false), nil},
	})
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "Show.S01E02.mkv" || !bytes.Equal(readContent(t, c), ep2) {
		t.Fatalf("selected %q with wrong payload", c.Name)
	}
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 1}); err == nil || err.Error() != "RAR volume sequence is incomplete" {
		t.Fatalf("broken requested episode error = %v", err)
	}
	if _, err := Select(context.Background(), files, Selection{}); err == nil || err.Error() != "RAR volume sequence is incomplete" {
		t.Fatalf("unnamed selection must keep failing on a broken set: %v", err)
	}
}

func TestDamagedVolumeInSelectedRARSetFails(t *testing.T) {
	want := payload(600000)
	second := rar4Volume("Show.S01E02.mkv", want[300000:], len(want), 1, false)
	files := damagedSetup(t, []inputFile{
		{"show.part01.rar", rar4Volume("Show.S01E02.mkv", want[:300000], len(want), 2, false), nil},
		{"show.part02.rar", second, twoSegments(len(second))},
	}, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := Select(ctx, files, Selection{Season: 1, Episode: 2}); !errors.Is(err, errDamagedNZBFile) {
		t.Fatalf("Select error = %v, want damaged NZB file", err)
	}
}

func TestDamagedReleaseIsNotIndexed(t *testing.T) {
	files := damagedSetup(t, []inputFile{
		{"Movie.2025.mkv", payload(20000), nil},
		{"Movie.2025.nfo", payload(2000), twoSegments(2000)},
	}, 1)
	cache := newNZBCache(t.TempDir())
	fill, _ := cache.begin(fmt.Sprintf("%064x", 1))
	if fill == nil {
		t.Fatal("cache fill unavailable")
	}
	if got := fill.indexed(files); got == "saved" {
		t.Fatal("release with a damaged file was indexed")
	}
}
