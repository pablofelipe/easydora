import json
import logging
import time

import pika

from app.consumer import process_order_created, process_order_status_changed

logger = logging.getLogger(__name__)

RECONNECT_DELAY_SECONDS = 5

# Must match orders-service's real producer exactly (see
# orders-service/src/main/java/com/easydora/orders/config/RabbitMQConfig.java
# and billing-service's own consumer of the same event, which established
# the "<consumer-service-name>.order.created.queue" naming convention).
ORDER_EXCHANGE = "order.exchange"
ORDER_CREATED_ROUTING_KEY = "order.created"
ORDER_CREATED_QUEUE = "notification.order.created.queue"

# order.status-changed's destination was decided in ADR-0001's Update; this
# is that consumer, finally implemented.
ORDER_STATUS_CHANGED_ROUTING_KEY = "order.status-changed"
ORDER_STATUS_CHANGED_QUEUE = "notification.order.status-changed.queue"

# Consumption resilience (conceptually equivalent to the Spring services'
# retry/backoff/DLQ policy, ADR-0019 -- same numbers, different mechanism
# since Pika has no built-in retry template). A failed message is
# republished to RETRY_QUEUE with a per-message TTL (no queue-level TTL,
# no manual sleep, no polling); that queue's own x-dead-letter-exchange
# sends it back to ORDER_EXCHANGE once the TTL expires, using the same
# routing key it was retried with, so it lands back on its original queue
# for redelivery. After MAX_ATTEMPTS, the message is published to DLX/DLQ
# instead and never retried again.
MAX_ATTEMPTS = 3
INITIAL_INTERVAL_MS = 200
BACKOFF_MULTIPLIER = 2.0
MAX_INTERVAL_MS = 2000
ATTEMPTS_HEADER = "x-notification-attempts"

RETRY_EXCHANGE = "notification.retry.exchange"
RETRY_QUEUE = "notification.retry.queue"
DLX_EXCHANGE = "notification.dlx"
DLQ = "notification.dlq"


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
    channel.queue_declare(queue=ORDER_STATUS_CHANGED_QUEUE, durable=True)
    channel.queue_bind(
        queue=ORDER_STATUS_CHANGED_QUEUE, exchange=ORDER_EXCHANGE, routing_key=ORDER_STATUS_CHANGED_ROUTING_KEY
    )

    # Retry queue: bound to its own exchange with "#" so it accepts a
    # retried message under any original routing key; its
    # x-dead-letter-exchange (no override routing key) sends an expired
    # message back to ORDER_EXCHANGE using that same original routing key,
    # landing it back on ORDER_CREATED_QUEUE or ORDER_STATUS_CHANGED_QUEUE
    # for redelivery. The delay itself is set per-message (BasicProperties.expiration
    # in _route_to_retry_or_dlq), not as a fixed queue-level TTL, so each
    # attempt can back off further than the last.
    channel.exchange_declare(exchange=RETRY_EXCHANGE, exchange_type="topic", durable=True)
    channel.queue_declare(
        queue=RETRY_QUEUE,
        durable=True,
        arguments={"x-dead-letter-exchange": ORDER_EXCHANGE},
    )
    channel.queue_bind(queue=RETRY_QUEUE, exchange=RETRY_EXCHANGE, routing_key="#")

    # Terminal dead letter queue: reached only after MAX_ATTEMPTS.
    channel.exchange_declare(exchange=DLX_EXCHANGE, exchange_type="topic", durable=True)
    channel.queue_declare(queue=DLQ, durable=True)
    channel.queue_bind(queue=DLQ, exchange=DLX_EXCHANGE, routing_key="#")


def _next_attempt(properties) -> int:
    headers = properties.headers or {}
    return int(headers.get(ATTEMPTS_HEADER, 1))


def _route_to_retry_or_dlq(channel, method, properties, body) -> None:
    """Concentrates the whole resilience policy here, in the messaging
    layer -- the business functions this is called after (process_order_created/
    process_order_status_changed) never see a retry count or know this
    exists. Retries up to MAX_ATTEMPTS with exponential backoff (via a
    per-message TTL on the retry queue, not a sleep); once exhausted,
    republishes to the dead letter exchange instead so the message is
    never silently dropped.
    """
    attempt = _next_attempt(properties)

    if attempt < MAX_ATTEMPTS:
        delay_ms = min(INITIAL_INTERVAL_MS * (BACKOFF_MULTIPLIER ** (attempt - 1)), MAX_INTERVAL_MS)
        headers = dict(properties.headers or {})
        headers[ATTEMPTS_HEADER] = attempt + 1
        retry_properties = pika.BasicProperties(
            content_type=properties.content_type,
            headers=headers,
            expiration=str(int(delay_ms)),
        )
        channel.basic_publish(
            exchange=RETRY_EXCHANGE,
            routing_key=method.routing_key,
            body=body,
            properties=retry_properties,
        )
        logger.warning(
            "message failed (attempt %d/%d), retrying in %dms: routing_key=%s",
            attempt, MAX_ATTEMPTS, delay_ms, method.routing_key,
        )
    else:
        channel.basic_publish(
            exchange=DLX_EXCHANGE,
            routing_key=method.routing_key,
            body=body,
            properties=properties,
        )
        logger.error(
            "message exhausted %d attempts, routed to the dead letter queue: routing_key=%s",
            MAX_ATTEMPTS, method.routing_key,
        )

    channel.basic_ack(delivery_tag=method.delivery_tag)


def consume_forever(channel, auth_client, repository, sender) -> None:
    def on_order_created(ch, method, properties, body):
        try:
            event = json.loads(body)
            process_order_created(event, auth_client, sender)
            ch.basic_ack(delivery_tag=method.delivery_tag)
        except Exception:
            logger.exception("failed to process order.created message")
            _route_to_retry_or_dlq(ch, method, properties, body)

    def on_order_status_changed(ch, method, properties, body):
        try:
            event = json.loads(body)
            process_order_status_changed(event, repository, sender)
            ch.basic_ack(delivery_tag=method.delivery_tag)
        except Exception:
            logger.exception("failed to process order.status-changed message")
            _route_to_retry_or_dlq(ch, method, properties, body)

    channel.basic_consume(queue=ORDER_CREATED_QUEUE, on_message_callback=on_order_created)
    channel.basic_consume(queue=ORDER_STATUS_CHANGED_QUEUE, on_message_callback=on_order_status_changed)
    channel.start_consuming()


def run_consumer(rabbitmq_url: str, auth_client, repository, sender) -> None:
    """Runs connect + declare_topology + consume_forever in a loop that
    never gives up permanently. This is a daemon thread with no supervisor:
    a container can start before RabbitMQ is fully ready to accept
    connections despite docker-compose's own healthcheck-based ordering,
    and the original single-attempt version died silently on that race --
    the container's HEALTHCHECK only covers FastAPI's own /health, which
    has nothing to do with this thread, so nothing else would ever notice
    the consumer was permanently dead. A later broker restart mid-run
    would kill this thread the same way. Every failure, at startup or
    mid-run, is logged and retried after a fixed delay instead.
    """
    while True:
        try:
            _connection, channel = connect(rabbitmq_url)
            declare_topology(channel)
            consume_forever(channel, auth_client, repository, sender)
        except Exception:
            logger.exception(
                "RabbitMQ connection lost or unavailable; retrying in %ss",
                RECONNECT_DELAY_SECONDS,
            )
            time.sleep(RECONNECT_DELAY_SECONDS)
