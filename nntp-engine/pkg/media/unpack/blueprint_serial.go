package unpack

import (
	"encoding/json"

	"streamnzb/pkg/core/logger"
)

// serialBlueprintKindRAR marks a serialized plaintext RAR ArchiveBlueprint.
const serialBlueprintKindRAR = "rar"

// serialBlueprintKindDirect marks a serialized DirectBlueprint.
const serialBlueprintKindDirect = "direct"

// serializableArchiveBlueprint is a JSON-round-trippable form of an
// *ArchiveBlueprint. The live *ArchiveBlueprint holds UnpackableFile handles
// (with usenet fetchers) that cannot be serialized, so we persist each part's
// volume by NAME and re-link it to a freshly-built file on replay.
//
// Only plaintext STORE-mode archives are supported: compressed archives can't be
// streamed anyway, and encrypted ones would require persisting derived AES keys —
// those simply re-scan on replay instead.
type serializableArchiveBlueprint struct {
	Kind         string                       `json:"kind"`
	MainFileName string                       `json:"main_file_name"`
	TotalSize    int64                        `json:"total_size"`
	Target       EpisodeTarget                `json:"target"`
	Parts        []serializableVirtualPartDef `json:"parts"`
	// SegmentMaps carries each volume's probed segment map, keyed by the same
	// NZB subject the parts re-link on. Reaching any offset inside a volume
	// needs that map, and building it costs an NNTP article per size class —
	// paid on the head volume at startup and again on the tail volume as soon
	// as the player reads the container index. The payloads are opaque here:
	// the segment layer owns their shape, this only ferries them.
	SegmentMaps map[string]json.RawMessage `json:"segment_maps,omitempty"`
}

type serializableVirtualPartDef struct {
	VirtualStart int64  `json:"virtual_start"`
	VirtualEnd   int64  `json:"virtual_end"`
	VolFileName  string `json:"vol_file_name"`
	VolOffset    int64  `json:"vol_offset"`
}

// SerializeBlueprint converts a blueprint into the JSON stored with the release
// in the library, so a later play replays the plan instead of rebuilding it.
// files is the set the plan was built from; it is what a direct blueprint needs
// to find the segment map that rides along with it. Returns (nil, false) for a
// blueprint that cannot be reused — an encrypted or compressed archive, a 7z
// set, a recorded failure — which the caller is free to store some other way.
func SerializeBlueprint(bp interface{}, files []UnpackableFile) ([]byte, bool) {
	if data, ok := SerializeArchiveBlueprint(bp); ok {
		return data, true
	}
	return serializeDirectBlueprint(bp, files)
}

// RehydrateBlueprint rebuilds a live blueprint from SerializeBlueprint output,
// re-linking it against freshly-built files. Strictly best-effort: anything it
// cannot match falls back to (nil, false) and a full rescan.
func RehydrateBlueprint(data []byte, files []UnpackableFile) (Blueprint, bool) {
	if bp, ok := RehydrateArchiveBlueprint(data, files); ok {
		return bp, true
	}
	if bp, ok := rehydrateDirectBlueprint(data, files); ok {
		return bp, true
	}
	return nil, false
}

// serializableDirectBlueprint is the JSON form of a DirectBlueprint.
//
// The plan itself is nearly free to rebuild — pick the media file out of the
// NZB — so this exists for what rides with it: the file's segment map. Learning
// that map costs an article per decoded-size class, and a release posted in
// 4 MiB articles was paying more than a second of it on every single play,
// because a direct blueprint had no serialized form to carry it.
type serializableDirectBlueprint struct {
	Kind        string                     `json:"kind"`
	FileName    string                     `json:"file_name"`
	FileIndex   int                        `json:"file_index"`
	Target      EpisodeTarget              `json:"target"`
	SegmentMaps map[string]json.RawMessage `json:"segment_maps,omitempty"`
}

func serializeDirectBlueprint(bp interface{}, files []UnpackableFile) ([]byte, bool) {
	db, ok := bp.(*DirectBlueprint)
	if !ok || db == nil || db.FileName == "" {
		return nil, false
	}
	s := serializableDirectBlueprint{
		Kind:      serialBlueprintKindDirect,
		FileName:  db.FileName,
		FileIndex: db.FileIndex,
		Target:    db.Target,
	}
	if f := directBlueprintFile(db, files); f != nil {
		if name := originalName(f); name != "" {
			s.SegmentMaps = collectSegmentMap(name, f)
		}
	}
	data, err := json.Marshal(s)
	if err != nil {
		return nil, false
	}
	return data, true
}

func rehydrateDirectBlueprint(data []byte, files []UnpackableFile) (*DirectBlueprint, bool) {
	if len(data) == 0 || len(files) == 0 {
		return nil, false
	}
	var s serializableDirectBlueprint
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, false
	}
	if s.Kind != serialBlueprintKindDirect || s.FileName == "" {
		return nil, false
	}
	// The stored index describes the file list of the session that built the
	// plan. Re-link by name and correct it, so Open cannot read a different
	// file out of a list that came back in another order.
	byName := make(map[string]UnpackableFile, len(files))
	index := -1
	for i, f := range files {
		if f == nil {
			continue
		}
		byName[f.Name()] = f
		if f.Name() == s.FileName {
			index = i
		}
	}
	if index < 0 {
		return nil, false
	}
	if restored := restoreSegmentMaps(s.SegmentMaps, byName); restored > 0 {
		logger.Debug("Restored persisted segment map with direct blueprint", "file", s.FileName)
	}
	return &DirectBlueprint{FileName: s.FileName, FileIndex: index, Target: s.Target}, true
}

