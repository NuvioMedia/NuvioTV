package nntppool

import (
	"math"
	"sync/atomic"
	"time"
)

// ewmaAlphaNum/ewmaAlphaDen express the EWMA smoothing factor (α = 0.2) as a
// rational so the integer update can avoid floating point: new = old + α(sample-old).
const (
	ewmaAlphaNum = 1
	ewmaAlphaDen = 5
)

// PingResult holds the outcome of a DATE-based ping to a provider.
type PingResult struct {
	RTT        time.Duration
	ServerTime time.Time
	Err        error
}

// providerStats holds internal atomic counters for a single provider group.
// Used on the hot path — no mutex, atomic only.
type providerStats struct {
	BytesConsumed atomic.Int64 // wire bytes consumed (used to compute AvgSpeed)
	Missing       atomic.Int64 // 430/423 responses
	Errors        atomic.Int64 // network errors, bad status codes
	Ping          PingResult   // result of initial DATE ping

	// ttfbEWMA is the exponentially weighted moving average of observed
	// time-to-first-byte, in nanoseconds. 0 = no sample yet. Seeded from the
	// startup ping RTT. Drives the adaptive per-attempt timeout.
	ttfbEWMA atomic.Int64

	// speedEWMA is the EWMA of observed body throughput in bytes/sec, stored as
	// math.Float64bits. 0 = no sample yet. Drives speed-aware dispatch weights.
	speedEWMA atomic.Uint64

	// Quota tracking. quotaBytes is set once at group init (0 = unlimited).
	quotaBytes    int64
	quotaUsed     atomic.Int64 // bytes consumed in the current quota period
	quotaExceeded atomic.Bool  // cached flag: set when quotaUsed >= quotaBytes; cleared on period reset

	// Background lane gate. bgInflight counts background-lane requests this
	// group currently has on the wire; lastForeground is the Unix-nanosecond
	// time a priority- or normal-lane request was last written; bgFloor is how
	// many background requests may stay in flight while foreground traffic is
	// recent (see backgroundLaneFor). bgFloor is set once at group init.
	bgInflight     atomic.Int32
	lastForeground atomic.Int64
	bgFloor        int32

	// Escalation circuit breaker. escFruitless counts consecutive escalated
	// passes that expired without delivering anything; once it reaches
	// escalationBreakerThreshold, escSuppressedUntil holds the Unix-nanosecond
	// deadline before which this provider is not escalated again. Any
	// definitive answer from the provider clears both.
	//
	// Kept last, and widest-first, so these cold fields neither pad nor share
	// a cache line with ttfbEWMA/speedEWMA/quotaUsed, which are CAS-updated on
	// every transfer.
	escSuppressedUntil atomic.Int64
	escFruitless       atomic.Int32
}

// recordTTFB updates the provider's time-to-first-byte EWMA. sample is the
// measured payload-write → first-response-byte duration; non-positive samples
// (and absent stats) are ignored as "not measured".
func recordTTFB(stats *providerStats, sample time.Duration) {
	if stats == nil || sample <= 0 {
		return
	}
	s := int64(sample)
	for {
		old := stats.ttfbEWMA.Load()
		var next int64
		if old == 0 {
			next = s
		} else {
			// Rounded float update so a small delta isn't truncated to zero
			// (which would freeze the EWMA on very low-latency links).
			next = old + int64(math.Round(float64(s-old)*ewmaAlphaNum/ewmaAlphaDen))
		}
		if stats.ttfbEWMA.CompareAndSwap(old, next) {
			return
		}
	}
}

// recordSpeed updates the provider's throughput EWMA (bytes/sec) from a
// completed body transfer. Samples below speedSampleFloor bytes are ignored as
// noise (STAT/HEAD/430 responses).
func recordSpeed(stats *providerStats, bytes int64, elapsed time.Duration) {
	if stats == nil || bytes < speedSampleFloor || elapsed <= 0 {
		return
	}
	sample := float64(bytes) / elapsed.Seconds()
	for {
		oldBits := stats.speedEWMA.Load()
		old := math.Float64frombits(oldBits)
		var next float64
		if old == 0 {
			next = sample
		} else {
			next = old + (sample-old)*ewmaAlphaNum/ewmaAlphaDen
		}
		if stats.speedEWMA.CompareAndSwap(oldBits, math.Float64bits(next)) {
			return
		}
	}
}

// speedSampleFloor is the minimum body size (bytes) for a throughput sample to
// count, filtering out tiny control responses.
const speedSampleFloor = 16 * 1024

// speedEWMABytesPerSec returns the current throughput EWMA in bytes/sec, or 0
// when no sample has been recorded yet.
func speedEWMABytesPerSec(stats *providerStats) float64 {
	return math.Float64frombits(stats.speedEWMA.Load())
}

// ProviderStats is a public snapshot of one provider's metrics.
type ProviderStats struct {
	Name              string
	AvgSpeed          float64 // bytes/sec average since client start
	SpeedEWMA         float64 // bytes/sec recent throughput estimate (drives speed-aware dispatch); 0 = no sample
	BytesConsumed     int64   // raw wire bytes consumed since client start
	Missing           int64
	Errors            int64
	ActiveConnections int           // currently running connections
	MaxConnections    int           // configured connection slots
	AvailableSlots    int           // connection slots currently free (allowed - held)
	TTFB              time.Duration // recent time-to-first-byte estimate (seeded from ping RTT); 0 = no sample
	Ping              PingResult

	// Quota fields. QuotaBytes is 0 when no quota is configured.
	QuotaBytes    int64     // configured limit per period (0 = unlimited)
	QuotaUsed     int64     // bytes consumed in the current period
	QuotaResetAt  time.Time // when the quota period resets; zero if no period
	QuotaExceeded bool      // true when QuotaUsed >= QuotaBytes > 0
}

// ClientStats is an aggregate snapshot of all provider metrics.
type ClientStats struct {
	Providers     []ProviderStats
	AvgSpeed      float64       // total bytes/sec across all providers
	BytesConsumed int64         // raw wire bytes consumed across all providers
	Elapsed       time.Duration // time since client creation
}
