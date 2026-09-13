package unpack

import (
	"context"
	"io"
	"testing"
)

// stubFile is a minimal UnpackableFile for blueprint (de)serialization tests.
type stubFile struct {
	name string
	size int64
}

func (f *stubFile) Name() string                                             { return f.name }
func (f *stubFile) Size() int64                                              { return f.size }
func (f *stubFile) EnsureSegmentMap() error                                  { return nil }
func (f *stubFile) OpenStream() (io.ReadSeekCloser, error)                   { return nil, nil }
func (f *stubFile) OpenStreamCtx(context.Context) (io.ReadSeekCloser, error) { return nil, nil }
func (f *stubFile) OpenReaderAt(context.Context, int64) (io.ReadCloser, error) {
	return nil, nil
}
func (f *stubFile) ReadAt([]byte, int64) (int, error) { return 0, nil }

func TestSerializeRehydrateArchiveBlueprintRoundTrip(t *testing.T) {
	vol1 := &stubFile{name: "xxx.part01.rar", size: 1000}
	vol2 := &stubFile{name: "xxx.part02.rar", size: 1000}
	orig := &ArchiveBlueprint{
		MainFileName: "movie.mkv",
		TotalSize:    2000,
		Target:       EpisodeTarget{Season: 1, Episode: 3},
		Parts: []VirtualPartDef{
			{VirtualStart: 0, VirtualEnd: 999, VolFile: vol1, VolOffset: 50},
			{VirtualStart: 1000, VirtualEnd: 1999, VolFile: vol2, VolOffset: 60},
		},
	}

	data, ok := SerializeArchiveBlueprint(orig)
	if !ok {
		t.Fatal("expected plaintext RAR blueprint to serialize")
	}

	// Fresh files (new handles), matched by name, possibly in a different order.
	fresh := []UnpackableFile{
		&stubFile{name: "xxx.part02.rar", size: 1000},
		&stubFile{name: "other.rar", size: 5},
		&stubFile{name: "xxx.part01.rar", size: 1000},
	}
	bp, ok := RehydrateArchiveBlueprint(data, fresh)
	if !ok {
		t.Fatal("expected rehydrate to succeed")
	}
	if bp.MainFileName != "movie.mkv" || bp.TotalSize != 2000 {
		t.Fatalf("header not restored: %+v", bp)
	}
	if bp.Target != (EpisodeTarget{Season: 1, Episode: 3}) {
		t.Fatalf("target not restored: %+v", bp.Target)
	}
	if len(bp.Parts) != 2 {
		t.Fatalf("expected 2 parts, got %d", len(bp.Parts))
	}
	if bp.Parts[0].VolFile.Name() != "xxx.part01.rar" || bp.Parts[0].VolOffset != 50 {
		t.Fatalf("part0 mis-linked: %+v", bp.Parts[0])
	}
	if bp.Parts[1].VolFile.Name() != "xxx.part02.rar" || bp.Parts[1].VolOffset != 60 {
		t.Fatalf("part1 mis-linked: %+v", bp.Parts[1])
	}
	// The rehydrated part must reference the FRESH handle, not the original.
	if bp.Parts[0].VolFile == vol1 {
		t.Fatal("expected a freshly-linked volume handle, got the original")
	}
}

func TestRehydrateFailsClosedOnMissingVolume(t *testing.T) {
	orig := &ArchiveBlueprint{
		MainFileName: "movie.mkv",
		TotalSize:    1000,
		Parts:        []VirtualPartDef{{VirtualEnd: 999, VolFile: &stubFile{name: "a.part01.rar"}}},
	}
	data, ok := SerializeArchiveBlueprint(orig)
	if !ok {
		t.Fatal("serialize failed")
	}
	// No matching volume -> must fall back (nil,false), never a partial blueprint.
	if _, ok := RehydrateArchiveBlueprint(data, []UnpackableFile{&stubFile{name: "different.rar"}}); ok {
		t.Fatal("expected rehydrate to fail closed when a volume is missing")
	}
}

