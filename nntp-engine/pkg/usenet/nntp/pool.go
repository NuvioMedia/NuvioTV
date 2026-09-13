package nntp

import (
	"context"
	"sync"
	"time"

	"streamnzb/pkg/core/logger"
)

type ClientPool struct {
	host    string
	port    int
	ssl     bool
	user    string
	pass    string
	maxConn int

	idleClients chan *Client
	slots       chan struct{}
	stopCh      chan struct{} // closed once by Shutdown(); never re-used

	bytesRead      int64
	totalBytesRead int64
	speed          SpeedMeter

	mu     sync.Mutex
	closed bool
}

func NewClientPool(host string, port int, ssl bool, user, pass string, maxConn int) *ClientPool {
	p := &ClientPool{
		host:        host,
		port:        port,
		ssl:         ssl,
		user:        user,
		pass:        pass,
		maxConn:     maxConn,
		idleClients: make(chan *Client, maxConn),
		slots:       make(chan struct{}, maxConn),
		stopCh:      make(chan struct{}),
	}

	for i := 0; i < maxConn; i++ {
		p.slots <- struct{}{}
	}

	go p.reaperLoop()
	return p
}

// SetProviderName names the pool for health reporting.
//
// It exists separately from SetUsageManager because the startup Validate dial
// happens before usage wiring, and that dial is the one most likely to meet a
// changed password: without a name at that point the rejection would be logged
// and the provider dropped, with nothing left for the user to see.
// dial opens one authenticated connection and reports what the handshake said
// about the account.
//
// Every caller took a slot before getting here and hands it back on failure,
// which is why this returns rather than releasing: the slot bookkeeping stays
// with the select arms that own it.
//
// The health verdict is taken here because this is the only place the AUTHINFO
// exchange happens, and an authentication code means something specific only in
// that exchange. A successful handshake clears any stored verdict — the account
// working is the one piece of evidence that outranks a stored complaint.
func (p *ClientPool) dial() (*Client, error) {
	c, err := NewClient(p.host, p.port, p.ssl)
	if err != nil {
		// A connect failure is inconclusive about credentials: the box may be
		// down, DNS may be lying, the network may be out. It never blocks. The
		// one exception worth recording is a 502 greeting — providers answer it
		// when the account is already at its connection limit, which is a
		// self-healing degraded state, not an outage.
		return nil, err
	}
	c.SetPool(p)
	if err := c.Authenticate(p.user, p.pass); err != nil {
		c.Quit()
		return nil, err
	}
	return c, nil
}

// Probe opens a throwaway authenticated connection to find out whether a
// blocked provider has started working again, then drops it.
//
// It deliberately bypasses the idle pool and the slot budget: a probe must not
// wait behind playback for a connection, and must not consume one either. The
// answer is the health verdict dial already recorded.
func (p *ClientPool) Probe(ctx context.Context) error {
	if p == nil {
		return nil
	}
	type result struct {
		c   *Client
		err error
	}
	done := make(chan result, 1)
	go func() {
		c, err := p.dial()
		done <- result{c: c, err: err}
	}()

	select {
	case <-ctx.Done():
		// The dial outlives the cancelled probe; close whatever it produces so
		// a slow provider cannot leak a connection per probe.
		go func() {
			if r := <-done; r.c != nil {
				r.c.Quit()
			}
		}()
		return ctx.Err()
	case r := <-done:
		if r.c != nil {
			r.c.Quit()
		}
		return r.err
	}
}

func (p *ClientPool) RestoreTotalBytes(total int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.totalBytesRead = total
	p.speed.Rebase(total)
}

func (p *ClientPool) TrackRead(n int) {
	p.mu.Lock()
	p.bytesRead += int64(n)
	p.totalBytesRead += int64(n)
	p.mu.Unlock()
}

func (p *ClientPool) GetSpeed() float64 {
	p.mu.Lock()
	total := p.totalBytesRead
	p.mu.Unlock()
	return p.speed.Rate(total)
}

func (p *ClientPool) TotalMegabytes() float64 {
	p.mu.Lock()
	totalBytesRead := p.totalBytesRead
	p.mu.Unlock()
	return float64(totalBytesRead) / (1024 * 1024)
}

// idleStaleThreshold: idle connections older than this are liveness-checked on
// checkout instead of trusted — providers drop idle TCP silently, and a dead
// pooled connection stalls its next command for the full 60s deadline.
const idleStaleThreshold = 60 * time.Second

// checkoutIdle validates a connection pulled from the idle channel. Dead ones
// are closed and their slot freed; the caller retries acquisition.
func (p *ClientPool) checkoutIdle(c *Client) *Client {
	if c.HealthyForCheckout(idleStaleThreshold) {
		return c
	}
	logger.Debug("NNTP pool discarding stale idle connection", "host", p.host)
	c.Quit()
	p.slots <- struct{}{}
	return nil
}

