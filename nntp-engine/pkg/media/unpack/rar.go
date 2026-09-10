package unpack

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"

	"streamnzb/pkg/core/logger"

	"github.com/javi11/rardecode/v2"
)

var ErrPAR2RepairRequired = errors.New("likely PAR2 repair required")

// ErrCompressedArchive means the release packs its media with real compression
// instead of STORE mode. Streaming maps byte ranges straight onto the packed
// volume bytes, so such a release can never play — not on another provider, not
// on a retry, not after a repair. It is definitive evidence about the release
// itself, so it must never be softened into an inconclusive fast-probe result.
var ErrCompressedArchive = errors.New("compressed archive -- STORE mode required for streaming")

// ErrNestedSetIncomplete means an inner archive set was enumerated from an
// outer scan that could not read every outer volume, so the inner volumes we
// know about are a subset of what the release actually contains. This is a
// scan limitation, not a damaged release: PAR2 cannot fix it, and the PAR2
// files live in the outer release where a nested scan cannot see them.
var ErrNestedSetIncomplete = errors.New("nested archive set incompletely enumerated")

type ArchiveBlueprint struct {
	MainFileName string
	TotalSize    int64
	Parts        []VirtualPartDef
	IsCompressed bool
	AnyEncrypted bool
	Target       EpisodeTarget
}

type VirtualPartDef struct {
	VirtualStart int64
	VirtualEnd   int64
	VolFile      UnpackableFile
	VolOffset    int64
	AesKey       []byte
	AesIV        []byte
}

func StreamFromBlueprint(ctx context.Context, bp *ArchiveBlueprint, password string) (io.ReadSeekCloser, string, int64, error) {
	if bp.IsCompressed {
		return nil, "", 0, fmt.Errorf("compressed RAR archive (file: %s): %w", bp.MainFileName, ErrCompressedArchive)
	}

	// Password-protected RAR5 releases sometimes list volumes without
	// AnyEncrypted/file Encrypted flags set. Route any passworded release
	// through rardecode so playback sees decrypted bytes.
	if bp.AnyEncrypted || password != "" {
		if password == "" {
			return nil, "", 0, fmt.Errorf("password-protected RAR (file: %s) -- password required from NZB head", bp.MainFileName)
		}
		return streamEncryptedRAR(ctx, bp, password)
	}

	// Blueprint virtual stream jumps straight to the target volume/offset
	// (OpenReaderAt). rardecode seek walks packed blocks across every volume
	// sequentially, which makes HTTP range seeks stall for minutes on huge sets.
	stream, name, size := newVirtualStreamFromBlueprint(ctx, bp)
	if len(bp.Parts) > 1 {
		logger.Debug("Serving multi-volume RAR via virtual blueprint", "file", name, "parts", len(bp.Parts))
	}
	return stream, name, size, nil
}

func newVirtualStreamFromBlueprint(ctx context.Context, bp *ArchiveBlueprint) (io.ReadSeekCloser, string, int64) {
	parts := make([]virtualPart, len(bp.Parts))
	for i, p := range bp.Parts {
		parts[i] = virtualPart(p)
	}
	return NewVirtualStream(ctx, parts, bp.TotalSize, 0), bp.MainFileName, bp.TotalSize
}

func streamEncryptedRAR(ctx context.Context, bp *ArchiveBlueprint, password string) (io.ReadSeekCloser, string, int64, error) {
	if err := contextErr(ctx); err != nil {
		return nil, "", 0, err
	}

	if !bp.AnyEncrypted {
		logger.Debug("Archive has no encrypted parts in blueprint, serving via plaintext virtual stream", "file", bp.MainFileName)
		stream, name, size := newVirtualStreamFromBlueprint(ctx, bp)
		return stream, name, size, nil
	}

	var aesKey, aesIV []byte
	var packedSize int64
	parts := make([]virtualPart, len(bp.Parts))
	for i, p := range bp.Parts {
		parts[i] = virtualPart{
			VirtualStart: p.VirtualStart,
			VirtualEnd:   p.VirtualEnd,
			VolFile:      p.VolFile,
			VolOffset:    p.VolOffset,
			AesKey:       p.AesKey,
			AesIV:        p.AesIV,
		}
		packedSize += (p.VirtualEnd - p.VirtualStart)
		if len(p.AesKey) > 0 && len(aesKey) == 0 {
			aesKey = p.AesKey
			aesIV = p.AesIV
		}
	}

	if len(aesKey) > 0 {
		logger.Debug("Serving multi-volume encrypted RAR via encrypted virtual stream", "file", bp.MainFileName, "parts", len(bp.Parts))
		stream := NewEncryptedVirtualStream(ctx, parts, bp.TotalSize, packedSize, aesKey, aesIV, 0)
		return stream, bp.MainFileName, bp.TotalSize, nil
	}

	if len(bp.Parts) > 1 {
		stream, name, size, decErr := streamRARFromDecoder(ctx, bp, password)
		if decErr == nil {
			logger.Debug("Serving multi-volume encrypted RAR via rardecode fallback", "file", name, "parts", len(bp.Parts))
			return stream, name, size, nil
		}
		logger.Debug("Multi-volume rardecode stream fallback failed for encrypted archive, trying standard virtual path", "file", bp.MainFileName, "err", decErr)
	}

	// Fall back to standard unencrypted stream. The archive is either not
	// encrypted, or we don't have the correct password/keys to decrypt it.
	logger.Debug("Encrypted virtual stream fallback to plaintext", "file", bp.MainFileName)
	stream, name, size := newVirtualStreamFromBlueprint(ctx, bp)
	return stream, name, size, nil
}

// maxFirstVolumesToScan caps how many "first" volumes we try when many files are
// treated as first volumes (e.g. non-standard names like .100, .101). Prevents
// failing slowly across hundreds of volumes; we try a few and fail fast.
const maxFirstVolumesToScan = 5

// scanVolumeBufferSize is the read buffer rardecode is given over a
// network-backed volume. Its default is 4 KiB — sized for local disks — and
// every fill of it is a full trip through fileWrapper.Read, File.ReadAt, the
// inflight-download map and the segment cache, so a header scan paid that trip
// ~190 times per already-downloaded segment. Reads are segment-granular either
// way (a 4 KiB read still fetches the whole ~700 KB article), so a buffer of a
// third of a segment costs no extra bytes and removes ~64x of the per-read
// overhead.
const scanVolumeBufferSize = 256 << 10

const (
	// nestedRescanBatch: outer volumes probed per round when hunting for a
	// nested set's first volume. Large enough to keep the scan parallel, small
	// enough that the hunt stops soon after it finds what it came for.
	nestedRescanBatch = 8
	// maxNestedRescanVolumes: ceiling on that hunt. The inner first volume sits
	// in exactly one outer volume, so if this many have not produced it, the
	// remaining ones are very unlikely to and the client is already waiting.
	maxNestedRescanVolumes = 32
)

