package engine

// A stored RAR set inside a RAR set: uploaders wrap a scene release, its
// .rar/.rNN volumes with the .sfv, .nfo and sample, in an obfuscated RAR set
// of their own, often in shuffled order. The outer set is listed to find the
// inner volumes by name, and the inner set is read as one inside a 7z is
// (nested.go), each inner volume being an outer entry.
//
// Only the names order the inner volumes, so every outer entry must be found,
// which the selection walk does with three headers per entry. A listing reads
// one: an entry's header gives its first part, the volumes it fills follow
// the continuation template of a volume it fills (rarContinuationShift), and
// the next entry's header must parse where that arithmetic puts it. The parts
// between are checked when a reader first reaches them, like any predicted
// part (verifyRARPart). Stretches of the set are listed at once, each from
// the first entry header in its volumes.

import (
	"context"
	"errors"
	"io"
	"sort"
	"sync"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/rarname"
)

// rarListSpans bounds the stretches of a set listed at once; each holds at
// least rarListSpanVolumes volumes.
const (
	rarListSpans       = 8
	rarListSpanVolumes = 16
)

// errRARListGuess is a listing whose arithmetic the set's headers disagreed
// with; the set is then walked header by header.
var errRARListGuess = errors.New("RAR listing does not match the set")

// wrapsRARVolume reports an entry, whose header b was read from f, that is a
// volume of a RAR set: named as one and opening with a volume's main header.
// A subtitle pack stored as a single archive is not. The main header follows
// the entry's header, in the same article. The numeric scheme is left out:
// .001 volumes are usually 7z.
func wrapsRARVolume(ctx context.Context, f *File, b rarBlock) bool {
	if scheme, _, ok := rarname.VolumeNumber(b.name); !ok || scheme == rarname.SchemeNumeric || b.crypt != nil || b.directory {
		return false
	}
	r := f.headerReader(ctx)
	defer r.Close()
	inner := io.NewSectionReader(r, b.data, b.packed)
	version, pos, err := rarSignature(inner)
	if err != nil {
		return false
	}
	var main rarBlock
	if version == 4 {
		main, err = rar4Block(inner, b.packed, pos)
	} else {
		main, err = rar5Block(inner, b.packed, pos)
	}
	return err == nil && main.main && main.multiVolume
}

type rarEntryHead struct {
	vol   int
	block rarBlock
}

type rarLister struct {
	vols     []*File
	index    map[*File]int
	version  int
	template *rarBlock // A continuation that fills its volume, from real headers.
	fill     int64     // That volume's decoded size.
}

// selectRARInRAR lists the set from first, an entry that is a volume of a RAR
// set stored inside it, and selects from the inner sets, then from the files
// beside them. cursor sits right after first, read from f.
func selectRARInRAR(ctx context.Context, cursor *rarCursor, first rarBlock, f *File, s Selection, index *int, consider func(*Content, int, string) (bool, error)) (*Content, error) {
	entries, err := listRARSet(ctx, cursor, first, f)
	if err != nil {
		return nil, err
	}
	names := make([]string, len(entries))
	for i, e := range entries {
		names[i] = e.Name
	}
	groups := nestedRARGroups(names)
	size := func(group []int) (n int64) {
		for _, j := range group {
			n += entries[j].Size
		}
		return n
	}
	// The release before a subtitle pack stored beside it.
	sort.SliceStable(groups, func(a, b int) bool { return size(groups[a]) > size(groups[b]) })
	inner := make([]bool, len(entries))
	var nestedErr error
	for _, group := range groups {
		vols := make([]*Content, len(group))
		for i, j := range group {
			vols[i], inner[j] = entries[j], true
		}
		c, err := selectNestedRAR(ctx, vols, index, consider, s.whole())
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if err != nil {
			nestedErr = err
			continue
		}
		if c != nil {
			return c, nil
		}
	}
	var largest *Content
	for j, e := range entries {
		if inner[j] {
			continue
		}
		matched, err := consider(e, *index, "")
		*index++
		if err != nil {
			return nil, err
		}
		if matched && !s.whole() {
			return e, nil
		}
		if matched && (largest == nil || e.Size > largest.Size) {
			largest = e
		}
	}
	if largest != nil {
		return largest, nil
	}
	return nil, nestedErr
}

// listRARSet returns the files of the set from first on, directories left out.
func listRARSet(ctx context.Context, cursor *rarCursor, first rarBlock, f *File) ([]*Content, error) {
	resume := *cursor
	// Shuffled volumes have no arithmetic order, and encrypted headers no
	// readable position without the volume's key.
	if !cursor.unordered && first.headerPrefix == 0 {
		l := &rarLister{vols: cursor.files, index: make(map[*File]int, len(cursor.files)), version: first.version}
		for i, v := range cursor.files {
			l.index[v] = i
		}
		entries, err := l.list(ctx, rarEntryHead{l.index[f], first})
		if err == nil || ctx.Err() != nil || permanentStreamFailure(err) != "" {
			return entries, err
		}
	}
	return listRARWalk(ctx, &resume, first, f)
}

