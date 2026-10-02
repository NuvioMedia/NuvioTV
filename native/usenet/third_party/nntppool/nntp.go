package nntppool

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

var ErrMaxConnections = errors.New("nntp: server max connections reached")
var ErrConnectionDied = errors.New("nntp: connection died")

// isConnectionDeathError reports whether err indicates the underlying
// connection failed at the transport layer (as opposed to a protocol-level
// response like 430/502, which is delivered via StatusCode). These are
// retryable on a fresh connection: an established connection that goes stale
// surfaces ErrConnectionDied via failOutstanding, while a connection that dies
// on its bootstrap request surfaces the raw IO error (EOF, closed pipe, reset,
// timeout). Both mean "this socket is gone — open a new one."
func isConnectionDeathError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, ErrConnectionDied) ||
		errors.Is(err, io.EOF) ||
		errors.Is(err, io.ErrUnexpectedEOF) ||
		errors.Is(err, io.ErrClosedPipe) ||
		errors.Is(err, net.ErrClosed) {
		return true
	}
	var netErr net.Error
	return errors.As(err, &netErr)
}

const (
	// inflightDrainTimeout is the maximum time to wait for in-flight
	// responses to complete during idle disconnect.
	inflightDrainTimeout = 10 * time.Second

	// defaultThrottleRestore is the default duration before restoring
	// throttled connection slots after a server "max connections" error.
	defaultThrottleRestore = 30 * time.Second

	// connFailureBackoff is the delay before retrying after a connection
	// factory error.
	connFailureBackoff = time.Second

	// maxConnsBackoff is the longer delay used when the server reports
	// max connections reached (502/400).
	maxConnsBackoff = 5 * time.Second

	// defaultKeepAlive is the TCP keep-alive interval used when the
	// provider does not specify one. Negative disables keep-alive.
	defaultKeepAlive = 30 * time.Second

	// defaultHandshakeTimeout caps the TCP dial + TLS handshake phase
	// to avoid hanging against unresponsive servers.
	defaultHandshakeTimeout = 10 * time.Second

	// maxConnDiedRetries bounds same-provider retries when a pooled connection
	// dies mid-request (typically a stale socket the server already closed).
	// The dead connection has drained by the time the error surfaces, so the
	// retry uses a fresh connection on the same provider.
	maxConnDiedRetries = 2

	// escalationFactor sizes the escalated pass after every attempted provider
	// expired awaiting its first response byte (the slow-spool signature): the
	// whole pass shares one wall-clock budget of escalationFactor × the widest
	// window that expired, capped at maxAttemptTimeout — so escalation adds a
	// bounded, provider-count-independent amount of patience to a request,
	// never a multiple of it. At the 2s adaptive floor that is 8s, which
	// clears real-world slow spool lookups (~7.5s to a 430 for aged articles)
	// without any new configuration; an explicit sub-second
	// Provider.AttemptTimeout escalates proportionately instead of jumping to
	// the cap. A provider whose escalated window could not exceed the window
	// it already expired at is skipped, so an explicit Provider.AttemptTimeout
	// at or above maxAttemptTimeout makes escalation a true no-op.
	escalationFactor = 4

	// escalationBreakerThreshold is how many consecutive fruitless escalations a
	// provider may cost before escalation is suppressed for it.
	escalationBreakerThreshold = 2

	// minAttemptTimeout is the floor (and default) for the per-attempt timeout
	// that bounds dispatch + time-to-first-response-byte. Once response bytes
	// start flowing, the rolling stall timeout takes over instead.
	minAttemptTimeout = 2 * time.Second

	// maxAttemptTimeout caps the adaptive per-attempt timeout derived from a
	// provider's measured round-trip time.
	maxAttemptTimeout = 10 * time.Second

	// defaultStallTimeout is the rolling progress deadline applied to a body
	// transfer once bytes are flowing: if no further bytes arrive within this
	// window the connection is considered stalled and torn down. A healthy but
	// slow transfer keeps extending the deadline and never trips it.
	defaultStallTimeout = 8 * time.Second

	// stallDeadlineQuantum coarsens stall-deadline updates so the read path
	// issues at most one SetReadDeadline syscall per quantum instead of one per
	// read.
	stallDeadlineQuantum = 250 * time.Millisecond

	// defaultStreamInflight is the priority-lane body cap per connection when
	// Provider.StreamInflight is unset: deep enough to pipeline, shallow
	// enough that a demand read waits behind at most three stream bodies.
	defaultStreamInflight = 4

	// defaultAbortDrainBytes is the remaining-body size above which an
	// abandoned drain is cut by closing the connection. A 750 KB article
	// finishes draining in less time than a reconnect; a 4 MiB one does not.
	defaultAbortDrainBytes int64 = 1 << 20
)

// Attempt lifecycle states, coordinating the race between tryGroup's attempt
// timer and the reader observing the first response byte. The CAS handshake
// guarantees exactly one of them "wins": if the reader commits first the
// attempt is never abandoned mid-stream; if the timer fires first the attempt
// fails over and the reader drops the (cancelled) request.
const (
	attemptPending   int32 = iota // no response byte seen yet
	attemptCommitted              // reader saw the first response byte
	attemptAbandoned              // tryGroup timed out before any byte arrived
)

type greetingError struct {
	StatusCode int
	Message    string
}

func (e *greetingError) Error() string {
	return fmt.Sprintf("nntp greeting: %d %s", e.StatusCode, e.Message)
}

func (e *greetingError) Is(target error) bool {
	return target == ErrMaxConnections && (e.StatusCode == 502 || e.StatusCode == 400)
}

// authResponseError preserves the NNTP status code returned during AUTHINFO.
// Some providers report an account connection ceiling here rather than in the
// initial greeting, so callers must be able to match ErrMaxConnections in both
// places.
type authResponseError struct {
	Command    string
	StatusCode int
	Message    string
}

func (e *authResponseError) Error() string {
	return fmt.Sprintf("nntp auth: unexpected response to %s: %s", e.Command, e.Message)
}

func (e *authResponseError) Is(target error) bool {
	return target == ErrMaxConnections && (e.StatusCode == 502 || e.StatusCode == 400)
}

type Request struct {
	Ctx context.Context

	Payload []byte
	RespCh  chan Response

	// Optional: decoded body bytes are streamed here. If nil, they are buffered into Response.Body.
	BodyWriter io.Writer

	// Optional: called with yEnc metadata once =ybegin/=ypart headers are parsed, before body decoding.
	OnMeta func(YEncMeta)

	// PayloadBody is an optional reader streamed to the connection after Payload.
	// Used by POST to stream article content without buffering in memory.
	PayloadBody io.Reader

	// PostMode signals readerLoop to expect two NNTP responses (340 + 240/441).
	PostMode bool

	// postReadyCh is set by writeLoop for PostMode requests. The readerLoop
	// sends nil after reading 340 (proceed to write body) or a non-nil error
	// otherwise (e.g. 440 posting not allowed). Buffered with capacity 1.
	postReadyCh chan error

	// attemptDeadline bounds dispatch + time-to-first-response-byte for this
	// attempt. Zero means no such bound (e.g. POST and keepalive requests).
	// Once the reader sees the first byte the attempt is committed and this
	// deadline no longer applies — the connection's rolling stall timeout does.
	attemptDeadline time.Time

	// attemptWindow is the duration attemptDeadline was derived from. The
	// reader re-anchors it at the moment it starts draining this request's
	// response (drain start + attemptWindow), so time spent queued behind
	// other pipelined responses never counts against the TTFB bound —
	// dispatch-anchored read deadlines let one deep request burst expire the
	// whole pipeline and tear the connection down. Zero falls back to
	// attemptDeadline as-is.
	attemptWindow time.Duration

	// attemptState is one of attemptPending/attemptCommitted/attemptAbandoned.
	// The reader CASes pending→committed on the first response byte; tryGroup's
	// timer CASes pending→abandoned on expiry. Zero value is attemptPending.
	attemptState atomic.Int32

	// sentAt is the Unix-nanosecond timestamp at which the payload was handed
	// to the connection, used to measure time-to-first-byte. 0 = unset (the
	// request is not measured, e.g. POST/keepalive).
	sentAt atomic.Int64

	// heldBody is set by writeLoop when this (body-bearing) request acquired a
	// bodySem slot, so readerLoop releases exactly the slots that were taken.
	// Bodyless STAT requests never acquire bodySem and leave this false.
	heldBody bool
	// heldPrio is set when the request also took a prioBodySem slot.
	heldPrio bool
	// lane is the request lane this attempt was dispatched on; see lane.
	lane lane
	// heldBg is set once a background-lane request is on the wire and has
	// been counted in its provider group's bgInflight, so the reader (or the
	// pending drain) releases exactly what was counted.
	heldBg bool

	// providerName identifies the provider group selected for this attempt.
	providerName string
}

// lane is which of the three request queues an attempt is dispatched on.
// Every connection writer reads them in strict preference order: priority,
// then normal, then background. Priority is for reads something is blocked
// on (a player waiting for the next article); normal is the default; background
// is for work nobody is waiting on — a PAR2 repair reading a whole release, or
// the liveness census that prices it — which may use every idle connection
// but must never queue ahead of the other two.
type lane uint8

const (
	laneNormal lane = iota
	lanePriority
	laneBackground
)

// isPriority reports whether the request rides the priority lane.
func (r *Request) isPriority() bool { return r.lane == lanePriority }

// escalated is the lane a retry after a 430 rides: a normal request steps up
// to priority so the failover to the next provider is not queued behind
// read-ahead, while background work stays background — nobody is waiting on
// it, so it has no claim to jump the queue however many providers it visits.
func (l lane) escalated(post430 bool) lane {
	if post430 && l == laneNormal {
		return lanePriority
	}
	return l
}

type Response struct {
	StatusCode int
	Status     string

	// For non-body multiline responses (CAPABILITIES, etc).
	Lines []string

	// Decoded payload bytes (only if Request.BodyWriter == nil).
	Body bytes.Buffer

	// Decoder metadata/status gathered while parsing.
	Meta NNTPResponse

	Err     error
	Request *Request
}

type Auth struct {
	Username string
	Password string
}

// ConnFactory is used by Client to create connections.
type ConnFactory func(ctx context.Context) (net.Conn, error)

type NNTPConnection struct {
	conn net.Conn

	ctx    context.Context
	cancel context.CancelFunc

	reqCh         <-chan *Request
	prioCh        <-chan *Request // priority channel; nil for standalone connections
	hotReqCh      <-chan *Request // unbuffered; set by runConnSlot before Run()
	hotPrioCh     <-chan *Request // unbuffered; set by runConnSlot before Run()
	hotIdleBodyCh <-chan *Request // unbuffered; read ONLY while this connection has no body in flight
	bgCh          <-chan *Request // background lane; read only when priority and normal are empty (see bgLane)
	pending       chan *Request

	// bgPending counts background-lane requests this connection has written
	// and not yet seen replied. While it is non-zero the connection is
	// dedicated to background work: it reads no foreground lane, so a stream
	// body never queues behind pipelined background STATs (seconds each for a
	// missing article on some providers). Written by the writer, released by
	// the reader.
	bgPending atomic.Int32

	// wake is poked by the reader whenever a reply completes, so a writer
	// parked with lanes disarmed (pipeline busy, or dedicated to background)
	// re-evaluates them the moment the pipeline changes instead of waiting
	// for an unrelated request or a timer.
	wake chan struct{}

	// inflightSem bounds the total pipeline depth (cap = StatInflight, i.e.
	// max(Inflight, StatInflight)). bodySem additionally bounds concurrent
	// body-bearing commands (cap = Inflight) so raising the STAT pipeline depth
	// never increases the number of BODY responses buffered/streamed at once.
	// Bodyless STAT commands acquire only inflightSem and so pipeline to the
	// deeper StatInflight depth.
	inflightSem chan struct{}
	bodySem     chan struct{}
	// prioBodySem additionally bounds priority-lane bodies (cap =
	// StreamInflight); nil when the cap is disabled.
	prioBodySem     chan struct{}
	abortDrainBytes int64 // see Provider.AbortDrainBytes; 0 = never abort

	rb readBuffer

	Greeting NNTPResponse

	firstReq          *Request      // bootstrap request from connection slot
	idleTimeout       time.Duration // 0 = no idle timeout
	stallTimeout      time.Duration // rolling body-progress deadline; 0 = disabled
	keepaliveInterval time.Duration // 0 = no keepalive
	keepaliveCommand  string        // NNTP command for keepalive probe (e.g. "DATE")
	providerName      string        // set by runConnSlot; used for error context
	userAgent         string

	stats *providerStats // nil for standalone connections

	done   chan struct{}
	doneMu sync.Once

	failMu sync.Once
}

func newNetConn(ctx context.Context, addr string, tlsConfig *tls.Config, keepAlive time.Duration) (net.Conn, error) {
	if keepAlive == 0 {
		keepAlive = defaultKeepAlive
	}
	ctx, cancel := context.WithTimeout(ctx, defaultHandshakeTimeout)
	defer cancel()
	dialer := net.Dialer{KeepAlive: keepAlive}
	if tlsConfig != nil {
		conn, err := dialer.DialContext(ctx, "tcp", addr)
		if err != nil {
			return nil, err
		}
		tlsConn := tls.Client(conn, tlsConfig)
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			_ = conn.Close()
			return nil, err
		}
		return tlsConn, nil
	}
	conn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	return conn, nil
}

func newNNTPConnectionFromConn(ctx context.Context, conn net.Conn, inflightLimit int, reqCh <-chan *Request, prioCh <-chan *Request, auth Auth, userAgent string, sharedBuf *readBuffer, stats *providerStats) (*NNTPConnection, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	cctx, cancel := context.WithCancel(ctx)

	var rb readBuffer
	if sharedBuf != nil && len(sharedBuf.buf) > 0 {
		// Reuse the buffer from a previous connection, reset read positions and deadline cache.
		rb = readBuffer{buf: sharedBuf.buf}
	} else {
		rb = readBuffer{buf: make([]byte, defaultReadBufSize)}
	}

	c := &NNTPConnection{
		conn:        conn,
		ctx:         cctx,
		cancel:      cancel,
		reqCh:       reqCh,
		prioCh:      prioCh,
		pending:     make(chan *Request, inflightLimit),
		inflightSem: make(chan struct{}, inflightLimit),
		wake:        make(chan struct{}, 1),
		// Default bodySem to the full pipeline depth (no separate BODY bound);
		// runConnSlot overrides this to Provider.Inflight when a deeper STAT
		// pipeline is configured. Standalone connections keep them equal.
		bodySem:   make(chan struct{}, inflightLimit),
		rb:        rb,
		stats:     stats,
		done:      make(chan struct{}),
		userAgent: userAgent,
	}

	// Server greeting is sent immediately upon connect.
	greeting, err := c.readOneResponse(io.Discard)
	if err != nil {
		return nil, fmt.Errorf("nntp greeting: %w", err)
	}
	c.Greeting = greeting
	if greeting.StatusCode != 200 && greeting.StatusCode != 201 {
		return nil, &greetingError{StatusCode: greeting.StatusCode, Message: greeting.Message}
	}

	// Optional AUTHINFO handshake.
	if auth.Username != "" {
		if auth.Password == "" {
			return nil, fmt.Errorf("nntp auth: password required when username is set")
		}

		if err := c.auth(auth); err != nil {
			return nil, err
		}
	}

	return c, nil
}

func NewNNTPConnection(ctx context.Context, addr string, tlsConfig *tls.Config, inflightLimit int, reqCh <-chan *Request, auth Auth, userAgent string) (*NNTPConnection, error) {
	conn, err := newNetConn(ctx, addr, tlsConfig, 0)
	if err != nil {
		return nil, err
	}

	c, err := newNNTPConnectionFromConn(ctx, conn, inflightLimit, reqCh, nil, auth, userAgent, nil, nil)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	return c, nil
}

