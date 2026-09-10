package unpack

import (
	"context"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"sort"
	"strings"
	"unicode"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/loader"
)

// ErrTooManyZeroFills is re-exported from the loader layer, which owns the
// zero-fill threshold, so existing callers keep matching one sentinel.
var ErrTooManyZeroFills = loader.ErrTooManyZeroFills
var ErrEpisodeTargetNotFound = errors.New("requested episode not found in release")

type ReadSeekCloser interface {
	io.Reader
	io.Seeker
	io.Closer
}

type DirectBlueprint struct {
	FileName  string
	FileIndex int
	Target    EpisodeTarget
}

type FailedBlueprint struct {
	Err    error
	Target EpisodeTarget
}

type StreamSelectionHints struct {
	AllowLargestDirectFallback bool
}

const maxDirectProbeCandidates = 3

func isPlausibleLargestDirectFallbackName(name string) bool {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return false
	}

	lower := strings.ToLower(trimmed)
	if IsVideoFile(lower) {
		return true
	}

	switch strings.ToLower(filepath.Ext(trimmed)) {
	case ".m2ts", ".mts", ".ts", ".tp":
		return true
	}

	if filepath.Ext(trimmed) != "" {
		return false
	}

	if utfLen := len([]rune(trimmed)); utfLen < 6 {
		return false
	}

	letters := 0
	alphaNum := 0
	for _, r := range trimmed {
		switch {
		case unicode.IsLetter(r):
			letters++
			alphaNum++
		case unicode.IsDigit(r):
			alphaNum++
		case strings.ContainsRune(" ._[]()-,'&+", r):
			continue
		default:
			return false
		}
	}

	return letters > 0 && alphaNum >= 6
}

func blueprintTargetMatches(cachedTarget, requestedTarget EpisodeTarget) bool {
	return cachedTarget == requestedTarget
}

func GetMediaStreamForEpisode(ctx context.Context, files []UnpackableFile, cachedBP Blueprint, password string, target EpisodeTarget) (ReadSeekCloser, string, int64, Blueprint, error) {
	return GetMediaStreamForEpisodeWithHints(ctx, files, cachedBP, password, target, StreamSelectionHints{AllowLargestDirectFallback: true})
}

