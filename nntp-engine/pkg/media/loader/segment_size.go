package loader

import (
	"context"
	"errors"
	"sort"
	"strings"
	"sync"

	"streamnzb/pkg/core/logger"
)

const (
	segmentSizeSlop            = 256
	maxSegmentProbeConcurrency = 16
)

// nzbBytesNearby reports whether two encoded article sizes belong to the same
// decoded-size class. yEnc encoded size tracks decoded size closely (escape
// overhead varies only ~1-3% with content), so encoded sizes differing by more
// than ~3% imply genuinely different decoded payloads that must be probed
// separately. The previous 10% tolerance merged a release's smaller FINAL
// segment (668,107 decoded / 686,112 encoded) into the full-segment class
// (716,800 decoded / ~739,315 encoded, 7.2% apart): the last segment's decoded
// size was painted across the whole volume, truncating the virtual file by
// 7.6MB — exactly where the Matroska cues live — so players read 0 bytes at the
// tail and never started playback.
func nzbBytesNearby(a, b int64) bool {
	diff := a - b
	if diff < 0 {
		diff = -diff
	}
	return diff*33 < b
}

// yencGeometry is a file's part layout as its own articles declared it:
// "=ybegin size=" for the whole decoded file, "=ypart begin=" per article.
// fileSize < 0 marks geometry poisoned by articles that disagreed.
type yencGeometry struct {
	fileSize int64
	offsets  map[int]int64
}

func (g yencGeometry) empty() bool {
	return g.fileSize <= 0 && len(g.offsets) == 0
}

// exactSizesFromYencGeometry builds the segment map from the yEnc headers
// instead of measuring and scaling, when the recorded offsets prove a uniform
// stride. One article at index i>0 pins the stride (offset i*stride), the
// declared file size pins the total and therefore the tail — the two probes
// the planner already fetches (head class + physical last) are enough, exactly,
// with no class clustering, no ratio scaling and no gap probing.
//
// Every check is a hard bail to the measuring path: a recorded offset off the
// stride grid (non-uniform post), an implausible tail, or a probed article
// whose measured length disagrees with the derived map. Serving through a
// wrong map shifts every byte after the first error, so "exact or not at all"
// is the only safe contract here.
func exactSizesFromYencGeometry(segments []*Segment, probedByIndex map[int]int64, geo yencGeometry) ([]int64, bool) {
	n := len(segments)
	if n < 2 || geo.fileSize <= 0 || len(geo.offsets) == 0 {
		return nil, false
	}

	var stride int64
	for idx, off := range geo.offsets {
		if idx <= 0 {
			continue
		}
		if idx >= n || off <= 0 || off%int64(idx) != 0 {
			return nil, false
		}
		s := off / int64(idx)
		if stride == 0 {
			stride = s
		} else if s != stride {
			return nil, false
		}
	}
	if stride <= 0 {
		return nil, false
	}
	for idx, off := range geo.offsets {
		if idx < 0 || idx >= n || off != int64(idx)*stride {
			return nil, false
		}
	}

	last := geo.fileSize - int64(n-1)*stride
	if last <= 0 || last > stride {
		return nil, false
	}

	for idx, decoded := range probedByIndex {
		if idx < 0 || idx >= n || decoded <= 0 {
			continue
		}
		want := stride
		if idx == n-1 {
			want = last
		}
		if decoded != want {
			return nil, false
		}
	}

	sizes := make([]int64, n)
	for i := 0; i < n-1; i++ {
		sizes[i] = stride
	}
	sizes[n-1] = last
	return sizes, true
}

func sumNZBSegmentBytes(segments []*Segment) int64 {
	var sum int64
	for _, seg := range segments {
		sum += seg.Bytes
	}
	return sum
}

func scaleDecodedSize(nzbBytes, firstEncoded, firstDecoded int64) int64 {
	if nzbBytes <= 0 {
		return firstDecoded
	}
	if firstEncoded <= 0 || firstDecoded <= 0 {
		return nzbBytes
	}
	return (nzbBytes * firstDecoded) / firstEncoded
}

