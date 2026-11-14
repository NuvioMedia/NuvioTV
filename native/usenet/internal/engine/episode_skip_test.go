package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"testing"
)

type rar4Entry struct {
	name  string
	data  []byte
	total int
	flags uint16 // 1: continues from the previous volume, 2: continues in the next.
}

// rar4Volumes writes one RAR4 volume holding several file blocks.
func rar4MultiVolume(entries ...rar4Entry) []byte {
	var out bytes.Buffer
	out.WriteString("Rar!\x1a\x07\x00")
	add := func(h []byte) { binary.LittleEndian.PutUint16(h, uint16(crc32.ChecksumIEEE(h[2:]))); out.Write(h) }
	main := make([]byte, 13)
	main[2] = 0x73
	binary.LittleEndian.PutUint16(main[5:], 13)
	add(main)
	for _, e := range entries {
		h := make([]byte, 32+len(e.name))
		h[2] = 0x74
		binary.LittleEndian.PutUint16(h[3:], e.flags|0x8000)
		binary.LittleEndian.PutUint16(h[5:], uint16(len(h)))
		binary.LittleEndian.PutUint32(h[7:], uint32(len(e.data)))
		binary.LittleEndian.PutUint32(h[11:], uint32(e.total))
		h[25] = 0x30
		binary.LittleEndian.PutUint16(h[26:], uint16(len(e.name)))
		copy(h[32:], e.name)
		add(h)
		out.Write(e.data)
	}
	end := make([]byte, 7)
	end[2] = 0x7b
	binary.LittleEndian.PutUint16(end[5:], 7)
	add(end)
	return out.Bytes()
}

// obfuscatedEpisode splits want across volumes named like a release, with a
// hashed inner name, and appends extra files to the last volume.
func obfuscatedEpisode(want []byte, volumes int, extra ...rar4Entry) []inputFile {
	chunk := len(want) / volumes
	var in []inputFile
	for k := 0; k < volumes; k++ {
		from, to := k*chunk, (k+1)*chunk
		flags := uint16(0)
		if k > 0 {
			flags |= 1
		}
		if k < volumes-1 {
			flags |= 2
		} else {
			to = len(want)
		}
		entries := []rar4Entry{{"779e135e20ae4c16.mkv", want[from:to], len(want), flags}}
		if k == volumes-1 {
			entries = append(entries, extra...)
		}
		in = append(in, inputFile{fmt.Sprintf("Show.S01E01.1080p.part%02d.rar", k+1), rar4MultiVolume(entries...), nil})
	}
	return in
}

func TestObfuscatedEpisodeSkipsMiddleVolumes(t *testing.T) {
	want := payload(12 * 30000)
	for _, tc := range []struct {
		name  string
		extra []rar4Entry
	}{
		{"single file", nil},
		{"sample after it", []rar4Entry{{"779e135e.sample.mkv", payload(4000), 4000, 0}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files, _, server := setup(t, obfuscatedEpisode(want, 12, tc.extra...), 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{Season: 1, Episode: 1})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != "779e135e20ae4c16.mkv" || c.Size != int64(len(want)) {
				t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
			}
			// First and last volume headers (twice for the strict, the
			// multi-episode and the fallback pass at most), never the middle.
			if bodies := server.Counters().Bodies; bodies > 6 {
				t.Fatalf("selection read %d articles; middle volumes were walked", bodies)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatalf("content mismatch (%d bytes)", len(got))
			}
		})
	}
}

func TestObfuscatedEpisodeWithSecondVideoStaysAmbiguous(t *testing.T) {
	want := payload(6 * 30000)
	files, _, _ := setup(t, obfuscatedEpisode(want, 6, rar4Entry{"a91c0e.mkv", payload(4000), 4000, 0}), 0, 4, 4)
	if _, err := Select(context.Background(), files, Selection{Season: 1, Episode: 1}); err != errNoMatchingVideo {
		t.Fatalf("err = %v, want %v", err, errNoMatchingVideo)
	}
}
