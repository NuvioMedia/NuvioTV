package nzb

import (
	"bytes"
	"context"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dreulavelle/jhin"
	"golang.org/x/net/html/charset"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/fileutil"
	searchparser "streamnzb/pkg/search/parser"
)

// defaultParseTimeout bounds nzb.Parse when no context deadline is provided,
// preventing a malformed or huge NZB from hanging the caller indefinitely.
const defaultParseTimeout = 30 * time.Second

type NZB struct {
	Head  Head   `xml:"head"`
	Files []File `xml:"file"`

	// Cached analysis state. Populated lazily by GetFileInfo and reused by
	// subsequent content-selection / compression-type calls.
	fileInfoOnce sync.Once
	fileInfos    []*FileInfo
}

type Head struct {
	Meta []Meta `xml:"meta"`
}

type Meta struct {
	Type  string `xml:"type,attr"`
	Value string `xml:",chardata"`
}

type File struct {
	Poster   string    `xml:"poster,attr"`
	Date     int64     `xml:"date,attr"`
	Subject  string    `xml:"subject,attr"`
	Groups   []string  `xml:"groups>group"`
	Segments []Segment `xml:"segments>segment"`
}

type Segment struct {
	Bytes  int64  `xml:"bytes,attr"`
	Number int    `xml:"number,attr"`
	ID     string `xml:",chardata"`
}

type FileInfo struct {
	File      *File
	Filename  string
	Extension string
	Size      int64
	IsVideo   bool
	IsSample  bool
	IsExtra   bool

	// IsObfuscated marks a payload file admitted by elimination rather than by
	// its subject: see markObfuscatedContentSet.
	IsObfuscated bool

	// Cached derived values to avoid recomputing string work during sorting
	// and content grouping. Populated in analyzeFile.
	pattern     string
	patternOnce sync.Once

	// Precomputed sort keys used by sortContentFiles.
	leadPriority     int
	leadPriorityOnce sync.Once
	sequence         int
	sequenceOnce     sync.Once
	episodeInfo      *jhin.Result
	episodeInfoOnce  sync.Once
}

// Parse reads and decodes an NZB document from r. It applies a default
// timeout (defaultParseTimeout) so a malformed or slow reader cannot hang
// the caller. Use ParseWithContext to supply your own deadline/cancellation.
func Parse(r io.Reader) (*NZB, error) {
	ctx, cancel := context.WithTimeout(context.Background(), defaultParseTimeout)
	defer cancel()
	return ParseWithContext(ctx, r)
}

// ParseWithContext reads and decodes an NZB document from r, honouring ctx
// for cancellation/deadline. It is safe to pass a context with no deadline
// (the decode will still be cancellable).
func ParseWithContext(ctx context.Context, r io.Reader) (*NZB, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	type parseResult struct {
		document *NZB
		err      error
	}
	result := make(chan parseResult, 1)
	go func() {
		document, err := decodeNZBReader(ctx, &nullFilteringReader{reader: r})
		result <- parseResult{document: document, err: err}
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case parsed := <-result:
		return parsed.document, parsed.err
	}
}

