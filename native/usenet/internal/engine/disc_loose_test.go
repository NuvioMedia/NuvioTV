package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// mplsFile builds a Blu-ray playlist playing [items], each a clip for so many minutes.
func mplsFile(items ...mplsItem) []byte {
	var body bytes.Buffer
	for _, it := range items {
		item := make([]byte, 28)
		copy(item, it.clip)
		copy(item[5:], "M2TS")
		binary.BigEndian.PutUint32(item[12:], it.in)
		binary.BigEndian.PutUint32(item[16:], it.out)
		binary.Write(&body, binary.BigEndian, uint16(len(item)))
		body.Write(item)
	}
	header := make([]byte, 40)
	copy(header, "MPLS0200")
	binary.BigEndian.PutUint32(header[8:], 40)
	playlist := make([]byte, 10)
	binary.BigEndian.PutUint32(playlist, uint32(6+body.Len()))
	binary.BigEndian.PutUint16(playlist[6:], uint16(len(items)))
	return append(append(header, playlist...), body.Bytes()...)
}

func play(clip string, minutes float64) mplsItem {
	return mplsItem{clip: clip, in: 900000, out: 900000 + uint32(minutes*60*45000)}
}

func clipData(tag byte) []byte {
	b := payload(4000)
	b[0], b[1] = 0x47, tag // A transport packet's sync byte, and which clip this is.
	return b
}

// Two discs posted without folders, as a season's are: backups, CLIPINF, other
// files, PLAYLIST, then STREAM, disc after disc.
func looseDiscFixture() []inputFile {
	clpi := func(tag string) []byte { return append([]byte("HDMV0200"), bytes.Repeat([]byte(tag), 8)...) }
	playlists1 := []inputFile{
		{name: "00000.mpls", data: mplsFile(play("00003", 2))},                     // menu
		{name: "00001.mpls", data: mplsFile(play("00002", 50))},                    // an episode
		{name: "00002.mpls", data: mplsFile(play("00001", 55))},                    // another
		{name: "00003.mpls", data: mplsFile(play("00002", 50), play("00001", 55))}, // play all: 00002 first
		{name: "00004.mpls", data: mplsFile(play("00001", 55))},                    // the same episode, other audio
	}
	var files []inputFile
	// Disc 1: its backup PLAYLIST must not be read as the disc's playlists.
	files = append(files, inputFile{name: "00001.clpi", data: clpi("b")}, inputFile{name: "00000.mpls", data: mplsFile(play("00003", 90))})
	files = append(files, inputFile{name: "index.bdmv", data: []byte("INDX0200")})
	files = append(files, inputFile{name: "00001.clpi", data: clpi("1")}, inputFile{name: "00002.clpi", data: clpi("2")})
	files = append(files, inputFile{name: "MovieObject.bdmv", data: []byte("MOBJ0200")})
	files = append(files, playlists1...)
	files = append(files, inputFile{name: "00001.m2ts", data: clipData(11)}, inputFile{name: "00002.m2ts", data: clipData(12)}, inputFile{name: "00003.m2ts", data: clipData(13)})
	// Disc 2: one episode and a long extra.
	files = append(files, inputFile{name: "index.bdmv", data: []byte("INDX0200")})
	files = append(files, inputFile{name: "00001.mpls", data: mplsFile(play("00001", 52))}, inputFile{name: "00002.mpls", data: mplsFile(play("00002", 12))})
	files = append(files, inputFile{name: "00001.m2ts", data: clipData(21)}, inputFile{name: "00002.m2ts", data: clipData(22)})
	return files
}

