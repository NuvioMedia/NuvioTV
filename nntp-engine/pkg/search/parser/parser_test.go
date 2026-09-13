package parser

import (
	"strings"
	"testing"

	"github.com/dreulavelle/jhin"
)

func TestParseReleaseTitleRetainsEpisodeCollections(t *testing.T) {
	parsed := ParseReleaseTitle("The.Walking.Dead.S01E05E06.1080p.WEB-DL")
	if parsed == nil {
		t.Fatal("expected parsed release")
	}
	if parsed.Season != 1 {
		t.Fatalf("expected first season 1, got %d", parsed.Season)
	}
	if parsed.Episode != 5 {
		t.Fatalf("expected first episode 5, got %d", parsed.Episode)
	}
	if !parsed.HasSeason(1) {
		t.Fatal("expected parsed release to include season 1")
	}
	if !parsed.HasEpisode(5) || !parsed.HasEpisode(6) {
		t.Fatalf("expected parsed release to include episodes 5 and 6, got %v", parsed.Episodes)
	}
}

func TestParsedReleaseEpisodeMatchRank(t *testing.T) {
	tests := []struct {
		name   string
		parsed *ParsedRelease
		want   int
	}{
		{name: "exact episode", parsed: &ParsedRelease{Season: 1, Episode: 5, Seasons: []int{1}, Episodes: []int{5}}, want: 4},
		{name: "multi episode", parsed: &ParsedRelease{Season: 1, Episode: 5, Seasons: []int{1}, Episodes: []int{5, 6}}, want: 3},
		{name: "season pack", parsed: &ParsedRelease{Season: 1, Seasons: []int{1}, Result: &jhin.Result{Complete: true}}, want: 2},
		{name: "show pack", parsed: &ParsedRelease{Result: &jhin.Result{Complete: true}}, want: 1},
		{name: "wrong season", parsed: &ParsedRelease{Season: 2, Seasons: []int{2}, Result: &jhin.Result{Complete: true}}, want: 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.parsed.EpisodeMatchRank(1, 5); got != tt.want {
				t.Fatalf("EpisodeMatchRank() = %d, want %d", got, tt.want)
			}
		})
	}

	wrongSeasonS02E01 := &ParsedRelease{Season: 2, Episode: 1, Seasons: []int{2}, Episodes: []int{1}}
	if got := wrongSeasonS02E01.EpisodeMatchRank(0, 1); got != 0 {
		t.Fatalf("expected S02E01 to be rejected for season 0 request, got rank %d", got)
	}
}

func TestParseReleaseTitleRecognizesDashedSeasonEpisodePattern(t *testing.T) {
	parsed := ParseReleaseTitle("[SubsPlease] Tensei Shitara Slime Datta Ken S4 - 03 (720p) [370B1C65]")
	if parsed == nil {
		t.Fatal("expected parsed release")
	}
	if parsed.Season != 4 {
		t.Fatalf("expected season 4, got %d", parsed.Season)
	}
	if parsed.Episode != 3 {
		t.Fatalf("expected episode 3, got %d", parsed.Episode)
	}
	if !parsed.HasSeason(4) {
		t.Fatal("expected parsed release to include season 4")
	}
	if !parsed.HasEpisode(3) {
		t.Fatalf("expected parsed release to include episode 3, got %v", parsed.Episodes)
	}
	if strings.Contains(parsed.Title, "S4 - 03") {
		t.Fatalf("expected parsed title to drop dashed season/episode suffix, got %q", parsed.Title)
	}
}

func TestDashedSeasonEpisodePatternDoesNotFalseMatchInsideLongerToken(t *testing.T) {
	if matches := dashedSeasonEpisodePattern.FindStringSubmatch("Example.Show.S1 - 24bit.1080p.WEB-DL"); len(matches) != 0 {
		t.Fatalf("expected no regex match inside longer token, got %v", matches)
	}
}

func TestParseReleaseTitleExpandsLanguageAliases(t *testing.T) {
	tests := []struct {
		name    string
		title   string
		want    []string
		wantAny []string
	}{
		{
			name:    "nordic expands to da fi no sv",
			title:   "Some.Movie.2024.1080p.BluRay.NORDIC.x264-RG",
			wantAny: []string{"da", "fi", "no", "sv"},
		},
		{
			name:    "scandinavian expands to da no sv",
			title:   "Some.Movie.2024.1080p.BluRay.SCANDINAVIAN.x264-RG",
			wantAny: []string{"da", "no", "sv"},
		},
		{
			name:    "baltic expands to et lv lt",
			title:   "Some.Movie.2024.1080p.BluRay.BALTIC.x264-RG",
			wantAny: []string{"et", "lv", "lt"},
		},
		{
			name:    "multi expands to fr",
			title:   "Some.Movie.2024.1080p.BluRay.MULTi.x264-RG",
			wantAny: []string{"fr"},
		},
		{
			name:    "truefrench expands to fr",
			title:   "Some.Movie.2024.1080p.BluRay.TRUEFRENCH.x264-RG",
			wantAny: []string{"fr"},
		},
		{
			name:  "no alias leaves languages from ptt only",
			title: "Some.Movie.2024.1080p.BluRay.FRENCH.x264-RG",
			want:  []string{"fr"},
		},
		{
			name:  "alias with explicit language merges both",
			title: "Some.Movie.2024.1080p.BluRay.NORDIC.FRENCH.x264-RG",
			want:  []string{"fr", "da", "fi", "no", "sv"},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			parsed := ParseReleaseTitle(tc.title)
			if parsed == nil {
				t.Fatal("expected parsed release")
			}
			if tc.want != nil {
				if !sameSet(parsed.Languages, tc.want) {
					t.Fatalf("expected languages %v, got %v", tc.want, parsed.Languages)
				}
			}
			if tc.wantAny != nil {
				for _, code := range tc.wantAny {
					if !containsString(parsed.Languages, code) {
						t.Fatalf("expected languages to include %q, got %v", code, parsed.Languages)
					}
				}
			}
		})
	}
}

func containsString(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

func sameSet(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	seen := make(map[string]bool, len(a))
	for _, v := range a {
		seen[v] = true
	}
	for _, v := range b {
		if !seen[v] {
			return false
		}
	}
	return true
}

func TestResolutionGroupHandlesFullDimensions(t *testing.T) {
	tests := []struct {
		resolution string
		want       string
	}{
		{"2160p", "4k"},
		{"1080p", "1080p"},
		{"720p", "720p"},
		{"480p", "sd"},
		// The parser reports both dimensions when a title spells them out;
		// grouping must key off the height, not the width.
		{"720x480p", "sd"},
		{"1916x1080p", "1080p"},
		{"3840x2160p", "4k"},
	}

	for _, tt := range tests {
		t.Run(tt.resolution, func(t *testing.T) {
			p := &ParsedRelease{Result: &jhin.Result{Resolution: tt.resolution}}
			if got := p.ResolutionGroup(); got != tt.want {
				t.Fatalf("ResolutionGroup(%q) = %q, want %q", tt.resolution, got, tt.want)
			}
		})
	}
}
