package unpack

import (
	"bytes"
	"context"
	"io"
	"testing"
)

// playbackWindowFile offers the deeper playback read-ahead window, and records
// which of the two open paths a caller took.
type playbackWindowFile struct {
	*memoryUnpackableFile
	playbackOpens int
	scanOpens     int
}

func (f *playbackWindowFile) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	f.scanOpens++
	return &nopReadSeekCloser{Reader: bytes.NewReader(f.data)}, nil
}

func (f *playbackWindowFile) OpenPlaybackStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	f.playbackOpens++
	return &nopReadSeekCloser{Reader: bytes.NewReader(f.data)}, nil
}

func newPlaybackWindowFile() *playbackWindowFile {
	return &playbackWindowFile{memoryUnpackableFile: &memoryUnpackableFile{name: "movie.mkv", data: []byte("payload")}}
}

// Direct playback is the common case and must get the playback-sized read-ahead
// window; it used to open through OpenStreamCtx, the window sized for RAR scans
// and PAR2 repair, and so ran three times shallower than archive playback did.
func TestDirectBlueprintOpensWithPlaybackWindow(t *testing.T) {
	f := newPlaybackWindowFile()
	b := &DirectBlueprint{FileName: "movie.mkv", FileIndex: 0}

	stream, _, _, err := b.Open(context.Background(), []UnpackableFile{f}, "")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer stream.Close()

	if f.playbackOpens != 1 {
		t.Errorf("playback opens = %d, want 1", f.playbackOpens)
	}
	if f.scanOpens != 0 {
		t.Errorf("scan opens = %d, want 0 (direct playback must not use the scan window)", f.scanOpens)
	}
}

// A file that offers no playback window still has to open.
func TestDirectBlueprintFallsBackWhenNoPlaybackWindow(t *testing.T) {
	f := &memoryUnpackableFile{name: "movie.mkv", data: []byte("payload")}
	b := &DirectBlueprint{FileName: "movie.mkv", FileIndex: 0}

	stream, _, _, err := b.Open(context.Background(), []UnpackableFile{f}, "")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer stream.Close()
}

// renamedFile embeds the UnpackableFile interface, so capabilities the wrapped
// file offers beyond that interface are not promoted through the wrapper. A
// deobfuscated release must not silently drop back to the shallow window.
func TestRenamedFileForwardsPlaybackWindow(t *testing.T) {
	inner := newPlaybackWindowFile()
	wrapped := &renamedFile{UnpackableFile: inner, name: "Movie.2010.mkv"}

	stream, err := openPlaybackStream(wrapped, context.Background())
	if err != nil {
		t.Fatalf("openPlaybackStream: %v", err)
	}
	defer stream.Close()

	if inner.playbackOpens != 1 {
		t.Errorf("playback opens = %d, want 1 (capability lost through the rename wrapper)", inner.playbackOpens)
	}
}
