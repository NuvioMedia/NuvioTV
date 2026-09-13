package nntp

import (
	"errors"
	"io"
	"net/textproto"
)

// BodyPipeline issues several BODY commands on one connection before reading
// the first reply, so the next article is already on the wire when the current
// one ends. Without it every article costs one idle round trip between the last
// byte of article N and the first byte of N+1: at 30 ms RTT that is most of the
// wall clock a segment fetch spends, and it is time no amount of extra
// connections recovers on a link that is already saturated.
//
// It is deliberately thinner than Client.Body:
//
//   - No retry, no Reconnect. Reconnecting drops every reply still in flight,
//     so a pipeline that trips is finished; the caller discards the connection
//     and refetches the remainder the ordinary way, where failover lives.
//   - Replies must be consumed in issue order, each body drained to io.EOF
//     before the next Next call. Undrained article bytes would be parsed as the
//     next reply's status line.
//   - Not safe for concurrent use. Issue and Next both touch the connection
//     deadline, and the ordering above only holds from one goroutine.
type BodyPipeline struct {
	c       *Client
	depth   int
	pending []pipelinedRequest
	broken  bool
}

type pipelinedRequest struct {
	id        uint
	messageID string
}

// ErrPipelineBroken is returned once a pipeline has lost sync with the
// connection. The connection must be discarded, never returned to a pool.
var ErrPipelineBroken = errors.New("nntp: body pipeline broken")

// PipelinedBody is one reply drained from a BodyPipeline, in the order its
// command was issued.
type PipelinedBody struct {
	MessageID string

	// Body is the article in canonical wire form, exactly as Client.Body
	// returns it. It is nil when Err is set. The caller MUST close it before
	// calling Next again.
	Body io.ReadCloser

	// Err is a per-article failure: the server answered this one command with
	// a status other than 222, which for BODY is always a single line (430, 412,
	// 420, 423). The pipeline stays usable — the replies queued behind it are
	// untouched — so a missing article costs the batch nothing but that article.
	Err error
}

// NewBodyPipeline starts a pipeline on c with the given depth. Depth below 2 is
// meaningless (that is what Client.Body already does) and is raised to 2.
func (c *Client) NewBodyPipeline(depth int) *BodyPipeline {
	if depth < 2 {
		depth = 2
	}
	return &BodyPipeline{c: c, depth: depth, pending: make([]pipelinedRequest, 0, depth)}
}

// Depth is the most replies this pipeline keeps outstanding.
func (p *BodyPipeline) Depth() int { return p.depth }

// Len is how many replies are currently outstanding.
func (p *BodyPipeline) Len() int { return len(p.pending) }

// Broken reports whether the pipeline lost sync with the connection. A broken
// pipeline's connection must be discarded rather than pooled.
func (p *BodyPipeline) Broken() bool { return p.broken }

// Issue sends one BODY command without waiting for its reply. It fails when the
// pipeline is already full, so the caller drives the window by alternating
// Issue and Next.
func (p *BodyPipeline) Issue(messageID string) error {
	if p.broken {
		return ErrPipelineBroken
	}
	if len(p.pending) >= p.depth {
		return errors.New("nntp: body pipeline full")
	}
	p.c.setDeadline()
	id, err := p.c.conn.Cmd("BODY %s", formatMessageID(messageID))
	if err != nil {
		// The command may or may not have reached the server, so what is still
		// queued behind it can no longer be matched to a reply.
		p.broken = true
		return err
	}
	p.pending = append(p.pending, pipelinedRequest{id: id, messageID: messageID})
	return nil
}

// Next reads the oldest outstanding reply. A non-nil error means the connection
// is unusable and every reply still queued on it is lost; a per-article failure
// arrives in the returned PipelinedBody's Err instead, leaving the pipeline
// usable. io.EOF means nothing is outstanding.
func (p *BodyPipeline) Next() (PipelinedBody, error) {
	if p.broken {
		return PipelinedBody{}, ErrPipelineBroken
	}
	if len(p.pending) == 0 {
		return PipelinedBody{}, io.EOF
	}
	req := p.pending[0]
	p.pending = p.pending[1:]

	p.c.setDeadline()
	p.c.conn.StartResponse(req.id)
	code, _, err := p.c.conn.ReadCodeLine(222)
	if err != nil {
		p.c.conn.EndResponse(req.id)
		// A parsed status code means the server answered this command and
		// stopped, so the next reply starts at a clean line boundary. Code 0
		// means no status line came back at all — the socket is gone, and the
		// replies behind this one went with it.
		var protoErr *textproto.Error
		if code == 0 || !errors.As(err, &protoErr) {
			p.broken = true
			return PipelinedBody{}, err
		}
		return PipelinedBody{MessageID: req.messageID, Err: err}, nil
	}

	metricR := &metricReader{r: newArticleReader(p.c.conn.R), client: p.c}
	body := &bodyReader{
		Reader:      metricR,
		endResponse: func() { p.c.conn.EndResponse(req.id) },
	}
	return PipelinedBody{
		MessageID: req.messageID,
		Body:      &pipelinedBodyReader{ReadCloser: body, p: p},
	}, nil
}

// pipelinedBodyReader marks the pipeline broken when the caller stops reading
// before io.EOF. Client.Body tolerates a short read because the pool discards
// that connection afterwards; here the undrained tail of the article would be
// read as the next reply's status line, so the pipeline has to notice.
type pipelinedBodyReader struct {
	io.ReadCloser
	p       *BodyPipeline
	drained bool
}

func (r *pipelinedBodyReader) Read(b []byte) (int, error) {
	n, err := r.ReadCloser.Read(b)
	if err == io.EOF {
		r.drained = true
	}
	return n, err
}

func (r *pipelinedBodyReader) Close() error {
	if !r.drained {
		r.p.broken = true
	}
	return r.ReadCloser.Close()
}