func (p *ClientPool) Get(ctx context.Context) (*Client, error) {
	logger.VerboseNNTP("nntp pool Get", "host", p.host)

	select {
	case <-ctx.Done():
		logger.VerboseNNTP("pool.Get ctx.Done (idle check)", "host", p.host)
		return nil, ctx.Err()
	case c := <-p.idleClients:
		logger.VerboseNNTP("nntp pool Get from idle", "host", p.host)
		if c = p.checkoutIdle(c); c == nil {
			return p.Get(ctx)
		}
		return c, nil
	default:
	}

	select {
	case <-ctx.Done():
		logger.VerboseNNTP("pool.Get ctx.Done (slot check)", "host", p.host)
		return nil, ctx.Err()
	case <-p.slots:

		c, err := p.dial()
		if err != nil {
			p.slots <- struct{}{}
			return nil, err
		}
		logger.VerboseNNTP("nntp pool Get new client", "host", p.host)
		return c, nil
	default:
	}

	waitStarted := time.Now()
	select {
	case <-ctx.Done():
		if wait := time.Since(waitStarted); wait >= 250*time.Millisecond {
			logger.Debug("NNTP pool wait exceeded threshold", "host", p.host, "wait", wait, "result", "context_canceled")
		}
		logger.VerboseNNTP("pool.Get ctx.Done (blocking)", "host", p.host)
		return nil, ctx.Err()
	case c := <-p.idleClients:
		if wait := time.Since(waitStarted); wait >= 250*time.Millisecond {
			logger.Debug("NNTP pool wait exceeded threshold", "host", p.host, "wait", wait, "result", "idle_client")
		}
		logger.VerboseNNTP("nntp pool Get from idle (after block)", "host", p.host)
		if c = p.checkoutIdle(c); c == nil {
			return p.Get(ctx)
		}
		return c, nil
	case <-p.slots:
		wait := time.Since(waitStarted)

		c, err := p.dial()
		if err != nil {
			p.slots <- struct{}{}
			return nil, err
		}
		if wait >= 250*time.Millisecond {
			logger.Debug("NNTP pool wait exceeded threshold", "host", p.host, "wait", wait, "result", "new_client")
		}
		logger.VerboseNNTP("nntp pool Get new client (after block)", "host", p.host)
		return c, nil
	}
}

func (p *ClientPool) TryGet(ctx context.Context) (*Client, bool) {

	select {
	case <-ctx.Done():
		return nil, false
	case c := <-p.idleClients:
		if c = p.checkoutIdle(c); c == nil {
			return p.TryGet(ctx)
		}
		return c, true
	default:
	}

	select {
	case <-ctx.Done():
		return nil, false
	case <-p.slots:
		c, err := p.dial()
		if err != nil {
			p.slots <- struct{}{}
			return nil, false
		}
		return c, true
	default:
		return nil, false
	}
}

func (p *ClientPool) Put(c *Client) {
	if c == nil {
		return
	}
	p.mu.Lock()
	closed := p.closed
	p.mu.Unlock()
	if closed {
		c.Quit()
		p.slots <- struct{}{}
		return
	}
	c.LastUsed = time.Now()
	logger.VerboseNNTP("nntp pool Put", "host", p.host)

	// Use stopCh as an extra guard: if Shutdown() fires in the window between
	// reading closed==false above and reaching this select, the stopCh case
	// prevents a panic that would occur if idleClients were closed.
	select {
	case p.idleClients <- c:
		// returned to idle
	case <-p.stopCh:
		// shutdown raced with Put; close and return slot
		c.Quit()
		p.slots <- struct{}{}
	default:
		logger.VerboseNNTP("nntp pool Put idle full, closing connection", "host", p.host)
		c.Quit()
		p.slots <- struct{}{}
	}
}

func (p *ClientPool) Discard(c *Client) {
	if c == nil {
		return
	}
	logger.VerboseNNTP("nntp pool Discard connection not returned to pool", "host", p.host)
	c.Quit()
	p.slots <- struct{}{}
}

func (p *ClientPool) reaperLoop() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop() // always release the timer

	const timeout = 30 * time.Second

	for {
		select {
		case <-p.stopCh:
			return
		case <-ticker.C:
		}

		count := len(p.idleClients)
		for i := 0; i < count; i++ {
			select {
			case c := <-p.idleClients:
				if time.Since(c.LastUsed) > timeout {
					c.Quit()
					p.slots <- struct{}{}
				} else {
					// Put connection back, but respect a concurrent Shutdown().
					select {
					case p.idleClients <- c:
					case <-p.stopCh:
						c.Quit()
						p.slots <- struct{}{}
						return
					}
				}
			default:
			}
		}
	}
}

func (p *ClientPool) Validate() error {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return p.ValidateContext(ctx)
}

func (p *ClientPool) ValidateContext(ctx context.Context) error {
	c, err := p.Get(ctx)
	if err != nil {
		return err
	}
	p.Put(c)
	return nil
}

func (p *ClientPool) Host() string {
	return p.host
}

func (p *ClientPool) MaxConn() int {
	return p.maxConn
}

func (p *ClientPool) TotalConnections() int {
	return p.maxConn - len(p.slots)
}

func (p *ClientPool) IdleConnections() int {
	return len(p.idleClients)
}

func (p *ClientPool) ActiveConnections() int {
	return p.TotalConnections() - p.IdleConnections()
}

func (p *ClientPool) Shutdown() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	p.mu.Unlock()

	// Signal reaperLoop and any racing Put() to stop.
	// idleClients is intentionally NOT closed here; closing it while Put() or
	// reaperLoop could still be writing to it causes a "send on closed channel"
	// panic.  Instead we drain it with non-blocking receives.
	close(p.stopCh)

	for {
		select {
		case c := <-p.idleClients:
			c.Quit()
		default:
			return
		}
	}
}
