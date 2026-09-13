package nntppool

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/mnightingale/rapidyenc"
)

// ArticleEncoding describes the transfer encoding detected in an article body.
type ArticleEncoding int

const (
	EncodingUnknown ArticleEncoding = iota
	EncodingYEnc
	EncodingUU
)

// ArticleBody holds the decoded result of a BODY command.
type ArticleBody struct {
	MessageID string

	// Decoded payload bytes. Nil when the body was streamed to an io.Writer.
	Bytes []byte

	BytesDecoded  int
	BytesConsumed int
	Encoding      ArticleEncoding

	// yEnc metadata from =ybegin/=ypart (zero values when not yEnc).
	YEnc YEncMeta

	CRC         uint32
	ExpectedCRC uint32
	CRCValid    bool // true when ExpectedCRC != 0 && CRC == ExpectedCRC

	byteBuf []byte // internal; transferred to Bytes in Body()
}

// ArticleHead holds the parsed result of a HEAD command.
type ArticleHead struct {
	MessageID string
	Headers   map[string][]string // RFC 5322 headers with folding resolved
}

// StatResult holds the parsed result of a STAT command.
type StatResult struct {
	MessageID string
	Number    int64  // article number from response (0 if no group selected)
	Provider  string // provider name that returned the successful response
}

// BodyResult is the result type for BodyAsync.
type BodyResult struct {
	Body *ArticleBody
	Err  error
}

// Body retrieves and decodes an article body, buffering the decoded bytes in memory.
// An optional onMeta callback is invoked with yEnc metadata before body decoding begins.
func (c *Client) Body(ctx context.Context, messageID string, onMeta ...func(YEncMeta)) (*ArticleBody, error) {
	var metaFn func(YEncMeta)
	if len(onMeta) > 0 {
		metaFn = onMeta[0]
	}
	body, err := c.doBody(ctx, messageID, nil, metaFn)
	if body != nil {
		body.Bytes = body.byteBuf
		body.byteBuf = nil
	}
	return body, err
}

// BodyPriority is like Body but enqueues on the priority channel so idle
// connections pick it up before normal requests.
func (c *Client) BodyPriority(ctx context.Context, messageID string, onMeta ...func(YEncMeta)) (*ArticleBody, error) {
	payload := []byte("BODY <" + messageID + ">\r\n")
	var respCh <-chan Response
	if len(onMeta) > 0 {
		respCh = c.SendPriority(ctx, payload, nil, onMeta[0])
	} else {
		respCh = c.SendPriority(ctx, payload, nil)
	}
	body, err := c.finishBody(messageID, nil, respCh)
	if body != nil {
		body.Bytes = body.byteBuf
		body.byteBuf = nil
	}
	return body, err
}

// BodyBackground is like Body but enqueues on the background lane: served
// only by connections with nothing priority or normal queued, and held to a
// per-provider floor while foreground traffic is recent (see
// Provider.BackgroundFloor). For fetches nobody is waiting on — a PAR2 repair
// reading a whole release — that may use every idle connection but must
// never delay a stream or an import.
func (c *Client) BodyBackground(ctx context.Context, messageID string, onMeta ...func(YEncMeta)) (*ArticleBody, error) {
	payload := []byte("BODY <" + messageID + ">\r\n")
	var respCh <-chan Response
	if len(onMeta) > 0 {
		respCh = c.SendBackground(ctx, payload, nil, onMeta[0])
	} else {
		respCh = c.SendBackground(ctx, payload, nil)
	}
	body, err := c.finishBody(messageID, nil, respCh)
	if body != nil {
		body.Bytes = body.byteBuf
		body.byteBuf = nil
	}
	return body, err
}

// BodyStreamPriority is BodyStream on the priority lane: decoded bytes are
// written to w as each wire read is decoded, so a caller can serve the head
// of an article before its tail has arrived. Bytes is nil on the result. If a
// provider fails after some bytes were written the client does not fail over,
// since re-streaming into w would duplicate them, and returns the error;
// callers wanting a second attempt must supply a fresh writer.
func (c *Client) BodyStreamPriority(ctx context.Context, messageID string, w io.Writer, onMeta ...func(YEncMeta)) (*ArticleBody, error) {
	if w == nil {
		return nil, fmt.Errorf("nntp: BodyStreamPriority requires a non-nil writer")
	}
	payload := []byte("BODY <" + messageID + ">\r\n")
	var respCh <-chan Response
	if len(onMeta) > 0 {
		respCh = c.SendPriority(ctx, payload, w, onMeta[0])
	} else {
		respCh = c.SendPriority(ctx, payload, w)
	}
	return c.finishBody(messageID, w, respCh)
}

