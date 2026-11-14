package engine

// A Blu-ray disc image (.iso) holds the disc's UDF 2.50 file system. The
// feature is BDMV/STREAM/<clip>.m2ts, stored as a few large extents of the
// image. Selection reads the volume descriptors and the directories, which a
// disc writes in its first megabytes, and serves the clip as byte ranges of
// the image, the way a RAR set inside a 7z serves its file (nested.go).

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"unicode/utf16"
)

const (
	udfSector        = 2048
	udfMaxDirBytes   = 16 << 20
	udfMaxEntries    = 100000
	udfMaxContinuing = 4096
)

var (
	errNotUDF        = errors.New("not a UDF disc image")
	errNoBluray      = errors.New("the disc image holds no Blu-ray clips (BDMV/STREAM)")
	errDVDImage      = errors.New("DVD disc images are not supported; use a Blu-ray image or a video file")
	errUDFUnreadable = errors.New("unsupported UDF disc image layout")
)

type udfExtent struct{ offset, length int64 }

// udfPartition maps a partition's logical blocks to image offsets: from its
// first sector, or, for a UDF 2.50 metadata partition, through the extents of
// its metadata file.
type udfPartition struct {
	start int64
	meta  []udfExtent
}

type udfAD struct {
	length int64
	kind   uint32 // 0 recorded, 1 allocated, 2 unallocated, 3 continuation
	block  uint32
	part   uint16
}

type udfFile struct {
	dir      bool
	size     int64
	extents  []udfExtent // image ranges, in file order
	embedded []byte      // small files and directories stored in the entry itself
}

type udfEntry struct {
	name string
	dir  bool
	icb  udfAD
}

type udfImage struct {
	r     io.ReaderAt
	size  int64
	parts []udfPartition // by partition reference number
	root  udfAD
	cache *udfCache // Shared by copies that read through other readers.
}

// udfCache keeps the image's last chunks read. Descriptors, entries and small
// files sit together, and each small read would otherwise fetch a whole article.
type udfCache struct {
	mu     sync.Mutex
	chunks map[int64][]byte
	order  []int64
}

const (
	udfChunk  = 1 << 20
	udfChunks = 8
)

func (img *udfImage) read(off int64, n int) ([]byte, error) {
	if off < 0 || n < 0 || off+int64(n) > img.size {
		return nil, errUDFUnreadable
	}
	out := make([]byte, 0, n)
	for len(out) < n {
		at := (off + int64(len(out))) / udfChunk * udfChunk
		chunk, err := img.chunk(at)
		if err != nil {
			return nil, err
		}
		from := off + int64(len(out)) - at
		out = append(out, chunk[from:min(int64(len(chunk)), from+int64(n-len(out)))]...)
	}
	return out, nil
}

func (img *udfImage) chunk(at int64) ([]byte, error) {
	c := img.cache
	c.mu.Lock()
	if b, ok := c.chunks[at]; ok {
		c.mu.Unlock()
		return b, nil
	}
	c.mu.Unlock()
	b := make([]byte, min(udfChunk, img.size-at))
	if _, err := img.r.ReadAt(b, at); err != nil {
		return nil, err
	}
	c.mu.Lock()
	if _, ok := c.chunks[at]; !ok {
		c.chunks[at] = b
		c.order = append(c.order, at)
		if len(c.order) > udfChunks {
			delete(c.chunks, c.order[0])
			c.order = c.order[1:]
		}
	}
	c.mu.Unlock()
	return b, nil
}

// udfTag checks a descriptor tag: its identifier and checksum.
func udfTag(b []byte, ids ...uint16) (uint16, bool) {
	if len(b) < 16 {
		return 0, false
	}
	sum := byte(0)
	for i := 0; i < 16; i++ {
		if i != 4 {
			sum += b[i]
		}
	}
	id := binary.LittleEndian.Uint16(b)
	if sum != b[4] {
		return id, false
	}
	for _, want := range ids {
		if id == want {
			return id, true
		}
	}
	return id, false
}

func udfLongAD(b []byte) udfAD {
	raw := binary.LittleEndian.Uint32(b)
	return udfAD{length: int64(raw & 0x3FFFFFFF), kind: raw >> 30, block: binary.LittleEndian.Uint32(b[4:]), part: binary.LittleEndian.Uint16(b[8:])}
}

