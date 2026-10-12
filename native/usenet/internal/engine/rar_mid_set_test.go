package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"testing"
	"time"
)

// A Blu-ray release stores the disc's clips back to back in one RAR set, so
// the feature starts and ends inside it, after and before other clips.

func rar5FileBody(name string, packed, total int, flags uint64) []byte {
	var h []byte
	for _, v := range []uint64{2, flags | 2, uint64(packed), 0, uint64(total), 0, 0, 1, uint64(len(name))} {
		h = binary.AppendUvarint(h, v)
	}
	return append(h, name...)
}

func rar5Main(index int) []byte {
	if index == 0 {
		return []byte{1, 0, 1}
	}
	return binary.AppendUvarint([]byte{1, 0, 3}, uint64(index))
}

// rar5MultiVolume writes one RAR5 volume holding several file blocks; entry
// flags are rar4Entry's: 1 continues from the previous volume, 2 in the next.
func rar5MultiVolume(index int, entries ...rar4Entry) []byte {
	out := []byte("Rar!\x1a\x07\x01\x00")
	out = append(out, rar5TestHeader(rar5Main(index))...)
	for _, e := range entries {
		flags := uint64(0)
		if e.flags&1 != 0 {
			flags |= 8
		}
		if e.flags&2 != 0 {
			flags |= 16
		}
		out = append(out, rar5TestHeader(rar5FileBody(e.name, len(e.data), e.total, flags))...)
		out = append(out, e.data...)
	}
	return append(out, rar5TestHeader([]byte{5, 0, 0})...)
}

type rarSetPart struct {
	name                string
	volume, offset, len int
}

// packedRARSet stores files back to back as rar -v does: each volume is filled
// to size(index) bytes and a file continues in the next one. The last volume
// holds what is left.
func packedRARSet(t *testing.T, version int, size func(int) int, files ...rar4Entry) ([]inputFile, []rarSetPart) {
	t.Helper()
	header := func(name string, packed, total int) int {
		if version == 4 {
			return 32 + len(name)
		}
		return len(rar5TestHeader(rar5FileBody(name, packed, total, 0)))
	}
	room := func(index int) int {
		if version == 4 {
			return size(index) - 7 - 13 - 7
		}
		return size(index) - 8 - len(rar5TestHeader(rar5Main(index))) - len(rar5TestHeader([]byte{5, 0, 0}))
	}
	var in []inputFile
	var parts []rarSetPart
	var entries []rar4Entry
	left := room(0)
	flush := func() {
		index := len(in)
		v := rar4MultiVolume(entries...)
		if version == 5 {
			v = rar5MultiVolume(index, entries...)
		}
		in = append(in, inputFile{fmt.Sprintf("disc.part%03d.rar", index+1), v, nil})
		entries, left = nil, room(index+1)
	}
	for _, f := range files {
		for off := 0; ; {
			if left == 0 {
				flush()
			}
			rest, flags := len(f.data)-off, uint16(0)
			if off > 0 {
				flags = 1
			}
			if n := header(f.name, rest, f.total) + rest; n <= left {
				entries = append(entries, rar4Entry{f.name, f.data[off:], f.total, flags})
				parts = append(parts, rarSetPart{f.name, len(in), off, rest})
				left -= n
				break
			}
			n := left - header(f.name, left, f.total)
			for n > 0 && header(f.name, n, f.total)+n > left {
				n--
			}
			if n <= 0 {
				t.Fatalf("no room for %s in volume %d", f.name, len(in)+1)
			}
			entries = append(entries, rar4Entry{f.name, f.data[off : off+n], f.total, flags | 2})
			parts = append(parts, rarSetPart{f.name, len(in), off, n})
			off += n
			flush()
		}
	}
	flush()
	for i, v := range in[:len(in)-1] {
		if len(v.data) != size(i) {
			t.Fatalf("volume %d holds %d bytes, want %d", i+1, len(v.data), size(i))
		}
	}
	return in, parts
}

const discClip = "BDMV/STREAM/00065.m2ts"

