package unpack

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"sync"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/ffprobe"
)

const ProbeSize = 256 * 1024

// warnMissingFFprobeOnce keeps the no-ffprobe warning to one line per process.
var warnMissingFFprobeOnce sync.Once

// ContainerTrackInfo summarizes detected track types in a media container header.
type ContainerTrackInfo struct {
	HasVideo bool
	HasAudio bool
	Detected bool
	Format   string
}

// HasMediaExtension returns whether fileName has a recognized video/audio container extension.
func HasMediaExtension(fileName string) bool {
	ext := strings.ToLower(filepath.Ext(fileName))
	switch ext {
	case ".mkv", ".mp4", ".avi", ".m4v", ".ts", ".mov", ".wmv", ".flv", ".webm", ".m2ts", ".vob", ".mka", ".mp3", ".aac", ".flac":
		return true
	default:
		return false
	}
}

// InspectContainerHeader inspects up to 256KB of media container header bytes
// to detect if video and/or audio tracks are present.
func InspectContainerHeader(header []byte, fileName string) ContainerTrackInfo {
	if len(header) < 16 {
		return ContainerTrackInfo{Detected: false}
	}

	nameLower := strings.ToLower(fileName)

	// 1. Matroska (.mkv) / WebM (.webm) / (.mka) inspection
	// EBML Header ID: 0x1A45DFA3 or Matroska signature
	if strings.HasSuffix(nameLower, ".mkv") || strings.HasSuffix(nameLower, ".webm") || strings.HasSuffix(nameLower, ".mka") || bytes.Contains(header[:minInt(1024, len(header))], []byte{0x1A, 0x45, 0xDF, 0xA3}) {
		hasVideo := bytes.Contains(header, []byte{0x83, 0x01, 0x01})
		hasAudio := bytes.Contains(header, []byte{0x83, 0x01, 0x02})
		return ContainerTrackInfo{
			HasVideo: hasVideo,
			HasAudio: hasAudio,
			Detected: true,
			Format:   "matroska",
		}
	}

	// 2. MP4 (.mp4, .m4v, .mov, .m4a) inspection
	// Check for 'ftyp' or 'moov' boxes
	if strings.HasSuffix(nameLower, ".mp4") || strings.HasSuffix(nameLower, ".m4v") || strings.HasSuffix(nameLower, ".mov") || strings.HasSuffix(nameLower, ".m4a") || bytes.Contains(header[:minInt(1024, len(header))], []byte("ftyp")) || bytes.Contains(header[:minInt(4096, len(header))], []byte("moov")) {
		hasVideo := bytes.Contains(header, []byte("vide"))
		hasAudio := bytes.Contains(header, []byte("soun"))
		return ContainerTrackInfo{
			HasVideo: hasVideo,
			HasAudio: hasAudio,
			Detected: true,
			Format:   "mp4",
		}
	}

	// 3. AVI (.avi) inspection
	if strings.HasSuffix(nameLower, ".avi") || bytes.Contains(header[:minInt(1024, len(header))], []byte("AVI ")) {
		hasVideo := bytes.Contains(header, []byte("vids"))
		hasAudio := bytes.Contains(header, []byte("auds"))
		return ContainerTrackInfo{
			HasVideo: hasVideo,
			HasAudio: hasAudio,
			Detected: true,
			Format:   "avi",
		}
	}

	return ContainerTrackInfo{Detected: false}
}

// ValidateMediaStreamHasVideoWithFFprobe inspects the stream with ffprobe (if configured/available),
// falling back to container track header inspection.
func ValidateMediaStreamHasVideoWithFFprobe(stream ReadSeekCloser, fileName string, customFFprobePath string) error {
	return ValidateMediaStreamHasVideoWithFFprobeCtx(context.Background(), stream, fileName, customFFprobePath)
}

// ValidateOptions tunes stream validation behavior.
type ValidateOptions struct {
	// ForceDecode makes ffprobe pull and decode several MB of real payload instead
	// of stopping at the container header. Use in speculative pre-probing to catch
	// releases with article holes past the header (e.g. 430 No Such Article).
	ForceDecode bool
	// DecodeFrames overrides the forced-decode frame count (0 => package default).
	DecodeFrames int
	// StrictFFprobe rejects the stream when ffprobe is available but errors or is
	// inconclusive, instead of falling back to the permissive header heuristic.
	// Appropriate for pre-probing (a false reject just tries the next candidate);
	// NOT for serve-time validation (a false reject breaks live playback).
	StrictFFprobe bool
	// QuickHeader bounds the ffprobe read window tightly. For serve-time
	// validation the probe sits on time-to-first-byte and only needs the
	// container header; anything it cannot settle in the small window falls
	// back to the permissive heuristic exactly as an ffprobe error would.
	QuickHeader bool
}

// ValidateMediaStreamHasVideoWithFFprobeCtx inspects the stream with ffprobe (if configured/available),
// falling back to container track header inspection, honoring context cancellation.
func ValidateMediaStreamHasVideoWithFFprobeCtx(ctx context.Context, stream ReadSeekCloser, fileName string, customFFprobePath string) error {
	_, err := ValidateMediaStreamWithOptions(ctx, stream, fileName, customFFprobePath, ValidateOptions{})
	return err
}

