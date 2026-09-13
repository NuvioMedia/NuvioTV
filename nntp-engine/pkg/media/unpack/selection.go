package unpack

import (
	"fmt"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"streamnzb/pkg/core/logger"
	searchparser "streamnzb/pkg/search/parser"
)

type EpisodeTarget struct {
	Season          int
	Episode         int
	FileIndex       int    `json:"file_index,omitempty"`
	HasFileIndex    bool   `json:"has_file_index,omitempty"`
	FileMustInclude string `json:"file_must_include,omitempty"`
	// Absolute is the anime absolute episode number of the same episode
	// (0 when unknown). Files carrying it match even though their parsed
	// season/episode differ from the target.
	Absolute int
}

func (t EpisodeTarget) Valid() bool {
	return (t.Season > 0 && t.Episode > 0) || t.Absolute > 0
}

type namedEpisodeCandidate struct {
	Name  string
	Size  int64
	Index int
	Order int
}

func selectEpisodeCandidate(candidates []namedEpisodeCandidate, target EpisodeTarget) (namedEpisodeCandidate, bool) {
	if !target.Valid() {
		return namedEpisodeCandidate{}, false
	}
	var best namedEpisodeCandidate
	bestRank := 0
	found := false
	for _, candidate := range candidates {
		rank := episodeNameMatchRank(candidate.Name, target)
		if rank == 0 {
			continue
		}
		if !found || rank > bestRank ||
			(rank == bestRank && (candidate.Size > best.Size ||
				(candidate.Size == best.Size && candidate.Order < best.Order))) {
			best = candidate
			bestRank = rank
			found = true
		}
	}
	if found {
		logger.Debug("Unpack episode candidate selected",
			"target", target,
			"name", best.Name,
			"rank", bestRank,
			"size", best.Size,
			"order", best.Order,
			"candidates", len(candidates))
	} else {
		logger.Debug("Unpack episode candidate selection found no match",
			"target", target,
			"candidates", len(candidates))
	}
	return best, found
}

func selectEpisodeCandidateOrError(candidates []namedEpisodeCandidate, target EpisodeTarget, scope string) (namedEpisodeCandidate, bool, error) {
	if selected, explicit, err := selectExplicitCandidate(candidates, target); explicit || err != nil {
		return selected, explicit && err == nil, err
	}
	if best, ok := selectEpisodeCandidate(candidates, target); ok {
		return best, true, nil
	}
	if !target.Valid() || len(candidates) == 0 {
		return namedEpisodeCandidate{}, false, nil
	}
	if len(candidates) == 1 {
		baseName := filepath.Base(candidates[0].Name)
		// An obfuscated stem carries no episode information, but the parser
		// still digs numbers out of the hex (a stem ending "...361e72" reads as
		// episode 72). Trusting that turns the sole media file in the archive
		// into a mismatch and fails a release that does contain the episode, so
		// obfuscated names are treated as unparseable here regardless of what
		// came back.
		if looksObfuscatedFilename(baseName) {
			logger.Debug("Skipping episode title check for single-candidate release with obfuscated filename",
				"target", target,
				"scope", scope,
				"name", candidates[0].Name)
			return namedEpisodeCandidate{}, false, nil
		}
		parsed := searchparser.ParseReleaseTitle(baseName)
		if parsed == nil || (parsed.Season == 0 && parsed.Episode == 0 && len(parsed.Episodes) == 0) {
			logger.Debug("Skipping episode title check for single-candidate release with unparseable filename",
				"target", target,
				"scope", scope,
				"name", candidates[0].Name)
			return namedEpisodeCandidate{}, false, nil
		}
	}
	err := fmt.Errorf("%w: season=%d episode=%d scope=%s candidates=%d", ErrEpisodeTargetNotFound, target.Season, target.Episode, scope, len(candidates))
	logger.Warn("Requested episode not found in candidate set",
		"target", target,
		"scope", scope,
		"candidates", len(candidates),
		"err", err)
	return namedEpisodeCandidate{}, false, err
}

