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
)

func rar5TestHeader(body []byte) []byte {
	h := binary.AppendUvarint(nil, uint64(len(body)))
	h = append(h, body...)
	return append(binary.LittleEndian.AppendUint32(nil, crc32.ChecksumIEEE(h)), h...)
}

// Use the independent wire encoder with a real, zero-based main-header index.
func indexedRAR5(name string, data []byte, total, index int, after bool) []byte {
	flags := uint64(0)
	if index > 0 {
		flags |= 8
	}
	if after {
		flags |= 16
	}
	v := rar5Volume(name, data, total, flags, false)
	if index == 0 {
		return v
	}
	main := binary.AppendUvarint([]byte{1, 0, 3}, uint64(index))
	return append(append(append([]byte(nil), v[:8]...), rar5TestHeader(main)...), v[17:]...)
}

func regularRARSet(version, count, chunk, tail int) ([]inputFile, []byte, []int) {
	sizes := make([]int, count)
	total := 0
	for i := range sizes {
		sizes[i] = chunk
		if version == 5 {
			if i == 0 {
				sizes[i]++ // First main header omits the volume number.
			} else if i >= 128 {
				sizes[i]--
			}
		}
		if i == count-1 {
			sizes[i] = tail
		}
		total += sizes[i]
	}
	want := payload(total)
	in := make([]inputFile, count)
	starts := make([]int, count)
	off := 0
	for i, size := range sizes {
		starts[i] = off
		var data []byte
		if version == 5 {
			data = indexedRAR5("movie.mkv", want[off:off+size], total, i, i < count-1)
		} else {
			flags := uint16(0)
			if i > 0 {
				flags |= 1
			}
			if i < count-1 {
				flags |= 2
			}
			data = rar4Volume("movie.mkv", want[off:off+size], total, flags, false)
		}
		in[i] = inputFile{fmt.Sprintf("pack.part%03d.rar", i+1), data, nil}
		off += size
	}
	return in, want, starts
}

func TestRARArithmeticSeek(t *testing.T) {
	for _, version := range []int{4, 5} {
		for _, count := range []int{50, 132} {
			t.Run(fmt.Sprintf("RAR%d/%d", version, count), func(t *testing.T) {
				in, want, starts := regularRARSet(version, count, 2048, 31)
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
					t.Fatalf("EOF seek bytes differ: %v", err)
				}
				if got := server.Counters().Bodies; got != 3 {
					t.Fatalf("EOF seek fetched %d articles, want 3 regardless of volume count", got)
				}
				if len(c.parts) != count || c.predicted == nil {
					t.Fatal("continuation extents were not predicted")
				}
				// Cold seek to part40 reads only its own header/body.
				r.Seek(int64(starts[39]+11), io.SeekStart)
				buf := make([]byte, 37)
				if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[starts[39]+11:starts[39]+48]) {
					t.Fatalf("forward seek bytes differ: %v", err)
				}
				if got := server.Counters().Bodies; got != 4 {
					t.Fatalf("part40 seek fetched %d articles, want 4 total", got)
				}
				// Exercise a width transition, every boundary, and backwards reads.
				for i := count - 1; i > 0; i-- {
					off := starts[i] - 9
					r.Seek(int64(off), io.SeekStart)
					if _, err := io.ReadFull(r, buf[:18]); err != nil || !bytes.Equal(buf[:18], want[off:off+18]) {
						t.Fatalf("boundary %d differs: %v", i, err)
					}
				}
			})
		}
	}
}

