package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"testing"
)

// udfWriter lays out a small UDF 2.50 image as a Blu-ray authoring tool does:
// a physical partition holding the metadata file, its mirror and the file
// data, and a metadata partition holding the file set, directories and entries.
type udfWriter struct {
	img        []byte
	partStart  int // first sector of the physical partition
	metaStart  int // physical block of metadata block 0
	metaBlocks int
	nextMeta   int // next free metadata block
	nextData   int // next free physical block
	extended   bool
	continued  bool // file allocation descriptors continue in an AED
	wide       bool // 16-bit names
}

type udfNode struct {
	name     string
	children []*udfNode
	data     []byte
	gap      bool // leave a block free between this file's extents
}

func udfPutTag(b []byte, id uint16, location uint32) {
	binary.LittleEndian.PutUint16(b, id)
	binary.LittleEndian.PutUint16(b[2:], 3)
	binary.LittleEndian.PutUint32(b[12:], location)
	sum := byte(0)
	for i := 0; i < 16; i++ {
		if i != 4 {
			sum += b[i]
		}
	}
	b[4] = sum
}

func (w *udfWriter) sector(s int) []byte { return w.img[s*udfSector : (s+1)*udfSector] }

func (w *udfWriter) metaBlock(n int) []byte { return w.sector(w.partStart + w.metaStart + n) }

func putLongAD(b []byte, length int, block int, part int) {
	binary.LittleEndian.PutUint32(b, uint32(length))
	binary.LittleEndian.PutUint32(b[4:], uint32(block))
	binary.LittleEndian.PutUint16(b[8:], uint16(part))
}

func (w *udfWriter) dstring(name string) []byte {
	if !w.wide {
		return append([]byte{8}, name...)
	}
	out := []byte{16}
	for _, r := range name {
		out = binary.BigEndian.AppendUint16(out, uint16(r))
	}
	return out
}

// entry writes a file entry at metadata block at, with long or short
// allocation descriptors, and returns it.
func (w *udfWriter) entry(at int, fileType byte, size int, ads []byte, long bool) {
	b := w.metaBlock(at)
	id, adAt := uint16(261), 176
	if w.extended {
		id, adAt = 266, 216
	}
	b[27] = fileType
	if long {
		b[34] = 1
	}
	binary.LittleEndian.PutUint64(b[56:], uint64(size))
	binary.LittleEndian.PutUint32(b[adAt-4:], uint32(len(ads)))
	copy(b[adAt:], ads)
	udfPutTag(b, id, uint32(at))
}

func (w *udfWriter) fid(name string, dir, parent bool, icb int) []byte {
	ident := []byte(nil)
	if !parent {
		ident = w.dstring(name)
	}
	b := make([]byte, (38+len(ident)+3)&^3)
	binary.LittleEndian.PutUint16(b[16:], 1)
	if dir {
		b[18] |= 2
	}
	if parent {
		b[18] |= 8
	}
	b[19] = byte(len(ident))
	putLongAD(b[20:], udfSector, icb, 1)
	copy(b[38:], ident)
	udfPutTag(b, 257, 0)
	return b
}

// dir writes a directory and its subtree; it returns the directory entry's block.
func (w *udfWriter) dir(n *udfNode, parent int) int {
	at, data := w.nextMeta, w.nextMeta+1
	w.nextMeta += 2
	var fids []byte
	fids = append(fids, w.fid("", true, true, parent)...)
	for _, c := range n.children {
		var icb int
		if c.children != nil {
			icb = w.dir(c, at)
		} else {
			icb = w.file(c)
		}
		fids = append(fids, w.fid(c.name, c.children != nil, false, icb)...)
	}
	copy(w.metaBlock(data), fids)
	ad := make([]byte, 8)
	binary.LittleEndian.PutUint32(ad, uint32(len(fids)))
	binary.LittleEndian.PutUint32(ad[4:], uint32(data))
	w.entry(at, 4, len(fids), ad, false)
	return at
}

// file stores data in the physical partition, in extents of three blocks at
// most, and writes its entry with long allocation descriptors.
func (w *udfWriter) file(n *udfNode) int {
	at := w.nextMeta
	w.nextMeta++
	var ads []byte
	for off := 0; off < len(n.data); {
		take := min(3*udfSector, len(n.data)-off)
		copy(w.img[(w.partStart+w.nextData)*udfSector:], n.data[off:off+take])
		ad := make([]byte, 16)
		putLongAD(ad, take, w.nextData, 0)
		ads = append(ads, ad...)
		w.nextData += (take + udfSector - 1) / udfSector
		if n.gap {
			w.nextData++
		}
		off += take
	}
	if w.continued && len(ads) > 32 {
		// Keep two descriptors in the entry; the rest go to an allocation
		// extent descriptor in the next metadata block.
		aed := w.nextMeta
		w.nextMeta++
		b := w.metaBlock(aed)
		binary.LittleEndian.PutUint32(b[20:], uint32(len(ads)-32))
		copy(b[24:], ads[32:])
		udfPutTag(b, 258, uint32(aed))
		next := make([]byte, 16)
		putLongAD(next, udfSector, aed, 1)
		binary.LittleEndian.PutUint32(next, uint32(udfSector)|3<<30)
		ads = append(ads[:32:32], next...)
	}
	w.entry(at, 5, len(n.data), ads, true)
	return at
}

