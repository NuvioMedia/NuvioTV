package loader

import "testing"

// The window is a buffer measured in seconds of playback, so its size has to
// follow the article size — the same count of 4 MiB articles is six times the
// bytes, and the reader waits on all of them to learn the one it needs.
func TestPlaybackReadAheadFollowsArticleSize(t *testing.T) {
	const gb = int64(1) << 30

	cases := []struct {
		name     string
		fileSize int64
		segment  int64
		want     int
	}{
		// Sub-megabyte articles on a large release get the full 48 MiB budget;
		// the old cap of 24 segments held this to ~17 MB in flight and left
		// half the pool's connections idle after a seek.
		{"67GB remux, 700KB articles", 67 * gb, 700 << 10, 70},
		// Tiny articles must not turn the budget into hundreds of claims.
		{"90GB, 300KB articles", 90 * gb, 300 << 10, MaxPlaybackReadAheadSegments},
		// The case that broke it: 4 MiB articles. 24 of those is ~100MB in
		// flight ahead of a reader that needs the first one.
		{"18GB movie, 4MiB articles", 18 * gb, 4 << 20, 11},
		// A small file gets the floor budget rather than one scaled to almost
		// nothing: 16MB of 700KB articles, near the historical depth.
		{"700MB episode, 700KB articles", 700 << 20, 700 << 10, 23},
		// Absurd article size still keeps enough connections busy to move data.
		{"20GB, 32MiB articles", 20 * gb, 32 << 20, MinPlaybackReadAheadSegments},
		// Before the map is built there is no article size to go on.
		{"unknown article size", 20 * gb, 0, PlaybackReadAheadSegments},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := PlaybackReadAheadFor(tc.fileSize, tc.segment); got != tc.want {
				t.Fatalf("PlaybackReadAheadFor(%d, %d) = %d, want %d", tc.fileSize, tc.segment, got, tc.want)
			}
		})
	}
}

// The budget tracks the file so it tracks the runtime, but a huge file must not
// be allowed to queue its whole buffer in front of its own next read.
func TestPlaybackReadAheadBytesStaysClamped(t *testing.T) {
	const gb = int64(1) << 30
	if got := PlaybackReadAheadBytes(100 * gb); got != maxPlaybackReadAheadBytes {
		t.Fatalf("100GB budget = %d, want the %d cap", got, int64(maxPlaybackReadAheadBytes))
	}
	if got := PlaybackReadAheadBytes(100 << 20); got != minPlaybackReadAheadBytes {
		t.Fatalf("100MB budget = %d, want the %d floor", got, int64(minPlaybackReadAheadBytes))
	}
	if got := PlaybackReadAheadBytes(10 * gb); got != 10*gb/playbackReadAheadFraction {
		t.Fatalf("10GB budget = %d, want it scaled to the file", got)
	}
}

// A file sizes its own window off the map it measured.
func TestFilePlaybackReadAheadUsesItsOwnArticleSize(t *testing.T) {
	big := int64(4 << 20)
	sizes := make([]int64, 64)
	nzbSizes := make([]int64, 64)
	for i := range sizes {
		sizes[i] = big
		nzbSizes[i] = big + 4096
	}
	f := NewFile(nil, testNZBFileWithSegments(nzbSizes...), nil, &varyingSizeSegmentFetcher{sizes: sizes})

	// Before detection the article size is unknown, so the window is the
	// historical default rather than a guess off the encoded sizes.
	if got := f.PlaybackReadAhead(); got != PlaybackReadAheadSegments {
		t.Fatalf("undetected window = %d, want %d", got, PlaybackReadAheadSegments)
	}
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}
	want := PlaybackReadAheadFor(f.Size(), big)
	if got := f.PlaybackReadAhead(); got != want {
		t.Fatalf("detected window = %d, want %d", got, want)
	}
	if got := f.PlaybackReadAhead(); got >= PlaybackReadAheadSegments {
		t.Fatalf("window %d did not shrink for 4MiB articles", got)
	}
}

// A split-archive volume is a fixed slice of a much longer stream, so its
// window must be sized against the movie being played, not the volume — sized
// against a 500 MB volume the budget floored at its minimum on exactly the
// releases that need the deepest window.
func TestPlaybackReadAheadUsesStreamSizeHint(t *testing.T) {
	seg := int64(700 << 10)
	sizes := make([]int64, 32)
	nzbSizes := make([]int64, 32)
	for i := range sizes {
		sizes[i] = seg
		nzbSizes[i] = seg + 4096
	}
	f := NewFile(nil, testNZBFileWithSegments(nzbSizes...), nil, &varyingSizeSegmentFetcher{sizes: sizes})
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap: %v", err)
	}

	volume := f.PlaybackReadAhead()
	if want := PlaybackReadAheadFor(f.Size(), seg); volume != want {
		t.Fatalf("window without hint = %d, want %d", volume, want)
	}

	const movie = int64(176) << 30
	f.SetPlaybackStreamBytes(movie)
	deepened := f.PlaybackReadAhead()
	if want := PlaybackReadAheadFor(movie, seg); deepened != want {
		t.Fatalf("window with stream hint = %d, want %d", deepened, want)
	}
	if deepened <= volume {
		t.Fatalf("stream hint did not deepen the window (%d <= %d)", deepened, volume)
	}

	// A shorter stream opened later over the same volume must not shrink a
	// window playback is already relying on.
	f.SetPlaybackStreamBytes(1)
	if got := f.PlaybackReadAhead(); got != deepened {
		t.Fatalf("window after smaller hint = %d, want %d", got, deepened)
	}
}