func GetMediaStreamForEpisodeWithHints(ctx context.Context, files []UnpackableFile, cachedBP Blueprint, password string, target EpisodeTarget, hints StreamSelectionHints) (ReadSeekCloser, string, int64, Blueprint, error) {
	if err := contextErr(ctx); err != nil {
		return nil, "", 0, nil, err
	}
	logger.Debug("GetMediaStreamForEpisode starting",
		"target", target,
		"files", len(files),
		"cached_type", fmt.Sprintf("%T", cachedBP))
	rarScanCtx := loader.WithSkipGapProbing(ctx, true)
	if cachedBP != nil {
		cachedTarget := cachedBP.TargetEpisode()
		if !blueprintTargetMatches(cachedTarget, target) {
			logger.Debug("Skipping cached blueprint due to target mismatch",
				"kind", cachedBP.Kind(), "cached", cachedTarget, "requested", target)
		} else {
			logger.Debug("Using cached blueprint",
				"kind", cachedBP.Kind(), "cached", cachedTarget, "requested", target)
			stream, name, size, err := cachedBP.Open(rarScanCtx, files, password)
			// A stale direct index means the release's file list changed under
			// the cached plan; fall through to a fresh scan rather than failing.
			if !errors.Is(err, ErrBlueprintFileIndexOutOfRange) {
				return stream, name, size, cachedBP, err
			}
			logger.Debug("Cached direct blueprint index no longer valid, rescanning",
				"cached", cachedTarget, "files", len(files))
		}
	}

	// Everything below routes on filenames, so give the release its names back
	// first. This is a no-op — and reads nothing — unless the subjects are
	// obfuscated.
	files = resolveObfuscatedNames(rarScanCtx, files)

	rarFiles := filterRarFiles(files)
	var rarScanFailed bool
	var rarScanErr error
	if len(rarFiles) > 0 {
		logger.Trace("Detected RAR archive", "target", target, "volumes", len(rarFiles))
		unpackables := make([]UnpackableFile, len(files))
		copy(unpackables, files)
		bp, err := ScanArchive(rarScanCtx, unpackables, password, target)
		if err != nil {
			if errors.Is(err, ErrEpisodeTargetNotFound) {
				return nil, "", 0, &FailedBlueprint{Err: err, Target: target}, err
			}
			rarScanFailed = true
			rarScanErr = maybeMarkArchiveFastProbe(rarScanCtx, err)
			logger.Warn("ScanArchive failed, falling back to other methods", "err", err)
		} else {
			s, name, size, err := StreamFromBlueprint(rarScanCtx, bp, password)
			if err != nil {
				return nil, "", 0, nil, err
			}
			return s, name, size, bp, nil
		}
		// Only say PAR2 when the error actually points there. Every other scan
		// failure reported as "requires PAR2 repair" sends the reader chasing a
		// repair that cannot help.
		if errors.Is(rarScanErr, ErrPAR2RepairRequired) {
			logger.Warn("RAR analysis failed; release likely requires PAR2 repair", "target", target, "err", rarScanErr)
		} else {
			logger.Warn("RAR analysis failed", "target", target, "err", rarScanErr)
		}
	}

	archiveFiles, err := Identify7zParts(files)
	if err != nil && !errors.Is(err, ErrNo7zFiles) {
		logger.Warn("7z archive identification failed", "target", target, "err", err)
		return nil, "", 0, &FailedBlueprint{Err: err, Target: target}, err
	}
	if len(archiveFiles) == 0 && rarScanFailed {
		// A 7z posted as bare .001/.002 volumes has no ".7z" in any name, so it
		// lands in the RAR filter first; once that scan has failed, one
		// signature read of the first part settles what the set really is.
		if sigParts, sigErr := Identify7zSplitPartsBySignature(rarScanCtx, files); sigErr == nil {
			logger.Info("Numeric split volumes identified as 7z by signature", "target", target, "parts", len(sigParts))
			archiveFiles = sigParts
		}
	}
	if len(archiveFiles) > 0 {
		firstVolName := ExtractFilename(archiveFiles[0].Name())
		logger.Info("Detected 7z archive", "target", target, "name", firstVolName, "parts", len(archiveFiles), "mode", "fast")
		newBp, err := CreateSevenZipBlueprint(rarScanCtx, archiveFiles, firstVolName, password, target)
		if err == nil {
			s, n, sz, err := Open7zStreamFromBlueprint(rarScanCtx, newBp, password)
			if err != nil {
				err = maybeMarkArchiveFastProbe(rarScanCtx, err)
			}
			return s, n, sz, newBp, err
		}

		// Fall back to 7z nested archive expansion (7z in 7z, RAR in 7z)
		if s, n, sz, bp, nestedErr := TrySevenZipNestedArchive(rarScanCtx, archiveFiles, password, target); nestedErr == nil {
			return s, n, sz, bp, nil
		} else if errors.Is(nestedErr, ErrEpisodeTargetNotFound) {
			return nil, "", 0, &FailedBlueprint{Err: nestedErr, Target: target}, nestedErr
		}

		err = maybeMarkArchiveFastProbe(rarScanCtx, err)
		return nil, "", 0, nil, err
	}

	if directIdx, err := selectDirectFileIndex(files, target); err != nil {
		return nil, "", 0, &FailedBlueprint{Err: err, Target: target}, err
	} else if directIdx >= 0 {
		f := files[directIdx]
		name := ExtractFilename(f.Name())
		stream, err := f.OpenStreamCtx(rarScanCtx)
		if err != nil {
			return nil, "", 0, nil, err
		}
		logger.Debug("Selected direct playback file", "target", target, "name", name, "index", directIdx, "size", f.Size())
		return stream, name, f.Size(), &DirectBlueprint{FileName: name, FileIndex: directIdx, Target: target}, nil
	}

	if probedStream, probedName, probedSize, probedIdx, ok := probeDirectPlayableCandidates(rarScanCtx, files); ok {
		logger.Debug("Selected direct playback file via content probe",
			"target", target,
			"name", probedName,
			"index", probedIdx,
			"size", probedSize)
		return probedStream, probedName, probedSize, &DirectBlueprint{FileName: probedName, FileIndex: probedIdx, Target: target}, nil
	}

	var largestFile UnpackableFile
	var largestIdx int
	for i, f := range files {
		name := strings.ToLower(ExtractFilename(f.Name()))
		if strings.HasSuffix(name, ExtRar) || strings.Contains(name, ".part") || IsRarPart(name) || IsSplitArchivePart(name) {
			continue
		}
		if strings.HasSuffix(name, ExtPar2) || strings.HasSuffix(name, ExtNzb) || strings.HasSuffix(name, ExtNfo) || strings.HasSuffix(name, ExtIso) {
			continue
		}
		if largestFile == nil || f.Size() > largestFile.Size() {
			largestFile = f
			largestIdx = i
		}
	}

	if largestFile != nil && largestFile.Size() > 50*1024*1024 {
		if !rarScanFailed {
			logger.Warn("No clear media found, probing largest file", "name", largestFile.Name(), "size", largestFile.Size())
			unpackables := make([]UnpackableFile, len(files))
			copy(unpackables, files)
			logger.Info("Attempting heuristic RAR scan on unknown files")
			bp, err := ScanArchive(rarScanCtx, unpackables, password, target)
			if err == nil {
				s, name, size, err := StreamFromBlueprint(rarScanCtx, bp, password)
				if err == nil {
					logger.Info("Heuristic scan found RAR archive")
					return s, name, size, bp, nil
				}
			} else if errors.Is(err, ErrEpisodeTargetNotFound) {
				return nil, "", 0, &FailedBlueprint{Err: err, Target: target}, err
			} else {
				logger.Warn("Heuristic RAR scan failed, falling back to direct stream", "err", err)
			}
		}
		extractedName := ExtractFilename(largestFile.Name())
		if !hints.AllowLargestDirectFallback {
			err := fmt.Errorf("%w: largest direct fallback disabled", io.EOF)
			if target.Valid() {
				err = fmt.Errorf("%w: no direct media candidate matched season=%d episode=%d", ErrEpisodeTargetNotFound, target.Season, target.Episode)
				logger.Warn("Refusing largest-file fallback for targeted episode request",
					"target", target,
					"name", extractedName,
					"index", largestIdx,
					"size", largestFile.Size())
			} else {
				logger.Warn("Refusing largest-file fallback by selection hints",
					"target", target,
					"name", extractedName,
					"index", largestIdx,
					"size", largestFile.Size())
			}
			return nil, "", 0, &FailedBlueprint{Err: err, Target: target}, err
		}
		if !isPlausibleLargestDirectFallbackName(extractedName) {
			err := fmt.Errorf("%w: suspicious largest direct fallback candidate %q", io.EOF, extractedName)
			logger.Warn("Refusing suspicious largest-file fallback",
				"target", target,
				"name", extractedName,
				"index", largestIdx,
				"size", largestFile.Size())
			return nil, "", 0, &FailedBlueprint{Err: err, Target: target}, err
		}
		stream, err := largestFile.OpenStreamCtx(rarScanCtx)
		if err != nil {
			return nil, "", 0, nil, err
		}
		logger.Debug("Falling back to largest direct file", "target", target, "name", extractedName, "index", largestIdx, "size", largestFile.Size())
		return stream, extractedName, largestFile.Size(), &DirectBlueprint{FileName: extractedName, FileIndex: largestIdx, Target: target}, nil
	}

	logger.Warn("GetMediaStream found no suitable media", "target", target, "files", len(files), "rar_candidates", len(rarFiles))
	finalErr := io.EOF
	if rarScanErr != nil {
		finalErr = fmt.Errorf("no suitable media stream: RAR scan failed: %w", rarScanErr)
	} else {
		finalErr = fmt.Errorf("no suitable media stream: no direct video candidates and no playable archive content")
	}
	return nil, "", 0, &FailedBlueprint{Err: finalErr, Target: target}, finalErr
}

