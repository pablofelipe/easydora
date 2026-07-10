import json
import threading
import time
import uuid

import psycopg2
import pytest
from fastapi.testclient import TestClient

from app.auth import JwtCache
from app.auth_client import AuthServiceClient
from app.config import load_settings
from app.rabbitmq import (
    ORDER_CREATED_ROUTING_KEY,
    ORDER_EXCHANGE,
    ORDER_STATUS_CHANGED_ROUTING_KEY,
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
    # Normally created by main.py's own startup; this test drives the
    # consumer functions directly rather than through a running app
    # process, so it ensures the schema itself the same way.
    ensure_schema(settings.db_dsn)


def _seed_user(email: str, first_name: str, last_name: str) -> int:
    """Not the flow under test -- a real signup would work too, but the
    thing this test exists to prove is notification-service's own
    consumption + HTTP enrichment + persistence, not auth-service's signup
    endpoint (already covered elsewhere). Seeding the row directly mirrors
    the same convention this project's other cross-service e2e tests
    already use for prerequisite state.
    """
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


def _await_notification(order_id: str, event_type: str = "order.created", timeout_seconds: float = 10.0):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        with psycopg2.connect(settings.db_dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT status, payload FROM notification_schema.notifications
                    WHERE aggregate_id = %s AND event_type = %s
                    """,
                    (order_id, event_type),
                )
                row = cur.fetchone()
                if row:
                    return row
        time.sleep(0.25)
    return None


def _start_consumer(auth_client, repository, sender) -> threading.Event:
    """Runs connect + declare_topology + consume_forever entirely on one
    thread -- pika's BlockingConnection is not safe to touch from a thread
    other than the one that created it. The ready_event lets the caller
    wait on a real condition (the queue is bound) instead of guessing with
    a sleep, avoiding the exact publish-before-queue-exists race this
    project's Go wiring tests already hit once (ADR-0012).
    """
    ready = threading.Event()

    def _run():
        _connection, channel = connect(settings.rabbitmq_url)
        declare_topology(channel)
        ready.set()
        consume_forever(channel, auth_client, repository, sender, JwtCache())

    threading.Thread(target=_run, daemon=True).start()
    return ready


def test_order_created_event_produces_a_sent_notification():
    order_id = f"it-{uuid.uuid4()}"
    email = f"buyer-{uuid.uuid4()}@example.com"
    user_id = _seed_user(email, "Casey", "Buyer")

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    ready = _start_consumer(auth_client, repository, sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    event = {
        "orderId": order_id,
        "userId": user_id,
        "totalAmount": 42.50,
        "items": [{"productId": "p1", "quantity": 1, "unitPrice": 42.50}],
        "createdAt": "2026-07-07T10:00:00",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_CREATED_ROUTING_KEY,
        body=json.dumps(event),
    )

    row = _await_notification(order_id)
    assert row is not None, f"expected a notification row for order {order_id}"
    status, payload = row
    assert status == "SENT"
    assert payload["email"] == email
    assert payload["userId"] == user_id


def test_order_created_event_for_unknown_user_produces_a_failed_notification():
    order_id = f"it-{uuid.uuid4()}"
    unknown_user_id = 999_999_999

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    ready = _start_consumer(auth_client, repository, sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    event = {
        "orderId": order_id,
        "userId": unknown_user_id,
        "totalAmount": 10.0,
        "items": [],
        "createdAt": "2026-07-07T10:00:00",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_CREATED_ROUTING_KEY,
        body=json.dumps(event),
    )

    row = _await_notification(order_id)
    assert row is not None, f"expected a notification row for order {order_id}"
    status, payload = row
    assert status == "FAILED"
    assert str(unknown_user_id) in payload["error"] or "not found" in payload["error"].lower()


def test_order_status_changed_event_reuses_the_prior_order_created_notification():
    order_id = f"it-{uuid.uuid4()}"
    email = f"buyer-{uuid.uuid4()}@example.com"
    user_id = _seed_user(email, "Casey", "Buyer")

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    ready = _start_consumer(auth_client, repository, sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    created_event = {
        "orderId": order_id,
        "userId": user_id,
        "totalAmount": 42.50,
        "items": [{"productId": "p1", "quantity": 1, "unitPrice": 42.50}],
        "createdAt": "2026-07-07T10:00:00",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_CREATED_ROUTING_KEY,
        body=json.dumps(created_event),
    )
    assert _await_notification(order_id, "order.created") is not None, "prior order.created notification never landed"

    status_changed_event = {
        "orderId": order_id,
        "previousState": "PROCESSING",
        "newState": "INVENTORY_RESERVED",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_STATUS_CHANGED_ROUTING_KEY,
        body=json.dumps(status_changed_event),
    )

    row = _await_notification(order_id, "order.status-changed")
    assert row is not None, f"expected an order.status-changed notification for order {order_id}"
    status, payload = row
    assert status == "SENT"
    assert payload["email"] == email
    assert payload["userId"] == user_id
    assert payload["previousState"] == "PROCESSING"
    assert payload["newState"] == "INVENTORY_RESERVED"


def test_order_status_changed_event_without_a_prior_notification_produces_a_failed_notification():
    order_id = f"it-{uuid.uuid4()}"

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    ready = _start_consumer(auth_client, repository, sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    status_changed_event = {
        "orderId": order_id,
        "previousState": "PENDING",
        "newState": "PAYMENT_FAILED",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_STATUS_CHANGED_ROUTING_KEY,
        body=json.dumps(status_changed_event),
    )

    row = _await_notification(order_id, "order.status-changed")
    assert row is not None, f"expected an order.status-changed notification for order {order_id}"
    status, payload = row
    assert status == "FAILED"
    assert "no prior order.created notification" in payload["error"]


def test_get_notifications_returns_every_notification_for_an_order_in_order():
    from app.main import app, jwt_cache

    order_id = f"it-{uuid.uuid4()}"
    email = f"buyer-{uuid.uuid4()}@example.com"
    user_id = _seed_user(email, "Casey", "Buyer")
    token = f"test-token-{uuid.uuid4()}"
    jwt_cache.add(token, user_id=user_id, email=email, role="BUYER")

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    ready = _start_consumer(auth_client, repository, sender)
    assert ready.wait(timeout=10), "consumer never finished declaring its queue"

    _pub_connection, pub_channel = connect(settings.rabbitmq_url)
    created_event = {
        "orderId": order_id,
        "userId": user_id,
        "totalAmount": 15.0,
        "items": [],
        "createdAt": "2026-07-07T10:00:00",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_CREATED_ROUTING_KEY,
        body=json.dumps(created_event),
    )
    assert _await_notification(order_id, "order.created") is not None

    status_changed_event = {
        "orderId": order_id,
        "previousState": "PROCESSING",
        "newState": "INVENTORY_RESERVED",
    }
    pub_channel.basic_publish(
        exchange=ORDER_EXCHANGE,
        routing_key=ORDER_STATUS_CHANGED_ROUTING_KEY,
        body=json.dumps(status_changed_event),
    )
    assert _await_notification(order_id, "order.status-changed") is not None

    # Not a live app instance (no lifespan/consumer thread started here) --
    # this exercises the same repository-backed endpoint against the rows
    # the manually-driven consumer above just persisted.
    client = TestClient(app)
    response = client.get(f"/notifications/{order_id}", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    body = response.json()
    assert [n["eventType"] for n in body] == ["order.created", "order.status-changed"]
    assert body[0]["status"] == "SENT"
    assert body[1]["status"] == "SENT"
    assert body[1]["payload"]["newState"] == "INVENTORY_RESERVED"


def test_get_notifications_for_an_unknown_order_returns_404():
    from app.main import app, jwt_cache

    token = f"test-token-{uuid.uuid4()}"
    jwt_cache.add(token, user_id=999999, email="someone@example.com", role="BUYER")

    client = TestClient(app)
    response = client.get(
        f"/notifications/does-not-exist-{uuid.uuid4()}", headers={"Authorization": f"Bearer {token}"}
    )

    assert response.status_code == 404
