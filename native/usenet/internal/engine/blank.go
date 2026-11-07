package engine

import (
	"context"
	"errors"
	"io"
)

// Some posts carry a full-size archive of zeros under a real release name. It
// opens like any release and the player only gives up much later, so the
// selected video's first bytes are checked before the session is offered.
var errBlankContent = errors.New("Usenet release holds no video data")

const blankProbeBytes = 64 << 10

// checkBlankContent fails a selected video whose first bytes are all zeros. A
// read error says nothing about the content: playback reports it as before.
func checkBlankContent(ctx context.Context, c *Content) error {
	if !isVideo(c.Name) || c.Size <= 0 {
		return nil
	}
	r := c.Reader(ctx, 0)
	r.probe = true
	defer r.Close()
	head := make([]byte, min(c.Size, blankProbeBytes))
	if _, err := io.ReadFull(r, head); err != nil {
		return ctx.Err()
	}
	for _, b := range head {
		if b != 0 {
			return nil
		}
	}
	return errBlankContent
}