func (c *NNTPConnection) auth(auth Auth) error {
	// AUTHINFO USER
	if _, err := fmt.Fprintf(c.conn, "AUTHINFO USER %s\r\n", auth.Username); err != nil {
		return fmt.Errorf("nntp auth: AUTHINFO USER: %w", err)
	}
	resp, err := c.readOneResponse(io.Discard)
	if err != nil {
		return fmt.Errorf("nntp auth: AUTHINFO USER: %w", err)
	}

	switch resp.StatusCode {
	case 281:
		return nil // authenticated
	case 381:
		// need pass
	default:
		return &authResponseError{Command: "AUTHINFO USER", StatusCode: resp.StatusCode, Message: resp.Message}
	}

	// AUTHINFO PASS
	if _, err := fmt.Fprintf(c.conn, "AUTHINFO PASS %s\r\n", auth.Password); err != nil {
		return fmt.Errorf("nntp auth: AUTHINFO PASS: %w", err)
	}
	resp, err = c.readOneResponse(io.Discard)
	if err != nil {
		return fmt.Errorf("nntp auth: AUTHINFO PASS: %w", err)
	}
	if resp.StatusCode != 281 {
		return &authResponseError{Command: "AUTHINFO PASS", StatusCode: resp.StatusCode, Message: resp.Message}
	}
	return nil
}

func (c *NNTPConnection) Done() <-chan struct{} { return c.done }

func (c *NNTPConnection) closeDone() {
	c.doneMu.Do(func() { close(c.done) })
}

func safeClose[T any](ch chan T) {
	defer func() { _ = recover() }()
	close(ch)
}

// keepaliveProbeTimeout bounds a keepalive probe's round trip. The probe is a
// trivial command (DATE/HELP), so the connection's stall timeout is ample as a
// time-to-first-byte bound; with stall disabled, a fixed 30s fallback still
// guarantees the probe can never park the connection forever.
func (c *NNTPConnection) keepaliveProbeTimeout() time.Duration {
	if c.stallTimeout > 0 {
		return c.stallTimeout
	}
	return 30 * time.Second
}

// keepaliveExpectedCode returns the expected NNTP status code for the given
// keepalive command: DATE→111, HELP→100, CAPABILITIES→101, default→111.
func keepaliveExpectedCode(cmd string) int {
	switch cmd {
	case "HELP":
		return 100
	case "CAPABILITIES":
		return 101
	default:
		return 111
	}
}

// statCmdPrefix identifies STAT commands, the only bodyless request the pool
// issues through the normal write path (keepalive DATE has its own path). STAT
// has a single-line reply and no payload, so it may pipeline to the deeper
// StatInflight depth without acquiring a bodySem slot.
var statCmdPrefix = []byte("STAT ")

// isCheapCommand reports whether payload is a bodyless command that should
// bypass the BODY concurrency bound (bodySem) and pipeline to the full
// inflightSem (StatInflight) depth.
func isCheapCommand(payload []byte) bool {
	return bytes.HasPrefix(payload, statCmdPrefix)
}

// tryNextRequest performs a non-blocking receive across the request channels,
// probing idleBodyChan() first and then, among the four request lanes, in
// strict preference order: hot priority, cold priority, hot normal, cold
// normal. Priority always outranks normal — including a cold priority request
// over a hot normal one, because a stream waiting on a body cares far more
// about being dispatched at all than about landing on an already-warm
// connection.
//
// The idleBodyChan() probe here can never actually fire: a send to it (see
// tryGroupTimeout) is itself a non-blocking select with a default case, so it
// only succeeds against a receiver that is genuinely parked (blocked, not
// polling) on the channel — which this probe, by construction, never is. It
// stays for symmetry with the same case in the writer's blocking select below,
// where the real hand-off happens; it is harmless here, just unreachable.
//
// Each lane is its own non-blocking select rather than one combined select,
// because Go chooses uniformly among ready cases: a single select over prioCh
// and reqCh makes "priority" a coin flip exactly when both lanes are busy.
// Receives from nil channels are never ready, so the standalone path (prioCh and
// the hot channels nil) probes reqCh alone.
//
// got reports whether any channel was ready; ok is false when the channel that
// fired was closed.
func (c *NNTPConnection) tryNextRequest() (req *Request, ok, got bool) {
	if c.bgPending.Load() > 0 {
		select {
		case req, ok = <-c.bgLane():
			return req, ok, true
		default:
		}
		return nil, false, false
	}
	select {
	case req, ok = <-c.idleBodyChan():
		return req, ok, true
	default:
	}
	hotPrio, coldPrio := c.prioLanes()
	select {
	case req, ok = <-hotPrio:
		return req, ok, true
	default:
	}
	select {
	case req, ok = <-coldPrio:
		return req, ok, true
	default:
	}
	select {
	case req, ok = <-c.hotReqCh:
		return req, ok, true
	default:
	}
	select {
	case req, ok = <-c.reqCh:
		return req, ok, true
	default:
	}
	select {
	case req, ok = <-c.bgLane():
		return req, ok, true
	default:
	}
	return nil, false, false
}

// bgLane returns the background lane this connection may read from, or nil
// (never ready in a select) when it must not take background work right now.
func (c *NNTPConnection) bgLane() <-chan *Request {
	// Background work lands only on an empty pipeline — or behind other
	// background work already pipelined here. Behind a foreground body it
	// would inherit that body's transfer time, and the connection could not
	// be dedicated (see bgPending) without stalling the body's owner.
	if c.bgPending.Load() == 0 && !c.pipelineIdle() {
		return nil
	}
	return backgroundLaneFor(c.stats, c.bgCh)
}

// pipelineIdle reports whether nothing is outstanding on the wire: no body in
// flight and no pipelined reply owed. The writer holds one inflightSem slot of
// its own while it chooses a request, hence <= 1.
func (c *NNTPConnection) pipelineIdle() bool {
	return len(c.bodySem) == 0 && len(c.inflightSem) <= 1
}

// foregroundLanes returns the lanes a connection may read besides background:
// all of them normally, none while it is dedicated to background work.
func (c *NNTPConnection) foregroundLanes() (idleBody, hotPrio, hotReq, coldPrio, req <-chan *Request) {
	if c.bgPending.Load() > 0 {
		return nil, nil, nil, nil, nil
	}
	hotPrio, coldPrio = c.prioLanes()
	return c.idleBodyChan(), hotPrio, c.hotReqCh, coldPrio, c.reqCh
}

// noteDispatched records a request that has just been written to the wire in
// the provider group's background gate: a background request is counted in
// flight until its reply is read (or the pending queue is drained); anything
// else stamps the group as having recent foreground traffic. Keepalive probes
// carry no lane and count as foreground, which is harmless — a connection
// idle long enough to probe is one the gate would open for anyway.
func (c *NNTPConnection) noteDispatched(req *Request) {
	if c.stats == nil {
		return
	}
	if req.lane == laneBackground {
		c.stats.bgInflight.Add(1)
		c.bgPending.Add(1)
		req.heldBg = true
		return
	}
	c.stats.lastForeground.Store(time.Now().UnixNano())
}

// pokeWriter tells a parked writer that the pipeline changed. Non-blocking:
// one pending poke is enough, the writer re-reads all state when it wakes.
func (c *NNTPConnection) pokeWriter() {
	if c.wake == nil {
		return
	}
	select {
	case c.wake <- struct{}{}:
	default:
	}
}

// releaseBackground undoes noteDispatched's count for a background request,
// exactly once.
func (c *NNTPConnection) releaseBackground(req *Request) {
	if req.heldBg && c.stats != nil {
		req.heldBg = false
		c.stats.bgInflight.Add(-1)
		c.bgPending.Add(-1)
	}
}

// backgroundYieldWindow is how long after the last priority- or normal-lane
// dispatch a provider group counts as contended. Playback and imports issue
// requests continuously, so while either runs the group stays contended and
// background work is held to its floor; once they stop, the floor lifts after
// this long and background may take every idle connection.
const backgroundYieldWindow = 3 * time.Second

// resolveBackgroundFloor applies Provider.BackgroundFloor's defaults and
// clamps: a quarter of the connections by default, at least one, and never
// the whole allowance (unless the allowance is a single connection).
func resolveBackgroundFloor(connections, floor int) int32 {
	if floor <= 0 {
		floor = connections / 4
	}
	if floor < 1 {
		floor = 1
	}
	if connections > 1 && floor > connections-1 {
		floor = connections - 1
	}
	return int32(floor)
}

// backgroundRecheckInterval is how often a parked writer whose background
// gate was closed re-evaluates it while background work is queued.
const backgroundRecheckInterval = 200 * time.Millisecond

// backgroundRecheck returns a timer channel when background work is queued on
// bgCh but the gate handed this writer a nil lane, and nil (never ready)
// otherwise. A writer parks with the gate's verdict frozen into its select, and
// the events that reopen the gate — a background reply completing, foreground
// going quiet — wake no writer by themselves; without this the queued work
// waits for an unrelated request or an idle timer.
func backgroundRecheck(bgCh, lane <-chan *Request) <-chan time.Time {
	if bgCh == nil || lane != nil || len(bgCh) == 0 {
		return nil
	}
	return time.After(backgroundRecheckInterval)
}

// backgroundLaneFor gates a provider group's background lane: the channel
// when this connection may take background work, nil (never ready in a
// select) when it may not.
//
// Nothing is reserved. When the group is idle — no foreground request written
// within backgroundYieldWindow — background may hold every connection. While
// the group is contended, background may keep only bgFloor requests in flight,
// so a stream request arriving finds at most that many connections stuck
// behind a background body. The floor is a minimum share, never a ceiling on
// an idle pool, and it is soft: several writers can pass the check together,
// so the count may briefly exceed the floor by the number of connections.
//
// Standalone connections (no stats) always read the lane.
func backgroundLaneFor(stats *providerStats, bgCh <-chan *Request) <-chan *Request {
	if stats == nil || bgCh == nil {
		return bgCh
	}
	last := stats.lastForeground.Load()
	if last == 0 || time.Since(time.Unix(0, last)) >= backgroundYieldWindow {
		return bgCh
	}
	if stats.bgInflight.Load() >= stats.bgFloor {
		return nil
	}
	return bgCh
}

// idleBodyChan returns the group's idle-body channel while this connection has
// nothing outstanding on the wire — no body-bearing request and no pipelined
// reply still owed — and nil otherwise. A nil channel is never ready in a
// select, so a connection whose reader is busy simply does not compete for
// priority bodies — which is the whole point: NNTP replies are FIFO per
// connection, so a priority body queued behind anything waits for all of it.
//
// This only changes the outcome when inflightSem's cap (StatInflight, i.e.
// max(Inflight, StatInflight)) exceeds the number of body slots a busy
// connection is holding — i.e. Inflight >= 2, or StatInflight > Inflight. At
// nntppool's own defaults (Inflight 1, StatInflight 0, so cap == 1) a
// body-draining connection's writer has no spare inflightSem slot at all: it
// parks at "c.inflightSem <- struct{}{}" in the writer loop, invisible to
// every request lane including this one, and this method is never even
// reached for it. This method only has anything to say about a connection
// that still has pipeline room while something is in flight.
//
// The pending-reply check matters because a bodyless STAT (which takes only
// inflightSem, not bodySem — see isCheapCommand) can have up to StatInflight
// replies pipelined ahead of a request steered here. That wait is cheap only
// when the server answers fast, and for a missing article it does not:
// Newshosting takes ~1.2 s per 430 and serialises them per connection, so a
// liveness sweep over a mostly-dead release parks every connection it touches
// for seconds — and, being body-free, those were precisely the connections a
// bodySem-only check steered priority bodies onto (observed live as playback
// falling from ~70 MB/s to ~5 MB/s beside such a sweep).
//
// The writer calls this while holding one inflightSem slot of its own (it
// acquires capacity before choosing a request), so an empty pipeline reads as
// len(inflightSem) <= 1. Both len() reads are the right signal (rather than
// the caps) because readerLoop releases a slot only after the full reply has
// been read and delivered; the window where the writer holds a slot but the
// reader has not started counts as busy — the safe side.
func (c *NNTPConnection) idleBodyChan() <-chan *Request {
	if len(c.bodySem) != 0 || len(c.inflightSem) > 1 {
		return nil
	}
	return c.hotIdleBodyCh
}

// prioLanes returns the priority request channels this connection may read.
//
// Full (StreamInflight priority bodies held): neither. The writer acquires
// prioBodySem only after it has a request in hand, so without this a full
// connection would keep receiving priority requests and park each behind its
// pipeline while other slots, idle ones that would dial included, saw nothing.
//
// Busy (at least one priority body in flight): the cold lane only. The client
// offers every request to the hot lane first, and a hot connection with room
// would otherwise deepen its own pipeline while undialed slots stayed idle: at
// a 15-connection budget only 8-12 sockets ever carried bytes. Leaving the hot
// lane makes the request go cold, where idle slots compete for it and dial;
// pipelines deepen only once every slot is busy.
func (c *NNTPConnection) prioLanes() (hot, cold <-chan *Request) {
	if c.prioBodySem == nil {
		return c.hotPrioCh, c.prioCh
	}
	switch held := len(c.prioBodySem); {
	case held >= cap(c.prioBodySem):
		return nil, nil
	case held > 0:
		return nil, c.prioCh
	}
	return c.hotPrioCh, c.prioCh
}

func failRequest(ch chan Response, err error) {
	defer func() { _ = recover() }()
	select {
	case ch <- Response{Err: err}:
	default:
	}
	close(ch)
}

func (c *NNTPConnection) failOutstanding() {
	c.failMu.Do(c.drainPending)
}

// connDiedErr is the connection-death error this connection reports, carrying
// the provider prefix that isConnectionDeathError and every errors.Is consumer
// downstream depend on. One definition so the wrapping cannot drift.
func (c *NNTPConnection) connDiedErr() error {
	if c.providerName == "" {
		return ErrConnectionDied
	}
	return fmt.Errorf("%s: %w", c.providerName, ErrConnectionDied)
}

// drainPending fails every request sitting in the pending queue. Deliberately
// callable OUTSIDE failOutstanding's once-guard: the writer loop can accept a
// request from a hot channel and enqueue it into pending AFTER the dying
// reader's failOutstanding sweep already ran — the once had fired, so the
// request was stranded silently and only the caller's attempt window ever
// recovered it. The writer's flush-error exits drain for themselves instead.
// Concurrent drains are safe: each request is received by exactly one, and
// failRequest tolerates an already-closed channel.
func (c *NNTPConnection) drainPending() {
	if len(c.pending) == 0 {
		return // nothing stranded; skip building the wrapped error
	}
	connErr := c.connDiedErr()
	for {
		select {
		case req := <-c.pending:
			if req == nil {
				continue
			}
			failRequest(req.RespCh, connErr)
			c.releaseBackground(req)
			// Best-effort inflight release (not strictly needed once we're shutting down).
			select {
			case <-c.inflightSem:
			default:
			}
		default:
			return
		}
	}
}

func (c *NNTPConnection) Close() error {
	c.cancel()
	_ = c.conn.Close()
	<-c.done
	return nil
}

// waitForInflightDrain acquires all semaphore slots, blocking until each
// in-flight response completes. This ensures a clean idle disconnect with
// no lost requests. A 10s timeout prevents hanging if the server stops
// responding mid-response.
func (c *NNTPConnection) waitForInflightDrain() {
	timer := time.NewTimer(inflightDrainTimeout)
	defer timer.Stop()
	for range cap(c.inflightSem) {
		select {
		case c.inflightSem <- struct{}{}:
		case <-c.ctx.Done():
			return
		case <-timer.C:
			return
		}
	}
}

// connGate controls how many connection slots may be connecting/running
// simultaneously within a single provider. When the server returns a
// "max connections" greeting (502/400), throttle() reduces the allowed
// count to the number of currently running connections (min 1) and starts
// a restore timer.
type connGate struct {
	mu           sync.Mutex
	cond         *sync.Cond
	maxSlots     int // original p.Connections
	allowed      int // current limit (reduced during throttle)
	held         int // slots past enter() (connecting + running)
	running      int // slots inside nc.Run()
	restoreTimer *time.Timer
	restoreDur   time.Duration
	available    atomic.Int32 // allowed - held; updated under mu, read lock-free
}

func newConnGate(max int, restoreDur time.Duration) *connGate {
	if restoreDur <= 0 {
		restoreDur = defaultThrottleRestore
	}
	g := &connGate{
		maxSlots:   max,
		allowed:    max,
		restoreDur: restoreDur,
	}
	g.cond = sync.NewCond(&g.mu)
	g.available.Store(int32(max))
	return g
}

