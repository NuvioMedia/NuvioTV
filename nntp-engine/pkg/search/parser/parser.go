package parser

import (
	"regexp"
	"strconv"
	"strings"

	"streamnzb/pkg/core/config/pttoptions"

	"github.com/dreulavelle/jhin"
	jhinparser "github.com/dreulavelle/jhin/parser"
)

var dashedSeasonEpisodePattern = regexp.MustCompile(`(?i)\bS(?:eason)?\s*0*([0-9]{1,2})\s*-\s*0*([0-9]{1,3})(?:$|[\s._()[\]])`)

// languageAliasPattern matches release-title alias words (e.g. "NORDIC") as whole words,
// case-insensitively, across common release-name separators (space, dot, underscore, dash).
var languageAliasPattern = func() *regexp.Regexp {
	words := pttoptions.LanguageAliasWords()
	parts := make([]string, 0, len(words))
	for _, w := range words {
		parts = append(parts, regexp.QuoteMeta(w))
	}
	// \b doesn't always play well with dots/underscores, so we use explicit boundaries.
	pattern := `(?i)(?:^|[\s._\-(\[])(` + strings.Join(parts, "|") + `)(?:$|[\s._\-)\]])`
	return regexp.MustCompile(pattern)
}()

// ParsedRelease exposes jhin's full parse output through the embedded
// jhin.Result, so every attribute jhin extracts is available to filtering,
// ranking and stream descriptions without this type having to mirror it.
//
// The fields declared here deliberately shadow their jhin counterparts because
// StreamNZB needs them in a different shape: Year as an int, Codec normalized
// to StreamNZB's vocabulary, and Seasons/Episodes deduplicated. Season and
// Episode are conveniences for the common single-value case. Read through
// ParsedRelease.Result when the raw jhin value is what you want.
type ParsedRelease struct {
	*jhin.Result

	Year     int
	Codec    string
	Seasons  []int
	Episodes []int

	Season  int
	Episode int
}

// ParseReleaseTitle parses a release title using the full jhin parser.
func ParseReleaseTitle(title string) *ParsedRelease {
	return ParseReleaseTitleWithParser(title, jhin.Parse)
}

// ParseReleaseTitleWithParser parses a release title using the supplied jhin
// parser function. This allows callers to pass a partial parser (e.g. one
// that only extracts seasons/episodes) to avoid the cost of the full
// handler set when only a subset of fields are needed.
func ParseReleaseTitleWithParser(title string, parse func(string) *jhin.Result) *ParsedRelease {
	if parse == nil {
		parse = jhin.Parse
	}
	return FromResult(title, parse(title))
}

// FromResult derives a ParsedRelease from a parse the caller already has.
// Ranking parses every release it evaluates, so building candidates from its
// result avoids parsing each title a second time.
func FromResult(title string, info *jhin.Result) *ParsedRelease {
	if info == nil {
		info = &jhin.Result{}
	}

	codec := pttoptions.NormalizeCodec(info.Codec)
	if codec == "" && info.Codec != "" {
		codec = info.Codec
	}

	parsed := &ParsedRelease{
		Result:   info,
		Codec:    codec,
		Seasons:  uniqueInts(info.Seasons),
		Episodes: uniqueInts(info.Episodes),
	}

	if info.Year != "" {
		if year, err := strconv.Atoi(info.Year); err == nil {
			parsed.Year = year
		}
	}
	if len(parsed.Seasons) > 0 {
		parsed.Season = parsed.Seasons[0]
	}
	if len(parsed.Episodes) > 0 {
		parsed.Episode = parsed.Episodes[0]
	}
	applyDashedSeasonEpisodeFallback(title, parsed)
	expandLanguageAliases(title, parsed)

	return parsed
}

// expandLanguageAliases scans the raw release title for group/region alias words
// (e.g. "NORDIC") and merges the corresponding language codes into parsed.Languages.
// jhin already extracts explicit language codes, so we only add codes that are missing.
func expandLanguageAliases(rawTitle string, parsed *ParsedRelease) {
	if parsed == nil {
		return
	}
	matches := languageAliasPattern.FindAllStringSubmatch(rawTitle, -1)
	if len(matches) == 0 {
		return
	}
	seen := make(map[string]bool, len(parsed.Languages))
	for _, lang := range parsed.Languages {
		seen[strings.ToLower(strings.TrimSpace(lang))] = true
	}
	for _, m := range matches {
		if len(m) < 2 {
			continue
		}
		alias := strings.ToLower(strings.TrimSpace(m[1]))
		codes, ok := pttoptions.LanguageAliases[alias]
		if !ok {
			continue
		}
		for _, code := range codes {
			key := strings.ToLower(code)
			if !seen[key] {
				seen[key] = true
				parsed.Languages = append(parsed.Languages, code)
			}
		}
	}
}