func openUDF(r io.ReaderAt, size int64) (*udfImage, error) {
	img := &udfImage{r: r, size: size, cache: &udfCache{chunks: map[int64][]byte{}}}
	if size < 257*udfSector {
		return nil, errNotUDF
	}
	// The volume recognition sequence names NSR02 or NSR03 for UDF.
	nsr := false
	for s := int64(16); s < 32 && !nsr; s++ {
		b, err := img.read(s*udfSector, 6)
		if err != nil {
			return nil, err
		}
		id := string(b[1:6])
		nsr = id == "NSR02" || id == "NSR03"
		if id != "BEA01" && id != "NSR02" && id != "NSR03" && id != "CD001" && id != "CDW02" && id != "BOOT2" && id != "TEA01" {
			break
		}
	}
	if !nsr {
		return nil, errNotUDF
	}
	anchor, err := img.read(256*udfSector, udfSector)
	if err != nil {
		return nil, err
	}
	if _, ok := udfTag(anchor, 2); !ok {
		return nil, errNotUDF
	}
	vdsLength, vdsStart := int64(binary.LittleEndian.Uint32(anchor[16:])), int64(binary.LittleEndian.Uint32(anchor[20:]))
	partitions := map[uint16]int64{}
	var lvd []byte
	for s, end := vdsStart, vdsStart+min(vdsLength/udfSector, 256); s < end; s++ {
		b, err := img.read(s*udfSector, udfSector)
		if err != nil {
			return nil, err
		}
		id, ok := udfTag(b, 5, 6, 8, 1, 3, 4, 7)
		if !ok {
			break
		}
		if id == 8 {
			break
		}
		switch id {
		case 5:
			partitions[binary.LittleEndian.Uint16(b[22:])] = int64(binary.LittleEndian.Uint32(b[188:]))
		case 6:
			if lvd == nil {
				lvd = b
			}
		}
	}
	if lvd == nil || binary.LittleEndian.Uint32(lvd[212:]) != udfSector {
		return nil, errUDFUnreadable
	}
	fsd := udfLongAD(lvd[248:])
	maps := int(binary.LittleEndian.Uint32(lvd[268:]))
	table := lvd[440:min(len(lvd), 440+int(binary.LittleEndian.Uint32(lvd[264:])))]
	type metaMap struct {
		ref          int
		physical     uint16
		file, mirror uint32
	}
	var metas []metaMap
	for i, p := 0, 0; i < maps; i++ {
		if p+2 > len(table) || int(table[p+1]) < 2 || p+int(table[p+1]) > len(table) {
			return nil, errUDFUnreadable
		}
		m := table[p : p+int(table[p+1])]
		switch {
		case m[0] == 1 && len(m) >= 6:
			start, ok := partitions[binary.LittleEndian.Uint16(m[4:])]
			if !ok {
				return nil, errUDFUnreadable
			}
			img.parts = append(img.parts, udfPartition{start: start})
		case m[0] == 2 && len(m) >= 52 && strings.HasPrefix(string(m[5:28]), "*UDF Metadata Partition"):
			if _, ok := partitions[binary.LittleEndian.Uint16(m[38:])]; !ok {
				return nil, errUDFUnreadable
			}
			metas = append(metas, metaMap{i, binary.LittleEndian.Uint16(m[38:]), binary.LittleEndian.Uint32(m[40:]), binary.LittleEndian.Uint32(m[44:])})
			img.parts = append(img.parts, udfPartition{start: -1})
		default:
			// Sparable and virtual partitions belong to rewritable and
			// recordable discs, not to the images of pressed ones.
			return nil, errUDFUnreadable
		}
		p += int(table[p+1])
	}
	for _, m := range metas {
		start := partitions[m.physical]
		var meta *udfFile
		for _, loc := range []uint32{m.file, m.mirror} {
			if meta, err = img.entryAt((start+int64(loc))*udfSector, 0, start); err == nil {
				break
			}
		}
		if err != nil {
			return nil, err
		}
		img.parts[m.ref].meta = meta.extents
	}
	b, err := img.block(fsd, udfSector)
	if err != nil {
		return nil, err
	}
	if _, ok := udfTag(b, 256); !ok {
		return nil, errUDFUnreadable
	}
	img.root = udfLongAD(b[400:])
	return img, nil
}