func TestRARPredictionFallback(t *testing.T) {
	for _, version := range []int{4, 5} {
		for _, irregular := range []string{"volume size", "final remainder", "header size", "unordered", "packed width"} {
			t.Run(fmt.Sprintf("RAR%d/%s", version, irregular), func(t *testing.T) {
				chunk := 2048
				if irregular == "packed width" {
					if version == 4 {
						t.Skip("RAR4 uses fixed-width packed sizes")
					}
					chunk = 16384
				}
				in, want, starts := regularRARSet(version, 132, chunk, 31)
				switch irregular {
				case "volume size", "final remainder":
					// Adjacent changes exercise demand validation. A shifted final
					// remainder instead rejects prediction before publishing it.
					i := 20
					if irregular == "final remainder" {
						i = len(in) - 2
					}
					end := len(want)
					after, flags := false, uint16(1)
					if i+2 < len(in) {
						end, after, flags = starts[i+2], true, 3
					}
					if version == 4 {
						in[i].data = rar4Volume("movie.mkv", want[starts[i]:starts[i+1]-7], len(want), 3, false)
						in[i+1].data = rar4Volume("movie.mkv", want[starts[i+1]-7:end], len(want), flags, false)
					} else {
						in[i].data = indexedRAR5("movie.mkv", want[starts[i]:starts[i+1]-7], len(want), i, true)
						in[i+1].data = indexedRAR5("movie.mkv", want[starts[i+1]-7:end], len(want), i+1, after)
					}
				case "header size":
					// Insert a harmless block before an intermediate file header.
					if version == 4 {
						h := []byte{0, 0, 0x75, 0, 0, 7, 0}
						binary.LittleEndian.PutUint16(h, uint16(crc32.ChecksumIEEE(h[2:])))
						v := in[20].data
						in[20].data = append(append(append([]byte(nil), v[:20]...), h...), v[20:]...)
					} else {
						v := in[20].data
						in[20].data = append(append(append([]byte(nil), v[:17]...), rar5TestHeader([]byte{3, 0})...), v[17:]...)
					}
				}
				files, _, _ := setup(t, in, 0, 4, 4)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				if irregular == "unordered" {
					c.cursor.unordered = true
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				// Read the changed volume first, so demand validation must reject it.
				r.Seek(int64(starts[20]), io.SeekStart)
				got, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(got, want[starts[20]:]) {
					t.Fatalf("fallback bytes differ: %v", err)
				}
				if c.predicted != nil {
					t.Fatal("irregular layout retained prediction")
				}
			})
		}
	}
}

func TestRARPredictionConcurrentReaders(t *testing.T) {
	in, want, starts := regularRARSet(5, 50, 2048, 31)
	files, _, _ := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for _, off := range []int{0, starts[39], starts[49], starts[39] + 1, starts[10]} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(int64(off), io.SeekStart)
			buf := make([]byte, 19)
			if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[off:off+19]) {
				t.Errorf("concurrent seek %d differs: %v", off, err)
			}
		}()
	}
	wg.Wait()
}

func TestRAR5PaddedQuickOpenSeek(t *testing.T) {
	in, want, _ := regularRARSet(5, 132, 16384, 31)
	for i := range in {
		v := in[i].data
		mainSize, mainWidth := binary.Uvarint(v[12:])
		filePos := 12 + mainWidth + int(mainSize)
		fileSize, fileWidth := binary.Uvarint(v[filePos+4:])
		bodyPos := filePos + 4 + fileWidth
		body := v[bodyPos : bodyPos+int(fileSize)]
		packed, packedWidth := binary.Uvarint(body[2:])
		// Reserve four packed-size bytes, including across the 16384 boundary.
		padded := append([]byte(nil), body[:2]...)
		for j := 0; j < 4; j++ {
			value := byte(packed & 127)
			packed >>= 7
			if j < 3 {
				value |= 128
			}
			padded = append(padded, value)
		}
		padded = append(padded, body[2+packedWidth:]...)
		// Match the real fixtures' Quick Open locator with a reserved 5-byte
		// offset. The offset values do not affect the payload's location.
		main := []byte{1, 5, 8, 1}
		if i > 0 {
			main[3] = 3
			main = binary.AppendUvarint(main, uint64(i))
		}
		main = append(main, 7, 1, 1, byte(i&127)|128, 128, 128, 128, 0)
		out := append(append([]byte(nil), v[:8]...), rar5TestHeader(main)...)
		out = append(out, rar5TestHeader(padded)...)
		in[i].data = append(out, v[bodyPos+int(fileSize):]...)
	}
	files, _, server := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	r.Seek(-17, io.SeekEnd)
	got, err := io.ReadAll(r)
	if err != nil || !bytes.Equal(got, want[len(want)-17:]) || c.predicted == nil {
		t.Fatalf("padded Quick Open seek: prediction=%v err=%v", c.predicted != nil, err)
	}
	if got := server.Counters().Bodies; got != 3 {
		t.Fatalf("EOF seek fetched %d articles, want 3", got)
	}
	r.Seek(0, io.SeekStart)
	got, err = io.ReadAll(r)
	if err != nil || !bytes.Equal(got, want) || c.predicted == nil {
		t.Fatalf("padded Quick Open read: prediction=%v err=%v", c.predicted != nil, err)
	}
}
