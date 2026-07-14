# Observability: distributed tracing via propagated identifiers

This document explains how one business operation — signup, order
creation, stock reservation, payment, notification — can be followed
end to end through the logs of every service it touches, without a
tracing backend. For the architectural decision behind *why* this
approach was chosen over a full OpenTelemetry/Jaeger/Zipkin tracing
backend, see [ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md).
This document is the how: the three identifiers, their lifecycle, the
propagation mechanism per hop, and worked examples from a real run.

This covers the *logging/tracing* pillar only — the quantitative
counterpart (request rate, latency, error rate, queue depth, business
volume) is a separate, sibling capability documented in
[ADR-0036](../adr/0036-metrics-via-prometheus-grafana.md), not repeated
here.

## The three identifiers

| Identifier | Scope | Lifecycle |
|---|---|---|
| **CorrelationId** | One entire business operation | Born at the first HTTP request that starts the operation (reused from the client's `X-Correlation-Id` header if it sent one, generated otherwise). Immutable from that point on — every hop reuses the exact same value, never generates a new one. |
| **RequestId** | One HTTP request | Freshly generated on every HTTP request, at every service, regardless of CorrelationId. Never reused across requests, never forwarded. |
| **MessageId** | One RabbitMQ message | Freshly generated every time a service publishes an event. A response/reaction to an earlier message gets its own new MessageId — only CorrelationId carries over between the message that triggered a reaction and the message that reaction produces. |

A concrete example makes the distinction clearest: a single `POST
/createOrder` call has one CorrelationId and one RequestId. That call
triggers two publishes (`order.created`, `stock.reserve`) — each gets
its own distinct MessageId, but both carry the same CorrelationId as the
HTTP request that triggered them. When `inventory-service` reacts to
`stock.reserve` by publishing `stock.reserved`, that new message gets a
third, different MessageId — but still the same CorrelationId.

## Propagation per hop

**HTTP → HTTP**: `X-Correlation-Id` and `X-Request-Id` headers. Every
service's HTTP entry point (a servlet Filter in the four Spring services,
a `net/http`/Gin middleware in the two Go services, a FastAPI
`@app.middleware("http")` in notification-service) reuses an incoming
`X-Correlation-Id` if present, generates one otherwise, always generates
a fresh `X-Request-Id`, and echoes both back as response headers.
`api-gateway` additionally writes the CorrelationId directly onto the
proxied request's headers (not just its own response), since its default
reverse-proxy Director already forwards every other header verbatim —
this guarantees a CorrelationId the gateway itself generated (because the
original client sent none) still reaches the downstream service, not just
one the client supplied.

