package loader

import (
	"encoding/json"
	"sort"

	"streamnzb/pkg/core/logger"
)

// A segment map costs one NNTP article per decoded-size class, per volume,
// every time a release is opened — the yEnc decoded length is not in the NZB,
// so the only way to learn it is to fetch an article and measure it. The map is
// what turns a byte offset into a segment, so nothing can be read before it
// exists: a 34-volume release paid the probes on the head volume at startup and
// again on the tail volume the moment the player asked for the container cues.
//
// The probes are the whole cost, and they are tiny: two (index, decoded) pairs
// describe a volume of 732 segments. A snapshot records those pairs, not the
// expanded map, and replays them through the same size builder — so a restored
// map is bit-identical to the probed one, and any change to that builder shows
// up as a total mismatch and is rejected rather than silently painting wrong
// offsets across the file.
type SegmentMapSnapshot struct {
	Segments int `json:"segments"`
	// NZBBytes is the sum of the NZB-declared (encoded) segment sizes. Together
	// with Segments it identifies the volume this map was measured against, so
	// a re-posted or truncated file can never inherit another file's map.
	NZBBytes int64            `json:"nzb_bytes"`
	Total    int64            `json:"total"`
	SkipGap  bool             `json:"skip_gap"`
	Probes   []SegmentSizeAt  `json:"probes,omitempty"`
	Known    []SegmentSizeFor `json:"known,omitempty"`
	// YencSize and YencOffsets replay an exact-geometry map (see
	// exactSizesFromYencGeometry); both absent for a measured/scaled map.
	YencSize    int64           `json:"yenc_size,omitempty"`
	YencOffsets []SegmentSizeAt `json:"yenc_offsets,omitempty"`
}

// SegmentSizeAt is a decoded size measured at one segment index.
type SegmentSizeAt struct {
	Index   int   `json:"i"`
	Decoded int64 `json:"d"`
}

// SegmentSizeFor is a decoded size known for an encoded-size class rather than
// for one index (what the estimator contributes).
type SegmentSizeFor struct {
	NZBBytes int64 `json:"n"`
	Decoded  int64 `json:"d"`
}