// enter blocks until held < allowed or one of the contexts is cancelled.
// Returns true if the slot was granted.
func (g *connGate) enter(slotCtx, reqCtx context.Context) bool {
	// Spin up a goroutine that broadcasts on context cancellation so
	// cond.Wait() can re-check.
	done := make(chan struct{})
	go func() {
		select {
		case <-slotCtx.Done():
		case <-reqCtx.Done():
		case <-done:
		}
		g.cond.Broadcast()
	}()

	g.mu.Lock()
	defer g.mu.Unlock()
	defer close(done)

	for g.held >= g.allowed {
		if slotCtx.Err() != nil || reqCtx.Err() != nil {
			return false
		}
		g.cond.Wait()
	}
	g.held++
	g.available.Store(int32(g.allowed - g.held))
	return true
}

func (g *connGate) exit() {
	g.mu.Lock()
	g.held--
	g.available.Store(int32(g.allowed - g.held))
	g.mu.Unlock()
	g.cond.Broadcast()
}

func (g *connGate) markRunning() {
	g.mu.Lock()
	g.running++
	g.mu.Unlock()
}

func (g *connGate) markNotRunning() {
	g.mu.Lock()
	g.running--
	g.mu.Unlock()
}

// throttle reduces allowed slots to max(1, running) and resets the restore timer.
func (g *connGate) throttle() {
	g.mu.Lock()
	defer g.mu.Unlock()

	newAllowed := max(1, g.running)
	// Only tighten, never loosen during throttle.
	if newAllowed < g.allowed {
		g.allowed = newAllowed
	}

	// Reset (or start) the restore timer.
	if g.restoreTimer != nil {
		g.restoreTimer.Stop()
	}
	g.restoreTimer = time.AfterFunc(g.restoreDur, g.restore)
	g.available.Store(int32(g.allowed - g.held))
}

func (g *connGate) restore() {
	g.mu.Lock()
	g.allowed = g.maxSlots
	g.restoreTimer = nil
	g.available.Store(int32(g.allowed - g.held))
	g.mu.Unlock()
	g.cond.Broadcast()
}

func (g *connGate) stop() {
	g.mu.Lock()
	if g.restoreTimer != nil {
		g.restoreTimer.Stop()
		g.restoreTimer = nil
	}
	g.mu.Unlock()
	g.cond.Broadcast()
}

func (g *connGate) snapshot() (maxSlots, running int) {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.maxSlots, g.running
}

// runConnSlot is the slot goroutine that manages the lifecycle of a single
// connection: IDLE → CONNECTING → ACTIVE → (death/idle) → IDLE.
//
// preWarm slots skip the IDLE wait and dial immediately (and again
// immediately on death), giving the provider a floor of connections that
// stay hot independent of traffic. Callers pair preWarm with idleTimeout==0
// so these connections are never torn down for being idle.
func runConnSlot(ctx context.Context, reqCh <-chan *Request, prioCh <-chan *Request, hotReqCh <-chan *Request, hotPrioCh <-chan *Request, hotIdleBodyCh <-chan *Request, bgCh <-chan *Request, factory ConnFactory, inflight int, statInflight int, streamInflight int, abortDrainBytes int64, auth Auth, userAgent string, idleTimeout time.Duration, stallTimeout time.Duration, keepaliveInterval time.Duration, keepaliveCommand string, gate *connGate, stats *providerStats, providerName string, wg *sync.WaitGroup, preWarm bool) {
	defer wg.Done()

	// Shared read buffer persists across reconnections to avoid re-growing.
	var sharedBuf readBuffer

	for {
		// preWarm slots have no request to wait on, so unlike the cold path
		// below they never block on a select that observes ctx.Done(). Check
		// explicitly here so a cancelled group context stops the goroutine
		// instead of spinning through gate.enter on every loop iteration.
		if preWarm {
			select {
			case <-ctx.Done():
				return
			default:
			}
		}

		// IDLE: wait for a request (zero TCP resources) — unless this is a
		// pre-warmed slot, which dials immediately without waiting for real
		// traffic so a minimum number of connections stay hot.
		var firstReq *Request
		var ok bool
		gateCtx := ctx
		if !preWarm {
			// Prefer priority requests over normal ones.
			bgLane := backgroundLaneFor(stats, bgCh)
			select {
			case firstReq, ok = <-prioCh:
				if !ok {
					return
				}
			default:
				select {
				case firstReq, ok = <-prioCh:
					if !ok {
						return
					}
				case firstReq, ok = <-reqCh:
					if !ok {
						return // channel closed, shut down
					}
				case firstReq, ok = <-bgLane:
					if !ok {
						return
					}
				case <-backgroundRecheck(bgCh, bgLane):
					continue
				case <-ctx.Done():
					return
				}
			}
			gateCtx = firstReq.Ctx

			// Check if the request is already cancelled.
			select {
			case <-firstReq.Ctx.Done():
				failRequest(firstReq.RespCh, firstReq.Ctx.Err())
				continue
			default:
			}
		}

		// GATE: block if we're at the throttled capacity limit.
		if !gate.enter(ctx, gateCtx) {
			// Slot or request context cancelled while waiting at the gate.
			if firstReq != nil {
				failRequest(firstReq.RespCh, context.Canceled)
			}
			continue
		}

		// CONNECTING: dial, greet, authenticate.
		conn, err := factory(ctx)
		if err != nil {
			gate.exit()
			if firstReq != nil {
				failRequest(firstReq.RespCh, fmt.Errorf("%s: %w", providerName, err))
			}
			// Backoff before retrying to avoid thrashing.
			select {
			case <-time.After(connFailureBackoff):
			case <-ctx.Done():
				return
			}
			continue
		}

		// Size the pipeline (inflightSem/pending) to statInflight so bodyless
		// STAT commands can pipeline deep; bodySem is overridden below to the
		// (smaller) Inflight so concurrent bodies stay bounded.
		nc, err := newNNTPConnectionFromConn(ctx, conn, statInflight, reqCh, prioCh, auth, userAgent, &sharedBuf, stats)
		if err != nil {
			_ = conn.Close()
			if firstReq != nil {
				failRequest(firstReq.RespCh, fmt.Errorf("%s: %w", providerName, err))
			}

			if errors.Is(err, ErrMaxConnections) {
				// Server said "max connections" — throttle and use longer backoff.
				gate.throttle()
				gate.exit()
				select {
				case <-time.After(maxConnsBackoff):
				case <-ctx.Done():
					return
				}
			} else {
				gate.exit()
				select {
				case <-time.After(connFailureBackoff):
				case <-ctx.Done():
					return
				}
			}
			continue
		}

		// ACTIVE: run the connection with the bootstrap request.
		// Bound concurrent bodies to Inflight while the pipeline (inflightSem)
		// allows STAT to reach statInflight. When statInflight == inflight this
		// is identical to the default (both caps equal).
		nc.bodySem = make(chan struct{}, inflight)
		if streamInflight > 0 {
			nc.prioBodySem = make(chan struct{}, streamInflight)
		}
		nc.abortDrainBytes = abortDrainBytes
		nc.firstReq = firstReq
		nc.idleTimeout = idleTimeout
		nc.stallTimeout = stallTimeout
		nc.providerName = providerName
		nc.hotReqCh = hotReqCh
		nc.hotPrioCh = hotPrioCh
		nc.hotIdleBodyCh = hotIdleBodyCh
		nc.bgCh = bgCh
		nc.keepaliveInterval = keepaliveInterval
		nc.keepaliveCommand = keepaliveCommand
		gate.markRunning()
		nc.Run() // blocks until death or idle timeout
		gate.markNotRunning()
		gate.exit()

		// Preserve the (possibly grown) read buffer for next connection.
		sharedBuf.buf = nc.rb.buf

		// Loop back to IDLE for automatic reconnection.
	}
}

type streamFeeder interface {
	Feed(in []byte, out io.Writer) (consumed int, done bool, err error)
}

type writerRef struct {
	w io.Writer
}

func (wr *writerRef) Write(p []byte) (int, error) {
	return wr.w.Write(p)
}

func (c *NNTPConnection) Run() {
	readerDone := make(chan struct{})
	defer func() {
		c.cancel()
		_ = c.conn.Close()
		// runConnSlot reuses this connection's read slab. Wait until the old
		// decoder can no longer touch it before exposing it to a new socket.
		<-readerDone
		c.failOutstanding()
		// failOutstanding is once-guarded and the reader's death path may
		// have already spent the once — while the writer, dying slightly
		// later, could still have enqueued one final request into pending
		// (stolen from a hot channel mid-teardown). This defer runs strictly
		// after the writer can no longer enqueue, so a drain HERE is the
		// last word: nothing left in pending, ever, or the caller stalls a
		// whole attempt window on a request nobody owns.
		c.drainPending()
		c.closeDone()
	}()

	go func() {
		defer close(readerDone)
		c.readerLoop()
		// ensure writer exits too
		c.cancel()
	}()

	// Buffered writer coalesces pipelined commands into fewer write syscalls.
	// It only pays off if the buffer is allowed to fill, so the loop below
	// flushes at the moment it is about to block and not before: a run of
	// commands queued behind one another leaves in a single write, which under
	// TLS is a single record rather than 29 bytes of framing per command.
	bw := bufio.NewWriterSize(c.conn, 64*1024)

	// flushBuffered empties the write buffer, if anything is in it.
	flushBuffered := func() error {
		if bw.Buffered() == 0 {
			return nil
		}
		return bw.Flush()
	}

	// Cached write deadline state to avoid redundant SetWriteDeadline syscalls.
	var lastWriteDL time.Time
	lastWriteHasDL := false
	writeDLSet := false

	setWriteDeadline := func(dl time.Time, hasDL bool) {
		if writeDLSet && lastWriteHasDL == hasDL && (!hasDL || dl.Equal(lastWriteDL)) {
			return
		}
		if hasDL {
			_ = c.conn.SetWriteDeadline(dl)
		} else {
			_ = c.conn.SetWriteDeadline(time.Time{})
		}
		lastWriteDL = dl
		lastWriteHasDL = hasDL
		writeDLSet = true
	}

	// Process the bootstrap request injected by runConnSlot, if any.
	if c.firstReq != nil {
		req := c.firstReq
		c.firstReq = nil

		if req.Ctx == nil {
			req.Ctx = context.Background()
		}

		// Check cancellation.
		select {
		case <-req.Ctx.Done():
			failRequest(req.RespCh, req.Ctx.Err())
			// Connection is still good — fall through to main loop.
			goto mainLoop
		default:
		}

		// Acquire inflight slot.
		select {
		case c.inflightSem <- struct{}{}:
		case <-c.ctx.Done():
			failRequest(req.RespCh, c.ctx.Err())
			return
		}

		// Body-bearing requests additionally take a bodySem slot so concurrent
		// bodies stay bounded by Inflight even when the pipeline (inflightSem)
		// is deeper for STAT. Bodyless STAT skips this and pipelines deep.
		if !isCheapCommand(req.Payload) {
			select {
			case c.bodySem <- struct{}{}:
				req.heldBody = true
			case <-req.Ctx.Done():
				<-c.inflightSem
				failRequest(req.RespCh, req.Ctx.Err())
				goto mainLoop // connection still good
			case <-c.ctx.Done():
				<-c.inflightSem
				failRequest(req.RespCh, c.ctx.Err())
				return
			}
			// Priority-lane bodies are bounded tighter than Inflight so a
			// demand read does not queue behind a connection's worth of
			// read-ahead (replies are FIFO per connection).
			if req.isPriority() && c.prioBodySem != nil {
				select {
				case c.prioBodySem <- struct{}{}:
					req.heldPrio = true
				case <-req.Ctx.Done():
					<-c.bodySem
					<-c.inflightSem
					failRequest(req.RespCh, req.Ctx.Err())
					goto mainLoop
				case <-c.ctx.Done():
					<-c.bodySem
					<-c.inflightSem
					failRequest(req.RespCh, c.ctx.Err())
					return
				}
			}
		}

		dl, hasDL := req.writeDeadline()
		setWriteDeadline(dl, hasDL)

		if _, err := bw.Write(req.Payload); err != nil {
			<-c.inflightSem
			failRequest(req.RespCh, err)
			_ = c.conn.Close()
			c.failOutstanding()
			return
		}
		c.noteDispatched(req)
		if req.PostMode {
			// Two-phase POST: flush "POST\r\n" immediately so the server can
			// respond with 340/440 before we send the article body.
			if err := bw.Flush(); err != nil {
				<-c.inflightSem
				failRequest(req.RespCh, err)
				_ = c.conn.Close()
				c.failOutstanding()
				return
			}
			req.postReadyCh = make(chan error, 1)
			c.pending <- req
			// Block here — no other request can be written while we wait.
			select {
			case postErr := <-req.postReadyCh:
				if postErr != nil {
					// 440 or error: drain body to unblock the pipe-writer goroutine.
					if req.PayloadBody != nil {
						_, _ = io.Copy(io.Discard, req.PayloadBody)
					}
					goto mainLoop
				}
			case <-c.ctx.Done():
				if req.PayloadBody != nil {
					_, _ = io.Copy(io.Discard, req.PayloadBody)
				}
				return
			}
			// 340 received: send the article body.
			if req.PayloadBody != nil {
				if _, err := io.Copy(bw, req.PayloadBody); err != nil {
					_ = c.conn.Close()
					c.failOutstanding()
					return
				}
			}
		} else {
			if req.PayloadBody != nil {
				if _, err := io.Copy(bw, req.PayloadBody); err != nil {
					<-c.inflightSem
					failRequest(req.RespCh, err)
					_ = c.conn.Close()
					c.failOutstanding()
					return
				}
			}
			req.sentAt.Store(time.Now().UnixNano())
			c.pending <- req
		}
	}

mainLoop:
	// Flush any buffered writes before blocking.
	if err := flushBuffered(); err != nil {
		return
	}

	// Set up idle timer (nil if no idle timeout configured).
	var idleTimer *time.Timer
	var idleCh <-chan time.Time
	if c.idleTimeout > 0 {
		idleTimer = time.NewTimer(c.idleTimeout)
		idleCh = idleTimer.C
		defer idleTimer.Stop()
	}

	// Set up keepalive timer (nil if no keepalive configured).
	var keepaliveCh <-chan time.Time
	if c.keepaliveInterval > 0 {
		keepaliveCh = time.After(c.keepaliveInterval)
	}

	for {
		select {
		case <-c.ctx.Done():
			return
		default:
		}

		// Acquire inflight capacity. The non-blocking attempt comes first so
		// that a writer with commands still to send does not flush: only when
		// the pipeline is genuinely full is the buffer drained and the writer
		// parked. A flush failure means the connection is dead; returning runs
		// Run's defer, which closes the conn and drains pending so nothing
		// strands.
		select {
		case c.inflightSem <- struct{}{}:
		default:
			if err := flushBuffered(); err != nil {
				return
			}
			select {
			case c.inflightSem <- struct{}{}:
			case <-c.ctx.Done():
				return
			}
		}

		// pull next request (with idle timeout)
		// Hot channels are tried first (non-blocking) so that requests
		// prefer already-connected connections over waking cold slots.
		// When hotReqCh/hotPrioCh are nil (standalone path), receives
		// from nil channels block forever in select and are excluded.
		var didKeepalive bool
		req, ok, got := c.tryNextRequest()
		if !got {
			// Nothing queued: this is the point the writer actually blocks, so
			// it is the point the buffer has to leave. Everything written since
			// the last flush goes out in one write.
			if err := flushBuffered(); err != nil {
				<-c.inflightSem
				return
			}
			idleBody, hotPrio, hotReq, coldPrio, reqLane := c.foregroundLanes()
			bgLane := c.bgLane()
			select {
			case req, ok = <-idleBody:
			case req, ok = <-hotPrio:
			case req, ok = <-hotReq:
			case req, ok = <-coldPrio:
			case req, ok = <-reqLane:
			case req, ok = <-bgLane:
			case <-c.wake:
				// A reply completed: the pipeline may be idle now, or the
				// connection no longer dedicated to background. Re-evaluate.
				<-c.inflightSem
				continue
			case <-backgroundRecheck(c.bgCh, bgLane):
				// The gate was closed when this writer parked; in-flight may
				// have dropped since with nothing else to wake it. Re-evaluate.
				<-c.inflightSem
				continue
			case <-c.ctx.Done():
				<-c.inflightSem
				return
			case <-idleCh:
				<-c.inflightSem
				c.waitForInflightDrain()
				return
			case <-keepaliveCh:
				didKeepalive = true
			}
		}

		// Keepalive probe: send a lightweight command through the normal pipeline
		// so readerLoop can match the response in FIFO order.
		// inflightSem is already held; readerLoop releases it at line 1008.
		if didKeepalive {
			keepaliveCh = time.After(c.keepaliveInterval) // reset regardless of outcome
			kaCh := make(chan Response, 1)
			// The probe MUST carry an attempt deadline: with a background context
			// and no deadline, a server that accepts the command but never
			// answers (half-open connection, stale provider session) leaves the
			// reader in a deadline-less Read forever — the connection parks
			// "busy" holding its slot and its provider session, and the feature
			// meant to detect dead connections becomes the thing that wedges
			// them. On expiry the reader surfaces a timeout, closes the
			// connection, and the pool replaces it — exactly what a keepalive
			// is for.
			kaReq := &Request{
				Payload:         []byte(c.keepaliveCommand + "\r\n"),
				RespCh:          kaCh,
				Ctx:             context.Background(),
				attemptDeadline: time.Now().Add(c.keepaliveProbeTimeout()),
				attemptWindow:   c.keepaliveProbeTimeout(),
			}
			if _, err := bw.Write(kaReq.Payload); err != nil {
				_ = c.conn.Close()
				c.failOutstanding()
				return
			}
			if err := bw.Flush(); err != nil {
				_ = c.conn.Close()
				c.failOutstanding()
				return
			}
			c.pending <- kaReq
			select {
			case resp := <-kaCh:
				if resp.Err != nil || resp.StatusCode != keepaliveExpectedCode(c.keepaliveCommand) {
					_ = c.conn.Close()
					c.failOutstanding()
					return
				}
			case <-c.ctx.Done():
				return
			}
			continue
		}
		if !ok {
			<-c.inflightSem
			return
		}
		if req.Ctx == nil {
			req.Ctx = context.Background()
		}

		// Reset idle timer since we got a request.
		if idleTimer != nil {
			if !idleTimer.Stop() {
				select {
				case <-idleTimer.C:
				default:
				}
			}
			idleTimer.Reset(c.idleTimeout)
		}

		// Cancel before sending (queued-but-not-sent case)
		select {
		case <-req.Ctx.Done():
			<-c.inflightSem
			failRequest(req.RespCh, req.Ctx.Err())
			continue
		default:
		}

		// Body-bearing requests additionally take a bodySem slot so concurrent
		// bodies stay bounded by Inflight even when the pipeline (inflightSem)
		// is deeper for STAT. Bodyless STAT skips this and pipelines deep.
		if !isCheapCommand(req.Payload) {
			select {
			case c.bodySem <- struct{}{}:
				req.heldBody = true
			default:
				// About to wait for a body slot, which only frees when a
				// reply arrives — and a reply can only arrive for commands
				// the server has actually seen. Anything still buffered must
				// go out first or the wait is on a reply to a command that
				// was never sent.
				if err := flushBuffered(); err != nil {
					<-c.inflightSem
					failRequest(req.RespCh, err)
					return
				}
				select {
				case c.bodySem <- struct{}{}:
					req.heldBody = true
				case <-req.Ctx.Done():
					<-c.inflightSem
					failRequest(req.RespCh, req.Ctx.Err())
					continue
				case <-c.ctx.Done():
					// The connection died with a request in hand. This arm is
					// reachable with a request because a select whose conn-death
					// arm and request arm are BOTH ready picks randomly — a dying
					// writer can steal one last request from a hot channel, then
					// land here when the dead reader's bodySem slot never frees.
					// Fail it back (ErrConnectionDied retries it on a fresh
					// connection at once); a bare return stranded it silently
					// until the caller's whole attempt window expired.
					<-c.inflightSem
					failRequest(req.RespCh, c.connDiedErr())
					return
				}
			}
			// Priority-lane bodies are bounded tighter than Inflight so a
			// demand read does not queue behind a connection's worth of
			// read-ahead (replies are FIFO per connection). Same flush rule:
			// a slot only frees when a reply arrives for a sent command.
			if req.isPriority() && c.prioBodySem != nil {
				select {
				case c.prioBodySem <- struct{}{}:
					req.heldPrio = true
				default:
					if err := flushBuffered(); err != nil {
						<-c.bodySem
						<-c.inflightSem
						failRequest(req.RespCh, err)
						return
					}
					select {
					case c.prioBodySem <- struct{}{}:
						req.heldPrio = true
					case <-req.Ctx.Done():
						<-c.bodySem
						<-c.inflightSem
						failRequest(req.RespCh, req.Ctx.Err())
						continue
					case <-c.ctx.Done():
						<-c.bodySem
						<-c.inflightSem
						failRequest(req.RespCh, c.connDiedErr())
						return
					}
				}
			}
		}

		// per-request write deadline (cached to avoid redundant syscalls)
		dl, hasDL := req.writeDeadline()
		setWriteDeadline(dl, hasDL)

		// pipeline write (buffered; flushed at top of loop before blocking)
		if _, err := bw.Write(req.Payload); err != nil {
			<-c.inflightSem
			failRequest(req.RespCh, err)
			_ = c.conn.Close()
			c.failOutstanding()
			return
		}
		c.noteDispatched(req)
		if req.PostMode {
			// Two-phase POST: flush "POST\r\n" immediately so the server can
			// respond with 340/440 before we send the article body. Blocking
			// here also prevents pipelining other requests during POST.
			if err := bw.Flush(); err != nil {
				<-c.inflightSem
				failRequest(req.RespCh, err)
				_ = c.conn.Close()
				c.failOutstanding()
				return
			}
			req.postReadyCh = make(chan error, 1)
			c.pending <- req
			select {
			case postErr := <-req.postReadyCh:
				if postErr != nil {
					// 440 or error: drain body to unblock the pipe-writer goroutine.
					if req.PayloadBody != nil {
						_, _ = io.Copy(io.Discard, req.PayloadBody)
					}
					continue
				}
			case <-c.ctx.Done():
				if req.PayloadBody != nil {
					_, _ = io.Copy(io.Discard, req.PayloadBody)
				}
				return
			}
			// 340 received: send the article body.
			if req.PayloadBody != nil {
				if _, err := io.Copy(bw, req.PayloadBody); err != nil {
					_ = c.conn.Close()
					c.failOutstanding()
					return
				}
			}
		} else {
			if req.PayloadBody != nil {
				if _, err := io.Copy(bw, req.PayloadBody); err != nil {
					<-c.inflightSem
					failRequest(req.RespCh, err)
					_ = c.conn.Close()
					c.failOutstanding()
					return
				}
			}
			// track FIFO ordering (after writes succeed to avoid send on closed channel)
			req.sentAt.Store(time.Now().UnixNano())
			c.pending <- req
		}
	}
}

