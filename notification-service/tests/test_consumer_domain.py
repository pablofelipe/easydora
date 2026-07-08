from app.auth_client import ProfileNotFoundError
from app.consumer import process_order_created, process_order_status_changed
from app.models import UserNotificationProfile


class StubAuthClient:
    def __init__(self, profile=None, error=None):
        self._profile = profile
        self._error = error

    def get_notification_profile(self, user_id):
        if self._error:
            raise self._error
        return self._profile


class RecordingSender:
    def __init__(self):
        self.sent = []

    def send(self, notification):
        self.sent.append(notification)


class StubLookup:
    def __init__(self, notifications=None):
        self._notifications = notifications or []

    def find_by_aggregate_id(self, aggregate_id):
        return self._notifications


ORDER_CREATED_EVENT = {
    "orderId": "order-123",
    "userId": 42,
    "totalAmount": 99.9,
    "items": [{"productId": "p1", "quantity": 2, "unitPrice": 49.95}],
    "createdAt": "2026-07-07T10:00:00",
}


def test_successful_enrichment_produces_sent_notification():
    profile = UserNotificationProfile(user_id=42, email="buyer@example.com", first_name="Casey", last_name="Buyer")
    auth_client = StubAuthClient(profile=profile)
    sender = RecordingSender()

    notification = process_order_created(ORDER_CREATED_EVENT, auth_client, sender)

    assert notification.status == "SENT"
    assert notification.aggregate_id == "order-123"
    assert notification.event_type == "order.created"
    assert notification.payload["email"] == "buyer@example.com"
    assert sender.sent == [notification]


def test_profile_not_found_produces_failed_notification_without_raising():
    auth_client = StubAuthClient(error=ProfileNotFoundError("user 42 not found"))
    sender = RecordingSender()

    notification = process_order_created(ORDER_CREATED_EVENT, auth_client, sender)

    assert notification.status == "FAILED"
    assert notification.aggregate_id == "order-123"
    assert "not found" in notification.payload["error"]
    assert sender.sent == [notification]


ORDER_STATUS_CHANGED_EVENT = {
    "orderId": "order-123",
    "previousState": "PROCESSING",
    "newState": "INVENTORY_RESERVED",
}


def test_status_changed_reuses_the_prior_order_created_notification():
    prior_order_created = {
        "eventType": "order.created",
        "status": "SENT",
        "payload": {"userId": 42, "email": "buyer@example.com", "firstName": "Casey", "lastName": "Buyer"},
        "createdAt": "2026-07-08T10:00:00+00:00",
    }
    lookup = StubLookup([prior_order_created])
    sender = RecordingSender()

    notification = process_order_status_changed(ORDER_STATUS_CHANGED_EVENT, lookup, sender)

    assert notification.status == "SENT"
    assert notification.aggregate_id == "order-123"
    assert notification.event_type == "order.status-changed"
    assert notification.payload["email"] == "buyer@example.com"
    assert notification.payload["previousState"] == "PROCESSING"
    assert notification.payload["newState"] == "INVENTORY_RESERVED"
    assert sender.sent == [notification]


def test_status_changed_ignores_a_failed_prior_order_created_notification():
    prior_failed = {
        "eventType": "order.created",
        "status": "FAILED",
        "payload": {"error": "auth-service lookup failed"},
        "createdAt": "2026-07-08T10:00:00+00:00",
    }
    lookup = StubLookup([prior_failed])
    sender = RecordingSender()

    notification = process_order_status_changed(ORDER_STATUS_CHANGED_EVENT, lookup, sender)

    assert notification.status == "FAILED"
    assert "no prior order.created notification" in notification.payload["error"]


def test_status_changed_without_any_prior_notification_produces_a_failed_notification_without_raising():
    lookup = StubLookup([])
    sender = RecordingSender()

    notification = process_order_status_changed(ORDER_STATUS_CHANGED_EVENT, lookup, sender)

    assert notification.status == "FAILED"
    assert notification.aggregate_id == "order-123"
    assert "no prior order.created notification" in notification.payload["error"]
    assert sender.sent == [notification]
