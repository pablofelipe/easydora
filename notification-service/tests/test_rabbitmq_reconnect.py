from unittest.mock import patch

import pytest

from app.rabbitmq import run_consumer


class _StopTest(BaseException):
    """Sentinel used to break run_consumer's intentionally-infinite retry
    loop once the test has observed what it needs to. Must NOT subclass
    Exception -- run_consumer's own `except Exception:` would catch it and
    keep looping forever instead of letting it escape to the test."""


def test_run_consumer_retries_past_a_startup_connection_failure():
    """Reproduces the real bug found while validating the documented
    end-to-end flow: notification-service's RabbitMQ consumer thread died
    permanently on a single failed connection attempt at container
    startup (a real race with docker-compose's healthcheck-based
    ordering), leaving the container reporting healthy forever while the
    consumer was silently dead. This proves connect() failing does not
    give up -- it retries until it succeeds.
    """
    attempts = []

    def fake_connect(url):
        attempts.append(url)
        if len(attempts) < 3:
            raise ConnectionError("simulated: broker not ready yet")
        return object(), object()

    with patch("app.rabbitmq.connect", side_effect=fake_connect), \
            patch("app.rabbitmq.declare_topology"), \
            patch("app.rabbitmq.consume_forever", side_effect=_StopTest), \
            patch("app.rabbitmq.time.sleep"):
        with pytest.raises(_StopTest):
            run_consumer("amqp://fake", auth_client=None, sender=None)

    assert len(attempts) == 3


def test_run_consumer_reconnects_after_a_mid_run_disconnect():
    """A broker restart or network blip after a successful initial
    connection must not kill this thread permanently either -- the same
    retry loop covers a later disconnect, not just the startup race."""
    calls = {"connect": 0, "consume": 0}

    def fake_connect(url):
        calls["connect"] += 1
        return object(), object()

    def fake_consume(channel, auth_client, sender):
        calls["consume"] += 1
        if calls["consume"] < 2:
            raise ConnectionError("simulated: broker connection lost mid-run")
        raise _StopTest

    with patch("app.rabbitmq.connect", side_effect=fake_connect), \
            patch("app.rabbitmq.declare_topology"), \
            patch("app.rabbitmq.consume_forever", side_effect=fake_consume), \
            patch("app.rabbitmq.time.sleep"):
        with pytest.raises(_StopTest):
            run_consumer("amqp://fake", auth_client=None, sender=None)

    assert calls["connect"] == 2
    assert calls["consume"] == 2
