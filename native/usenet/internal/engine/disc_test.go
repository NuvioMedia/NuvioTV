package engine

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// discServer serves a selected disc clip the way a session does.
func discServer(t *testing.T, c *Content, store *Store) func(path string) (int, []byte) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	app := NewServer(ctx, "secret", nil, &http.Client{})
	app.sessions["d"] = &Session{ctx: ctx, cancel: cancel, store: store, content: c, trace: newStartupTrace()}
	srv := httptest.NewServer(app)
	t.Cleanup(func() { srv.Close(); cancel() }) // The session has no pool of its own to close.
	return func(path string) (int, []byte) {
		resp, err := srv.Client().Get(srv.URL + "/stream/d/" + path)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, b
	}
}

func distinctPayload(n, seed int) []byte { return payload(n + seed)[seed:] }

// A disc whose feature playlist names a clip stored after the feature clip,
// which selection never reaches.
func playlistDisc(t *testing.T, version int, size func(int) int) ([]inputFile, map[string][]byte) {
	files := map[string][]byte{
		"BDMV/CLIPINF/00001.clpi":  distinctPayload(700, 1),
		"BDMV/CLIPINF/00002.clpi":  distinctPayload(650, 2),
		"BDMV/PLAYLIST/00000.mpls": distinctPayload(300, 3),
		"BDMV/PLAYLIST/00001.mpls": distinctPayload(200, 4),
		"BDMV/STREAM/00001.m2ts":   distinctPayload(60*2048, 5),
		"BDMV/STREAM/00002.m2ts":   distinctPayload(9*2048+100, 6),
		"BDMV/STREAM/00003.m2ts":   distinctPayload(900, 7),
	}
	var entries []rar4Entry
	for _, name := range []string{"BDMV/CLIPINF/00001.clpi", "BDMV/CLIPINF/00002.clpi", "BDMV/PLAYLIST/00000.mpls",
		"BDMV/PLAYLIST/00001.mpls", "BDMV/STREAM/00001.m2ts", "BDMV/STREAM/00002.m2ts", "BDMV/STREAM/00003.m2ts"} {
		entries = append(entries, rar4Entry{name, files[name], len(files[name]), 0})
	}
	in, _ := packedRARSet(t, version, size, entries...)
	return in, files
}

func TestDiscServesItsPlaylistsAndClips(t *testing.T) {
	for _, tc := range []struct {
		name    string
		version int
		size    func(int) int
	}{
		{"RAR4", 4, func(int) int { return 2048 }},
		{"RAR5", 5, func(int) int { return 2048 }},
		// A short volume inside the clip after the feature: the walk maps it
		// volume by volume instead.
		{"RAR5 irregular", 5, func(i int) int {
			if i == 64 {
				return 2048 - 7
			}
			return 2048
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			in, files := playlistDisc(t, tc.version, tc.size)
			nzb, store, _ := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), nzb, Selection{})
			if err != nil || c.Name != "BDMV/STREAM/00001.m2ts" {
				t.Fatalf("selected %v, %v", c, err)
			}
			if c.disc == nil || c.disc.get("BDMV/STREAM/00002.m2ts") != nil {
				t.Fatal("selection should not have reached the clips after the feature")
			}
			get := discServer(t, c, store)
			status, listing := get("BDMV/PLAYLIST/")
			if status != 200 || !strings.Contains(string(listing), `href="00000.mpls"`) || !strings.Contains(string(listing), `href="00001.mpls"`) {
				t.Fatalf("listing %d: %s", status, listing)
			}
			for name, want := range files {
				if name == "BDMV/STREAM/00001.m2ts" {
					continue
				}
				if status, got := get(name); status != 200 || !bytes.Equal(got, want) {
					t.Fatalf("%s: HTTP %d, %d bytes, want %d", name, status, len(got), len(want))
				}
			}
			if status, _ := get("BDMV/STREAM/00009.m2ts"); status != 404 {
				t.Fatalf("missing clip: HTTP %d", status)
			}
			if status, got := get("BDMV/STREAM/00001.m2ts"); status != 200 || !bytes.Equal(got, files["BDMV/STREAM/00001.m2ts"]) {
				t.Fatalf("feature: HTTP %d", status)
			}
		})
	}
}

