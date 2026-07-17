package messaging

import (
	"sync"
	"time"
)

// ProgressWatchdog answers one question only: has this service's messaging
// loop made any progress recently -- a consumer (re)connect attempt
// (successful or not), a message processed, or an outbox poll tick?
// Deliberately does not ask whether RabbitMQ is reachable right now: a
// stalled loop and a broker that is merely down (and being tolerated, per
// docs/adr/0038-infrastructure-startup-resilience.md) are different
// questions. Conflating them would make a liveness probe built on this
// watchdog restart the pod during an ordinary, tolerated broker outage,
// turning an external dependency's downtime into a self-inflicted restart
// storm. Mirrors the Java services' identical ProgressWatchdog.
type ProgressWatchdog struct {
	mu           sync.RWMutex
	lastProgress time.Time
}

func NewProgressWatchdog() *ProgressWatchdog {
	return &ProgressWatchdog{lastProgress: time.Now()}
}

// newProgressWatchdogAt is test-only: lets tests seed a stale lastProgress
// without sleeping.
func newProgressWatchdogAt(t time.Time) *ProgressWatchdog {
	return &ProgressWatchdog{lastProgress: t}
}

func (w *ProgressWatchdog) RecordProgress() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.lastProgress = time.Now()
}

func (w *ProgressWatchdog) IsStuck(threshold time.Duration) bool {
	w.mu.RLock()
	defer w.mu.RUnlock()
	return time.Since(w.lastProgress) > threshold
}