func TestEpisodesOfLooseBlurayDiscsComeFromTheirPlaylists(t *testing.T) {
	in := looseDiscFixture()
	files, _, _ := setup(t, in, 0, 4, 4)
	for episode, want := range map[int]struct {
		name string
		tag  byte
		info string
	}{
		1: {"Disc 1/BDMV/STREAM/00002.m2ts", 12, "Disc 1/BDMV/CLIPINF/00002.clpi"}, // play all order, not clip numbers
		2: {"Disc 1/BDMV/STREAM/00001.m2ts", 11, "Disc 1/BDMV/CLIPINF/00001.clpi"},
		3: {"Disc 2/BDMV/STREAM/00001.m2ts", 21, ""}, // the 12 minute extra is not an episode
	} {
		c, err := Select(context.Background(), files, Selection{Season: 1, Episode: episode})
		if err != nil {
			t.Fatalf("episode %d: %v", episode, err)
		}
		if c.Name != want.name || c.direct == nil || c.Size != 4000 {
			t.Fatalf("episode %d = %s (%d bytes)", episode, c.Name, c.Size)
		}
		head, err := sniff(context.Background(), c.direct)
		if err != nil || head[1] != want.tag {
			t.Fatalf("episode %d plays the wrong clip: %v %v", episode, head, err)
		}
		if want.info == "" {
			if c.clipInfo != nil {
				t.Fatalf("episode %d has a CLPI from another disc", episode)
			}
		} else if c.clipInfo == nil || c.clipInfo.Name != want.info || clipInfoPath(c.Name) != "BDMV/CLIPINF/"+c.Name[len(c.Name)-10:len(c.Name)-5]+".clpi" {
			t.Fatalf("episode %d CLPI = %+v", episode, c.clipInfo)
		}
	}
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 4}); !errors.Is(err, errDiscEpisodeMissing) {
		t.Fatalf("episode 4: %v", err)
	}
}

// Discs whose articles were removed fail as such, at the first disc, so the
// release is remembered as unavailable rather than as missing an episode.
func TestLooseDiscsWithRemovedPlaylistsFailAsMissingArticles(t *testing.T) {
	in := looseDiscFixture()
	xml, articles := fixture(in)
	for i, f := range in {
		if strings.HasSuffix(f.name, ".mpls") {
			delete(articles, fmt.Sprintf("f%d-s0@test", i))
		}
	}
	server, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := nntppool.NewClient(context.Background(), []nntppool.Provider{{Host: server.Addr(), Connections: 4, Inflight: 4, StreamInflight: 4, SkipPing: true}}, nntppool.WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	store := NewStore(context.Background(), pool, 32<<20)
	t.Cleanup(func() { store.Close(); pool.Close(); server.Close() })
	files, err := ParseNZB(strings.NewReader(xml), store)
	if err != nil {
		t.Fatal(err)
	}
	_, err = Select(context.Background(), files, Selection{Season: 1, Episode: 1})
	if !errors.Is(err, nntppool.ErrArticleNotFound) || permanentStreamFailure(err) != "missing-article" {
		t.Fatalf("err = %v (kind %q)", err, permanentStreamFailure(err))
	}
	if strings.Contains(err.Error(), "disc 2") {
		t.Fatal("the second disc was read after the first failed")
	}
}

func TestOnlyFolderlessDiscSetsAreLooseDiscs(t *testing.T) {
	named := []*File{{Name: "Show.S01E01.mkv", Index: 0}, {Name: "Show.S01E02.mkv", Index: 1}}
	clipsOnly := []*File{{Name: "00001.m2ts", Index: 0}, {Name: "00002.m2ts", Index: 1}}
	twice := []*File{{Name: "00001.mpls", Index: 0}, {Name: "00001.m2ts", Index: 1}, {Name: "00001.m2ts", Index: 2}}
	for _, files := range [][]*File{named, clipsOnly, twice} {
		if looseDiscs(files) != nil {
			t.Fatalf("%s taken for a disc set", files[0].Name)
		}
	}
	// Posting order, when every subject gives one, decides the runs.
	ordered := []*File{{Name: "00001.m2ts", Index: 0, order: 2}, {Name: "00001.mpls", Index: 1, order: 1}}
	if discs := looseDiscs(ordered); len(discs) != 1 || len(discs[0].playlists) != 1 {
		t.Fatal("subject order not followed")
	}
}

func TestBlurayPlaylistParsing(t *testing.T) {
	items, err := parseMPLS(mplsFile(play("00045", 55), play("00046", 3)))
	if err != nil || len(items) != 2 || items[0].clip != "00045" || items[0].seconds() != 55*60 || items[1].clip != "00046" {
		t.Fatalf("items = %+v, %v", items, err)
	}
	for _, bad := range [][]byte{nil, []byte("MPLS0200"), append([]byte("XXXX0200"), make([]byte, 40)...), mplsFile(play("00045", 55))[:60]} {
		if _, err := parseMPLS(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}