func (c *NNTPConnection) readerLoop() {
	for {
		select {
		case <-c.ctx.Done():
			c.failOutstanding()
			return
		default:
		}

		// Match FIFO request
		var req *Request
		select {
		case req = <-c.pending:
		case <-c.ctx.Done():
			return
		}
		if req.Ctx == nil {
			req.Ctx = context.Background()
		}

		resp := Response{
			Request: req,
		}
		decoder := NNTPResponse{
			onMeta: req.OnMeta,
		}

		// If the request is cancelled after send, we must still drain its response off the wire,
		// but we don't deliver it.
		deliver := true
		select {
		case <-req.Ctx.Done():
			deliver = false
		default:
		}

		out := req.BodyWriter
		if !deliver {
			out = io.Discard
		} else if out == nil {
			out = &resp.Body
		}

		// Allow us to switch output to io.Discard if the request is cancelled while
		// we are still draining the response.
		outRef := &writerRef{w: out}

		// Progress-aware deadline: before the first response byte we honor the
		// attempt window as a TTFB bound — re-anchored at drain start, so time
		// a request spent queued behind other pipelined responses never counts
		// against it (replies are FIFO; the server has not even seen slowness).
		// A dispatch-anchored deadline here lets one deep request burst arrive
		// with its windows pre-expired, and the resulting read timeouts tear
		// the connection down and fail every pipelined neighbour with
		// "connection died" (observed live during wide STAT sweeps). Once bytes
		// flow we switch to the rolling stall deadline. The caller's own ctx
		// deadline applies as an upper bound only while the caller still wants
		// the response: once the request is abandoned (attempt expired and
		// failed over, or caller ctx done) its deadlines are stale and only the
		// drain-anchored window / stall bound governs — the server must keep
		// making progress, nothing more.
		stall := c.stallTimeout
		lastBytes := 0
		var stallDeadline time.Time
		var firstByteAt time.Time
		respStart := time.Now()
		abandoned := func() bool {
			return req.attemptState.Load() == attemptAbandoned || req.Ctx.Err() != nil
		}
		ttfbBound := func() (time.Time, bool) {
			if req.attemptWindow > 0 {
				return respStart.Add(req.attemptWindow), true
			}
			if !req.attemptDeadline.IsZero() {
				return req.attemptDeadline, true
			}
			return time.Time{}, false
		}
		abortDrain := false
		drain := func() error {
			return c.rb.feedUntilDone(c.conn, &decoder, outRef, func(wireBytes int) (time.Time, bool) {
				if deliver {
					select {
					case <-req.Ctx.Done():
						deliver = false
						outRef.w = io.Discard
						decoder.onMeta = nil
					default:
					}
				}
				// Nobody wants this body any more. Draining keeps the
				// connection in sync, but past a point a reconnect is far
				// cheaper than the bytes: a closed player's read-ahead on
				// 4 MiB parts would otherwise keep the wire busy for seconds.
				if !deliver && c.abortDrainBytes > 0 && decoder.YEnc.PartSize > 0 &&
					decoder.YEnc.PartSize-int64(decoder.BytesDecoded) > c.abortDrainBytes {
					abortDrain = true
					return time.Unix(0, 0), true
				}
				if wireBytes > lastBytes {
					lastBytes = wireBytes
					if firstByteAt.IsZero() {
						firstByteAt = time.Now()
					}
					req.attemptState.CompareAndSwap(attemptPending, attemptCommitted)
					if req.attemptState.Load() == attemptAbandoned {
						deliver = false
						outRef.w = io.Discard
						decoder.onMeta = nil
					}
					if stall > 0 {
						if dl := time.Now().Add(stall); dl.Sub(stallDeadline) >= stallDeadlineQuantum {
							stallDeadline = dl
						}
					}
				}
				if lastBytes > 0 {
					// Committed: the attempt owns the wire and the caller's
					// own deadline no longer applies at all, abandoned or
					// not — see the field comment. Folding a live parentDL
					// in here (the pre-fix behavior) raced the caller's
					// timer against this exact deadline, since both target
					// the same instant: whichever timer the Go runtime
					// resolved first decided whether the read returned
					// "abandoned" (parentDL fired, ctx.Err() not yet set)
					// or genuinely abandoned, and a queued neighbor request
					// paid for the wrong guess with the whole connection.
					if stall > 0 {
						return stallDeadline, true
					}
					return time.Time{}, false
				}
				parentDL, hasParent := req.Ctx.Deadline()
				if abandoned() {
					// Stale caller deadlines must not govern the drain.
					parentDL, hasParent = time.Time{}, false
				}
				if bound, ok := ttfbBound(); ok {
					return minDeadline(bound, parentDL, hasParent)
				}
				return parentDL, hasParent
			})
		}
		err := drain()
		if abortDrain {
			// Surface as connection death so the teardown below runs and
			// pending neighbours are retried through the usual path.
			err = c.connDiedErr()
		}
		// A read that timed out on a caller deadline may have raced the very
		// abandonment that invalidates it (the tryGroup timer fires at the same
		// instant the attempt deadline expires, and a caller ctx expiring IS the
		// abandonment). Re-check and resume the drain under the surviving bound
		// instead of surfacing a timeout that would kill the connection; a
		// genuinely stalled server still runs out the drain-anchored window (or
		// the stall clock) and dies below.
		for err != nil && !abortDrain && isTimeoutErr(err) && abandoned() {
			bound, ok := ttfbBound()
			if lastBytes > 0 {
				bound, ok = stallDeadline, stall > 0
			}
			if !ok || !time.Now().Before(bound) {
				break
			}
			err = drain()
		}
		if err != nil {
			if c.providerName != "" {
				resp.Err = fmt.Errorf("%s: %w", c.providerName, err)
			} else {
				resp.Err = err
			}
		}

		resp.StatusCode = decoder.StatusCode
		resp.Status = decoder.Message
		resp.Lines = decoder.Lines
		resp.Meta = decoder

		// Two-phase POST: coordinate with writeLoop via postReadyCh.
		if req.PostMode {
			if decoder.StatusCode == 340 {
				// Signal writeLoop to send the article body, then read the
				// final response (240/441) once the server acknowledges it.
				if req.postReadyCh != nil {
					req.postReadyCh <- nil
				}
				decoder2 := NNTPResponse{}
				err2 := c.rb.feedUntilDone(c.conn, &decoder2, io.Discard, func(wireBytes int) (time.Time, bool) {
					if deliver {
						select {
						case <-req.Ctx.Done():
							deliver = false
						default:
						}
					}
					return req.Ctx.Deadline()
				})
				if err2 != nil {
					if c.providerName != "" {
						resp.Err = fmt.Errorf("%s: %w", c.providerName, err2)
					} else {
						resp.Err = err2
					}
				}
				resp.StatusCode = decoder2.StatusCode
				resp.Status = decoder2.Message
				resp.Meta = decoder2
			} else if req.postReadyCh != nil {
				// 440 or other rejection: tell writeLoop not to send the body.
				req.postReadyCh <- fmt.Errorf("post rejected: %d %s", decoder.StatusCode, decoder.Message)
			}
		}

		if c.stats != nil {
			n := int64(decoder.BytesConsumed)
			c.stats.BytesConsumed.Add(n)
			if c.stats.quotaBytes > 0 {
				if c.stats.quotaUsed.Add(n) >= c.stats.quotaBytes {
					c.stats.quotaExceeded.Store(true)
				}
			}
			if resp.Err != nil {
				c.stats.Errors.Add(1)
			} else if decoder.StatusCode == 430 || decoder.StatusCode == 423 {
				c.stats.Missing.Add(1)
			} else if decoder.StatusCode < 100 || decoder.StatusCode >= 400 {
				c.stats.Errors.Add(1)
			} else {
				// Successful transfer: feed the TTFB and throughput EWMAs that
				// drive the adaptive attempt timeout and speed-aware dispatch.
				// firstByteAt is unset when the whole response arrived in a
				// single read; fall back to the read start. recordTTFB/Speed
				// ignore non-positive and sub-floor samples respectively.
				fb := firstByteAt
				if fb.IsZero() {
					fb = respStart
				}
				if sentAt := req.sentAt.Load(); sentAt != 0 {
					recordTTFB(c.stats, fb.Sub(time.Unix(0, sentAt)))
				}
				recordSpeed(c.stats, n, time.Since(fb))
			}
		}

		if deliver {
			// Best effort: don't block forever if the receiver abandoned the channel.
			select {
			case req.RespCh <- resp:
			default:
			}
		}
		safeClose(req.RespCh)

		// release inflight slot (and the body slot, if this request took one)
		<-c.inflightSem
		if req.heldBody {
			<-c.bodySem
		}
		if req.heldPrio {
			<-c.prioBodySem
		}
		c.releaseBackground(req)
		c.pokeWriter()

		// If we hit a timeout, cancellation-related network error, or protocol
		// desync, close the connection so the pool replaces it with a fresh one.
		if resp.Err != nil {
			if isTimeoutErr(resp.Err) || abortDrain {
				_ = c.conn.Close()
				c.failOutstanding()
				return
			}
			if errors.Is(resp.Err, ErrProtocolDesync) {
				_ = c.conn.Close()
				c.failOutstanding()
				return
			}
		}

		// 502 "service unavailable" mid-session: close the connection so
		// all pending requests fail fast instead of waiting in the pipeline.
		if decoder.StatusCode == 502 {
			_ = c.conn.Close()
			c.failOutstanding()
			return
		}
	}
}

// readOneResponse reads a complete NNTP response from the stream.
// Any unread bytes remain buffered in c.rbuf[c.rstart:c.rend] for subsequent reads.
func (c *NNTPConnection) readOneResponse(out io.Writer) (NNTPResponse, error) {
	resp := NNTPResponse{}
	if err := c.rb.feedUntilDone(c.conn, &resp, out, func(int) (time.Time, bool) { return time.Time{}, false }); err != nil {
		return resp, err
	}
	return resp, nil
}

