package engine

// A Blu-ray season posted file by file without its folders: every disc's BDMV
// files carry only their base names (00045.m2ts, 00001.mpls, 00045.clpi), the
// discs one after another, each in the order its paths sort. A disc's clips (its
// STREAM run of .m2ts) come right after its playlists (its PLAYLIST run of
// .mpls), with its CLPI run (CLIPINF) before those; its backup copies come
// earlier still and are not read. Nothing names an episode, so an episode is
// found the way a player would: from the discs' playlists.

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strings"
	"sync"
)

var looseDiscFileRE = regexp.MustCompile(`(?i)^(\d{5})\.(m2ts|mpls|clpi)$`)

var errDiscEpisodeMissing = errors.New("the Blu-ray discs hold no such episode")

// A playlist shorter than this is a menu, a trailer or an extra, never an episode.
const minEpisodeSeconds = 10 * 60

type looseDisc struct {
	number    int
	playlists []*File          // its PLAYLIST run, in posting order
	clips     map[string]*File // by clip number
	clipInfo  map[string]*File
}

// looseDiscs groups a season of discs posted without folders, in posting order.
// Nil when the files are not such a set.
func looseDiscs(files []*File) []*looseDisc {
	ordered := append([]*File(nil), files...)
	byOrder := true
	for _, f := range ordered {
		if f.order <= 0 {
			byOrder = false
			break
		}
	}
	sort.SliceStable(ordered, func(i, j int) bool {
		if byOrder {
			return ordered[i].order < ordered[j].order
		}
		return ordered[i].Index < ordered[j].Index
	})
	type run struct {
		ext   string
		files []*File
	}
	var runs []run
	for _, f := range ordered {
		ext := ""
		if m := looseDiscFileRE.FindStringSubmatch(f.Name); m != nil {
			ext = strings.ToLower(m[2])
		}
		if n := len(runs); n > 0 && ext != "" && runs[n-1].ext == ext {
			runs[n-1].files = append(runs[n-1].files, f)
		} else {
			runs = append(runs, run{ext, []*File{f}})
		}
	}
	var discs []*looseDisc
	for i, r := range runs {
		if r.ext != "m2ts" {
			continue
		}
		d := &looseDisc{number: len(discs) + 1, clips: map[string]*File{}, clipInfo: map[string]*File{}}
		for _, f := range r.files {
			clip := f.Name[:5]
			if d.clips[clip] != nil {
				return nil // A clip twice on one disc: not a disc's STREAM folder.
			}
			d.clips[clip] = f
		}
		// The nearest PLAYLIST and CLIPINF runs before it, after the previous disc's clips.
		for j := i - 1; j >= 0 && runs[j].ext != "m2ts"; j-- {
			switch {
			case runs[j].ext == "mpls" && d.playlists == nil:
				d.playlists = runs[j].files
			case runs[j].ext == "clpi" && len(d.clipInfo) == 0:
				for _, f := range runs[j].files {
					d.clipInfo[f.Name[:5]] = f
				}
			}
		}
		if d.playlists == nil {
			return nil
		}
		discs = append(discs, d)
	}
	return discs
}

// mplsItem is one PlayItem of a playlist: a clip and its span, in 45 kHz ticks.
type mplsItem struct {
	clip    string
	in, out uint32
}

func (i mplsItem) seconds() float64 { return float64(i.out-i.in) / 45000 }

// parseMPLS reads a Blu-ray playlist's PlayItems.
func parseMPLS(b []byte) ([]mplsItem, error) {
	invalid := errors.New("invalid Blu-ray playlist")
	if len(b) < 20 || string(b[:4]) != "MPLS" {
		return nil, invalid
	}
	start := int64(binary.BigEndian.Uint32(b[8:12]))
	if start < 20 || start+10 > int64(len(b)) {
		return nil, invalid
	}
	p := b[start:]
	count := int(binary.BigEndian.Uint16(p[6:8]))
	items := make([]mplsItem, 0, count)
	off := 10
	for range count {
		if off+2 > len(p) {
			return nil, invalid
		}
		length := int(binary.BigEndian.Uint16(p[off : off+2]))
		if length < 20 || off+2+length > len(p) {
			return nil, invalid
		}
		item := p[off+2 : off+2+length]
		in, out := binary.BigEndian.Uint32(item[12:16]), binary.BigEndian.Uint32(item[16:20])
		if codec := string(item[5:9]); out <= in || (codec != "M2TS" && codec != "FMTS") {
			return nil, invalid
		}
		items = append(items, mplsItem{clip: string(item[:5]), in: in, out: out})
		off += 2 + length
	}
	return items, nil
}

// discEpisode is an episode found on a disc: the clip that carries it.
type discEpisode struct {
	disc    *looseDisc
	clip    string
	seconds float64
}

