from unittest.mock import patch

import pytest

from app.rabbitmq import run_consumer, BOOT_MAX_ATTEMPTS


class _StopTest(BaseException):
    """See test_rabbitmq_reconnect.py's own _StopTest -- same reasoning:
    must not subclass Exception, or run_consumer's own `except Exception:`
    would swallow it and keep looping."""


def test_run_consumer_gives_up_after_boot_max_attempts_never_connecting():
    """Reproduces the Roadmap gap: unlike app/schema.py's ensure_schema
    (bounded, MAX_ATTEMPTS=10, raises and lets the container crash/restart
    instead of retrying forever), run_consumer's initial boot connection
    used to retry indefinitely with no upper bound at all -- a permanently
    misconfigured RABBITMQ_URL would leave the process "healthy" forever,
    having never connected once, with nothing to ever notice or restart
    it. Mirrors inventory-service's own already-bounded boot-time RabbitMQ
    connection (ADR-0038's Decision).

    The steady-state reconnect path (after at least one successful
    connection) is deliberately NOT bounded this way -- see
    test_run_consumer_reconnects_after_a_mid_run_disconnect, which this
    test must not regress.
    """
    attempts = []

    def fake_connect(url):
        attempts.append(url)
        raise ConnectionError("simulated: RabbitMQ permanently unreachable")

    with patch("app.rabbitmq.connect", side_effect=fake_connect), \
            patch("app.rabbitmq.declare_topology"), \
            patch("app.rabbitmq.consume_forever", side_effect=_StopTest), \
            patch("app.rabbitmq.time.sleep"):
        with pytest.raises(ConnectionError):
            run_consumer("amqp://fake", auth_client=None, repository=None, sender=None, jwt_cache=None)

    assert len(attempts) == BOOT_MAX_ATTEMPTS