type directProbeCandidate struct {
	idx  int
	file UnpackableFile
	name string
	size int64
}

func probeDirectPlayableCandidates(ctx context.Context, files []UnpackableFile) (ReadSeekCloser, string, int64, int, bool) {
	candidates := make([]directProbeCandidate, 0, len(files))
	for i, f := range files {
		if f == nil {
			continue
		}
		name := ExtractFilename(f.Name())
		lower := strings.ToLower(name)
		if strings.HasSuffix(lower, ExtRar) || strings.Contains(lower, ".part") || IsRarPart(lower) || IsSplitArchivePart(lower) {
			continue
		}
		if strings.HasSuffix(lower, ExtPar2) || strings.HasSuffix(lower, ExtNzb) || strings.HasSuffix(lower, ExtNfo) || strings.HasSuffix(lower, ExtIso) {
			continue
		}
		size := f.Size()
		if size < 50*1024*1024 {
			continue
		}
		candidates = append(candidates, directProbeCandidate{idx: i, file: f, name: name, size: size})
	}
	if len(candidates) == 0 {
		return nil, "", 0, -1, false
	}
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].size > candidates[j].size })
	if len(candidates) > maxDirectProbeCandidates {
		candidates = candidates[:maxDirectProbeCandidates]
	}

	for _, c := range candidates {
		if err := contextErr(ctx); err != nil {
			return nil, "", 0, -1, false
		}
		stream, err := c.file.OpenStreamCtx(ctx)
		if err != nil {
			continue
		}
		probeErr := ProbeMediaStreamByContent(ctx, stream, c.name, c.size)
		if probeErr == nil {
			return stream, c.name, c.size, c.idx, true
		}
		_ = stream.Close()
	}
	return nil, "", 0, -1, false
}
