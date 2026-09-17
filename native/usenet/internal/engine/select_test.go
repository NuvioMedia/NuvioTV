package engine

import (
	"bytes"
	"context"
	"fmt"
	"io"
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

func TestEpisodeSelectionSingleVideoFallback(t *testing.T) {
	for _, format := range []string{"direct", "rar4", "rar5", "anonymous-rar5"} {
		for _, tc := range []struct {
			name  string
			files []string
			want  string
		}{
			{"obfuscated", []string{"8d2f1b.mkv"}, "8d2f1b.mkv"},
			{"numeric", []string{"02.mkv"}, "02.mkv"},
			{"scene-number", []string{"Show.102.mkv"}, "Show.102.mkv"},
			{"sample-and-subtitle", []string{"Show.S01E02.sample.mkv", "Show.S01E02.srt", "8d2f1b.mkv"}, "8d2f1b.mkv"},
			{"no-video", []string{"Show.S01E02.sample.mkv", "Show.S01E02.srt"}, ""},
			{"wrong-episode", []string{"Show.S01E03.mkv"}, ""},
			{"wrong-season", []string{"Show.S02E02.mkv"}, ""},
			{"wrong-x-marker", []string{"Show.1x03.mkv"}, ""},
			{"episode-prefix", []string{"Show.S01E020.mkv"}, ""},
			{"obfuscated-pack", []string{"8d2f1b.mkv", "a8f9c2.mkv"}, ""},
			{"mixed-pack", []string{"8d2f1b.mkv", "Show.S01E03.mkv"}, ""},
			{"strict-wins", []string{"8d2f1b.mkv", "Show.S01E02.mkv"}, "Show.S01E02.mkv"},
			{"strict-x-wins", []string{"8d2f1b.mkv", "Show.1x02.mkv"}, "Show.1x02.mkv"},
		} {
			t.Run(format+"/"+tc.name, func(t *testing.T) {
				var in []inputFile
				var want []byte
				for i, name := range tc.files {
					data := bytes.Repeat([]byte{byte(i + 1)}, 1024)
					if name == tc.want {
						want = data
					}
					switch format {
					case "rar4":
						in = append(in, inputFile{fmt.Sprintf("release%d.rar", i), rar4Volume(name, data, len(data), 0, false), nil})
					case "rar5", "anonymous-rar5":
						archive := fmt.Sprintf("release%d.rar", i)
						if format == "anonymous-rar5" {
							// Anonymous volume ordering represents one archive set.
							// Use one volume with all file records below.
							archive = "a8f9c2"
						}
						volume := rar5Volume(name, data, len(data), 0, false)
						if format == "anonymous-rar5" && len(in) > 0 {
							// Remove END (8 bytes) and the next signature/main (16).
							in[0].data = append(in[0].data[:len(in[0].data)-8], volume[16:]...)
						} else {
							in = append(in, inputFile{archive, volume, nil})
						}
					default:
						in = append(in, inputFile{name, data, nil})
					}
				}
				files, _, _ := setup(t, in, 0, 2, 2)
				c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
				if tc.want == "" {
					if err != errNoMatchingVideo {
						t.Fatalf("Select = %v, %v; want selector rejection", c, err)
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				if c.Name != tc.want {
					t.Fatalf("selected %q, want %q", c.Name, tc.want)
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				got, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(got, want) {
					t.Fatalf("selected payload = %d bytes, %v", len(got), err)
				}
			})
		}
	}
}

func TestEpisodeSelectionRecoveredDirectNames(t *testing.T) {
	for _, tc := range []struct {
		name, subject, recovered string
		want                     bool
	}{
		{"named-strict", "8d2f1b.mkv", "Show.S01E02.mkv", true},
		{"anonymous-strict", "8d2f1b", "Show.S01E02.mkv", true},
		{"anonymous-fallback", "8d2f1b", "a8f9c2.mkv", true},
		{"recovered-conflict", "8d2f1b.mkv", "Show.S01E03.mkv", false},
		{"subject-conflict", "Show.S01E03.mkv", "8d2f1b.mkv", false},
		{"recovered-sample", "8d2f1b.mkv", "Show.S01E02.sample.mkv", false},
		{"subject-sample", "sample.mkv", "8d2f1b.mkv", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files, _, _ := setup(t, []inputFile{{tc.recovered, payload(1024), nil}}, 0, 1, 1)
			files[0].Name = tc.subject // Keep the real name only in the yEnc header.
			c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
			if !tc.want {
				if err != errNoMatchingVideo {
					t.Fatalf("Select = %v, %v; want rejection", c, err)
				}
			} else if err != nil || c.Name != tc.recovered || c.Size != 1024 {
				t.Fatalf("Select = %v, %v; want recovered video", c, err)
			}
		})
	}
}

func TestEpisodeFallbackPreservesExplicitSelectors(t *testing.T) {
	index := 1
	for _, s := range []Selection{
		{Season: 1, Episode: 2, FileIdx: &index},
		{Season: 1, Episode: 2, FileMustInclude: `S01E02`},
	} {
		files, _, _ := setup(t, []inputFile{{"8d2f1b.mkv", payload(1024), nil}}, 0, 1, 1)
		if _, err := Select(context.Background(), files, s); err != errNoMatchingVideo {
			t.Fatalf("selector %+v was relaxed: %v", s, err)
		}
	}
	index = 0
	files, _, _ := setup(t, []inputFile{{"Show.S01E03.mkv", payload(1024), nil}}, 0, 1, 1)
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2, FileIdx: &index}); err != nil {
		t.Fatalf("explicit index lost precedence: %v", err)
	}
}

func TestEpisodeFallbackMultipartRAR(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprint(version), func(t *testing.T) {
			want := payload(600000)
			var first, second []byte
			if version == 4 {
				first = rar4Volume("8d2f1b.mkv", want[:300000], len(want), 2, false)
				second = rar4Volume("8d2f1b.mkv", want[300000:], len(want), 1, false)
			} else {
				first = rar5Volume("8d2f1b.mkv", want[:300000], len(want), 16, false)
				second = rar5Volume("8d2f1b.mkv", want[300000:], len(want), 8, false)
			}
			files, _, server := setup(t, []inputFile{{"pack.part01.rar", first, nil}, {"pack.part02.rar", second, nil}}, 0, 2, 2)
			c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
			if err != nil {
				t.Fatal(err)
			}
			if calls := server.Counters().Bodies; calls > 4 {
				t.Fatalf("inventory downloaded payload instead of skipping it: %d articles", calls)
			}
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			got, err := io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want) {
				t.Fatalf("multipart fallback read = %d bytes, %v", len(got), err)
			}
		})
	}
}

