package nzb

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"streamnzb/pkg/core/logger"
)

func TestParseWithContextFiltersNullBytes(t *testing.T) {
	raw := []byte(`<?xml version="1.0"?><nzb><file subject="Movie.mkv"><groups><group>alt.binaries.test</group></groups><segments><segment bytes="10" number="1">message-id</segment></segments></file></nzb>`)
	raw = bytes.Replace(raw, []byte("Movie.mkv"), []byte("Movie\x00.mkv"), 1)

	document, err := ParseWithContext(context.Background(), bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("ParseWithContext: %v", err)
	}
	if len(document.Files) != 1 || document.Files[0].Subject != "Movie.mkv" {
		t.Fatalf("unexpected parsed files: %+v", document.Files)
	}
}

func TestMovieFileAnalysisDoesNotRunEpisodeParser(t *testing.T) {
	document := &NZB{Files: []File{{
		Subject:  "Movie.2026.2160p.mkv",
		Segments: []Segment{{ID: "message-id", Bytes: 10}},
	}}}

	infos := document.GetSessionContentFilesForEpisode(0, 0, 0)
	if len(infos) != 1 {
		t.Fatalf("expected one content file, got %d", len(infos))
	}
	if infos[0].episodeInfo != nil {
		t.Fatal("movie selection unexpectedly ran the episode parser")
	}
}