// ValidateMediaStreamWithOptions validates the stream and returns the ffprobe
// result (nil when ffprobe was unavailable and only the header heuristic ran) so
// callers can persist/surface capability metadata.
func ValidateMediaStreamWithOptions(ctx context.Context, stream ReadSeekCloser, fileName string, customFFprobePath string, opts ValidateOptions) (*ffprobe.FFprobeResult, error) {
	if stream == nil {
		return nil, nil
	}
	if ctx == nil {
		ctx = context.Background()
	}

	if binPath, ok := ffprobe.FindFFprobeBinary(customFFprobePath); !ok {
		// Once per process: without this the only trace of a missing ffprobe
		// is a Debug line at startup, and the operator sees a permanently
		// empty measured tier with no explanation.
		warnMissingFFprobeOnce.Do(func() {
			logger.Warn("No ffprobe binary found (config, PATH, embedded); validating with header heuristics only and skipping measured capabilities")
		})
	} else {
		logger.Debug("Running ffprobe on media stream", "binary", binPath, "file", fileName, "force_decode", opts.ForceDecode)
		res, err := ffprobe.ProbeStreamWithOptions(ctx, stream, binPath, ffprobe.ProbeOptions{
			ForceDecode:  opts.ForceDecode,
			DecodeFrames: opts.DecodeFrames,
			QuickHeader:  opts.QuickHeader,
		})
		if _, seekErr := stream.Seek(0, io.SeekStart); seekErr != nil {
			logger.Warn("Failed to seek stream back after ffprobe", "err", seekErr)
		}
		if err == nil {
			if res.HasAudio && !res.HasVideo {
				return res, fmt.Errorf("FFprobe verified media stream is audio-only (audio_codec=%s, file=%s): missing video track", res.AudioCodec, fileName)
			}
			// When we forced a decode, "no real video stream" means ffprobe could not
			// decode any video frames — the release is broken or unplayable.
			if opts.ForceDecode && !res.HasVideo {
				return res, fmt.Errorf("FFprobe could not decode any video frames (file=%s): stream is corrupt or unplayable", fileName)
			}
			if res.HasVideo {
				logger.Info("FFprobe verified valid video stream", "file", fileName,
					"video_codec", res.VideoCodec, "profile", res.Profile,
					"resolution", fmt.Sprintf("%dx%d", res.Width, res.Height),
					"bit_depth", res.BitDepth, "hdr", res.HDR, "dolby_vision", res.DolbyVision,
					"frames_decoded", res.FramesDecoded)
				return res, nil
			}
			if opts.StrictFFprobe {
				return res, fmt.Errorf("FFprobe found no decodable video stream (file=%s)", fileName)
			}
		} else {
			if opts.StrictFFprobe {
				return nil, fmt.Errorf("FFprobe validation failed (file=%s): %w", fileName, err)
			}
			logger.Warn("FFprobe execution failed; falling back to container track header inspection", "err", err, "file", fileName)
		}
	}

	header := make([]byte, ProbeSize)
	n, err := io.ReadFull(stream, header)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return nil, fmt.Errorf("read media header: %w", err)
	}
	header = header[:n]

	// Reset stream position back to 0 so subsequent readers start at byte 0
	if _, seekErr := stream.Seek(0, io.SeekStart); seekErr != nil {
		logger.Warn("Failed to seek media stream back to start after track validation", "err", seekErr)
	}

	info := InspectContainerHeader(header, fileName)
	if info.Detected {
		logger.Debug("Media container track inspection completed",
			"file", fileName,
			"format", info.Format,
			"has_video", info.HasVideo,
			"has_audio", info.HasAudio)
		if info.HasAudio && !info.HasVideo {
			return nil, fmt.Errorf("media stream is audio-only (format=%s, file=%s): missing video track", info.Format, fileName)
		}
	}

	return nil, nil
}

// ProbeMediaStreamByContent probes a stream by content, verifying container
// structure and video track presence. The ffprobe binary and read-window
// bound come from WithProbeConfig on the context — hardcoding "" here used to
// bypass a configured ffprobe_path, and the unbounded window used to sit on
// the serve path's time-to-first-byte.
func ProbeMediaStreamByContent(ctx context.Context, stream ReadSeekCloser, name string, size int64) error {
	ffprobePath, quick := probeConfigFrom(ctx)
	if _, err := ValidateMediaStreamWithOptions(ctx, stream, name, ffprobePath, ValidateOptions{QuickHeader: quick}); err != nil {
		return err
	}
	header := make([]byte, ProbeSize)
	n, _ := io.ReadFull(stream, header)
	header = header[:n]
	_, _ = stream.Seek(0, io.SeekStart)

	info := InspectContainerHeader(header, name)
	if !info.Detected && !HasMediaExtension(name) {
		return fmt.Errorf("unrecognized non-media format for candidate %q", name)
	}
	return nil
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