func selectExplicitCandidate(candidates []namedEpisodeCandidate, target EpisodeTarget) (namedEpisodeCandidate, bool, error) {
	pattern := strings.TrimSpace(target.FileMustInclude)
	if !target.HasFileIndex && pattern == "" {
		return namedEpisodeCandidate{}, false, nil
	}
	ordered := append([]namedEpisodeCandidate(nil), candidates...)
	sort.SliceStable(ordered, func(i, j int) bool { return ordered[i].Order < ordered[j].Order })

	var matcher *regexp.Regexp
	if pattern != "" {
		compiled, err := compileMediaPattern(pattern)
		if err != nil {
			return namedEpisodeCandidate{}, true, err
		}
		matcher = compiled
	}
	if target.HasFileIndex {
		for _, candidate := range ordered {
			if candidate.Index != target.FileIndex {
				continue
			}
			if matcher != nil && !matcher.MatchString(candidate.Name) {
				return namedEpisodeCandidate{}, true, fmt.Errorf("selected file does not match fileMustInclude")
			}
			return candidate, true, nil
		}
		return namedEpisodeCandidate{}, true, fmt.Errorf("media file index is out of range")
	}

	var best namedEpisodeCandidate
	found := false
	for _, candidate := range ordered {
		if !matcher.MatchString(candidate.Name) {
			continue
		}
		if !found || candidate.Size > best.Size ||
			(candidate.Size == best.Size && candidate.Order < best.Order) {
			best = candidate
			found = true
		}
	}
	if !found {
		return namedEpisodeCandidate{}, true, fmt.Errorf("no media file matches fileMustInclude")
	}
	return best, true, nil
}

func compileMediaPattern(value string) (*regexp.Regexp, error) {
	expression := strings.TrimSpace(value)
	if len(expression) > 512 {
		return nil, fmt.Errorf("fileMustInclude is too long")
	}
	if strings.HasPrefix(expression, "/") {
		if closing := strings.LastIndex(expression, "/"); closing > 0 {
			flags := expression[closing+1:]
			for _, flag := range flags {
				if flag != 'i' && flag != 'm' {
					return nil, fmt.Errorf("unsupported fileMustInclude flags")
				}
			}
			prefix := ""
			if strings.ContainsRune(flags, 'i') {
				prefix += "(?i)"
			}
			if strings.ContainsRune(flags, 'm') {
				prefix += "(?m)"
			}
			expression = prefix + expression[1:closing]
		}
	}
	matcher, err := regexp.Compile(expression)
	if err != nil {
		return nil, fmt.Errorf("invalid fileMustInclude pattern")
	}
	return matcher, nil
}

func episodeNameMatchRank(name string, target EpisodeTarget) int {
	if !target.Valid() {
		return 0
	}
	baseName := filepath.Base(name)
	parsed := searchparser.ParseReleaseTitle(baseName)
	if parsed == nil {
		logger.Debug("Unpack episode candidate parse returned nil",
			"target", target,
			"name", baseName)
		return 0
	}
	rank := parsed.EpisodeMatchRank(target.Season, target.Episode)
	if target.Absolute > 0 {
		// Absolute-numbered anime files carry no season (or season 1), which
		// is exactly the season<=0 match path.
		if absRank := parsed.EpisodeMatchRank(0, target.Absolute); absRank > rank {
			rank = absRank
		}
	}
	logger.Debug("Unpack episode candidate rank evaluated",
		"target", target,
		"name", baseName,
		"rank", rank,
		"parsed_season", parsed.Season,
		"parsed_episode", parsed.Episode,
		"parsed_seasons", parsed.Seasons,
		"parsed_episodes", parsed.Episodes,
		"complete", parsed.Complete,
		"episode_code", parsed.EpisodeCode)
	return rank
}

func selectDirectFileIndex(files []UnpackableFile, target EpisodeTarget) (int, error) {
	firstVideoIdx := -1
	firstVideoName := ""
	candidates := make([]namedEpisodeCandidate, 0, len(files))
	for i, f := range files {
		name := ExtractFilename(f.Name())
		if !IsVideoFile(name) {
			continue
		}
		if firstVideoIdx == -1 {
			firstVideoIdx = i
			firstVideoName = name
		}
		candidates = append(candidates, namedEpisodeCandidate{Name: name, Size: f.Size(), Index: i, Order: len(candidates)})
	}
	if best, ok, err := selectEpisodeCandidateOrError(candidates, target, "direct_media"); err != nil {
		return -1, err
	} else if ok {
		logger.Debug("Direct file selection matched requested episode",
			"target", target,
			"name", best.Name,
			"index", best.Index,
			"size", best.Size,
			"candidates", len(candidates))
		return best.Index, nil
	}
	if firstVideoIdx >= 0 {
		logger.Debug("Direct file selection fell back to first video",
			"target", target,
			"name", firstVideoName,
			"index", firstVideoIdx,
			"candidates", len(candidates))
	} else {
		logger.Debug("Direct file selection found no video candidates",
			"target", target,
			"files", len(files))
	}
	return firstVideoIdx, nil
}