// span maps n bytes from a partition block to image ranges.
func (img *udfImage) span(part uint16, block uint32, n int64) ([]udfExtent, error) {
	if int(part) >= len(img.parts) {
		return nil, errUDFUnreadable
	}
	p := img.parts[part]
	off := int64(block) * udfSector
	if p.meta == nil {
		if p.start < 0 {
			return nil, errUDFUnreadable
		}
		e := udfExtent{p.start*udfSector + off, n}
		if e.offset+e.length > img.size {
			return nil, errUDFUnreadable
		}
		return []udfExtent{e}, nil
	}
	var out []udfExtent
	for _, m := range p.meta {
		if n == 0 {
			break
		}
		if off >= m.length {
			off -= m.length
			continue
		}
		take := min(n, m.length-off)
		out = append(out, udfExtent{m.offset + off, take})
		n -= take
		off = 0
	}
	if n != 0 {
		return nil, errUDFUnreadable
	}
	return out, nil
}

func (img *udfImage) block(ad udfAD, n int) ([]byte, error) {
	spans, err := img.span(ad.part, ad.block, int64(n))
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, n)
	for _, s := range spans {
		b, err := img.read(s.offset, int(s.length))
		if err != nil {
			return nil, err
		}
		out = append(out, b...)
	}
	return out, nil
}

// entryAt reads a (extended) file entry at an image offset. Short allocation
// descriptors name blocks of the entry's own partition: part, or the physical
// partition starting at sector physical for the metadata file itself.
func (img *udfImage) entryAt(off int64, part uint16, physical int64) (*udfFile, error) {
	b, err := img.read(off, udfSector)
	if err != nil {
		return nil, err
	}
	return img.entry(b, part, physical)
}

func (img *udfImage) entry(b []byte, part uint16, physical int64) (*udfFile, error) {
	id, ok := udfTag(b, 261, 266)
	if !ok {
		return nil, errUDFUnreadable
	}
	f := &udfFile{dir: b[27] == 4, size: int64(binary.LittleEndian.Uint64(b[56:]))}
	at := 176
	if id == 266 {
		at = 216
	}
	ea, ad := int(binary.LittleEndian.Uint32(b[at-8:])), int(binary.LittleEndian.Uint32(b[at-4:]))
	if ea < 0 || ad < 0 || at+ea+ad > len(b) || f.size < 0 {
		return nil, errUDFUnreadable
	}
	ads := b[at+ea : at+ea+ad]
	if b[34]&7 == 3 {
		if int64(len(ads)) < f.size {
			return nil, errUDFUnreadable
		}
		f.embedded = ads[:f.size]
		return f, nil
	}
	long := b[34]&7 == 1
	if !long && b[34]&7 != 0 {
		return nil, errUDFUnreadable
	}
	total := int64(0)
	for hops := 0; ; {
		size := 8
		if long {
			size = 16
		}
		next := false
		for len(ads) >= size {
			var a udfAD
			if long {
				a = udfLongAD(ads)
			} else {
				raw := binary.LittleEndian.Uint32(ads)
				a = udfAD{length: int64(raw & 0x3FFFFFFF), kind: raw >> 30, block: binary.LittleEndian.Uint32(ads[4:]), part: part}
			}
			ads = ads[size:]
			if a.length == 0 {
				break
			}
			if a.kind == 3 {
				// The descriptors continue in an allocation extent descriptor.
				hops++
				if hops > udfMaxContinuing {
					return nil, errUDFUnreadable
				}
				aed, err := img.blockIn(a, physical)
				if err != nil {
					return nil, err
				}
				if _, ok := udfTag(aed, 258); !ok {
					return nil, errUDFUnreadable
				}
				n := int(binary.LittleEndian.Uint32(aed[20:]))
				if 24+n > len(aed) {
					return nil, errUDFUnreadable
				}
				ads, next = aed[24:24+n], true
				break
			}
			if a.kind != 0 || len(f.extents) >= udfMaxEntries {
				// Unrecorded ranges read as zeros on a disc; a clip has none.
				return nil, errUDFUnreadable
			}
			spans, err := img.spansIn(a, physical)
			if err != nil {
				return nil, err
			}
			f.extents = append(f.extents, spans...)
			total += a.length
		}
		if !next {
			break
		}
	}
	if total < f.size {
		return nil, errUDFUnreadable
	}
	// The last extent is rounded up to a block; keep exactly the file's bytes.
	for excess, i := total-f.size, len(f.extents)-1; excess > 0 && i >= 0; i-- {
		cut := min(excess, f.extents[i].length)
		f.extents[i].length -= cut
		excess -= cut
		if f.extents[i].length == 0 {
			f.extents = f.extents[:i]
		}
	}
	return f, nil
}