func applyDashedSeasonEpisodeFallback(rawTitle string, parsed *ParsedRelease) {
	if parsed == nil {
		return
	}
	matches := dashedSeasonEpisodePattern.FindStringSubmatch(rawTitle)
	if len(matches) != 3 {
		return
	}
	season, seasonErr := strconv.Atoi(matches[1])
	episode, episodeErr := strconv.Atoi(matches[2])
	if seasonErr != nil || episodeErr != nil || season <= 0 || episode <= 0 {
		return
	}
	if parsed.Season == 0 {
		if !hasInt(parsed.Seasons, season) {
			parsed.Seasons = append(parsed.Seasons, season)
		}
		parsed.Season = season
	}
	if parsed.Episode == 0 {
		if !hasInt(parsed.Episodes, episode) {
			parsed.Episodes = append(parsed.Episodes, episode)
		}
		parsed.Episode = episode
	}
	if parsed.Title != "" {
		cleanedTitle := strings.TrimSpace(dashedSeasonEpisodePattern.ReplaceAllString(parsed.Title, " "))
		cleanedTitle = strings.Join(strings.Fields(cleanedTitle), " ")
		if cleanedTitle != "" {
			parsed.Title = cleanedTitle
		}
	}
}

func uniqueInts(values []int) []int {
	if len(values) == 0 {
		return nil
	}
	seen := make(map[int]struct{}, len(values))
	out := make([]int, 0, len(values))
	for _, v := range values {
		if _, ok := seen[v]; ok {
			continue
		}
		seen[v] = struct{}{}
		out = append(out, v)
	}
	return out
}

func hasInt(values []int, want int) bool {
	for _, v := range values {
		if v == want {
			return true
		}
	}
	return false
}

func (p *ParsedRelease) HasSeason(season int) bool {
	if p == nil || season <= 0 {
		return false
	}
	if hasInt(p.Seasons, season) {
		return true
	}
	return p.Season == season
}

func (p *ParsedRelease) HasEpisode(episode int) bool {
	if p == nil || episode <= 0 {
		return false
	}
	if hasInt(p.Episodes, episode) {
		return true
	}
	return p.Episode == episode
}

func (p *ParsedRelease) IsEpisodeRelease(season, episode int) bool {
	if p == nil || season <= 0 || episode <= 0 {
		return false
	}
	return p.HasSeason(season) && p.HasEpisode(episode) && len(p.Episodes) <= 1
}

func (p *ParsedRelease) IsMultiEpisodeRelease(season, episode int) bool {
	if p == nil || season <= 0 || episode <= 0 {
		return false
	}
	return p.HasSeason(season) && p.HasEpisode(episode) && len(p.Episodes) > 1
}

func (p *ParsedRelease) IsSeasonPack(season int) bool {
	if p == nil || season <= 0 {
		return false
	}
	if !p.HasSeason(season) || len(p.Episodes) > 0 {
		return false
	}
	return !p.IsShowPack()
}

func (p *ParsedRelease) IsShowPack() bool {
	if p == nil || !p.Complete || len(p.Episodes) > 0 {
		return false
	}
	return len(p.Seasons) == 0 || len(p.Seasons) > 1
}

func (p *ParsedRelease) EpisodeMatchRank(season, episode int) int {
	if p == nil || episode <= 0 {
		return 0
	}
	if season <= 0 {
		if p.HasEpisode(episode) {
			if len(p.Seasons) > 0 && !p.HasSeason(1) {
				return 0
			}
			if len(p.Episodes) <= 1 {
				return 4
			}
			return 3
		}
		if p.IsShowPack() {
			return 1
		}
		return 0
	}
	if p.IsEpisodeRelease(season, episode) {
		return 4
	}
	if p.IsMultiEpisodeRelease(season, episode) {
		return 3
	}
	if p.IsSeasonPack(season) {
		return 2
	}
	if p.IsShowPack() {
		return 1
	}
	return 0
}

func (p *ParsedRelease) MatchesEpisodeRequest(season, episode int) bool {
	return p.EpisodeMatchRank(season, episode) > 0
}

func (p *ParsedRelease) ResolutionGroup() string {
	if p == nil {
		return "sd"
	}
	res := jhinparser.ResolutionHeight(p.Resolution)
	if strings.Contains(res, "2160") || strings.Contains(res, "4k") {
		return "4k"
	}
	if strings.Contains(res, "1080") {
		return "1080p"
	}
	if strings.Contains(res, "720") {
		return "720p"
	}
	return "sd"
}

// EffectiveEpisodeSize is the size a size bound or a size score should judge:
// the whole release for films and single episodes, the per-episode share for a
// multi-episode release, and nothing at all for a season pack whose episode
// count the title does not reveal — judging a 10-episode pack as one 40 GB
// file would reject it for being large when it is not.
//
// It lives here rather than in ranking because the rule engine has to reach the
// same number: a size rule that disagreed with the size limit about what a
// season pack weighs would be a trap.
func EffectiveEpisodeSize(size int64, episodic bool, parsed *jhin.Result) (int64, bool) {
	if size <= 0 {
		return 0, false
	}
	if !episodic || parsed == nil {
		return size, true
	}
	if n := len(parsed.Episodes); n > 1 {
		return size / int64(n), true
	}
	if len(parsed.Episodes) == 0 && (parsed.Complete || len(parsed.Seasons) > 0) {
		return 0, false
	}
	return size, true
}
