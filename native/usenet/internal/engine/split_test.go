package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"testing"
)

var mkvMagic = []byte{0x1a, 0x45, 0xdf, 0xa3}

// rawSplit cuts data into parts of size, posted as name.001, name.002, ...
func rawSplit(name string, data []byte, size int) []inputFile {
	var in []inputFile
	for i := 0; i*size < len(data); i++ {
		in = append(in, inputFile{fmt.Sprintf("%s.%03d", name, i+1), data[i*size : min((i+1)*size, len(data))], nil})
	}
	return in
}

func TestRawSplitVideoJoinsParts(t *testing.T) {
	for _, tc := range []struct {
		name, posted, want string
		selection          Selection
	}{
		{"named", "Movie.2024.mkv", "Movie.2024.mkv", Selection{}},
		{"episode", "Show.S01E02.mkv", "Show.S01E02.mkv", Selection{Season: 1, Episode: 2}},
		{"obfuscated", "a8f3c2d1", "a8f3c2d1.mkv", Selection{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			want := payload(9*40000 + 1234)
			copy(want, mkvMagic)
			in := rawSplit(tc.posted, want, 40000)
			files, _, server := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, tc.selection)
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != tc.want || c.Size != int64(len(want)) {
				t.Fatalf("selected %q (%d bytes), want %q (%d)", c.Name, c.Size, tc.want, len(want))
			}
			// The first, second, second to last and last parts give the sizes.
			if got := server.Counters().Bodies; got > 4 {
				t.Fatalf("selection fetched %d articles", got)
			}
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(-17, io.SeekEnd)
			tail, err := io.ReadAll(r)
			if err != nil || !bytes.Equal(tail, want[len(want)-17:]) {
				t.Fatalf("EOF bytes differ: %v", err)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatalf("content mismatch (%d bytes)", len(got))
			}
		})
	}
}

// A middle part whose size the prediction got wrong fails its read rather
// than serving bytes from the wrong offset.
func TestRawSplitVideoRejectsUnequalMiddlePart(t *testing.T) {
	want := payload(9*40000 + 1234)
	copy(want, mkvMagic)
	in := rawSplit("movie.mkv", want, 40000)
	in[4].data, in[5].data = in[4].data[:39000], append(append([]byte(nil), in[4].data[39000:]...), in[5].data...)
	files, _, _ := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	r.Seek(4*40000+100, io.SeekStart)
	if _, err := io.ReadAll(r); !errors.Is(err, err7zVolumeLayout) {
		t.Fatalf("read across a mispredicted part: %v", err)
	}
}

// Numbered parts that hold neither an archive nor a video fail as before.
func TestRawSplitOfUnknownDataStillFails(t *testing.T) {
	in := rawSplit("data.bin", payload(3*40000), 40000)
	files, _, _ := setup(t, in, 0, 4, 4)
	if _, err := Select(context.Background(), files, Selection{}); err == nil {
		t.Fatal("selected a split of unknown data")
	}
}

// namedRARSet stores data as one entry called name across count volumes.
func namedRARSet(version int, name string, data []byte, count int) []inputFile {
	chunk := (len(data) + count - 1) / count
	in := make([]inputFile, count)
	for i := range in {
		part := data[i*chunk : min((i+1)*chunk, len(data))]
		var v []byte
		if version == 5 {
			v = indexedRAR5(name, part, len(data), i, i < count-1)
		} else {
			flags := uint16(0)
			if i > 0 {
				flags |= 1
			}
			if i < count-1 {
				flags |= 2
			}
			v = rar4Volume(name, part, len(data), flags, false)
		}
		in[i] = inputFile{fmt.Sprintf("pack.part%02d.rar", i+1), v, nil}
	}
	return in
}

func TestRAREntryWithoutExtensionIsTypedByContent(t *testing.T) {
	old := minSniffedEntry
	minSniffedEntry = 1 << 20
	t.Cleanup(func() { minSniffedEntry = old })
	for _, version := range []int{4, 5} {
		for _, recovery := range []bool{false, true} {
			t.Run(fmt.Sprintf("RAR%d/recovery=%t", version, recovery), func(t *testing.T) {
				want := payload(40*32768 + 99)
				copy(want, mkvMagic)
				in := namedRARSet(version, "Kx9f2Lq0Bw", want, 41)
				if recovery {
					for i := range in {
						in[i].data = withRecoveryRecord(version, in[i].data, i)
					}
				}
				files, _, _ := setup(t, in, 0, 4, 4)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				if c.Name != "Kx9f2Lq0Bw.mkv" || c.Size != int64(len(want)) {
					t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				r.Seek(-17, io.SeekEnd)
				tail, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(tail, want[len(want)-17:]) {
					t.Fatalf("EOF bytes differ: %v", err)
				}
				if got := readAllContent(t, c); !bytes.Equal(got, want) {
					t.Fatalf("content mismatch (%d bytes)", len(got))
				}
			})
		}
	}
}

func TestRAREntryWithoutExtensionNeedsVideoContent(t *testing.T) {
	old := minSniffedEntry
	minSniffedEntry = 1 << 20
	t.Cleanup(func() { minSniffedEntry = old })
	in := namedRARSet(5, "Kx9f2Lq0Bw", payload(40*32768), 41)
	files, _, _ := setup(t, in, 0, 4, 4)
	if _, err := Select(context.Background(), files, Selection{}); !errors.Is(err, errNoMatchingVideo) {
		t.Fatalf("selection of unknown data: %v", err)
	}
}

// A small entry is never read to find out what it is.
func TestRAREntryWithoutExtensionBelowThresholdIsNotRead(t *testing.T) {
	want := payload(40*32768 + 99)
	copy(want, mkvMagic)
	in := namedRARSet(5, "Kx9f2Lq0Bw", want, 41)
	files, _, _ := setup(t, in, 0, 4, 4)
	if _, err := Select(context.Background(), files, Selection{}); !errors.Is(err, errNoMatchingVideo) {
		t.Fatalf("selection below the size threshold: %v", err)
	}
}