func buildUDF(root *udfNode, extended, continued, wide bool) []byte {
	w := &udfWriter{img: make([]byte, 2048*udfSector), partStart: 300, metaStart: 2, metaBlocks: 64, nextMeta: 1, extended: extended, continued: continued, wide: wide}
	w.nextData = w.metaStart + w.metaBlocks
	for i, id := range []string{"BEA01", "NSR03", "TEA01"} {
		copy(w.sector(16 + i)[1:], id)
	}
	anchor := w.sector(256)
	binary.LittleEndian.PutUint32(anchor[16:], 16*udfSector)
	binary.LittleEndian.PutUint32(anchor[20:], 32)
	udfPutTag(anchor, 2, 256)
	udfPutTag(w.sector(32), 1, 32) // Primary volume descriptor.
	pd := w.sector(33)
	binary.LittleEndian.PutUint32(pd[188:], uint32(w.partStart))
	binary.LittleEndian.PutUint32(pd[192:], uint32(len(w.img)/udfSector-w.partStart))
	udfPutTag(pd, 5, 33)
	lvd := w.sector(34)
	binary.LittleEndian.PutUint32(lvd[212:], udfSector)
	putLongAD(lvd[248:], udfSector, 0, 1) // File set descriptor: metadata block 0.
	maps := []byte{1, 6, 1, 0, 0, 0}
	meta := make([]byte, 64)
	meta[0], meta[1] = 2, 64
	copy(meta[5:], "*UDF Metadata Partition")
	binary.LittleEndian.PutUint32(meta[40:], 0) // Metadata file: physical block 0.
	binary.LittleEndian.PutUint32(meta[44:], 1) // Its mirror: block 1.
	maps = append(maps, meta...)
	binary.LittleEndian.PutUint32(lvd[264:], uint32(len(maps)))
	binary.LittleEndian.PutUint32(lvd[268:], 2)
	copy(lvd[440:], maps)
	udfPutTag(lvd, 6, 34)
	udfPutTag(w.sector(35), 8, 35)
	rootAt := w.dir(root, 0)
	fsd := w.metaBlock(0)
	putLongAD(fsd[400:], udfSector, rootAt, 1)
	udfPutTag(fsd, 256, 0)
	// The metadata file and its mirror both map metadata blocks onto the
	// physical blocks after them.
	for _, block := range []int{0, 1} {
		b := w.sector(w.partStart + block)
		b[27] = 250
		binary.LittleEndian.PutUint64(b[56:], uint64(w.metaBlocks*udfSector))
		binary.LittleEndian.PutUint32(b[172:], 8)
		binary.LittleEndian.PutUint32(b[176:], uint32(w.metaBlocks*udfSector))
		binary.LittleEndian.PutUint32(b[180:], uint32(w.metaStart))
		udfPutTag(b, 261, uint32(block))
	}
	return w.img[:(w.partStart+w.nextData+1)*udfSector]
}

func blurayTree(feature, extra []byte) *udfNode {
	return &udfNode{children: []*udfNode{
		{name: "BDMV", children: []*udfNode{
			{name: "CLIPINF", children: []*udfNode{{name: "00800.clpi", data: payload(300)}}},
			{name: "STREAM", children: []*udfNode{
				{name: "00001.m2ts", data: extra},
				{name: "00800.m2ts", data: feature, gap: true},
			}},
		}},
	}}
}