// DispatchStrategy controls how the client distributes requests across main providers.
type DispatchStrategy int

const (
	// DispatchRoundRobin distributes requests using dynamic weighted round-robin
	// based on each provider's available capacity. This is the default.
	DispatchRoundRobin DispatchStrategy = iota

	// DispatchFIFO sends all requests to the first provider that has capacity.
	// Overflow cascades to subsequent providers in declaration order.
	DispatchFIFO
)

// ClientOption configures optional Client behavior.
type ClientOption func(*clientConfig)

type clientConfig struct {
	dispatch      DispatchStrategy
	statProbeOff  bool
	speedAwareOff bool
}

// WithDispatchStrategy sets the request distribution strategy for main providers.
// The default is DispatchRoundRobin.
func WithDispatchStrategy(s DispatchStrategy) ClientOption {
	return func(cfg *clientConfig) { cfg.dispatch = s }
}

// WithStatProbe enables or disables parallel STAT probing on 430 failover.
// When enabled (the default), after the first 430 response the remaining
// providers are probed concurrently with lightweight STAT commands; only the
// first provider that confirms article existence (223) receives the full
// request. This reduces "article missing on N providers" latency from
// sum-of-RTTs to max-of-RTTs.
func WithStatProbe(enabled bool) ClientOption {
	return func(cfg *clientConfig) { cfg.statProbeOff = !enabled }
}

// WithSpeedAwareDispatch enables or disables speed-aware weighting of the
// DispatchRoundRobin strategy. When enabled (the default), each provider's
// round-robin weight is scaled by its observed throughput so faster providers
// receive proportionally more traffic; available connection capacity still
// governs the base weight. Has no effect under DispatchFIFO.
func WithSpeedAwareDispatch(enabled bool) ClientOption {
	return func(cfg *clientConfig) { cfg.speedAwareOff = !enabled }
}

// Provider describes a single NNTP server with its own credentials and connection count.
type Provider struct {
	Host string
	// Name is the stable external identity used in stats and errors. When empty,
	// the name falls back to the legacy host+username derivation.
	Name        string
	TLSConfig   *tls.Config
	Auth        Auth
	Connections int
	// MinConnections is the number of connections to this provider that are
	// dialed eagerly at startup (and re-dialed immediately on death) instead
	// of waiting for the first request. These slots ignore IdleTimeout, so
	// they stay connected indefinitely once established, giving the provider
	// a warm floor of ready connections. 0 disables pre-warming — all
	// connections then dial lazily on demand, up to Connections, as before.
	// Must be <= Connections.
	MinConnections  int
	Inflight        int           // 0 defaults to 1; max concurrent BODY (and other body-bearing) commands per connection
	StatInflight    int           // 0 defaults to Inflight; deeper pipeline depth for bodyless STAT commands. Because STAT carries no payload, many can be in flight per connection at negligible memory cost, amortising round-trips. Set higher than Inflight (e.g. 50-100) for STAT-heavy workloads without inflating BODY memory.
	Factory         ConnFactory   // overrides Host/TLSConfig when set
	Backup          bool          // if true, only used when main providers return 430
	StorageGroup    string        // optional label for providers sharing upstream storage (same backbone); a 430 from one skips the rest in the group for that request
	SkipPing        bool          // if true, skip the DATE ping on startup (for providers that don't support DATE)
	IdleTimeout     time.Duration // 0 means no idle disconnect
	ThrottleRestore time.Duration // 0 defaults to 30s
	KeepAlive       time.Duration // TCP keep-alive interval; 0 defaults to 30s; negative disables
	ReconnectDelay  time.Duration // 0 disables auto-reconnect after 502; when set, re-adds provider after this delay

	// StreamInflight caps priority-lane bodies in flight per connection, so a
	// demand read never queues behind more than StreamInflight-1 stream
	// bodies on its connection while normal-lane bodies keep Inflight.
	// 0 defaults to min(Inflight, 4); a value >= Inflight disables the cap.
	StreamInflight int

	// BackgroundFloor is how many background-lane requests (BodyBackground,
	// StatBackground, StatMany with Background) this provider keeps in flight
	// while priority or normal traffic is recent. When the provider is idle
	// background may use every connection; the floor only bounds it under
	// contention so a stream never finds more than this many connections stuck
	// behind background bodies. 0 defaults to max(1, Connections/4); values
	// are clamped to [1, Connections-1] (or 1 for a single connection).
	BackgroundFloor int

	// AbortDrainBytes: when a body's request is cancelled while its bytes are
	// still arriving, the connection normally drains the rest to stay in sync.
	// If more than this many bytes remain, the connection is closed instead
	// and its slot reconnects; pending neighbours are retried by the caller's
	// connection-death handling. 0 defaults to 1 MiB; negative disables.
	AbortDrainBytes int64

	// AttemptTimeout bounds dispatch plus time-to-first-response-byte for each
	// attempt against this provider (it does NOT bound the body transfer, which
	// is governed by StallTimeout). 0 selects an adaptive value derived from the
	// provider's measured RTT, clamped to [2s, 10s]. Set explicitly to override.
	AttemptTimeout time.Duration

	// StallTimeout is the rolling progress deadline for a body transfer: once
	// bytes are flowing, the read deadline is extended by StallTimeout on each
	// chunk of progress, so a slow-but-healthy download never times out while a
	// truly stalled one is torn down. 0 defaults to 8s; negative disables it
	// (only the caller's context deadline applies).
	StallTimeout time.Duration

	// KeepaliveInterval, if non-zero, sends a lightweight NNTP command
	// periodically when the connection is idle, to detect zombie connections
	// before a real request arrives. Recommended: 30s–60s.
	// Disabled when SkipPing is true and KeepaliveCommand is empty.
	KeepaliveInterval time.Duration

	// KeepaliveCommand is the NNTP command sent as a keepalive probe.
	// Defaults to "DATE" (response 111). Use "HELP" (response 100) or
	// "CAPABILITIES" (response 101) for providers that do not support DATE.
	// Ignored when KeepaliveInterval is 0.
	KeepaliveCommand string

	// UserAgent identifies this client to the NNTP server. Empty string disables it.
	UserAgent string

	// QuotaBytes is the maximum number of bytes that may be downloaded from this
	// provider per QuotaPeriod. 0 means unlimited.
	QuotaBytes int64

	// QuotaPeriod is the rolling window after which the quota counter resets.
	// 0 means the quota never resets (lifetime cap).
	// Typical value: 30 * 24 * time.Hour  (≈ monthly)
	QuotaPeriod time.Duration

	// QuotaUsed is the number of bytes already consumed at startup.
	// Set this on restart to restore quota state from a previous run.
	// Read the current value from [ProviderStats.QuotaUsed] before shutting down.
	QuotaUsed int64

	// QuotaResetAt, if non-zero, overrides the quota period reset deadline on startup.
	// Set this on restart to restore the reset deadline from a previous run.
	// Read the current value from [ProviderStats.QuotaResetAt] before shutting down.
	// Ignored when QuotaPeriod is 0 or the time is in the past.
	QuotaResetAt time.Time
}

type providerGroup struct {
	name          string
	host          string // raw Provider.Host; empty for Factory-based providers
	skipID        string // Provider.StorageGroup when set, else host; identity used for 430 skipping
	maxConns      int
	ctx           context.Context // cancelled on removal/close
	reqCh         chan *Request
	prioCh        chan *Request // priority requests; connections prefer this over reqCh
	hotReqCh      chan *Request // unbuffered; hot (connected) connections read this
	hotPrioCh     chan *Request // unbuffered; hot priority connections read this
	hotIdleBodyCh chan *Request // unbuffered; only connections with no body in flight read this
	bgCh          chan *Request // background lane; read only when prioCh and reqCh are empty, capped while foreground is recent
	gate          *connGate
	stats         providerStats
	cancel        context.CancelFunc // cancels this group's slot goroutines
	p             Provider           // original config; used for auto-reconnect

	// Quota period configuration. quotaBytes/quotaUsed/quotaExceeded live in
	// stats so that NNTPConnection can update them via its *providerStats pointer.
	quotaPeriod  time.Duration // 0 = no auto-reset
	quotaResetAt atomic.Int64  // Unix nanoseconds of next reset; 0 = never
}

// attemptTimeout returns the per-attempt timeout (dispatch + time-to-first-byte
// bound). An explicit Provider.AttemptTimeout wins; otherwise it adapts to the
// provider's observed time-to-first-byte EWMA (seeded from the ping RTT) as
// 4×TTFB, clamped to [minAttemptTimeout, maxAttemptTimeout]. With no sample yet
// it falls back to minAttemptTimeout, preserving the historical 2s behavior.
func (g *providerGroup) attemptTimeout() time.Duration {
	if g.p.AttemptTimeout > 0 {
		return g.p.AttemptTimeout
	}
	ttfb := g.stats.ttfbEWMA.Load()
	if ttfb <= 0 {
		return minAttemptTimeout
	}
	d := time.Duration(ttfb) * 4
	if d < minAttemptTimeout {
		return minAttemptTimeout
	}
	if d > maxAttemptTimeout {
		return maxAttemptTimeout
	}
	return d
}

// windowOr resolves an attempt window: a positive d is used as-is, anything
// else means "the provider's adaptive window". The single definition of the
// zero-means-default convention that both tryGroupTimeout and
// tryGroupResilient sit on.
func (g *providerGroup) windowOr(d time.Duration) time.Duration {
	if d > 0 {
		return d
	}
	return g.attemptTimeout()
}

// escalationBreakerCooldown is how long escalation stays suppressed for a
// provider whose escalations proved fruitless, before one half-open re-probe is
// allowed. A var rather than a const so tests can shorten it; not exported, so
// callers cannot depend on tuning it.
var escalationBreakerCooldown = 30 * time.Second

// escalationSuppressed reports whether this provider's escalation breaker is
// open: escalating it has repeatedly cost a full budget and delivered nothing,
// so the widened window is not worth re-paying yet. The breaker is half-open —
// once the cooldown lapses one escalation is allowed through, so a provider
// that recovers into the slow-spool shape is never locked out permanently.
func (g *providerGroup) escalationSuppressed(now time.Time) bool {
	until := g.stats.escSuppressedUntil.Load()
	return until > 0 && now.UnixNano() < until
}

// noteEscalationFruitless records an escalated pass that expired without
// delivering. At escalationBreakerThreshold consecutive fruitless escalations
// the breaker opens (and re-arms on every later fruitless half-open probe).
func (g *providerGroup) noteEscalationFruitless(now time.Time) {
	if g.stats.escFruitless.Add(1) >= escalationBreakerThreshold {
		g.stats.escSuppressedUntil.Store(now.Add(escalationBreakerCooldown).UnixNano())
	}
}

// resetEscalationBreaker clears the breaker after the provider answers
// definitively — including a body delivered on an escalated window, which is
// exactly the slow-spool case escalation exists to serve.
// Load-guarded: this runs on every definitive answer (every successful body),
// and providerStats is shared by all of the provider's connections — an
// unconditional store would take that cache line exclusive on each download to
// rewrite a zero with a zero.
func (g *providerGroup) resetEscalationBreaker() {
	if g.stats.escFruitless.Load() != 0 {
		g.stats.escFruitless.Store(0)
	}
	if g.stats.escSuppressedUntil.Load() != 0 {
		g.stats.escSuppressedUntil.Store(0)
	}
}

// escalationWindow reports the window this provider should be re-asked at in
// an escalated pass, given the window it already expired at (prev) and the
// pass's remaining shared budget. ok is false when escalating it would buy
// nothing: it answered definitively, its breaker is open, or the budget cannot
// out-wait the window it just expired against. The single definition of
// "may this provider be escalated?", consulted both when deciding to escalate
// at all and when running the escalated pass.
func (g *providerGroup) escalationWindow(prev, budget time.Duration, now time.Time) (time.Duration, bool) {
	if prev == 0 || budget <= prev || g.escalationSuppressed(now) {
		return 0, false
	}
	return budget, true
}

// isQuotaExceeded reports whether this provider has consumed its download quota
// for the current period.
//
// Fast path (quota not exceeded): single atomic.Bool load (~1 ns).
// Slow path (flag set, period elapsed): resets counters and returns false.
// The time.Now() call is deferred until the cached flag is actually set.
func (g *providerGroup) isQuotaExceeded() bool {
	if g.stats.quotaBytes <= 0 {
		return false // unlimited
	}
	if !g.stats.quotaExceeded.Load() {
		return false // fast path: quota not yet hit
	}
	// Flag is set. If a reset period is configured, check whether it has elapsed.
	if g.quotaPeriod > 0 {
		resetAt := g.quotaResetAt.Load()
		if resetAt > 0 && time.Now().UnixNano() >= resetAt {
			g.stats.quotaUsed.Store(0)
			g.stats.quotaExceeded.Store(false)
			g.quotaResetAt.Store(time.Now().Add(g.quotaPeriod).UnixNano())
			return false
		}
	}
	return true
}

type Client struct {
	ctx    context.Context
	cancel context.CancelFunc

	mainGroups   atomic.Pointer[[]*providerGroup]
	backupGroups atomic.Pointer[[]*providerGroup]
	nextIdx      atomic.Uint64 // round-robin counter for mainGroups

	dispatch   DispatchStrategy // set once by NewClient, read-only after
	statProbe  bool             // set once by NewClient; enables parallel STAT probing on 430
	speedAware bool             // set once by NewClient; weights round-robin dispatch by throughput

	providerIdx atomic.Int64 // monotonic counter for unnamed providers

	startTime time.Time
	wg        sync.WaitGroup
}

// parseDateResponse parses an NNTP DATE response message.
// message is the full status line, e.g. "111 20240315120000".
func parseDateResponse(message string) (time.Time, error) {
	// Skip "111 " prefix if present.
	ts := message
	if len(ts) > 4 && ts[3] == ' ' {
		ts = ts[4:]
	}
	if len(ts) < 14 {
		return time.Time{}, fmt.Errorf("nntp: DATE response too short: %q", message)
	}
	return time.Parse("20060102150405", ts[:14])
}

// pingProvider dials a temporary connection, authenticates, sends DATE, and
// measures RTT. The connection is always closed before returning.
func pingProvider(ctx context.Context, factory ConnFactory, auth Auth) PingResult {
	conn, err := factory(ctx)
	if err != nil {
		return PingResult{Err: fmt.Errorf("ping dial: %w", err)}
	}
	if conn == nil {
		return PingResult{Err: fmt.Errorf("ping dial: factory returned nil connection")}
	}
	defer func() { _ = conn.Close() }()

	rb := readBuffer{buf: make([]byte, defaultReadBufSize)}
	nc := &NNTPConnection{
		conn: conn,
		rb:   rb,
	}

	// Read greeting.
	greeting, err := nc.readOneResponse(io.Discard)
	if err != nil {
		return PingResult{Err: fmt.Errorf("ping greeting: %w", err)}
	}
	if greeting.StatusCode != 200 && greeting.StatusCode != 201 {
		return PingResult{Err: &greetingError{StatusCode: greeting.StatusCode, Message: greeting.Message}}
	}

	// Auth if needed.
	if auth.Username != "" {
		if err := nc.auth(auth); err != nil {
			return PingResult{Err: fmt.Errorf("ping auth: %w", err)}
		}
	}

	// Send DATE and measure RTT.
	start := time.Now()
	if _, err := conn.Write([]byte("DATE\r\n")); err != nil {
		return PingResult{Err: fmt.Errorf("ping write DATE: %w", err)}
	}
	resp, err := nc.readOneResponse(io.Discard)
	rtt := time.Since(start)
	if err != nil {
		return PingResult{Err: fmt.Errorf("ping read DATE: %w", err)}
	}
	if resp.StatusCode != 111 {
		return PingResult{Err: fmt.Errorf("ping DATE unexpected status: %d %s", resp.StatusCode, resp.Message)}
	}

	serverTime, err := parseDateResponse(resp.Message)
	if err != nil {
		return PingResult{RTT: rtt, Err: err}
	}
	return PingResult{RTT: rtt, ServerTime: serverTime}
}

