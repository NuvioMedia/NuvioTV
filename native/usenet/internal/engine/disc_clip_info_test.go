package engine

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// discWithClipInfo stores a disc as rar does: the CLIPINF entries first, then
// the clips, the feature last and split over three volumes.
func discWithClipInfo(root string) ([]inputFile, []byte, []byte) {
	info, other := payload(3000)[100:], payload(900)[50:]
	extra, movie := payload(5000)[200:], payload(90000)
	in := []inputFile{
		{"disc.part1.rar", rar4MultiVolume(
			rar4Entry{root + "BDMV/CLIPINF/00001.clpi", other, len(other), 0},
			rar4Entry{root + "BDMV/CLIPINF/00065.clpi", info, len(info), 0},
			rar4Entry{root + "BDMV/STREAM/00001.m2ts", extra, len(extra), 0},
			rar4Entry{root + "BDMV/STREAM/00065.m2ts", movie[:30000], len(movie), 2}), nil},
		{"disc.part2.rar", rar4MultiVolume(rar4Entry{root + "BDMV/STREAM/00065.m2ts", movie[30000:60000], len(movie), 3}), nil},
		{"disc.part3.rar", rar4MultiVolume(rar4Entry{root + "BDMV/STREAM/00065.m2ts", movie[60000:], len(movie), 1}), nil},
	}
	return in, info, movie
}

func TestDiscClipServesItsClipInfo(t *testing.T) {
	for _, root := range []string{"", "Movie.2026.UHD.BluRay/"} {
		t.Run("root="+root, func(t *testing.T) {
			in, info, movie := discWithClipInfo(root)
			files, store, _ := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil || c.Name != root+"BDMV/STREAM/00065.m2ts" {
				t.Fatalf("selected %v, %v", c, err)
			}
			if c.clipInfo == nil || c.clipInfo.Name != root+"BDMV/CLIPINF/00065.clpi" {
				t.Fatalf("clip info = %v", c.clipInfo)
			}
			ctx, cancel := context.WithCancel(context.Background())
			app := NewServer(ctx, "secret", nil, &http.Client{})
			app.sessions["d"] = &Session{ctx: ctx, cancel: cancel, store: store, content: c, trace: newStartupTrace()}
			srv := httptest.NewServer(app)
			t.Cleanup(func() { srv.Close(); cancel() }) // The session has no pool of its own to close.
			get := func(path, rng string) []byte {
				req, _ := http.NewRequest(http.MethodGet, srv.URL+path, nil)
				if rng != "" {
					req.Header.Set("Range", rng)
				}
				resp, err := srv.Client().Do(req)
				if err != nil {
					t.Fatal(err)
				}
				defer resp.Body.Close()
				b, _ := io.ReadAll(resp.Body)
				return b
			}
			clip := "/stream/d/" + streamPath(c.Name)
			if clip != "/stream/d/BDMV/STREAM/00065.m2ts" {
				t.Fatalf("stream path = %s", clip)
			}
			if got := get("/stream/d/BDMV/CLIPINF/00065.clpi", ""); !bytes.Equal(got, info) {
				t.Fatalf("clip info bytes differ (%d)", len(got))
			}
			if got := get(clip, "bytes=29990-30009"); !bytes.Equal(got, movie[29990:30010]) {
				t.Fatal("clip bytes differ")
			}
			// Another clip's CLPI is not served, and never falls back to the clip.
			resp, err := srv.Client().Get(srv.URL + "/stream/d/BDMV/CLIPINF/00001.clpi")
			if err != nil {
				t.Fatal(err)
			}
			resp.Body.Close()
			if resp.StatusCode != http.StatusNotFound {
				t.Fatalf("other CLPI status = %d", resp.StatusCode)
			}
		})
	}
}

func TestClipInfoOnlyForDiscClips(t *testing.T) {
	for name, want := range map[string]string{
		"BDMV/STREAM/00065.m2ts":           "BDMV/STREAM/00065.m2ts",
		"Disc/BDMV/STREAM/00800.M2TS":      "BDMV/STREAM/00800.m2ts",
		"Show.S01/Show.S01E02.mkv":         "Show.S01E02.mkv",
		"movie.m2ts":                       "movie.m2ts",
		"Disc/BDMV/STREAM/SSIF/00065.ssif": "00065.ssif",
	} {
		if got := streamPath(name); got != want {
			t.Errorf("streamPath(%q) = %q, want %q", name, got, want)
		}
	}
	if clipInfoName("movie.m2ts") != "" || clipInfoPath("movie.mkv") != "" {
		t.Fatal("a file outside BDMV/STREAM has clip info")
	}
	if !isClipInfo("Disc/BDMV/CLIPINF/00001.CLPI") || isClipInfo("CLIPINF/00001.clpi") || isClipInfo("BDMV/STREAM/00001.m2ts") {
		t.Fatal("clip info names")
	}
	in, _, _ := discWithClipInfo("")
	in = in[:1]
	in[0].data = rar4MultiVolume(rar4Entry{"BDMV/CLIPINF/00065.clpi", payload(maxClipInfoBytes + 1), maxClipInfoBytes + 1, 0},
		rar4Entry{"BDMV/STREAM/00065.m2ts", payload(5000), 5000, 0})
	files, _, _ := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil || c.clipInfo != nil {
		t.Fatalf("an oversized CLPI was kept: %v, %v", c.clipInfo, err)
	}
}