type firstVolumeCandidate struct {
	file  UnpackableFile
	score int
	order int
}

func selectFirstVolumesForFastScan(files []UnpackableFile, limit int) []UnpackableFile {
	if len(files) <= limit {
		out := make([]UnpackableFile, len(files))
		copy(out, files)
		return out
	}
	candidates := make([]firstVolumeCandidate, 0, len(files))
	for idx, f := range files {
		candidates = append(candidates, firstVolumeCandidate{
			file:  f,
			score: firstVolumeScore(f, idx, len(files)),
			order: idx,
		})
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].score == candidates[j].score {
			return candidates[i].order < candidates[j].order
		}
		return candidates[i].score > candidates[j].score
	})
	out := make([]UnpackableFile, 0, limit)
	for i := 0; i < len(candidates) && i < limit; i++ {
		out = append(out, candidates[i].file)
	}
	return out
}

func firstVolumeScore(file UnpackableFile, index, total int) int {
	name := strings.ToLower(ExtractFilename(file.Name()))
	score := 0

	// Prefer names that are likely canonical first volumes.
	switch {
	case strings.HasSuffix(name, ExtRar) && !strings.Contains(name, ".part"):
		score += 1200
	case strings.Contains(name, ".part001.") || strings.Contains(name, ".part01."):
		score += 1100
	case strings.Contains(name, ".part1."):
		score += 1000
	case strings.HasSuffix(name, ".001"):
		score += 900
	case strings.HasSuffix(name, ".r00"):
		score += 300
	}

	if IsMiddleRarVolume(name) {
		score -= 800
	}

	// NZB-order tie-breaker: keep earlier files slightly preferred.
	if total > 0 {
		score += (total - index)
	}

	// Subject counters often indicate segment/part ordering when filenames are obfuscated.
	if part, ok := parseSubjectPartCounter(strings.ToLower(file.Name())); ok {
		switch {
		case part == 1:
			score += 200
		case part <= 3:
			score += 100
		default:
			score -= 50
		}
	}

	return score
}

func parseSubjectPartCounter(subject string) (int, bool) {
	closeIdx := strings.Index(subject, "]")
	if closeIdx <= 0 {
		return 0, false
	}
	openIdx := strings.LastIndex(subject[:closeIdx], "[")
	if openIdx < 0 || openIdx+1 >= closeIdx {
		return 0, false
	}
	token := strings.TrimSpace(subject[openIdx+1 : closeIdx])
	slashIdx := strings.Index(token, "/")
	if slashIdx <= 0 {
		return 0, false
	}
	partStr := strings.TrimSpace(token[:slashIdx])
	part := 0
	for _, r := range partStr {
		if r < '0' || r > '9' {
			return 0, false
		}
		part = part*10 + int(r-'0')
	}
	if part <= 0 {
		return 0, false
	}
	return part, true
}

func ScanArchive(ctx context.Context, files []UnpackableFile, password string, target EpisodeTarget) (*ArchiveBlueprint, error) {
	if err := contextErr(ctx); err != nil {
		return nil, err
	}
	ctx = WithArchiveScanIOTrace(ctx)
	scanTraceReadPos.Store(0)
	rarFiles := filterRarFiles(files)
	if len(rarFiles) == 0 {
		return nil, errors.New("no RAR files found")
	}

	allFirstVols := filterFirstVolumes(rarFiles)
	if len(allFirstVols) == 0 {
		// An inner set only contains what the outer scan managed to enumerate.
		// Blaming the release for a missing first volume here would be wrong:
		// the outer volumes that would have named it may simply have failed to
		// list. There is also no PAR2 among these files to repair from.
		if depth := NestDepthFromContext(ctx); depth > 0 {
			logger.Warn("Nested archive set has no first volume; the outer scan did not enumerate it",
				"inner_volumes", len(rarFiles), "depth", depth)
			return nil, fmt.Errorf("no first volume among the %d inner volume(s) enumerated from the outer archive: %w",
				len(rarFiles), ErrNestedSetIncomplete)
		}
		logger.Warn("No candidate first volume found in RAR set, attempting targeted Volume 1 PAR2 repair", "rar_files", len(rarFiles))
		repairedVol, repairErr := RepairFirstVolumeWithPAR2(ctx, files)
		if repairErr == nil && repairedVol != nil {
			logger.Info("Successfully repaired missing Volume 1 via PAR2", "name", repairedVol.Name(), "size", repairedVol.Size())
			allFirstVols = []UnpackableFile{repairedVol}
			rarFiles = append([]UnpackableFile{repairedVol}, rarFiles...)
		} else {
			logger.Warn("Targeted Volume 1 PAR2 repair unavailable", "err", repairErr)
			return nil, fmt.Errorf("first RAR volume (.rar / .part01.rar) missing from release (%d continuation volume(s) present): %w", len(rarFiles), ErrPAR2RepairRequired)
		}
	}
	firstVols := make([]UnpackableFile, len(allFirstVols))
	copy(firstVols, allFirstVols)
	tryingCount := len(firstVols)
	if tryingCount > maxFirstVolumesToScan {
		firstVols = selectFirstVolumesForFastScan(firstVols, maxFirstVolumesToScan)
		logger.Debug("Limiting RAR first-volume scan to fail fast", "trying", len(firstVols), "skipped", tryingCount-len(firstVols))
	}
	runScan := func(scanFirstVols []UnpackableFile, mode string) (*ArchiveBlueprint, error) {
		if len(scanFirstVols) == 0 {
			return nil, fmt.Errorf("no RAR volumes available to scan out of %d RAR file(s)", len(rarFiles))
		}
		logger.Debug("Scanning RAR first volumes", "target", target, "count", len(scanFirstVols), "total", len(rarFiles), "mode", mode)
		start := time.Now()
		parts, diagnostics, err := scanVolumesParallel(ctx, scanFirstVols, password)
		if err != nil {
			return nil, err
		}

		for _, f := range scanFirstVols {
			if fc, ok := f.(interface{ IsFailed() bool }); ok && fc.IsFailed() {
				logger.Error("First volume failed too many segments, aborting scan", "file", f.Name())
				return nil, fmt.Errorf("first volume unavailable: %w", ErrTooManyZeroFills)
			}
		}

		logger.Info("RAR scan complete", "files", len(rarFiles), "duration", time.Since(start), "mode", mode)
		if diagnostics.failedScans > 0 {
			logger.Warn("RAR volume header scan encountered failures",
				"mode", mode,
				"failed_scans", diagnostics.failedScans,
				"total_scanned", len(scanFirstVols),
				"last_vol", diagnostics.lastVolName,
				"last_err", diagnostics.lastErr,
				"invalid_blocks", diagnostics.invalidBlocks,
				"unexpected_eof", diagnostics.unexpectedEOF,
				"decode_corruption", diagnostics.decodeCorruption)
		}
		if len(parts) == 0 {
			if err := diagnostics.classifyArchiveError(); err != nil {
				return nil, err
			}
			if diagnostics.failedScans > 0 {
				return nil, fmt.Errorf("RAR header scan failed on %d volume(s) (last error on %s: %w)", diagnostics.failedScans, diagnostics.lastVolName, diagnostics.lastErr)
			}
		}

		for _, p := range parts {
			if p.isCompressed {
				return nil, fmt.Errorf("compressed RAR archive (file: %s): %w", p.name, ErrCompressedArchive)
			}
		}

		return buildBlueprint(ctx, parts, rarFiles, password, target)
	}

	bp, err := runScan(firstVols, "fast")
	if err == nil {
		return bp, nil
	}

	// Fast failover mode is always enabled: obfuscated sets can have random volume
	// names where alphabetical top-N may miss the true first volume, but we keep
	// fail-fast behavior and skip the exhaustive retry to avoid slow startup.
	if len(firstVols) < len(allFirstVols) {
		logger.Warn("RAR fast first-volume scan failed, skipping exhaustive scan",
			"target", target,
			"fast_count", len(firstVols),
			"full_count", len(allFirstVols),
			"err", err)
	}

	return nil, err
}