// discRARSet places the feature after one clip and before another. The feature
// spans part129, where RAR5 volume numbers take two bytes.
func discRARSet(t *testing.T, version int, size func(int) int) ([]inputFile, []byte, []rarSetPart) {
	return discRARSetLead(t, version, size, 5)
}

// discRARSetLead puts a clip of about lead volumes before the feature.
func discRARSetLead(t *testing.T, version int, size func(int) int, lead int) ([]inputFile, []byte, []rarSetPart) {
	distinct := func(n, seed int) []byte { return payload(n + seed)[seed:] }
	movie := distinct(150*2048, 0)
	in, parts := packedRARSet(t, version, size,
		rar4Entry{"BDMV/STREAM/00001.m2ts", distinct(lead*2048+300, 7), lead*2048 + 300, 0},
		rar4Entry{discClip, movie, len(movie), 0},
		rar4Entry{"BDMV/STREAM/00002.m2ts", distinct(4*2048+77, 13), 4*2048 + 77, 0})
	var clip []rarSetPart
	for _, p := range parts {
		if p.name == discClip {
			clip = append(clip, p)
		}
	}
	if clip[0].volume == 0 || clip[len(clip)-1].volume == len(in)-1 || clip[len(clip)-1].volume < 129 {
		t.Fatalf("feature spans volumes %d-%d of %d", clip[0].volume, clip[len(clip)-1].volume, len(in))
	}
	return in, movie, clip
}

func TestRARPredictionForAClipInsideTheSet(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprintf("RAR%d", version), func(t *testing.T) {
			in, want, clip := discRARSet(t, version, func(int) int { return 2048 })
			files, _, server := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != discClip || c.Size != int64(len(want)) {
				t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
			}
			selected := server.Counters().Bodies
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			if _, err := r.Seek(-17, io.SeekEnd); err != nil {
				t.Fatal(err)
			}
			got, err := io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want[len(want)-17:]) {
				t.Fatalf("EOF seek bytes differ: %v", err)
			}
			if fetched := server.Counters().Bodies - selected; fetched > 2 {
				t.Fatalf("EOF seek fetched %d articles for a clip over %d volumes, want 2", fetched, len(clip))
			}
			if c.predicted == nil || c.predicted.base != clip[0].volume || len(c.parts) != len(clip) {
				t.Fatalf("continuation extents were not predicted from volume %d", clip[0].volume)
			}
			// Every part, backwards: each check reads its own volume's header.
			buf := make([]byte, 18)
			for off := len(want) - 9; off >= 0; off -= 1024 {
				r.Seek(int64(off), io.SeekStart)
				n := min(len(buf), len(want)-off)
				if _, err := io.ReadFull(r, buf[:n]); err != nil || !bytes.Equal(buf[:n], want[off:off+n]) {
					t.Fatalf("offset %d differs: %v", off, err)
				}
			}
			if c.predicted == nil {
				t.Fatal("a correct prediction was rejected")
			}
			for i, ok := range c.predicted.verified {
				if !ok {
					t.Fatalf("part %d was never checked", i)
				}
			}
		})
	}
}

func TestRARPredictionInsideTheSetIsChecked(t *testing.T) {
	for _, version := range []int{4, 5} {
		for _, irregular := range []string{"middle volume", "final remainder"} {
			t.Run(fmt.Sprintf("RAR%d/%s", version, irregular), func(t *testing.T) {
				// Shift 7 bytes between two of the feature's volumes. A middle pair
				// keeps the end where predicted, so only the demand check sees it;
				// a short volume alone moves the end and fails before publishing.
				_, _, clip := discRARSet(t, version, func(int) int { return 2048 })
				k := clip[20].volume
				size := func(i int) int {
					switch {
					case i == k:
						return 2048 - 7
					case i == k+1 && irregular == "middle volume":
						return 2048 + 7
					}
					return 2048
				}
				in, want, clip := discRARSet(t, version, size)
				files, _, _ := setup(t, in, 0, 4, 4)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				r.Seek(-17, io.SeekEnd)
				if got, err := io.ReadAll(r); err != nil || !bytes.Equal(got, want[len(want)-17:]) {
					t.Fatalf("EOF bytes differ: %v", err)
				}
				if published := c.predicted != nil; published != (irregular == "middle volume") {
					t.Fatalf("prediction published = %t", published)
				}
				// Then read the changed volume, so the check must reject it.
				from := clip[20].offset
				r.Seek(int64(from), io.SeekStart)
				got, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(got, want[from:]) {
					t.Fatalf("fallback bytes differ: %v", err)
				}
				if c.predicted != nil {
					t.Fatal("irregular layout retained prediction")
				}
			})
		}
	}
}

