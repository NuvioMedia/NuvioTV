package ffprobe

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"streamnzb/pkg/core/logger"
)

// DefaultDecodeFrames is the number of frames forced through the decoder when
// ProbeOptions.ForceDecode is enabled. ~120 frames of a 4K HEVC stream pulls
// roughly 10-20MB of real payload, which is enough to trip over article holes
// (e.g. 430 No Such Article) that a header-only probe structurally cannot see.
const DefaultDecodeFrames = 120

// ffprobeDisposition captures the stream disposition flags we care about.
type ffprobeDisposition struct {
	AttachedPic int `json:"attached_pic"`
}

type FFprobeStream struct {
	CodecType        string             `json:"codec_type"`
	CodecName        string             `json:"codec_name"`
	Profile          string             `json:"profile"`
	Width            int                `json:"width,omitempty"`
	Height           int                `json:"height,omitempty"`
	PixFmt           string             `json:"pix_fmt"`
	ColorTransfer    string             `json:"color_transfer"`
	ColorPrimaries   string             `json:"color_primaries"`
	CodecTagString   string             `json:"codec_tag_string"`
	BitRate          string             `json:"bit_rate"`
	BitsPerRawSample string             `json:"bits_per_raw_sample"`
	NbReadFrames     string             `json:"nb_read_frames"`
	Disposition      ffprobeDisposition `json:"disposition"`
}

// FFprobeFormat carries the container-level fields we ask for. Duration comes
// from the container header (MKV Segment Info, MP4 moov), so it is usually
// known even on a piped probe that never reads the whole file — and absent
// when the header does not state one (e.g. a moov-at-end MP4 on a pipe).
type FFprobeFormat struct {
	Duration string `json:"duration"`
}

type FFprobeOutput struct {
	Streams []FFprobeStream `json:"streams"`
	Format  FFprobeFormat   `json:"format"`
}

// FFprobeResult summarizes the probed media. The capability fields (Profile,
// PixFmt, HDR, ...) are captured from the first *qualifying* video stream so
// callers can distinguish "the file is broken" from "this client can't decode
// this codec."
type FFprobeResult struct {
	HasVideo   bool
	HasAudio   bool
	VideoCodec string
	AudioCodec string
	Width      int
	Height     int

	// Capability metadata, from the first real (non cover-art) video stream.
	Profile        string
	PixFmt         string
	ColorTransfer  string
	ColorPrimaries string
	CodecTag       string
	BitDepth       int
	HDR            string // "", "HDR10", "HDR10+", "HLG"
	DolbyVision    bool
	FramesDecoded  int // nb_read_frames of the chosen video stream (only when ForceDecode)

	// DurationSeconds is the container-reported duration, 0 when the header
	// does not state one.
	DurationSeconds float64
}

// ProbeOptions tunes how aggressively ProbeStream inspects the input.
type ProbeOptions struct {
	// ForceDecode adds -count_frames + -read_intervals so ffprobe actually pulls
	// and decodes packets instead of stopping at the container header. This is the
	// difference between validating a Matroska Tracks element (<1MB) and reading
	// several MB of real payload.
	ForceDecode bool
	// DecodeFrames overrides the number of frames to force-decode. 0 => DefaultDecodeFrames.
	DecodeFrames int
	// QuickHeader caps -probesize/-analyzeduration far below the thorough
	// defaults. On a network-backed stream the default 50M probesize can pull
	// tens of MB before the caller gets an answer — over a second of wall
	// clock that lands directly on time-to-first-byte when the probe sits on
	// the serve path. Track headers live in the first few MB of every
	// container we serve; anything the small window misses degrades to the
	// caller's permissive fallback, not to a rejection.
	QuickHeader bool
}

func FindFFprobeBinary(customPath string) (string, bool) {
	if strings.TrimSpace(customPath) != "" {
		if _, err := os.Stat(customPath); err == nil {
			return customPath, true
		}
	}

	execDir := ""
	if ex, err := os.Executable(); err == nil {
		execDir = filepath.Dir(ex)
	}

	candidates := []string{}
	if runtime.GOOS == "windows" {
		candidates = append(candidates, "ffprobe.exe", filepath.Join(execDir, "ffprobe.exe"))
	} else {
		candidates = append(candidates, "ffprobe", filepath.Join(execDir, "ffprobe"))
	}

	for _, cand := range candidates {
		if _, err := os.Stat(cand); err == nil {
			if abs, err := filepath.Abs(cand); err == nil {
				return abs, true
			}
			return cand, true
		}
	}

	if path, err := exec.LookPath("ffprobe"); err == nil {
		return path, true
	}

	if path, ok := ExtractEmbeddedBinary(); ok {
		return path, true
	}

	return "", false
}

