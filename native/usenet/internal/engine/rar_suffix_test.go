package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"io"
	"sync"
	"testing"
	"time"
)

// withRecoveryRecord announces a recovery record in a volume's main header, as
// releases made with one do. The continuation template then no longer applies.
func withRecoveryRecord(version int, v []byte, index int) []byte {
	if version == 4 {
		out := append([]byte(nil), v...)
		h := out[7:20]
		binary.LittleEndian.PutUint16(h[3:], binary.LittleEndian.Uint16(h[3:])|0x40)
		binary.LittleEndian.PutUint16(h, uint16(crc32.ChecksumIEEE(h[2:])))
		return out
	}
	mainSize, mainWidth := binary.Uvarint(v[12:])
	filePos := 12 + mainWidth + int(mainSize)
	// A locator record with quick open and recovery record offsets.
	extra := []byte{4, 1, 3, 9, 9}
	main := []byte{1, 1, byte(len(extra)), 1}
	if index > 0 {
		main[3] = 3
		main = binary.AppendUvarint(main, uint64(index))
	}
	main = append(main, extra...)
	out := append(append([]byte(nil), v[:8]...), rar5TestHeader(main)...)
	return append(out, v[filePos:]...)
}

func unpredictableRARSet(version, count, chunk, tail int) ([]inputFile, []byte, []int) {
	in, want, starts := regularRARSet(version, count, chunk, tail)
	for i := range in {
		in[i].data = withRecoveryRecord(version, in[i].data, i)
	}
	return in, want, starts
}

// rarSetVolume is volume index of a set holding one part of name.
func rarSetVolume(version int, name string, data []byte, total, index int, before, after bool) []byte {
	if version == 4 {
		flags := uint16(0)
		if before {
			flags |= 1
		}
		if after {
			flags |= 2
		}
		return withRecoveryRecord(4, rar4Volume(name, data, total, flags, false), index)
	}
	flags := uint64(0)
	if before {
		flags |= 8
	}
	if after {
		flags |= 16
	}
	return withRecoveryRecord(5, rar5Volume(name, data, total, flags, false), index)
}

func TestRARSuffixMapsEndOfUnpredictableSet(t *testing.T) {
	for _, version := range []int{4, 5} {
		for _, count := range []int{50, 132} {
			t.Run(fmt.Sprintf("RAR%d/%d", version, count), func(t *testing.T) {
				in, want, starts := unpredictableRARSet(version, count, 2048, 31)
				files, _, server := setup(t, in, 0, 4, 4)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				if got := server.Counters().Bodies; got != 1 {
					t.Fatalf("startup fetched %d articles, want 1", got)
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				if _, err := r.Seek(-17, io.SeekEnd); err != nil {
					t.Fatal(err)
				}
				got, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(got, want[len(want)-17:]) {
					t.Fatalf("EOF bytes differ: %v", err)
				}
				// The second volume, whose header rules out the prediction, and the last.
				if got := server.Counters().Bodies; got != 3 {
					t.Fatalf("EOF read fetched %d articles, want 3 regardless of volume count", got)
				}
				if c.predicted != nil || len(c.suffix) != 1 {
					t.Fatalf("prediction=%v suffix=%d, want only the final part mapped", c.predicted != nil, len(c.suffix))
				}
				// A read in the volume before the last maps it from its own header.
				off := starts[count-2] + 5
				r.Seek(int64(off), io.SeekStart)
				buf := make([]byte, 40)
				if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[off:off+40]) {
					t.Fatalf("second to last volume differs: %v", err)
				}
				if got := server.Counters().Bodies; got != 4 || len(c.suffix) != 2 {
					t.Fatalf("fetched %d articles with %d suffix parts, want 4 and 2", got, len(c.suffix))
				}
				// Further back is walked, up to the suffix.
				r.Seek(0, io.SeekStart)
				got, err = io.ReadAll(r)
				if err != nil || !bytes.Equal(got, want) {
					t.Fatalf("full read differs: %v", err)
				}
				known := c.parts[len(c.parts)-1]
				if len(c.parts)+len(c.suffix) != count || known.start+known.length != c.suffix[0].start {
					t.Fatalf("walk mapped %d parts up to %d, suffix starts at %d", len(c.parts), known.start+known.length, c.suffix[0].start)
				}
			})
		}
	}
}

// Posts name volumes off by one: the first volume as part002 and the last as
// part001. Main-header numbers order such a set, and its last volume, the
// short one, still gives the final part directly.
func TestRARSuffixMapsEndOfMisnumberedSet(t *testing.T) {
	for _, tail := range []int{31, 2048} {
		t.Run(fmt.Sprintf("tail%d", tail), func(t *testing.T) {
			const count = 50
			in, want, _ := unpredictableRARSet(5, count, 2048, tail)
			named := make([]inputFile, count)
			for i, f := range in {
				f.name = fmt.Sprintf("pack.part%03d.rar", (i+1)%count+1)
				named[(i+1)%count] = f
			}
			files, _, server := setup(t, named, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			startup := server.Counters().Bodies
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(-17, io.SeekEnd)
			got, err := io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want[len(want)-17:]) {
				t.Fatalf("EOF bytes differ: %v", err)
			}
			if !c.cursor.unordered {
				t.Fatal("set was not ordered by its main headers")
			}
			eof := server.Counters().Bodies - startup
			if tail == 31 && (eof > 2 || len(c.suffix) != 1) {
				t.Fatalf("EOF read fetched %d articles with %d suffix parts, want at most 2 and 1", eof, len(c.suffix))
			}
			// Equal volumes leave no short one. Whichever is read must number
			// itself last, or the walk maps the end as before.
			for _, s := range c.suffix {
				if s.file.Name != "pack.part001.rar" {
					t.Fatalf("final part mapped from %s", s.file.Name)
				}
			}
			r.Seek(0, io.SeekStart)
			got, err = io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want) {
				t.Fatalf("full read differs: %v", err)
			}
		})
	}
}