type filePart struct {
	name         string
	unpackedSize int64
	dataOffset   int64
	packedSize   int64
	volFile      UnpackableFile
	volName      string
	isMedia      bool
	isCompressed bool
	isEncrypted  bool
	aesKey       []byte
	aesIV        []byte
}

type scanDiagnostics struct {
	failedScans      int
	evidenceScans    int
	invalidBlocks    int
	unexpectedEOF    int
	decodeCorruption int
	lastErr          error
	lastVolName      string
}

func (d scanDiagnostics) classifyArchiveError() error {
	if d.failedScans == 0 {
		return nil
	}
	// Require explicit decode-corruption evidence; "invalid file block"
	// alone can happen when scanning non-first volumes in obfuscated sets.
	if d.decodeCorruption == 0 {
		return nil
	}
	// Classify as likely corruption only when most failures look like
	// broken article/volume bytes rather than generic parse errors.
	evidence := d.evidenceScans
	if evidence == 0 {
		return nil
	}
	if evidence*2 < d.failedScans {
		return nil
	}
	return fmt.Errorf(
		"archive data appears corrupted or incomplete (%d/%d corrupt scans; invalid_blocks=%d unexpected_eof=%d decode_corruption=%d): %w",
		evidence,
		d.failedScans,
		d.invalidBlocks,
		d.unexpectedEOF,
		d.decodeCorruption,
		ErrPAR2RepairRequired,
	)
}

func scanVolumesParallel(ctx context.Context, files []UnpackableFile, password string) ([]filePart, scanDiagnostics, error) {
	var mu sync.Mutex
	var result []filePart
	diag := scanDiagnostics{}
	sem := make(chan struct{}, 20)
	var wg sync.WaitGroup
	var firstErr error
	var firstErrMu sync.Mutex

	setFirstErr := func(err error) {
		if err == nil {
			return
		}
		firstErrMu.Lock()
		if firstErr == nil {
			firstErr = err
		}
		firstErrMu.Unlock()
	}

	for _, f := range files {
		if err := contextErr(ctx); err != nil {
			setFirstErr(err)
			break
		}
		var acquired bool
		select {
		case sem <- struct{}{}:
			acquired = true
		case <-ctx.Done():
			setFirstErr(ctx.Err())
		}
		if !acquired {
			break
		}
		wg.Add(1)
		go func(f UnpackableFile) {
			defer wg.Done()
			defer func() { <-sem }()
			defer func() {
				if r := recover(); r != nil {
					logger.Error("Panic scanning RAR", "file", f.Name(), "err", r)
				}
			}()
			if err := contextErr(ctx); err != nil {
				setFirstErr(err)
				return
			}

			cleanName := ExtractFilename(f.Name())
			fsys := NewNZBFSFromMapCtx(ctx, map[string]UnpackableFile{cleanName: f})
			// ListTolerant: this maps only a single volume; a split file that
			// continues into sibling volumes must still be discoverable here. The full
			// per-volume layout is stitched later by aggregateRemainingVolumes.
			// ListFromAnyVolume: every volume but the first opens on a continuation
			// block, so without it each of those scans fails outright and the set
			// looks like it holds only whatever the first volume happened to name.
			listOpts := []rardecode.Option{rardecode.FileSystem(fsys), rardecode.BufferSize(scanVolumeBufferSize), rardecode.ParallelRead(false), rardecode.SkipVolumeCheck, rardecode.ListTolerant, rardecode.ListFromAnyVolume}
			if password != "" {
				listOpts = append(listOpts, rardecode.Password(password))
			}
			infos, err := rardecode.ListArchiveInfo(cleanName, listOpts...)
			if err != nil {
				if ctxErr := contextErr(ctx); ctxErr != nil {
					setFirstErr(ctxErr)
					return
				}
				logger.Debug("Scan failure", "name", cleanName, "err", err, "virtual_size", f.Size())
				logRARScanDebug(f, err)
				mu.Lock()
				diag.failedScans++
				diag.lastErr = err
				diag.lastVolName = cleanName
				msg := strings.ToLower(err.Error())
				scanHasEvidence := false
				if strings.Contains(msg, "invalid file block") {
					diag.invalidBlocks++
					scanHasEvidence = true
				}
				if strings.Contains(msg, "unexpected eof") {
					diag.unexpectedEOF++
					scanHasEvidence = true
				}
				if strings.Contains(msg, "data corruption detected") ||
					strings.Contains(msg, "without finding \"=yend\" trailer") ||
					strings.Contains(msg, "without finding '=yend' trailer") ||
					strings.Contains(msg, "rapidyenc") {
					diag.decodeCorruption++
					scanHasEvidence = true
				}
				if scanHasEvidence {
					diag.evidenceScans++
				}
				mu.Unlock()
			}

			for _, info := range infos {
				if err := contextErr(ctx); err != nil {
					setFirstErr(err)
					return
				}
				if info.Name == "" {
					continue
				}
				logger.Debug("Found file in archive", "vol", cleanName, "name", info.Name, "size", info.TotalUnpackedSize)

				compressed := false
				for _, p := range info.Parts {
					if p.CompressionMethod != "stored" {
						compressed = true
					}
				}

				fileEncrypted := info.AnyEncrypted
				if !fileEncrypted {
					for _, p := range info.Parts {
						if p.Encrypted || len(p.AesKey) > 0 {
							fileEncrypted = true
							break
						}
					}
				}

				for _, p := range info.Parts {
					mu.Lock()
					result = append(result, filePart{
						name:         info.Name,
						unpackedSize: info.TotalUnpackedSize,
						dataOffset:   p.DataOffset,
						packedSize:   p.PackedSize,
						volFile:      f,
						volName:      f.Name(),
						isMedia:      isMediaFile(info),
						isCompressed: compressed,
						isEncrypted:  fileEncrypted,
						aesKey:       p.AesKey,
						aesIV:        p.AesIV,
					})
					mu.Unlock()
				}
			}
		}(f)
	}
	wg.Wait()
	if firstErr != nil {
		return nil, scanDiagnostics{}, firstErr
	}
	return result, diag, nil
}