**HTTP → RabbitMQ**: CorrelationId and MessageId ride as the AMQP
message's own native `correlation_id`/`message_id` properties (not custom
headers, not embedded in the JSON payload) — every AMQP client library
used in this project (Spring AMQP's `MessageProperties`, Go's
`amqp091-go` `Publishing`, Python's `pika` `BasicProperties`) already
exposes these as first-class fields. This keeps every event's JSON body
completely untouched: no existing consumer, JSON Schema (ADR-0002), or
hand-built test payload needed to change.

**RabbitMQ → RabbitMQ / RabbitMQ → HTTP**: a consumer reads
`correlation_id`/`message_id` off the incoming AMQP properties (reusing
the CorrelationId, never minting a new one) before doing anything else,
so any further publish or outbound HTTP call made while handling that
message carries the same CorrelationId forward.

## The Outbox exception: an internal envelope, not a new column

Two services (`auth-service`, `inventory-service`) write their outbox row
in the same transaction as the business state change, and a separate
poller publishes it later (ADR-0003, ADR-0007). At write time, the
poller doesn't exist yet to read anything off a live AMQP context — the
CorrelationId has to be persisted *somewhere* durable across that gap.

Rather than adding a new database column (a real, if small, migration —
and, per [ADR-0023](0023-notification-service-persistence-evolution-strategy.md)'s
sibling reasoning, exactly the kind of schema change this project treats
deliberately), both services' outbox writer wraps the original payload
in a small internal envelope before storing it:

```json
{"correlationId": "...", "messageId": "...", "body": "<the original payload, verbatim>"}
```

This envelope is **never visible outside the Outbox mechanism itself**.
The poller unwraps it at actual publish time, promotes `correlationId`/
`messageId` to native AMQP properties (the same mechanism every other
publish uses), and publishes `body` byte-for-byte as the wire payload —
including the one case where that payload is a bare, non-JSON-object
string (`auth-service`'s `user.verified`, published as
`String.valueOf(userId)`, e.g. `"888"` — see ADR-0003). No consumer,
schema, or existing test anywhere in the system needed to change because
of this technique; it is purely an implementation detail of two outbox
tables' `payload` column.

**Deliberately not fixed here**: this project does not (yet) propagate a
*CausationId* (the id of the specific message that caused this one, as
distinct from the CorrelationId of the whole operation). With every hop
in this system currently forming a single linear chain, CorrelationId
alone is enough to reconstruct the full timeline of one business
operation from logs. A CausationId would earn its place if this project
ever needed to reconstruct a branching/fan-out causality graph (e.g. one
event triggering multiple independent downstream chains whose relative
order to each other matters) — it does not today, so it was left out
rather than added speculatively.

## Structured logging

Every service emits one consistent field set on every log line, so a
correlationId can be grepped across all seven services' logs and read
the same way regardless of language:

```
timestamp service=<name> level=<level> correlationId=<id> requestId=<id> messageId=<id> logger=<name> - message
```

This is deliberately **logfmt (key=value text), not JSON**, in every
language — not because JSON wouldn't be nicer in Go or Python, but
because Java can't produce it without a new dependency
(`logstash-logback-encoder` or similar), and this project prioritizes one
consistent format across all seven services over the nicest format each
language could individually produce. See
[ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md)
for the full reasoning.

At the infrastructure level (HTTP filter/middleware, RabbitMQ
consumer/publisher), each language uses its own idiomatic mechanism to
make this automatic for every log statement in the request/message's
scope, without threading a parameter through every intermediate function:

- **Java** (auth/products/orders/billing-service): SLF4J's `MDC`. A
  `CorrelationIdFilter`/`@RabbitListener` parameter puts the ids in MDC at
  the start of a request/message and removes them in a `finally` block;
  `logging.pattern.console`'s `%X{correlationId:-}` placeholders read
  them automatically.
- **Go** (inventory-service, api-gateway): `context.Context`, carried
  explicitly through function signatures (Go has no implicit
  thread-local-like mechanism) and read by a small `correlation.Info(...)`
  helper wrapping `log/slog`.
- **Python** (notification-service): `contextvars.ContextVar`, set via a
  `correlation_scope` context manager at the top of an HTTP request or a
  RabbitMQ callback. Values set in the async request-handling task are
  also visible to Starlette's thread-pooled synchronous path operations,
  since `anyio.to_thread.run_sync` copies the current `contextvars`
  context into the worker thread.

At a small number of specific domain-event boundaries (a message
published, an outcome resolved), each service also logs one line with an
`event`/`aggregateId` pair for that specific business fact (e.g.
`event=order.created.published aggregateId=<orderId>`) — this project did
not attempt to convert every pre-existing log statement to this shape;
only the log lines that mark a traceable business milestone were
touched.

## Shared correlation infrastructure: two deliberate exceptions to "no shared library"

This project's stated convention (see
[architectural-principles.md](architectural-principles.md) and
`CLAUDE.md`) is that services don't share a library — each keeps its own
copy of event DTOs, so a polyglot service can evolve independently. That
convention is about *business* contracts. The identifier-generation,
context-propagation, and logging code described above has no business
meaning at all — it must, in fact, stay byte-for-byte identical across
every service in the same language for the CorrelationId contract to
hold, so duplicating it four (or two) times over would only add
maintenance risk with no corresponding independence benefit.