func TestSerializeSkipsEncryptedAndCompressed(t *testing.T) {
	enc := &ArchiveBlueprint{MainFileName: "m", AnyEncrypted: true, Parts: []VirtualPartDef{{VolFile: &stubFile{name: "x"}}}}
	if _, ok := SerializeArchiveBlueprint(enc); ok {
		t.Error("encrypted blueprint must not be serialized for reuse")
	}
	comp := &ArchiveBlueprint{MainFileName: "m", IsCompressed: true, Parts: []VirtualPartDef{{VolFile: &stubFile{name: "x"}}}}
	if _, ok := SerializeArchiveBlueprint(comp); ok {
		t.Error("compressed blueprint must not be serialized for reuse")
	}
	if _, ok := SerializeArchiveBlueprint(&DirectBlueprint{}); ok {
		t.Error("non-RAR blueprint must not be serialized by SerializeArchiveBlueprint")
	}
}

// mappedStubFile is a volume that carries a segment map snapshot, the way a
// loader file does.
type mappedStubFile struct {
	stubFile
	snapshot []byte
	restored []byte
	accept   bool
}

func (f *mappedStubFile) SegmentMapSnapshotJSON() ([]byte, bool) {
	if len(f.snapshot) == 0 {
		return nil, false
	}
	return f.snapshot, true
}

func (f *mappedStubFile) RestoreSegmentMapJSON(data []byte) bool {
	f.restored = data
	return f.accept
}

// The segment map is what a seek into a volume needs before it can read a
// single byte, and rebuilding it costs NNTP round trips. It has to survive in
// the blueprint alongside the part offsets.
func TestBlueprintCarriesSegmentMapsAcrossSessions(t *testing.T) {
	vol1 := &mappedStubFile{stubFile: stubFile{name: "xxx.part01.rar", size: 1000}, snapshot: []byte(`{"segments":2}`)}
	// The tail volume was never mapped in this session: it simply contributes
	// nothing, and must not stop the rest from being carried.
	vol2 := &mappedStubFile{stubFile: stubFile{name: "xxx.part02.rar", size: 1000}}
	orig := &ArchiveBlueprint{
		MainFileName: "movie.mkv",
		TotalSize:    2000,
		Parts: []VirtualPartDef{
			{VirtualStart: 0, VirtualEnd: 999, VolFile: vol1},
			{VirtualStart: 1000, VirtualEnd: 1999, VolFile: vol2},
		},
	}
	data, ok := SerializeArchiveBlueprint(orig)
	if !ok {
		t.Fatal("serialize failed")
	}

	fresh1 := &mappedStubFile{stubFile: stubFile{name: "xxx.part01.rar", size: 1000}, accept: true}
	fresh2 := &mappedStubFile{stubFile: stubFile{name: "xxx.part02.rar", size: 1000}, accept: true}
	if _, ok := RehydrateArchiveBlueprint(data, []UnpackableFile{fresh1, fresh2}); !ok {
		t.Fatal("rehydrate failed")
	}
	if string(fresh1.restored) != `{"segments":2}` {
		t.Fatalf("mapped volume got %q, want the persisted snapshot", fresh1.restored)
	}
	if fresh2.restored != nil {
		t.Fatalf("unmapped volume was handed %q", fresh2.restored)
	}
}

// A volume that refuses its snapshot must still rehydrate: it just probes.
func TestBlueprintRehydratesWhenSegmentMapRejected(t *testing.T) {
	orig := &ArchiveBlueprint{
		MainFileName: "movie.mkv",
		TotalSize:    1000,
		Parts: []VirtualPartDef{
			{VirtualEnd: 999, VolFile: &mappedStubFile{stubFile: stubFile{name: "a.part01.rar"}, snapshot: []byte(`{"segments":9}`)}},
		},
	}
	data, ok := SerializeArchiveBlueprint(orig)
	if !ok {
		t.Fatal("serialize failed")
	}
	fresh := &mappedStubFile{stubFile: stubFile{name: "a.part01.rar"}, accept: false}
	if _, ok := RehydrateArchiveBlueprint(data, []UnpackableFile{fresh}); !ok {
		t.Fatal("a rejected segment map must not fail the blueprint")
	}
}

