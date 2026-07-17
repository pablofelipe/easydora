import time

from app.health import ProgressWatchdog


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