func hasUniformNZBSegmentBytes(segments []*Segment) bool {
	if len(segments) <= 1 {
		return true
	}
	first := segments[0].Bytes
	for i := 1; i < len(segments)-1; i++ {
		if segments[i].Bytes != first {
			return false
		}
	}
	return true
}

func middleProbeIndex(segmentCount int) int {
	if segmentCount <= 2 {
		return 0
	}
	return segmentCount / 2
}

func shouldProbeMiddleSegment(_ context.Context, segments []*Segment) bool {
	if len(segments) <= 2 {
		return false
	}
	// Fast failover mode is always enabled: skip middle-segment calibration
	// to avoid the extra NNTP probe that delays playback startup.
	return false
}

// segmentProbeIndices returns segment indices to BODY-probe in parallel.
// We probe the first occurrence of each distinct NZB bytes value (unless already
// known from the estimator), always probe the physical last segment (tail size
// often differs while NZB bytes match an earlier article), and optionally probe
// the middle segment when uniform NZB bytes need slow-mode calibration.
//
// Segments without a message id — numbering-gap placeholders and id-less
// originals — are never chosen: a probe of one can only zero-fill, and its
// filler length would then stand in for the real decoded size of a whole class.
func segmentProbeIndices(segments []*Segment, knownByNZBBytes map[int64]int64, includeMiddle bool, skipGapProbing bool) []int {
	if len(segments) == 0 {
		return nil
	}

	fetchable := func(i int) bool {
		return strings.TrimSpace(segments[i].ID) != ""
	}

	lastIdx := len(segments) - 1
	seen := make(map[int]bool)
	var indices []int

	add := func(i int) {
		if i < 0 || i >= len(segments) || seen[i] || !fetchable(i) {
			return
		}
		seen[i] = true
		indices = append(indices, i)
	}

	firstIndexByBytes := make(map[int64]int)
	for i, seg := range segments {
		if !fetchable(i) {
			continue
		}
		if _, ok := firstIndexByBytes[seg.Bytes]; !ok {
			firstIndexByBytes[seg.Bytes] = i
		}
	}

	if skipGapProbing {
		var uniqueSizes []int64
		for sz := range firstIndexByBytes {
			uniqueSizes = append(uniqueSizes, sz)
		}
		sort.Slice(uniqueSizes, func(i, j int) bool { return uniqueSizes[i] < uniqueSizes[j] })

		var representatives []int64
		for _, sz := range uniqueSizes {
			matched := false
			for _, rep := range representatives {
				if nzbBytesNearby(sz, rep) {
					matched = true
					break
				}
			}
			if !matched {
				representatives = append(representatives, sz)
			}
		}

		for _, repSz := range representatives {
			idx := firstIndexByBytes[repSz]
			if _, known := knownByNZBBytes[repSz]; known {
				continue
			}
			knownNearby := false
			for knownSz := range knownByNZBBytes {
				if nzbBytesNearby(repSz, knownSz) {
					knownNearby = true
					break
				}
			}
			if knownNearby {
				continue
			}
			add(idx)
		}
	} else {
		for nbytes, idx := range firstIndexByBytes {
			if _, known := knownByNZBBytes[nbytes]; known {
				continue
			}
			add(idx)
		}
	}

	// The physical last segment, or with a trailing gap the last article the
	// NZB actually carries — that one is a full segment, so it is safe as a
	// class representative where a true remainder tail would not be.
	for i := lastIdx; i > 0; i-- {
		if fetchable(i) {
			add(i)
			break
		}
	}

	if includeMiddle {
		add(middleProbeIndex(len(segments)))
	}

	// The physical last segment is remainder-sized: its decoded size must never
	// stand in for the full-segment class (see buildSegmentDecodedSizesFromProbes,
	// which excludes it from class matching). If clustering collapsed everything
	// into the last segment's class — its encoded size can sit within tolerance
	// of full segments — force a real probe of a full segment too. Without this,
	// a 23,538-segment file had the last segment's 711,755 painted across every
	// segment (~5KB/segment cumulative offset drift), desyncing the demuxer.
	//
	// A known size is a full-segment size and satisfies the same requirement, so
	// the forced probe is only needed when nothing else can supply one.
	// Otherwise the second volume of a release re-measures a class the first
	// volume already measured, which is the whole point of the estimator.
	if lastIdx > 0 && len(knownByNZBBytes) == 0 {
		hasNonLast := false
		for _, idx := range indices {
			if idx != lastIdx {
				hasNonLast = true
				break
			}
		}
		if !hasNonLast {
			for i := 0; i < lastIdx; i++ {
				if fetchable(i) {
					add(i)
					break
				}
			}
		}
	}

	sort.Ints(indices)
	return indices
}