// TestProvider dials the given provider, performs greeting + authentication +
// DATE, and returns the result. It is completely independent of Client/pool.
func TestProvider(ctx context.Context, p Provider) PingResult {
	factory := p.Factory
	if factory == nil {
		host := p.Host
		tlsCfg := p.TLSConfig
		keepAlive := p.KeepAlive
		factory = func(ctx context.Context) (net.Conn, error) {
			return newNetConn(ctx, host, tlsCfg, keepAlive)
		}
	}
	return pingProvider(ctx, factory, p.Auth)
}

// resolveProviderName builds a unique name for a provider based on host and auth.
func resolveProviderName(p Provider, index int) string {
	if p.Name != "" {
		return p.Name
	}
	if p.Host != "" {
		if p.Auth.Username != "" {
			return p.Host + "+" + p.Auth.Username
		}
		return p.Host
	}
	return fmt.Sprintf("provider-%d", index)
}

// startProviderGroup creates a providerGroup, pings the provider, and launches
// connection slot goroutines. The caller is responsible for storing the group.
func (c *Client) startProviderGroup(p Provider, index int) *providerGroup {
	g, factory := c.newProviderGroup(p, index)
	c.pingProviderGroup(g, p, factory)
	c.launchConnSlots(g, p, factory)
	return g
}

// newProviderGroup builds the group and resolves its connection factory. It
// performs no I/O and starts no goroutines, so a caller with many providers can
// construct them all and then ping them concurrently (see NewClient) instead of
// paying one handshake timeout per provider, serially.
func (c *Client) newProviderGroup(p Provider, index int) (*providerGroup, ConnFactory) {
	factory := p.Factory
	if factory == nil {
		host := p.Host
		tlsCfg := p.TLSConfig
		keepAlive := p.KeepAlive
		factory = func(ctx context.Context) (net.Conn, error) {
			return newNetConn(ctx, host, tlsCfg, keepAlive)
		}
	}

	name := resolveProviderName(p, index)
	gate := newConnGate(p.Connections, p.ThrottleRestore)
	gctx, gcancel := context.WithCancel(c.ctx)

	g := &providerGroup{
		name:          name,
		host:          p.Host,
		skipID:        providerSkipID(p),
		maxConns:      p.Connections,
		ctx:           gctx,
		reqCh:         make(chan *Request, p.Connections),
		prioCh:        make(chan *Request, p.Connections),
		hotReqCh:      make(chan *Request),
		hotPrioCh:     make(chan *Request),
		hotIdleBodyCh: make(chan *Request),
		bgCh:          make(chan *Request, p.Connections),
		gate:          gate,
		cancel:        gcancel,
		p:             p,
		quotaPeriod:   p.QuotaPeriod,
	}
	g.stats.quotaBytes = p.QuotaBytes
	g.stats.bgFloor = resolveBackgroundFloor(p.Connections, p.BackgroundFloor)
	if p.QuotaBytes > 0 {
		if p.QuotaUsed > 0 {
			g.stats.quotaUsed.Store(p.QuotaUsed)
			if p.QuotaUsed >= p.QuotaBytes {
				g.stats.quotaExceeded.Store(true)
			}
		}
		if p.QuotaPeriod > 0 {
			if !p.QuotaResetAt.IsZero() && p.QuotaResetAt.After(time.Now()) {
				g.quotaResetAt.Store(p.QuotaResetAt.UnixNano())
			} else {
				g.quotaResetAt.Store(time.Now().Add(p.QuotaPeriod).UnixNano())
			}
		}
	}

	return g, factory
}

// pingProviderGroup performs the startup handshake probe and records it on the
// group. Safe to run concurrently for distinct groups: it only touches g.stats
// for its own group, which no other goroutine reads until the group is stored.
func (c *Client) pingProviderGroup(g *providerGroup, p Provider, factory ConnFactory) {
	// Ping with a short timeout so we don't block forever.
	if p.SkipPing {
		return
	}
	pingCtx, pingCancel := context.WithTimeout(c.ctx, defaultHandshakeTimeout)
	g.stats.Ping = pingProvider(pingCtx, factory, p.Auth)
	pingCancel()
	// Seed the TTFB EWMA from the measured RTT so the adaptive attempt
	// timeout has a sensible starting point before any request completes.
	if g.stats.Ping.Err == nil && g.stats.Ping.RTT > 0 {
		g.stats.ttfbEWMA.Store(int64(g.stats.Ping.RTT))
	}
}

// launchConnSlots resolves the remaining per-connection settings and starts one
// slot goroutine per configured connection.
func (c *Client) launchConnSlots(g *providerGroup, p Provider, factory ConnFactory) {
	inflight := p.Inflight
	if inflight <= 0 {
		inflight = 1
	}
	// STAT (bodyless) may pipeline deeper than BODY. The overall pipeline cap is
	// max(Inflight, StatInflight); 0 or a smaller value means "same as Inflight"
	// (no separate STAT lane — fully backward compatible).
	statInflight := p.StatInflight
	if statInflight < inflight {
		statInflight = inflight
	}

	// Resolve the rolling stall timeout: 0 => default, negative => disabled.
	stall := p.StallTimeout
	if stall == 0 {
		stall = defaultStallTimeout
	} else if stall < 0 {
		stall = 0
	}

	// Resolve keepalive settings. If SkipPing is true and no explicit command
	// is set, keepalive is disabled (we don't know which command the server supports).
	kaInterval := p.KeepaliveInterval
	kaCmd := p.KeepaliveCommand
	if kaInterval > 0 {
		if kaCmd == "" {
			if p.SkipPing {
				kaInterval = 0 // disable: no safe probe command known
			} else {
				kaCmd = "DATE"
			}
		}
	}

	minConns := p.MinConnections
	if minConns > p.Connections {
		minConns = p.Connections
	}
	streamInflight := p.StreamInflight
	if streamInflight <= 0 {
		streamInflight = min(inflight, defaultStreamInflight)
	}
	if streamInflight >= inflight {
		streamInflight = 0 // no separate bound
	}
	abortDrain := p.AbortDrainBytes
	switch {
	case abortDrain == 0:
		abortDrain = defaultAbortDrainBytes
	case abortDrain < 0:
		abortDrain = 0
	}
	for i := range p.Connections {
		preWarm := i < minConns
		idleTimeout := p.IdleTimeout
		if preWarm {
			// Pre-warmed slots stay connected indefinitely; idle disconnect
			// would defeat the point of keeping a minimum floor hot.
			idleTimeout = 0
		}
		c.wg.Add(1)
		go runConnSlot(g.ctx, g.reqCh, g.prioCh, g.hotReqCh, g.hotPrioCh, g.hotIdleBodyCh, g.bgCh, factory, inflight, statInflight, streamInflight, abortDrain, p.Auth, p.UserAgent, idleTimeout, stall, kaInterval, kaCmd, g.gate, &g.stats, g.name, &c.wg, preWarm)
	}
}

func NewClient(ctx context.Context, providers []Provider, opts ...ClientOption) (*Client, error) {
	if len(providers) == 0 {
		return nil, fmt.Errorf("nntp: at least one provider is required")
	}

	// Require at least one main (non-backup) provider.
	hasMain := false
	for _, p := range providers {
		if !p.Backup {
			hasMain = true
			break
		}
	}
	if !hasMain {
		return nil, fmt.Errorf("nntp: at least one non-backup provider is required")
	}

	// Validation only — no TCP connections are created here.
	seen := make(map[string]struct{}, len(providers))
	for i, p := range providers {
		if p.Connections <= 0 {
			return nil, fmt.Errorf("nntp: provider connections must be > 0")
		}
		if p.MinConnections < 0 || p.MinConnections > p.Connections {
			return nil, fmt.Errorf("nntp: provider min connections must be between 0 and connections")
		}
		if p.Factory == nil && p.Host == "" {
			return nil, fmt.Errorf("nntp: provider must have Host or Factory")
		}
		name := resolveProviderName(p, i)
		if _, dup := seen[name]; dup {
			return nil, fmt.Errorf("nntp: provider %q already exists", name)
		}
		seen[name] = struct{}{}
	}

	if ctx == nil {
		ctx = context.Background()
	}
	ctx, cancel := context.WithCancel(ctx)

	var cfg clientConfig
	for _, opt := range opts {
		opt(&cfg)
	}

	c := &Client{
		ctx:        ctx,
		cancel:     cancel,
		dispatch:   cfg.dispatch,
		statProbe:  !cfg.statProbeOff,
		speedAware: !cfg.speedAwareOff,
		startTime:  time.Now(),
	}
	// Initialize empty slices.
	c.mainGroups.Store(&[]*providerGroup{})
	c.backupGroups.Store(&[]*providerGroup{})

	// Build every group first (no I/O), then ping them all at once. The ping is
	// bounded by defaultHandshakeTimeout per provider, so doing it serially cost
	// N × 10s when the hosts are unreachable — with callers (AltMount) holding a
	// write lock across NewClient, that stalled the whole app on a config
	// change. Concurrent pings make the worst case ~one timeout, not N.
	groups := make([]*providerGroup, len(providers))
	factories := make([]ConnFactory, len(providers))
	for pi, p := range providers {
		groups[pi], factories[pi] = c.newProviderGroup(p, pi)
	}
	var pingWG sync.WaitGroup
	for pi, p := range providers {
		pingWG.Add(1)
		go func() {
			defer pingWG.Done()
			c.pingProviderGroup(groups[pi], p, factories[pi])
		}()
	}
	pingWG.Wait()

	// Slots start only once every ping has landed, matching the previous
	// ordering: g.stats.Ping is complete before any request can be dispatched.
	var mainGs, backupGs []*providerGroup
	for pi, p := range providers {
		g := groups[pi]
		c.launchConnSlots(g, p, factories[pi])
		if p.Backup {
			backupGs = append(backupGs, g)
		} else {
			mainGs = append(mainGs, g)
		}
	}
	c.mainGroups.Store(&mainGs)
	c.backupGroups.Store(&backupGs)

	return c, nil
}

// Close cancels the client, stops all provider gates, and waits for all
// connection slots to stop. Slots manage their own TCP connection cleanup.
// Context cancellation (c.cancel) cascades to all group contexts, so closing
// reqCh is unnecessary and avoids a race with stale-snapshot senders.
func (c *Client) Close() error {
	c.cancel()
	for _, gs := range []*[]*providerGroup{c.mainGroups.Load(), c.backupGroups.Load()} {
		for _, g := range *gs {
			g.gate.stop()
		}
	}
	c.wg.Wait()
	return nil
}

func (c *Client) Send(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta ...func(YEncMeta)) <-chan Response {
	respCh := make(chan Response, 1)
	if ctx == nil {
		ctx = context.Background()
	}

	var metaFn func(YEncMeta)
	if len(onMeta) > 0 {
		metaFn = onMeta[0]
	}

	go c.sendWithRetry(ctx, payload, bodyWriter, metaFn, respCh)
	return respCh
}

// SendPriority is like Send but enqueues the request on the priority channel,
// so idle connections will pick it up before normal requests.
func (c *Client) SendPriority(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta ...func(YEncMeta)) <-chan Response {
	respCh := make(chan Response, 1)
	if ctx == nil {
		ctx = context.Background()
	}

	var metaFn func(YEncMeta)
	if len(onMeta) > 0 {
		metaFn = onMeta[0]
	}

	go c.doSendWithRetry(ctx, payload, bodyWriter, metaFn, respCh, lanePriority)
	return respCh
}

// SendBackground is like Send but enqueues the request on the background
// lane, which connections read only when the priority and normal lanes are
// empty and which is capped per provider while foreground traffic is recent.
func (c *Client) SendBackground(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta ...func(YEncMeta)) <-chan Response {
	respCh := make(chan Response, 1)
	if ctx == nil {
		ctx = context.Background()
	}

	var metaFn func(YEncMeta)
	if len(onMeta) > 0 {
		metaFn = onMeta[0]
	}

	go c.doSendWithRetry(ctx, payload, bodyWriter, metaFn, respCh, laneBackground)
	return respCh
}

// sendSync runs the full send path on the caller's goroutine and returns the
// reply.
//
// Send and SendPriority spawn a goroutine and hand back a channel, which is
// what a caller that wants to get on with something else meanwhile needs. A
// caller that blocks on the reply immediately does not: the goroutine is pure
// overhead, and on a sweep that is one goroutine per message-id.
func (c *Client) sendSync(ctx context.Context, payload []byte, ln lane) Response {
	if ctx == nil {
		ctx = context.Background()
	}
	respCh := make(chan Response, 1)
	c.doSendWithRetry(ctx, payload, nil, nil, respCh, ln)
	return <-respCh
}

// extractProbeMsgID returns the "<id@host>" message-ID from a BODY, HEAD, or
// ARTICLE payload, or nil when the payload has no message-ID (GROUP, DATE, …)
// or when the command is already STAT or POST (probing would be redundant or
// inapplicable).
func extractProbeMsgID(payload []byte) []byte {
	if len(payload) == 0 {
		return nil
	}
	// Reject commands where probing is irrelevant or already done.
	switch {
	case len(payload) >= 4 && (payload[0]|0x20) == 's' && (payload[1]|0x20) == 't' &&
		(payload[2]|0x20) == 'a' && (payload[3]|0x20) == 't':
		return nil // STAT
	case len(payload) >= 4 && (payload[0]|0x20) == 'p' && (payload[1]|0x20) == 'o' &&
		(payload[2]|0x20) == 's' && (payload[3]|0x20) == 't':
		return nil // POST
	}
	open := bytes.IndexByte(payload, '<')
	if open < 0 {
		return nil
	}
	close := bytes.IndexByte(payload[open:], '>')
	if close < 0 {
		return nil
	}
	return payload[open : open+close+1]
}

// probeResult carries the outcome of one parallel STAT probe.
type probeResult struct {
	g         *providerGroup
	resp      Response
	ok        bool
	cancelled bool
}

