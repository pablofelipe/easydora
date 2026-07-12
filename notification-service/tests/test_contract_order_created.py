"""Contract test: order.created (RabbitMQ order.exchange), consumed by
this service's app.consumer.process_order_created. Validates against
/schemas/json/order-created.schema.json (ADR-0002, already covering
orders-service/billing-service; this closes the gap that
notification-service, a third consumer of the same event, was never
checked against it).
"""
import jsonschema

from schema_contract_support import load_schema
from app.consumer import process_order_created
from app.models import UserNotificationProfile


ORDER_CREATED_EVENT = {
    "orderId": "order-123",
    "userId": 42,
    "totalAmount": 99.9,
    "items": [{"productId": "p1", "quantity": 2, "unitPrice": 49.95}],
    "createdAt": "2026-07-07T10:00:00",
}


class StubAuthClient:
    def __init__(self, profile):
        self._profile = profile

    def get_notification_profile(self, user_id, correlation_id=""):
        return self._profile


class RecordingSender:
    def __init__(self):
        self.sent = []

    def send(self, notification):
        self.sent.append(notification)


def test_example_payload_conforms_to_shared_schema():
    schema = load_schema("order-created.schema.json")
    jsonschema.validate(instance=ORDER_CREATED_EVENT, schema=schema)


def test_consumer_correctly_processes_a_schema_conformant_payload():
    schema = load_schema("order-created.schema.json")
    jsonschema.validate(instance=ORDER_CREATED_EVENT, schema=schema)

    profile = UserNotificationProfile(user_id=42, email="buyer@example.com", first_name="Casey", last_name="Buyer")
    notification = process_order_created(ORDER_CREATED_EVENT, StubAuthClient(profile), RecordingSender())

    assert notification.status == "SENT"
    assert notification.aggregate_id == "order-123"
