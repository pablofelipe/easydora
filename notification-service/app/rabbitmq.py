import json
import logging
import time
from datetime import datetime, timedelta

import pika
from opentelemetry import trace
from prometheus_client import Counter

from app.consumer import process_order_created, process_order_status_changed
from app.correlation import correlation_scope, current_or_new_correlation_id
from app.health import ProgressWatchdog
from app.tracing import extract_trace_context, tracer

logger = logging.getLogger(__name__)

# Reconnection observability (docs/adr/0038-infrastructure-startup-resilience.md's
# Update): same metric names/shapes as inventory-service's (Go) and the
# four Spring services' equivalents. run_consumer's loop has no
# structurally separate boot-time phase the way Go's does (see its own
# docstring), so "before the first successful connection" stands in for
# it -- neither counter is incremented until at least one connect +
# declare_topology cycle has already succeeded once.
rabbitmq_reconnect_attempts_total = Counter(
    "rabbitmq_reconnect_attempts_total",
    "Total steady-state RabbitMQ reconnect attempts made by run_consumer, successful or not.",
)
rabbitmq_topology_setup_total = Counter(
    "rabbitmq_topology_setup_total",
    "Total attempts to (re)declare this service's RabbitMQ topology after a reconnect, by outcome.",
    ["outcome"],
)

# Makes _route_to_retry_or_dlq's outcome a queryable metric (ADR-0036/
# ADR-0037's convention), not just a log line: process_order_created/
# process_order_status_changed never see a retry count, and until now
# nothing outside this file's own WARNING/ERROR logs did either.
notification_retry_total = Counter(
    "notification_retry_total",
    "Total messages routed by _route_to_retry_or_dlq, by outcome (retry or dead_letter).",
    ["outcome"],
)

RECONNECT_DELAY_SECONDS = 5

# Bounds only the very first connection attempt, before this process has
# ever connected once -- mirrors app/schema.py's ensure_schema (also
# MAX_ATTEMPTS=10) and inventory-service's own already-bounded boot-time
# RabbitMQ connection (ADR-0038's Decision). Consistent with both: a
# permanently unreachable broker at boot should fail loudly (raise, let
# the container crash and restart) rather than leave the process
# "healthy" forever having never connected once. The steady-state
# reconnect path below (after at least one successful connection) is
# deliberately NOT bounded this way -- see ADR-0038's Update.
BOOT_MAX_ATTEMPTS = 10

# Explicit, not left to whatever pika/RabbitMQ negotiate by default (see
# docs/adr/0038-infrastructure-startup-resilience.md's Update): detection
# of a dead connection depends entirely on this value. A missed heartbeat
# is what makes BlockingConnection.start_consuming() eventually raise and
# fall into run_consumer's own retry loop below -- without an explicit
# value, that detection window is implicit and unverified.
HEARTBEAT_SECONDS = 30

# How often a call_later tick fires while start_consuming() is otherwise
# blocking -- the equivalent of Spring AMQP's ListenerContainerIdleEvent:
# proves the connection's own ioloop is still alive and processing events,
# independent of whether any business message has arrived.
IDLE_TICK_SECONDS = 30

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
    parameters = pika.URLParameters(rabbitmq_url)
    parameters.heartbeat = HEARTBEAT_SECONDS
    connection = pika.BlockingConnection(parameters)
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
        notification_retry_total.labels(outcome="retry").inc()
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
        notification_retry_total.labels(outcome="dead_letter").inc()

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


