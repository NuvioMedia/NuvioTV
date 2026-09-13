package unpack

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/binary"
	"fmt"
	"testing"
)

// readTrackingFile fails the test if anything reads it, proving that a normal
// release costs no extra fetches.
type readTrackingFile struct {
	*memoryUnpackableFile
	t *testing.T
}

func (f *readTrackingFile) ReadAt(p []byte, off int64) (int, error) {
	f.t.Fatalf("deobfuscation read %q, but its subject already names it", f.name)
	return 0, nil
}

func TestResolveObfuscatedNamesLeavesNamedReleaseUntouched(t *testing.T) {
	files := []UnpackableFile{
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: "Show.S01E01.1080p.part01.rar"}, t: t},
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: "Show.S01E01.1080p.part02.rar"}, t: t},
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: "Show.S01E01.1080p.par2"}, t: t},
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: "Show.S01E01.1080p.nfo"}, t: t},
	}

	out := resolveObfuscatedNames(context.Background(), files)
	for i := range files {
		if out[i] != files[i] {
			t.Fatalf("file %d was rewrapped: %q", i, out[i].Name())
		}
	}
}

// par2IndexWithFileDesc builds a PAR2 index file describing each (name, data)
// pair by its length and the MD5 of its first 16 KiB — the identity PAR2 name
// recovery matches on.
func par2IndexWithFileDesc(entries map[string][]byte) []byte {
	var buf bytes.Buffer
	id := byte(1)
	for name, data := range entries {
		fileID := [16]byte{id}
		id++

		var body bytes.Buffer
		body.Write(fileID[:])
		body.Write(make([]byte, 16)) // full-file MD5, unused here
		hash16k := md5.Sum(hashable16k(data))
		body.Write(hash16k[:])
		var length [8]byte
		binary.LittleEndian.PutUint64(length[:], uint64(len(data)))
		body.Write(length[:])
		body.WriteString(name + "\x00")

		writePAR2Packet(&buf, par2FileDescPacket, body.Bytes())
	}
	return buf.Bytes()
}

func TestResolveObfuscatedNamesFromPAR2FileDesc(t *testing.T) {
	media := bytes.Repeat([]byte("M"), 20<<10)
	par2 := par2IndexWithFileDesc(map[string][]byte{"Real.Movie.2024.1080p.BluRay.mkv": media})

	files := []UnpackableFile{
		&memoryUnpackableFile{name: `[1/2] - "a3f9c1e2b8d47a56" yEnc (1/1)`, data: media},
		&memoryUnpackableFile{name: `[2/2] - "a3f9c1e2b8d47a56.par2" yEnc (1/1)`, data: par2},
	}

	out := resolveObfuscatedNames(context.Background(), files)
	if got := ExtractFilename(out[0].Name()); got != "Real.Movie.2024.1080p.BluRay.mkv" {
		t.Fatalf("expected the PAR2 filename, got %q", got)
	}
	if got := originalName(out[0]); got != files[0].Name() {
		t.Fatalf("original subject not preserved: %q", got)
	}
	if out[1] != files[1] {
		t.Fatalf("the .par2 file should keep its own name, got %q", out[1].Name())
	}
}

// yencNamedFile reports a filename the way a segment-backed file reads it out
// of the article's yEnc header.
type yencNamedFile struct {
	*memoryUnpackableFile
	yenc string
}

func (f *yencNamedFile) YencFileName(context.Context) (string, error) { return f.yenc, nil }

func TestResolveObfuscatedNamesFromYencHeader(t *testing.T) {
	files := []UnpackableFile{
		&yencNamedFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: `[1/1] - "7e6d5c4b3a291807" yEnc (1/1)`, data: bytes.Repeat([]byte("x"), 4096)},
			yenc:                 "Show.S01E02.1080p.WEB-DL.mkv",
		},
	}

	out := resolveObfuscatedNames(context.Background(), files)
	if got := ExtractFilename(out[0].Name()); got != "Show.S01E02.1080p.WEB-DL.mkv" {
		t.Fatalf("expected the yEnc filename, got %q", got)
	}
}

func TestResolveObfuscatedNamesRejectsObfuscatedRecoveredNames(t *testing.T) {
	// A poster who renamed the files before building the PAR2 set leaves the
	// same hashes in the recovery data. Signature naming must take over rather
	// than swapping one meaningless name for another.
	media := append([]byte{0x1A, 0x45, 0xDF, 0xA3}, bytes.Repeat([]byte("M"), 4096)...)
	par2 := par2IndexWithFileDesc(map[string][]byte{"0123456789abcdef0123456789abcdef.mkv": media})

	files := []UnpackableFile{
		&memoryUnpackableFile{name: `"aabbccdd11223344"`, data: media},
		&memoryUnpackableFile{name: `"aabbccdd11223344.par2"`, data: par2},
	}

	out := resolveObfuscatedNames(context.Background(), files)
	if got := ExtractFilename(out[0].Name()); got != "aabbccdd11223344.mkv" {
		t.Fatalf("expected a signature-derived name, got %q", got)
	}
}

