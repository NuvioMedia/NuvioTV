package engine

// A stored RAR set inside a 7z archive: some indexers wrap scene releases this
// way. Each inner volume is a 7z entry, readable (and decrypted) as its own
// Content. The selected inner file is a composite Content whose children are
// byte ranges of those entries. As for NZB-level RAR sets, only the first,
// second and last volume headers are read before playback; middle volumes are
// predicted from the second one and each prediction is checked when a reader
// first reaches it, which costs nothing extra for sequential playback because
// the header shares its article with the payload that follows it.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/rarname"
)

type nestedChild struct {
	volume                int // index into nestedRAR.vols
	offset, length, start int64
	verified              bool
}

type nestedRAR struct {
	vols    []*Content
	version int
}

// contentAt adapts a Content to io.ReaderAt for header parsing. It is not safe
// for concurrent use; every caller owns its reader.
type contentAt struct{ r *ContentReader }

func (a contentAt) ReadAt(p []byte, off int64) (int, error) {
	if _, err := a.r.Seek(off, io.SeekStart); err != nil {
		return 0, err
	}
	n, err := io.ReadFull(a.r, p)
	if err == io.ErrUnexpectedEOF {
		err = io.EOF
	}
	return n, err
}

// blocks walks the headers of inner volume k from pos, calling visit for each
// one until visit returns false or the volume ends.
func (n *nestedRAR) blocks(ctx context.Context, k int, visit func(b rarBlock) bool) error {
	v := n.vols[k]
	r := v.Reader(ctx, 0)
	defer r.Close()
	at := contentAt{r}
	version, pos, err := rarSignature(at)
	if err != nil {
		return err
	}
	if n.version == 0 {
		n.version = version
	} else if version != n.version {
		return errors.New("RAR volumes mix format versions")
	}
	for {
		var b rarBlock
		if version == 4 {
			b, err = rar4Block(at, v.Size, pos)
		} else {
			b, err = rar5Block(at, v.Size, pos)
		}
		if err != nil {
			return err
		}
		if b.end || !visit(b) || b.after {
			return nil
		}
		if b.next <= pos {
			return errors.New("RAR header does not advance")
		}
		pos = b.next
	}
}

// fileBlock returns the continuation header of the selected file in volume k.
func (n *nestedRAR) fileBlock(ctx context.Context, k int, name string, size int64) (rarBlock, error) {
	var found rarBlock
	ok := false
	err := n.blocks(ctx, k, func(b rarBlock) bool {
		if b.file && !b.directory {
			found, ok = b, true
			return false
		}
		return true
	})
	if err != nil {
		return found, err
	}
	if !ok || !found.before || found.name != name || found.unpacked != size {
		return found, errors.New("RAR continuation does not match selected file")
	}
	return found, nil
}

// selectNestedRAR offers the files of an inner RAR set to consider and maps
// the selected one. A nil Content without error means nothing matched.
func selectNestedRAR(ctx context.Context, vols []*Content, index *int, consider func(*Content, int, string) (bool, error)) (*Content, error) {
	n := &nestedRAR{vols: vols}
	for k := 0; k < len(vols); k++ {
		var selected *Content
		var considerErr error
		continues := false
		err := n.blocks(ctx, k, func(b rarBlock) bool {
			if !b.file || b.directory {
				return true
			}
			continues = b.after
			if b.before {
				return true // tail of a file that started in an earlier volume.
			}
			c := &Content{Name: b.name, Size: b.unpacked, nested: n, complete: true}
			start, header := k, b
			c.mapLater = func(ctx context.Context) error { return c.mapNested(ctx, start, header) }
			matched, err := consider(c, *index, "")
			*index++
			if err != nil || matched {
				selected, considerErr = c, err
				return false
			}
			return true
		})
		if considerErr != nil {
			return nil, considerErr
		}
		if errors.Is(err, errNotRAR) && vols[k].aes != nil {
			// Decrypted bytes of a .rar entry without a RAR signature.
			return nil, Err7zWrongPassword
		}
		if err != nil {
			return nil, err
		}
		if selected != nil {
			return selected.usable(ctx)
		}
		if !continues {
			// Later volumes only continue files that started here.
			break
		}
	}
	return nil, nil
}

// mapNested lays out the selected file that starts in volume k with header b.
func (c *Content) mapNested(ctx context.Context, k int, b rarBlock) error {
	n := c.nested
	children := []nestedChild{{k, b.data, b.packed, 0, true}}
	start := b.packed
	if !b.after {
		if start != c.Size {
			return errors.New("invalid stored RAR file size")
		}
		c.children = children
		return nil
	}
	if k+1 >= len(n.vols) {
		return errors.New("missing RAR continuation")
	}
	second, err := n.fileBlock(ctx, k+1, c.Name, c.Size)
	if err != nil {
		return err
	}
	children = append(children, nestedChild{k + 1, second.data, second.packed, start, true})
	start += second.packed
	last := len(n.vols) - 1
	if second.after && last > k+1 {
		predicted := true
		for j := k + 2; j < last; j++ {
			if n.vols[j].Size != n.vols[k+1].Size || start+second.packed >= c.Size {
				predicted = false
				break
			}
			children = append(children, nestedChild{j, second.data, second.packed, start, false})
			start += second.packed
		}
		var tail rarBlock
		if predicted {
			tail, err = n.fileBlock(ctx, last, c.Name, c.Size)
			predicted = err == nil && !tail.after && start+tail.packed == c.Size
		}
		if predicted {
			children = append(children, nestedChild{last, tail.data, tail.packed, start, true})
			start += tail.packed
		} else {
			children = children[:2]
			start = children[1].start + children[1].length
			if children, err = n.resolveFrom(ctx, children, k+2, c.Name, c.Size); err != nil {
				return err
			}
			start = c.Size
		}
	} else if second.after {
		return errors.New("missing RAR continuation")
	}
	if start != c.Size {
		return errors.New("incomplete stored RAR data")
	}
	c.children = children
	return nil
}

