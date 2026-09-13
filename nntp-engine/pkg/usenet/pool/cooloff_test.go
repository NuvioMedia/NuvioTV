package pool

import (
	"testing"
	"time"
)

func TestProvider430CooloffArmAndClear(t *testing.T) {
	p := &Pool{}

	// Below threshold: no cooloff.
	for i := 0; i < providerCooloff430Threshold-1; i++ {
		p.record430Error("eweka")
	}
	if p.providerInCooloff("eweka") {
		t.Fatal("cooloff must not arm below the threshold")
	}

	// Crossing the threshold arms the cooloff window.
	p.record430Error("eweka")
	if !p.providerInCooloff("eweka") {
		t.Fatal("cooloff must arm at the threshold")
	}
	// Other providers unaffected.
	if p.providerInCooloff("newshosting") {
		t.Fatal("unrelated provider must not be in cooloff")
	}

	// 10 consecutive successes clear both the counter and the cooloff.
	for i := 0; i < 10; i++ {
		p.recordSuccess("eweka")
	}
	if p.providerInCooloff("eweka") {
		t.Fatal("cooloff must clear after sustained successes")
	}

	// An expired window no longer reports cooloff even without successes.
	p.mu.Lock()
	if p.cooloffUntil == nil {
		p.cooloffUntil = make(map[string]time.Time)
	}
	p.cooloffUntil["stale"] = time.Now().Add(-time.Second)
	p.mu.Unlock()
	if p.providerInCooloff("stale") {
		t.Fatal("expired cooloff window must not report as cooling")
	}
}
