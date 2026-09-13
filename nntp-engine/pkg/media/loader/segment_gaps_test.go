package loader

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"strings"
	"testing"

	"streamnzb/pkg/media/nzb"
)

func TestDeclaredYencPartTotal(t *testing.T) {
	cases := []struct {
		subject string
		want    int
	}{
		{`[1/3] - "Show.S01E06.mkv" yEnc(1/6510)`, 6510},
		{`[2/9] - "abc.part1.rar" yEnc  524288000 (1/732)`, 732},
		{`Show.S01E06.1080p.mkv`, 0},
		{`Show (1/12) without the marker word`, 0},
		{`[1/3] - "name" yEnc`, 0},
	}
	for _, c := range cases {
		if got := declaredYencPartTotal(c.subject); got != c.want {
			t.Fatalf("declaredYencPartTotal(%q) = %d, want %d", c.subject, got, c.want)
		}
	}
}

// gappedNZBSegments builds n declared segments of size bytes each, then drops
// the 1-based numbers in missing — the shape of an NZB whose indexer never saw
// those articles.
func gappedNZBSegments(n int, bytes int64, missing ...int) []nzb.Segment {
	gone := make(map[int]bool, len(missing))
	for _, num := range missing {
		gone[num] = true
	}
	segments := make([]nzb.Segment, 0, n)
	for num := 1; num <= n; num++ {
		if gone[num] {
			continue
		}
		segments = append(segments, nzb.Segment{ID: fmt.Sprintf("<seg-%d>", num), Number: num, Bytes: bytes})
	}
	return segments
}

func TestNormalizeContiguousSegmentsUntouched(t *testing.T) {
	in := gappedNZBSegments(8, 1024)
	out, missing := normalizeNZBSegments("test.mkv", in)
	if missing != 0 {
		t.Fatalf("missing = %d, want 0", missing)
	}
	if &out[0] != &in[0] || len(out) != len(in) {
		t.Fatal("a contiguous file must come back as the original slice")
	}
}

func TestNormalizeFillsMiddleGap(t *testing.T) {
	in := gappedNZBSegments(12, 1024, 5, 9)
	out, missing := normalizeNZBSegments("test.mkv", in)
	if missing != 0 {
		t.Fatalf("materialized gaps must not be reported as unmaterialized, got %d", missing)
	}
	if len(out) != 12 {
		t.Fatalf("len(out) = %d, want the declared 12", len(out))
	}
	for i, s := range out {
		if s.Number != i+1 {
			t.Fatalf("segment %d has number %d, want %d", i, s.Number, i+1)
		}
		isPlaceholder := i == 4 || i == 8
		if (s.ID == "") != isPlaceholder {
			t.Fatalf("segment %d placeholder = %v, want %v", i, s.ID == "", isPlaceholder)
		}
		if s.Bytes != 1024 {
			t.Fatalf("segment %d bytes = %d, want the neighbour's 1024", i, s.Bytes)
		}
	}
}

func TestNormalizeFillsTrailingGapFromSubject(t *testing.T) {
	in := gappedNZBSegments(10, 1024)
	out, missing := normalizeNZBSegments(`[1/3] - "test.mkv" yEnc (1/12)`, in)
	if missing != 0 {
		t.Fatalf("missing = %d, want 0", missing)
	}
	if len(out) != 12 {
		t.Fatalf("len(out) = %d, want the subject-declared 12", len(out))
	}
	for i := 10; i < 12; i++ {
		if out[i].ID != "" || out[i].Bytes != 1024 {
			t.Fatalf("segment %d = %+v, want a 1024-byte placeholder", i, out[i])
		}
	}
}

func TestNormalizeFillsLeadingGapWithFollowingSize(t *testing.T) {
	in := gappedNZBSegments(6, 2048, 1, 2)
	out, missing := normalizeNZBSegments("test.mkv", in)
	if missing != 0 || len(out) != 6 {
		t.Fatalf("normalize = (%d segments, %d missing), want (6, 0)", len(out), missing)
	}
	for i := 0; i < 2; i++ {
		if out[i].ID != "" || out[i].Bytes != 2048 {
			t.Fatalf("leading placeholder %d = %+v, want the following segment's 2048 bytes", i, out[i])
		}
	}
}

func TestNormalizeSortsOutOfOrderSegments(t *testing.T) {
	in := gappedNZBSegments(4, 1024)
	in[1], in[2] = in[2], in[1]
	out, missing := normalizeNZBSegments("test.mkv", in)
	if missing != 0 {
		t.Fatalf("missing = %d, want 0", missing)
	}
	for i, s := range out {
		if s.Number != i+1 {
			t.Fatalf("segment %d has number %d after sorting, want %d", i, s.Number, i+1)
		}
	}
	if in[1].Number != 3 {
		t.Fatal("the caller's slice must not be mutated")
	}
}

func TestNormalizeLeavesUnreliableNumberingAlone(t *testing.T) {
	dup := gappedNZBSegments(4, 1024)
	dup[2].Number = 2
	unnumbered := gappedNZBSegments(4, 1024)
	unnumbered[0].Number = 0
	implausible := []nzb.Segment{
		{ID: "<a>", Number: 1, Bytes: 1024},
		{ID: "<b>", Number: 100, Bytes: 1024},
	}
	for name, in := range map[string][]nzb.Segment{
		"duplicate":   dup,
		"unnumbered":  unnumbered,
		"implausible": implausible,
	} {
		out, missing := normalizeNZBSegments("test.mkv", in)
		if missing != 0 || &out[0] != &in[0] || len(out) != len(in) {
			t.Fatalf("%s numbering must be left untouched, got %d segments and missing=%d", name, len(out), missing)
		}
	}
}