func buildBlueprint(ctx context.Context, parts []filePart, allRarFiles []UnpackableFile, password string, target EpisodeTarget) (*ArchiveBlueprint, error) {
	if err := contextErr(ctx); err != nil {
		return nil, err
	}
	bestName, err := selectMainFile(parts, target)
	if err != nil {
		logger.Debug("RAR blueprint direct media selection failed for requested episode, trying nested archive", "target", target, "err", err)
		if bp, nestedErr := tryNestedArchive(ctx, parts, allRarFiles, password, target); nestedErr == nil {
			return bp, nil
		}
		return nil, err
	}
	logger.Debug("RAR blueprint main file decision", "target", target, "selected", bestName, "parts", len(parts), "rar_files", len(allRarFiles))

	if bestName != "" {
		var mediaTotal, archiveTotal int64
		for _, p := range parts {
			if p.isMedia {
				mediaTotal += p.packedSize
			} else if IsArchiveFile(p.name) {
				archiveTotal += p.packedSize
			}
		}
		if archiveTotal > mediaTotal*2 {
			logger.Info("Archive content outweighs direct media, trying nested archive first",
				"media", mediaTotal, "archive", archiveTotal, "sample", bestName)
			if bp, err := tryNestedArchive(ctx, parts, allRarFiles, password, target); err == nil {
				return bp, nil
			}
		}
	}

	if bestName == "" {
		logger.Debug("RAR blueprint found no direct media match, trying nested archive", "target", target, "parts", len(parts))
		bp, nestedErr := tryNestedArchive(ctx, parts, allRarFiles, password, target)
		if nestedErr == nil {
			return bp, nil
		}
		// Definitive verdicts propagate untouched; only a nested scan that
		// found nothing playable falls through to the ISO.
		if errors.Is(nestedErr, ErrCompressedArchive) || errors.Is(nestedErr, ErrEpisodeTargetNotFound) {
			return nil, nestedErr
		}
		// A stored .iso is not media by name, and it is not a scannable
		// archive either — but its bytes are a payload most players open
		// (BD/DVD images), and serving them beats a dead end. The client
		// decides what it can play; see the codec-responsibility rule.
		if isoName := selectLargestStoredISO(parts); isoName != "" {
			logger.Info("No media or nested archive found; streaming the stored ISO payload", "target", target, "name", isoName)
			bestName = isoName
		} else {
			return nil, nestedErr
		}
	}

	logger.Info("Selected main media", "target", target, "name", bestName)

	mainParts := collectParts(parts, bestName)
	sortByVolume(mainParts)

	headerSize := mainParts[0].unpackedSize
	scannedSize := totalPackedSize(mainParts)

	anyEncrypted := false
	for _, p := range mainParts {
		if p.isEncrypted {
			anyEncrypted = true
			break
		}
	}

	targetSize := headerSize
	if anyEncrypted {
		targetSize = roundUpTo16(headerSize)
	}

	if scannedSize < targetSize && len(allRarFiles) > len(mainParts) {
		mainParts, err = aggregateRemainingVolumes(ctx, mainParts, allRarFiles, bestName, targetSize, password)
		if err != nil {
			return nil, err
		}
	}

	mainParts = reconcileMainPartsPackedSpan(mainParts, targetSize)

	compressed := false
	anyEncrypted = false
	for _, p := range mainParts {
		if p.isCompressed {
			compressed = true
		}
		if p.isEncrypted {
			anyEncrypted = true
		}
	}

	bp := &ArchiveBlueprint{
		MainFileName: bestName,
		TotalSize:    headerSize,
		IsCompressed: compressed,
		AnyEncrypted: anyEncrypted,
		Target:       target,
	}

	var vOffset int64
	for i, p := range mainParts {
		bp.Parts = append(bp.Parts, VirtualPartDef{
			VirtualStart: vOffset,
			VirtualEnd:   vOffset + p.packedSize,
			VolFile:      p.volFile,
			VolOffset:    p.dataOffset,
			AesKey:       p.aesKey,
			AesIV:        p.aesIV,
		})
		if i < 3 || i >= len(mainParts)-2 {
			logger.Trace("Blueprint part", "idx", i, "vStart", vOffset, "vEnd", vOffset+p.packedSize, "volOff", p.dataOffset, "packed", p.packedSize)
		}
		vOffset += p.packedSize
	}

	logger.Trace("Blueprint total", "vOffset", vOffset, "headerSize", headerSize, "parts", len(mainParts))

	if vOffset != headerSize {
		logger.Debug("Blueprint packed span differs from unpacked header",
			"header", headerSize, "packed", vOffset, "parts", len(mainParts))
	}

	// Packed volume totals can be smaller than unpacked media size; only clamp
	// for direct VirtualStream reads. Decrypting streams must keep unpacked size.
	if vOffset < headerSize && !anyEncrypted && password == "" {
		logger.Debug("Adjusting stream size", "header", headerSize, "actual", vOffset)
		bp.TotalSize = vOffset
	}

	primeUniformSegmentMapsForPlayback(allRarFiles)

	return bp, nil
}

type uniformSegmentMapPrimer interface {
	PrimeUniformSegmentMapFromEstimator() bool
}

func primeUniformSegmentMapsForPlayback(files []UnpackableFile) {
	var primed int
	for _, f := range files {
		if p, ok := f.(uniformSegmentMapPrimer); ok && p.PrimeUniformSegmentMapFromEstimator() {
			primed++
		}
	}
	if primed > 0 {
		logger.Debug("Primed uniform segment maps for playback", "files", primed, "total", len(files))
	}
}

// selectLargestStoredISO picks the biggest uncompressed .iso among the
// scanned parts, summing packed size across its volumes the way selectMainFile
// does for media. Sample images never qualify.
func selectLargestStoredISO(parts []filePart) string {
	sizes := make(map[string]int64)
	for _, p := range parts {
		lower := strings.ToLower(p.name)
		if !strings.HasSuffix(lower, ExtIso) || p.isCompressed || IsSampleFile(p.name) {
			continue
		}
		sizes[p.name] += p.packedSize
	}
	best, bestSize := "", int64(0)
	for name, size := range sizes {
		if size > bestSize {
			best, bestSize = name, size
		}
	}
	return best
}