func (l *rarLister) list(ctx context.Context, h rarEntryHead) ([]*Content, error) {
	var out []*Content
	// Entries are mapped from real headers until one fills a volume.
	ok := true
	for ok && l.template == nil {
		c, i, pos, err := l.read(ctx, h)
		if err != nil {
			return nil, err
		}
		if c != nil {
			out = append(out, c)
		}
		if h, ok, err = l.next(ctx, i, pos); err != nil {
			return nil, err
		}
	}
	if !ok {
		return out, nil
	}
	n := len(l.vols) - h.vol
	spans := max(1, min(rarListSpans, n/rarListSpanVolumes))
	heads := make([]rarEntryHead, spans)
	found := make([]bool, spans)
	errs := make([]error, spans)
	heads[0], found[0] = h, true
	var wg sync.WaitGroup
	for j := 1; j < spans; j++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			heads[j], found[j], errs[j] = l.head(ctx, h.vol+j*n/spans)
		}()
	}
	wg.Wait()
	starts := []rarEntryHead{h}
	for j := 1; j < spans; j++ {
		if errs[j] != nil {
			return nil, errs[j]
		}
		// A long entry can hold several stretches' first headers.
		if prev := starts[len(starts)-1]; found[j] && (heads[j].vol != prev.vol || heads[j].block.data != prev.block.data) {
			starts = append(starts, heads[j])
		}
	}
	lists := make([][]*Content, len(starts))
	errs = make([]error, len(starts))
	for j := range starts {
		var stop *rarEntryHead
		if j+1 < len(starts) {
			stop = &starts[j+1]
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			lists[j], errs[j] = l.walk(ctx, starts[j], stop)
		}()
	}
	wg.Wait()
	for j := range starts {
		if errs[j] != nil {
			return nil, errs[j]
		}
		out = append(out, lists[j]...)
	}
	return out, nil
}

// head returns the first entry header from the start of volume vol on, read
// from real headers. It reports false at the set's end.
func (l *rarLister) head(ctx context.Context, vol int) (rarEntryHead, bool, error) {
	cur := rarCursor{files: l.vols, index: vol}
	for {
		b, f, err := cur.next(ctx)
		if err == io.EOF {
			return rarEntryHead{}, false, nil
		}
		if err != nil {
			return rarEntryHead{}, false, err
		}
		if !b.before {
			return rarEntryHead{l.index[f], b}, true, nil
		}
	}
}

// walk lists the entries from h until the next stretch's first header, stop,
// or the set's end.
func (l *rarLister) walk(ctx context.Context, h rarEntryHead, stop *rarEntryHead) ([]*Content, error) {
	var out []*Content
	for {
		var c *Content
		var i int
		var pos int64
		var err error
		// Continuation headers repeat the first one, name included, and the
		// volume's quick open data copies them: a file whose header differs
		// in size from the template's fills a volume differently.
		if h.block.header == l.template.header {
			c, i, pos, err = l.predict(h)
		} else {
			c, i, pos, err = l.read(ctx, h)
		}
		if err != nil {
			return nil, err
		}
		if c != nil {
			out = append(out, c)
		}
		next, ok, err := l.next(ctx, i, pos)
		if err != nil {
			return nil, err
		}
		if stop == nil {
			if !ok {
				return out, nil
			}
		} else if !ok || next.vol > stop.vol || next.vol == stop.vol && next.block.data > stop.block.data {
			return nil, errRARListGuess
		} else if next.vol == stop.vol && next.block.data == stop.block.data {
			return out, nil
		}
		h = next
	}
}

func (l *rarLister) entry(h rarEntryHead) (*Content, error) {
	b := h.block
	if b.crypt != nil {
		return nil, ErrEncryptedRAR
	}
	if b.unpacked < 0 || b.packed > b.unpacked || (!b.after && b.packed != b.unpacked) {
		return nil, errors.New("invalid stored RAR file size")
	}
	return &Content{Name: b.name, Size: b.unpacked, complete: true, parts: []extent{{l.vols[h.vol], b.data, b.packed, 0}}}, nil
}

// read maps the entry whose header is h from the headers of the volumes it
// continues into, and keeps the first continuation that fills its volume as
// the template. It returns the entry, nil for a directory, and where the next
// header starts.
func (l *rarLister) read(ctx context.Context, h rarEntryHead) (*Content, int, int64, error) {
	c, err := l.entry(h)
	if err != nil || !h.block.after {
		return listedFile(c, h.block), h.vol, h.block.next, err
	}
	start := h.block.packed
	for i := h.vol + 1; i < len(l.vols); i++ {
		cur := rarCursor{files: l.vols, index: i}
		b, f, err := cur.next(ctx)
		if err != nil {
			return nil, 0, 0, err
		}
		if !b.before || b.name != c.Name || b.unpacked != c.Size || b.packed <= 0 || b.packed > c.Size-start || (!b.after && b.packed != c.Size-start) {
			return nil, 0, 0, errors.New("RAR continuation does not match selected file")
		}
		c.parts = append(c.parts, extent{f, b.data, b.packed, start})
		start += b.packed
		if !b.after {
			return c, i, b.next, nil
		}
		if l.template == nil && b.template && f.Size() == l.vols[i-1].Size() {
			t := b
			l.template, l.fill = &t, f.Size()
		}
	}
	return nil, 0, 0, errors.New("missing RAR continuation")
}

