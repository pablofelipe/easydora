import json
import threading
import time
import uuid

import pika
import psycopg2
import pytest

from app.auth import JwtCache
from app.auth_client import AuthServiceClient
from app.config import load_settings
from app.rabbitmq import (
    DLQ,
    MAX_ATTEMPTS,
    ORDER_CREATED_ROUTING_KEY,
    ORDER_EXCHANGE,
    connect,
    consume_forever,
    declare_topology,
)
from app.repository import NotificationRepository
from app.schema import ensure_schema
from app.sender import FakeNotificationSender

pytestmark = pytest.mark.integration

settings = load_settings()


@pytest.fixture(autouse=True, scope="module")
def _schema():
    ensure_schema(settings.db_dsn)


def _seed_user(email: str, first_name: str, last_name: str) -> int:
    with psycopg2.connect(settings.db_dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO auth_schema.users
                    (email, password_hash, first_name, last_name, role, status, email_verified)
                VALUES (%s, 'not-a-real-hash', %s, %s, 'BUYER', 'ACTIVE', true)
                RETURNING id
                """,
                (email, first_name, last_name),
            )
            user_id = cur.fetchone()[0]
    return user_id


# Every *IT test in this file leaves its own consumer thread running for
# the rest of the process (same tolerated pattern the sibling
# test_order_created_flow.py already relies on) -- RabbitMQ round-robins
# a given queue's deliveries across however many competing consumers are
# alive, so a later test's message can land on an earlier test's leftover
# thread. Keying failure/attempt state by order id in module-level dicts,
# rather than on a per-instance counter, makes the simulated failure
# behave correctly no matter which thread/instance actually executes it.
_fail_counts: dict[str, int] = {}
_attempt_counts: dict[str, int] = {}


class _FlakySender:
    """Wraps a real sender, raising for the first `fail_count` calls for a
    given order to simulate a transient failure -- decoupled from business
    logic, the same kind of test-time collaborator swap already used by
    StubAuthClient/RecordingSender in the domain tests, just driving the
    real consumer loop this time instead of calling process_order_created
    directly. process_order_created calls sender.send() outside its own
    try/except, so a raise here reaches rabbitmq.py's resilience policy
    exactly the way a genuinely unexpected failure would.
    """

    def __init__(self, real_sender):
        self._real_sender = real_sender

    def configure(self, order_id: str, fail_count: int) -> None:
        _fail_counts[order_id] = fail_count
        _attempt_counts[order_id] = 0

    def attempts_for(self, order_id: str) -> int:
        return _attempt_counts.get(order_id, 0)

    def send(self, notification):
        order_id = notification.aggregate_id
        _attempt_counts[order_id] = _attempt_counts.get(order_id, 0) + 1
        if _attempt_counts[order_id] <= _fail_counts.get(order_id, 0):
            raise RuntimeError(f"simulated transient failure for {order_id}, attempt {_attempt_counts[order_id]}")
        self._real_sender.send(notification)


def _start_consumer(auth_client, repository, sender) -> threading.Event:
    ready = threading.Event()

    def _run():
        _connection, channel = connect(settings.rabbitmq_url)
        declare_topology(channel)
        ready.set()
        consume_forever(channel, auth_client, repository, sender, JwtCache())

    threading.Thread(target=_run, daemon=True).start()
    return ready


def _await_notification(order_id: str, timeout_seconds: float = 10.0):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        with psycopg2.connect(settings.db_dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT status, payload FROM notification_schema.notifications
                    WHERE aggregate_id = %s AND event_type = 'order.created'
                    """,
                    (order_id,),
                )
                row = cur.fetchone()
                if row:
                    return row
        time.sleep(0.25)
    return None


def _await_dlq_message(order_id: str, timeout_seconds: float = 10.0):
    """Scans the DLQ for the message matching this test's own orderId,
    draining and discarding anything unrelated left over from other tests
    or other runs -- same rationale as orders-service's
    PaymentOutcomeWiringIT probe queue scan.
    """
    _connection, channel = connect(settings.rabbitmq_url)
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        method, _properties, body = channel.basic_get(queue=DLQ, auto_ack=True)
        if method is None:
            time.sleep(0.25)
            continue
        if order_id in body.decode("utf-8"):
            return body
    return None


def test_transient_failure_is_retried_and_eventually_succeeds():
    order_id = f"it-{uuid.uuid4()}"
    email = f"buyer-{uuid.uuid4()}@example.com"
    user_id = _seed_user(email, "Casey", "Buyer")

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    flaky_sender = _FlakySender(FakeNotificationSender(repository))
    flaky_sender.configure(order_id, fail_count=MAX_ATTEMPTS - 1)

    ready = _start_consumer(auth_client, repository, flaky_sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    event = {
        "orderId": order_id,
        "userId": user_id,
        "totalAmount": 15.0,
        "items": [],
        "createdAt": "2026-07-08T10:00:00",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_CREATED_ROUTING_KEY,
        body=json.dumps(event),
    )

    row = _await_notification(order_id, timeout_seconds=15.0)
    assert row is not None, f"expected order {order_id} to eventually succeed after retries"
    status, payload = row
    assert status == "SENT"
    assert payload["email"] == email
    assert flaky_sender.attempts_for(order_id) == MAX_ATTEMPTS, (
        f"expected exactly {MAX_ATTEMPTS} attempts (fail_count retries then one success), "
        f"got {flaky_sender.attempts_for(order_id)}"
    )


def test_permanent_failure_exhausts_retries_and_lands_on_the_dead_letter_queue():
    order_id = f"it-{uuid.uuid4()}"
    email = f"buyer-{uuid.uuid4()}@example.com"
    user_id = _seed_user(email, "Casey", "Buyer")

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    always_failing_sender = _FlakySender(FakeNotificationSender(repository))
    always_failing_sender.configure(order_id, fail_count=999)

    ready = _start_consumer(auth_client, repository, always_failing_sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    event = {
        "orderId": order_id,
        "userId": user_id,
        "totalAmount": 15.0,
        "items": [],
        "createdAt": "2026-07-08T10:00:00",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_CREATED_ROUTING_KEY,
        body=json.dumps(event),
    )

    dead_body = _await_dlq_message(order_id, timeout_seconds=15.0)
    assert dead_body is not None, (
        f"expected order {order_id} to land on the dead letter queue after exhausting retries"
    )
    assert order_id in dead_body.decode("utf-8")

    # No further attempts happen once the message is on the DLQ (no infinite
    # retry), and it was never silently dropped -- it's on a real queue.
    assert always_failing_sender.attempts_for(order_id) == MAX_ATTEMPTS

    # Not lost, and not persisted as a notification either -- it's parked
    # on the DLQ instead, exactly once.
    row = _await_notification(order_id, timeout_seconds=2.0)
    assert row is None, "a permanently failing message should never produce a persisted notification"