// rar5MainHeader builds the signature and main archive header of one RAR5
// volume. volume is 0-based; the first volume omits the number field entirely,
// exactly as the format specifies.
func rar5MainHeader(volume int) []byte {
	var b bytes.Buffer
	b.Write(rar5Signature)
	b.Write([]byte{0, 0, 0, 0}) // header CRC32
	b.WriteByte(8)              // header size
	b.WriteByte(1)              // header type: main archive header
	b.WriteByte(0)              // header flags
	if volume == 0 {
		b.WriteByte(0x01) // archive flags: volume set, no volume number field
	} else {
		b.WriteByte(0x03) // archive flags: volume set + volume number present
		b.WriteByte(byte(volume))
	}
	b.Write(bytes.Repeat([]byte{0}, 64))
	return b.Bytes()
}

func TestResolveObfuscatedNamesOrdersRARVolumesFromHeaders(t *testing.T) {
	// NZB order deliberately does not match volume order.
	files := []UnpackableFile{
		&memoryUnpackableFile{name: `"9c0d1e2f3a4b5c6d"`, data: rar5MainHeader(2)},
		&memoryUnpackableFile{name: `"1122334455667788"`, data: append([]byte(nil), par2PacketMagic...)},
		&memoryUnpackableFile{name: `"3f8a91b2c7d6e5f4"`, data: rar5MainHeader(0)},
		&memoryUnpackableFile{name: `"abcdefabcdefabcd"`, data: rar5MainHeader(1)},
	}

	out := resolveObfuscatedNames(context.Background(), files)
	want := []string{
		fmt.Sprintf("%s.part003%s", synthesizedStem, ExtRar),
		fmt.Sprintf("%s.001%s", synthesizedStem, ExtPar2),
		fmt.Sprintf("%s.part001%s", synthesizedStem, ExtRar),
		fmt.Sprintf("%s.part002%s", synthesizedStem, ExtRar),
	}
	for i, expected := range want {
		if got := ExtractFilename(out[i].Name()); got != expected {
			t.Fatalf("file %d: got %q, want %q", i, got, expected)
		}
	}

	// The recovery file must never be mistaken for archive content, and the
	// first volume must be the one the scanner opens the set with.
	rars := filterRarFiles(out)
	if len(rars) != 3 {
		t.Fatalf("expected 3 RAR volumes, got %d", len(rars))
	}
	firsts := filterFirstVolumes(rars)
	if len(firsts) != 1 || GetRARVolumeNumber(firsts[0].Name()) != 1 {
		t.Fatalf("expected part001 as the only first volume, got %v", firsts)
	}
}

func TestParseRAR4MainHeaderFlagsFirstVolume(t *testing.T) {
	header := func(flags uint16) []byte {
		var b bytes.Buffer
		b.Write(rar4Signature)
		b.Write([]byte{0, 0}) // HEAD_CRC
		b.WriteByte(0x73)     // MAIN_HEAD
		var f [2]byte
		binary.LittleEndian.PutUint16(f[:], flags)
		b.Write(f[:])
		b.Write([]byte{13, 0}) // HEAD_SIZE
		b.Write(bytes.Repeat([]byte{0}, 16))
		return b.Bytes()
	}

	first, ok := parseRARHeader(header(0x0001 | 0x0100))
	if !ok || !first.isFirst {
		t.Fatalf("expected the first volume flag to be read, got %+v (ok=%v)", first, ok)
	}
	middle, ok := parseRARHeader(header(0x0001))
	if !ok || middle.isFirst {
		t.Fatalf("expected a continuation volume, got %+v (ok=%v)", middle, ok)
	}
	if middle.volume != -1 {
		t.Fatalf("RAR4 states no volume number, got %d", middle.volume)
	}
}

func TestGetMediaStreamPlaysFullyObfuscatedReleaseNamedByPAR2(t *testing.T) {
	media := append([]byte{0x1A, 0x45, 0xDF, 0xA3}, bytes.Repeat([]byte("M"), 20<<10)...)
	par2 := par2IndexWithFileDesc(map[string][]byte{"Real.Show.S01E03.1080p.WEB-DL.mkv": media})

	files := []UnpackableFile{
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: `[1/2] - "c7d6e5f43f8a91b2" yEnc (1/300)`, data: media},
			size:                 80 * 1024 * 1024,
		},
		&sizedUnpackableFile{
			memoryUnpackableFile: &memoryUnpackableFile{name: `[2/2] - "9b8a7c6d5e4f3a2b" yEnc (1/1)`, data: par2},
			size:                 90 * 1024 * 1024,
		},
	}

	stream, name, _, bp, err := GetMediaStreamForEpisode(
		context.Background(), files, nil, "", EpisodeTarget{Season: 1, Episode: 3})
	if err != nil {
		t.Fatalf("obfuscated release did not resolve: %v", err)
	}
	defer stream.Close()

	if name != "Real.Show.S01E03.1080p.WEB-DL.mkv" {
		t.Fatalf("expected the PAR2-recovered media name, got %q", name)
	}
	// The PAR2 file is the larger of the two: only content-based exclusion keeps
	// it out of the running.
	direct, ok := bp.(*DirectBlueprint)
	if !ok {
		t.Fatalf("expected a direct blueprint, got %T", bp)
	}
	if direct.FileIndex != 0 {
		t.Fatalf("expected the media file at index 0, got %d", direct.FileIndex)
	}
}