// resolveFrom reads every continuation header from volume j on, replacing any
// prediction. Used when volumes are irregular or a prediction failed.
func (n *nestedRAR) resolveFrom(ctx context.Context, children []nestedChild, j int, name string, size int64) ([]nestedChild, error) {
	last := children[len(children)-1]
	start := last.start + last.length
	for after := true; after; j++ {
		if j >= len(n.vols) {
			return nil, errors.New("missing RAR continuation")
		}
		b, err := n.fileBlock(ctx, j, name, size)
		if err != nil {
			return nil, err
		}
		if b.packed <= 0 || b.packed > size-start {
			return nil, errors.New("invalid RAR continuation size")
		}
		children = append(children, nestedChild{j, b.data, b.packed, start, true})
		start += b.packed
		after = b.after
	}
	if start != size {
		return nil, errors.New("incomplete stored RAR data")
	}
	return children, nil
}

func (c *Content) nestedChild(off int64) (int, nestedChild) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	i := sort.Search(len(c.children), func(i int) bool { return c.children[i].start+c.children[i].length > off })
	if i == len(c.children) {
		return i, nestedChild{}
	}
	return i, c.children[i]
}

// child returns the verified mapping for a content offset.
func (c *Content) child(ctx context.Context, off int64) (nestedChild, error) {
	i, ch := c.nestedChild(off)
	if i == len(c.children) {
		return ch, io.EOF
	}
	if ch.verified {
		return ch, nil
	}
	c.mu.Lock()
	if c.layoutGate == nil {
		c.layoutGate = make(chan struct{}, 1)
	}
	gate := c.layoutGate
	c.mu.Unlock()
	waitStart := time.Now()
	select {
	case gate <- struct{}{}:
		c.layoutWaitNS.Add(time.Since(waitStart).Nanoseconds())
		defer func() { <-gate }()
	case <-ctx.Done():
		return ch, ctx.Err()
	}
	if i, ch = c.nestedChild(off); ch.verified {
		return ch, nil
	}
	layoutStart := time.Now()
	defer func() { c.layoutNS.Add(time.Since(layoutStart).Nanoseconds()) }()
	b, err := c.nested.fileBlock(ctx, ch.volume, c.Name, c.Size)
	if err != nil {
		return ch, fmt.Errorf("checking RAR continuation: %w", err)
	}
	if b.after && b.data == ch.offset && b.packed == ch.length {
		c.mu.Lock()
		c.children[i].verified = true
		c.mu.Unlock()
		return c.children[i], nil
	}
	// The prediction is wrong from here on: read the real headers instead.
	c.mu.RLock()
	prefix := append([]nestedChild(nil), c.children[:i]...)
	c.mu.RUnlock()
	children, err := c.nested.resolveFrom(ctx, prefix, ch.volume, c.Name, c.Size)
	if err != nil {
		return ch, err
	}
	c.mu.Lock()
	c.children = children
	c.mu.Unlock()
	_, ch = c.nestedChild(off)
	return ch, nil
}

func (r *ContentReader) readNested(p []byte) (int, error) {
	c := r.content
	ch, err := c.child(r.ctx, r.pos)
	if err != nil {
		if errors.Is(err, io.EOF) {
			err = fmt.Errorf("%w: incomplete RAR continuation", errInvalidArticle)
		}
		return 0, err
	}
	vol := c.nested.vols[ch.volume]
	if r.inner == nil || r.inner.content != vol {
		if r.inner != nil {
			r.inner.Close()
		}
		r.inner = vol.Reader(r.ctx, r.ahead)
	}
	if _, err := r.inner.Seek(ch.offset+r.pos-ch.start, io.SeekStart); err != nil {
		return 0, err
	}
	n, err := r.inner.Read(p[:min(int64(len(p)), ch.start+ch.length-r.pos)])
	r.pos += int64(n)
	if n > 0 && err == io.EOF {
		err = nil
	} else if n == 0 && (err == nil || err == io.EOF) {
		err = fmt.Errorf("%w: inner RAR volume ended early", errInvalidArticle)
	}
	return n, err
}

// nestedRARGroups returns the 7z entries that form RAR sets, in volume order.
func nestedRARGroups(entries []sevenZipEntry) [][]int {
	groups := map[string][]int{}
	var order []string
	for i, e := range entries {
		if key, ok := rarname.SetKey(e.name); ok {
			if _, seen := groups[key]; !seen {
				order = append(order, key)
			}
			groups[key] = append(groups[key], i)
		}
	}
	var out [][]int
	for _, key := range order {
		set := groups[key]
		sort.SliceStable(set, func(a, b int) bool {
			_, x, _ := rarname.VolumeNumber(entries[set[a]].name)
			_, y, _ := rarname.VolumeNumber(entries[set[b]].name)
			return x < y
		})
		out = append(out, set)
	}
	return out
}
