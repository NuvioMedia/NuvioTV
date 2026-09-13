package nntp

import (
	"math"
	"testing"
	"time"
)

func TestSpeedMeterFirstSampleEstablishesBaseline(t *testing.T) {
	var m SpeedMeter
	start := time.Now()
	if got := m.rateAt(1<<20, start); got != 0 {
		t.Fatalf("first sample = %v, want 0 (no window to measure)", got)
	}
	// One mebibyte over one second is eight mebibits per second.
	if got := m.rateAt(2<<20, start.Add(time.Second)); math.Abs(got-8) > 0.001 {
		t.Fatalf("rate = %v, want 8", got)
	}
}

func TestSpeedMeterDecaysWhenIdleAndZeroesOut(t *testing.T) {
	var m SpeedMeter
	start := time.Now()
	m.rateAt(0, start)
	first := m.rateAt(8<<20, start.Add(time.Second))

	idle := m.rateAt(8<<20, start.Add(2*time.Second))
	if math.Abs(idle-first*speedDecay) > 0.001 {
		t.Fatalf("idle rate = %v, want %v", idle, first*speedDecay)
	}

	at := start.Add(2 * time.Second)
	for i := 0; i < 20 && m.rateAt(8<<20, at) > 0; i++ {
		at = at.Add(time.Second)
	}
	if got := m.rateAt(8<<20, at); got != 0 {
		t.Fatalf("rate never reached 0, still %v", got)
	}
}

func TestSpeedMeterIgnoresTooShortAndTooLongWindows(t *testing.T) {
	var m SpeedMeter
	start := time.Now()
	m.rateAt(0, start)
	settled := m.rateAt(8<<20, start.Add(time.Second))

	short := m.rateAt(64<<20, start.Add(time.Second+10*time.Millisecond))
	if short != settled {
		t.Fatalf("sub-window rate = %v, want the previous %v", short, settled)
	}

	if got := m.rateAt(64<<20, start.Add(time.Minute)); got != 0 {
		t.Fatalf("rate after a %v gap = %v, want 0", time.Minute, got)
	}
}
