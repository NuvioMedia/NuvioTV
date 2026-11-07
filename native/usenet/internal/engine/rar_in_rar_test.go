package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"testing"
)

// wrapRARSet stores a RAR set's volumes in an outer RAR set, as uploaders
// re-wrap a scene release: scene names in shuffled order, with the release's
// other files among them, in volumes of outer bytes each.
func wrapRARSet(t *testing.T, version int, inner []inputFile, outer func(int) int, beside ...rar4Entry) []inputFile {
	t.Helper()
	var entries []rar4Entry
	for i, v := range inner {
		name := "Movie.2026-GRP/movie.2026-grp.rar"
		if i > 0 {
			name = fmt.Sprintf("Movie.2026-GRP/movie.2026-grp.r%02d", i-1)
		}
		data := v.data
		if bytes.HasPrefix(data, []byte("Rar!\x1a\x07\x00")) {
			// rar flags every RAR4 volume's main header, the first one too.
			flags := uint16(0x0001)
			if i == 0 {
				flags |= 0x0100
			}
			data = withRAR4MainFlags(data, flags)
		}
		entries = append(entries, rar4Entry{name, data, len(data), 0})
	}
	rand.New(rand.NewSource(3)).Shuffle(len(entries), func(i, j int) { entries[i], entries[j] = entries[j], entries[i] })
	// The other files land between volumes, as WinRAR lists a folder.
	entries = append(entries[:2:2], append(beside, entries[2:]...)...)
	in, _ := packedRARSet(t, version, outer, entries...)
	return in
}

func releaseFiles() []rar4Entry {
	return []rar4Entry{
		{"Movie.2026-GRP/movie.2026-grp.sfv", payload(90), 90, 0},
		// Longer than a volume, under a longer name than the volumes': its
		// continuation headers are longer than the template's.
		{"Movie.2026-GRP/Sample/movie.2026-grp.sample.mkv", payload(30000)[7:], 30000 - 7, 0},
		{"Movie.2026-GRP/movie.2026-grp.nfo", payload(300), 300, 0},
	}
}

func TestRARInRARDiscImage(t *testing.T) {
	feature, extra := payload(70000)[1000:], payload(9000)[77:]
	image := buildUDF(blurayTree(feature, extra), false, false, false)
	inner, _ := packedRARSet(t, 5, func(int) int { return 64 << 10 }, rar4Entry{"MOVIE.iso", image, len(image), 0})
	in := wrapRARSet(t, 5, inner, func(int) int { return 12 << 10 }, releaseFiles()...)
	files, _, _ := setup(t, in, 0, 8, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "MOVIE.iso/BDMV/STREAM/00800.m2ts" || c.Size != int64(len(feature)) {
		t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
	}
	if iso := c.nested.vols[0]; iso.nested == nil || len(iso.nested.vols) != len(inner) {
		t.Fatalf("the image is not read from the %d inner volumes", len(inner))
	}
	if got := readAllContent(t, c); !bytes.Equal(got, feature) {
		t.Fatal("feature bytes differ")
	}
	if c.disc == nil || c.clipInfo == nil {
		t.Fatal("the image's clip lost its disc files")
	}
}

// RAR4 headers keep their size and need no volume numbers: every volume
// holds the same room. Reads in any order check each predicted part.
func TestRARInRARMovie(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprintf("RAR%d", version), func(t *testing.T) {
			movie := payload(400000)[3:]
			inner, _ := packedRARSet(t, version, func(int) int { return 30000 }, rar4Entry{"Movie.2026.1080p-GRP.mkv", movie, len(movie), 0})
			in := wrapRARSet(t, version, inner, func(int) int { return 7000 }, releaseFiles()...)
			files, _, server := setup(t, in, 0, 8, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != "Movie.2026.1080p-GRP.mkv" || c.Size != int64(len(movie)) || c.nested == nil {
				t.Fatalf("selected %q (%d bytes, nested=%t)", c.Name, c.Size, c.nested != nil)
			}
			// Walking the set reads every volume's header, each volume one
			// article here; listing it reads about one per entry.
			if bodies := server.Counters().Bodies; bodies >= int64(len(in)) {
				t.Fatalf("selection fetched %d articles from %d volumes", bodies, len(in))
			}
			listed := 0
			for _, v := range c.nested.vols {
				if v.predicted != nil {
					listed++
				}
			}
			if listed == 0 {
				t.Fatal("no inner volume was mapped by the listing's arithmetic")
			}
			rng := rand.New(rand.NewSource(11))
			r := c.Reader(context.Background(), 1)
			defer r.Close()
			for i := 0; i < 200; i++ {
				off := rng.Int63n(c.Size)
				buf := make([]byte, 1+rng.Intn(20000))
				if _, err := r.Seek(off, io.SeekStart); err != nil {
					t.Fatal(err)
				}
				n, err := io.ReadFull(r, buf)
				if err != nil && !(errors.Is(err, io.ErrUnexpectedEOF) && off+int64(n) == c.Size) {
					t.Fatalf("read at %d: %v", off, err)
				}
				if !bytes.Equal(buf[:n], movie[off:off+int64(n)]) {
					t.Fatalf("read at %d (+%d) differs", off, n)
				}
			}
			if got := readAllContent(t, c); !bytes.Equal(got, movie) {
				t.Fatal("sequential read differs")
			}
		})
	}
}