// ParseBytesWithContext decodes an NZB already held in memory without copying
// the complete document through io.ReadAll again.
func ParseBytesWithContext(ctx context.Context, raw []byte) (*NZB, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if bytes.ContainsRune(raw, 0) {
		raw = bytes.ReplaceAll(raw, []byte{0x00}, nil)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return decodeNZBReader(ctx, bytes.NewReader(raw))
}

func decodeNZBReader(ctx context.Context, reader io.Reader) (*NZB, error) {
	var nzb NZB
	decoder := xml.NewDecoder(&contextReader{ctx: ctx, reader: reader})
	decoder.CharsetReader = charset.NewReaderLabel
	if err := decoder.Decode(&nzb); err != nil {
		return nil, err
	}
	return &nzb, nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r *contextReader) Read(buffer []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(buffer)
}

type nullFilteringReader struct {
	reader io.Reader
}

func (r *nullFilteringReader) Read(buffer []byte) (int, error) {
	for {
		read, err := r.reader.Read(buffer)
		firstNull := bytes.IndexByte(buffer[:read], 0)
		if firstNull < 0 {
			return read, err
		}
		written := firstNull
		for _, value := range buffer[firstNull:read] {
			if value != 0 {
				buffer[written] = value
				written++
			}
		}
		if written > 0 || err != nil || len(buffer) == 0 {
			return written, err
		}
	}
}

func (n *NZB) Password() string {
	for _, m := range n.Head.Meta {
		if strings.EqualFold(m.Type, "password") {
			return strings.TrimSpace(m.Value)
		}
	}
	return ""
}

func (n *NZB) Hash() string {
	if len(n.Files) == 0 {
		return ""
	}

	subject := n.Files[0].Subject
	if subject == "" {
		subject = n.Files[0].Poster
	}
	h := sha256.New()
	h.Write([]byte(subject))
	return hex.EncodeToString(h.Sum(nil))[:16]
}

func (n *NZB) CalculateID() string {
	if len(n.Files) == 0 || len(n.Files[0].Segments) == 0 {
		return ""
	}

	msgID := n.Files[0].Segments[0].ID
	msgID = strings.Trim(msgID, "<>")
	h := sha1.New()
	h.Write([]byte(msgID))
	return hex.EncodeToString(h.Sum(nil))
}

func (n *NZB) TotalSize() int64 {
	var total int64
	for _, file := range n.Files {
		for _, seg := range file.Segments {
			total += seg.Bytes
		}
	}
	return total
}

// GetFileInfo analyzes every file in the NZB and returns the cached result.
// Episode parsing remains lazy because movie playback does not need it.
func (n *NZB) GetFileInfo() []*FileInfo {
	n.fileInfoOnce.Do(func() {
		infos := make([]*FileInfo, 0, len(n.Files))
		for i := range n.Files {
			file := &n.Files[i]
			info := analyzeFile(file)
			infos = append(infos, info)
		}
		markObfuscatedContentSet(infos)
		n.fileInfos = infos
	})
	return n.fileInfos
}

func (n *NZB) GetLargestContentFile() *FileInfo {
	return largestContentFile(n.GetFileInfo())
}

func largestContentFile(infos []*FileInfo) *FileInfo {
	var largest *FileInfo
	var maxSize int64
	for _, info := range infos {
		if info.IsSample || info.IsExtra {
			continue
		}
		if info.Size <= maxSize {
			continue
		}
		if isContentCandidate(info) {
			maxSize = info.Size
			largest = info
		}
	}
	return largest
}

func (n *NZB) GetPlaybackFile() *FileInfo {
	return n.GetPlaybackFileForEpisode(0, 0)
}

func (n *NZB) GetPlaybackFileForEpisode(season, episode int) *FileInfo {
	contentFiles := n.GetContentFilesForEpisode(season, episode)
	if len(contentFiles) == 0 {
		return n.GetLargestContentFile()
	}

	if ct := compressionTypeFromContentFiles(contentFiles); ct != "direct" {
		return contentFiles[0]
	}

	if season > 0 && episode > 0 {
		if largest := largestContentFile(contentFiles); largest != nil {
			return largest
		}
	}

	return n.GetLargestContentFile()
}

func (n *NZB) GetContentFiles() []*FileInfo {
	return n.GetContentFilesForEpisode(0, 0)
}

func (n *NZB) GetContentFilesForEpisode(season, episode int) []*FileInfo {
	infos := n.GetFileInfo()
	if contentFiles := selectEpisodeContentFiles(infos, season, episode, 0); len(contentFiles) > 0 {
		return contentFiles
	}
	return selectLargestContentFiles(infos)
}

// GetSessionContentFilesForEpisode selects the content files serving the
// requested episode. absoluteEpisode (0 when unknown) is the anime absolute
// number of the same episode; files carrying it match even though their
// parsed season/episode differ from the request.
func (n *NZB) GetSessionContentFilesForEpisode(season, episode, absoluteEpisode int) []*FileInfo {
	infos := n.GetFileInfo()
	if contentFiles := selectEpisodeContentFiles(infos, season, episode, absoluteEpisode); len(contentFiles) > 0 {
		logger.Debug("Session episode content selection matched targeted NZB group",
			"season", season,
			"episode", episode,
			"absolute_episode", absoluteEpisode,
			"files", len(contentFiles),
			"samples", sampleContentFilenames(contentFiles, 6))
		return contentFiles
	}
	contentFiles := selectAllContentFiles(infos)
	logger.Debug("Session episode content selection fell back to all content candidates",
		"season", season,
		"episode", episode,
		"absolute_episode", absoluteEpisode,
		"files", len(contentFiles),
		"samples", sampleContentFilenames(contentFiles, 8))
	return contentFiles
}

func selectEpisodeContentFiles(infos []*FileInfo, season, episode, absoluteEpisode int) []*FileInfo {
	if (season <= 0 || episode <= 0) && absoluteEpisode <= 0 {
		return nil
	}

	type groupChoice struct {
		pattern string
		rank    int
		size    int64
		order   int
	}

	groups := make(map[string][]*FileInfo)
	order := make(map[string]int)
	groupOrder := 0
	for _, info := range infos {
		if !isContentCandidate(info) {
			continue
		}
		pattern := info.getFilePattern()
		if pattern == "" {
			continue
		}
		if _, ok := groups[pattern]; !ok {
			order[pattern] = groupOrder
			groupOrder++
		}
		groups[pattern] = append(groups[pattern], info)
	}

	var best groupChoice
	found := false
	for pattern, files := range groups {
		choice := groupChoice{pattern: pattern, order: order[pattern]}
		for _, info := range files {
			choice.size += info.Size
			if rank := episodeMatchRank(info, season, episode, absoluteEpisode); rank > choice.rank {
				choice.rank = rank
			}
		}
		logger.Debug("NZB episode content group evaluated",
			"season", season,
			"episode", episode,
			"absolute_episode", absoluteEpisode,
			"pattern", pattern,
			"files", len(files),
			"rank", choice.rank,
			"size", choice.size,
			"samples", sampleContentFilenames(files, 3))
		if choice.rank == 0 {
			continue
		}
		if !found || choice.rank > best.rank ||
			(choice.rank == best.rank && (choice.size > best.size ||
				(choice.size == best.size && choice.order < best.order))) {
			best = choice
			found = true
		}
	}

	if !found {
		logger.Debug("NZB episode selection found no matching content group",
			"season", season,
			"episode", episode,
			"groups", len(groups))
		return nil
	}
	contentFiles := collectPatternContentFiles(infos, best.pattern)
	logger.Debug("NZB episode selection chose content group",
		"season", season,
		"episode", episode,
		"pattern", best.pattern,
		"rank", best.rank,
		"size", best.size,
		"files", len(contentFiles),
		"samples", sampleContentFilenames(contentFiles, 4))
	return contentFiles
}

func selectLargestContentFiles(infos []*FileInfo) []*FileInfo {
	var mainPattern string
	var maxSize int64

	for _, info := range infos {
		if !isContentCandidate(info) {
			continue
		}

		if info.Size > maxSize {
			maxSize = info.Size
			mainPattern = info.getFilePattern()
		}
	}

	if mainPattern == "" {
		for _, info := range infos {
			if info.IsSample || info.IsExtra {
				continue
			}
			if info.Size > maxSize {
				maxSize = info.Size
				mainPattern = info.getFilePattern()
			}
		}
	}

	contentFiles := collectPatternContentFiles(infos, mainPattern)
	if len(contentFiles) == 0 {
		logGetContentFilesEmpty(infos, mainPattern)
	}

	return contentFiles
}

func selectAllContentFiles(infos []*FileInfo) []*FileInfo {
	contentFiles := make([]*FileInfo, 0, len(infos))
	for _, info := range infos {
		if !isContentCandidate(info) {
			continue
		}
		contentFiles = append(contentFiles, info)
	}
	sortContentFiles(contentFiles)
	return contentFiles
}

func collectPatternContentFiles(infos []*FileInfo, pattern string) []*FileInfo {
	if pattern == "" {
		return nil
	}
	var contentFiles []*FileInfo
	for _, info := range infos {
		if info.getFilePattern() == pattern {
			contentFiles = append(contentFiles, info)
		}
	}
	sortContentFiles(contentFiles)
	return contentFiles
}

func isContentCandidate(info *FileInfo) bool {
	if info == nil || info.IsSample || info.IsExtra {
		return false
	}
	return isSubjectClassifiedContent(info) || info.IsObfuscated
}

// isSubjectClassifiedContent reports whether the subject alone proves the file
// is payload — a known video container or archive extension.
func isSubjectClassifiedContent(info *FileInfo) bool {
	if info == nil || info.IsSample || info.IsExtra {
		return false
	}
	return info.IsVideo || info.Extension == ".rar" || info.Extension == ".7z" ||
		isArchivePart(info.Extension) || isRarVolume(info.Extension) ||
		isSplitArchivePart(info.Extension) || isRarSplitPart(info.Extension, info.Filename)
}

// obfuscatedPattern groups every file of an obfuscated release into one content
// set. Their subjects are unrelated random tokens, so the per-file pattern that
// groups a normal release would split each volume into a set of its own and the
// session would receive a single volume out of dozens.
const obfuscatedPattern = "\x00obfuscated"

// markObfuscatedContentSet admits a release's payload files when subject-based
// classification found nothing playable at all. A fully obfuscated post carries
// random-hash subjects with no extension, so every file classifies as "other"
// and the release is refused before a single byte is read — even though the real
// names are recoverable from PAR2, yEnc headers and content signatures once
// something is allowed to read them.
//
// Classification stays subject-first: the moment one file classifies normally,
// this returns and nothing is marked. Admission here is deliberately coarse
// (anything not sample/extra, above a relative size floor); telling payload from
// PAR2 volumes and cover art needs the file's bytes, which is the unpack layer's
// job, not this one's.
func markObfuscatedContentSet(infos []*FileInfo) {
	candidates := make([]*FileInfo, 0, len(infos))
	for _, info := range infos {
		if isSubjectClassifiedContent(info) {
			return
		}
		// No extension test here: an obfuscated hash can carry a dot ("abc.xyz.
		// 1a2b3c"), which reads as an extension and would exclude exactly the
		// files this exists to admit. Nothing in the release classified, so any
		// extension present is by definition not a content one, and the sample
		// / extra checks above already cover PAR2, NFO, art and subtitles.
		//
		// There is deliberately no size floor either. A floor looks like cheap
		// junk filtering, but the smallest file in an obfuscated set is usually
		// its PAR2 index — the one file that names all the others — and
		// dropping it forces name recovery onto a multi-megabyte recovery
		// volume instead. Admitting a stray NFO costs a lazy loader file and
		// one article; the unpack layer excludes it by content signature.
		if info == nil || info.IsSample || info.IsExtra {
			continue
		}
		info.IsObfuscated = true
		candidates = append(candidates, info)
	}
	if len(candidates) == 0 {
		return
	}
	logger.Debug("NZB admitted an obfuscated content set",
		"files", len(candidates),
		"samples", sampleContentFilenames(candidates, 6))
}

// DescribeMissingContent explains why the NZB yields no playable file, so
// callers can report something actionable instead of a bare "no content
// files". A PAR2-only NZB is the common case: the indexer published the
// recovery set but not the content file itself, and no amount of retrying
// will make it playable. Returns "" when a content candidate does exist.
func (n *NZB) DescribeMissingContent() string {
	if n == nil || len(n.Files) == 0 {
		return "the NZB contains no files"
	}
	infos := n.GetFileInfo()
	var par2, samples, other int
	for _, info := range infos {
		if isContentCandidate(info) {
			return ""
		}
		switch {
		case info.Extension == ".par2":
			par2++
		case info.IsSample:
			samples++
		default:
			other++
		}
	}
	switch len(infos) {
	case par2:
		return fmt.Sprintf("all %d files are PAR2 recovery data — the content file is missing from the NZB", par2)
	case samples:
		return fmt.Sprintf("all %d files are samples", samples)
	}
	return fmt.Sprintf("none of the %d files is playable content (%d PAR2, %d sample, %d other)",
		len(infos), par2, samples, other)
}

// episodePartialParser parses only the seasons/episodes fields, avoiding the
// full handler set used by jhin.Parse. It is reused across all episode-match
// rank evaluations.
var episodePartialParser = sync.OnceValue(func() func(string) *jhin.Result {
	return jhin.GetPartialParser([]string{"seasons", "episodes"})
})

func episodeMatchRank(info *FileInfo, season, episode, absoluteEpisode int) int {
	if (season <= 0 || episode <= 0) && absoluteEpisode <= 0 {
		return 0
	}
	if info == nil {
		return 0
	}
	info.episodeInfoOnce.Do(func() {
		info.episodeInfo = episodePartialParser()(info.Filename)
	})
	parsed := searchparser.FromResult(info.Filename, info.episodeInfo)
	if parsed == nil {
		logger.Debug("NZB episode filename parse returned nil",
			"filename", info.Filename,
			"season", season,
			"episode", episode)
		return 0
	}
	rank := parsed.EpisodeMatchRank(season, episode)
	if absoluteEpisode > 0 {
		// Absolute-numbered anime files carry no season (or season 1), which
		// is exactly the season<=0 match path.
		if absRank := parsed.EpisodeMatchRank(0, absoluteEpisode); absRank > rank {
			rank = absRank
		}
	}
	logger.Debug("NZB episode filename rank evaluated",
		"filename", info.Filename,
		"requested_season", season,
		"requested_episode", episode,
		"requested_absolute_episode", absoluteEpisode,
		"rank", rank,
		"parsed_season", parsed.Season,
		"parsed_episode", parsed.Episode,
		"parsed_seasons", parsed.Seasons,
		"parsed_episodes", parsed.Episodes,
		"complete", parsed.Complete,
		"episode_code", parsed.EpisodeCode)
	return rank
}

func sampleContentFilenames(files []*FileInfo, limit int) []string {
	if limit <= 0 {
		return nil
	}
	samples := make([]string, 0, min(limit, len(files)))
	for _, info := range files {
		if info == nil {
			continue
		}
		samples = append(samples, info.Filename)
		if len(samples) >= limit {
			break
		}
	}
	return samples
}

func sortContentFiles(files []*FileInfo) {
	sort.SliceStable(files, func(i, j int) bool {
		left := files[i]
		right := files[j]
		if left == nil || right == nil {
			return left != nil
		}
		leftCandidate := 0
		rightCandidate := 0
		if left.IsSample || left.IsExtra {
			leftCandidate = 1
		}
		if right.IsSample || right.IsExtra {
			rightCandidate = 1
		}
		if leftCandidate != rightCandidate {
			return leftCandidate < rightCandidate
		}
		// An obfuscated set carries no ordering in its names, so every key below
		// falls through to size and then to the hash itself — which would sort
		// the volumes alphabetically and scramble the archive. NZB order is the
		// poster's own upload order; leave it alone.
		if left.IsObfuscated && right.IsObfuscated {
			return false
		}
		leftPriority := left.getLeadPriority()
		rightPriority := right.getLeadPriority()
		if leftPriority != rightPriority {
			return leftPriority < rightPriority
		}
		leftSequence := left.getSequence()
		rightSequence := right.getSequence()
		if leftSequence != rightSequence {
			return leftSequence < rightSequence
		}
		if left.Size != right.Size {
			return left.Size > right.Size
		}
		return strings.ToLower(left.Filename) < strings.ToLower(right.Filename)
	})
}

func (info *FileInfo) getLeadPriority() int {
	info.leadPriorityOnce.Do(func() {
		info.leadPriority = contentFileLeadPriority(info)
	})
	return info.leadPriority
}

func (info *FileInfo) getSequence() int {
	info.sequenceOnce.Do(func() {
		info.sequence = contentFileSequence(info)
	})
	return info.sequence
}

func contentFileLeadPriority(info *FileInfo) int {
	if info == nil {
		return 4
	}
	lower := strings.ToLower(info.Filename)
	switch {
	case fileutil.IsVideoFile(info.Filename):
		return 0
	case (strings.HasSuffix(lower, ".rar") && !strings.Contains(lower, ".part")) ||
		strings.Contains(lower, ".part01.") || strings.Contains(lower, ".part1.") ||
		strings.Contains(lower, ".part001."):
		return 0
	case strings.HasSuffix(lower, ".7z") || strings.Contains(lower, ".7z.001") || strings.Contains(lower, ".7z.0001"):
		return 0
	case strings.HasSuffix(lower, ".001"):
		return 0
	case isRarVolume(info.Extension) || isArchivePart(info.Extension) || isSplitArchivePart(info.Extension) || isRarSplitPart(info.Extension, info.Filename):
		return 1
	default:
		return 2
	}
}

func contentFileSequence(info *FileInfo) int {
	if info == nil {
		return int(^uint(0) >> 1)
	}
	lower := strings.ToLower(info.Filename)
	switch {
	case fileutil.IsVideoFile(info.Filename):
		return 0
	case (strings.HasSuffix(lower, ".rar") && !strings.Contains(lower, ".part")) ||
		strings.HasSuffix(lower, ".7z"):
		return 0
	}

	base := strings.TrimSuffix(lower, filepath.Ext(lower))
	if idx := strings.LastIndex(base, ".part"); idx != -1 {
		if seq, err := strconv.Atoi(base[idx+5:]); err == nil {
			return seq - 1
		}
	}
	if ext := filepath.Ext(lower); len(ext) == 4 && strings.HasPrefix(ext, ".r") {
		if seq, err := strconv.Atoi(ext[2:]); err == nil {
			return seq + 1
		}
	}
	if ext := filepath.Ext(lower); len(ext) > 1 {
		if seq, err := strconv.Atoi(ext[1:]); err == nil {
			return seq - 1
		}
	}
	if strings.Contains(lower, ".7z.") {
		if seq, err := strconv.Atoi(filepath.Ext(lower)[1:]); err == nil {
			return seq - 1
		}
	}

	return int(^uint(0) >> 1)
}

func logGetContentFilesEmpty(infos []*FileInfo, mainPattern string) {
	total := len(infos)
	samples := 0
	extras := 0
	subjects := make([]string, 0, 8)
	for _, info := range infos {
		if info.IsSample {
			samples++
		}
		if info.IsExtra {
			extras++
		}
		if len(subjects) < 8 {
			subjects = append(subjects, info.Filename)
		}
	}
	logger.Debug("GetContentFiles returned empty",
		"total_files", total,
		"samples", samples,
		"extras", extras,
		"main_pattern", mainPattern,
		"sample_filenames", subjects)
}

// getFilePattern returns the cached pattern for a file, computing it once.
func (info *FileInfo) getFilePattern() string {
	info.patternOnce.Do(func() {
		if info.IsObfuscated {
			info.pattern = obfuscatedPattern
			return
		}
		info.pattern = getFilePattern(info.Filename)
	})
	return info.pattern
}

func getFilePattern(filename string) string {

	s := strings.ToLower(filename)

	ext := filepath.Ext(s)
	s = strings.TrimSuffix(s, ext)

	if idx := strings.LastIndex(s, ".part"); idx != -1 {
		s = s[:idx]
	}
	if idx := strings.LastIndex(s, ".vol"); idx != -1 {
		s = s[:idx]
	}

	s = strings.TrimSuffix(s, ".7z")

	return strings.Trim(s, " .-_")
}

func (n *NZB) IsRARRelease() bool {
	return n.CompressionType() == "rar"
}

func (n *NZB) CompressionType() string {
	return n.CompressionTypeForEpisode(0, 0)
}

func (n *NZB) CompressionTypeForEpisode(season, episode int) string {
	contentFiles := n.GetContentFilesForEpisode(season, episode)
	return compressionTypeFromContentFiles(contentFiles)
}

func compressionTypeFromContentFiles(contentFiles []*FileInfo) string {
	if len(contentFiles) == 0 {
		return "direct"
	}

	for _, info := range contentFiles {
		if info.Extension == ".7z" || strings.Contains(strings.ToLower(info.Filename), ".7z.001") {
			return "7z"
		}
	}

	hasRarFiles := false
	for _, info := range contentFiles {
		ext := strings.ToLower(info.Extension)
		if ext == ".rar" || isRarVolume(ext) {
			hasRarFiles = true
			break
		}
	}

	if hasRarFiles {
		return "rar"
	}

	largest := largestContentFile(contentFiles)
	if largest == nil {
		return "direct"
	}
	ct, _ := compressionTypeFromFileWithReason(largest.Filename, largest.Extension)
	return ct
}

func compressionTypeFromFileWithReason(filename, ext string) (string, string) {
	ext = strings.ToLower(ext)
	filenameLower := strings.ToLower(filename)

	if ext == ".7z" || strings.Contains(filenameLower, ".7z.001") {
		return "7z", "ext=.7z or contains .7z.001"
	}
	if strings.HasSuffix(filenameLower, ".7z.001") || strings.HasSuffix(filenameLower, ".7z.0001") {
		return "7z", "suffix .7z.001/.7z.0001"
	}

	if ext == ".rar" {
		return "rar", "ext=.rar"
	}
	if isRarVolume(ext) {
		return "rar", "isRarVolume(ext)"
	}

	return "direct", ""
}

func isRarVolume(ext string) bool {
	if len(ext) < 4 || !strings.HasPrefix(ext, ".r") {
		return false
	}
	for _, c := range ext[2:] {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func isRarSplitPart(ext, filename string) bool {
	if len(ext) < 3 || ext[0] != '.' {
		return false
	}
	for _, c := range ext[1:] {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func (n *NZB) GetMainVideoFile() *FileInfo {
	files := n.GetContentFiles()
	if len(files) > 0 {
		return files[0]
	}
	return nil
}

func analyzeFile(file *File) *FileInfo {

	subject := file.Subject
	if subject == "" {
		subject = file.Poster
	}
	filename := fileutil.ExtractFilename(subject)

	var size int64
	for _, seg := range file.Segments {
		size += seg.Bytes
	}

	ext := strings.ToLower(filepath.Ext(filename))

	info := &FileInfo{
		File:      file,
		Filename:  filename,
		Extension: ext,
		Size:      size,
	}

	info.IsVideo = fileutil.IsVideoOrArchiveExtension(ext)
	info.IsSample = isSampleFile(filename)
	info.IsExtra = isExtraFile(filename, ext)

	return info
}

func isArchivePart(ext string) bool {
	if len(ext) == 4 && strings.HasPrefix(ext, ".r") {
		for _, c := range ext[2:] {
			if c < '0' || c > '9' {
				return false
			}
		}
		return true
	}
	return false
}

func isSplitArchivePart(ext string) bool {
	if len(ext) != 4 {
		return false
	}
	return ext[0] == '.' &&
		ext[1] >= '0' && ext[1] <= '9' &&
		ext[2] >= '0' && ext[2] <= '9' &&
		ext[3] >= '0' && ext[3] <= '9'
}

func isSampleFile(filename string) bool {
	lower := strings.ToLower(filename)
	return strings.Contains(lower, "sample") ||
		strings.Contains(lower, "preview")
}

// extraExts is a package-level lookup table so isExtraFile does not allocate
// a new map on every call (it runs once per file per GetFileInfo).
var extraExts = map[string]bool{
	".nfo": true, ".txt": true, ".srt": true, ".sub": true,
	".idx": true, ".ass": true, ".ssa": true, ".vtt": true,
	".jpg": true, ".png": true, ".gif": true,

	".par2": true,
}

func isExtraFile(filename string, ext string) bool {
	if extraExts[ext] {
		return true
	}

	lower := strings.ToLower(filename)
	return strings.Contains(lower, "proof") ||
		strings.Contains(lower, "cover")
}
