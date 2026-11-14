package engine

import (
	"bytes"
	"context"
	"io"
	"strings"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/rarname"
)

// minSniffedEntry is the smallest archive entry of unknown type whose first
// bytes are read to find a video.
var minSniffedEntry int64 = 64 << 20

// maxSniffedEntries bounds those reads in one selection.
const maxSniffedEntries = 4

// videoExtension names the container a file's first bytes start, or "".
func videoExtension(head []byte) string {
	switch {
	case bytes.HasPrefix(head, []byte{0x1a, 0x45, 0xdf, 0xa3}):
		return ".mkv"
	case len(head) >= 8 && string(head[4:8]) == "ftyp":
		return ".mp4"
	case len(head) >= 12 && string(head[:4]) == "RIFF" && string(head[8:12]) == "AVI ":
		return ".avi"
	}
	return ""
}

// splitVideo serves the numbered parts of a video cut without an archive
// (Movie.mkv.001, .002, ...) as one file. Part sizes come from the same probes
// as a 7z set's volumes: the middle ones are predicted and each is checked
// against its own yEnc size before it is read.
func splitVideo(ctx context.Context, vols []*File, ext string) (*Content, error) {
	layout, err := sevenZipLayout(ctx, vols, false)
	if err != nil {
		return nil, err
	}
	name, ok := rarname.NumericStem(vols[0].Name)
	if !ok {
		name = vols[0].Name
	}
	if !isVideo(name) {
		name += ext
	}
	c := &Content{Name: name, complete: true}
	for _, v := range layout {
		c.parts = append(c.parts, extent{v.file, 0, v.size, v.start})
		if !v.verified {
			if c.pending == nil {
				c.pending = make(map[*File]int64)
			}
			c.pending[v.file] = v.size
		}
	}
	last := layout[len(layout)-1]
	c.Size = last.start + last.size
	return c, nil
}

// typeByContent gives a large stored entry of unknown type, such as a video
// whose obfuscated name has no extension, the extension its first bytes show.
// Its headers keep the stored name. Only the entry's first part is read: no
// continuation volume is resolved. It returns an error only when ctx ends.
func (c *Content) typeByContent(ctx context.Context, b rarBlock, sniffed *int) error {
	lower := strings.ToLower(c.Name)
	_, volume := rarname.SetKey(c.Name)
	_, _, disc := discPath(c.Name)
	if *sniffed >= maxSniffedEntries || c.Size < minSniffedEntry || b.crypt != nil || len(c.parts) == 0 ||
		isVideo(c.Name) || isISO(c.Name) || isSubtitle(lower) || archiveName(c.Name) || volume || disc {
		return nil
	}
	n := min(int64(16), c.parts[0].length)
	if n < 12 {
		return nil
	}
	*sniffed++
	r := c.Reader(ctx, 0)
	r.probe = true
	defer r.Close()
	head := make([]byte, n)
	if _, err := io.ReadFull(r, head); err != nil {
		return ctx.Err() // An unreadable entry is no video; selection goes on.
	}
	if ext := videoExtension(head); ext != "" {
		c.stored, c.Name = c.Name, c.Name+ext
	}
	return nil
}