// BodyStream retrieves and decodes an article body, streaming decoded bytes to w.
// The returned ArticleBody contains metadata but Bytes will be nil.
// An optional onMeta callback is invoked with yEnc metadata before body decoding begins.
func (c *Client) BodyStream(ctx context.Context, messageID string, w io.Writer, onMeta ...func(YEncMeta)) (*ArticleBody, error) {
	if w == nil {
		return nil, fmt.Errorf("nntp: BodyStream requires a non-nil writer")
	}
	var metaFn func(YEncMeta)
	if len(onMeta) > 0 {
		metaFn = onMeta[0]
	}
	return c.doBody(ctx, messageID, w, metaFn)
}

// BodyAsync returns a channel that will receive exactly one BodyResult.
// The body is streamed to w (use io.Discard to discard decoded bytes).
// This preserves the fan-out pattern used by Send.
// An optional onMeta callback is invoked with yEnc metadata before body decoding begins.
func (c *Client) BodyAsync(ctx context.Context, messageID string, w io.Writer, onMeta ...func(YEncMeta)) <-chan BodyResult {
	var metaFn func(YEncMeta)
	if len(onMeta) > 0 {
		metaFn = onMeta[0]
	}
	ch := make(chan BodyResult, 1)
	go func() {
		body, err := c.doBody(ctx, messageID, w, metaFn)
		ch <- BodyResult{Body: body, Err: err}
		close(ch)
	}()
	return ch
}

// Head retrieves the headers of an article.
func (c *Client) Head(ctx context.Context, messageID string) (*ArticleHead, error) {
	payload := []byte("HEAD <" + messageID + ">\r\n")
	respCh := c.Send(ctx, payload, nil)

	resp := <-respCh
	if resp.Err != nil {
		return nil, resp.Err
	}
	if err := toError(resp.StatusCode, resp.Status); err != nil {
		return nil, err
	}

	return &ArticleHead{
		MessageID: messageID,
		Headers:   parseHeaders(resp.Lines),
	}, nil
}

// statPayload builds the wire payload for a STAT command.
func statPayload(messageID string) []byte {
	return []byte("STAT <" + messageID + ">\r\n")
}

// parseStat maps a STAT Response to a StatResult. A 430/423 (article not found)
// is returned as ErrArticleNotFound with a nil result; callers doing bulk
// existence checks treat that as a normal miss rather than a fatal error.
func parseStat(messageID string, resp Response) (*StatResult, error) {
	if resp.Err != nil {
		return nil, resp.Err
	}
	if err := toError(resp.StatusCode, resp.Status); err != nil {
		return nil, err
	}

	result := &StatResult{MessageID: messageID}
	if resp.Request != nil {
		result.Provider = resp.Request.providerName
	}

	// Parse "223 <number> <message-id>" from the status line.
	parts := strings.SplitN(resp.Status, " ", 4)
	if len(parts) >= 2 {
		result.Number, _ = strconv.ParseInt(parts[1], 10, 64)
	}
	if len(parts) >= 3 {
		result.MessageID = strings.Trim(parts[2], "<>")
	}

	return result, nil
}

// Stat checks whether an article exists without transferring its contents.
func (c *Client) Stat(ctx context.Context, messageID string) (*StatResult, error) {
	return parseStat(messageID, <-c.Send(ctx, statPayload(messageID), nil))
}

// StatPriority is like Stat but enqueues on the priority channel so idle
// connections pick it up before normal requests. Useful for a latency-sensitive
// existence check that must not queue behind a large BODY on a busy connection.
func (c *Client) StatPriority(ctx context.Context, messageID string) (*StatResult, error) {
	return parseStat(messageID, <-c.SendPriority(ctx, statPayload(messageID), nil))
}

// StatBackground is Stat on the background lane (see BodyBackground). For
// existence checks nobody is waiting on; StatMany with
// StatManyOptions.Background sweeps many at once.
func (c *Client) StatBackground(ctx context.Context, messageID string) (*StatResult, error) {
	return parseStat(messageID, <-c.SendBackground(ctx, statPayload(messageID), nil))
}

// StatAsync returns a channel that will receive exactly one StatManyResult,
// mirroring BodyAsync. It preserves the fan-out pattern used by BodyAsync so a
// caller can dispatch many existence checks and collect them concurrently. For
// checking a slice of message-IDs prefer StatMany, which bounds concurrency.
func (c *Client) StatAsync(ctx context.Context, messageID string) <-chan StatManyResult {
	ch := make(chan StatManyResult, 1)
	go func() {
		res, err := c.Stat(ctx, messageID)
		ch <- StatManyResult{MessageID: messageID, Result: res, Err: err}
		close(ch)
	}()
	return ch
}

