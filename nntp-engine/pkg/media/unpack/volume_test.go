package unpack

import (
	"context"
	"io"
	"testing"
)

func TestGetRARVolumeNumber(t *testing.T) {
	tests := []struct {
		filename string
		expected int
	}{
		{"movie.part01.rar", 1},
		{"movie.part02.rar", 2},
		{"movie.part10.rar", 10},
		{"movie.rar", 0},
		{"movie.r00", 1},
		{"movie.r01", 2},
		{"movie.r99", 100},
		{"movie.s00", 101},
		{"movie.s01", 102},
		{"movie.s99", 200},
		{"movie.t00", 201},
		{"movie.z00", 801},
		{"movie.z99", 900},
		{"movie.mkv", -1},
		{"movie.txt", -1},
		// Bare numeric split volumes number by their extension...
		{"a9f3c2d1.001", 1},
		{"a9f3c2d1.002", 2},
		{"a9f3c2d1.037", 37},
		// ...but .7z.NNN belongs to the 7z path, and a year before a real
		// extension is not a volume.
		{"archive.7z.001", -1},
		{"movie.2001.mkv", -1},
	}

	for _, tt := range tests {
		got := GetRARVolumeNumber(tt.filename)
		if got != tt.expected {
			t.Errorf("GetRARVolumeNumber(%q) = %d; want %d", tt.filename, got, tt.expected)
		}
	}
}

func TestProbeConfigRidesTheContext(t *testing.T) {
	path, quick := probeConfigFrom(context.Background())
	if path != "" || quick {
		t.Fatalf("unset context should carry zero config, got %q/%v", path, quick)
	}
	ctx := WithProbeConfig(context.Background(), "/opt/ffprobe", true)
	path, quick = probeConfigFrom(ctx)
	if path != "/opt/ffprobe" || !quick {
		t.Fatalf("probeConfigFrom = %q/%v, want /opt/ffprobe/true", path, quick)
	}
	// Derived contexts — the nested-archive recursion — inherit it.
	path, quick = probeConfigFrom(WithNestDepth(ctx, 1))
	if path != "/opt/ffprobe" || !quick {
		t.Fatalf("derived context lost the probe config: %q/%v", path, quick)
	}
}

func TestSelectLargestStoredISOPrefersTheBigUncompressedImage(t *testing.T) {
	parts := []filePart{
		{name: "movie.iso", packedSize: 500, isCompressed: false},
		{name: "movie.iso", packedSize: 400, isCompressed: false}, // second volume of the same image
		{name: "sample-movie.iso", packedSize: 5000},
		{name: "other.iso", packedSize: 600, isCompressed: true},
		{name: "notes.nfo", packedSize: 10},
	}
	if got := selectLargestStoredISO(parts); got != "movie.iso" {
		t.Fatalf("selectLargestStoredISO = %q, want movie.iso", got)
	}
	if got := selectLargestStoredISO(nil); got != "" {
		t.Fatalf("empty parts should select nothing, got %q", got)
	}
}

func TestNumericSplitVolumeClassification(t *testing.T) {
	if IsMiddleRarVolume("a9f3c2d1.001") {
		t.Error(".001 opens the set and must not read as a middle volume")
	}
	if !IsMiddleRarVolume("a9f3c2d1.002") {
		t.Error(".002 is a continuation and must read as a middle volume")
	}
	if IsMiddleRarVolume("archive.7z.002") {
		t.Error("a .7z split part is not a RAR middle volume")
	}
	if got := archivePrefix("A9F3C2D1.001"); got != "a9f3c2d1" {
		t.Errorf("archivePrefix(A9F3C2D1.001) = %q, want a9f3c2d1", got)
	}
}

func TestGet7zVolumeNumber(t *testing.T) {
	tests := []struct {
		filename string
		expected int
	}{
		{"archive.7z.001", 1},
		{"archive.7z.002", 2},
		{"archive.7z", 0},
		{"archive.rar", -1},
	}

	for _, tt := range tests {
		got := Get7zVolumeNumber(tt.filename)
		if got != tt.expected {
			t.Errorf("Get7zVolumeNumber(%q) = %d; want %d", tt.filename, got, tt.expected)
		}
	}
}

func TestArchiveBaseName(t *testing.T) {
	tests := []struct {
		filename string
		wantBase string
		wantKind ArchiveKind
	}{
		{"Some.Movie.part01.rar", "Some.Movie", KindRAR},
		{"Some.Movie.r00", "Some.Movie", KindRAR},
		{"Some.Movie.s05", "Some.Movie", KindRAR},
		{"Some.Movie.rar", "Some.Movie", KindRAR},
		{"Release.7z.001", "Release", Kind7z},
		{"Release.7z", "Release", Kind7z},
	}

	for _, tt := range tests {
		res := ArchiveBaseName(tt.filename)
		if res == nil {
			t.Fatalf("ArchiveBaseName(%q) returned nil; want base %q kind %q", tt.filename, tt.wantBase, tt.wantKind)
		}
		if res.Base != tt.wantBase || res.Kind != tt.wantKind {
			t.Errorf("ArchiveBaseName(%q) = {Base: %q, Kind: %q}; want {Base: %q, Kind: %q}",
				tt.filename, res.Base, res.Kind, tt.wantBase, tt.wantKind)
		}
	}
}

type dummyUnpackable struct {
	name string
	size int64
}

func (d *dummyUnpackable) Name() string                           { return d.name }
func (d *dummyUnpackable) Size() int64                            { return d.size }
func (d *dummyUnpackable) EnsureSegmentMap() error                { return nil }
func (d *dummyUnpackable) OpenStream() (io.ReadSeekCloser, error) { return nil, nil }
func (d *dummyUnpackable) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	return nil, nil
}
func (d *dummyUnpackable) OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	return nil, nil
}
func (d *dummyUnpackable) ReadAt(p []byte, off int64) (n int, err error) {
	return 0, nil
}

func TestDedupeVolumeMembers(t *testing.T) {
	files := []UnpackableFile{
		&dummyUnpackable{name: "release.part01.rar", size: 1000},
		&dummyUnpackable{name: "release.part01.rar", size: 5000}, // complete candidate
		&dummyUnpackable{name: "release.part02.rar", size: 5000},
	}

	deduped := dedupeVolumeMembers(files)
	if len(deduped) != 2 {
		t.Fatalf("dedupeVolumeMembers returned %d files; want 2", len(deduped))
	}
	if deduped[0].Size() != 5000 {
		t.Errorf("dedupeVolumeMembers kept size %d; want 5000", deduped[0].Size())
	}
}
