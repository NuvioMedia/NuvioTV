package nntp

import (
	"sync"
	"time"
)

// minSpeedWindow: samples closer together than this report the previous rate
// rather than dividing by a near-zero duration.
const minSpeedWindow = 0.05

// maxSpeedDuration: a gap longer than this means nobody has been watching, so
// the rate is meaningless — rebase on the current total and report idle.
const maxSpeedDuration = 5.0

// speedDecay: how much of the previous rate survives a window that moved no
// bytes, so a line eases to zero instead of dropping out from under itself.
const speedDecay = 0.35

// SpeedMeter turns a cumulative byte counter into a rate in Mbps (mebibits),
// sampled whenever Rate is called. It is the one place that smoothing lives:
// provider pools and per-stream counters both meter through it, so their
// numbers are comparable rather than merely similar.
//
// Rate is stateful — each call closes the window the next one opens — so a
// counter must be metered from a single place, once per sampling tick.
type SpeedMeter struct {
	mu        sync.Mutex
	lastTotal int64
	lastCheck time.Time
	lastSpeed float64
}

// Rate reports the rate since the previous call, given the counter's total now.
func (m *SpeedMeter) Rate(total int64) float64 {
	return m.rateAt(total, time.Now())
}

func (m *SpeedMeter) rateAt(total int64, now time.Time) float64 {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.lastCheck.IsZero() {
		m.lastCheck = now
		m.lastTotal = total
		return 0
	}

	duration := now.Sub(m.lastCheck).Seconds()
	m.lastCheck = now

	if duration < minSpeedWindow {
		return m.lastSpeed
	}

	if duration > maxSpeedDuration {
		m.lastTotal = total
		m.lastSpeed = 0
		return 0
	}

	delta := total - m.lastTotal
	m.lastTotal = total

	if delta > 0 {
		m.lastSpeed = (float64(delta) * 8) / (1024 * 1024) / duration
	} else {
		m.lastSpeed *= speedDecay
		if m.lastSpeed < 0.1 {
			m.lastSpeed = 0
		}
	}
	return m.lastSpeed
}

// Rebase moves the meter's baseline to total without reporting a rate, for a
// counter that was restored rather than earned.
func (m *SpeedMeter) Rebase(total int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastTotal = total
}