func TestCompressionType_posterAttribute(t *testing.T) {

	logger.Init("warn")

	path := filepath.Join("..", "..", "..", "The.Hobbit.The.Desolation.Of.Smaug.Extended.(2013).HDR.10bit.2160p.BT2020.DTS.HD.MA-VISIONPLUSHDR1000.NLsubs.nzb")
	if _, err := os.Stat(path); err != nil {
		t.Skipf("NZB file not found: %v", err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer f.Close()
	nzb, err := Parse(f)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	ct := nzb.CompressionType()
	if ct != "rar" {
		t.Errorf("CompressionType() = %q, want %q", ct, "rar")
	}
	contentFiles := nzb.GetContentFiles()
	if len(contentFiles) == 0 {
		t.Error("GetContentFiles() returned empty, expected RAR parts")
	}
}

func TestGetContentFilesForEpisodePrefersMatchingEpisode(t *testing.T) {
	logger.Init("ERROR")

	n := &NZB{Files: []File{
		{Subject: "Show.S01E06.1080p.mkv", Segments: []Segment{{ID: "<a>", Bytes: 600}}},
		{Subject: "Show.S01E05.1080p.mkv", Segments: []Segment{{ID: "<b>", Bytes: 500}}},
	}}

	files := n.GetContentFilesForEpisode(1, 5)
	if len(files) != 1 {
		t.Fatalf("expected one matching file, got %d", len(files))
	}
	if !strings.Contains(files[0].Filename, "S01E05") {
		t.Fatalf("expected episode 5 file, got %q", files[0].Filename)
	}
}

func TestGetContentFilesForEpisodePrefersSeasonPackAndOrdersFirstVolume(t *testing.T) {
	logger.Init("ERROR")

	n := &NZB{Files: []File{
		{Subject: "Show.S02E01.1080p.mkv", Segments: []Segment{{ID: "<a>", Bytes: 900}}},
		{Subject: "Show.S01.COMPLETE.part02.rar", Segments: []Segment{{ID: "<b>", Bytes: 350}}},
		{Subject: "Show.S01.COMPLETE.part01.rar", Segments: []Segment{{ID: "<c>", Bytes: 300}}},
	}}

	files := n.GetContentFilesForEpisode(1, 5)
	if len(files) != 2 {
		t.Fatalf("expected season pack archive set, got %d files", len(files))
	}
	if !strings.Contains(files[0].Filename, "part01") {
		t.Fatalf("expected first archive volume first, got %q", files[0].Filename)
	}
	if !strings.Contains(strings.ToLower(files[0].Filename), "show.s01.complete") {
		t.Fatalf("expected season pack selection, got %q", files[0].Filename)
	}
}

func TestGetPlaybackFileForEpisodePrefersRequestedEpisodeOverLargerOtherEpisode(t *testing.T) {
	logger.Init("ERROR")

	n := &NZB{Files: []File{
		{Subject: "Show.S01E06.1080p.mkv", Segments: []Segment{{ID: "<a>", Bytes: 900}}},
		{Subject: "Show.S01E05.1080p.mkv", Segments: []Segment{{ID: "<b>", Bytes: 500}}},
	}}

	info := n.GetPlaybackFileForEpisode(1, 5)
	if info == nil {
		t.Fatal("expected playback file")
	}
	if !strings.Contains(info.Filename, "S01E05") {
		t.Fatalf("expected episode 5 playback file, got %q", info.Filename)
	}
}

func TestCompressionTypeForEpisodeUsesSelectedPackGroup(t *testing.T) {
	logger.Init("ERROR")

	n := &NZB{Files: []File{
		{Subject: "Show.S01E05.1080p.mkv", Segments: []Segment{{ID: "<a>", Bytes: 300}}},
		{Subject: "Show.S02.COMPLETE.part01.rar", Segments: []Segment{{ID: "<b>", Bytes: 900}}},
		{Subject: "Show.S02.COMPLETE.part02.rar", Segments: []Segment{{ID: "<c>", Bytes: 950}}},
	}}

	if ct := n.CompressionTypeForEpisode(1, 5); ct != "direct" {
		t.Fatalf("expected direct compression for requested episode, got %q", ct)
	}
}

func TestGetSessionContentFilesForEpisodeKeepsAllCandidatesWhenNoEpisodeMatchExists(t *testing.T) {
	logger.Init("ERROR")

	n := &NZB{Files: []File{
		{Subject: "Altered.Carbon.Release.A.part02.rar", Segments: []Segment{{ID: "<a>", Bytes: 310}}},
		{Subject: "Altered.Carbon.Release.B.part01.rar", Segments: []Segment{{ID: "<b>", Bytes: 420}}},
		{Subject: "Altered.Carbon.Release.A.part01.rar", Segments: []Segment{{ID: "<c>", Bytes: 300}}},
		{Subject: "Altered.Carbon.Release.B.part02.rar", Segments: []Segment{{ID: "<d>", Bytes: 410}}},
	}}

	files := n.GetSessionContentFilesForEpisode(2, 1, 0)
	if len(files) != 4 {
		t.Fatalf("expected all content candidates, got %d", len(files))
	}
	var sawA, sawB bool
	for _, file := range files {
		if strings.Contains(file.Filename, "Release.A") {
			sawA = true
		}
		if strings.Contains(file.Filename, "Release.B") {
			sawB = true
		}
	}
	if !sawA || !sawB {
		t.Fatalf("expected files from both fallback groups, sawA=%v sawB=%v", sawA, sawB)
	}
}

func TestGetSessionContentFilesForEpisodeMatchesAbsoluteNumberedAnime(t *testing.T) {
	logger.Init("ERROR")

	// One Piece S10E01 == absolute 337; the release carries only the absolute
	// number, alongside a decoy episode from the same show.
	n := &NZB{Files: []File{
		{Subject: "One Piece - 337 - Plunging into the Devils Sea [1080p][x264].mkv", Segments: []Segment{{ID: "<a>", Bytes: 900}}},
		{Subject: "One Piece - 338 - Landing to Get to Fish-Man Island [1080p][x264].mkv", Segments: []Segment{{ID: "<b>", Bytes: 950}}},
	}}

	files := n.GetSessionContentFilesForEpisode(10, 1, 337)
	if len(files) != 1 {
		t.Fatalf("expected the absolute-numbered episode group, got %d files", len(files))
	}
	if !strings.Contains(files[0].Filename, "337") {
		t.Fatalf("expected episode 337 file, got %q", files[0].Filename)
	}
}

func TestDescribeMissingContentReportsPar2OnlyNZB(t *testing.T) {
	logger.Init("ERROR")

	// nZEDb-style hashed release whose content file never made it into the
	// NZB — only the PAR2 recovery set was published.
	n := &NZB{Files: []File{
		{Subject: `[02/11] - "18a691ba.par2" yEnc(1/1)`, Segments: []Segment{{ID: "<a>", Bytes: 42199}}},
		{Subject: `[03/11] - "18a691ba.vol000+001.par2" yEnc(1/1)`, Segments: []Segment{{ID: "<b>", Bytes: 310126}}},
		{Subject: `[04/11] - "18a691ba.vol001+002.par2" yEnc(1/1)`, Segments: []Segment{{ID: "<c>", Bytes: 578044}}},
	}}

	if files := n.GetSessionContentFilesForEpisode(0, 0, 0); len(files) != 0 {
		t.Fatalf("expected no content candidates, got %d", len(files))
	}
	detail := n.DescribeMissingContent()
	if !strings.Contains(detail, "PAR2") {
		t.Fatalf("expected PAR2-only diagnosis, got %q", detail)
	}
	if !strings.Contains(detail, "3") {
		t.Fatalf("expected the file count in the diagnosis, got %q", detail)
	}
}

func TestDescribeMissingContentEmptyWhenPlayableFileExists(t *testing.T) {
	logger.Init("ERROR")

	n := &NZB{Files: []File{
		{Subject: `[01/02] - "Show.S01E01.mkv" yEnc(1/1)`, Segments: []Segment{{ID: "<a>", Bytes: 900}}},
		{Subject: `[02/02] - "Show.S01E01.par2" yEnc(1/1)`, Segments: []Segment{{ID: "<b>", Bytes: 100}}},
	}}

	if detail := n.DescribeMissingContent(); detail != "" {
		t.Fatalf("expected no diagnosis when content exists, got %q", detail)
	}
}

func TestDescribeMissingContentHandlesEmptyNZB(t *testing.T) {
	logger.Init("ERROR")

	if detail := (&NZB{}).DescribeMissingContent(); detail == "" {
		t.Fatal("expected a diagnosis for an NZB with no files")
	}
}

// obfuscatedNZB builds a release whose subjects are random hashes with no
// extension — the "fully obfuscated" shape that subject-based classification
// cannot recognise at all.
func obfuscatedNZB() *NZB {
	files := []File{
		{Subject: `[01/13] - "aa11bb22cc33dd44.par2" yEnc (1/1)`, Segments: []Segment{{ID: "<p0>", Bytes: 40_000}}},
		{Subject: `[02/13] - "aa11bb22cc33dd44.vol000+01.par2" yEnc (1/5)`, Segments: []Segment{{ID: "<p1>", Bytes: 3_500_000}}},
		{Subject: `[03/13] - "0a1b2c3d4e5f6071" yEnc (1/1)`, Segments: []Segment{{ID: "<nfo>", Bytes: 4_000}}},
	}
	hashes := []string{
		"3f8a91b2c7d6e5f4", "9c0d1e2f3a4b5c6d", "7e6d5c4b3a291807", "1122334455667788",
		"abcdefabcdefabcd", "00112233445566aa", "ffeeddccbbaa9988", "13579bdf2468ace0",
		"0f1e2d3c4b5a6978", "5a5b5c5d5e5f6061",
	}
	for i, h := range hashes {
		files = append(files, File{
			Subject:  `[` + string(rune('0'+(i+4)/10)) + string(rune('0'+(i+4)%10)) + `/13] - "` + h + `" yEnc (1/100)`,
			Segments: []Segment{{ID: "<" + h + ">", Bytes: 70_000_000}},
		})
	}
	return &NZB{Files: files}
}

func TestObfuscatedReleaseYieldsOneContentSet(t *testing.T) {
	logger.Init("ERROR")

	n := obfuscatedNZB()

	if detail := n.DescribeMissingContent(); detail != "" {
		t.Fatalf("obfuscated release reported as having no content: %s", detail)
	}

	// Every non-PAR2 file lands in one set: the 10 payload files plus the small
	// unidentified one. Size is deliberately not a filter here — the smallest
	// file of an obfuscated set is often its PAR2 index, and the unpack layer
	// is what tells payload from recovery data.
	files := n.GetSessionContentFilesForEpisode(0, 0, 0)
	if len(files) != 11 {
		names := make([]string, 0, len(files))
		for _, f := range files {
			names = append(names, f.Filename)
		}
		t.Fatalf("expected all 11 non-PAR2 files in one set, got %d (%v)", len(files), names)
	}
	for _, f := range files {
		if f.Extension == ".par2" {
			t.Fatalf("PAR2 recovery file admitted as content: %q", f.Filename)
		}
	}
	if largest := n.GetLargestContentFile(); largest == nil || largest.Size != 70_000_000 {
		t.Fatalf("expected a payload file as the largest, got %+v", largest)
	}

	// The whole set must also survive the pattern-grouped selection path used by
	// validation and compression-type detection.
	if got := len(n.GetContentFiles()); got != 11 {
		t.Fatalf("GetContentFiles() = %d files, want 11", got)
	}
}

func TestObfuscatedClassificationStaysSubjectFirst(t *testing.T) {
	logger.Init("ERROR")

	// One recognisable .mkv alongside extension-less junk: the subject already
	// classifies the release, so nothing may be admitted by elimination.
	n := &NZB{Files: []File{
		{Subject: `Show.S01E01.1080p.mkv`, Segments: []Segment{{ID: "<a>", Bytes: 70_000_000}}},
		{Subject: `[02/02] - "0a1b2c3d4e5f6071" yEnc (1/1)`, Segments: []Segment{{ID: "<b>", Bytes: 90_000_000}}},
	}}

	for _, info := range n.GetFileInfo() {
		if info.IsObfuscated {
			t.Fatalf("file %q admitted by elimination despite a classified release", info.Filename)
		}
	}
	files := n.GetContentFiles()
	if len(files) != 1 || files[0].Filename != "Show.S01E01.1080p.mkv" {
		t.Fatalf("expected only the .mkv, got %v", files)
	}
}

func TestObfuscatedContentSetAdmitsDottedHashes(t *testing.T) {
	logger.Init("ERROR")

	// A hash carrying a dot reads as having an extension, but it is no more a
	// filename than a bare hash is.
	n := &NZB{Files: []File{
		{Subject: `[1/3] - "abc.xyz.1a2b3c4d" yEnc (1/100)`, Segments: []Segment{{ID: "<a>", Bytes: 70_000_000}}},
		{Subject: `[2/3] - "abc.xyz.5e6f7a8b" yEnc (1/100)`, Segments: []Segment{{ID: "<b>", Bytes: 70_000_000}}},
		{Subject: `[3/3] - "abc.xyz.par2" yEnc (1/1)`, Segments: []Segment{{ID: "<c>", Bytes: 40_000}}},
	}}

	files := n.GetSessionContentFilesForEpisode(0, 0, 0)
	if len(files) != 2 {
		t.Fatalf("expected both payload files, got %d", len(files))
	}
	for _, f := range files {
		if f.Extension == ".par2" {
			t.Fatalf("PAR2 file admitted as content: %q", f.Filename)
		}
	}
}