// segmentUnprobedIndices returns segment indices not covered by the initial probe plan.
// Same NZB bytes can decode to different lengths; a second pass probes these gaps only.
func segmentUnprobedIndices(segmentCount int, probedIndices []int) []int {
	if segmentCount <= 0 {
		return nil
	}
	seen := make(map[int]bool, len(probedIndices))
	for _, i := range probedIndices {
		seen[i] = true
	}
	var missing []int
	for i := 0; i < segmentCount; i++ {
		if !seen[i] {
			missing = append(missing, i)
		}
	}
	return missing
}

func buildSegmentDecodedSizesFromProbes(segments []*Segment, probedByIndex map[int]int64, knownByNZBBytes map[int64]int64, skipGapProbing bool) []int64 {
	n := len(segments)
	sizes := make([]int64, n)
	if n == 0 {
		return sizes
	}

	firstEncoded := segments[0].Bytes
	if firstEncoded <= 0 {
		firstEncoded = 1
	}

	decodedByBytes := make(map[int64]int64)
	for sz, dec := range knownByNZBBytes {
		decodedByBytes[sz] = dec
	}
	for idx, dec := range probedByIndex {
		if idx < 0 || idx >= n || dec <= 0 {
			continue
		}
		// Never let the physical LAST segment's decoded size represent a class:
		// it is remainder-sized, yet its encoded size can sit within cluster
		// tolerance of full segments. It still sizes itself via probedByIndex.
		if idx == n-1 && n > 1 {
			continue
		}
		decodedByBytes[segments[idx].Bytes] = dec
	}

	firstDecoded := probedByIndex[0]
	if firstDecoded <= 0 {
		firstDecoded = decodedByBytes[firstEncoded]
	}
	if firstDecoded <= 0 {
		for idx, dec := range probedByIndex {
			if dec > 0 && !(idx == n-1 && n > 1) {
				firstDecoded = dec
				break
			}
		}
	}
	if firstDecoded <= 0 {
		// Only the last segment was probed (no other candidates): better than nothing.
		firstDecoded = probedByIndex[n-1]
	}

	// Closest encoded-size match wins — map iteration order is random, and a
	// segment can be "nearby" more than one probed class.
	findNearbyProbedDecoded := func(nzbBytes int64) (int64, bool) {
		var bestDec int64
		bestDiff := int64(-1)
		for probedNzb, dec := range decodedByBytes {
			if !nzbBytesNearby(nzbBytes, probedNzb) {
				continue
			}
			diff := nzbBytes - probedNzb
			if diff < 0 {
				diff = -diff
			}
			if bestDiff < 0 || diff < bestDiff {
				bestDiff = diff
				bestDec = dec
			}
		}
		return bestDec, bestDiff >= 0
	}

	for i := 0; i < n; i++ {
		if dec, ok := probedByIndex[i]; ok && dec > 0 {
			sizes[i] = dec
			continue
		}
		if dec, ok := decodedByBytes[segments[i].Bytes]; ok {
			sizes[i] = dec
			continue
		}
		if skipGapProbing {
			if dec, ok := findNearbyProbedDecoded(segments[i].Bytes); ok {
				sizes[i] = dec
				continue
			}
		}
		sizes[i] = scaleDecodedSize(segments[i].Bytes, firstEncoded, firstDecoded)
	}
	return sizes
}

