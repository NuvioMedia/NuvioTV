// Package rarname holds the pure RAR / split-volume filename parsing used across
// the importer. It is a dependency-free leaf so both the archive/rar packages and
// the filesystem package can share one source of truth without an import cycle.
package rarname

import (
	"path/filepath"
	"regexp"
	"strings"
)

// RAR / split-volume filename patterns.
var (
	// PartPattern matches filename.part###.rar (e.g., movie.part001.rar, movie.part01.rar)
	PartPattern = regexp.MustCompile(`^(.+)\.part(\d+)\.rar$`)
	// NumericPattern matches filename.### (numeric extensions like .001, .002)
	NumericPattern = regexp.MustCompile(`^(.+)\.(\d+)$`)
	// RPattern matches filename.r## or filename.r### (e.g., movie.r00, movie.r01)
	RPattern = regexp.MustCompile(`^(.+)\.r(\d+)$`)
	// RollVolPattern matches old-style continuation volumes whose extension letter
	// rolls after .r99 (r→s→…→z), always with two digits — e.g. movie.s00 is the
	// volume right after movie.r99. Mirrors rardecode's nextOldVolName. The leading
	// .rar is volume 0; .r00 is volume 1. Group 1 is the base, 2 the letter, 3 the digits.
	RollVolPattern = regexp.MustCompile(`(?i)^(.+)\.([r-z])(\d{2})$`)

	partPatternNumber    = regexp.MustCompile(`\.part(\d+)\.rar$`)
	rPatternNumber       = regexp.MustCompile(`\.r(\d+)$`)
	rollVolPatternNumber = regexp.MustCompile(`(?i)\.([r-z])(\d{2})$`)
	numericPatternNumber = regexp.MustCompile(`\.(\d+)$`)
)

// Scheme identifies the volume-numbering convention of a RAR/split set.
type Scheme int

const (
	SchemeUnknown Scheme = iota
	SchemePart           // name.partNN.rar (1-based)
	SchemeRoll           // name.rar (vol 0) + name.rNN (vol NN+1)
	SchemeNumeric        // name.NNN, e.g. .001 or .7z.001 (1-based)
)

// parseInt safely converts a string of digits to an int, returning -1 on any
// non-digit. Kept local so this package imports nothing from the importer tree.
func parseInt(s string) int {
	num := 0
	for _, r := range s {
		if r >= '0' && r <= '9' {
			num = num*10 + int(r-'0')
		} else {
			return -1
		}
	}
	return num
}

// SetKey returns the multi-volume grouping key for a filename and whether the
// name matches a RAR/split-volume pattern (.partNN.rar, .rar, .rNN, .NNN —
// the latter also covers .7z.NNN sets). Non-volume filenames (plain media,
// obfuscated names with no recognizable extension) return ("", false) and
// should be treated as standalone files. The key is the lowercased base name
// with the volume suffix stripped, so all parts of one set share it.
//
// Input must be a canonical filename: the patterns anchor on the extension, so
// callers normalize poster-controlled names (e.g. strip surrounding quotes) before
// calling. SetKey performs no cleaning of its own.
func SetKey(filename string) (string, bool) {
	lower := strings.ToLower(filepath.Base(filename))

	// Pattern 1: filename.part###.rar
	if m := PartPattern.FindStringSubmatch(lower); len(m) > 1 {
		return m[1], true
	}
	// Pattern 2: filename.rar (single part / old-style first volume)
	if before, ok := strings.CutSuffix(lower, ".rar"); ok {
		return before, true
	}
	// Pattern 3: filename.r##
	if m := RPattern.FindStringSubmatch(lower); len(m) > 1 {
		return m[1], true
	}
	// Pattern 3b: old-style rollover volumes .s##..z## (continuation after .r99)
	if m := RollVolPattern.FindStringSubmatch(lower); len(m) > 1 {
		return m[1], true
	}
	// Pattern 4: filename.### (numeric)
	if m := NumericPattern.FindStringSubmatch(lower); len(m) > 1 {
		return m[1], true
	}
	return "", false
}

// VolumeNumber extracts the volume scheme and ordinal for a filename so a set can
// be checked for contiguity or resolved by number. The old-style roll scheme is
// normalized so ".rar" is volume 0 and ".rNN" is volume NN+1, giving a single
// contiguous sequence. The part and numeric schemes parse the digits regardless of
// zero-padding width, so movie.part01.rar, movie.part010.rar and movie.part0259.rar
// resolve to 1, 10 and 259. Returns ok=false for names with no recognizable volume
// suffix.
func VolumeNumber(filename string) (Scheme, int, bool) {
	lower := strings.ToLower(filepath.Base(filename))

	// part###.rar must be checked before the plain .rar suffix.
	if m := partPatternNumber.FindStringSubmatch(lower); len(m) > 1 {
		if n := parseInt(m[1]); n >= 0 {
			return SchemePart, n, true
		}
	}
	if strings.HasSuffix(lower, ".rar") {
		return SchemeRoll, 0, true
	}
	// Old-style continuation volumes .r00..z99. The extension letter rolls after
	// .r99 (r→s→…→z), so the ordinal must stay contiguous across the boundary:
	// .r00=1, .r99=100, .s00=101, …, .z99=900. (.rar=0 is handled above.)
	if m := rollVolPatternNumber.FindStringSubmatch(lower); len(m) > 2 {
		letter := m[1][0]
		if nn := parseInt(m[2]); nn >= 0 {
			return SchemeRoll, 1 + int(letter-'r')*100 + nn, true
		}
	}
	if m := rPatternNumber.FindStringSubmatch(lower); len(m) > 1 {
		if n := parseInt(m[1]); n >= 0 {
			return SchemeRoll, n + 1, true
		}
	}
	if m := numericPatternNumber.FindStringSubmatch(lower); len(m) > 1 {
		if n := parseInt(m[1]); n >= 0 {
			return SchemeNumeric, n, true
		}
	}
	return SchemeUnknown, 0, false
}
