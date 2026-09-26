package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/rarname"
	"github.com/dlclark/regexp2/v2"
)

type Selection struct {
	FileIdx         *int   `json:"fileIdx,omitempty"`
	FileMustInclude string `json:"fileMustInclude,omitempty"`
	Season          int    `json:"season,omitempty"`
	Episode         int    `json:"episode,omitempty"`
}

var errNoMatchingVideo = errors.New("no video matches the addon's file/episode selector")

// A fallback is only considered after strict matching failed. Any remaining
// explicit SxxExx/NxNN marker therefore prevents guessing a different episode.
var episodeMarkerRE = regexp.MustCompile(`(?i)(?:s\d+[ ._-]*e\d+|(?:^|\D)\d+x\d+)`)

func (s Selection) episodeOnly() bool {
	return s.Episode > 0 && s.FileIdx == nil && s.FileMustInclude == ""
}

func selectionVideo(name string) bool {
	return isVideo(name) && !strings.Contains(strings.ToLower(name), "sample")
}

func (s Selection) matcher() (func(string, int) (bool, error), error) {
	var re *regexp2.Regexp
	if len(s.FileMustInclude) > 4096 {
		return nil, errors.New("file selection expression is too long")
	}
	if s.FileMustInclude != "" {
		pattern := s.FileMustInclude
		options := regexp2.ECMAScript
		if strings.HasPrefix(pattern, "/") {
			if j := strings.LastIndex(pattern, "/"); j > 0 {
				flags := pattern[j+1:]
				pattern = pattern[1:j]
				for _, flag := range flags {
					switch flag {
					case 'i':
						options |= regexp2.IgnoreCase
					case 'm':
						options |= regexp2.Multiline
					case 's':
						options |= regexp2.Singleline
					case 'u':
						options |= regexp2.Unicode
					case 'y':
						pattern = "^(?:" + pattern + ")"
					case 'g', 'd': // A stateless existence match needs neither iteration nor indices.
					default:
						return nil, errors.New("unsupported fileMustInclude flag")
					}
				}
			}
		}
		var err error
		re, err = regexp2.Compile(pattern, options, regexp2.OptionMaxBacktrackingStackSize(16384), regexp2.OptionMaxCachedRuneBufferLength(4096))
		if err != nil {
			return nil, errors.New("unsupported fileMustInclude expression")
		}
		re.MatchTimeout = 100 * time.Millisecond
	}
	var episodeRE *regexp.Regexp
	if s.Episode > 0 {
		episodeRE = regexp.MustCompile(fmt.Sprintf(`(?i)(?:s0*%d[ ._-]*e0*%d(?:\D|$)|(?:^|\D)0*%dx0*%d(?:\D|$))`, s.Season, s.Episode, s.Season, s.Episode))
	}
	var matchingTime time.Duration
	return func(name string, index int) (bool, error) {
		if s.FileIdx != nil {
			return index == *s.FileIdx, nil
		}
		if re != nil {
			if len(name) > 16384 || matchingTime > time.Second {
				return false, errors.New("fileMustInclude exceeds selection limits")
			}
			start := time.Now()
			matched, err := re.MatchString(name)
			matchingTime += time.Since(start)
			if err != nil {
				return false, errors.New("fileMustInclude exceeds selection limits")
			}
			return matched, nil
		}
		if episodeRE != nil {
			return episodeRE.MatchString(name), nil
		}
		return isVideo(name) && !strings.Contains(strings.ToLower(name), "sample"), nil
	}, nil
}

func sniff(ctx context.Context, f *File) ([]byte, error) {
	r := f.headerReader(ctx)
	defer r.Close()
	b := make([]byte, 16)
	if err := readFullAt(r, b, 0); err != nil {
		return nil, err
	}
	return b, nil
}

func Select(ctx context.Context, files []*File, s Selection) (*Content, error) {
	c, err := selectContent(ctx, files, s, false)
	if err == errNoMatchingVideo && s.episodeOnly() {
		// Only ambiguous episode names need a full inventory. Strict matches
		// keep the lazy RAR startup path, without probing continuation volumes.
		return selectContent(ctx, files, s, true)
	}
	return c, err
}