func selectMainFile(parts []filePart, target EpisodeTarget) (string, error) {
	type mediaChoice struct {
		size  int64
		order int
	}
	choices := make(map[string]*mediaChoice)
	order := 0
	for _, p := range parts {
		if p.isMedia {
			choice, ok := choices[p.name]
			if !ok {
				choice = &mediaChoice{order: order}
				choices[p.name] = choice
				order++
			}
			choice.size += p.packedSize
		}
	}
	candidates := make([]namedEpisodeCandidate, 0, len(choices))
	for name, choice := range choices {
		candidates = append(candidates, namedEpisodeCandidate{Name: name, Size: choice.size, Index: choice.order, Order: choice.order})
	}
	if len(candidates) == 0 {
		logger.Debug("RAR main file selection found no media candidates", "target", target)
		return "", nil
	}
	if best, ok, err := selectEpisodeCandidateOrError(candidates, target, "rar_main_media"); err != nil {
		return "", err
	} else if ok {
		logger.Debug("RAR main file selected by episode match", "target", target, "name", best.Name, "size", best.Size, "order", best.Order, "candidates", len(candidates))
		return best.Name, nil
	}
	var best namedEpisodeCandidate
	found := false
	for _, candidate := range candidates {
		if !found || candidate.Size > best.Size || (candidate.Size == best.Size && candidate.Order < best.Order) {
			best = candidate
			found = true
		}
	}
	logger.Debug("RAR main file selection fell back to largest candidate", "target", target, "name", best.Name, "size", best.Size, "order", best.Order, "candidates", len(candidates))
	return best.Name, nil
}

func collectParts(parts []filePart, name string) []filePart {
	var result []filePart
	for _, p := range parts {
		if p.name == name {
			result = append(result, p)
		}
	}
	return result
}

func totalPackedSize(parts []filePart) int64 {
	var total int64
	for _, p := range parts {
		total += p.packedSize
	}
	return total
}

// reconcileMainPartsPackedSpan trims the final part when summed packed spans exceed
// the MKV header size. Continuation aggregation can overshoot by trailing RAR bytes
// on the last volume (decoded file size minus dataOffset > MKV contribution).
func reconcileMainPartsPackedSpan(parts []filePart, headerSize int64) []filePart {
	if len(parts) == 0 || headerSize <= 0 {
		return parts
	}
	packed := totalPackedSize(parts)
	delta := packed - headerSize
	if delta <= 0 {
		return parts
	}
	out := append([]filePart(nil), parts...)
	last := len(out) - 1
	newPacked := out[last].packedSize - delta
	if newPacked <= 0 {
		return parts
	}
	out[last].packedSize = newPacked
	logger.Debug("Reconciled last blueprint part packed span to header",
		"header", headerSize,
		"was_packed", packed,
		"delta", delta,
		"last_packed", newPacked)
	return out
}

func roundUpTo16(val int64) int64 {
	if val%16 == 0 {
		return val
	}
	return (val/16 + 1) * 16
}

func aggregateRemainingVolumes(ctx context.Context, mainParts []filePart, allRarFiles []UnpackableFile, name string, headerSize int64, password string) ([]filePart, error) {
	if err := contextErr(ctx); err != nil {
		return nil, err
	}
	// Stable: names with no recognizable volume number all compare equal, and
	// an unstable sort would permute them out of NZB order.
	sort.SliceStable(allRarFiles, func(i, j int) bool {
		return GetRARVolumeNumber(allRarFiles[i].Name()) < GetRARVolumeNumber(allRarFiles[j].Name())
	})

	startVol := mainParts[0].volName
	startIdx := -1
	for i, f := range allRarFiles {
		if f.Name() == startVol {
			startIdx = i
			break
		}
	}
	if startIdx == -1 {
		return mainParts, nil
	}

	probe, err := probeContinuation(ctx, allRarFiles, startIdx, name, password)
	if err != nil {
		return nil, err
	}
	if probe.dataOffset > 0 {
		logger.Trace("Probed continuation volume", "dataOffset", probe.dataOffset, "packedSize", probe.packedSize)
	}

	mainParts = applyContinuationProbeToMainParts(mainParts, probe)

	return aggregateRemainingVolumesFromStart(ctx, mainParts, allRarFiles, startIdx, name, headerSize, probe)
}

// applyContinuationProbeToMainParts aligns the first-volume block with metadata from
// the two-volume ListTolerant probe. Single-volume scans can report a different
// dataOffset than continuation volumes; that skew breaks every seek past ~100MB.
func applyContinuationProbeToMainParts(parts []filePart, probe continuationProbe) []filePart {
	if len(parts) == 0 || (probe.firstDataOffset <= 0 && probe.firstPackedSize <= 0) {
		return parts
	}
	out := make([]filePart, len(parts))
	copy(out, parts)
	firstVol := out[0].volName
	for i := range out {
		if out[i].volName != firstVol || i != 0 {
			continue
		}
		beforeOff, beforePacked := out[i].dataOffset, out[i].packedSize
		if probe.firstDataOffset > 0 {
			out[i].dataOffset = probe.firstDataOffset
		}
		packed := probe.firstPackedSize
		if probe.packedSize > 0 && packed > 0 {
			switch {
			case packed == probe.packedSize:
				// already aligned with continuation span
			case packed == probe.packedSize+1:
				// Keep the first-volume STORE span. Shrinking by one byte shifts the
				// ~100MB part boundary and breaks MKV seek/resume past the first RAR.
			case packed == probe.packedSize-1:
				packed = probe.packedSize
			}
		}
		if packed > 0 {
			out[i].packedSize = packed
		}
		if out[i].dataOffset != beforeOff || out[i].packedSize != beforePacked {
			logger.Debug("Aligned first-volume part with continuation probe",
				"vol", firstVol,
				"data_offset", out[i].dataOffset,
				"packed_size", out[i].packedSize,
				"was_data_offset", beforeOff,
				"was_packed_size", beforePacked)
		}
	}
	return out
}

func uvarintSize(v uint64) int64 {
	if v == 0 {
		return 1
	}
	var size int64
	for v > 0 {
		size++
		v >>= 7
	}
	return size
}

