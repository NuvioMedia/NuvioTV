package loader

import (
	"context"
	"errors"
)

// This file owns the segment-fetch policy knobs that higher layers (unpack)
// need to influence. They live here, in the layer that actually fetches
// segments, so the archive layer depends on the segment layer and not the
// other way around.

// ErrTooManyZeroFills marks a read that gave up after too many segments could
// not be fetched, so the gaps were zero-filled past the tolerated threshold.
// Callers treat it as evidence the release itself is bad, not a transient blip.
var ErrTooManyZeroFills = errors.New("too many failed segments")

// PlaybackReadAheadSegments is the playback read-ahead window, in segments,
// while the article size is still unknown — before the segment map is built
// there is no byte budget to translate into a count.
const PlaybackReadAheadSegments = 24

// The window used to be that count and nothing else. It was chosen against
// ~700 KB articles — about 17 MB in flight — and breaks on a release posted in
// 4 MiB articles, where the same 24 becomes ~100 MB: the connections share the
// line fairly, so the one segment the reader is blocked on arrives no sooner
// than the twenty-three it does not need yet. A seek into such a release logged
// forty-odd concurrent fetches all completing together at 10-11 seconds each,
// with the serve blocked on reads for 99% of the window.
//
// What the window is really buying is seconds of playback: bytes = bitrate ×
// seconds. Sizing it as a fraction of the file gives exactly that without
// needing to know the bitrate, because the size cancels — a fraction of the
// file is the same fraction of the runtime, at any bitrate. The clamps keep it
// honest at the ends, where a small file would starve and a 67 GB one would put
// a hundred megabytes back in front of its own next read.
const (
	playbackReadAheadFraction = 400      // 0.25% of the file ≈ 0.25% of its runtime
	minPlaybackReadAheadBytes = 16 << 20 // a short file still gets a real buffer
	maxPlaybackReadAheadBytes = 48 << 20 // past this, depth costs more latency than it buys
)

// MinPlaybackReadAheadSegments floors the window so a large article size cannot
// collapse it toward a serial fetch: throughput is concurrency times
// per-connection rate, and a handful of connections is the least that sustains
// a high bitrate.
const MinPlaybackReadAheadSegments = 6

// MaxPlaybackReadAheadSegments caps the window's segment count so an unusually
// small article size cannot explode the byte budget into hundreds of concurrent
// claims. It is sized to let the full budget through at the ordinary ~700 KB
// article size (48 MiB is ~70 of those); the old cap of 24 held every large
// release to ~17 MB in flight and left half the pool idle after a seek.
const MaxPlaybackReadAheadSegments = 96

// PlaybackReadAheadBytes is the byte budget for a file of fileBytes.
func PlaybackReadAheadBytes(fileBytes int64) int64 {
	budget := fileBytes / playbackReadAheadFraction
	if budget < minPlaybackReadAheadBytes {
		return minPlaybackReadAheadBytes
	}
	if budget > maxPlaybackReadAheadBytes {
		return maxPlaybackReadAheadBytes
	}
	return budget
}

// PlaybackReadAheadFor sizes the playback window for a file of fileBytes whose
// segments decode to segmentBytes each.
func PlaybackReadAheadFor(fileBytes, segmentBytes int64) int {
	if segmentBytes <= 0 {
		return PlaybackReadAheadSegments
	}
	segments := int(PlaybackReadAheadBytes(fileBytes) / segmentBytes)
	if segments > MaxPlaybackReadAheadSegments {
		return MaxPlaybackReadAheadSegments
	}
	if segments < MinPlaybackReadAheadSegments {
		return MinPlaybackReadAheadSegments
	}
	return segments
}

type skipGapProbingContextKey struct{}

// WithSkipGapProbing marks ctx as a playback-time read, where the expensive
// gap probing used for one-time archive sizing is skipped.
func WithSkipGapProbing(ctx context.Context, enabled bool) context.Context {
	if !enabled {
		return ctx
	}
	return context.WithValue(ctx, skipGapProbingContextKey{}, true)
}

// IsSkipGapProbingEnabled reports whether ctx asked to skip gap probing.
func IsSkipGapProbingEnabled(ctx context.Context) bool {
	if ctx == nil {
		return false
	}
	enabled, _ := ctx.Value(skipGapProbingContextKey{}).(bool)
	return enabled
}
