"""Contract test: order.status-changed (RabbitMQ order.exchange), consumed
by this service's app.consumer.process_order_status_changed. Validates
against /schemas/json/order-status-changed.schema.json.
"""
import jsonschema

from schema_contract_support import load_schema
from app.consumer import process_order_status_changed


ORDER_STATUS_CHANGED_EVENT = {
    "orderId": "order-123",
    "previousState": "INVENTORY_RESERVED",
    "newState": "PAYMENT_APPROVED",
}


class RecordingSender:
    def __init__(self):
        self.sent = []

    def send(self, notification):
        self.sent.append(notification)


class StubLookup:
    def __init__(self, notifications):
        self._notifications = notifications

    def find_by_aggregate_id(self, aggregate_id):
        return self._notifications


def test_example_payload_conforms_to_shared_schema():
    schema = load_schema("order-status-changed.schema.json")
    jsonschema.validate(instance=ORDER_STATUS_CHANGED_EVENT, schema=schema)


def test_consumer_correctly_processes_a_schema_conformant_payload():
    schema = load_schema("order-status-changed.schema.json")
    jsonschema.validate(instance=ORDER_STATUS_CHANGED_EVENT, schema=schema)

    prior_notification = {
        "eventType": "order.created",
        "status": "SENT",
        "payload": {"userId": 42, "email": "buyer@example.com", "firstName": "Casey", "lastName": "Buyer"},
    }
    notification = process_order_status_changed(
        ORDER_STATUS_CHANGED_EVENT, StubLookup([prior_notification]), RecordingSender()
    )

    assert notification.status == "SENT"
    assert notification.payload["previousState"] == "INVENTORY_RESERVED"
    assert notification.payload["newState"] == "PAYMENT_APPROVED"