func aggregateRemainingVolumesFromStart(ctx context.Context, mainParts []filePart, allRarFiles []UnpackableFile, startIdx int, name string, headerSize int64, probe continuationProbe) ([]filePart, error) {
	if err := contextErr(ctx); err != nil {
		return nil, err
	}
	if len(mainParts) == 0 {
		return nil, fmt.Errorf("aggregate remaining volumes: no main parts")
	}
	first := mainParts[0]
	// Keep every split block discovered on the first volume. Using only mainParts[0]
	// shifts all later virtual offsets and breaks seeks past the first ~100MB.
	result := append([]filePart(nil), mainParts...)

	numContVolumes := len(allRarFiles) - startIdx - 1
	if numContVolumes <= 0 {
		return result, nil
	}

	contPackedSize := probe.packedSize
	contDataOffset := probe.dataOffset

	// When the fast continuation probe is unavailable we must learn each remaining
	// volume's decoded size. Probing volumes one-by-one is the dominant startup
	// cost (each probe is a round of segment downloads), so prime them concurrently
	// up front; the sequential loop below then hits already-cached segment maps.
	if contPackedSize <= 0 {
		if err := primeContinuationSegmentMaps(ctx, allRarFiles[startIdx+1:]); err != nil {
			return nil, err
		}
	} else if len(allRarFiles) > 1 {
		// Prime the last volume's segment map in the background so player seeks to the end (Matroska cues) do not stall.
		lastVol := allRarFiles[len(allRarFiles)-1]
		bgCtx := playbackSegmentMapCtx(context.Background())
		go func() {
			_ = ensureSegmentMap(bgCtx, lastVol)
		}()
	}

	firstVolumePacked := totalPackedSize(mainParts)
	firstVolNum := GetRARVolumeNumber(allRarFiles[startIdx].Name())

	var lastPartData int64
	if contPackedSize > 0 {
		var sumNonLastContPacked int64
		for i := startIdx + 1; i < len(allRarFiles)-1; i++ {
			volIdx := volumeIndexFrom(allRarFiles[i].Name(), firstVolNum, i-startIdx)
			volDataOffset := contDataOffset + uvarintSize(uint64(volIdx)) - 1
			sumNonLastContPacked += contPackedSize - (volDataOffset - contDataOffset)
		}
		lastPartData = headerSize - firstVolumePacked - sumNonLastContPacked
	}

	added := 0
	for i := startIdx + 1; i < len(allRarFiles); i++ {
		if err := contextErr(ctx); err != nil {
			return nil, err
		}
		f := allRarFiles[i]
		fileSize := int64(0)
		if contPackedSize <= 0 {
			if err := ensureSegmentMap(ctx, f); err != nil {
				return nil, err
			}
			fileSize = f.Size()
			if fileSize <= 0 {
				continue
			}
		}

		volIdx := volumeIndexFrom(f.Name(), firstVolNum, i-startIdx)
		volDataOffset := contDataOffset + uvarintSize(uint64(volIdx)) - 1

		isLastVolume := i == len(allRarFiles)-1
		var dataSize int64
		if contPackedSize > 0 {
			if isLastVolume {
				// Prefer the header-reconciled tail size. Measured fileSize-dataOffset
				// includes trailing non-MKV bytes after the STORE block and overshoots
				// TotalUnpackedSize (e.g. +744 on 761-volume sets).
				if lastPartData > 0 {
					dataSize = lastPartData
				} else if err := ensureSegmentMap(ctx, f); err == nil {
					if fileSize = f.Size(); fileSize > volDataOffset {
						if measured := fileSize - volDataOffset; measured > 0 {
							dataSize = measured
						}
					}
				}
			} else {
				dataSize = contPackedSize - (volDataOffset - contDataOffset)
			}
		} else {
			dataSize = fileSize - volDataOffset
		}

		if dataSize <= 0 {
			continue
		}

		result = append(result, filePart{
			name:         name,
			unpackedSize: headerSize,
			dataOffset:   volDataOffset,
			packedSize:   dataSize,
			volFile:      f,
			volName:      f.Name(),
			isMedia:      true,
			isCompressed: first.isCompressed,
			isEncrypted:  first.isEncrypted,
			aesKey:       first.aesKey,
			aesIV:        first.aesIV,
		})
		added++
	}
	logger.Trace("Manual volume aggregation", "added", added, "total", len(result))
	return result, nil
}

// maxContinuationProbeConcurrency bounds how many continuation volumes we probe
// at once so we speed up startup without flooding the NNTP connection pool.
// Each volume probe fans out into ~60 segment fetches, so keep this small to
// avoid saturating the pool (which shows up as "NNTP pool wait exceeded
// threshold" and stalls real playback reads).
const maxContinuationProbeConcurrency = 3

// primeContinuationSegmentMaps probes the segment maps of the given volumes
// concurrently so the subsequent sequential aggregation hits cached maps. It
// returns the first error encountered (other than per-volume sizing issues,
// which the aggregation loop already tolerates by skipping empty volumes).
func primeContinuationSegmentMaps(ctx context.Context, files []UnpackableFile) error {
	if len(files) == 0 {
		return nil
	}
	if err := contextErr(ctx); err != nil {
		return err
	}

	var (
		wg       sync.WaitGroup
		mu       sync.Mutex
		firstErr error
		sem      = make(chan struct{}, maxContinuationProbeConcurrency)
	)

	for _, f := range files {
		f := f
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				mu.Lock()
				if firstErr == nil {
					firstErr = ctx.Err()
				}
				mu.Unlock()
				return
			}
			defer func() { <-sem }()

			if err := ensureSegmentMap(ctx, f); err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = err
				}
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	// Only surface context cancellation as fatal; individual volume probe errors
	// are handled (and may be retried) by the aggregation loop.
	if firstErr != nil && isContextErr(firstErr) {
		return firstErr
	}
	return nil
}

type continuationProbe struct {
	dataOffset      int64 // per-continuation-volume payload offset (parts[1+])
	packedSize      int64 // per-continuation-volume packed span (parts[1+])
	firstDataOffset int64 // opening block on the first volume (parts[0])
	firstPackedSize int64 // opening block packed span on the first volume (parts[0])
}

func probeContinuation(ctx context.Context, allRarFiles []UnpackableFile, startIdx int, targetName string, password string) (continuationProbe, error) {
	if err := contextErr(ctx); err != nil {
		return continuationProbe{}, err
	}
	if startIdx+1 >= len(allRarFiles) {
		return continuationProbe{}, nil
	}

	firstFile := allRarFiles[startIdx]
	secondFile := allRarFiles[startIdx+1]
	firstName := ExtractFilename(firstFile.Name())
	secondName := ExtractFilename(secondFile.Name())

	fsys := NewNZBFSFromMapCtx(ctx, map[string]UnpackableFile{
		firstName:  firstFile,
		secondName: secondFile,
	})
	// ListTolerant lets the split file resolve across the two mounted volumes and
	// return both parts even though the archive continues into volumes not mounted
	// here; we only need Parts[1] (the per-continuation-volume packed size).
	listOpts := []rardecode.Option{rardecode.FileSystem(fsys), rardecode.BufferSize(scanVolumeBufferSize), rardecode.ParallelRead(false), rardecode.SkipVolumeCheck, rardecode.ListTolerant}
	if password != "" {
		listOpts = append(listOpts, rardecode.Password(password))
	}
	infos, err := rardecode.ListArchiveInfo(firstName, listOpts...)
	if err != nil {
		if ctxErr := contextErr(ctx); ctxErr != nil {
			return continuationProbe{}, ctxErr
		}
		logger.Debug("Continuation probe failed, falling back to zero offset", "err", err)
		return continuationProbe{}, nil
	}

	// Gather every part of the target file across all returned entries, in the
	// order rardecode discovered them (volume order). Depending on how the split
	// continuation block in the second volume is flagged, rardecode may either
	// merge both volumes into a single entry with 2 parts, or emit two separate
	// single-part entries for the same name. Both layouts mean the same thing:
	// the second part overall is the per-continuation-volume data block we want.
	lowerTarget := strings.ToLower(targetName)
	var targetParts []rardecode.FilePartInfo
	for _, info := range infos {
		if err := contextErr(ctx); err != nil {
			return continuationProbe{}, err
		}
		if strings.ToLower(info.Name) != lowerTarget {
			continue
		}
		targetParts = append(targetParts, info.Parts...)
	}

	logger.Debug("Continuation probe result",
		"target", targetName,
		"infos", len(infos),
		"target_parts", len(targetParts),
		"first_volume", firstName,
		"second_volume", secondName)

	if len(targetParts) >= 2 {
		probe := normalizeContinuationProbe(targetParts)
		probe.firstDataOffset = targetParts[0].DataOffset
		probe.firstPackedSize = targetParts[0].PackedSize
		logger.Debug("Continuation probe metadata",
			"target", targetName,
			"dataOffset", probe.dataOffset,
			"packedSize", probe.packedSize,
			"first_data_offset", probe.firstDataOffset,
			"first_part_packed", probe.firstPackedSize,
			"second_part_packed", targetParts[1].PackedSize)
		return probe, nil
	}
	return continuationProbe{}, nil
}