func TestDiscImageServesItsPlaylistsAndClips(t *testing.T) {
	feature, extra, mpls := payload(70000)[1000:], payload(9000)[77:], payload(500)[9:]
	tree := blurayTree(feature, extra)
	tree.children[0].children = append(tree.children[0].children,
		&udfNode{name: "PLAYLIST", children: []*udfNode{{name: "00800.mpls", data: mpls}}})
	image := buildUDF(tree, false, false, false)
	nzb, store, _ := setup(t, []inputFile{{"Disc.iso", image, nil}}, 0, 4, 4)
	c, err := Select(context.Background(), nzb, Selection{})
	if err != nil || c.disc == nil {
		t.Fatalf("selected %v, %v", c, err)
	}
	get := discServer(t, c, store)
	if status, listing := get("BDMV/PLAYLIST"); status != 200 || !strings.Contains(string(listing), `href="00800.mpls"`) {
		t.Fatalf("listing %d: %s", status, listing)
	}
	for name, want := range map[string][]byte{"BDMV/PLAYLIST/00800.mpls": mpls, "BDMV/STREAM/00001.m2ts": extra, "BDMV/CLIPINF/00800.clpi": payload(300)} {
		if status, got := get(name); status != 200 || !bytes.Equal(got, want) {
			t.Fatalf("%s: HTTP %d, %d bytes", name, status, len(got))
		}
	}
	if status, _ := get(fmt.Sprintf("BDMV/STREAM/%05d.m2ts", 7)); status != 404 {
		t.Fatal("missing clip served")
	}
}

// waitRead waits for the disc's background reads to bring a file into memory.
func waitRead(t *testing.T, d *discFiles, rel string) {
	t.Helper()
	for deadline := time.Now().Add(10 * time.Second); !d.isRead(rel); time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatalf("%s was not read in the background", rel)
		}
	}
}

func TestDiscReadsItsPlaylistsAsTheSessionOpens(t *testing.T) {
	t.Run("RAR", func(t *testing.T) {
		in, files := playlistDisc(t, 5, func(int) int { return 2048 })
		nzb, _, _ := setup(t, in, 0, 4, 4)
		c, err := Select(context.Background(), nzb, Selection{})
		if err != nil || c.disc == nil {
			t.Fatalf("selected %v, %v", c, err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		c.disc.start(ctx)
		// Both playlists, the one selection passed and the one the walk finds.
		for _, rel := range []string{"BDMV/PLAYLIST/00000.mpls", "BDMV/PLAYLIST/00001.mpls"} {
			waitRead(t, c.disc, rel)
			if _, b, err := c.disc.lookup(ctx, ctx, rel); err != nil || !bytes.Equal(b, files[rel]) {
				t.Fatalf("%s: %d bytes, %v", rel, len(b), err)
			}
		}
		if c.disc.isRead("BDMV/CLIPINF/00002.clpi") {
			t.Fatal("clip info is read when asked for, not ahead")
		}
	})
	t.Run("image", func(t *testing.T) {
		feature, extra, mpls := payload(70000)[1000:], payload(9000)[77:], payload(500)[9:]
		tree := blurayTree(feature, extra)
		tree.children[0].children = append(tree.children[0].children,
			&udfNode{name: "PLAYLIST", children: []*udfNode{{name: "00800.mpls", data: mpls}}})
		nzb, _, _ := setup(t, []inputFile{{"Disc.iso", buildUDF(tree, false, false, false), nil}}, 0, 4, 4)
		c, err := Select(context.Background(), nzb, Selection{})
		if err != nil || c.disc == nil {
			t.Fatalf("selected %v, %v", c, err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		c.disc.start(ctx)
		waitRead(t, c.disc, "BDMV/PLAYLIST/00800.mpls")
		if _, b, _ := c.disc.lookup(ctx, ctx, "BDMV/PLAYLIST/00800.mpls"); !bytes.Equal(b, mpls) {
			t.Fatalf("read %d bytes", len(b))
		}
	})
}