// mappedFile models a segment-backed file: ReadAt is only legal once the whole
// file has been mapped, which is what makes reading a header via ReadAt so
// expensive in production.
type mappedFile struct {
	*memoryUnpackableFile
	t      *testing.T
	mapped bool
	reads  int
	firsts int
}

func (f *mappedFile) EnsureSegmentMap() error { f.mapped = true; return nil }

func (f *mappedFile) ReadAt(p []byte, off int64) (int, error) {
	if !f.mapped {
		f.t.Fatalf("ReadAt on %q without a segment map", f.name)
	}
	f.reads++
	return f.memoryUnpackableFile.ReadAt(p, off)
}

func (f *mappedFile) ReadFirstSegment(context.Context) ([]byte, error) {
	f.firsts++
	data := f.data
	if len(data) > contentSignatureBytes {
		data = data[:contentSignatureBytes]
	}
	return data, nil
}

func TestDeobfuscationReadsOnlyTheFirstSegment(t *testing.T) {
	// Mapping a file means probing every one of its segments. Identifying a
	// release must cost one article per file, not a full map of each.
	files := []UnpackableFile{
		&mappedFile{memoryUnpackableFile: &memoryUnpackableFile{name: `"3f8a91b2c7d6e5f4"`, data: rar5MainHeader(0)}, t: t},
		&mappedFile{memoryUnpackableFile: &memoryUnpackableFile{name: `"9c0d1e2f3a4b5c6d"`, data: rar5MainHeader(1)}, t: t},
		&mappedFile{memoryUnpackableFile: &memoryUnpackableFile{name: `"1122334455667788"`, data: append([]byte(nil), par2PacketMagic...)}, t: t},
	}

	resolveObfuscatedNames(context.Background(), files)

	for _, f := range files {
		m := f.(*mappedFile)
		if m.mapped {
			t.Fatalf("%q was segment-mapped just to read its header", m.name)
		}
		if m.firsts != 1 {
			t.Fatalf("%q: expected exactly 1 first-segment read, got %d", m.name, m.firsts)
		}
	}
}

func TestUsableFilenameIgnoresHashedAuxiliaryStems(t *testing.T) {
	// A hashed stem on a PAR2 or NFO says nothing worth recovering — only the
	// extension matters for those — so it must not drag the file into a read.
	// The payload's stem still counts: that one has to be episode-matchable.
	cases := []struct {
		name   string
		usable bool
	}{
		{"3d02d1eb4aa11502234691f0a21c7250.par2", true},
		{"3d02d1eb4aa11502234691f0a21c7250.vol00-01.par2", true},
		{"3d02d1eb4aa11502234691f0a21c7250.part01.rar", true},
		{"tjrtajrykjrwji5rikr5.nzb", true},
		{"Show.S01E01.1080p.mkv", true},
		{"0123456789abcdef0123456789abcdef.mkv", false},
		{"3f8a91b2c7d6e5f4", false},
	}
	for _, tc := range cases {
		if got := isUsableFilename(tc.name); got != tc.usable {
			t.Errorf("isUsableFilename(%q) = %v, want %v", tc.name, got, tc.usable)
		}
	}
}

func TestResolveObfuscatedNamesSkipsHashedButClassifiedRelease(t *testing.T) {
	// A hashed-stem release with real extensions routes fine on its subjects
	// alone. Not one file may be read, including the PAR2 index whose bare
	// 32-hex stem is the most hash-like name in the set.
	files := []UnpackableFile{
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: `[01/4] - "3d02d1eb4aa11502234691f0a21c7250.par2" yEnc (1/1)`}, t: t},
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: `[02/4] - "3d02d1eb4aa11502234691f0a21c7250.part01.rar" yEnc (1/147)`}, t: t},
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: `[03/4] - "3d02d1eb4aa11502234691f0a21c7250.part02.rar" yEnc (1/147)`}, t: t},
		&readTrackingFile{memoryUnpackableFile: &memoryUnpackableFile{name: `[04/4] - "3d02d1eb4aa11502234691f0a21c7250.vol00-01.par2" yEnc (1/8)`}, t: t},
	}

	out := resolveObfuscatedNames(context.Background(), files)
	for i := range files {
		if out[i] != files[i] {
			t.Fatalf("file %d was rewrapped: %q", i, out[i].Name())
		}
	}
}
