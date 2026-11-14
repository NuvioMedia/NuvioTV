package engine

// A Blu-ray release's other files, served beside its selected clip under the
// same disc root: the playlists (BDMV/PLAYLIST), the clips they name
// (BDMV/STREAM) and those clips' CLPI. A player that finds the feature's
// playlist there plays it clip by clip, which a disc with a feature split
// over several clips needs.
//
// rar lists BDMV/CLIPINF and BDMV/PLAYLIST before BDMV/STREAM, so selection
// passes them, and the clips before the feature, on its way. The clips after
// the feature are found by a walk of the rest of the set, which runs in the
// background once the session opens, two headers per clip. A disc image lists
// every file in its directories.

import (
	"context"
	"errors"
	"fmt"
	"html"
	"io"
	"regexp"
	"sort"
	"strings"
	"sync"
)

var discFileRE = regexp.MustCompile(`(?i)^(.*/)?(BDMV/(?:PLAYLIST/[^/]+\.mpls|CLIPINF/[^/]+\.clpi|STREAM/[^/]+\.m2ts))$`)

// discPath splits a disc file's name into its disc root and its path under it.
func discPath(name string) (root, rel string, ok bool) {
	m := discFileRE.FindStringSubmatch(name)
	if m == nil {
		return "", "", false
	}
	return m[1], m[2], true
}

// isDiscClip reports whether a disc file is a clip rather than a playlist or CLPI.
func isDiscClip(rel string) bool { return strings.EqualFold(pathExtension(rel), ".m2ts") }

type discFiles struct {
	mu    sync.Mutex
	files map[string]*Content // by lowercase path under the disc root
	lazy  map[string]lazyDiscFile
	small map[string][]byte // Playlists and CLPI, read once.
	rest  func(ctx context.Context, add func(name string, c *Content)) error
	once  sync.Once
	done  chan struct{}
	err   error
}

// lazyDiscFile is a disc image's file, mapped when first asked for: a clip as
// ranges of the image, a playlist or CLPI as its bytes.
type lazyDiscFile struct {
	name string
	open func(context.Context) (*Content, []byte, error)
}

func newDiscFiles() *discFiles {
	return &discFiles{files: map[string]*Content{}, lazy: map[string]lazyDiscFile{}, small: map[string][]byte{}, done: make(chan struct{})}
}

// addLazy keeps a file that open maps on first use.
func (d *discFiles) addLazy(name string, open func(context.Context) (*Content, []byte, error)) {
	_, rel, ok := discPath(name)
	if !ok {
		return
	}
	key := strings.ToLower(rel)
	d.mu.Lock()
	if _, seen := d.files[key]; !seen {
		d.lazy[key] = lazyDiscFile{name, open}
	}
	d.mu.Unlock()
}

// add keeps a disc file by its path under the disc root; the first one wins.
func (d *discFiles) add(name string, c *Content) {
	_, rel, ok := discPath(name)
	if !ok {
		return
	}
	key := strings.ToLower(rel)
	d.mu.Lock()
	if _, seen := d.files[key]; !seen {
		d.files[key] = c
	}
	d.mu.Unlock()
}

// start walks the rest of the set once, in the background, for the session's lifetime,
// and reads the disc's playlists as it goes.
func (d *discFiles) start(ctx context.Context) {
	d.once.Do(func() {
		go d.warm(ctx)
		if d.rest == nil {
			close(d.done)
			return
		}
		go func() {
			defer close(d.done)
			err := d.rest(ctx, d.add)
			d.mu.Lock()
			d.err = err
			d.mu.Unlock()
		}()
	})
}

// warm reads the disc's playlists into memory as the session opens. A player
// reads them all before it starts, to find the feature's, and read on demand
// each waits on the articles that hold it, one after another. Those found by
// the walk are read once it ends.
func (d *discFiles) warm(ctx context.Context) {
	read := func() {
		for _, rel := range d.unreadPlaylists() {
			if ctx.Err() != nil {
				return
			}
			_, _, _ = d.lookup(ctx, ctx, rel) // A failure here is the player's to see, when it asks.
		}
	}
	read()
	select {
	case <-d.done:
		read()
	case <-ctx.Done():
	}
}