// raceCandidates probes candidates in parallel with STAT on the priority lane,
// then sends the real payload to the first provider that confirms 223.
// All-miss latency is max-of-RTTs instead of sum-of-RTTs.
//
// Note: when all probes miss, respCh is NOT written; the caller must deliver
// the saved 430 response from the first provider that triggered the race.
func (c *Client) raceCandidates(
	ctx context.Context,
	candidates []*providerGroup,
	statPayload, payload []byte,
	bodyWriter io.Writer,
	onMeta func(YEncMeta),
	skipHosts *[4]string,
	skipCount *int,
	respCh chan<- Response,
) (delivered, cancelled bool, lastErr error) {
	// Filter to live candidates (skip same hosts and quota-exceeded).
	live := make([]*providerGroup, 0, len(candidates))
	seen := make(map[string]bool)
	for _, g := range candidates {
		if hostSkipped(g.skipID, skipHosts, *skipCount) {
			continue
		}
		if g.isQuotaExceeded() {
			lastErr = fmt.Errorf("%s: %w", g.name, ErrQuotaExceeded)
			continue
		}
		if g.host != "" && seen[g.host] {
			continue
		}
		if g.host != "" {
			seen[g.host] = true
		}
		live = append(live, g)
	}

	if len(live) == 0 {
		return false, false, lastErr
	}

	// Single live candidate: skip the probe RTT and send the real payload directly.
	if len(live) == 1 {
		g := live[0]
		resp, ok, done := c.tryGroup(ctx, g, payload, bodyWriter, onMeta, lanePriority)
		if done {
			return false, true, lastErr
		}
		if !ok {
			keepErr(&lastErr, resp)
			return false, false, lastErr
		}
		if resp.Err != nil {
			return false, false, resp.Err
		}
		if resp.StatusCode == 502 {
			_ = c.RemoveProvider(g.name)
			if g.p.ReconnectDelay > 0 {
				c.scheduleReconnect(g)
			}
			return false, false, fmt.Errorf("%s: %w", g.name, ErrServiceUnavailable)
		}
		if resp.StatusCode == 430 || resp.StatusCode == 423 {
			if g.skipID != "" && *skipCount < len(skipHosts) {
				skipHosts[*skipCount] = g.skipID
				*skipCount++
			}
			c.nextIdx.Add(1)
			return false, false, lastErr
		}
		respCh <- resp
		return true, false, lastErr
	}

	// ≥2 candidates: probe all in parallel.
	results := make(chan probeResult, len(live))
	for _, g := range live {
		go func(g *providerGroup) {
			resp, ok, done := c.tryGroup(ctx, g, statPayload, nil, nil, lanePriority)
			results <- probeResult{g: g, resp: resp, ok: ok, cancelled: done}
		}(g)
	}

	// Collect ALL probe results before acting on the winner, so that side
	// effects like 502 provider removal are applied regardless of order.
	var winner *providerGroup
	for range live {
		pr := <-results
		if pr.cancelled {
			cancelled = true
			continue
		}
		if !pr.ok {
			keepErr(&lastErr, pr.resp)
			continue
		}
		if pr.resp.Err != nil {
			lastErr = pr.resp.Err
			continue
		}
		g := pr.g
		switch pr.resp.StatusCode {
		case 502:
			_ = c.RemoveProvider(g.name)
			if g.p.ReconnectDelay > 0 {
				c.scheduleReconnect(g)
			}
			lastErr = fmt.Errorf("%s: %w", g.name, ErrServiceUnavailable)
		case 430, 423:
			if g.skipID != "" && *skipCount < len(skipHosts) {
				skipHosts[*skipCount] = g.skipID
				*skipCount++
			}
			c.nextIdx.Add(1)
		case 223:
			if winner == nil {
				winner = g // first 223; keep collecting for 502s
			}
		default:
			lastErr = fmt.Errorf("%s: unexpected STAT probe status %d", g.name, pr.resp.StatusCode)
		}
	}

	if cancelled {
		return false, true, lastErr
	}

	if winner == nil {
		return false, false, lastErr
	}

	// Send the real payload to the winner on the priority lane.
	resp, ok, done := c.tryGroup(ctx, winner, payload, bodyWriter, onMeta, lanePriority)
	if done {
		return false, true, lastErr
	}
	if !ok {
		keepErr(&lastErr, resp)
		return false, false, lastErr
	}
	if resp.Err != nil {
		// A committed attempt with a caller writer already streamed partial
		// bytes; deliver the error rather than letting the caller re-stream
		// into the same writer on another provider.
		if bodyWriter != nil && attemptCommittedResp(resp) {
			respCh <- resp
			return true, false, nil
		}
		return false, false, resp.Err
	}
	if resp.StatusCode == 430 || resp.StatusCode == 423 {
		// Rare: article expired between STAT and BODY.
		if winner.skipID != "" && *skipCount < len(skipHosts) {
			skipHosts[*skipCount] = winner.skipID
			*skipCount++
		}
		c.nextIdx.Add(1)
		return false, false, lastErr
	}
	if resp.StatusCode == 502 {
		_ = c.RemoveProvider(winner.name)
		if winner.p.ReconnectDelay > 0 {
			c.scheduleReconnect(winner)
		}
		return false, false, fmt.Errorf("%s: %w", winner.name, ErrServiceUnavailable)
	}
	respCh <- resp
	return true, false, lastErr
}

// minDeadline returns d, unless other is an earlier deadline (when hasOther),
// in which case it returns other. The bool is always true (a deadline exists).
func minDeadline(d, other time.Time, hasOther bool) (time.Time, bool) {
	if hasOther && other.Before(d) {
		return other, true
	}
	return d, true
}

// writeDeadline is the deadline used while writing the request payload: the
// earlier of the caller's ctx deadline and the attempt deadline, so a dead
// socket cannot hang the writer. Payloads are tiny, so the attempt deadline is
// a safe upper bound.
func (req *Request) writeDeadline() (time.Time, bool) {
	dl, ok := req.Ctx.Deadline()
	if !req.attemptDeadline.IsZero() && (!ok || req.attemptDeadline.Before(dl)) {
		return req.attemptDeadline, true
	}
	return dl, ok
}

// tryGroup dispatches a single request to a provider group and waits for the
// response. priority=true routes through the priority channels.
//
// The attempt timeout bounds only dispatch + time-to-first-response-byte, not
// the whole body transfer: the request ctx carries no fixed deadline, and a
// timer races the response. If the timer fires before any byte arrives the
// attempt is abandoned (CAS pending→abandoned) and we fail over; if the reader
// committed first (it saw a byte) we keep waiting for the body to finish, since
// failing over after partial delivery would corrupt a caller's writer.
func (c *Client) tryGroup(
	ctx context.Context,
	g *providerGroup,
	payload []byte,
	bodyWriter io.Writer,
	onMeta func(YEncMeta),
	ln lane,
) (resp Response, ok bool, done bool) {
	return c.tryGroupTimeout(ctx, g, payload, bodyWriter, onMeta, ln, 0)
}

// tryGroupTimeout is tryGroup with an explicit attempt window — the seam that
// lets tryGroupResilient escalate the window on response-phase expiry instead
// of abandoning a slow-but-honest server at the adaptive default.
func (c *Client) tryGroupTimeout(
	ctx context.Context,
	g *providerGroup,
	payload []byte,
	bodyWriter io.Writer,
	onMeta func(YEncMeta),
	ln lane,
	attemptTimeout time.Duration,
) (resp Response, ok bool, done bool) {
	attemptTimeout = g.windowOr(attemptTimeout)
	reqCtx, reqCancel := context.WithCancel(ctx)
	defer reqCancel()

	innerCh := make(chan Response, 1)
	req := &Request{
		Ctx:             reqCtx,
		Payload:         payload,
		RespCh:          innerCh,
		BodyWriter:      bodyWriter,
		OnMeta:          onMeta,
		attemptDeadline: time.Now().Add(attemptTimeout),
		attemptWindow:   attemptTimeout,
		providerName:    g.name,
		lane:            ln,
	}

	// The attempt timer bounds dispatch plus time-to-first-byte. A background
	// request is meant to wait — the lane holds it back for as long as
	// foreground traffic keeps the gate closed — so queue time must not count
	// against it: no client-side timer and no dispatch deadline. Once a
	// connection starts draining its reply, the reader still applies
	// attemptWindow from that moment, so a hung server is caught as usual.
	timer := time.NewTimer(attemptTimeout)
	defer timer.Stop()
	var timerC <-chan time.Time
	if ln == laneBackground {
		req.attemptDeadline = time.Time{}
	} else {
		timerC = timer.C
	}

	var hotCh chan *Request
	var coldCh chan *Request
	switch ln {
	case lanePriority:
		hotCh = g.hotPrioCh
		coldCh = g.prioCh
	case laneBackground:
		// No hot variant: background work is never worth waking a cold slot
		// ahead of anything, and a nil hotCh is never ready in the select.
		coldCh = g.bgCh
	default:
		hotCh = g.hotReqCh
		coldCh = g.reqCh
	}

	// A priority BODY prefers a connection with no body in flight. NNTP replies
	// are FIFO per connection, so landing behind an in-flight article costs the
	// whole transfer. Only body-free connections read hotIdleBodyCh, and only
	// while they stay body-free, so nothing is reserved: when no priority body
	// is in play nothing is ever sent here and every connection serves the
	// normal lane exactly as before.
	dispatched := false
	if ln == lanePriority && !isCheapCommand(payload) {
		select {
		case g.hotIdleBodyCh <- req:
			dispatched = true
		default:
		}
	}

	if !dispatched {
		select {
		case hotCh <- req:
		default:
			select {
			case <-c.ctx.Done():
				return Response{}, false, true
			case <-reqCtx.Done():
				return Response{}, false, ctx.Err() != nil
			case <-g.ctx.Done():
				return Response{}, false, false
			case <-timerC:
				// Could not be dispatched within the attempt window: the provider
				// is saturated. Fail over — with the reason preserved, so the
				// terminal error names the saturation instead of arriving bare.
				return Response{Err: &AttemptTimeoutError{Provider: g.name, Timeout: attemptTimeout, Phase: PhaseDispatch}}, false, false
			case coldCh <- req:
			}
		}
	}

	for {
		select {
		case resp, ok = <-innerCh:
			return resp, ok, false
		case <-c.ctx.Done():
			return Response{}, false, true
		case <-g.ctx.Done():
			return Response{}, false, false
		case <-reqCtx.Done():
			return Response{}, false, ctx.Err() != nil
		case <-timerC:
			if req.attemptState.CompareAndSwap(attemptPending, attemptAbandoned) {
				// No response byte arrived in time: hung or too-slow to start.
				// Cancel so the reader drops the request, and fail over. The
				// typed error both survives into the terminal error and lets
				// tryGroupResilient escalate the window — this phase cannot
				// distinguish a hung connection from a server legitimately
				// taking longer than the window to produce its status line.
				reqCancel()
				// done only when the caller's or the pool's context was
				// cancelled (true shutdown), not on a plain attempt timeout.
				return Response{Err: &AttemptTimeoutError{Provider: g.name, Timeout: attemptTimeout, Phase: PhaseResponse}}, false, ctx.Err() != nil || c.ctx.Err() != nil
			}
			// Reader already committed (first byte arrived): the body is
			// streaming. Do not fail over; keep waiting for it to finish. The
			// timer has fired and will not fire again.
		}
	}
}

// providerSkipID returns the identity used to suppress further attempts after a
// 430. Providers on the same host share article availability; so do providers
// that resell the same upstream storage under different hostnames (e.g. two
// brands on one backbone). StorageGroup lets an operator declare the latter.
func providerSkipID(p Provider) string {
	if p.StorageGroup != "" {
		return p.StorageGroup
	}
	return p.Host
}

// hostSkipped reports whether the skip identity is already in the skip list.
// Empty identities (Factory-based providers with no StorageGroup) are never skipped.
func hostSkipped(host string, skipHosts *[4]string, count int) bool {
	if host == "" || count == 0 {
		return false
	}
	for i := range count {
		if skipHosts[i] == host {
			return true
		}
	}
	return false
}

// attemptCommittedResp reports whether the response came from an attempt that
// had already started streaming bytes (the reader committed). Such an attempt
// must not be retried or failed over when a caller-supplied writer is in use.
func attemptCommittedResp(resp Response) bool {
	return resp.Request != nil && resp.Request.attemptState.Load() == attemptCommitted
}

// maxSpeedScore is the highest multiplier speed-aware dispatch applies to a
// provider's base (capacity) weight.
const maxSpeedScore = 4

// dispatchWeights computes cumulative round-robin weights for the given main
// providers. The base weight is each provider's available connection capacity
// (min 1 when live); quota-exceeded providers get weight 0. When speedAware is
// true the base weight is scaled by speedScore so faster providers receive
// proportionally more traffic. With no throughput samples this reduces to pure
// capacity weighting (the historical behavior).
func dispatchWeights(mains []*providerGroup, speedAware bool) (cum []int, total int) {
	cum = make([]int, len(mains))
	var maxSpeed float64
	if speedAware {
		for _, g := range mains {
			if s := speedEWMABytesPerSec(&g.stats); s > maxSpeed {
				maxSpeed = s
			}
		}
	}
	for i, g := range mains {
		w := 0
		if !g.isQuotaExceeded() {
			w = max(1, int(g.gate.available.Load()))
			if speedAware && maxSpeed > 0 {
				w *= speedScore(speedEWMABytesPerSec(&g.stats), maxSpeed)
			}
		}
		total += w
		cum[i] = total
	}
	return cum, total
}

// speedScore maps a provider's throughput to an integer multiplier in
// [1, maxSpeedScore] relative to the fastest provider. An unmeasured provider
// (speed 0) scores the maximum so it is not starved before it has a sample.
func speedScore(speed, maxSpeed float64) int {
	if speed <= 0 {
		return maxSpeedScore
	}
	s := int(float64(maxSpeedScore)*speed/maxSpeed + 0.5)
	if s < 1 {
		return 1
	}
	if s > maxSpeedScore {
		return maxSpeedScore
	}
	return s
}

func (c *Client) sendWithRetry(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta func(YEncMeta), respCh chan Response) {
	c.doSendWithRetry(ctx, payload, bodyWriter, onMeta, respCh, laneNormal)
}

// tryGroupResilient retries a single provider on a fresh connection when a
// pooled connection dies mid-request (stale socket the server already
// closed). Without this, a single-provider pool fails immediately with
// "all providers exhausted: ... connection died" because there is no next
// provider to fall back to. Bounded so a genuinely-down server still fails
// fast. Only transport-level connection death is retried (see
// isConnectionDeathError); 430/502/quota and provider removal (!ok) keep
// their existing behavior.
func (c *Client) tryGroupResilient(
	ctx context.Context,
	g *providerGroup,
	payload []byte,
	bodyWriter io.Writer,
	onMeta func(YEncMeta),
	ln lane,
	window time.Duration,
) (resp Response, ok bool, cancelled bool) {
	// window == 0 uses the provider's adaptive attempt window. A positive
	// window is the escalation seam — see doSendWithRetry: a pass in which
	// EVERY provider expired awaiting its first response byte is re-run once
	// with a wider window, because that shape is a slow-but-honest answer
	// (cold spool lookups for aged articles measure ~7.5s to a 430 while the
	// TTFB EWMA — dominated by cache-hot serving — derives a 2s window) at
	// least as often as it is dead infrastructure. Failover order is
	// untouched: within a pass a quiet provider still costs one base window.
	timeout := g.windowOr(window)
	for r := 0; ; r++ {
		resp, ok, cancelled = c.tryGroupTimeout(ctx, g, payload, bodyWriter, onMeta, ln, timeout)
		if cancelled {
			return
		}
		if expiredAwaitingResponse(resp, ok) {
			// Normalize both expiry surfaces (the attempt timer, or the
			// connection's own read deadline racing it) into one shape: a
			// failover carrying the typed story — never a deliverable
			// response, never a bare error. Deliberately NOT retried on a
			// fresh connection (the read-deadline surface used to fall
			// through to the isConnectionDeathError retry below): re-dialing
			// restarts the server-side lookup under the same window it just
			// expired against, so it can only re-pay the dial to learn
			// nothing. Failing over — and, on the mains path, the escalated
			// pass — answers the same question wider instead.
			var at *AttemptTimeoutError
			if !errors.As(resp.Err, &at) {
				resp.Err = &AttemptTimeoutError{Provider: g.name, Timeout: timeout, Phase: PhaseResponse, Cause: resp.Err}
			}
			return resp, false, false
		}
		if !ok {
			return
		}
		// If the attempt already streamed bytes into the caller's writer, never
		// retry: partial data was delivered and re-streaming would corrupt it.
		// Buffered requests (bodyWriter == nil) keep their per-attempt buffer,
		// so retrying them stays safe.
		if bodyWriter != nil && attemptCommittedResp(resp) {
			return
		}
		if r < maxConnDiedRetries && isConnectionDeathError(resp.Err) {
			continue // dead connection drained; retry fresh on same provider
		}
		return
	}
}

// responseExpiry returns the response-phase attempt timeout err carries, if
// any. The single definition of "this attempt expired awaiting its first
// response byte" — both the failover check and the escalation bookkeeping
// (which also needs the expired window) read it through here.
func responseExpiry(err error) (*AttemptTimeoutError, bool) {
	var at *AttemptTimeoutError
	if errors.As(err, &at) && at.Phase == PhaseResponse {
		return at, true
	}
	return nil, false
}