func TestNormalizeLargeGapCountedNotFilled(t *testing.T) {
	missingNums := make([]int, 0, MaxZeroFills+2)
	for num := 2; num < MaxZeroFills+4; num++ {
		missingNums = append(missingNums, num)
	}
	in := gappedNZBSegments(30, 1024, missingNums...)
	out, missing := normalizeNZBSegments("test.mkv", in)
	if missing != MaxZeroFills+2 {
		t.Fatalf("missing = %d, want %d", missing, MaxZeroFills+2)
	}
	if len(out) != len(in) {
		t.Fatalf("a gap past the zero-fill budget must not be materialized, got %d segments", len(out))
	}
}

func TestNewFileCountsMissingFromNZB(t *testing.T) {
	small := NewFile(context.Background(), &nzb.File{
		Subject:  "small.mkv",
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(12, 1024, 5, 9),
	}, nil, nil)
	if got := small.MissingFromNZB(); got != 2 {
		t.Fatalf("MissingFromNZB() = %d, want 2", got)
	}
	if got := small.SegmentCount(); got != 12 {
		t.Fatalf("SegmentCount() = %d, want the declared 12", got)
	}

	missingNums := make([]int, 0, MaxZeroFills+2)
	for num := 2; num < MaxZeroFills+4; num++ {
		missingNums = append(missingNums, num)
	}
	large := NewFile(context.Background(), &nzb.File{
		Subject:  "large.mkv",
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(30, 1024, missingNums...),
	}, nil, nil)
	if got := large.MissingFromNZB(); got != MaxZeroFills+2 {
		t.Fatalf("MissingFromNZB() = %d, want %d", got, MaxZeroFills+2)
	}
}

// The regression this package comment describes: articles missing from the NZB
// itself must read back as zero-filled holes at their declared offsets, not
// silently shift every byte after the gap.
func TestNZBGapPlaceholdersZeroFillDuringPlayback(t *testing.T) {
	const segments, segmentSize = 12, 1024
	fetcher := newDamagedSegmentFetcher(segmentSize)
	f := NewFile(context.Background(), &nzb.File{
		Subject:  "test.mkv",
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(segments, segmentSize, 4, 8),
	}, nil, fetcher)

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()

	got, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("reading a release with two NZB gaps must succeed, got: %v", err)
	}
	if len(got) != segments*segmentSize {
		t.Fatalf("read %d bytes, want the declared %d", len(got), segments*segmentSize)
	}
	for i := 0; i < segments; i++ {
		want := segmentPayload(i, segmentSize)
		if i == 3 || i == 7 {
			want = make([]byte, segmentSize)
		}
		if !bytes.Equal(got[i*segmentSize:(i+1)*segmentSize], want) {
			t.Fatalf("segment %d did not read back at its declared offset", i)
		}
	}
	if holes := f.ZeroFilledSegments(); holes != 2 {
		t.Fatalf("zero-filled segments = %d, want 2", holes)
	}
}

func TestNZBGapAtHeaderIsFatal(t *testing.T) {
	const segmentSize = 1024
	fetcher := newDamagedSegmentFetcher(segmentSize)
	f := NewFile(context.Background(), &nzb.File{
		Subject:  "test.mkv",
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(6, segmentSize, 1),
	}, nil, fetcher)

	_, err := f.DownloadSegment(context.Background(), 0)
	if err == nil || !strings.Contains(err.Error(), "segment unavailable") {
		t.Fatalf("a missing header article must be a definitive verdict, got: %v", err)
	}
}

func TestCheckFirstSegmentExistsSkipsDeclaredHoles(t *testing.T) {
	fetcher := &statSpyFetcher{hint: 4}
	// 40 declared segments with number 20 missing: index 19 is one of the
	// spread sample points, so the sweep must skip the placeholder instead of
	// condemning a release two zero-fills can carry.
	f := NewFile(context.Background(), &nzb.File{
		Subject:  "test.mkv",
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(40, 1024, 20),
	}, nil, fetcher)

	exists, err := f.CheckFirstSegmentExists(context.Background())
	if err != nil || !exists {
		t.Fatalf("a single declared hole must not fail the sweep, got exists=%v err=%v", exists, err)
	}

	headGap := NewFile(context.Background(), &nzb.File{
		Subject:  "test.mkv",
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(40, 1024, 1),
	}, nil, &statSpyFetcher{hint: 4})
	exists, err = headGap.CheckFirstSegmentExists(context.Background())
	if err != nil || exists {
		t.Fatalf("a missing header article must fail the sweep, got exists=%v err=%v", exists, err)
	}
}

func TestSegmentProbePlanSkipsDeclaredHoles(t *testing.T) {
	f := NewFile(context.Background(), &nzb.File{
		Subject:  `[1/1] - "test.mkv" yEnc (1/8)`,
		Groups:   []string{"alt.test"},
		Segments: gappedNZBSegments(8, 1024, 3, 7, 8),
	}, nil, nil)

	indices := segmentProbeIndices(f.Segments(), nil, false, true)
	for _, idx := range indices {
		if strings.TrimSpace(f.Segments()[idx].ID) == "" {
			t.Fatalf("probe plan selected unfetchable segment %d", idx)
		}
	}
	// The trailing placeholders must not stop the plan from probing the last
	// article the NZB actually carries.
	last := -1
	for _, idx := range indices {
		if idx > last {
			last = idx
		}
	}
	if last != 5 {
		t.Fatalf("last probed index = %d, want 5 (the last fetchable segment)", last)
	}
}