func normalizeContinuationProbe(parts []rardecode.FilePartInfo) continuationProbe {
	if len(parts) < 2 {
		return continuationProbe{}
	}
	probe := continuationProbe{
		dataOffset: parts[1].DataOffset,
		packedSize: parts[1].PackedSize,
	}
	// RAR5 split continuations can expose a valid dataOffset while PackedSize is
	// left zero on the second list entry; reuse the first part's packed span.
	if probe.packedSize <= 0 && parts[0].PackedSize > 0 {
		probe.packedSize = parts[0].PackedSize
	}
	return probe
}

func tryNestedArchive(ctx context.Context, parts []filePart, allRarFiles []UnpackableFile, password string, target EpisodeTarget) (*ArchiveBlueprint, error) {
	if err := contextErr(ctx); err != nil {
		return nil, err
	}
	depth := NestDepthFromContext(ctx)
	if depth >= MaxNestDepth {
		return nil, fmt.Errorf("max archive nesting depth (%d) reached", MaxNestDepth)
	}
	if len(parts) == 0 {
		return nil, errors.New("archive contained no file headers")
	}

	type archiveSet struct {
		totalSize int64
		parts     []filePart
		order     int
	}
	sets := make(map[string]*archiveSet)
	order := 0

	for _, p := range parts {
		if !IsArchiveFile(p.name) {
			continue
		}
		setName := archiveSetName(p.name)
		s, ok := sets[setName]
		if !ok {
			s = &archiveSet{order: order}
			sets[setName] = s
			order++
		}
		s.totalSize += p.packedSize
		s.parts = append(s.parts, p)
	}

	if len(sets) == 0 {
		return nil, errors.New("no video or nested archive found")
	}

	candidates := make([]namedEpisodeCandidate, 0, len(sets))
	for name, set := range sets {
		candidates = append(candidates, namedEpisodeCandidate{Name: name, Size: set.totalSize, Order: set.order})
	}
	bestSet := ""
	maxSize := int64(0)
	if best, ok, err := selectEpisodeCandidateOrError(candidates, target, "nested_archive_sets"); err != nil {
		return nil, err
	} else if ok {
		bestSet = best.Name
		maxSize = best.Size
		logger.Debug("Nested archive set selected by episode match", "target", target, "set", bestSet, "size", maxSize, "candidates", len(candidates))
	} else {
		for _, candidate := range candidates {
			if candidate.Size > maxSize {
				maxSize = candidate.Size
				bestSet = candidate.Name
			}
		}
		logger.Debug("Nested archive set selection fell back to largest candidate", "target", target, "set", bestSet, "size", maxSize, "candidates", len(candidates))
	}

	nestedParts := sets[bestSet].parts
	logger.Info("Detected nested archive", "set", bestSet, "size", maxSize, "volumes", len(nestedParts))
	for _, p := range nestedParts {
		logger.Trace("Nested archive part", "name", p.name, "volName", p.volName, "packed", p.packedSize, "unpacked", p.unpackedSize)
	}

	innerFiles := make(map[string][]filePart)
	for _, p := range nestedParts {
		innerFiles[p.name] = append(innerFiles[p.name], p)
	}

	var nestedFiles []UnpackableFile
	for name, fps := range innerFiles {
		sortByVolume(fps)

		compressed := false
		var vfParts []virtualPart
		var vOffset int64
		for _, p := range fps {
			if p.isCompressed {
				compressed = true
			}
			vfParts = append(vfParts, virtualPart{
				VirtualStart: vOffset,
				VirtualEnd:   vOffset + p.packedSize,
				VolFile:      p.volFile,
				VolOffset:    p.dataOffset,
			})
			vOffset += p.packedSize
		}

		if compressed {
			return nil, fmt.Errorf("nested archive %s is compressed: %w", name, ErrCompressedArchive)
		}

		totalSize := fps[0].unpackedSize
		if totalSize == 0 {
			totalSize = vOffset
		}
		nestedFiles = append(nestedFiles, NewVirtualFile(name, totalSize, vfParts))
	}

	if len(filterFirstVolumes(nestedFiles)) == 0 && len(allRarFiles) > 1 {
		logger.Info("Nested archive set missing first volume from initial outer scan; scanning remaining outer volumes to locate inner first volume",
			"set", bestSet, "scanned_parts", len(parts), "total_outer_vols", len(allRarFiles))

		scannedVols := make(map[string]bool)
		for _, p := range parts {
			scannedVols[p.volName] = true
		}
		var remainingOuter []UnpackableFile
		for _, f := range allRarFiles {
			if f != nil && !scannedVols[f.Name()] {
				remainingOuter = append(remainingOuter, f)
			}
		}

		if len(remainingOuter) > 0 {
			// Hunt for the inner first volume in batches instead of scanning
			// every outer volume. Each volume costs a fetch and a parse, so on
			// a 135-volume release the exhaustive version ran past the client's
			// timeout; the volume we need is usually an early one, and once it
			// turns up the rest cannot add anything.
			budget := len(remainingOuter)
			if budget > maxNestedRescanVolumes {
				budget = maxNestedRescanVolumes
				logger.Info("Capping the inner first-volume hunt",
					"set", bestSet, "scanning", budget, "remaining_outer_vols", len(remainingOuter))
			}

			var (
				extraParts []filePart
				extraDiag  scanDiagnostics
				extraErr   error
				scanned    int
			)
			for scanned < budget {
				end := min(scanned+nestedRescanBatch, budget)
				batch, diag, batchErr := scanVolumesParallel(ctx, remainingOuter[scanned:end], password)
				extraDiag = diag
				scanned = end
				if batchErr != nil {
					extraErr = batchErr
					break
				}
				extraParts = append(extraParts, batch...)
				if setHasFirstVolume(extraParts, bestSet) {
					logger.Info("Found the inner first volume; stopping the outer rescan",
						"set", bestSet, "outer_vols_scanned", scanned, "of", len(remainingOuter))
					break
				}
			}
			if extraErr == nil && len(extraParts) > 0 {
				allParts := append(parts, extraParts...)
				return tryNestedArchive(ctx, allParts, nil, password, target)
			}
			// Nothing recovered: the inner set stays as short as the first scan
			// left it. Say so, otherwise the recursion below reports a missing
			// first volume with no hint that most outer volumes were unreadable.
			logger.Warn("Could not enumerate more inner volumes; the nested set stays incomplete",
				"set", bestSet, "outer_vols_rescanned", len(remainingOuter),
				"outer_scans_failed", extraDiag.failedScans, "inner_vols_known", len(nestedFiles),
				"last_err", extraDiag.lastErr, "err", extraErr)
		}
	}

	for _, nf := range nestedFiles {
		logger.Debug("Nested VirtualFile", "name", nf.Name(), "size", nf.Size(), "extracted", ExtractFilename(nf.Name()))
	}
	logger.Info("Recursively scanning nested archive", "set", bestSet, "volumes", len(nestedFiles), "depth", depth+1)
	nextCtx := WithNestDepth(ctx, depth+1)
	return ScanArchive(nextCtx, nestedFiles, password, target)
}