// unreadPlaylists lists the paths of the playlists not yet read, in order.
func (d *discFiles) unreadPlaylists() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	var keys []string
	for key := range d.files {
		if _, read := d.small[key]; !read && strings.HasPrefix(key, "bdmv/playlist/") {
			keys = append(keys, key)
		}
	}
	for key := range d.lazy {
		_, read := d.small[key]
		if _, mapped := d.files[key]; !mapped && !read && strings.HasPrefix(key, "bdmv/playlist/") {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	return keys
}

// isRead reports whether a disc file's bytes are in memory.
func (d *discFiles) isRead(rel string) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, ok := d.small[strings.ToLower(rel)]
	return ok
}

func (d *discFiles) get(rel string) *Content {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.files[strings.ToLower(rel)]
}

// lookup finds a disc file, waiting for the walk of the rest of the set when
// it has not been seen yet. A playlist or CLPI comes back as its bytes.
func (d *discFiles) lookup(ctx, session context.Context, rel string) (*Content, []byte, error) {
	key := strings.ToLower(rel)
	d.mu.Lock()
	b, cached := d.small[key]
	d.mu.Unlock()
	if cached {
		return nil, b, nil
	}
	c, err := d.find(ctx, session, key)
	if err != nil || c == nil || isDiscClip(rel) {
		if c == nil && err == nil {
			d.mu.Lock()
			b, cached = d.small[key]
			d.mu.Unlock()
			if cached {
				return nil, b, nil
			}
		}
		return c, nil, err
	}
	// A small file of an archive: read it once.
	r := c.Reader(ctx, 0)
	defer r.Close()
	b, err = io.ReadAll(io.LimitReader(r, maxClipInfoBytes+1))
	if err != nil {
		return nil, nil, err
	}
	d.mu.Lock()
	d.small[key] = b
	d.mu.Unlock()
	return nil, b, nil
}

