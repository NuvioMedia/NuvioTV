package loader

import (
	"regexp"
	"sort"
	"strconv"
	"strings"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/nzb"
)

// A gap in an NZB file's segment numbering means the post is incomplete in the
// document itself: the missing articles were never indexed, so no provider can
// ever serve them. Laying the remaining segments back-to-back — what NewFile
// used to do unconditionally — built a virtual file shorter than the container
// header inside it declares, with every byte after the first gap shifted. A
// player would then ask for the tail (Matroska cues) past the served EOF, get
// 416, and reload forever: an out-of-range request never reads the stream, so
// nothing ever marked the slot failed and failover never advanced.
//
// normalizeNZBSegments restores the declared layout instead: segments are
// sorted by number, and small gaps are filled with unfetchable placeholder
// segments (empty message id) so offsets and the total size stay truthful.
// Reading a placeholder goes through the same zero-fill policy as a 430 hole.
// Gaps larger than MaxZeroFills are not materialized — a release missing that
// much can never stream, and VerifyRequiredArchivesExist fails it before a
// Content-Length is promised — but they are still counted so that verdict can
// be reached.
//
// The declared part count comes from the numbering itself (the highest segment
// number), raised by the "(n/total)" tail of a yEnc subject when one is
// present, which is what catches a post whose missing articles are the
// trailing ones. Numbering that is absent, duplicated, or implies a gap larger
// than the file itself is not treated as evidence: those NZBs keep today's
// layout (fail-open).

// maxNZBGapExpansionFactor caps how much larger the declared segment count may
// be than the actual one before the numbering is considered unreliable noise
// rather than evidence of a gap.
const maxNZBGapExpansionFactor = 2

var yencSubjectPartsRe = regexp.MustCompile(`\((\d+)\s*/\s*(\d+)\)\s*$`)

// declaredYencPartTotal returns the total part count a yEnc subject declares
// in its trailing "(n/total)" marker, or 0 when the subject carries none.
func declaredYencPartTotal(subject string) int {
	if !strings.Contains(subject, "yEnc") {
		return 0
	}
	m := yencSubjectPartsRe.FindStringSubmatch(strings.TrimSpace(subject))
	if m == nil {
		return 0
	}
	total, err := strconv.Atoi(m[2])
	if err != nil {
		return 0
	}
	return total
}

// normalizeNZBSegments returns the segment list in declared order with small
// gaps filled by placeholders, plus how many declared articles were left
// unmaterialized (nonzero only for a gap too large to ever play). The input is
// never mutated; when nothing needs fixing the original slice is returned
// as-is.
func normalizeNZBSegments(subject string, segments []nzb.Segment) ([]nzb.Segment, int) {
	n := len(segments)
	if n == 0 {
		return segments, 0
	}

	maxNumber := 0
	inOrder := true
	seen := make(map[int]struct{}, n)
	for i, s := range segments {
		if s.Number < 1 {
			return segments, 0 // unnumbered post: no layout evidence to act on
		}
		if _, dup := seen[s.Number]; dup {
			return segments, 0 // duplicated numbering is noise, not a gap
		}
		seen[s.Number] = struct{}{}
		if s.Number > maxNumber {
			maxNumber = s.Number
		}
		if i > 0 && segments[i-1].Number > s.Number {
			inOrder = false
		}
	}

	expected := maxNumber
	if total := declaredYencPartTotal(subject); total > expected {
		expected = total
	}
	if expected > maxNZBGapExpansionFactor*n {
		logger.Warn("NZB segment numbering implies an implausible gap; keeping layout as-is",
			"file", subject, "segments", n, "declared", expected)
		return segments, 0
	}
	missing := expected - n
	if missing == 0 {
		if inOrder {
			return segments, 0
		}
		out := append([]nzb.Segment(nil), segments...)
		sort.Slice(out, func(i, j int) bool { return out[i].Number < out[j].Number })
		logger.Warn("NZB segments were out of order; sorted by declared number", "file", subject, "segments", n)
		return out, 0
	}

	if missing > MaxZeroFills {
		// The release can never stream: playback would exhaust the zero-fill
		// budget. Counting the gap is enough for the pre-flight to fail the
		// release, and skipping the placeholders keeps a wildly wrong declared
		// total from driving the allocation.
		logger.Warn("NZB file is missing more articles than playback can zero-fill",
			"file", subject, "segments", n, "declared", expected, "missing", missing, "max_zero_fills", MaxZeroFills)
		return segments, missing
	}

	byNumber := make(map[int]nzb.Segment, n)
	for _, s := range segments {
		byNumber[s.Number] = s
	}
	out := make([]nzb.Segment, 0, expected)
	var prevBytes int64
	for num := 1; num <= expected; num++ {
		if s, ok := byNumber[num]; ok {
			out = append(out, s)
			prevBytes = s.Bytes
			continue
		}
		// The placeholder inherits the nearest preceding real segment's encoded
		// size so class matching sizes it like the full segments around it;
		// leading placeholders are patched below once a real size is known.
		out = append(out, nzb.Segment{Number: num, Bytes: prevBytes})
	}
	var firstBytes int64
	for _, s := range out {
		if s.Bytes > 0 {
			firstBytes = s.Bytes
			break
		}
	}
	for i := range out {
		if out[i].Bytes > 0 {
			break // only the leading run of placeholders can lack a size
		}
		out[i].Bytes = firstBytes
	}
	logger.Warn("NZB file is missing articles; holes will be zero-filled at their declared offsets",
		"file", subject, "segments", n, "declared", expected, "missing", missing)
	return out, 0
}