// A direct release's plan is cheap to rebuild; its segment map is not. The
// serialized form exists to carry the map, so a replay must not have to
// re-measure the file.
func TestDirectBlueprintCarriesItsSegmentMap(t *testing.T) {
	media := &mappedStubFile{stubFile: stubFile{name: "movie.mkv", size: 100}, snapshot: []byte(`{"segments":4488}`)}
	other := &mappedStubFile{stubFile: stubFile{name: "sample.mkv", size: 10}, snapshot: []byte(`{"segments":2}`)}
	orig := &DirectBlueprint{FileName: "movie.mkv", FileIndex: 1, Target: EpisodeTarget{Season: 2, Episode: 5}}

	data, ok := SerializeBlueprint(orig, []UnpackableFile{other, media})
	if !ok {
		t.Fatal("expected a direct blueprint to serialize")
	}

	// Replay: same names, different order, fresh handles.
	freshMedia := &mappedStubFile{stubFile: stubFile{name: "movie.mkv", size: 100}, accept: true}
	freshOther := &mappedStubFile{stubFile: stubFile{name: "sample.mkv", size: 10}, accept: true}
	bp, ok := RehydrateBlueprint(data, []UnpackableFile{freshMedia, freshOther})
	if !ok {
		t.Fatal("expected rehydrate to succeed")
	}
	direct, ok := bp.(*DirectBlueprint)
	if !ok {
		t.Fatalf("rehydrated %T, want *DirectBlueprint", bp)
	}
	if direct.FileName != "movie.mkv" || direct.Target != (EpisodeTarget{Season: 2, Episode: 5}) {
		t.Fatalf("plan not restored: %+v", direct)
	}
	// The index must follow the name into the new list, or Open reads the
	// wrong file.
	if direct.FileIndex != 0 {
		t.Fatalf("file index %d, want 0 (re-linked by name)", direct.FileIndex)
	}
	if string(freshMedia.restored) != `{"segments":4488}` {
		t.Fatalf("media got %q, want the persisted snapshot", freshMedia.restored)
	}
	if freshOther.restored != nil {
		t.Fatalf("a file the blueprint does not play was handed %q", freshOther.restored)
	}
}

func TestRehydrateBlueprintRejectsUnknownForms(t *testing.T) {
	files := []UnpackableFile{&stubFile{name: "movie.mkv"}}
	// A legacy row: DirectBlueprint marshalled raw, with no kind.
	legacy := []byte(`{"FileName":"movie.mkv","FileIndex":0,"Target":{}}`)
	if _, ok := RehydrateBlueprint(legacy, files); ok {
		t.Fatal("a form with no kind must fall back to a rescan")
	}
	if _, ok := RehydrateBlueprint([]byte("{}"), files); ok {
		t.Fatal("an empty object is not a blueprint")
	}
	if _, ok := RehydrateBlueprint(nil, files); ok {
		t.Fatal("no data is not a blueprint")
	}
}

func TestSerializeBlueprintSkipsUnreusableKinds(t *testing.T) {
	if _, ok := SerializeBlueprint(&SevenZipBlueprint{}, nil); ok {
		t.Error("a 7z set re-scans on replay; it must not claim a reusable form")
	}
	if _, ok := SerializeBlueprint(&FailedBlueprint{}, nil); ok {
		t.Error("a recorded failure is not a reusable plan")
	}
	if _, ok := SerializeBlueprint(&DirectBlueprint{}, nil); ok {
		t.Error("a direct blueprint with no file name cannot be re-linked")
	}
}