// A volume shorter than the template's breaks the arithmetic past it; the
// set is then walked header by header and still plays.
func TestRARInRARIrregularVolume(t *testing.T) {
	movie := payload(300000)[5:]
	inner, _ := packedRARSet(t, 5, func(int) int { return 30000 }, rar4Entry{"Movie.2026.1080p-GRP.mkv", movie, len(movie), 0})
	in := wrapRARSet(t, 5, inner, func(i int) int {
		if i == 23 {
			return 5000
		}
		return 7000
	}, releaseFiles()...)
	selectAndRead(t, damagedSetup(t, in), "Movie.2026.1080p-GRP.mkv", movie)
}

// Outer volumes posted under shuffled numbers have no arithmetic order: their
// main headers order them, and every header is read.
func TestRARInRARShuffledOuterVolumes(t *testing.T) {
	movie := payload(200000)[9:]
	inner, _ := packedRARSet(t, 5, func(int) int { return 30000 }, rar4Entry{"Movie.2026.1080p-GRP.mkv", movie, len(movie), 0})
	in := wrapRARSet(t, 5, inner, func(int) int { return 9000 }, releaseFiles()...)
	order := rand.New(rand.NewSource(5)).Perm(len(in))
	var files []inputFile
	for k, f := range in {
		files = append(files, inputFile{fmt.Sprintf("a01b92d0b72af2e9.%d", order[k]+1), f.data, nil})
	}
	selectAndRead(t, damagedSetup(t, files), "Movie.2026.1080p-GRP.mkv", movie)
}

// A subtitle pack stored as one archive is not a wrapped set: the walk goes
// on past it, and the largest video still plays. The feature fills less than
// half the set, so the walk reaches the pack and the smaller video after it.
func TestRARInRARSubtitlePackIsNotASet(t *testing.T) {
	movie, extra := payload(90000)[1:], payload(30000)[2:]
	subs := rar4Volume("Movie.2026.idx", payload(500), 500, 0, false)
	in, _ := packedRARSet(t, 5, func(int) int { return 8000 },
		rar4Entry{"Movie.2026.1080p-GRP.mkv", movie, len(movie), 0},
		rar4Entry{"Subs/movie.2026.subs.rar", subs, len(subs), 0},
		rar4Entry{"Movie.2026.Extras-GRP.mkv", extra, len(extra), 0},
		rar4Entry{"Movie.2026.Extras-GRP.bin", payload(100000), 100000, 0})
	selectAndRead(t, damagedSetup(t, in), "Movie.2026.1080p-GRP.mkv", movie)
}

// A wrapped set after a larger video the walk passed: the larger one plays.
func TestRARInRARKeepsALargerVideoBeforeIt(t *testing.T) {
	movie, extra := payload(90000)[1:], payload(40000)[3:]
	inner, _ := packedRARSet(t, 5, func(int) int { return 15000 }, rar4Entry{"Extras.mkv", extra, len(extra), 0})
	entries := []rar4Entry{{"Movie.2026.1080p-GRP.mkv", movie, len(movie), 0}}
	for i, v := range inner {
		name := "Extras/extras.rar"
		if i > 0 {
			name = fmt.Sprintf("Extras/extras.r%02d", i-1)
		}
		entries = append(entries, rar4Entry{name, v.data, len(v.data), 0})
	}
	entries = append(entries, rar4Entry{"Movie.2026.Extras-GRP.bin", payload(100000), 100000, 0})
	in, _ := packedRARSet(t, 5, func(int) int { return 8000 }, entries...)
	selectAndRead(t, damagedSetup(t, in), "Movie.2026.1080p-GRP.mkv", movie)
}

// A locked archive (rar -k) keeps each volume's layout, so its continuations
// still template a prediction; a solid one does not.
func TestRAR5LockedMainHeaderIsATemplate(t *testing.T) {
	for _, tc := range []struct {
		flags    uint64
		template bool
	}{{0x03, true}, {0x13, true}, {0x07, false}} {
		h := rar5TestHeader(binary.AppendUvarint(binary.AppendUvarint([]byte{1, 0}, tc.flags), 5))
		b, err := rar5Block(bytes.NewReader(h), int64(len(h)), 0)
		if err != nil || !b.main || !b.multiVolume || b.volume != 5 || b.template != tc.template {
			t.Fatalf("archive flags %#x: main=%t volume=%t template=%t, want template=%t (%v)", tc.flags, b.main, b.multiVolume, b.template, tc.template, err)
		}
	}
}