// doBody is the shared implementation for Body, BodyStream, and BodyAsync.
// When w is nil, decoded bytes are buffered in the Response.Body field.
func (c *Client) doBody(ctx context.Context, messageID string, w io.Writer, onMeta func(YEncMeta)) (*ArticleBody, error) {
	payload := []byte("BODY <" + messageID + ">\r\n")
	var respCh <-chan Response
	if onMeta != nil {
		respCh = c.Send(ctx, payload, w, onMeta)
	} else {
		respCh = c.Send(ctx, payload, w)
	}
	return c.finishBody(messageID, w, respCh)
}

// finishBody waits on respCh and builds the ArticleBody result.
func (c *Client) finishBody(messageID string, w io.Writer, respCh <-chan Response) (*ArticleBody, error) {
	resp := <-respCh
	if resp.Err != nil {
		return nil, resp.Err
	}
	if err := toError(resp.StatusCode, resp.Status); err != nil {
		return nil, err
	}

	body := &ArticleBody{
		MessageID:     messageID,
		BytesDecoded:  resp.Meta.BytesDecoded,
		BytesConsumed: resp.Meta.BytesConsumed,
		Encoding:      mapFormat(resp.Meta.Format),
		YEnc:          resp.Meta.YEnc,
		CRC:           resp.Meta.CRC,
		ExpectedCRC:   resp.Meta.ExpectedCRC,
	}
	body.CRCValid = body.ExpectedCRC != 0 && body.CRC == body.ExpectedCRC

	// When w was nil, the decoded bytes were buffered in resp.Body.
	if w == nil {
		buf := resp.Body.Bytes()
		if len(buf) > 0 {
			body.byteBuf = buf
		}
	}

	// Return both the body and a CRC error so callers get data but are warned.
	if body.ExpectedCRC != 0 && body.CRC != body.ExpectedCRC {
		return body, ErrCRCMismatch
	}

	return body, nil
}

// mapFormat converts from the internal rapidyenc.Format to the public ArticleEncoding.
func mapFormat(f rapidyenc.Format) ArticleEncoding {
	switch f {
	case rapidyenc.FormatYenc:
		return EncodingYEnc
	case rapidyenc.FormatUU:
		return EncodingUU
	default:
		return EncodingUnknown
	}
}

