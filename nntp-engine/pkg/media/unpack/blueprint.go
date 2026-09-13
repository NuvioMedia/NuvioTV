package unpack

import (
	"context"
	"errors"

	"streamnzb/pkg/core/logger"
)

// Blueprint is a cached plan for reaching the requested media inside a release
// without rescanning it. Scanning a large RAR set is the expensive part of
// starting playback, so the plan is built once, serialized into the library,
// and replayed on later plays.
//
// Each concrete blueprint knows how to reopen its own stream. Open receives the
// loader files because the direct path streams one of them by index while the
// archive paths read through their own recorded volume map.
type Blueprint interface {
	// TargetEpisode reports which episode this blueprint was built for, so a
	// cached plan is never reused for a different request.
	TargetEpisode() EpisodeTarget

	// Open reopens the media stream this blueprint describes, returning the
	// stream, the inner file name and its size.
	Open(ctx context.Context, files []UnpackableFile, password string) (ReadSeekCloser, string, int64, error)

	// Kind is a short label used in logs ("rar", "7z", "direct", "failed").
	Kind() string
}

var (
	_ Blueprint = (*ArchiveBlueprint)(nil)
	_ Blueprint = (*SevenZipBlueprint)(nil)
	_ Blueprint = (*DirectBlueprint)(nil)
	_ Blueprint = (*FailedBlueprint)(nil)
)

func (b *ArchiveBlueprint) TargetEpisode() EpisodeTarget { return b.Target }
func (b *ArchiveBlueprint) Kind() string                 { return "rar" }

func (b *ArchiveBlueprint) Open(ctx context.Context, _ []UnpackableFile, password string) (ReadSeekCloser, string, int64, error) {
	return StreamFromBlueprint(ctx, b, password)
}

func (b *SevenZipBlueprint) TargetEpisode() EpisodeTarget { return b.Target }
func (b *SevenZipBlueprint) Kind() string                 { return "7z" }

func (b *SevenZipBlueprint) Open(ctx context.Context, _ []UnpackableFile, password string) (ReadSeekCloser, string, int64, error) {
	if len(b.Files) == 0 {
		return nil, "", 0, errors.New("7z set empty for cached blueprint")
	}
	stream, name, size, err := Open7zStreamFromBlueprint(ctx, b, password)
	if err != nil {
		err = maybeMarkArchiveFastProbe(ctx, err)
	}
	return stream, name, size, err
}

func (b *DirectBlueprint) TargetEpisode() EpisodeTarget { return b.Target }
func (b *DirectBlueprint) Kind() string                 { return "direct" }

// ErrBlueprintFileIndexOutOfRange means the cached file index no longer matches
// the release's file list, so the plan must be rebuilt by a fresh scan.
var ErrBlueprintFileIndexOutOfRange = errors.New("blueprint file index out of range")

func (b *DirectBlueprint) Open(ctx context.Context, files []UnpackableFile, _ string) (ReadSeekCloser, string, int64, error) {
	if b.FileIndex < 0 || b.FileIndex >= len(files) {
		return nil, "", 0, ErrBlueprintFileIndexOutOfRange
	}
	f := files[b.FileIndex]
	stream, err := openPlaybackStream(f, ctx)
	if err != nil {
		return nil, "", 0, err
	}
	return stream, b.FileName, f.Size(), nil
}

func (b *FailedBlueprint) TargetEpisode() EpisodeTarget { return b.Target }
func (b *FailedBlueprint) Kind() string                 { return "failed" }

// Open replays the scan failure this blueprint recorded, so a release already
// proven unusable fails immediately instead of being rescanned.
func (b *FailedBlueprint) Open(context.Context, []UnpackableFile, string) (ReadSeekCloser, string, int64, error) {
	return nil, "", 0, b.Err
}

// TailVolume returns the volume file holding the end of the media, or nil when
// the blueprint has no usable parts.
func (b *ArchiveBlueprint) TailVolume() UnpackableFile {
	if b == nil || len(b.Parts) == 0 {
		return nil
	}
	return b.Parts[len(b.Parts)-1].VolFile
}

// playbackTailWarmBytes is how much of the end of a file the tail warm pulls.
// Container indexes are small — a Matroska cues block or a trailing MP4 moov —
// and the player reads them once before seeking back to the start, so this is
// deliberately a couple of articles rather than a read-ahead window.
const playbackTailWarmBytes = 8 << 20

// WarmPlaybackTail prepares the end of the media while startup is busy at the
// beginning of it.
//
// Every container player reads the tail before it shows a frame — Matroska
// cues, an MP4 moov written last — and that read lands somewhere nothing has
// touched. For a multi-volume archive it costs a fresh round of segment-size
// probes on the final volume; for a directly-posted file the map is already
// there but the articles are not. Both were paid while the user watched a
// spinner: 1.6 s of probes on one release, 7 s of cold articles on another.
// Doing it here overlaps the wait with the ffprobe run at the head.
//
// Reports whether a warm was started. Nothing here is fatal — a failure only
// means the seek pays what it always paid.
func WarmPlaybackTail(ctx context.Context, bp Blueprint, files []UnpackableFile) bool {
	switch b := bp.(type) {
	case *ArchiveBlueprint:
		vol := b.TailVolume()
		if vol == nil {
			return false
		}
		if err := ensureSegmentMap(ctx, vol); err != nil {
			logger.Debug("Tail segment map warm failed", "volume", vol.Name(), "err", err)
			return false
		}
		prefetchPlaybackRange(ctx, vol, vol.Size()-playbackTailWarmBytes, playbackTailWarmBytes)
		return true
	case *DirectBlueprint:
		f := directBlueprintFile(b, files)
		if f == nil {
			return false
		}
		if err := ensureSegmentMap(ctx, f); err != nil {
			logger.Debug("Tail segment map warm failed", "file", f.Name(), "err", err)
			return false
		}
		prefetchPlaybackRange(ctx, f, f.Size()-playbackTailWarmBytes, playbackTailWarmBytes)
		return true
	}
	return false
}

// directBlueprintFile resolves a direct blueprint against a file set, by name
// first: the recorded index is only meaningful for the file list the blueprint
// was built from, and a replay rebuilds that list from the NZB.
func directBlueprintFile(bp *DirectBlueprint, files []UnpackableFile) UnpackableFile {
	if bp == nil {
		return nil
	}
	for _, f := range files {
		if f != nil && f.Name() == bp.FileName {
			return f
		}
	}
	if bp.FileIndex >= 0 && bp.FileIndex < len(files) {
		return files[bp.FileIndex]
	}
	return nil
}