// ExoPlayer drops its first request once it has the clip's header, often while
// that reader's lookahead is fetching the end volume's header for a prediction.
func TestCancelledPredictionIsTriedAgain(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprintf("RAR%d", version), func(t *testing.T) {
			in, want, clip := discRARSet(t, version, func(int) int { return 2048 })
			files, _, server := setup(t, in, 200*time.Millisecond, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			before := server.Counters().Bodies
			ctx, cancel := context.WithCancel(context.Background())
			go func() {
				// The server answers each request after its RTT. Once part 2's header
				// is answered, the end volume's is in flight for the next 200 ms.
				for deadline := time.Now().Add(10 * time.Second); server.Counters().Bodies < before+1 && time.Now().Before(deadline); {
					time.Sleep(time.Millisecond)
				}
				time.Sleep(100 * time.Millisecond)
				cancel()
			}()
			if _, err := c.part(ctx, int64(clip[1].offset)); err != nil && !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
			if c.predicted != nil || len(c.parts) != 2 {
				t.Fatal("the cancel did not land while the end volume was fetched")
			}
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(-17, io.SeekEnd)
			got, err := io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want[len(want)-17:]) {
				t.Fatalf("EOF seek bytes differ: %v", err)
			}
			if c.predicted == nil || len(c.parts) != len(clip) {
				t.Fatal("the next reader walked the volumes instead of predicting")
			}
			if fetched := server.Counters().Bodies - before; fetched > 4 {
				t.Fatalf("fetched %d articles for a clip over %d volumes", fetched, len(clip))
			}
		})
	}
}

// Selection reaches the feature past the clips before it with two headers per
// clip, and can skip the feature itself to select a clip after it.
func TestSelectionSkipsClipsInsideTheSet(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprintf("RAR%d", version), func(t *testing.T) {
			in, want, _ := discRARSetLead(t, version, func(int) int { return 2048 }, 40)
			files, _, server := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil || c.Name != discClip {
				t.Fatalf("selected %v, %v", c, err)
			}
			if bodies := server.Counters().Bodies; bodies > 4 {
				t.Fatalf("selection read %d articles to pass a 40-volume clip", bodies)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatal("feature bytes differ")
			}

			files, _, server = setup(t, in, 0, 4, 4)
			c, err = Select(context.Background(), files, Selection{FileMustInclude: "00002"})
			if err != nil || c.Name != "BDMV/STREAM/00002.m2ts" {
				t.Fatalf("selected %v, %v", c, err)
			}
			if bodies := server.Counters().Bodies; bodies > 6 {
				t.Fatalf("selection read %d articles to pass two clips", bodies)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, payload(4*2048 + 77 + 13)[13:]) {
				t.Fatal("bytes of the clip after the feature differ")
			}
		})
	}
}

// A clip whose volumes do not follow the template is walked as before.
func TestSelectionSkipInsideTheSetIsChecked(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprintf("RAR%d", version), func(t *testing.T) {
			size := func(i int) int {
				if i == 20 {
					return 2048 - 7
				}
				return 2048
			}
			in, want, _ := discRARSetLead(t, version, size, 40)
			files, _, server := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil || c.Name != discClip {
				t.Fatalf("selected %v, %v", c, err)
			}
			if bodies := server.Counters().Bodies; bodies < 40 {
				t.Fatalf("selection read %d articles: an irregular clip was skipped", bodies)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatal("feature bytes differ")
			}
		})
	}
}
