package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strconv"
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
	multiEpisode    bool
}

var errNoMatchingVideo = errors.New("no video matches the addon's file/episode selector")

// A fallback is only considered after strict matching failed. Any remaining
// explicit SxxExx/NxNN marker therefore prevents guessing a different episode.
var episodeMarkerRE = regexp.MustCompile(`(?i)(?:s\d+[ ._-]*e\d+|(?:^|\D)\d+x\d+)`)

var multiEpisodeREs = []*regexp.Regexp{
	regexp.MustCompile(`(?i)s0*(\d{1,4})[ ._-]*e0*(\d{1,4})`),
	regexp.MustCompile(`(?i)(?:^|\D)0*(\d{1,4})x0*(\d{1,4})`),
}

func multiEpisodeMatch(name string, season, episode int) bool {
	digit := func(b byte) bool { return b >= '0' && b <= '9' }
	letter := func(b byte) bool { return b|0x20 >= 'a' && b|0x20 <= 'z' && b|0x20 != 'e' && b|0x20 != 'v' }
	name = name[strings.LastIndexAny(name, "/\\")+1:]
	for _, re := range multiEpisodeREs {
		for _, m := range re.FindAllStringSubmatchIndex(name, -1) {
			if n, _ := strconv.Atoi(name[m[2]:m[3]]); n != season {
				continue
			}
			rest := name[m[5]:]
			if rest != "" && digit(rest[0]) {
				continue
			}
			previous, _ := strconv.Atoi(name[m[4]:m[5]])
			for {
				i := 0
				for i < len(rest) && strings.IndexByte(" ._-", rest[i]) >= 0 {
					i++
				}
				sep, j := rest[:i], i
				marked := j < len(rest) && rest[j]|0x20 == 'e'
				if marked {
					j++
				}
				k := j
				for k < len(rest) && k-j < 4 && digit(rest[k]) {
					k++
				}
				if k == j || (k < len(rest) && (digit(rest[k]) || letter(rest[k]))) {
					break
				}
				ranged := strings.Contains(sep, "-")
				if !marked && sep != "-" {
					break
				}
				next, _ := strconv.Atoi(rest[j:k])
				if next == episode || ranged && previous < episode && episode < next && next-previous <= 30 {
					return true
				}
				previous, rest = next, rest[k:]
			}
		}
	}
	return false
}

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
			return episodeRE.MatchString(name) || s.multiEpisode && multiEpisodeMatch(name, s.Season, s.Episode), nil
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
		s.multiEpisode = true
		if c, err = selectContent(ctx, files, s, false); err != errNoMatchingVideo {
			return c, err
		}
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
		if f.damaged != nil {
			matched, err := consider(&Content{Name: f.Name, Size: f.Size(), direct: f}, f.Index, f.Name)
			if err != nil {
				return nil, err
			}
			if matched {
				return nil, f.damaged
			}
			continue
		}
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
	// For an explicitly indexed standalone video, inspect that file before
	// unrelated obfuscated entries. Do not reorder archive volumes: their file
	// indexes refer to entries inside the archive, not NZB volume indexes.
	if s.FileIdx != nil {
		for _, f := range unknown {
			if f.Index != *s.FileIdx {
				continue
			}
			head, err := sniff(ctx, f)
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if err == nil && !bytes.HasPrefix(head, []byte("Rar!\x1a\x07")) {
				if name, video := recoveredVideo(f, head); video {
					return &Content{Name: name, Size: f.Size(), direct: f}, nil
				}
			}
		}
	}
	skipBroken := s.FileIdx == nil && (s.FileMustInclude != "" || s.Episode > 0)
	var brokenErr error
	broken := func(err error) error {
		if !skipBroken || ctx.Err() != nil {
			return err
		}
		if brokenErr == nil {
			brokenErr = err
		}
		return nil
	}
	index := 0
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
			if is7zHead(head) {
				// A named .7z stands alone; the volumes of an extensionless
				// obfuscated set follow its first one in release order.
				named := strings.HasSuffix(strings.ToLower(f.Name), ".7z")
				vols := unknown[i:]
				if named {
					vols = vols[:1]
				}
				c, err := select7z(ctx, vols, s, &index, consider)
				if err == nil && c != nil {
					return c, nil
				}
				if err != nil {
					if err := broken(err); err != nil {
						return nil, err
					}
				}
				if named {
					continue
				}
				break
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
groups:
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
					if err := broken(errors.New("RAR volume sequence is incomplete")); err != nil {
						return nil, err
					}
					continue groups
				}
			}
		}
		if scheme, _, _ := rarname.VolumeNumber(vols[0].Name); key != anonymousRARKey && scheme == rarname.SchemeNumeric {
			// .001/.002 and .7z.001 sets are often 7z rather than RAR.
			head, err := sniff(ctx, vols[0])
			if err == nil && is7zHead(head) {
				var c *Content
				if c, err = select7z(ctx, vols, s, &index, consider); err == nil && c != nil {
					for _, v := range vols {
						if v.damaged != nil {
							return nil, v.damaged
						}
					}
					return c, nil
				}
			}
			if err != nil {
				if err := broken(err); err != nil {
					return nil, err
				}
			}
			if err != nil || is7zHead(head) {
				continue groups
			}
		}
		cursor := &rarCursor{files: vols, unordered: key == anonymousRARKey}
		for {
			b, f, e := cursor.next(ctx)
			if e == io.EOF {
				break
			}
			if e != nil {
				if err := broken(e); err != nil {
					return nil, err
				}
				continue groups
			}
			if b.before {
				if err := broken(errors.New("RAR starts with a missing continuation")); err != nil {
					return nil, err
				}
				continue groups
			}
			if b.directory {
				continue
			}
			c := &Content{Name: b.name, Size: b.unpacked, cursor: cursor, complete: !b.after, parts: []extent{{f, b.data, b.packed, 0}}}
			if b.crypt != nil {
				// AES data: derive the key only if this entry is selected.
				crypt, password := b.crypt, f.password
				c.padded = true
				c.prepare = func(ctx context.Context) (err error) {
					c.aes, err = rarDataCipher(ctx, crypt, password)
					return err
				}
			}
			if c.Size < 0 || b.packed > c.payloadSize() || (c.complete && b.packed != c.payloadSize()) {
				if err := broken(errors.New("invalid stored RAR file size")); err != nil {
					return nil, err
				}
				continue groups
			}
			matched, err := consider(c, index, "")
			if err != nil {
				return nil, err
			}
			if matched {
				for _, v := range vols {
					if v.damaged != nil {
						return nil, v.damaged
					}
				}
				return c.usable(ctx)
			}
			index++
			// Reach the next entry using headers only. Selected content returns
			// above, before resolving ANY continuation volume.
			if err := c.extend(ctx, -1); err != nil {
				if err := broken(err); err != nil {
					return nil, err
				}
				continue groups
			}
		}
	}
	if brokenErr != nil {
		return nil, brokenErr
	}
	if allowFallback && videoCount == 1 && fallback != nil {
		if fallback.direct != nil && fallback.direct.damaged != nil {
			return nil, fallback.direct.damaged
		}
		return fallback.usable(ctx)
	}
	return nil, errNoMatchingVideo
}

func recoveredVideo(f *File, head []byte) (string, bool) {
	f.mu.RLock()
	name := f.recoveredName
	f.mu.RUnlock()
	video := isVideo(name) || bytes.HasPrefix(head, []byte{0x1a, 0x45, 0xdf, 0xa3}) ||
		(len(head) >= 8 && string(head[4:8]) == "ftyp") || bytes.HasPrefix(head, []byte("RIFF"))
	if !isVideo(name) {
		name = f.Name
		if !isVideo(name) {
			name += ".mkv"
		}
	}
	return name, video
}
