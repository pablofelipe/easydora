import threading
import time
from urllib.parse import urlparse

import httpx
import pytest

from app.config import load_settings
from app.health import ProgressWatchdog
from app.rabbitmq import run_consumer
from app.repository import NotificationRepository
from app.sender import FakeNotificationSender

pytestmark = pytest.mark.integration

settings = load_settings()


def _management_api_base() -> str:
    """RabbitMQ's management API always lives on port 15672, regardless of
    the AMQP port -- reusing the same host/credentials settings.rabbitmq_url
    already carries."""
    parsed = urlparse(settings.rabbitmq_url)
    return f"http://{parsed.hostname}:15672/api"


def _force_close_all_connections() -> None:
    """Closes every open AMQP connection from the broker side, via the
    management API -- the real, server-initiated equivalent of what a
    RabbitMQ restart does to an already-connected client (ADR-0040 gives
    RabbitMQ no PersistentVolume in Kubernetes, so a broker restart there
    produces exactly this). Deliberately not pika Connection.close() from a
    second thread: BlockingConnection is not thread-safe, and this project
    doesn't need to touch that guarantee to prove the real bug is fixed.
    """
    auth = (urlparse(settings.rabbitmq_url).username, urlparse(settings.rabbitmq_url).password)
    with httpx.Client(auth=auth, timeout=5.0) as client:
        response = client.get(f"{_management_api_base()}/connections")
        response.raise_for_status()
        for conn in response.json():
            client.delete(f"{_management_api_base()}/connections/{conn['name']}")


def test_run_consumer_resumes_consuming_after_the_broker_force_closes_the_connection():
    """Reproduces the exact gap found in the RabbitMQ resilience
    investigation (docs/adr/0038-infrastructure-startup-resilience.md's
    Update) and proves it closed: run_consumer's retry loop was only ever
    verified against a *mocked* mid-run exception, never against pika
    actually detecting a real connection drop. If the heartbeat isn't
    configured (or pika doesn't notice in time), this test hangs and times
    out instead of passing -- silence is exactly the failure mode this is
    guarding against.
    """
    watchdog = ProgressWatchdog()
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    thread = threading.Thread(
        target=run_consumer,
        args=(settings.rabbitmq_url, None, repository, sender, None),
        kwargs={"watchdog": watchdog},
        daemon=True,
    )
    thread.start()

    # Give the consumer a moment to connect and start consuming before
    # yanking the connection out from under it.
    time.sleep(2)
    progress_before = watchdog.last_progress()

    _force_close_all_connections()

    # watchConnection-equivalent behavior: run_consumer's own loop must
    # notice the broker-initiated close and reconnect on its own, with no
    # test code driving that beyond having triggered the close itself.
    reconnected = False
    deadline = time.time() + 20
    while time.time() < deadline:
        if watchdog.last_progress() > progress_before:
            reconnected = True
            break
        time.sleep(0.5)

    assert reconnected, "run_consumer did not record any progress within 20s of the broker closing its connection"
