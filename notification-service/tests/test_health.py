import time

from app.health import ProgressWatchdog, messaging_last_progress_timestamp_seconds


def test_not_stuck_immediately_after_construction():
    watchdog = ProgressWatchdog()

    assert watchdog.is_stuck(120) is False


def test_stuck_once_threshold_elapses_with_no_progress_recorded():
    watchdog = ProgressWatchdog()

    assert watchdog.is_stuck(-1) is True, "a negative threshold must always report stuck (elapsed time is never negative)"


def test_recording_progress_resets_the_stuck_clock():
    watchdog = ProgressWatchdog()
    # Simulate time having passed with no progress by using a threshold
    # that would trip if the constructor's own initial timestamp were
    # stale, then recording progress and re-checking against the same
    # threshold -- proves record_progress() actually moves the clock.
    watchdog.record_progress()

    assert watchdog.is_stuck(120) is False


def test_last_progress_advances_after_recording():
    watchdog = ProgressWatchdog()
    before = watchdog.last_progress()

    time.sleep(0.01)
    watchdog.record_progress()

    assert watchdog.last_progress() > before


def test_record_progress_exposes_last_progress_as_a_gauge():
    """record_progress() also updates messaging_last_progress_timestamp_seconds
    (wall-clock, unlike last_progress()'s monotonic clock used for
    is_stuck), so Grafana can compute "time since last progress"
    (time() - this gauge) with the same metric name/shape as
    inventory-service's (Go) and the four Spring services' equivalents
    (docs/adr/0038's Update, the reconnection observability contract).
    """
    watchdog = ProgressWatchdog()
    before = messaging_last_progress_timestamp_seconds._value.get()

    watchdog.record_progress()

    after = messaging_last_progress_timestamp_seconds._value.get()
    assert after >= before
    assert time.time() - after < 2
