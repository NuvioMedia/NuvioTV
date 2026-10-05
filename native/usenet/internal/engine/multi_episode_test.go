package engine

import (
	"context"
	"testing"
)

func TestMultiEpisodeNamesMatchEachEpisode(t *testing.T) {
	for _, tc := range []struct {
		name    string
		episode int
		want    bool
	}{
		{"Show.S01E01E02.mkv", 1, true},
		{"Show.S01E01E02.mkv", 2, true},
		{"Show.S01E01E02.mkv", 3, false},
		{"Show.S01E01-E02.mkv", 2, true},
		{"Show.S01E01.E02.mkv", 2, true},
		{"Show.S01E01 - E02.mkv", 2, true},
		{"Show.S01E01-02.mkv", 2, true},
		{"Show.S01E01-E03.mkv", 2, true},
		{"Show.S01E01-E03.mkv", 4, false},
		{"Show.S01E01E02E03.mkv", 3, true},
		{"Show.1x01-02.mkv", 2, true},
		{"Show.S01E01.720p.mkv", 2, false},
		{"Show.S01E01-720p.mkv", 5, false},
		{"Show.S01E01-2160p.mkv", 2, false},
		{"Show.S01E01.x264.mkv", 2, false},
		{"Show.S01E01.2.mkv", 2, false},
		{"Show.S01E01E020.mkv", 2, false},
		{"Show.S01E01-E40.mkv", 20, false},
		{"Show.S02E01E02.mkv", 2, false},
		{"Show.S01E02.mkv", 2, true},
		{"Show.S01E020.mkv", 2, false},
		{"[Group] Show S01E05-E06v2 [1080p].mkv", 6, true},
		{"[Group] Show - S01E12-13 [BD 1080p].mkv", 13, true},
		{"[Group] Show S01E05 [1080p][HEVC x265 10bit].mkv", 6, false},
		{"Show.S01E01.E5F6A7B8.mkv", 5, false},
		{"Show.S01E01_E3D2C1A0.mkv", 3, false},
		{"Show S01E01 (13) [1080p].mkv", 13, false},
		{"Show.S01E01-E12.Batch/Show - 01.mkv", 5, false},
		{"Show.S01E01-E12.Batch\\Show - 01.mkv", 5, false},
		{"Batch.S01E01-E12/Show.S01E04E05.mkv", 5, true},
	} {
		match, err := (Selection{Season: 1, Episode: tc.episode, multiEpisode: true}).matcher()
		if err != nil {
			t.Fatal(err)
		}
		if got, _ := match(tc.name, 0); got != tc.want {
			t.Errorf("%s episode %d = %v, want %v", tc.name, tc.episode, got, tc.want)
		}
	}
}

func TestMultiEpisodeFileSelectedFromPack(t *testing.T) {
	files, _, _ := setup(t, []inputFile{
		{"Show.S01E01E02.mkv", payload(4096), nil},
		{"Show.S01E03.mkv", payload(4096), nil},
	}, 0, 2, 2)
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "Show.S01E01E02.mkv" {
		t.Fatalf("selected %q, want the double episode", c.Name)
	}
	if subs := standaloneSubtitles([]*File{{Name: "Show.S01E01E02.en.srt"}}, Selection{Season: 1, Episode: 2}); len(subs) != 1 {
		t.Fatal("double-episode subtitle not offered for its second episode")
	}
}

func TestExactEpisodeWinsOverMultiEpisodeFile(t *testing.T) {
	files, _, _ := setup(t, []inputFile{
		{"Show.S01E01E02.mkv", payload(4096), nil},
		{"Show.S01E02.mkv", payload(4096), nil},
	}, 0, 2, 2)
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "Show.S01E02.mkv" {
		t.Fatalf("selected %q, want the exact episode", c.Name)
	}
}
