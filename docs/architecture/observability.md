# Observability: distributed tracing via propagated identifiers

This document explains how to trace one business operation end to end.
Examples of a business operation: signup, order creation, stock
reservation, payment, notification. You can follow the operation
through the logs of every service it touches. You do not need a tracing
backend to do this.

For the architectural decision behind this approach, see
[ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md).
ADR-0024 explains *why* the project chose this approach over a full
OpenTelemetry/Jaeger/Zipkin tracing backend. This document explains
*how*: the three identifiers, their lifecycle, the propagation
mechanism per hop, and worked examples from a real run.

This document covers the *logging/tracing* pillar only. The
quantitative pillar (request rate, latency, error rate, queue depth,
business volume) is a separate, sibling capability. See
[ADR-0036](../adr/0036-metrics-via-prometheus-grafana.md) for that
pillar. This document does not repeat it.

## The three identifiers

| Identifier | Scope | Lifecycle |
|---|---|---|
| **CorrelationId** | One entire business operation | The first HTTP request in the operation creates this id. The service reuses the id from the client's `X-Correlation-Id` header if the client sent one. Otherwise the service generates a new id. The id never changes after that. Every hop reuses the exact same value. No hop generates a new value. |
| **RequestId** | One HTTP request | Every service generates a fresh RequestId on every HTTP request, regardless of CorrelationId. A RequestId is never reused across requests and never forwarded. |
| **MessageId** | One RabbitMQ message | A service generates a fresh MessageId every time it publishes an event. A response to an earlier message gets its own new MessageId. Only CorrelationId carries over between the message that triggers a reaction and the message that reaction produces. |

One example makes the distinction clear. A single `POST /createOrder`
call has one CorrelationId and one RequestId. That call triggers two
publishes: `order.created` and `stock.reserve`. Each publish gets its
own distinct MessageId. Both publishes carry the same CorrelationId as
the HTTP request that triggered them. Later, `inventory-service` reacts
to `stock.reserve` by publishing `stock.reserved`. That new message
gets a third, different MessageId, but the same CorrelationId.

## Propagation per hop

**HTTP → HTTP**: Services use the `X-Correlation-Id` and
`X-Request-Id` headers. Every service has one HTTP entry point: a
servlet Filter in the four Spring services, a `net/http`/Gin
middleware in the two Go services, or a FastAPI
`@app.middleware("http")` in notification-service. This entry point
reuses an incoming `X-Correlation-Id` if present. Otherwise it
generates one. It always generates a fresh `X-Request-Id`. It echoes
both ids back as response headers. `api-gateway` also writes the
CorrelationId directly onto the proxied request's headers, not just
its own response headers. This step is needed because the gateway's
default reverse-proxy Director already forwards every other header
verbatim. Without this step, a CorrelationId the gateway itself
generated (because the original client sent none) would not reach the
downstream service.

**HTTP → RabbitMQ**: CorrelationId and MessageId ride as the AMQP
message's own native `correlation_id`/`message_id` properties. They do
not ride as custom headers, and they are not embedded in the JSON
payload. Every AMQP client library used in this project already
exposes these fields: Spring AMQP's `MessageProperties`, Go's
`amqp091-go` `Publishing`, and Python's `pika` `BasicProperties`. This
approach keeps every event's JSON body completely untouched. No
existing consumer, JSON Schema (ADR-0002), or hand-built test payload
needs to change.

**RabbitMQ → RabbitMQ / RabbitMQ → HTTP**: A consumer reads
`correlation_id`/`message_id` off the incoming AMQP properties before
doing anything else. The consumer reuses the CorrelationId; it never
mints a new one. Any further publish or outbound HTTP call made while
handling that message carries the same CorrelationId forward.

## The Outbox exception: an internal envelope, not a new column

Two services, `auth-service` and `inventory-service`, write their
outbox row in the same transaction as the business state change. A
separate poller publishes the row later (ADR-0003, ADR-0007). At write
time, the poller does not exist yet. It cannot read anything off a
live AMQP context yet. The CorrelationId must be stored somewhere
durable across that gap.

Adding a new database column would work, but it is a real migration.
Per [ADR-0023](0023-notification-service-persistence-evolution-strategy.md)'s
sibling reasoning, this project treats schema changes deliberately, not
as a default choice. Instead, both services' outbox writer wraps the
original payload in a small internal envelope before storing it:

```json
{"correlationId": "...", "messageId": "...", "body": "<the original payload, verbatim>"}
```

This envelope is **never visible outside the Outbox mechanism itself**.
The poller unwraps the envelope at publish time. It promotes
`correlationId`/`messageId` to native AMQP properties, the same
mechanism every other publish uses. It publishes `body` byte-for-byte
as the wire payload. This includes the one case where that payload is
a bare, non-JSON-object string: `auth-service`'s `user.verified` event,
published as `String.valueOf(userId)`, for example `"888"` (see
ADR-0003). No consumer, schema, or existing test anywhere in the
system needed to change for this technique. The technique is purely an
implementation detail of two outbox tables' `payload` column.