func TestDiscImageServesItsFeatureClip(t *testing.T) {
	feature, extra := payload(70000)[1000:], payload(9000)[77:]
	for _, tc := range []struct {
		name                      string
		extended, continued, wide bool
		archived                  bool
	}{
		{name: "file entries in RAR", archived: true},
		{name: "extended entries, continued descriptors, wide names", extended: true, continued: true, wide: true, archived: true},
		{name: "posted without an archive"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			image := buildUDF(blurayTree(feature, extra), tc.extended, tc.continued, tc.wide)
			in := []inputFile{{"Movie.2026.BluRay.iso", image, nil}}
			if tc.archived {
				third := len(image) / 3
				in = []inputFile{
					{"Movie.part1.rar", rar4MultiVolume(rar4Entry{"Movie.2026.BluRay.iso", image[:third], len(image), 2}), nil},
					{"Movie.part2.rar", rar4MultiVolume(rar4Entry{"Movie.2026.BluRay.iso", image[third : 2*third], len(image), 3}), nil},
					{"Movie.part3.rar", rar4MultiVolume(rar4Entry{"Movie.2026.BluRay.iso", image[2*third:], len(image), 1}), nil},
				}
			}
			files, _, _ := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != "Movie.2026.BluRay.iso/BDMV/STREAM/00800.m2ts" || c.Size != int64(len(feature)) {
				t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
			}
			if len(c.children) < 2 {
				t.Fatalf("feature mapped as %d ranges; the gaps should split it", len(c.children))
			}
			if got := readAllContent(t, c); !bytes.Equal(got, feature) {
				t.Fatal("feature bytes differ")
			}
			if c.clipInfo == nil || c.clipInfo.Name != "Movie.2026.BluRay.iso/BDMV/CLIPINF/00800.clpi" {
				t.Fatalf("clip info = %v", c.clipInfo)
			}
			if got := readAllContent(t, c.clipInfo); !bytes.Equal(got, payload(300)) {
				t.Fatal("clip info bytes differ")
			}
			if streamPath(c.Name) != "BDMV/STREAM/00800.m2ts" || clipInfoPath(c.Name) != "BDMV/CLIPINF/00800.clpi" {
				t.Fatalf("served as %s and %s", streamPath(c.Name), clipInfoPath(c.Name))
			}
			// Reads that start inside and across every range of the image.
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			buf := make([]byte, 64)
			for _, ch := range c.children {
				for _, off := range []int64{ch.start, ch.start + ch.length - 20} {
					if off < 0 || off >= c.Size {
						continue
					}
					r.Seek(off, io.SeekStart)
					n := min(int64(len(buf)), c.Size-off)
					if _, err := io.ReadFull(r, buf[:n]); err != nil || !bytes.Equal(buf[:n], feature[off:off+n]) {
						t.Fatalf("read at %d differs: %v", off, err)
					}
				}
			}
		})
	}
}

func TestDiscImagesThatCannotPlay(t *testing.T) {
	dvd := buildUDF(&udfNode{children: []*udfNode{{name: "VIDEO_TS", children: []*udfNode{{name: "VTS_01_1.VOB", data: payload(5000)}}}}}, false, false, false)
	for _, tc := range []struct {
		name  string
		image []byte
		want  error
	}{
		{"DVD", dvd, errDVDImage},
		{"no BDMV", buildUDF(&udfNode{children: []*udfNode{{name: "DATA", children: []*udfNode{{name: "a.bin", data: payload(10)}}}}}, false, false, false), errNoBluray},
		{"not UDF", payload(600 * udfSector), errNotUDF},
		{"too short", payload(1000), errNotUDF},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files, _, _ := setup(t, []inputFile{{"Disc.iso", tc.image, nil}}, 0, 4, 4)
			if _, err := Select(context.Background(), files, Selection{}); !errors.Is(err, tc.want) {
				t.Fatalf("err = %v, want %v", err, tc.want)
			}
		})
	}
	// A video beside an image that cannot play is still selected.
	files, _, _ := setup(t, []inputFile{{"Disc.iso", dvd, nil}, {"Movie.mkv", payload(3000), nil}}, 0, 4, 4)
	if c, err := Select(context.Background(), files, Selection{}); err != nil || c.Name != "Movie.mkv" {
		t.Fatalf("selected %v, %v", c, err)
	}
}

func TestDiscImageReaderChecksDescriptors(t *testing.T) {
	image := buildUDF(blurayTree(payload(9000), payload(900)), false, false, false)
	corrupt := func(sector int) []byte {
		bad := append([]byte(nil), image...)
		bad[sector*udfSector+4] ^= 0xFF // Tag checksum.
		return bad
	}
	// The anchor, the logical volume and the file set descriptor.
	for _, sector := range []int{256, 34, 302} {
		bad := corrupt(sector)
		if _, err := openUDF(bytes.NewReader(bad), int64(len(bad))); err == nil {
			t.Fatalf("corrupt descriptor in sector %d accepted", sector)
		}
	}
	// A damaged metadata file is read from its mirror.
	bad := corrupt(300)
	img, err := openUDF(bytes.NewReader(bad), int64(len(bad)))
	if err != nil {
		t.Fatalf("metadata mirror not used: %v", err)
	}
	if root, err := img.file(img.root); err != nil || !root.dir {
		t.Fatal(fmt.Sprint("root through the mirror: ", err))
	}
}