// SegmentMapSnapshotJSON returns the probes behind this file's segment map, or
// (nil, false) when the map has not been built yet. Callers persist the bytes
// opaquely; the shape is this package's business.
func (f *File) SegmentMapSnapshotJSON() ([]byte, bool) {
	if f == nil {
		return nil, false
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.detected || len(f.segments) == 0 {
		return nil, false
	}
	snap := SegmentMapSnapshot{
		Segments: len(f.segments),
		NZBBytes: sumNZBSegmentBytes(f.segments),
		Total:    f.totalSize,
		SkipGap:  f.mapSkipGap,
	}
	for idx, decoded := range f.mapProbes {
		snap.Probes = append(snap.Probes, SegmentSizeAt{Index: idx, Decoded: decoded})
	}
	for nzbBytes, decoded := range f.mapKnown {
		snap.Known = append(snap.Known, SegmentSizeFor{NZBBytes: nzbBytes, Decoded: decoded})
	}
	if !f.mapYenc.empty() {
		snap.YencSize = f.mapYenc.fileSize
		for idx, off := range f.mapYenc.offsets {
			snap.YencOffsets = append(snap.YencOffsets, SegmentSizeAt{Index: idx, Decoded: off})
		}
	}
	// Map iteration order is random; sorting keeps a re-save of an unchanged
	// map byte-identical instead of rewriting the library row every time.
	sort.Slice(snap.Probes, func(i, j int) bool { return snap.Probes[i].Index < snap.Probes[j].Index })
	sort.Slice(snap.Known, func(i, j int) bool { return snap.Known[i].NZBBytes < snap.Known[j].NZBBytes })
	sort.Slice(snap.YencOffsets, func(i, j int) bool { return snap.YencOffsets[i].Index < snap.YencOffsets[j].Index })
	if len(snap.Probes) == 0 && len(snap.Known) == 0 && snap.YencSize == 0 {
		return nil, false
	}
	data, err := json.Marshal(snap)
	if err != nil {
		return nil, false
	}
	return data, true
}

// RestoreSegmentMapJSON rebuilds this file's segment map from a snapshot,
// skipping the NNTP probes entirely. It is strictly best-effort: a snapshot
// that does not describe this exact volume, or that no longer reproduces the
// total it was saved with, is rejected and the caller falls back to probing.
func (f *File) RestoreSegmentMapJSON(data []byte) bool {
	if f == nil || len(data) == 0 || len(f.segments) == 0 {
		return false
	}
	// A read has already disproved a map for this file; a snapshot written
	// before that is the same evidence that just failed.
	if len(f.segmentMapCorrections()) > 0 {
		return false
	}
	var snap SegmentMapSnapshot
	if err := json.Unmarshal(data, &snap); err != nil {
		return false
	}
	if snap.Segments != len(f.segments) || snap.Total <= 0 {
		return false
	}
	if snap.NZBBytes != sumNZBSegmentBytes(f.segments) {
		return false
	}

	probes := make(map[int]int64, len(snap.Probes))
	for _, p := range snap.Probes {
		if p.Index < 0 || p.Index >= len(f.segments) || p.Decoded <= 0 {
			return false
		}
		probes[p.Index] = p.Decoded
	}
	known := make(map[int64]int64, len(snap.Known))
	for _, k := range snap.Known {
		if k.NZBBytes <= 0 || k.Decoded <= 0 {
			return false
		}
		known[k.NZBBytes] = k.Decoded
	}
	geo := yencGeometry{fileSize: snap.YencSize}
	for _, o := range snap.YencOffsets {
		if o.Index < 0 || o.Index >= len(f.segments) || o.Decoded < 0 {
			return false
		}
		if geo.offsets == nil {
			geo.offsets = make(map[int]int64, len(snap.YencOffsets))
		}
		geo.offsets[o.Index] = o.Decoded
	}
	if len(probes) == 0 && len(known) == 0 && geo.empty() {
		return false
	}

	// Replay through the same builder chain as detection: exact geometry when
	// the snapshot recorded one, the measuring/scaling builder otherwise. The
	// total check below still rejects a snapshot the current rules cannot
	// reproduce bit-identically.
	sizes, exact := exactSizesFromYencGeometry(f.segments, probes, geo)
	if !exact {
		geo = yencGeometry{}
		sizes = buildSegmentDecodedSizesFromProbes(f.segments, probes, known, snap.SkipGap)
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	if f.detected {
		return true
	}
	// Re-checked under mu, not just on the way in: a read can disprove the map
	// while this call is unmarshalling and rebuilding sizes, and applying the
	// snapshot then would reinstate the map that read just refused and clear
	// the mapDistrusted flag holding reads closed. Read directly rather than
	// through segmentMapCorrections, which takes mu.
	if len(f.mapCorrections) > 0 {
		return false
	}
	total := applySegmentDecodedSizes(f.segments, sizes)
	if total != snap.Total {
		// The size builder changed since the snapshot was written. Offsets
		// derived from a stale rule desync the demuxer silently, so drop the
		// map and let the probes rebuild it.
		applySegmentDecodedSizes(f.segments, nzbSegmentSizes(f.segments))
		logger.Debug("Rejected persisted segment map (total mismatch)",
			"name", f.Name(), "snapshot_total", snap.Total, "rebuilt_total", total)
		return false
	}
	f.totalSize = total
	f.detected = true
	f.mapDistrusted = false
	f.mapProbes = probes
	f.mapKnown = known
	f.mapSkipGap = snap.SkipGap
	f.mapYenc = geo
	// Only a real probe of segment 0 may teach the estimator: an inferred size
	// painted across other volumes is exactly how a map goes subtly wrong.
	if decoded, ok := probes[0]; ok && decoded > 0 && f.estimator != nil {
		f.estimator.Set(f.segments[0].Bytes, decoded)
	}
	logger.Debug("Restored persisted segment map (skipped probes)",
		"name", f.Name(), "segments", len(f.segments), "size", f.totalSize, "probes", len(probes))
	return true
}

// nzbSegmentSizes is the pre-detection layout: encoded sizes standing in for
// decoded ones, which is what NewFile starts from.
func nzbSegmentSizes(segments []*Segment) []int64 {
	sizes := make([]int64, len(segments))
	for i, seg := range segments {
		sizes[i] = seg.Bytes
	}
	return sizes
}

// recordSegmentMapInputsLocked stores what produced the map so it can be
// snapshotted later. Called with f.mu held.
func (f *File) recordSegmentMapInputsLocked(probes map[int]int64, known map[int64]int64, skipGap bool, geo yencGeometry) {
	f.mapProbes = probes
	f.mapKnown = known
	f.mapSkipGap = skipGap
	f.mapYenc = geo
}
