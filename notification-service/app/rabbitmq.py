import json
import logging

import pika

from app.consumer import process_order_created

logger = logging.getLogger(__name__)

# Must match orders-service's real producer exactly (see
# orders-service/src/main/java/com/easydora/orders/config/RabbitMQConfig.java
# and billing-service's own consumer of the same event, which established
# the "<consumer-service-name>.order.created.queue" naming convention).
ORDER_EXCHANGE = "order.exchange"
ORDER_CREATED_ROUTING_KEY = "order.created"
ORDER_CREATED_QUEUE = "notification.order.created.queue"


def connect(rabbitmq_url: str):
    connection = pika.BlockingConnection(pika.URLParameters(rabbitmq_url))
    channel = connection.channel()
    return connection, channel


def declare_topology(channel) -> None:
    """Declares/binds the real queue synchronously. Callers must do this
    before anything can be published, or a topic exchange drops the message
    outright instead of buffering it -- the exact race already discovered
    and documented in this project's Go wiring tests (ADR-0012).
    """
    channel.exchange_declare(exchange=ORDER_EXCHANGE, exchange_type="topic", durable=True)
    channel.queue_declare(queue=ORDER_CREATED_QUEUE, durable=True)
    channel.queue_bind(queue=ORDER_CREATED_QUEUE, exchange=ORDER_EXCHANGE, routing_key=ORDER_CREATED_ROUTING_KEY)


def consume_forever(channel, auth_client, sender) -> None:
    def on_message(ch, method, properties, body):
        try:
            event = json.loads(body)
            process_order_created(event, auth_client, sender)
        except Exception:
            # Last-resort safety net for a malformed message (process_order_created
            # itself already turns a failed auth-service lookup into a FAILED
            # notification, not an exception). Acked regardless, so a
            # malformed message doesn't loop forever -- there is no
            # retry/DLQ policy here, deliberately kept as simple as every
            # other consumer in this project.
            logger.exception("failed to process order.created message")
        finally:
            ch.basic_ack(delivery_tag=method.delivery_tag)

    channel.basic_consume(queue=ORDER_CREATED_QUEUE, on_message_callback=on_message)
    channel.start_consuming()


def run_consumer(rabbitmq_url: str, auth_client, sender) -> None:
    _connection, channel = connect(rabbitmq_url)
    declare_topology(channel)
    consume_forever(channel, auth_client, sender)