func applyUniformMiddleCalibration(segments []*Segment, sizes []int64, middleIdx int, middleDecoded int64) {
	n := len(segments)
	if n <= 2 || middleIdx <= 0 || middleIdx >= n-1 {
		return
	}
	if !hasUniformNZBSegmentBytes(segments) {
		return
	}

	firstEncoded := segments[0].Bytes
	if firstEncoded <= 0 {
		return
	}
	firstDecoded := sizes[0]
	scaledMid := scaleDecodedSize(segments[middleIdx].Bytes, firstEncoded, firstDecoded)
	diff := middleDecoded - scaledMid
	if diff < 0 {
		diff = -diff
	}
	if diff <= segmentSizeSlop {
		sizes[middleIdx] = middleDecoded
		return
	}

	ratio1 := float64(firstDecoded) / float64(firstEncoded)
	ratio2 := float64(middleDecoded) / float64(segments[middleIdx].Bytes)
	if segments[middleIdx].Bytes <= 0 {
		ratio2 = ratio1
	}
	for i := 0; i < middleIdx; i++ {
		sizes[i] = int64(float64(segments[i].Bytes) * ratio1)
	}
	sizes[middleIdx] = middleDecoded
	for i := middleIdx + 1; i < n-1; i++ {
		sizes[i] = int64(float64(segments[i].Bytes) * ratio2)
	}
}

func applySegmentDecodedSizes(segments []*Segment, sizes []int64) int64 {
	var offset int64
	for i := range segments {
		size := sizes[i]
		if size < 0 {
			size = 0
		}
		segments[i].StartOffset = offset
		segments[i].EndOffset = offset + size
		offset += size
	}
	return offset
}

func (f *File) probeSegmentIndicesParallel(ctx context.Context, indices []int) (map[int]int64, error) {
	probed := make(map[int]int64, len(indices))
	if len(indices) == 0 {
		return probed, nil
	}

	var (
		mu       sync.Mutex
		wg       sync.WaitGroup
		firstErr error
		sem      = make(chan struct{}, maxSegmentProbeConcurrency)
	)

	for _, idx := range indices {
		if idx < 0 || idx >= len(f.segments) || strings.TrimSpace(f.segments[idx].ID) == "" {
			// An article the NZB does not carry cannot be probed; its size
			// comes from class matching. Guards the slow-mode gap pass, which
			// probes every unprobed index.
			continue
		}
		idx := idx
		wg.Add(1)
		go func() {
			defer wg.Done()
			var acquired bool
			select {
			case sem <- struct{}{}:
				acquired = true
			case <-ctx.Done():
				mu.Lock()
				if firstErr == nil {
					firstErr = ctx.Err()
				}
				mu.Unlock()
			}
			if !acquired {
				return
			}
			defer func() { <-sem }()

			if err := ctx.Err(); err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = err
				}
				mu.Unlock()
				return
			}

			data, err := f.DownloadSegment(ctx, idx)
			if err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = err
				}
				mu.Unlock()
				return
			}
			if len(data) == 0 {
				mu.Lock()
				if firstErr == nil {
					firstErr = errors.New("empty probe segment")
				}
				mu.Unlock()
				return
			}

			mu.Lock()
			probed[idx] = int64(len(data))
			mu.Unlock()
		}()
	}
	wg.Wait()
	return probed, firstErr
}

func logSegmentProbePlan(ctx context.Context, name string, segments []*Segment, indices []int, knownByNZBBytes map[int64]int64, includeMiddle bool) {
	unique := len(firstIndexByBytesMap(segments))
	logger.Debug("Segment map probe plan",
		"name", name,
		"segments", len(segments),
		"unique_nzb_bytes", unique,
		"probe_indices", indices,
		"known_from_estimator", len(knownByNZBBytes),
		"uniform_nzb_bytes", hasUniformNZBSegmentBytes(segments),
		"middle_calibration", includeMiddle)
}

func firstIndexByBytesMap(segments []*Segment) map[int64]int {
	m := make(map[int64]int)
	for i, seg := range segments {
		if _, ok := m[seg.Bytes]; !ok {
			m[seg.Bytes] = i
		}
	}
	return m
}