// predict maps the entry whose header is h by the template, and returns where
// the next header starts: right after the entry's last part.
func (l *rarLister) predict(h rarEntryHead) (*Content, int, int64, error) {
	c, err := l.entry(h)
	if err != nil || !h.block.after {
		return listedFile(c, h.block), h.vol, h.block.next, err
	}
	t := *l.template
	start := h.block.packed
	for i := h.vol + 1; i < len(l.vols); i++ {
		delta, ok := rarContinuationShift(t, i)
		if !ok {
			return nil, 0, 0, errRARListGuess
		}
		f, room := l.vols[i], t.packed-delta
		if rest := c.Size - start; rest <= room {
			c.parts = append(c.parts, extent{f, t.data + delta, rest, start})
			verified := make([]bool, len(c.parts))
			verified[0] = true
			c.predicted = &rarPrediction{base: h.vol, volumeSize: l.fill, version: l.version, verified: verified, known: 1}
			c.cursor = &rarCursor{files: l.vols, index: h.vol + 1}
			return c, i, t.data + delta + rest, nil
		}
		f.mu.RLock()
		wrongSize := f.exact && f.size != l.fill
		f.mu.RUnlock()
		if wrongSize {
			return nil, 0, 0, errRARListGuess
		}
		c.parts = append(c.parts, extent{f, t.data + delta, room, start})
		start += room
	}
	return nil, 0, 0, errRARListGuess
}

// listedFile leaves directories out of a listing.
func listedFile(c *Content, b rarBlock) *Content {
	if b.directory {
		return nil
	}
	return c
}

// next returns the entry header at pos of volume i, where the previous entry
// ended, or the next volume's first one when this volume ends there. It
// reports false at the set's end.
func (l *rarLister) next(ctx context.Context, i int, pos int64) (rarEntryHead, bool, error) {
	v := &rarVolume{file: l.vols[i], version: l.version}
	for {
		b, err := v.block(ctx, pos)
		if err != nil {
			if ctx.Err() == nil && permanentStreamFailure(err) == "" {
				err = errRARListGuess // Not a header: the arithmetic missed it.
			}
			return rarEntryHead{}, false, err
		}
		if b.file {
			if b.before {
				return rarEntryHead{}, false, errRARListGuess
			}
			return rarEntryHead{i, b}, true, nil
		}
		if b.main {
			return rarEntryHead{}, false, errRARListGuess
		}
		if b.end {
			break
		}
		if b.next <= pos {
			return rarEntryHead{}, false, errors.New("RAR header does not advance")
		}
		pos = b.next // Quick open data and other service headers.
	}
	if i+1 == len(l.vols) {
		return rarEntryHead{}, false, nil
	}
	cur := rarCursor{files: l.vols, index: i + 1}
	b, f, err := cur.next(ctx)
	if err == io.EOF {
		return rarEntryHead{}, false, nil
	}
	if err != nil {
		return rarEntryHead{}, false, err
	}
	if b.before || f != l.vols[i+1] {
		return rarEntryHead{}, false, errRARListGuess
	}
	return rarEntryHead{i + 1, b}, true, nil
}

// listRARWalk lists the set from first on by reading every header with the
// selection cursor.
func listRARWalk(ctx context.Context, cursor *rarCursor, first rarBlock, f *File) ([]*Content, error) {
	var out []*Content
	var open *Content // The file whose continuation comes next.
	for b := first; ; {
		switch {
		case b.crypt != nil:
			return nil, ErrEncryptedRAR
		case !b.before && open != nil:
			return nil, errors.New("RAR continuation is missing")
		case !b.before:
			c := &Content{Name: b.name, Size: b.unpacked, complete: true, parts: []extent{{f, b.data, b.packed, 0}}}
			if !b.directory {
				out = append(out, c)
			}
			open = c
		case open == nil || b.name != open.Name || b.unpacked != open.Size:
			return nil, errors.New("RAR continuation does not match selected file")
		default:
			last := open.parts[len(open.parts)-1]
			open.parts = append(open.parts, extent{f, b.data, b.packed, last.start + last.length})
		}
		if !b.after {
			last := open.parts[len(open.parts)-1]
			if last.start+last.length != open.Size {
				return nil, errors.New("incomplete stored RAR data")
			}
			open = nil
		}
		var err error
		if b, f, err = cursor.next(ctx); err == io.EOF {
			break
		} else if err != nil {
			return nil, err
		}
	}
	if open != nil {
		return nil, errors.New("missing RAR continuation")
	}
	return out, nil
}
