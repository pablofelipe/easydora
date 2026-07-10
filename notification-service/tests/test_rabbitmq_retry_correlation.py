import pika

from app.rabbitmq import _route_to_retry_or_dlq


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


def test_retry_path_preserves_correlation_id_and_message_id():
    """Reproduces the real gap found while wiring observability into
    notification-service: the retry path built a brand-new BasicProperties
    (content_type, headers, expiration only), silently dropping the
    original message's correlation_id/message_id -- a message that got
    retried lost its CorrelationId even though the terminal DLQ path
    (below) never had this problem, since it reuses the original
    properties object unmodified.
    """
    channel = FakeChannel()
    method = FakeMethod(routing_key="order.created")
    properties = pika.BasicProperties(
        content_type="application/json",
        correlation_id="corr-1",
        message_id="msg-1",
        headers={},
    )

    _route_to_retry_or_dlq(channel, method, properties, b"{}")

    assert len(channel.published) == 1
    retry_properties = channel.published[0]["properties"]
    assert retry_properties.correlation_id == "corr-1"
    assert retry_properties.message_id == "msg-1"


def test_dlq_path_preserves_correlation_id_and_message_id_too():
    channel = FakeChannel()
    method = FakeMethod(routing_key="order.created")
    properties = pika.BasicProperties(
        content_type="application/json",
        correlation_id="corr-2",
        message_id="msg-2",
        headers={"x-notification-attempts": 3},
    )

    _route_to_retry_or_dlq(channel, method, properties, b"{}")

    assert len(channel.published) == 1
    dlq_properties = channel.published[0]["properties"]
    assert dlq_properties.correlation_id == "corr-2"
    assert dlq_properties.message_id == "msg-2"