// discEpisodes lists a disc's episodes in play order, from its playlists. An
// episode playlist plays one main clip for most of its length; several such
// playlists with one clip (other audio or menus) are one episode; a playlist
// that chains other episodes is the disc's "play all", which orders them.
func discEpisodes(d *looseDisc, playlists map[*File][]mplsItem) []discEpisode {
	type candidate struct {
		name    string
		main    mplsItem
		seconds float64
		items   []mplsItem
	}
	var candidates []candidate
	for _, f := range d.playlists {
		items := playlists[f]
		if len(items) == 0 {
			continue
		}
		var total float64
		main := items[0]
		known := true
		for _, it := range items {
			total += it.seconds()
			if it.seconds() > main.seconds() {
				main = it
			}
			if d.clips[it.clip] == nil {
				known = false
			}
		}
		if !known || main.seconds() < minEpisodeSeconds {
			continue
		}
		candidates = append(candidates, candidate{f.Name, main, total, items})
	}
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].name < candidates[j].name })
	// A playlist whose clips hold two or more other candidates' main clips plays them all.
	mains := map[string]bool{}
	for _, c := range candidates {
		mains[c.main.clip] = false
	}
	var playAll []mplsItem
	single := candidates[:0]
	for _, c := range candidates {
		others := 0
		for _, it := range c.items {
			if _, isMain := mains[it.clip]; isMain && it.clip != c.main.clip {
				others++
			}
		}
		if others > 0 {
			if len(c.items) > len(playAll) {
				playAll = c.items
			}
			continue
		}
		single = append(single, c)
	}
	var episodes []discEpisode
	seen := map[string]bool{}
	add := func(clip string, seconds float64) {
		if !seen[clip] {
			seen[clip] = true
			episodes = append(episodes, discEpisode{d, clip, seconds})
		}
	}
	byClip := map[string]float64{}
	for _, c := range single {
		if _, ok := byClip[c.main.clip]; !ok {
			byClip[c.main.clip] = c.seconds
		}
	}
	for _, it := range playAll {
		if seconds, ok := byClip[it.clip]; ok {
			add(it.clip, seconds)
		}
	}
	for _, c := range single {
		add(c.main.clip, byClip[c.main.clip])
	}
	return episodes
}

// looseDiscEpisode serves the requested episode of a season of loose discs: the
// discs' episodes in disc order, the n-th of them. Episodes much shorter than
// the others are extras. The discs are taken to hold one season.
func looseDiscEpisode(ctx context.Context, discs []*looseDisc, s Selection) (*Content, error) {
	playlists, err := readLoosePlaylists(ctx, discs)
	if err != nil {
		return nil, err
	}
	var episodes []discEpisode
	for _, d := range discs {
		episodes = append(episodes, discEpisodes(d, playlists)...)
	}
	if len(episodes) > 0 {
		lengths := make([]float64, len(episodes))
		for i, e := range episodes {
			lengths[i] = e.seconds
		}
		sort.Float64s(lengths)
		median := lengths[len(lengths)/2]
		kept := episodes[:0]
		for _, e := range episodes {
			if e.seconds >= median*0.6 {
				kept = append(kept, e)
			}
		}
		episodes = kept
	}
	if s.Episode < 1 || s.Episode > len(episodes) {
		return nil, fmt.Errorf("%w: episode %d of %d", errDiscEpisodeMissing, s.Episode, len(episodes))
	}
	e := episodes[s.Episode-1]
	clip := e.disc.clips[e.clip]
	root := fmt.Sprintf("Disc %d/BDMV/", e.disc.number)
	if _, err := sniff(ctx, clip); err != nil {
		return nil, err
	}
	c := &Content{Name: root + "STREAM/" + e.clip + ".m2ts", Size: clip.Size(), direct: clip}
	if info := e.disc.clipInfo[e.clip]; info != nil {
		if _, err := sniff(ctx, info); err == nil && info.Size() <= maxClipInfoBytes {
			c.clipInfo = &Content{Name: root + "CLIPINF/" + e.clip + ".clpi", Size: info.Size(), direct: info}
		}
	}
	return c, nil
}

// readLoosePlaylists reads every disc's playlists, disc by disc and a few at
// once: each is one small article, and a season has dozens. One that cannot be
// read is skipped, but a disc none of whose playlists can be read fails the
// whole set with its first error: a release whose articles were removed fails
// as such, at its first disc, instead of after every disc.
func readLoosePlaylists(ctx context.Context, discs []*looseDisc) (map[*File][]mplsItem, error) {
	result := map[*File][]mplsItem{}
	for _, d := range discs {
		var mu sync.Mutex
		var first error
		read := 0
		work := make(chan *File)
		var wg sync.WaitGroup
		for range min(8, len(d.playlists)) {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for f := range work {
					items, err := readPlaylist(ctx, f)
					mu.Lock()
					if err != nil {
						if first == nil {
							first = err
						}
					} else {
						result[f] = items
						read++
					}
					mu.Unlock()
				}
			}()
		}
	send:
		for _, f := range d.playlists {
			select {
			case work <- f:
			case <-ctx.Done():
				break send
			}
		}
		close(work)
		wg.Wait()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if read == 0 && first != nil {
			return nil, fmt.Errorf("reading the playlists of Blu-ray disc %d: %w", d.number, first)
		}
	}
	return result, nil
}

func readPlaylist(ctx context.Context, f *File) ([]mplsItem, error) {
	if _, err := sniff(ctx, f); err != nil {
		return nil, err
	}
	if f.Size() > maxClipInfoBytes {
		return nil, errors.New("Blu-ray playlist too large")
	}
	r := (&Content{Name: f.Name, Size: f.Size(), direct: f}).Reader(ctx, 0)
	defer r.Close()
	b, err := io.ReadAll(io.LimitReader(r, maxClipInfoBytes+1))
	if err != nil {
		return nil, err
	}
	return parseMPLS(b)
}