// spansIn and blockIn resolve a descriptor in a partition, or, while the
// metadata file itself is being read, in the physical partition at sector physical.
func (img *udfImage) spansIn(a udfAD, physical int64) ([]udfExtent, error) {
	if physical > 0 {
		e := udfExtent{(physical + int64(a.block)) * udfSector, a.length}
		if e.offset+e.length > img.size {
			return nil, errUDFUnreadable
		}
		return []udfExtent{e}, nil
	}
	return img.span(a.part, a.block, a.length)
}

func (img *udfImage) blockIn(a udfAD, physical int64) ([]byte, error) {
	if physical > 0 {
		return img.read((physical+int64(a.block))*udfSector, udfSector)
	}
	return img.block(udfAD{block: a.block, part: a.part}, udfSector)
}

func (img *udfImage) file(icb udfAD) (*udfFile, error) {
	b, err := img.block(icb, udfSector)
	if err != nil {
		return nil, err
	}
	return img.entry(b, icb.part, 0)
}

func (img *udfImage) data(f *udfFile, limit int64) ([]byte, error) {
	if f.embedded != nil {
		return f.embedded, nil
	}
	if f.size > limit {
		return nil, errUDFUnreadable
	}
	out := make([]byte, 0, f.size)
	for _, e := range f.extents {
		b, err := img.read(e.offset, int(e.length))
		if err != nil {
			return nil, err
		}
		out = append(out, b...)
	}
	return out, nil
}

// list returns a directory's entries, without its parent and deleted ones.
func (img *udfImage) list(dir *udfFile) ([]udfEntry, error) {
	b, err := img.data(dir, udfMaxDirBytes)
	if err != nil {
		return nil, err
	}
	var out []udfEntry
	for len(b) >= 38 && len(out) < udfMaxEntries {
		if _, ok := udfTag(b, 257); !ok {
			return nil, errUDFUnreadable
		}
		chars, nameLen, implLen := b[18], int(b[19]), int(binary.LittleEndian.Uint16(b[36:]))
		size := (38 + implLen + nameLen + 3) &^ 3
		if 38+implLen+nameLen > len(b) {
			return nil, errUDFUnreadable
		}
		if chars&(4|8) == 0 && nameLen > 0 {
			out = append(out, udfEntry{udfName(b[38+implLen : 38+implLen+nameLen]), chars&2 != 0, udfLongAD(b[20:])})
		}
		if size > len(b) {
			break
		}
		b = b[size:]
	}
	return out, nil
}

// udfName decodes an OSTA compressed Unicode d-string: one byte or two per character.
func udfName(b []byte) string {
	switch b[0] {
	case 8, 254:
		r := make([]rune, len(b)-1)
		for i, c := range b[1:] {
			r[i] = rune(c)
		}
		return string(r)
	case 16, 255:
		u := make([]uint16, (len(b)-1)/2)
		for i := range u {
			u[i] = binary.BigEndian.Uint16(b[1+2*i:])
		}
		return string(utf16.Decode(u))
	}
	return ""
}

func (img *udfImage) find(dir *udfFile, name string) (*udfFile, error) {
	entries, err := img.list(dir)
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if strings.EqualFold(e.name, name) {
			return img.file(e.icb)
		}
	}
	return nil, nil
}

