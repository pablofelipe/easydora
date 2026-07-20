from unittest.mock import patch

import pytest

from app.rabbitmq import run_consumer, rabbitmq_reconnect_attempts_total, rabbitmq_topology_setup_total


def _reconnect_attempts_value() -> float:
    return rabbitmq_reconnect_attempts_total._value.get()


def _topology_setup_value(outcome: str) -> float:
    return rabbitmq_topology_setup_total.labels(outcome=outcome)._value.get()


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

    Also proves the boot-time retries here are NOT counted as
    reconnects -- mirroring inventory-service (Go) and the Spring
    services, which never count their own separate bounded boot-time
    retry against rabbitmq_reconnect_attempts_total either. This loop has
    no structurally separate boot phase the way Go's does, so
    "before the first successful connection" is what stands in for it.
    """
    attempts = []
    reconnects_before = _reconnect_attempts_value()

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
            run_consumer("amqp://fake", auth_client=None, repository=None, sender=None, jwt_cache=None)

    assert len(attempts) == 3
    assert _reconnect_attempts_value() == reconnects_before, \
        "boot-time retries (before the first successful connection) must not count as reconnects"


def test_run_consumer_reconnects_after_a_mid_run_disconnect():
    """A broker restart or network blip after a successful initial
    connection must not kill this thread permanently either -- the same
    retry loop covers a later disconnect, not just the startup race.

    Also proves the reconnection observability contract's two counters
    (docs/adr/0038's Update): the second connect (a real steady-state
    reconnect, not the initial boot connection) increments
    rabbitmq_reconnect_attempts_total, and its successful topology
    redeclaration increments rabbitmq_topology_setup_total{outcome="success"}.
    """
    calls = {"connect": 0, "consume": 0}
    reconnects_before = _reconnect_attempts_value()
    topology_success_before = _topology_setup_value("success")

    def fake_connect(url):
        calls["connect"] += 1
        return object(), object()

    def fake_consume(channel, auth_client, repository, sender, jwt_cache, watchdog=None):
        calls["consume"] += 1
        if calls["consume"] < 2:
            raise ConnectionError("simulated: broker connection lost mid-run")
        raise _StopTest

    with patch("app.rabbitmq.connect", side_effect=fake_connect), \
            patch("app.rabbitmq.declare_topology"), \
            patch("app.rabbitmq.consume_forever", side_effect=fake_consume), \
            patch("app.rabbitmq.time.sleep"):
        with pytest.raises(_StopTest):
            run_consumer("amqp://fake", auth_client=None, repository=None, sender=None, jwt_cache=None)

    assert calls["connect"] == 2
    assert calls["consume"] == 2
    assert _reconnect_attempts_value() == reconnects_before + 1
    assert _topology_setup_value("success") == topology_success_before + 1