func selectContent(ctx context.Context, files []*File, s Selection, allowFallback bool) (*Content, error) {
	match, err := s.matcher()
	if err != nil {
		return nil, err
	}
	if s.FileIdx != nil && *s.FileIdx < 0 {
		return nil, errors.New("invalid file index")
	}
	var fallback *Content
	videoCount := 0
	consider := func(c *Content, index int, originalName string) (bool, error) {
		if !s.episodeOnly() {
			return match(c.Name, index)
		}
		if !selectionVideo(c.Name) || strings.Contains(strings.ToLower(originalName), "sample") {
			return false, nil
		}
		for _, name := range []string{c.Name, originalName} {
			matched, err := match(name, index)
			if err != nil || matched {
				return matched, err
			}
		}
		if allowFallback {
			videoCount++ // Conflicting videos also make a release ambiguous.
			if !episodeMarkerRE.MatchString(c.Name) && !episodeMarkerRE.MatchString(originalName) {
				fallback = c
			}
		}
		return false, nil
	}
	const anonymousRARKey = "\x00obfuscated" // Cannot collide with an NZB filename.
	groups := map[string][]*File{}
	var order []string
	var direct, unknown []*File
	for _, f := range files {
		if key, ok := rarname.SetKey(f.Name); ok {
			if _, seen := groups[key]; !seen {
				order = append(order, key)
			}
			groups[key] = append(groups[key], f)
		} else if isVideo(f.Name) {
			direct = append(direct, f)
		} else {
			l := strings.ToLower(f.Name)
			if !isSubtitle(l) && !strings.HasSuffix(l, ".par2") && !strings.HasSuffix(l, ".nfo") && !strings.HasSuffix(l, ".sfv") && !strings.HasSuffix(l, ".srr") && !strings.HasSuffix(l, ".txt") {
				unknown = append(unknown, f)
			}
		}
	}
	// Largest direct candidate first when the addon supplies no file selector.
	// This uses NZB metadata and does not probe other candidates for sizing.
	if s.FileIdx == nil && s.FileMustInclude == "" && s.Episode == 0 {
		sort.SliceStable(direct, func(i, j int) bool { return direct[i].Size() > direct[j].Size() })
	}
	if s.episodeOnly() {
		// Prefer an explicit subject match before recovering other filenames.
		// A named season pack must not load every episode's cached segment list
		// or fetch its first article just to select an already identified file.
		for _, f := range direct {
			if !selectionVideo(f.Name) {
				continue
			}
			matched, err := match(f.Name, f.Index)
			if err != nil {
				return nil, err
			}
			if matched {
				if _, err := sniff(ctx, f); err != nil {
					return nil, err
				}
				return &Content{Name: f.Name, Size: f.Size(), direct: f}, nil
			}
		}
	}
	for _, f := range direct {
		name := f.Name
		if s.episodeOnly() {
			// yEnc may reveal a clean episode name behind an obfuscated subject.
			if _, err := sniff(ctx, f); err != nil {
				return nil, err
			}
			f.mu.RLock()
			if isVideo(f.recoveredName) {
				name = f.recoveredName
			}
			f.mu.RUnlock()
		}
		c := &Content{Name: name, Size: f.Size(), direct: f}
		matched, err := consider(c, f.Index, f.Name)
		if err != nil {
			return nil, err
		}
		if matched {
			// This also establishes the decoded yEnc size. NZB wire counts are
			// not valid Content-Length/Range sizes, even for a named video.
			if !s.episodeOnly() {
				if _, err := sniff(ctx, f); err != nil {
					return nil, err
				}
			}
			c.Size = f.Size()
			return c, nil
		}
	}
	if (len(groups) == 0 || allowFallback) && len(unknown) > 0 {
		ordered := true
		for _, f := range unknown {
			if f.order <= 0 {
				ordered = false
				break
			}
		}
		if ordered {
			sort.SliceStable(unknown, func(i, j int) bool { return unknown[i].order < unknown[j].order })
		}
		// Preserve the NZB's release/volume order for extensionless obfuscated
		// sets, as AltMount's original-index normalization does.
		for i, f := range unknown {
			head, e := sniff(ctx, f)
			if e != nil {
				return nil, e
			}
			if bytes.HasPrefix(head, []byte("Rar!\x1a\x07")) {
				if allowFallback {
					// Inventory every anonymous entry, including direct videos
					// interleaved with archive volumes, before accepting a fallback.
					if len(groups[anonymousRARKey]) == 0 {
						order = append(order, anonymousRARKey)
					}
					groups[anonymousRARKey] = append(groups[anonymousRARKey], f)
					continue
				}
				groups[anonymousRARKey] = unknown[i:]
				order = append(order, anonymousRARKey)
				break
			}
			f.mu.RLock()
			recovered := f.recoveredName
			f.mu.RUnlock()
			video := isVideo(recovered) || bytes.HasPrefix(head, []byte{0x1a, 0x45, 0xdf, 0xa3}) || (len(head) >= 8 && string(head[4:8]) == "ftyp") || bytes.HasPrefix(head, []byte("RIFF"))
			if video && (s.FileIdx == nil || *s.FileIdx == f.Index) {
				name := f.Name
				if isVideo(recovered) {
					name = recovered
				}
				if !isVideo(name) {
					name += ".mkv"
				}
				c := &Content{Name: name, Size: f.Size(), direct: f}
				if s.FileMustInclude != "" || s.Episode > 0 {
					matched, err := consider(c, f.Index, f.Name)
					if err != nil {
						return nil, err
					}
					if !matched {
						continue
					}
				}
				return c, nil
			}
		}
	}
	index := 0
	for _, key := range order {
		vols := groups[key]
		if key != anonymousRARKey {
			sort.SliceStable(vols, func(i, j int) bool {
				_, a, _ := rarname.VolumeNumber(vols[i].Name)
				_, b, _ := rarname.VolumeNumber(vols[j].Name)
				return a < b
			})
			for j, f := range vols {
				scheme, n, ok := rarname.VolumeNumber(f.Name)
				first := 1
				if scheme == rarname.SchemeRoll {
					first = 0
				}
				if !ok || n != j+first {
					return nil, errors.New("RAR volume sequence is incomplete")
				}
			}
		}
		cursor := &rarCursor{files: vols, unordered: key == anonymousRARKey}
		for {
			b, f, e := cursor.next(ctx)
			if e == io.EOF {
				break
			}
			if e != nil {
				return nil, e
			}
			if b.before {
				return nil, errors.New("RAR starts with a missing continuation")
			}
			if b.directory {
				continue
			}
			c := &Content{Name: b.name, Size: b.unpacked, cursor: cursor, complete: !b.after, parts: []extent{{f, b.data, b.packed, 0}}}
			if c.Size < 0 || b.packed > c.Size || (c.complete && b.packed != c.Size) {
				return nil, errors.New("invalid stored RAR file size")
			}
			matched, err := consider(c, index, "")
			if err != nil {
				return nil, err
			}
			if matched {
				return c, nil
			}
			index++
			// Reach the next entry using headers only. Selected content returns
			// above, before resolving ANY continuation volume.
			if err := c.extend(ctx, -1); err != nil {
				return nil, err
			}
		}
	}
	if allowFallback && videoCount == 1 && fallback != nil {
		return fallback, nil
	}
	return nil, errNoMatchingVideo
}