// isTimeoutErr reports whether err is a transport-level timeout. Distinct from
// isConnectionDeathError, which treats any net.Error as a dead socket.
func isTimeoutErr(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// keepErr preserves a failover attempt's reason in dst. An attempt that fails
// over (!ok) may now carry a typed reason; dropping it is what made an
// all-attempts-expired request surface as a bare "all providers exhausted".
func keepErr(dst *error, resp Response) {
	if resp.Err != nil {
		*dst = resp.Err
	}
}

// expiredAwaitingResponse reports whether an attempt died awaiting its first
// response byte — whichever side noticed first: the attempt timer (ok=false,
// typed error) or the connection's own read deadline (ok=true, a transport
// timeout on a request whose reader never committed).
func expiredAwaitingResponse(resp Response, ok bool) bool {
	if !ok {
		_, expired := responseExpiry(resp.Err)
		return expired
	}
	// Anything short of committed counts: the attempt timer may have CASed
	// pending→abandoned in the same instant the reader delivered its deadline
	// error — both interleavings mean "no first byte ever arrived".
	if resp.Err == nil || resp.Request == nil || resp.Request.attemptState.Load() == attemptCommitted {
		return false
	}
	return isTimeoutErr(resp.Err)
}

func (c *Client) doSendWithRetry(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta func(YEncMeta), respCh chan Response, ln lane) {
	defer close(respCh)

	// Precompute for STAT probe: extract message-ID once.
	msgID := extractProbeMsgID(payload)
	raceable := c.statProbe && msgID != nil
	var statPayload []byte
	if raceable {
		statPayload = append(append([]byte("STAT "), msgID...), "\r\n"...)
	}

	var lastResp Response
	hasResp := false
	var lastErr error
	post430 := false

	// Track providers that returned 430 so we can skip others sharing the same
	// article availability — same host (different credentials won't help), or
	// same declared StorageGroup (same upstream storage behind another brand).
	var skipHosts [4]string
	skipCount := 0

	// 1. Try all main providers.
	mains := *c.mainGroups.Load()
	n := len(mains)
	if n == 0 {
		respCh <- Response{Err: errors.New("nntp: no main providers")}
		return
	}

	// Pick start index based on dispatch strategy.
	var start int
	switch c.dispatch {
	case DispatchFIFO:
		// Priority order: first provider with available capacity and within quota,
		// falling back to provider 0 if all are saturated or exceeded.
		for i, g := range mains {
			if g.gate.available.Load() > 0 && !g.isQuotaExceeded() {
				start = i
				break
			}
		}
	default: // DispatchRoundRobin
		// Dynamic weighted round-robin. Quota-exceeded providers get weight 0
		// so they are never selected during normal dispatch.
		cumWeights, totalW := dispatchWeights(mains, c.speedAware)
		if totalW == 0 {
			// All providers are quota-exceeded; start at 0 and let the main
			// loop below return ErrQuotaExceeded for each.
			start = 0
		} else {
			slot := int(c.nextIdx.Add(1) % uint64(totalW))
			start = sort.SearchInts(cumWeights, slot+1)
		}
	}

	// Escalation: pass 0 runs every provider at its base attempt window (a
	// quiet provider costs one window before failover, exactly as before).
	// Only when the WHOLE pass produced nothing but expired-awaiting-response
	// failures — the signature of a slow spool lookup, not of dead infra — is
	// it re-run ONCE, all expired providers sharing a single wall-clock budget
	// (escalationFactor × the widest expired window, capped at
	// maxAttemptTimeout), so a server that needs 7s to say 430 gets heard
	// while a genuinely hung pool costs at most one budget of extra latency,
	// however many providers it holds.
	// expiredWin holds each provider's response-phase expiry window (0 = none),
	// keyed by index into mains. Allocated only once escalation is actually in
	// play — the pathological case — so the common download path stays
	// allocation-free here.
	var expiredWin []time.Duration
	var widest time.Duration // widest expiry seen this pass; 0 = none
	escalated := false
	var budget time.Duration
	// One clock read per pass, taken when the pass is decided on, rather than
	// one per provider inside it.
	var now time.Time
	for {
		sawOther := false
		for attempt := range n {
			idx := (start + attempt) % n
			g := mains[idx]
			var window time.Duration // 0 = the provider's base window
			if escalated {
				// Cheapest discriminator first: a provider that answered
				// definitively in pass 0 needs no re-filtering at all.
				var mayEscalate bool
				if window, mayEscalate = g.escalationWindow(expiredWin[idx], budget, now); !mayEscalate {
					continue
				}
			}
			if hostSkipped(g.skipID, &skipHosts, skipCount) {
				continue
			}
			if g.isQuotaExceeded() {
				lastErr = fmt.Errorf("%s: %w", g.name, ErrQuotaExceeded)
				sawOther = true
				continue
			}
			var attemptStart time.Time
			if escalated {
				attemptStart = time.Now()
			}
			resp, ok, cancelled := c.tryGroupResilient(ctx, g, payload, bodyWriter, onMeta, ln.escalated(post430), window)
			if escalated {
				budget -= time.Since(attemptStart)
			}
			if cancelled {
				err := ctx.Err()
				if err == nil {
					err = c.ctx.Err()
				}
				respCh <- Response{Err: err}
				return
			}
			if !ok {
				// Connection died or the attempt window expired — try the next
				// provider, keeping the reason: an all-attempts-expired request
				// used to surface as a BARE "all providers exhausted", which
				// reads as total infrastructure death and hides the one thread
				// worth pulling (the per-attempt timeout).
				keepErr(&lastErr, resp)
				if at, expired := responseExpiry(resp.Err); expired {
					if expiredWin == nil {
						expiredWin = make([]time.Duration, n)
					}
					// Each idx is visited at most once per pass, so this is an
					// assignment, not an accumulation.
					expiredWin[idx] = at.Timeout
					widest = max(widest, at.Timeout)
					if escalated {
						// A widened window bought nothing. Enough of these in a
						// row and this provider stops being escalated, so a
						// sustained outage costs one base window per request
						// again instead of re-paying the budget every time.
						// Real clock, not the pass's hoisted `now`: this
						// STORES a future deadline, and the pass may have been
						// decided on a whole budget ago.
						g.noteEscalationFruitless(time.Now())
					}
				} else {
					sawOther = true
				}
				continue
			}
			// A definitive answer — including a body delivered on an escalated
			// window, the slow-spool case escalation exists for. The provider
			// is talking, so escalating it is worth paying for again.
			g.resetEscalationBreaker()
			sawOther = true
			if resp.Err != nil {
				// A committed attempt with a caller writer already streamed partial
				// bytes; deliver the error rather than re-streaming into the same
				// writer on another provider.
				if bodyWriter != nil && attemptCommittedResp(resp) {
					respCh <- resp
					return
				}
				lastErr = resp.Err
				continue
			}
			if resp.StatusCode == 502 {
				// Provider returned "service unavailable" — remove it from the
				// pool immediately so no further requests are routed to it.
				_ = c.RemoveProvider(g.name)
				if g.p.ReconnectDelay > 0 {
					c.scheduleReconnect(g)
				}
				lastErr = fmt.Errorf("%s: %w", g.name, ErrServiceUnavailable)
				continue
			}
			// 423 ("no article with that number") is the same miss as 430 for
			// dispatch purposes — errors.go folds both into ErrArticleNotFound
			// and raceCandidates already treats them alike. Handling only 430
			// here delivered a 423 straight to the caller with no failover to
			// the remaining mains or the backups.
			if resp.StatusCode == 430 || resp.StatusCode == 423 {
				c.nextIdx.Add(1) // bias next request away from this provider
				if g.skipID != "" && skipCount < len(skipHosts) {
					skipHosts[skipCount] = g.skipID
					skipCount++
				}
				lastResp = resp
				hasResp = true
				post430 = true

				if raceable {
					// Build remaining mains and race them in parallel via STAT.
					rest := make([]*providerGroup, 0, n-attempt-1)
					for a := attempt + 1; a < n; a++ {
						rest = append(rest, mains[(start+a)%n])
					}
					delivered, cancelled, raceErr := c.raceCandidates(
						ctx, rest, statPayload, payload, bodyWriter, onMeta,
						&skipHosts, &skipCount, respCh,
					)
					if cancelled {
						err := ctx.Err()
						if err == nil {
							err = c.ctx.Err()
						}
						respCh <- Response{Err: err}
						return
					}
					if delivered {
						return
					}
					if raceErr != nil {
						lastErr = raceErr
					}
					break // all remaining mains were probed in the race
				}
				continue
			}
			// Success.
			respCh <- resp
			return
		}
		// Escalate only on the pure slow-lookup signature: every attempted
		// provider expired awaiting its first response byte. Any other outcome
		// (430, quota, conn death, saturation) means the pass learned
		// something and the normal flow — backups, then the terminal error —
		// proceeds at once. One escalated pass only, and only when the budget
		// can actually widen at least one provider's window: when every base
		// window is already at maxAttemptTimeout there is nothing wider to
		// ask, and re-running an identical pass would buy latency, not
		// information.
		if escalated || widest == 0 || sawOther {
			break
		}
		budget = min(escalationFactor*widest, maxAttemptTimeout)
		// Only escalate if the budget can actually widen at least one
		// provider's window: when every base window already sits at
		// maxAttemptTimeout there is nothing wider to ask, and re-running an
		// identical pass would buy latency, not information. Same predicate
		// the escalated pass applies per provider, so the two cannot drift.
		now = time.Now()
		grows := false
		for i, w := range expiredWin {
			if _, ok := mains[i].escalationWindow(w, budget, now); ok {
				grows = true
				break
			}
		}
		if !grows {
			break
		}
		escalated = true
	}

	// 2. All main providers returned 430 (or died) — try backup providers.
	backups := *c.backupGroups.Load()
	if raceable && post430 {
		delivered, cancelled, raceErr := c.raceCandidates(
			ctx, backups, statPayload, payload, bodyWriter, onMeta,
			&skipHosts, &skipCount, respCh,
		)
		if cancelled {
			err := ctx.Err()
			if err == nil {
				err = c.ctx.Err()
			}
			respCh <- Response{Err: err}
			return
		}
		if delivered {
			return
		}
		if raceErr != nil {
			lastErr = raceErr
		}
	} else {
		for i := range backups {
			g := backups[i]
			if hostSkipped(g.skipID, &skipHosts, skipCount) {
				continue
			}
			if g.isQuotaExceeded() {
				lastErr = fmt.Errorf("%s: %w", g.name, ErrQuotaExceeded)
				continue
			}
			resp, ok, cancelled := c.tryGroupResilient(ctx, g, payload, bodyWriter, onMeta, ln.escalated(post430), 0)
			if cancelled {
				err := ctx.Err()
				if err == nil {
					err = c.ctx.Err()
				}
				respCh <- Response{Err: err}
				return
			}
			if !ok {
				keepErr(&lastErr, resp)
				continue
			}
			if resp.Err != nil {
				// A committed attempt with a caller writer already streamed
				// partial bytes; deliver the error rather than re-streaming into
				// the same writer on another provider.
				if bodyWriter != nil && attemptCommittedResp(resp) {
					respCh <- resp
					return
				}
				lastErr = resp.Err
				continue
			}
			if resp.StatusCode == 502 {
				_ = c.RemoveProvider(g.name)
				if g.p.ReconnectDelay > 0 {
					c.scheduleReconnect(g)
				}
				lastErr = fmt.Errorf("%s: %w", g.name, ErrServiceUnavailable)
				continue
			}
			// Deliver whatever backup returns (including 430).
			respCh <- resp
			return
		}
	}

	// 3. All providers exhausted — deliver the last 430, the last error, or a fallback.
	if hasResp {
		respCh <- lastResp
	} else if lastErr != nil {
		respCh <- Response{Err: fmt.Errorf("nntp: all providers exhausted: %w", lastErr)}
	} else {
		respCh <- Response{Err: errors.New("nntp: all providers exhausted")}
	}
}

// NumProviders returns the number of configured providers (main + backup).
func (c *Client) NumProviders() int {
	return len(*c.mainGroups.Load()) + len(*c.backupGroups.Load())
}

// Stats returns a snapshot of per-provider and aggregate metrics.
func (c *Client) Stats() ClientStats {
	elapsed := time.Since(c.startTime)
	secs := elapsed.Seconds()
	var cs ClientStats
	cs.Elapsed = elapsed
	var totalBytes int64
	for _, groups := range [...]*[]*providerGroup{c.mainGroups.Load(), c.backupGroups.Load()} {
		for _, g := range *groups {
			consumed := g.stats.BytesConsumed.Load()
			totalBytes += consumed
			maxSlots, running := g.gate.snapshot()
			quotaUsed := g.stats.quotaUsed.Load()
			ps := ProviderStats{
				Name:              g.name,
				SpeedEWMA:         speedEWMABytesPerSec(&g.stats),
				BytesConsumed:     consumed,
				Missing:           g.stats.Missing.Load(),
				Errors:            g.stats.Errors.Load(),
				ActiveConnections: running,
				MaxConnections:    maxSlots,
				AvailableSlots:    int(g.gate.available.Load()),
				TTFB:              time.Duration(g.stats.ttfbEWMA.Load()),
				Ping:              g.stats.Ping,
				QuotaBytes:        g.stats.quotaBytes,
				QuotaUsed:         quotaUsed,
				QuotaExceeded:     g.stats.quotaBytes > 0 && quotaUsed >= g.stats.quotaBytes,
			}
			if g.stats.quotaBytes > 0 && g.quotaPeriod > 0 {
				resetAt := g.quotaResetAt.Load()
				if resetAt > 0 {
					ps.QuotaResetAt = time.Unix(0, resetAt)
				}
			}
			if secs > 0 {
				ps.AvgSpeed = float64(consumed) / secs
			}
			cs.Providers = append(cs.Providers, ps)
		}
	}
	cs.BytesConsumed = totalBytes
	if secs > 0 {
		cs.AvgSpeed = float64(totalBytes) / secs
	}
	return cs
}

// AddProvider validates, pings, and registers a new provider at runtime.
// Ping failures are recorded in the group's stats but do not cause an error return.
func (c *Client) AddProvider(p Provider) error {
	if p.Connections <= 0 {
		return fmt.Errorf("nntp: provider connections must be > 0")
	}
	if p.MinConnections < 0 || p.MinConnections > p.Connections {
		return fmt.Errorf("nntp: provider min connections must be between 0 and connections")
	}
	if p.Factory == nil && p.Host == "" {
		return fmt.Errorf("nntp: provider must have Host or Factory")
	}

	idx := int(c.providerIdx.Add(1))
	name := resolveProviderName(p, idx)

	// Check for duplicate name.
	for _, gs := range [...]*[]*providerGroup{c.mainGroups.Load(), c.backupGroups.Load()} {
		for _, g := range *gs {
			if g.name == name {
				return fmt.Errorf("nntp: provider %q already exists", name)
			}
		}
	}

	g := c.startProviderGroup(p, idx)

	// Copy-on-write append.
	if p.Backup {
		old := c.backupGroups.Load()
		updated := make([]*providerGroup, len(*old)+1)
		copy(updated, *old)
		updated[len(*old)] = g
		c.backupGroups.Store(&updated)
	} else {
		old := c.mainGroups.Load()
		updated := make([]*providerGroup, len(*old)+1)
		copy(updated, *old)
		updated[len(*old)] = g
		c.mainGroups.Store(&updated)
	}
	return nil
}

// RemoveProvider stops and removes a provider by name.
// Goroutines wind down asynchronously; Client.Close still waits for all via c.wg.
func (c *Client) scheduleReconnect(g *providerGroup) {
	go func() {
		select {
		case <-c.ctx.Done():
			return
		case <-time.After(g.p.ReconnectDelay):
		}
		_ = c.AddProvider(g.p) // no-op if client closed or duplicate
	}()
}

func (c *Client) RemoveProvider(name string) error {
	for _, pair := range [...]struct {
		ptr *atomic.Pointer[[]*providerGroup]
	}{
		{&c.mainGroups},
		{&c.backupGroups},
	} {
		old := pair.ptr.Load()
		for i, g := range *old {
			if g.name != name {
				continue
			}
			// Found it — cancel context and stop gate. Context cancellation
			// is sufficient; runConnSlot goroutines exit via ctx.Done() and
			// tryGroup detects removal via g.ctx.Done().
			g.cancel()
			g.gate.stop()

			// Copy-on-write removal.
			updated := make([]*providerGroup, 0, len(*old)-1)
			updated = append(updated, (*old)[:i]...)
			updated = append(updated, (*old)[i+1:]...)
			pair.ptr.Store(&updated)
			return nil
		}
	}
	return fmt.Errorf("nntp: provider %q not found", name)
}

// ResetProviderQuota resets the download quota for the named provider without
// removing and re-adding it. The consumed-bytes counter and exceeded flag are
// cleared atomically, and a fresh reset deadline is scheduled when the provider
// has a non-zero quota period.
//
// Returns an error if no provider with that name is registered.
func (c *Client) ResetProviderQuota(name string) error {
	for _, ptr := range [...]*atomic.Pointer[[]*providerGroup]{
		&c.mainGroups,
		&c.backupGroups,
	} {
		for _, g := range *ptr.Load() {
			if g.name != name {
				continue
			}
			g.stats.quotaUsed.Store(0)
			g.stats.quotaExceeded.Store(false)
			if g.quotaPeriod > 0 {
				g.quotaResetAt.Store(time.Now().Add(g.quotaPeriod).UnixNano())
			} else {
				g.quotaResetAt.Store(0)
			}
			return nil
		}
	}
	return fmt.Errorf("nntp: provider %q not found", name)
}
