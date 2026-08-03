import pika

from app.rabbitmq import _route_to_retry_or_dlq, notification_retry_total


def _retry_metric_value(outcome: str) -> float:
    return notification_retry_total.labels(outcome=outcome)._value.get()


class FakeMethod:
    def __init__(self, routing_key, delivery_tag=1):
        self.routing_key = routing_key
        self.delivery_tag = delivery_tag


class FakeChannel:
    def __init__(self):
        self.published = []
        self.acked = []

    def basic_publish(self, exchange, routing_key, body, properties):
        self.published.append(
            {"exchange": exchange, "routing_key": routing_key, "body": body, "properties": properties}
        )

    def basic_ack(self, delivery_tag):
        self.acked.append(delivery_tag)


def test_retry_path_increments_retry_metric():
    """Reproduces the Roadmap gap: process_order_created/process_order_status_changed
    never see a retry count, and until now nothing outside _route_to_retry_or_dlq's
    own log lines did either -- a retry/DLQ event was invisible to anyone not
    tailing this one file's warnings. notification_retry_total makes it a
    queryable metric, the same way ADR-0036/ADR-0037 did for this project's
    other previously-log-only internal states.
    """
    channel = FakeChannel()
    method = FakeMethod(routing_key="order.created")
    properties = pika.BasicProperties(content_type="application/json", headers={})

    before = _retry_metric_value("retry")

    _route_to_retry_or_dlq(channel, method, properties, b"{}")

    assert _retry_metric_value("retry") == before + 1


def test_dlq_path_increments_dead_letter_metric():
    channel = FakeChannel()
    method = FakeMethod(routing_key="order.created")
    properties = pika.BasicProperties(content_type="application/json", headers={"x-notification-attempts": 3})

    before = _retry_metric_value("dead_letter")

    _route_to_retry_or_dlq(channel, method, properties, b"{}")

    assert _retry_metric_value("dead_letter") == before + 1
