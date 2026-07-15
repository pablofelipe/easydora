import json
import logging
import time
from datetime import datetime, timedelta

import pika

from app.consumer import process_order_created, process_order_status_changed
from app.correlation import correlation_scope, current_or_new_correlation_id

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

# jwt.created broadcast, consumed the same way every Spring service's own
# JwtConsumer does: cache the raw token against the user info it carries,
# for GET /notifications/{orderId}'s own authentication (see app/auth.py).
# The same cached data also backs CachingAuthClient's order.created
# enrichment fast path (see app/auth_client.py), via JwtCache's
# userId-keyed view -- one broadcast, two consumers of the same cache.
AUTH_EXCHANGE = "auth.exchange"
JWT_CREATED_ROUTING_KEY = "jwt.created"
JWT_CREATED_QUEUE = "notification.jwt.created.queue"

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

    channel.exchange_declare(exchange=AUTH_EXCHANGE, exchange_type="topic", durable=True)
    channel.queue_declare(queue=JWT_CREATED_QUEUE, durable=True)
    channel.queue_bind(queue=JWT_CREATED_QUEUE, exchange=AUTH_EXCHANGE, routing_key=JWT_CREATED_ROUTING_KEY)

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
            correlation_id=properties.correlation_id,
            message_id=properties.message_id,
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


def _scope_from_properties(properties) -> correlation_scope:
    """Builds the logging scope for one delivery: CorrelationId is reused
    from the message's own property if the publisher set one, generated
    otherwise -- this service should never invent a new CorrelationId when
    a perfectly good one already arrived on the message."""
    correlation_id = properties.correlation_id or current_or_new_correlation_id()
    return correlation_scope(correlation_id=correlation_id, message_id=properties.message_id or "")


def _cache_jwt_created(event: dict, jwt_cache) -> None:
    token = event["token"]
    if not token:
        logger.error("jwt.created event has no token, ignoring")
        return
    # ADR-0039: give the cache entry a lifetime equal to the JWT's own
    # expiresIn, instead of none at all (previously only a restart ever
    # removed an entry).
    created_at = datetime.fromisoformat(event["createdAt"])
    expires_at = created_at + timedelta(seconds=event["expiresIn"])
    jwt_cache.add(
        token,
        user_id=int(event["userId"]),
        email=event["email"],
        role=event["role"],
        first_name=event["firstName"],
        last_name=event["lastName"],
        expires_at=expires_at,
    )


def consume_forever(channel, auth_client, repository, sender, jwt_cache) -> None:
    def on_order_created(ch, method, properties, body):
        with _scope_from_properties(properties):
            try:
                event = json.loads(body)
                process_order_created(event, auth_client, sender, correlation_id=properties.correlation_id or "")
                ch.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:
                logger.exception("failed to process order.created message")
                _route_to_retry_or_dlq(ch, method, properties, body)

    def on_order_status_changed(ch, method, properties, body):
        with _scope_from_properties(properties):
            try:
                event = json.loads(body)
                process_order_status_changed(event, repository, sender)
                ch.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:
                logger.exception("failed to process order.status-changed message")
                _route_to_retry_or_dlq(ch, method, properties, body)

    def on_jwt_created(ch, method, properties, body):
        with _scope_from_properties(properties):
            try:
                event = json.loads(body)
                _cache_jwt_created(event, jwt_cache)
                ch.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:
                logger.exception("failed to process jwt.created message")
                _route_to_retry_or_dlq(ch, method, properties, body)

    channel.basic_consume(queue=ORDER_CREATED_QUEUE, on_message_callback=on_order_created)
    channel.basic_consume(queue=ORDER_STATUS_CHANGED_QUEUE, on_message_callback=on_order_status_changed)
    channel.basic_consume(queue=JWT_CREATED_QUEUE, on_message_callback=on_jwt_created)
    channel.start_consuming()


def run_consumer(rabbitmq_url: str, auth_client, repository, sender, jwt_cache) -> None:
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
            consume_forever(channel, auth_client, repository, sender, jwt_cache)
        except Exception:
            logger.exception(
                "RabbitMQ connection lost or unavailable; retrying in %ss",
                RECONNECT_DELAY_SECONDS,
            )
            time.sleep(RECONNECT_DELAY_SECONDS)