func TestEpisodeFallbackCountsMixedSources(t *testing.T) {
	for _, unknown := range []bool{false, true} {
		t.Run(fmt.Sprintf("anonymous-direct=%t", unknown), func(t *testing.T) {
			data := payload(1024)
			files, _, _ := setup(t, []inputFile{
				{"8d2f1b.mkv", data, nil},
				{"pack.rar", rar4Volume("a8f9c2.mkv", data, len(data), 0, false), nil},
			}, 0, 2, 2)
			if unknown {
				files[0].Name = "8d2f1b"
			}
			if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2}); err != errNoMatchingVideo {
				t.Fatalf("ambiguous mixed release accepted: %v", err)
			}
		})
	}
}

func TestEpisodeRecoveredNameWinsOverAmbiguousPack(t *testing.T) {
	files, _, _ := setup(t, []inputFile{
		{"8d2f1b.mkv", payload(1024), nil},
		{"Show.S01E02.mkv", payload(2048), nil},
	}, 0, 2, 2)
	files[1].Name = "a8f9c2.mkv"
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil || c.Name != "Show.S01E02.mkv" || c.Size != 2048 {
		t.Fatalf("recovered strict match = %v, %v", c, err)
	}
}

func TestEpisodeFallbackRejectsMultipleEntriesInRAR4(t *testing.T) {
	data := payload(1024)
	first := rar4Volume("8d2f1b.mkv", data, len(data), 0, false)
	second := rar4Volume("a8f9c2.mkv", data, len(data), 0, false)
	pack := append(first[:len(first)-7], second[20:]...)
	files, _, _ := setup(t, []inputFile{{"pack.rar", pack, nil}}, 0, 1, 1)
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2}); err != errNoMatchingVideo {
		t.Fatalf("ambiguous archive accepted: %v", err)
	}
}

func TestEpisodeFallbackRequiresCompleteInventory(t *testing.T) {
	data := payload(1024)
	for _, tc := range []struct {
		name string
		in   []inputFile
	}{
		{"missing-continuation", []inputFile{{"pack.rar", rar4Volume("8d2f1b.mkv", data, 2048, 2, false), nil}}},
		{"unreadable-other-archive", []inputFile{{"8d2f1b.mkv", data, nil}, {"broken.rar", data, nil}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files, _, _ := setup(t, tc.in, 0, 1, 1)
			if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2}); err == nil {
				t.Fatal("fallback accepted an incomplete inventory")
			}
		})
	}
}

func TestEpisodeFallbackRecognizesAnonymousVideoSignature(t *testing.T) {
	data := append([]byte{0x1a, 0x45, 0xdf, 0xa3}, payload(1024)...)
	files, _, _ := setup(t, []inputFile{{"8d2f1b", data, nil}}, 0, 1, 1)
	c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 2})
	if err != nil || c.Name != "8d2f1b.mkv" || c.Size != int64(len(data)) {
		t.Fatalf("anonymous MKV fallback = %v, %v", c, err)
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