// isoClip opens a Blu-ray disc image and returns its largest BDMV/STREAM clip
// as a Content over image ranges. Selection treats it like any other video.
func isoClip(ctx context.Context, iso *Content) (*Content, error) {
	r := iso.Reader(ctx, 0)
	defer r.Close()
	img, err := openUDF(contentAt{r}, iso.Size)
	if err != nil {
		return nil, err
	}
	root, err := img.file(img.root)
	if err != nil {
		return nil, err
	}
	bdmv, err := img.find(root, "BDMV")
	if err != nil {
		return nil, err
	}
	var stream *udfFile
	if bdmv != nil {
		if stream, err = img.find(bdmv, "STREAM"); err != nil {
			return nil, err
		}
	}
	if stream == nil {
		if dvd, _ := img.find(root, "VIDEO_TS"); dvd != nil {
			return nil, errDVDImage
		}
		return nil, errNoBluray
	}
	entries, err := img.list(stream)
	if err != nil {
		return nil, err
	}
	sort.SliceStable(entries, func(i, j int) bool { return entries[i].name < entries[j].name })
	var best *udfFile
	bestName := ""
	for _, e := range entries {
		if e.dir || !strings.EqualFold(pathExtension(e.name), ".m2ts") {
			continue
		}
		f, err := img.file(e.icb)
		if err != nil {
			return nil, err
		}
		if best == nil || f.size > best.size {
			best, bestName = f, e.name
		}
	}
	if best == nil || best.embedded != nil || best.size == 0 {
		return nil, errNoBluray
	}
	clip, err := imageFile(iso, iso.Name+"/BDMV/STREAM/"+bestName, best)
	if err != nil {
		return nil, err
	}
	// The clip's CLPI names its stream languages; it is served beside the clip.
	if dir, _ := img.find(bdmv, "CLIPINF"); dir != nil {
		stem := strings.TrimSuffix(bestName, pathExtension(bestName))
		if f, _ := img.find(dir, stem+".clpi"); f != nil && f.embedded == nil && f.size > 0 && f.size <= maxClipInfoBytes {
			clip.clipInfo, _ = imageFile(iso, iso.Name+"/BDMV/CLIPINF/"+stem+".clpi", f)
		}
	}
	clip.disc = imageDisc(img, iso, clip, bdmv, stream)
	return clip, nil
}

// imageDisc lists a disc image's playlists, clips and CLPI from its directories.
// Each file is mapped when first asked for, through a reader of its own, so
// selection reads only the directories.
func imageDisc(img *udfImage, iso, clip *Content, bdmv, stream *udfFile) *discFiles {
	d := newDiscFiles()
	d.add(clip.Name, clip)
	if clip.clipInfo != nil {
		d.add(clip.clipInfo.Name, clip.clipInfo)
	}
	playlist, _ := img.find(bdmv, "PLAYLIST")
	clipinf, _ := img.find(bdmv, "CLIPINF")
	for _, dir := range []struct {
		file      *udfFile
		name, ext string
	}{{playlist, "PLAYLIST", ".mpls"}, {clipinf, "CLIPINF", ".clpi"}, {stream, "STREAM", ".m2ts"}} {
		if dir.file == nil {
			continue
		}
		entries, err := img.list(dir.file)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if e.dir || !strings.EqualFold(pathExtension(e.name), dir.ext) {
				continue
			}
			name, icb, small := iso.Name+"/BDMV/"+dir.name+"/"+e.name, e.icb, dir.ext != ".m2ts"
			if _, rel, _ := discPath(name); d.get(rel) != nil {
				continue
			}
			d.addLazy(name, func(ctx context.Context) (*Content, []byte, error) {
				r := iso.Reader(ctx, 0)
				defer r.Close()
				own := *img
				own.r = contentAt{r}
				f, err := own.file(icb)
				if err != nil || f.size == 0 || (small && f.size > maxClipInfoBytes) {
					return nil, nil, err
				}
				if small {
					// Read through the image's chunks: the playlists sit together.
					b, err := own.data(f, maxClipInfoBytes)
					return nil, b, err
				}
				if f.embedded != nil {
					return nil, nil, nil
				}
				c, err := imageFile(iso, name, f)
				return c, nil, err
			})
		}
	}
	return d
}

// imageFile serves a file of the image as byte ranges of it.
func imageFile(iso *Content, name string, f *udfFile) (*Content, error) {
	children := make([]nestedChild, 0, len(f.extents))
	start := int64(0)
	for _, e := range f.extents {
		if n := len(children); n > 0 && children[n-1].offset+children[n-1].length == e.offset {
			children[n-1].length += e.length
		} else {
			children = append(children, nestedChild{offset: e.offset, length: e.length, start: start, verified: true})
		}
		start += e.length
	}
	if start != f.size {
		return nil, fmt.Errorf("%w: file extents", errUDFUnreadable)
	}
	return &Content{Name: name, Size: f.size, nested: &nestedRAR{vols: []*Content{iso}}, children: children}, nil
}

func isISO(name string) bool {
	return strings.EqualFold(pathExtension(name), ".iso")
}