func filterRarFiles(files []UnpackableFile) []UnpackableFile {
	var result []UnpackableFile
	for _, f := range files {
		name := ExtractFilename(f.Name())
		lower := strings.ToLower(name)
		if strings.HasSuffix(lower, ExtPar2) {
			logger.Trace("filterRarFiles: skip par2", "name", name)
			continue
		}

		if strings.Contains(lower, ".7z.") || strings.HasSuffix(lower, ".7z") {
			logger.Trace("filterRarFiles: skip 7z", "name", name)
			continue
		}
		if strings.HasSuffix(lower, ExtRar) || strings.Contains(lower, ".part") || IsRarPart(lower) || IsSplitArchivePart(lower) || GetRARVolumeNumber(name) >= 0 {
			result = append(result, f)
		} else {
			logger.Trace("filterRarFiles: skip non-rar", "name", name)
		}
	}
	return dedupeVolumeMembers(result)
}

func dedupeVolumeMembers(files []UnpackableFile) []UnpackableFile {
	byVol := make(map[int]UnpackableFile)
	var volNums []int
	var unnumbered []UnpackableFile

	for _, f := range files {
		vol := GetRARVolumeNumber(f.Name())
		if vol < 0 {
			unnumbered = append(unnumbered, f)
			continue
		}
		prev, exists := byVol[vol]
		if !exists {
			byVol[vol] = f
			volNums = append(volNums, vol)
		} else {
			if f.Size() > prev.Size() {
				byVol[vol] = f
			}
		}
	}

	if len(byVol) == 0 {
		return files
	}

	sort.Ints(volNums)
	result := make([]UnpackableFile, 0, len(volNums)+len(unnumbered))
	for _, v := range volNums {
		result = append(result, byVol[v])
	}
	result = append(result, unnumbered...)
	return result
}

// isFirstVolumeName reports whether name can open a set: a plain .rar, or
// anything that is not recognisably a continuation volume.
func isFirstVolumeName(name string) bool {
	lower := strings.ToLower(ExtractFilename(name))
	if strings.HasSuffix(lower, ExtRar) && !strings.Contains(lower, ".part") && !strings.Contains(lower, ".r0") {
		return true
	}
	return !IsMiddleRarVolume(lower)
}

func filterFirstVolumes(files []UnpackableFile) []UnpackableFile {
	var result []UnpackableFile
	for _, f := range files {
		if !isFirstVolumeName(f.Name()) {
			logger.Trace("filterFirstVolumes: skip middle vol", "name", ExtractFilename(f.Name()))
			continue
		}
		logger.Trace("filterFirstVolumes: accept first vol", "name", ExtractFilename(f.Name()))
		result = append(result, f)
	}
	return result
}

// setHasFirstVolume reports whether parts already name a first volume for the
// given inner set — the one thing the remaining-volume rescan is hunting for.
func setHasFirstVolume(parts []filePart, setName string) bool {
	for _, p := range parts {
		if !IsArchiveFile(p.name) || archiveSetName(p.name) != setName {
			continue
		}
		if isFirstVolumeName(p.name) {
			return true
		}
	}
	return false
}

func isMediaFile(info rardecode.ArchiveFileInfo) bool {
	name := info.Name
	lower := strings.ToLower(name)
	// .iso is not playable; do not treat as media so we don't select it as main file.
	if strings.HasSuffix(lower, ExtIso) {
		return false
	}
	// Do not select sample files (e.g. Sample/sample-foo.m2ts) as main media.
	if IsSampleFile(name) {
		return false
	}
	isVideo := IsVideoFile(name)
	isLarge := info.TotalUnpackedSize > minLargeMediaBytes
	return isVideo || (isLarge && !isArchiveLikeSuffix(lower))
}

func archiveSetName(name string) string {
	lower := strings.ToLower(name)
	if idx := strings.LastIndex(lower, ".part"); idx != -1 {
		return name[:idx]
	}
	if idx := strings.LastIndex(lower, ".r"); idx != -1 && idx > len(lower)-5 {
		return name[:idx]
	}
	if strings.HasSuffix(lower, ExtRar) {
		return strings.TrimSuffix(strings.TrimSuffix(name, ExtRar), ".RAR")
	}
	return name
}

// volumeIndexFrom is a continuation volume's ordinal relative to the set's
// first volume. Names with no recognizable number fall back to their position
// in the (stably) sorted list — the old subtraction produced -1 - -1 = 0 for
// every unnumbered volume, so the positional fallback was dead code and every
// continuation was priced as volume zero.
func volumeIndexFrom(name string, firstVolNum, positional int) int64 {
	n := GetRARVolumeNumber(name)
	if n < 0 || firstVolNum < 0 {
		return int64(positional)
	}
	if idx := int64(n - firstVolNum); idx >= 0 {
		return idx
	}
	return int64(positional)
}

func sortByVolume(parts []filePart) {
	// Stable for the same reason as aggregateRemainingVolumes: unnumbered
	// names compare equal and must keep their NZB order.
	sort.SliceStable(parts, func(i, j int) bool {
		return GetRARVolumeNumber(parts[i].volName) < GetRARVolumeNumber(parts[j].volName)
	})
}