func (d *discFiles) find(ctx, session context.Context, key string) (*Content, error) {
	if c := d.get(key); c != nil {
		return c, nil
	}
	d.mu.Lock()
	lazy, ok := d.lazy[key]
	d.mu.Unlock()
	if ok {
		c, b, err := lazy.open(ctx)
		if err != nil || (c == nil && b == nil) {
			return nil, err
		}
		d.mu.Lock()
		defer d.mu.Unlock()
		if b != nil {
			d.small[key] = b
			return nil, nil
		}
		if kept := d.files[key]; kept != nil {
			return kept, nil
		}
		d.files[key] = c
		return c, nil
	}
	d.start(session)
	select {
	case <-d.done:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	if c := d.get(key); c != nil {
		return c, nil
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.err != nil && !errors.Is(d.err, context.Canceled) {
		return nil, fmt.Errorf("finding %s: %w", key, d.err)
	}
	return nil, nil
}

// playlists lists the playlist names. They precede the clips in a set, so the
// walk of its rest is awaited only when selection saw none.
func (d *discFiles) playlists(ctx, session context.Context) ([]string, error) {
	names := d.playlistNames()
	if len(names) == 0 {
		d.start(session)
		select {
		case <-d.done:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		names = d.playlistNames()
	}
	return names, nil
}

func (d *discFiles) playlistNames() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	var names []string
	for key, c := range d.files {
		if strings.HasPrefix(key, "bdmv/playlist/") {
			names = append(names, c.Name[strings.LastIndexByte(c.Name, '/')+1:])
		}
	}
	for key, f := range d.lazy {
		if _, mapped := d.files[key]; !mapped && strings.HasPrefix(key, "bdmv/playlist/") {
			names = append(names, f.name[strings.LastIndexByte(f.name, '/')+1:])
		}
	}
	sort.Strings(names)
	return names
}

// discListing renders a directory listing a player can read, like a web server's.
func discListing(names []string) string {
	var b strings.Builder
	b.WriteString("<html><head><title>BDMV/PLAYLIST</title></head><body>\n")
	for _, n := range names {
		fmt.Fprintf(&b, "<a href=\"%s\">%s</a><br>\n", html.EscapeString(n), html.EscapeString(n))
	}
	b.WriteString("</body></html>\n")
	return b.String()
}

// discFor collects the disc files selection saw under the selected clip's root.
func discFor(c *Content, seen map[string]*Content) *discFiles {
	root, _, ok := discPath(c.Name)
	if !ok || !isDiscClip(c.Name) {
		return nil
	}
	d := newDiscFiles()
	d.add(c.Name, c)
	lower := strings.ToLower(root)
	for name, file := range seen {
		if r, _, ok := discPath(name); ok && strings.ToLower(r) == lower {
			d.add(file.Name, file)
		}
	}
	return d
}

// keepDiscFile records a stored disc file once the cursor has passed it, so
// that its own cursor no longer moves with selection's. A playlist or CLPI may
// straddle two volumes like any entry.
func keepDiscFile(seen map[string]*Content, c *Content, b rarBlock) {
	_, rel, ok := discPath(c.Name)
	if !ok || b.crypt != nil || (!isDiscClip(rel) && c.Size > maxClipInfoBytes) {
		return
	}
	seen[strings.ToLower(c.Name)] = c
}

// skipEntry moves cursor past the rest of entry c, whose first block was b, when
// a volume proves where it ends: the set's last one, or the one its
// continuation template predicts. The skipped entry keeps its own cursor to
// map its volumes later.
func skipEntry(ctx context.Context, cursor *rarCursor, c *Content, b rarBlock) (bool, error) {
	if !b.after {
		return false, nil
	}
	resume := *cursor
	last, skipped, err := cursor.skipToLastPart(ctx, b.name, b.unpacked)
	if err == nil && skipped && resume.unordered && last.length > 0 && last.length < c.payloadSize() {
		last.start = c.payloadSize() - last.length
		c.tail = &last
	}
	if err == nil && !skipped {
		skipped, err = cursor.skipToPredictedEnd(ctx, b, c.payloadSize())
	}
	if err == nil && skipped {
		c.cursor = &resume
	}
	return skipped, err
}

// walkRest returns the walk of the set after the selected clip, whose first
// block was first, from a copy of selection's cursor right after it.
func walkRest(cur rarCursor, selected *Content, first rarBlock) func(context.Context, func(string, *Content)) error {
	feature := &Content{Name: selected.Name, Size: selected.Size, cursor: &cur, complete: !first.after,
		parts: []extent{selected.parts[0]}, padded: selected.padded}
	root, _, _ := discPath(selected.Name)
	return func(ctx context.Context, add func(string, *Content)) error {
		if skipped, err := skipEntry(ctx, &cur, feature, first); err != nil {
			return err
		} else if !skipped {
			if err := feature.extend(ctx, -1); err != nil {
				return err
			}
		}
		for {
			b, f, err := cur.next(ctx)
			if err == io.EOF {
				return nil
			}
			if err != nil {
				return err
			}
			if b.before {
				return errors.New("RAR entry starts with a missing continuation")
			}
			if b.directory {
				continue
			}
			c := &Content{Name: b.name, Size: b.unpacked, cursor: &cur, complete: !b.after, parts: []extent{{f, b.data, b.packed, 0}}, padded: b.crypt != nil}
			if c.Size < 0 || b.packed > c.payloadSize() || (c.complete && b.packed != c.payloadSize()) {
				return errors.New("invalid stored RAR file size")
			}
			seen := map[string]*Content{}
			if skipped, err := skipEntry(ctx, &cur, c, b); err != nil {
				return err
			} else if !skipped {
				if err := c.extend(ctx, -1); err != nil {
					return err
				}
			}
			keepDiscFile(seen, c, b)
			for _, file := range seen {
				if r, _, _ := discPath(file.Name); strings.EqualFold(r, root) {
					add(file.Name, file)
				}
			}
		}
	}
}