// showEntries is the -show_entries spec: enough stream fields to both validate
// playability and capture client-relevant capabilities (profile, bit depth, HDR).
const showEntries = "stream=codec_type,codec_name,profile,width,height,pix_fmt," +
	"color_transfer,color_primaries,codec_tag_string,bit_rate,bits_per_raw_sample,nb_read_frames:" +
	"stream_disposition=attached_pic:" +
	"format=duration"

// ProbeStream runs a lightweight, header-only inspection (backwards-compatible).
func ProbeStream(ctx context.Context, stream io.Reader, customPath string) (*FFprobeResult, error) {
	return ProbeStreamWithOptions(ctx, stream, customPath, ProbeOptions{})
}

// ProbeStreamWithOptions runs ffprobe against the reader, optionally forcing a
// real multi-frame decode.
func ProbeStreamWithOptions(ctx context.Context, stream io.Reader, customPath string, opts ProbeOptions) (*FFprobeResult, error) {
	binaryPath, ok := FindFFprobeBinary(customPath)
	if !ok {
		return nil, errors.New("ffprobe binary not found in PATH or working directory")
	}

	timeout := 15 * time.Second
	if opts.ForceDecode {
		timeout = 60 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	// -analyzeduration is microseconds of content, -probesize is bytes.
	probesize, analyzeduration := "50M", "20M"
	if opts.QuickHeader {
		probesize, analyzeduration = "5M", "5M"
	}
	args := []string{
		"-v", "error",
		"-probesize", probesize,
		"-analyzeduration", analyzeduration,
		"-show_entries", showEntries,
		"-of", "json",
	}
	if opts.ForceDecode {
		frames := opts.DecodeFrames
		if frames <= 0 {
			frames = DefaultDecodeFrames
		}
		// "%+#N" = starting at the current position (offset 0, no seek needed for
		// a pipe), read N packets. Combined with -count_frames this forces a real
		// decode of the opening N frames.
		args = append(args, "-count_frames", "-read_intervals", fmt.Sprintf("%%+#%d", frames))
	}
	args = append(args, "pipe:0")

	cmd := exec.CommandContext(ctx, binaryPath, args...)
	// Wrap stdin so we can recover the underlying stream error: when ffprobe exits
	// non-zero because a segment read failed (e.g. 430), exec masks the copy error
	// behind the ExitError. Capturing it lets callers tell a real broken release
	// (missing/corrupt article) from an inconclusive probe (timeout, codec).
	rr := &recordingReader{r: stream}
	cmd.Stdin = rr
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		if rr.lastErr != nil {
			return nil, fmt.Errorf("ffprobe execution failed (%v): %s: %w", err, strings.TrimSpace(stderr.String()), rr.lastErr)
		}
		return nil, fmt.Errorf("ffprobe execution failed (%v): %s", err, strings.TrimSpace(stderr.String()))
	}

	var output FFprobeOutput
	if err := json.Unmarshal(stdout.Bytes(), &output); err != nil {
		return nil, fmt.Errorf("parse ffprobe json output: %w", err)
	}

	res := summarizeStreams(output.Streams)
	if seconds, err := strconv.ParseFloat(strings.TrimSpace(output.Format.Duration), 64); err == nil && seconds > 0 {
		res.DurationSeconds = seconds
	}

	logger.Debug("FFprobe stream inspection completed",
		"binary", binaryPath,
		"force_decode", opts.ForceDecode,
		"has_video", res.HasVideo,
		"video_codec", res.VideoCodec,
		"profile", res.Profile,
		"width", res.Width,
		"height", res.Height,
		"pix_fmt", res.PixFmt,
		"bit_depth", res.BitDepth,
		"hdr", res.HDR,
		"dolby_vision", res.DolbyVision,
		"codec_tag", res.CodecTag,
		"frames_decoded", res.FramesDecoded,
		"has_audio", res.HasAudio,
		"audio_codec", res.AudioCodec,
		"duration_s", res.DurationSeconds,
	)

	return res, nil
}