// parseHeaders parses RFC 5322 header lines, resolving continuation lines
// (lines starting with whitespace are folded into the previous header value).
func parseHeaders(lines []string) map[string][]string {
	headers := make(map[string][]string)
	var currentKey string

	for _, line := range lines {
		if len(line) == 0 {
			continue
		}

		// Continuation line: starts with space or tab.
		if line[0] == ' ' || line[0] == '\t' {
			if currentKey != "" {
				values := headers[currentKey]
				if len(values) > 0 {
					values[len(values)-1] += " " + strings.TrimSpace(line)
				}
			}
			continue
		}

		// New header line.
		key, value, found := strings.Cut(line, ":")
		if !found {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		currentKey = key
		headers[key] = append(headers[key], value)
	}

	return headers
}

// PostYenc sends a yEnc-encoded article to the server using the NNTP POST command.
// The body is yEnc-encoded on the fly using the provided metadata. yEnc encoding
// avoids '.' at start of lines, so no dot-stuffing is needed.
// The body reader is consumed exactly once; on failure, the caller must retry
// with a fresh reader.
func (c *Client) PostYenc(ctx context.Context, headers PostHeaders, body io.Reader, meta rapidyenc.Meta) (*PostResult, error) {
	return c.postYenc(ctx, headers, body, meta, nil)
}

// PostYencTo sends a yEnc-encoded article to a specific provider using the
// NNTP POST command. The provider name matches the provider's configured Name
// (or its derived name when Name is empty) and may identify either a main or
// backup provider. The request does not fall back to another provider.
// The body reader is consumed exactly once; on failure, the caller must retry
// with a fresh reader.
func (c *Client) PostYencTo(ctx context.Context, provider string, headers PostHeaders, body io.Reader, meta rapidyenc.Meta) (*PostResult, error) {
	target := c.findGroup(provider)
	if target == nil {
		return nil, fmt.Errorf("nntp: provider %q not found", provider)
	}
	return c.postYenc(ctx, headers, body, meta, target)
}

func (c *Client) postYenc(ctx context.Context, headers PostHeaders, body io.Reader, meta rapidyenc.Meta, target *providerGroup) (*PostResult, error) {
	pr, pw := io.Pipe()
	// Unblocks the writer goroutine below if pr is never read (e.g. dispatch
	// never reaches a connection); no-op once the body has been read to EOF.
	defer func() { _ = pr.CloseWithError(errors.New("nntp: post abandoned")) }()
	go func() {
		var err error
		defer func() { _ = pw.CloseWithError(err) }()

		if _, err = headers.WriteTo(pw); err != nil {
			return
		}
		var enc *rapidyenc.Encoder
		enc, err = rapidyenc.NewEncoder(pw, meta)
		if err != nil {
			return
		}
		if _, err = io.Copy(enc, body); err != nil {
			return
		}
		if err = enc.Close(); err != nil {
			return
		}
		// rapidyenc's Close already terminates the last line with CRLF, so we
		// only need the dot-line terminator here. Writing "\r\n.\r\n" would
		// inject a stray blank line before the dot.
		_, err = pw.Write([]byte(".\r\n"))
	}()

	respCh := c.sendPost(ctx, pr, target)
	return c.finishPost(respCh)
}

// sendPost dispatches a POST request using the configured dispatch strategy
// so concurrent calls are spread across all provider connections.
// No backup fallback, no retry on protocol errors.
func (c *Client) sendPost(ctx context.Context, payloadBody io.Reader, target *providerGroup) <-chan Response {
	respCh := make(chan Response, 1)
	if ctx == nil {
		ctx = context.Background()
	}
	go c.doSendPost(ctx, payloadBody, target, respCh)
	return respCh
}

func (c *Client) doSendPost(ctx context.Context, payloadBody io.Reader, target *providerGroup, respCh chan Response) {
	defer close(respCh)

	mains := *c.mainGroups.Load()
	if target != nil {
		mains = []*providerGroup{target}
	}
	n := len(mains)
	if n == 0 {
		respCh <- Response{Err: errors.New("nntp: no main providers")}
		return
	}

	// Pick start index using the same dispatch strategy as normal requests so
	// concurrent POST calls are spread across all provider connections.
	var start int
	switch c.dispatch {
	case DispatchFIFO:
		for i, g := range mains {
			if g.gate.available.Load() > 0 {
				start = i
				break
			}
		}
	default: // DispatchRoundRobin
		// Sized to len(mains), unlike a fixed-size array — never overflows.
		cumWeights, totalW := dispatchWeights(mains, c.speedAware)
		if totalW == 0 {
			start = 0 // all providers quota-exceeded; let the loop below fail each
		} else {
			slot := int(c.nextIdx.Add(1) % uint64(totalW))
			start = sort.SearchInts(cumWeights, slot+1)
		}
	}

	var lastErr error
	for attempt := range n {
		idx := (start + attempt) % n
		g := mains[idx]
		innerCh := make(chan Response, 1)
		// attemptDeadline is what bounds the pre-first-byte read: with a
		// cancel-only caller context and a server that never answers the POST,
		// a deadline-less request would block the reader in Read forever.
		req := &Request{
			Ctx:             ctx,
			Payload:         []byte("POST\r\n"),
			RespCh:          innerCh,
			PayloadBody:     payloadBody,
			PostMode:        true,
			attemptDeadline: time.Now().Add(g.attemptTimeout()),
		}

		// Try hot channel first (non-blocking), then cold channel.
		select {
		case g.hotReqCh <- req:
		default:
			select {
			case <-c.ctx.Done():
				respCh <- Response{Err: c.ctx.Err()}
				return
			case <-ctx.Done():
				respCh <- Response{Err: ctx.Err()}
				return
			case <-g.ctx.Done():
				continue
			case g.reqCh <- req:
			}
		}

		// g.reqCh is buffered, so the send above can succeed with no
		// connection reading it (e.g. a stalled dial) — guard the receive on
		// ctx too, or it blocks forever. Abandoning innerCh here is safe: a
		// late write from the connection side is non-blocking and its close
		// is panic-safe (see safeClose in nntp.go).
		var resp Response
		var ok bool
		select {
		case resp, ok = <-innerCh:
		case <-c.ctx.Done():
			respCh <- Response{Err: c.ctx.Err()}
			return
		case <-ctx.Done():
			respCh <- Response{Err: ctx.Err()}
			return
		}
		if !ok {
			continue
		}
		if resp.Err != nil {
			lastErr = resp.Err
			continue
		}
		// Deliver whatever status we got (240, 440, 441, etc.).
		respCh <- resp
		return
	}

	if lastErr != nil {
		respCh <- Response{Err: fmt.Errorf("nntp: post failed: %w", lastErr)}
	} else {
		respCh <- Response{Err: errors.New("nntp: post failed: all providers exhausted")}
	}
}

// finishPost waits for the POST response and maps status codes to errors.
func (c *Client) finishPost(respCh <-chan Response) (*PostResult, error) {
	resp := <-respCh
	if resp.Err != nil {
		return nil, resp.Err
	}
	if err := toError(resp.StatusCode, resp.Status); err != nil {
		return nil, err
	}
	return &PostResult{
		StatusCode: resp.StatusCode,
		Status:     resp.Status,
	}, nil
}
