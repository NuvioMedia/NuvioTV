package rardecode

import (
	"encoding/binary"
	"errors"
	"hash/crc32"
	"testing"
	"testing/fstest"
)

// The helpers below emit a minimal RAR5 archive: signature, main archive
// header, one stored file block and an end-of-archive marker. Enough to
// exercise volume traversal without shipping binary fixtures.

// rar5Signature is the "Rar!\x1A\x07" prefix plus the RAR5 version marker.
func rar5Signature() []byte {
	return append([]byte(sigPrefix), 0x01, 0x00)
}

func vint(v uint64) []byte {
	var out []byte
	for {
		b := byte(v & 0x7f)
		v >>= 7
		if v == 0 {
			return append(out, b)
		}
		out = append(out, b|0x80)
	}
}

// rar5Block frames a header: crc32 over (size vint || body), then both.
func rar5Block(htype, flags uint64, dataSize int64, body []byte) []byte {
	var b []byte
	b = append(b, vint(htype)...)
	b = append(b, vint(flags)...)
	if flags&block5HasData != 0 {
		b = append(b, vint(uint64(dataSize))...)
	}
	b = append(b, body...)

	framed := append(vint(uint64(len(b))), b...)
	out := make([]byte, 4, 4+len(framed))
	binary.LittleEndian.PutUint32(out, crc32.ChecksumIEEE(framed))
	return append(out, framed...)
}

func rar5MainHeader(volNum uint64) []byte {
	// archive flags: volume | volume number present
	body := append(vint(0x03), vint(volNum)...)
	return rar5Block(block5Arc, 0, 0, body)
}

// rar5StoredFile emits a stored (uncompressed) file block. unpackedSize is the
// whole file's size, repeated in every volume that carries part of it, exactly
// as WinRAR writes it.
func rar5StoredFile(name string, unpackedSize int64, data []byte, notFirst, notLast bool) []byte {
	var body []byte
	body = append(body, vint(0)...)                    // file flags: no mtime, no crc
	body = append(body, vint(uint64(unpackedSize))...) // total unpacked size
	body = append(body, vint(0)...)                    // attributes
	body = append(body, vint(0)...)                    // compression info: stored
	body = append(body, vint(0)...)                    // host OS
	body = append(body, vint(uint64(len(name)))...)
	body = append(body, name...)

	flags := uint64(block5HasData)
	if notFirst {
		flags |= block5DataNotFirst
	}
	if notLast {
		flags |= block5DataNotLast
	}
	return append(rar5Block(block5File, flags, int64(len(data)), body), data...)
}

func rar5EndOfArchive(moreVolumes bool) []byte {
	var flags uint64
	if moreVolumes {
		flags = 1 // endArc5NotLast
	}
	return rar5Block(block5End, 0, 0, vint(flags))
}

// twoVolumeSet builds one stored file split evenly across two volumes.
func twoVolumeSet(t *testing.T, name string, payload []byte) (vol1, vol2 []byte) {
	t.Helper()
	half := len(payload) / 2
	total := int64(len(payload))

	vol1 = append(vol1, rar5Signature()...)
	vol1 = append(vol1, rar5MainHeader(0)...)
	vol1 = append(vol1, rar5StoredFile(name, total, payload[:half], false, true)...)
	vol1 = append(vol1, rar5EndOfArchive(true)...)

	vol2 = append(vol2, rar5Signature()...)
	vol2 = append(vol2, rar5MainHeader(1)...)
	vol2 = append(vol2, rar5StoredFile(name, total, payload[half:], true, false)...)
	vol2 = append(vol2, rar5EndOfArchive(false)...)
	return vol1, vol2
}

func mountOnly(volName string, data []byte) fstest.MapFS {
	return fstest.MapFS{volName: &fstest.MapFile{Data: data}}
}

// A first volume lists on its own today; this pins that it keeps working.
func TestListFirstVolumeStandalone(t *testing.T) {
	payload := []byte("0123456789abcdefghij")
	vol1, _ := twoVolumeSet(t, "inner.bin", payload)

	infos, err := ListArchiveInfo("set.part1.rar",
		FileSystem(mountOnly("set.part1.rar", vol1)),
		SkipVolumeCheck, ListTolerant)
	if err != nil {
		t.Fatalf("listing the first volume failed: %v", err)
	}
	if len(infos) != 1 || infos[0].Name != "inner.bin" {
		t.Fatalf("expected inner.bin, got %+v", infos)
	}
	if infos[0].TotalUnpackedSize != int64(len(payload)) {
		t.Fatalf("expected total size %d, got %d", len(payload), infos[0].TotalUnpackedSize)
	}
}

// The regression: a continuation volume mounted alone used to fail outright,
// which is what made 39 of 40 volumes unscannable in a real release.
func TestListContinuationVolumeStandalone(t *testing.T) {
	payload := []byte("0123456789abcdefghij")
	_, vol2 := twoVolumeSet(t, "inner.bin", payload)
	fsys := mountOnly("set.part2.rar", vol2)

	if _, err := ListArchiveInfo("set.part2.rar",
		FileSystem(fsys), SkipVolumeCheck, ListTolerant); !errors.Is(err, ErrInvalidFileBlock) {
		t.Fatalf("expected ErrInvalidFileBlock without the opt-in, got %v", err)
	}

	infos, err := ListArchiveInfo("set.part2.rar",
		FileSystem(fsys), SkipVolumeCheck, ListTolerant, ListFromAnyVolume)
	if err != nil {
		t.Fatalf("listing the continuation volume failed: %v", err)
	}
	if len(infos) != 1 {
		t.Fatalf("expected exactly one entry, got %d: %+v", len(infos), infos)
	}
	if infos[0].Name != "inner.bin" {
		t.Fatalf("expected inner.bin, got %q", infos[0].Name)
	}
	// The name and the whole-file size are what callers need to identify which
	// inner volumes a given outer volume carries.
	if infos[0].TotalUnpackedSize != int64(len(payload)) {
		t.Fatalf("expected whole-file size %d, got %d", len(payload), infos[0].TotalUnpackedSize)
	}
	if got := infos[0].TotalPackedSize; got != int64(len(payload)/2) {
		t.Fatalf("expected only this volume's %d packed bytes, got %d", len(payload)/2, got)
	}
}