// summarizeStreams reduces ffprobe's stream list to a single result, choosing
// the FIRST qualifying video stream (so real video followed by cover art does
// not get its codec/dimensions overwritten by the artwork).
func summarizeStreams(streams []FFprobeStream) *FFprobeResult {
	res := &FFprobeResult{}
	videoChosen := false
	for _, st := range streams {
		switch st.CodecType {
		case "video":
			if videoChosen || !isRealVideoStream(st) {
				continue // cover art / still image / undecodable — not a real video track
			}
			res.HasVideo = true
			res.VideoCodec = st.CodecName
			res.Width = st.Width
			res.Height = st.Height
			res.Profile = st.Profile
			res.PixFmt = st.PixFmt
			res.ColorTransfer = st.ColorTransfer
			res.ColorPrimaries = st.ColorPrimaries
			res.CodecTag = st.CodecTagString
			res.BitDepth = bitDepthFromStream(st)
			res.HDR = classifyHDR(st)
			res.DolbyVision = isDolbyVision(st)
			if n, ok := parseIntOK(st.NbReadFrames); ok {
				res.FramesDecoded = n
			}
			videoChosen = true
		case "audio":
			res.HasAudio = true
			if res.AudioCodec == "" {
				res.AudioCodec = st.CodecName
			}
		}
	}
	return res
}

// isRealVideoStream rejects "video" streams that are actually embedded cover art
// (attached_pic disposition, a still-image codec, or a stream that decoded <=1
// frame when a forced decode was requested).
func isRealVideoStream(st FFprobeStream) bool {
	if st.Disposition.AttachedPic == 1 {
		return false
	}
	if isStillImageCodec(st.CodecName) {
		return false
	}
	if n, ok := parseIntOK(st.NbReadFrames); ok && n <= 1 {
		return false
	}
	return true
}

// isStillImageCodec reports whether codec is a still-image codec typically used
// for embedded artwork rather than a real video track.
func isStillImageCodec(codec string) bool {
	switch strings.ToLower(strings.TrimSpace(codec)) {
	case "mjpeg", "png", "bmp", "gif", "tiff", "webp", "jpeg", "jpegls", "ppm", "pgm", "pam", "targa", "tga":
		return true
	default:
		return false
	}
}

// bitDepthFromStream derives the luma bit depth, preferring the explicit
// bits_per_raw_sample and falling back to the pixel format naming convention.
func bitDepthFromStream(st FFprobeStream) int {
	if n, ok := parseIntOK(st.BitsPerRawSample); ok && n > 0 {
		return n
	}
	pf := strings.ToLower(st.PixFmt)
	switch {
	case strings.Contains(pf, "12le"), strings.Contains(pf, "12be"), strings.Contains(pf, "p012"):
		return 12
	case strings.Contains(pf, "10le"), strings.Contains(pf, "10be"), strings.Contains(pf, "p010"):
		return 10
	case pf == "":
		return 0
	default:
		return 8
	}
}

// classifyHDR maps the transfer characteristics to a coarse HDR bucket.
func classifyHDR(st FFprobeStream) string {
	switch strings.ToLower(strings.TrimSpace(st.ColorTransfer)) {
	case "smpte2084", "smptest2084":
		return "HDR10"
	case "arib-std-b67":
		return "HLG"
	default:
		return ""
	}
}

// isDolbyVision detects Dolby Vision from the codec tag / profile. DV profile 5
// commonly reports a dvhe/dvh1 tag which most React Native players cannot decode.
func isDolbyVision(st FFprobeStream) bool {
	switch strings.ToLower(strings.TrimSpace(st.CodecTagString)) {
	case "dvhe", "dvh1", "dva1", "dav1", "dvav":
		return true
	}
	if strings.Contains(strings.ToLower(st.Profile), "dolby vision") {
		return true
	}
	return false
}

// recordingReader wraps the probe input and remembers the last non-EOF read
// error so the underlying cause survives ffprobe's non-zero exit. exec's stdin
// copy goroutine finishes before cmd.Run returns, so lastErr is safe to read after.
type recordingReader struct {
	r       io.Reader
	lastErr error
}

func (rr *recordingReader) Read(p []byte) (int, error) {
	n, err := rr.r.Read(p)
	if err != nil && err != io.EOF {
		rr.lastErr = err
	}
	return n, err
}

// parseIntOK parses an ffprobe integer field, treating "N/A"/"" as absent.
func parseIntOK(s string) (int, bool) {
	s = strings.TrimSpace(s)
	if s == "" || strings.EqualFold(s, "N/A") {
		return 0, false
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return 0, false
	}
	return n, true
}
