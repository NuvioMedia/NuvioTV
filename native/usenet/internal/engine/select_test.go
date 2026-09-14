package engine

import (
	"strings"
	"testing"
)

func TestAddonJavaScriptSelectors(t *testing.T) {
	for _, tc := range []struct {
		pattern, name string
		want          bool
	}{
		{`/^(?!.*sample)(?=.*S01E02).*\.(mkv|mp4)$/i`, "Show.S01E02.MKV", true},
		{`/^(?!.*sample)(?=.*S01E02).*\.(mkv|mp4)$/i`, "Show.S01E02.sample.mkv", false},
		{`/(?<=episode-)(\d+)\D+\1\.mkv$/i`, "episode-02-copy02.mkv", true},
		{`/\p{L}+\.mkv$/u`, "épisode.mkv", true},
		{`/show/iy`, "other-show.mkv", false},
	} {
		match, err := (Selection{FileMustInclude: tc.pattern}).matcher()
		if err != nil {
			t.Fatal(err)
		}
		got, err := match(tc.name, 0)
		if err != nil || got != tc.want {
			t.Fatalf("%s on %s = %v, %v", tc.pattern, tc.name, got, err)
		}
	}
}

func TestAddonSelectorRejectsExcessiveBacktracking(t *testing.T) {
	match, err := (Selection{FileMustInclude: `/^(a+)+$/`}).matcher()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := match(strings.Repeat("a", 12000)+"!", 0); err == nil {
		t.Fatal("unbounded selector accepted")
	}
}