def consume_forever(channel, auth_client, repository, sender, jwt_cache, watchdog: ProgressWatchdog | None = None) -> None:
    def _record_progress():
        if watchdog is not None:
            watchdog.record_progress()

    def _idle_tick():
        _record_progress()
        channel.connection.call_later(IDLE_TICK_SECONDS, _idle_tick)

    def on_order_created(ch, method, properties, body):
        _record_progress()
        trace_ctx = extract_trace_context(properties.headers)
        with _scope_from_properties(properties), tracer.start_as_current_span(
            f"{ORDER_CREATED_QUEUE} receive", context=trace_ctx, kind=trace.SpanKind.CONSUMER
        ):
            try:
                event = json.loads(body)
                process_order_created(event, auth_client, sender, correlation_id=properties.correlation_id or "")
                ch.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:
                logger.exception("failed to process order.created message")
                _route_to_retry_or_dlq(ch, method, properties, body)

    def on_order_status_changed(ch, method, properties, body):
        _record_progress()
        trace_ctx = extract_trace_context(properties.headers)
        with _scope_from_properties(properties), tracer.start_as_current_span(
            f"{ORDER_STATUS_CHANGED_QUEUE} receive", context=trace_ctx, kind=trace.SpanKind.CONSUMER
        ):
            try:
                event = json.loads(body)
                process_order_status_changed(event, repository, sender)
                ch.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:
                logger.exception("failed to process order.status-changed message")
                _route_to_retry_or_dlq(ch, method, properties, body)

    def on_jwt_created(ch, method, properties, body):
        _record_progress()
        trace_ctx = extract_trace_context(properties.headers)
        with _scope_from_properties(properties), tracer.start_as_current_span(
            f"{JWT_CREATED_QUEUE} receive", context=trace_ctx, kind=trace.SpanKind.CONSUMER
        ):
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
    channel.connection.call_later(IDLE_TICK_SECONDS, _idle_tick)
    channel.start_consuming()


def run_consumer(rabbitmq_url: str, auth_client, repository, sender, jwt_cache,
                  watchdog: ProgressWatchdog | None = None) -> None:
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

    watchdog (docs/adr/0038-infrastructure-startup-resilience.md's Update)
    records progress on every iteration of this loop -- successful or
    not -- plus every message processed and every idle tick inside
    consume_forever, so an arbitrarily long, tolerated broker outage never
    looks "stuck" as long as this loop keeps retrying.

    The very first connection attempt is bounded (BOOT_MAX_ATTEMPTS,
    ADR-0038's 2026-08-03 Update): giving up and raising after that many
    failures is a deliberate exception to "never gives up permanently"
    above, matching ensure_schema's own bounded-then-raise shape for
    Postgres -- a permanently unreachable broker (e.g. a misconfigured
    RABBITMQ_URL) should crash this container loudly instead of leaving it
    reporting healthy forever, having never connected once. Once past that
    first connection, every later disconnect goes through the unbounded
    reconnect path unchanged -- a transient broker outage should still
    self-heal without crashing an otherwise-working process.
    """
    first_connection = True
    boot_attempts = 0
    while True:
        if watchdog is not None:
            watchdog.record_progress()
        if not first_connection:
            rabbitmq_reconnect_attempts_total.inc()

        # Connect + topology are tracked separately from consume_forever
        # below: a mid-run consume failure is an ordinary reconnect-worthy
        # event (already counted by rabbitmq_reconnect_attempts_total on
        # the next iteration), not a topology redeclaration failure --
        # conflating the two would misreport a plain disconnect as a
        # topology problem.
        try:
            _connection, channel = connect(rabbitmq_url)
            declare_topology(channel)
        except Exception:
            if not first_connection:
                rabbitmq_topology_setup_total.labels(outcome="failure").inc()
                logger.exception(
                    "RabbitMQ connection lost or unavailable; retrying in %ss",
                    RECONNECT_DELAY_SECONDS,
                )
            else:
                boot_attempts += 1
                if boot_attempts >= BOOT_MAX_ATTEMPTS:
                    logger.error(
                        "RabbitMQ still unreachable after %d boot-time attempts; giving up",
                        BOOT_MAX_ATTEMPTS,
                    )
                    raise
                logger.exception(
                    "RabbitMQ not ready yet (boot attempt %d/%d); retrying in %ss",
                    boot_attempts, BOOT_MAX_ATTEMPTS, RECONNECT_DELAY_SECONDS,
                )
            time.sleep(RECONNECT_DELAY_SECONDS)
            continue

        if not first_connection:
            rabbitmq_topology_setup_total.labels(outcome="success").inc()
        first_connection = False

        try:
            consume_forever(channel, auth_client, repository, sender, jwt_cache, watchdog)
        except Exception:
            logger.exception(
                "RabbitMQ connection lost or unavailable; retrying in %ss",
                RECONNECT_DELAY_SECONDS,
            )
            time.sleep(RECONNECT_DELAY_SECONDS)
