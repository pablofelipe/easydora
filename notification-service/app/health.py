import threading
import time

from prometheus_client import Gauge

# Reconnection observability (docs/adr/0038-infrastructure-startup-resilience.md's
# Update): mirrors ProgressWatchdog's own internal (monotonic) clock as a
# wall-clock Prometheus gauge, so "time since last progress"
# (time() - this gauge) is queryable directly in Grafana. Same metric
# name/shape as inventory-service's (Go) and the four Spring services'
# equivalents.
messaging_last_progress_timestamp_seconds = Gauge(
    "messaging_last_progress_timestamp_seconds",
    "Unix timestamp of the last time this service's messaging loop recorded progress "
    "(a (re)connect attempt, a message processed, or an idle tick).",
)


class ProgressWatchdog:
    """Answers one question only: has this service's messaging loop made
    any progress recently -- a connect/reconnect attempt (successful or
    not), a message processed, or a periodic idle tick while consuming?
    Deliberately does not ask whether RabbitMQ is reachable right now: a
    stalled loop and a broker that is merely down (and being tolerated, per
    docs/adr/0038-infrastructure-startup-resilience.md) are different
    questions. Conflating them would make a liveness probe built on this
    watchdog restart the pod during an ordinary, tolerated broker outage,
    turning an external dependency's downtime into a self-inflicted restart
    storm. Mirrors the Java services'/inventory-service's identical
    ProgressWatchdog.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._last_progress = time.monotonic()

    def record_progress(self) -> None:
        with self._lock:
            self._last_progress = time.monotonic()
        messaging_last_progress_timestamp_seconds.set(time.time())

    def last_progress(self) -> float:
        with self._lock:
            return self._last_progress

    def is_stuck(self, threshold_seconds: float) -> bool:
        with self._lock:
            return (time.monotonic() - self._last_progress) > threshold_seconds