// SerializeArchiveBlueprint converts a blueprint into a JSON form that can be
// rehydrated later, so a library replay can skip ScanArchive. It returns
// (nil, false) for anything that is not a reusable plaintext STORE-mode
// *ArchiveBlueprint (the caller should fall back to storing whatever it likes,
// or nothing).
func SerializeArchiveBlueprint(bp interface{}) ([]byte, bool) {
	ab, ok := bp.(*ArchiveBlueprint)
	if !ok || ab == nil {
		return nil, false
	}
	if ab.IsCompressed || ab.AnyEncrypted || len(ab.Parts) == 0 {
		return nil, false
	}
	s := serializableArchiveBlueprint{
		Kind:         serialBlueprintKindRAR,
		MainFileName: ab.MainFileName,
		TotalSize:    ab.TotalSize,
		Target:       ab.Target,
		Parts:        make([]serializableVirtualPartDef, 0, len(ab.Parts)),
	}
	for _, p := range ab.Parts {
		if p.VolFile == nil {
			return nil, false // can't re-link an unnamed volume
		}
		// Record the release's own NZB subject, not a name deobfuscation
		// recovered: the rename is not persisted, so a replay re-links against
		// the raw files and would find nothing under a recovered name.
		volName := originalName(p.VolFile)
		if volName == "" {
			return nil, false
		}
		s.Parts = append(s.Parts, serializableVirtualPartDef{
			VirtualStart: p.VirtualStart,
			VirtualEnd:   p.VirtualEnd,
			VolFileName:  volName,
			VolOffset:    p.VolOffset,
		})
	}
	s.SegmentMaps = collectSegmentMaps(ab.Parts)
	data, err := json.Marshal(s)
	if err != nil {
		return nil, false
	}
	return data, true
}

// segmentMapSnapshotter is the optional capability a volume file offers to let
// its probed segment map ride along in the blueprint. Discovered by type
// assertion, like every other capability beyond UnpackableFile.
type segmentMapSnapshotter interface {
	SegmentMapSnapshotJSON() ([]byte, bool)
	RestoreSegmentMapJSON(data []byte) bool
}

// collectSegmentMaps snapshots the segment map of every distinct volume the
// blueprint spans. A volume whose map was never built (the tail of a release
// that was only ever probed at the head) simply contributes nothing.
func collectSegmentMaps(parts []VirtualPartDef) map[string]json.RawMessage {
	var maps map[string]json.RawMessage
	for _, p := range parts {
		if p.VolFile == nil {
			continue
		}
		name := originalName(p.VolFile)
		if name == "" {
			continue
		}
		if _, done := maps[name]; done {
			continue
		}
		for k, v := range collectSegmentMap(name, p.VolFile) {
			if maps == nil {
				maps = make(map[string]json.RawMessage, len(parts))
			}
			maps[k] = v
		}
	}
	return maps
}

// collectSegmentMap snapshots one file's segment map, keyed by name, or returns
// nil when the file cannot offer one (not a loader file, or never mapped).
func collectSegmentMap(name string, f UnpackableFile) map[string]json.RawMessage {
	snapshotter, ok := f.(segmentMapSnapshotter)
	if !ok {
		return nil
	}
	data, ok := snapshotter.SegmentMapSnapshotJSON()
	if !ok || len(data) == 0 {
		return nil
	}
	return map[string]json.RawMessage{name: json.RawMessage(data)}
}

// restoreSegmentMaps replays persisted segment maps onto freshly-built files.
// Each map validates itself against the file it lands on, so a mismatch just
// leaves that volume to probe as before.
func restoreSegmentMaps(maps map[string]json.RawMessage, byName map[string]UnpackableFile) int {
	restored := 0
	for name, data := range maps {
		f, ok := byName[name]
		if !ok || len(data) == 0 {
			continue
		}
		snapshotter, ok := f.(segmentMapSnapshotter)
		if !ok {
			continue
		}
		if snapshotter.RestoreSegmentMapJSON(data) {
			restored++
		}
	}
	return restored
}

// RehydrateArchiveBlueprint rebuilds a live *ArchiveBlueprint from JSON produced
// by SerializeArchiveBlueprint, re-linking each part's volume to a file in files
// by Name(). It is strictly best-effort: it returns (nil, false) if the JSON is
// not a serialized RAR blueprint or if any part's volume cannot be matched, so
// the caller safely falls back to a full ScanArchive.
func RehydrateArchiveBlueprint(data []byte, files []UnpackableFile) (*ArchiveBlueprint, bool) {
	if len(data) == 0 || len(files) == 0 {
		return nil, false
	}
	var s serializableArchiveBlueprint
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, false
	}
	if s.Kind != serialBlueprintKindRAR || len(s.Parts) == 0 {
		return nil, false
	}

	byName := make(map[string]UnpackableFile, len(files))
	for _, f := range files {
		if f != nil {
			byName[f.Name()] = f
		}
	}

	bp := &ArchiveBlueprint{
		MainFileName: s.MainFileName,
		TotalSize:    s.TotalSize,
		Target:       s.Target,
		Parts:        make([]VirtualPartDef, 0, len(s.Parts)),
	}
	for _, sp := range s.Parts {
		vol, ok := byName[sp.VolFileName]
		if !ok {
			return nil, false // volume set no longer matches; re-scan instead
		}
		bp.Parts = append(bp.Parts, VirtualPartDef{
			VirtualStart: sp.VirtualStart,
			VirtualEnd:   sp.VirtualEnd,
			VolFile:      vol,
			VolOffset:    sp.VolOffset,
		})
	}
	if restored := restoreSegmentMaps(s.SegmentMaps, byName); restored > 0 {
		logger.Debug("Restored persisted segment maps with blueprint",
			"file", s.MainFileName, "volumes", restored, "parts", len(bp.Parts))
	}
	return bp, true
}