// A file that ends before the set's last volume is walked as before, without
// reading any header the walk would not read.
func TestRARSuffixLeavesEarlierEndToWalk(t *testing.T) {
	for _, version := range []int{4, 5} {
		for _, movieVolumes := range []int{44, 49} {
			t.Run(fmt.Sprintf("RAR%d/%d", version, movieVolumes), func(t *testing.T) {
				const count, chunk = 50, 2048
				movie, extras := payload(movieVolumes*chunk), payload((count-movieVolumes)*chunk)
				var in []inputFile
				for i := range count {
					var v []byte
					if i < movieVolumes {
						v = rarSetVolume(version, "movie.mkv", movie[i*chunk:(i+1)*chunk], len(movie), i, i > 0, i < movieVolumes-1)
					} else {
						j := i - movieVolumes
						v = rarSetVolume(version, "extras.mkv", extras[j*chunk:(j+1)*chunk], len(extras), i, j > 0, i < count-1)
					}
					in = append(in, inputFile{fmt.Sprintf("pack.part%03d.rar", i+1), v, nil})
				}
				files, _, server := setup(t, in, 0, 4, 4)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil || c.Name != "movie.mkv" {
					t.Fatalf("selected %v: %v", c, err)
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				r.Seek(-17, io.SeekEnd)
				got, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(got, movie[len(movie)-17:]) {
					t.Fatalf("EOF bytes differ: %v", err)
				}
				if got := server.Counters().Bodies; got != int64(movieVolumes) || len(c.suffix) != 0 {
					t.Fatalf("fetched %d articles with %d suffix parts, want the walk's %d and none", got, len(c.suffix), movieVolumes)
				}
			})
		}
	}
}

// A last volume that does not end the file leaves the walk to map it.
func TestRARSuffixRejectsForeignLastVolume(t *testing.T) {
	in, want, _ := unpredictableRARSet(5, 50, 2048, 31)
	// The same name and size, but numbered as another volume: never trusted.
	in[49].data = withRecoveryRecord(5, rar5Volume("movie.mkv", want[len(want)-31:], len(want), 8, false), 48)
	files, _, _ := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	r.Seek(-17, io.SeekEnd)
	if _, err := io.ReadAll(r); err == nil {
		t.Fatal("misnumbered last volume was served")
	}
	if len(c.suffix) != 0 || !c.suffixDone {
		t.Fatalf("suffix=%d done=%v, want none mapped", len(c.suffix), c.suffixDone)
	}
}

// The startup warmer's Cues read reaches the end of such a set from its last
// volume, not by reading every volume header first.
func TestStartupWarmsCuesOfUnpredictableRARSet(t *testing.T) {
	for _, version := range []int{4, 5} {
		t.Run(fmt.Sprintf("RAR%d", version), func(t *testing.T) {
			const count, chunk = 60, 4096
			want := payload((count-1)*chunk + 3000)
			cues := int64(len(want) - 2000)
			copy(want, cuesPrefix(uint64(cues-10)))
			var in []inputFile
			for i := range count {
				end := min((i+1)*chunk, len(want))
				in = append(in, inputFile{fmt.Sprintf("movie.part%02d.rar", i+1),
					rarSetVolume(version, "movie.mkv", want[i*chunk:end], len(want), i, i > 0, i < count-1), nil})
			}
			files, store, server := setup(t, in, 10*time.Millisecond, 8, 4)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			c, err := Select(ctx, files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			s := &Session{ctx: ctx, content: c, store: store, trace: newStartupTrace()}
			s.startMKVWarmup(true, 8)
			defer s.warmup.Close()
			deadline := time.Now().Add(3 * time.Second)
			for {
				if _, ok := s.trace.snapshot()["marksMs"].(map[string]float64)["cues_article_ready"]; ok {
					break
				}
				if time.Now().After(deadline) {
					t.Fatalf("Cues were not warmed: %v", s.trace.snapshot()["marksMs"])
				}
				time.Sleep(time.Millisecond)
			}
			// The first, second and last volumes, not one per volume.
			if got := server.Counters().Bodies; got > 4 {
				t.Fatalf("warming the Cues fetched %d articles", got)
			}
			r := c.Reader(ctx, 0)
			defer r.Close()
			r.Seek(cues, io.SeekStart)
			b := make([]byte, 128)
			if _, err := io.ReadFull(r, b); err != nil || !bytes.Equal(b, want[cues:cues+128]) {
				t.Fatalf("Cues read: %v", err)
			}
		})
	}
}

func TestRARSuffixConcurrentReaders(t *testing.T) {
	in, want, starts := unpredictableRARSet(5, 60, 2048, 700)
	files, _, _ := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for _, off := range []int{len(want) - 19, 0, starts[58] + 3, starts[30], len(want) - 600, starts[59] - 9, starts[2]} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(int64(off), io.SeekStart)
			buf := make([]byte, 19)
			if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[off:off+19]) {
				t.Errorf("concurrent read at %d differs: %v", off, err)
			}
		}()
	}
	wg.Wait()
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	got, err := io.ReadAll(r)
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("full read differs: %v", err)
	}
}
