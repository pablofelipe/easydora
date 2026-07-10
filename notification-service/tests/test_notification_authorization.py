"""Proves GET /notifications/{orderId} requires authentication and enforces
ownership (buyerId == authenticated userId), deriving identity exclusively
from the JWT principal cached via jwt.created -- never from a client header.
No real Postgres/RabbitMQ needed: repository and jwt_cache are swapped out
directly on the app.main module, mirroring how this service's other unit
tests fake their collaborators instead of hitting real infrastructure.
"""

import pytest
from fastapi.testclient import TestClient

from app import main

BUYER_TOKEN = "buyer-real-token"
OTHER_USER_TOKEN = "other-user-token"
BUYER_USER_ID = 65
OTHER_USER_ID = 66


class FakeRepository:
    def __init__(self, notifications_by_order: dict[str, list[dict]]):
        self._notifications_by_order = notifications_by_order

    def find_by_aggregate_id(self, aggregate_id: str) -> list[dict]:
        return self._notifications_by_order.get(aggregate_id, [])


def _order_created_notification(user_id: int) -> dict:
    return {
        "eventType": "order.created",
        "status": "SENT",
        "payload": {"userId": user_id, "email": "buyer@example.com", "firstName": "B", "lastName": "Uyer"},
        "createdAt": "2026-07-10T00:00:00+00:00",
    }


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(
        main,
        "repository",
        FakeRepository({"order-1": [_order_created_notification(BUYER_USER_ID)]}),
    )
    main.jwt_cache.add(BUYER_TOKEN, user_id=BUYER_USER_ID, email="buyer@example.com", role="BUYER")
    main.jwt_cache.add(OTHER_USER_TOKEN, user_id=OTHER_USER_ID, email="other@example.com", role="BUYER")
    return TestClient(main.app)


def test_buyer_can_read_their_own_order_notifications(client):
    response = client.get("/notifications/order-1", headers={"Authorization": f"Bearer {BUYER_TOKEN}"})
    assert response.status_code == 200
    assert response.json()[0]["payload"]["userId"] == BUYER_USER_ID


def test_other_authenticated_user_is_forbidden(client):
    response = client.get("/notifications/order-1", headers={"Authorization": f"Bearer {OTHER_USER_TOKEN}"})
    assert response.status_code == 403


def test_unauthenticated_request_is_rejected(client):
    response = client.get("/notifications/order-1")
    assert response.status_code == 401


def test_invalid_token_is_rejected(client):
    response = client.get("/notifications/order-1", headers={"Authorization": "Bearer not-a-real-token"})
    assert response.status_code == 401


def test_nonexistent_order_is_not_found_even_when_authenticated(client):
    response = client.get("/notifications/does-not-exist", headers={"Authorization": f"Bearer {BUYER_TOKEN}"})
    assert response.status_code == 404


def test_gateway_namespaced_path_also_requires_authentication(client):
    response = client.get("/notification/notifications/order-1")
    assert response.status_code == 401