- **`correlation-commons`** (Maven module, `com.easydora:correlation-commons`) —
  used by all four Spring services. Resolved as a normal Maven dependency;
  not a reactor module of `easydora-parent` (ADR-0016's "inheritance
  only, no reactor" decision is unchanged) — installed to the local
  repository once (`mvn install`), same as any other pre-built
  dependency. Each service's Dockerfile installs it in its own build
  stage before building the service itself.
- **`correlation-commons-go`** (Go module, `easydora/correlation-commons`) —
  used by `inventory-service` and `api-gateway`. Resolved via a relative
  `replace` directive in each service's own `go.mod` — the standard Go
  pattern for an internal shared package split across modules in one
  repository, requiring no install step at all (Go resolves the relative
  path directly from source), unlike the Maven case.
- `notification-service` (Python) needs no equivalent, being the only
  Python service in the project.

A real bug surfaced by this sharing, while api-gateway (the second Go
consumer) was wired up: `correlation-commons-go`'s logging helper had the
literal string `"inventory-service"` hardcoded as its `service=` field,
left over from when the package was still inventory-service-private. Not
caught until api-gateway's own logs printed `service=inventory-service`
— exactly the kind of mistake extracting shared code is meant to let you
fix once instead of chasing down per copy, and exactly the kind of
mistake four separate hardcoded copies would have hidden as four
separate, individually-plausible-looking bugs instead.

## A real bug this work found: notification-service's retry path dropped the CorrelationId

`notification-service`'s consumption-resilience policy (ADR-0022)
republishes a failed message to a retry queue with a fresh
`BasicProperties` object carrying the incremented attempt count and a
backoff TTL. That object was built from scratch, copying only
`content_type` and `headers` from the original message — silently
dropping `correlation_id`/`message_id`. A message that failed once and
succeeded on retry would log its retry attempt and its eventual success
under two different, unrelated CorrelationIds. The terminal dead-letter
path never had this problem (it republishes the original `properties`
object unmodified); only the fact that retry and DLQ used two different
code paths let one carry the bug while the other didn't. Fixed by
carrying `correlation_id`/`message_id` forward explicitly into the
retry-path properties too.

## Worked example: one CorrelationId across five services

The following was captured from a real, live run (`X-Correlation-Id:
e2e-full-flow-test-001` supplied on the first HTTP call, never
resupplied afterward):

```
auth-service      correlationId=e2e-full-flow-test-001  (signup, verify, login — seller and buyer)
products-service  correlationId=e2e-full-flow-test-001  event=product.created.published
inventory-service correlationId=e2e-full-flow-test-001  event=product.created (message received/processed)
orders-service    correlationId=e2e-full-flow-test-001  event=order.created.published
orders-service    correlationId=e2e-full-flow-test-001  event=stock.reserve.published
inventory-service correlationId=e2e-full-flow-test-001  event=stock.reserved
orders-service    correlationId=e2e-full-flow-test-001  event=order.status-changed.published (PROCESSING -> INVENTORY_RESERVED)
billing-service   correlationId=e2e-full-flow-test-001  event=order.created.received / payment.pending.created
billing-service   correlationId=e2e-full-flow-test-001  event=payment.approved.published
orders-service    correlationId=e2e-full-flow-test-001  event=order.status-changed.published (INVENTORY_RESERVED -> PAYMENT_APPROVED)
```

Ten log lines, five services, three languages, one grep-able id.

## Where to go next

- [ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md) —
  why this approach, and why not a tracing backend.
- [ADR-0036](../adr/0036-metrics-via-prometheus-grafana.md) — the
  quantitative metrics pillar (Prometheus/Grafana) that sits alongside
  this document's logging/tracing design, not inside it.
- [Architecture Overview](overview.md) — the system map this tracing
  strategy sits inside.
- [Walkthrough](../walkthrough.md) — the same business flow this
  document's worked example is drawn from, driven by real `curl` calls.
