package messaging

import (
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"
)

// ProgressWatchdog answers one question only: has this service's messaging
// loop made any progress recently -- a consumer (re)connect attempt
// (successful or not), a message processed, or an outbox poll tick?
// Deliberately does not ask whether RabbitMQ is reachable right now -- see
// the Java services' identical ProgressWatchdog and
// docs/adr/0038-infrastructure-startup-resilience.md's Update.

func TestProgressWatchdog_NotStuckImmediatelyAfterConstruction(t *testing.T) {
	w := NewProgressWatchdog()

	if w.IsStuck(2 * time.Minute) {
		t.Fatal("a freshly constructed watchdog must not be stuck")
	}
}

func TestProgressWatchdog_StuckOnceThresholdElapsesWithNoProgress(t *testing.T) {
	w := newProgressWatchdogAt(time.Now().Add(-5 * time.Minute))

	if !w.IsStuck(2 * time.Minute) {
		t.Fatal("a watchdog with no progress for 5 minutes must be stuck past a 2-minute threshold")
	}
}

func TestProgressWatchdog_RecordProgressResetsTheStuckClock(t *testing.T) {
	w := newProgressWatchdogAt(time.Now().Add(-5 * time.Minute))

	w.RecordProgress()

	if w.IsStuck(2 * time.Minute) {
		t.Fatal("recording progress must reset the stuck clock")
	}
}

func TestProgressWatchdog_ToleratesAnArbitrarilyLongBrokerOutageAsLongAsRetriesKeepBeingRecorded(t *testing.T) {
	w := NewProgressWatchdog()

	// Simulates a reconnect loop retrying every 3s for a long stretch while
	// the broker is down -- each attempt is itself progress, so the
	// watchdog must never trip during this.
	for i := 0; i < 5; i++ {
		w.RecordProgress()
	}

	if w.IsStuck(2 * time.Minute) {
		t.Fatal("repeated recorded attempts must never report stuck")
	}
}

// TestProgressWatchdog_RecordProgressExposesLastProgressAsAGauge proves
// RecordProgress also updates messaging_last_progress_timestamp_seconds,
// so Grafana can compute "time since last progress" (time() - this gauge)
// without needing access to the watchdog's internal state directly --
// answers the same question IsStuck already answers for the liveness
// probe, but as a Prometheus metric instead of an HTTP response.
func TestProgressWatchdog_RecordProgressExposesLastProgressAsAGauge(t *testing.T) {
	w := NewProgressWatchdog()

	before := testutil.ToFloat64(lastProgressTimestamp)

	w.RecordProgress()

	after := testutil.ToFloat64(lastProgressTimestamp)
	if after < before {
		t.Fatalf("expected messaging_last_progress_timestamp_seconds to advance, went from %v to %v", before, after)
	}
	now := float64(time.Now().Unix())
	if now-after > 2 {
		t.Fatalf("expected messaging_last_progress_timestamp_seconds to reflect roughly now, got %v (now=%v)", after, now)
	}
}