**Deliberately not fixed here**: this project does not yet propagate a
*CausationId*, the id of the specific message that caused this one, as
distinct from the CorrelationId of the whole operation. Every hop in
this system currently forms a single linear chain. CorrelationId alone
is enough to reconstruct the full timeline of one business operation
from logs. A CausationId would earn its place if this project ever
needed to reconstruct a branching/fan-out causality graph, for example
one event triggering multiple independent downstream chains whose
relative order to each other matters. This project does not need that
today, so it left CausationId out rather than add it speculatively.

## Structured logging

Every service emits one consistent field set on every log line:

```
timestamp service=<name> level=<level> correlationId=<id> requestId=<id> messageId=<id> logger=<name> - message
```

This consistent format lets you grep a correlationId across all seven
services' logs, and read the result the same way regardless of
language.

This format is deliberately **logfmt (key=value text), not JSON**, in
every language. The reason is not that JSON would look worse in Go or
Python. The reason is that Java cannot produce JSON logs without a new
dependency, such as `logstash-logback-encoder`. This project prioritizes
one consistent format across all seven services over the nicest format
each language could individually produce. See
[ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md)
for the full reasoning.

At the infrastructure level, each language uses its own idiomatic
mechanism to make this automatic for every log statement in a request
or message's scope. This way, no code needs to thread a parameter
through every intermediate function:

- **Java** (auth/products/orders/billing-service): SLF4J's `MDC`. A
  `CorrelationIdFilter`/`@RabbitListener` parameter puts the ids in MDC
  at the start of a request or message. It removes them in a `finally`
  block. `logging.pattern.console`'s `%X{correlationId:-}` placeholders
  read the ids automatically.
- **Go** (inventory-service, api-gateway): `context.Context`. Go has no
  implicit thread-local-like mechanism, so the context is carried
  explicitly through function signatures. A small `correlation.Info(...)`
  helper wraps `log/slog` and reads the context.
- **Python** (notification-service): `contextvars.ContextVar`, set via
  a `correlation_scope` context manager at the top of an HTTP request or
  a RabbitMQ callback. Values set in the async request-handling task are
  also visible to Starlette's thread-pooled synchronous path operations.
  This works because `anyio.to_thread.run_sync` copies the current
  `contextvars` context into the worker thread.

At a small number of specific domain-event boundaries, such as a message
published or an outcome resolved, each service also logs one line with
an `event`/`aggregateId` pair for that business fact, for example
`event=order.created.published aggregateId=<orderId>`. This project did
not convert every pre-existing log statement to this shape. Only the
log lines that mark a traceable business milestone were changed.

## Shared correlation infrastructure: two deliberate exceptions to "no shared library"

This project's stated convention is that services do not share a
library (see [architectural-principles.md](architectural-principles.md)).
Each service keeps its own copy of event DTOs, so a polyglot service
can evolve independently. That convention is about *business*
contracts.

The identifier-generation, context-propagation, and logging code
described above has no business meaning at all. This code must stay
byte-for-byte identical across every service in the same language, or
the CorrelationId contract breaks. Duplicating this code four (or two)
times would only add maintenance risk, with no independence benefit in
return.

- **`correlation-commons`** (Maven module, `com.easydora:correlation-commons`) —
  used by all four Spring services. It resolves as a normal Maven
  dependency. It is not a reactor module of `easydora-parent`;
  ADR-0016's "inheritance only, no reactor" decision is unchanged. Each
  service installs it to the local repository once (`mvn install`), the
  same as any other pre-built dependency. Each service's Dockerfile
  installs it in its own build stage before building the service
  itself.
- **`correlation-commons-go`** (Go module, `easydora/correlation-commons`) —
  used by `inventory-service` and `api-gateway`. It resolves via a
  relative `replace` directive in each service's own `go.mod`. This is
  the standard Go pattern for an internal shared package split across
  modules in one repository. It needs no install step at all, because
  Go resolves the relative path directly from source. This differs
  from the Maven case above.
- `notification-service` (Python) needs no equivalent module. It is
  the only Python service in the project.

This sharing surfaced a real bug while api-gateway, the second Go
consumer, was wired up. `correlation-commons-go`'s logging helper had
the literal string `"inventory-service"` hardcoded as its `service=`
field. This string was left over from when the package was still
inventory-service-private. The bug went uncaught until api-gateway's
own logs printed `service=inventory-service`. This is exactly the kind
of mistake that extracting shared code lets you fix once. Four
separate hardcoded copies would have hidden this same mistake as four
separate, individually-plausible-looking bugs instead.

## A real bug this work found: notification-service's retry path dropped the CorrelationId

`notification-service`'s consumption-resilience policy (ADR-0022)
republishes a failed message to a retry queue. It builds a fresh
`BasicProperties` object carrying the incremented attempt count and a
backoff TTL. That object was built from scratch, copying only
`content_type` and `headers` from the original message. It silently
dropped `correlation_id`/`message_id`. A message that failed once and
succeeded on retry would log its retry attempt and its eventual success
under two different, unrelated CorrelationIds.

The terminal dead-letter path never had this problem, because it
republishes the original `properties` object unmodified. Only the fact
that the retry path and the DLQ path used two different code paths let
one path carry the bug while the other did not. The fix carries
`correlation_id`/`message_id` forward explicitly into the retry-path
properties too.

## Worked example: one CorrelationId across five services

The following log lines come from a real, live run. The client
supplied `X-Correlation-Id: e2e-full-flow-test-001` on the first HTTP
call, and never resupplied it afterward:

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
