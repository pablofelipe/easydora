# ADR-0024: Distributed tracing via propagated correlation identifiers, not a tracing backend

## Status

Accepted - 2026-07-10

## Context

This project's business flow — signup, product creation, order creation,
stock reservation, payment, notification — crosses seven services in
three languages, connected almost entirely through asynchronous RabbitMQ
events (ADR-0007) rather than synchronous calls. Before this ADR, nothing
tied a log line in one service to the operation that caused it in
another: `orders-service` publishing `stock.reserve` and
`inventory-service` consuming it produced two log lines with no shared
identifier, and reconstructing "what happened to order X" meant grepping
each service's logs separately for the order id and hoping the timestamps
lined up.

The project's own goal (see
[architectural-principles.md](../architecture/architectural-principles.md))
is demonstrating distributed-systems architecture and engineering
discipline, not operating production infrastructure. This ADR evaluates
that goal directly against what a full tracing/metrics stack
(OpenTelemetry SDK + collector, Jaeger, Zipkin, Prometheus, Grafana)
would cost versus what it would teach.

### What a full tracing/metrics stack would actually cost here

- **New infrastructure containers** in `docker-compose.yml` beyond the
  two this project already runs (Postgres, RabbitMQ) — at minimum a
  collector and a trace store, doubling or tripling the infrastructure
  surface for a portfolio-scale system that today has exactly two
  business flows to trace.
- **A new dependency in every one of seven services**, in three
  different languages — the OpenTelemetry SDK for Java, Go, and Python
  each have their own instrumentation APIs, auto-instrumentation
  packages, and exporter configuration, none of which this project
  currently needs for anything else.
- **Operational knowledge this project doesn't otherwise demonstrate**:
  configuring exporters, sampling strategies, trace retention, and a
  query UI are real skills, but they are a different skill set than the
  one this project's other ADRs build up (event-driven architecture,
  the Outbox pattern, contract testing, resilient consumption, CI
  against real infrastructure).

### What this project actually needs

Every business flow in this system is already a single, linear chain —
not a branching fan-out with independent concurrent sub-flows. Tracing
"what happened to order X, in order, across every service" only requires
one stable identifier threaded through every hop and included in every
log line; it does not require span timing, service-dependency graphs, or
sampling, all of which are what a tracing backend adds on top of simple
id propagation. The projected benefit of a full stack (accurate latency
breakdowns per hop, a visual trace waterfall) does not correspond to a
problem this project currently has — no ADR here has ever been blocked
on not knowing which hop was slow.

## Decision

**Propagate three plain identifiers — CorrelationId, RequestId,
MessageId — through HTTP headers and native AMQP message properties, and
log them in a consistent structured format across every service. Do not
adopt OpenTelemetry, Jaeger, Zipkin, Prometheus, or Grafana.**

The full design (identifier lifecycle, propagation mechanism per hop,
per-language logging mechanism, the Outbox envelope technique, and a
worked example from a real run) is documented in
[docs/architecture/observability.md](../architecture/observability.md),
which this ADR defers to rather than duplicating.

### Why plain identifiers, specifically

- **CorrelationId/RequestId/MessageId ride on transport-native fields**
  (HTTP headers, AMQP `correlation_id`/`message_id` properties) rather
  than a custom trace-context header format (e.g. W3C Trace Context) —
  every language/library already used in this project exposes these as
  first-class fields, so no new dependency was needed to carry them.
- **Structured logging uses logfmt (key=value text) in every language**,
  not JSON, even though Go (`log/slog`'s `NewTextHandler` vs.
  `NewJSONHandler`) and Python could produce JSON with equal ease.
  Java cannot, without a new dependency
  (`logstash-logback-encoder` or similar) purely for this. Rather than
  three services logging JSON and one logging text, every service logs
  the same format — consistency across the whole system was weighted
  over the nicest format any single language could individually produce.
- **Two small shared modules** (`correlation-commons` for the four
  Spring services, `correlation-commons-go` for the two Go services) —
  a deliberate, narrow exception to this project's "no shared library
  between services" convention (see
  [architectural-principles.md](../architecture/architectural-principles.md)).
  That convention exists to let *business* DTOs evolve
  independently per polyglot service; this code has no business meaning
  and must stay byte-for-byte identical across every same-language
  service for the CorrelationId contract to hold, so duplicating it
  would only add maintenance risk (a fix landing in three copies and
  missed in a fourth) with no corresponding independence benefit.
  `notification-service` needs no equivalent module, being the only
  Python service.

## Consequences

**Positive**:
- One business operation is now traceable end to end through plain log
  greps, across three languages and seven services, with zero new
  infrastructure containers and one new dependency total across the
  entire project (none, actually — every mechanism used here is either
  stdlib or a library already in use).
- Found and fixed a real bug along the way: `notification-service`'s
  retry path silently dropped `correlation_id`/`message_id` when
  rebuilding message properties for a retried delivery (the terminal
  dead-letter path never had this problem, since it reuses the original
  properties unmodified) — a message that failed once and succeeded on
  retry would previously have logged its two attempts under two
  unrelated CorrelationIds.
- Found and fixed a second bug specifically because the Go tracing
  helper became genuinely shared: `correlation-commons-go`'s logger had
  `"inventory-service"` hardcoded as its service name from before
  `api-gateway` became its second consumer — api-gateway's own logs were
  printing the wrong service name until this was caught.

**Negative / residual, not fixed here**:
- **No CausationId.** This system's flows are linear chains today, so a
  CorrelationId alone reconstructs their full timeline; a CausationId
  (the id of the specific message that caused this one, as distinct from
  the whole operation's id) would only earn its place if a flow ever
  branched into independent concurrent sub-chains whose relative order
  to each other matters. Left out deliberately rather than added
  speculatively — see
  [docs/architecture/observability.md](../architecture/observability.md)'s
  own note on this.
- **No trace visualization, no latency breakdown per hop, no
  service-dependency graph.** This is the direct, accepted trade-off of
  not adopting a tracing backend — this ADR's Context section explains
  why that trade is acceptable at this project's current scale and goal,
  not that the capability doesn't exist elsewhere.
- **No sampling.** Every request/message is fully traced, all the time —
  acceptable at this project's traffic volume (a portfolio exercise, not
  production load), the same reasoning this project already applies
  elsewhere (e.g. the Outbox pattern's unremarkable 5-second poll
  interval, ADR-0003).
- This decision should be revisited if any of the following becomes
  true, concretely:
  - A business flow stops being a linear chain (independent concurrent
    sub-flows whose relative causality matters) — the point at which a
    CausationId, and likely a real trace data model, would start
    earning its place.
  - This project's own goal shifts from demonstrating architectural
    patterns toward demonstrating distributed *operations* themselves —
    the same kind of goal shift
    [ADR-0007](0007-remove-kafka-broker.md) documents for the Kafka
    removal decision, and [ADR-0018](0018-persistence-strategy.md) for
    the single-Postgres-instance decision.
  - A real need emerges to measure latency *between* hops, not just
    trace an operation's identity across them.

## Update — 2026-07-14: the Prometheus/Grafana half of this rejection was too broad

This ADR's Decision bundled five technologies into one sentence — "Do not
adopt OpenTelemetry, Jaeger, Zipkin, Prometheus, or Grafana" — and one
cost analysis (new collector containers, a new SDK dependency per
language, operational skills this project doesn't otherwise build
toward) to justify all five. That analysis is accurate for a **tracing**
backend; it does not actually hold for Prometheus and Grafana, which
need no collector (each service exposes its own scrape endpoint
directly) and no sampling strategy (a scrape interval reads current
counters, nothing is dropped).

[ADR-0036](0036-metrics-via-prometheus-grafana.md) revisits that half of
this Decision on its own merits and adopts Prometheus and Grafana for
quantitative metrics (request rate, latency, error rate, queue depth,
business volume) — the aggregate, point-in-time questions this ADR's own
CorrelationId design was never meant to answer. **This ADR's rejection of
OpenTelemetry, Jaeger, and Zipkin — a full distributed tracing backend —
is unchanged and remains in effect**, for the reasons in this ADR's
Context and Decision above: this system's flows are linear chains,
CorrelationId propagation already reconstructs their timeline, and no
ADR here has ever been blocked on not knowing which hop was slow.

## Update — 2026-08-02: a tracing backend, adopted alongside CorrelationId, not instead of it

This ADR's rejection of a tracing backend rested on one concrete
criterion, stated in its own Consequences section: revisit this decision
if the project's own goal shifts from demonstrating architectural
patterns toward demonstrating distributed *operations* themselves. That
shift has happened for this specific capability — visual trace
waterfalls and per-hop latency are now something this project wants to
demonstrate operating, not just design around. Nothing else in the
original Context changed: this system's flows are still linear chains,
and CorrelationId still fully reconstructs any one operation's timeline
from logs alone.

This is not a reversal of the CorrelationId/RequestId/MessageId design
([docs/architecture/observability.md](../architecture/observability.md)).
That design stays exactly as documented and remains the log-grep
mechanism. What's added here is a second, independent capability this
project had no other way to get: a visual span tree and real per-hop
latency numbers, which a background-poller-driven at-least-once log
identifier was never built to answer. That distinction is also what
separates this decision from the second-broker adapter
[ADR-0041](0041-kafka-rabbitmq-broker-benchmark.md) considered and
rejected for the Kafka benchmark: a live second broker there would have
duplicated a capability RabbitMQ already provides, with no requirement
this project actually has pointing at the difference. A tracing backend
duplicates nothing already present — it earns its place under
[architectural-principles.md](../architecture/architectural-principles.md)'s
principle #2 precisely because there was no existing mechanism doing
this job.

### What was added

A single **Jaeger** all-in-one container (OTLP receiver built in, no
separate collector) in `docker-compose.yml` — the same "one extra
container, not a stack" shape this project already applies to
Prometheus/Grafana (ADR-0036). Every one of the eight services now
exports spans to it:

- **api-gateway, inventory-service** (Go): `go.opentelemetry.io/otel`'s
  SDK, `otelhttp` wrapping both the inbound router and the outbound
  reverse-proxy transport for HTTP spans; a small carrier adapter
  (`internal/messaging/tracing_carrier.go`, inventory-service) injects/
  extracts a W3C `traceparent` into RabbitMQ message headers, alongside
  the existing `correlation_id`/`message_id` AMQP properties — a
  separate header because `traceparent` has its own W3C-defined format,
  not something to conflate with CorrelationId's own identifier.
- **auth-service, products-service, orders-service, billing-service**
  (Spring Boot): Micrometer Tracing's OTel bridge plus the OTLP exporter
  (`management.tracing.sampling.probability=1.0`, no sampling — same
  "trace everything" reasoning this project already applies to the
  Outbox's own unremarkable poll interval, ADR-0003). RabbitMQ
  propagation needed no new code at all:
  `RabbitTemplate.setObservationEnabled(true)` and
  `SimpleRabbitListenerContainerFactory.setObservationEnabled(true)` are
  existing Spring AMQP options that inject/extract `traceparent`
  automatically once an `ObservationRegistry`/`Tracer` bean exists.
- **notification-service** (FastAPI):
  `opentelemetry-instrumentation-fastapi` for the HTTP surface,
  `opentelemetry-instrumentation-httpx` for the one synchronous call to
  auth-service, and a manual `propagate.extract`/`start_as_current_span`
  pair around each of the three `pika` consumer callbacks (this
  service's own carrier is a plain dict, `pika`'s header type already
  matches OTel's default `Getter`/`Setter` shape).

### Evidence, not just a running container

A real login (`POST /login`, `auth.exchange`/`jwt.created`) produced one
trace spanning **6 services, depth 6, 13 spans**: api-gateway → auth-service
→ a single producer span, fanning out into **five** independent consumer
spans across three languages and two of this project's messaging queues'
worth of competing/parallel consumers — `notification-service`,
`orders-service` (twice, its two independent `jwt.created` queues,
ADR-0001's fan-out fix), `products-service`, `billing-service`. Screenshot:
[`images/jaeger-jwt-created-fanout-trace.jpg`](images/jaeger-jwt-created-fanout-trace.jpg).
A `POST /createProduct` call produced an equally real cross-language
trace: `products-service`'s HTTP handler → its own
`product.exchange/product.created` producer span → a consumer span in
`orders-service` (Java) and a separate one in `inventory-service` (Go) —
proof the W3C `traceparent` propagates correctly across a RabbitMQ hop
between two different language runtimes, not just within one.

### A real, honest gap this surfaced: Outbox-mediated publishes don't carry a trace

`POST /createOrder`'s own trace stops at the HTTP server span. It does
not continue into `stock.reserve`/`order.created`'s producer or consumer
spans, even though the same call's CorrelationId reaches every one of
those hops correctly. The reason is structural, not a bug: `orders-service`,
`inventory-service`, and `billing-service` all publish through the Outbox
pattern (ADR-0037) — a background poller sends the message seconds after
the original request's span has already ended, the same write-to-publish
gap [docs/architecture/observability.md](../architecture/observability.md)
already documents for CorrelationId. CorrelationId survives that gap
because the outbox row's payload is wrapped in a small internal envelope
before storage. `traceparent` is not added to that envelope in this
Update — doing so touches a cross-language envelope format shared by
`correlation-commons` (Java) and `correlation-commons-go`, plus every
outbox-writing call site in both languages, which is a larger, separate
change than instrumenting the already-live HTTP/RabbitMQ hops above. The
services that still publish directly rather than through the Outbox
(`auth-service`'s `user.registered`/`jwt.created`, `products-service`'s
`product.*`) show no such gap, as the two traces above demonstrate.

### Incidental fixes made while validating this live

- `auth-service/Dockerfile` had a stale hardcoded jar filename
  (`auth-service-0.0.1-SNAPSHOT.jar`) left over from before the parent
  POM's version was fixed at `0.3.0` — every other service's Dockerfile
  already used a wildcard (`*.jar`). Never surfaced before because no
  session had rebuilt this image from scratch since that version was
  set. Fixed to match its three siblings; unrelated to tracing itself.
- `go.work`'s and both Go services' `go.mod`'s `go` directive moved from
  `1.23.0` to `1.25.0` (the OpenTelemetry Go SDK's own dependency chain
  requires it); both Dockerfiles' builder base image moved from
  `golang:1.23-alpine` to `golang:1.25-alpine` to match.
- `notification-service/requirements.txt` pins `setuptools<81`:
  `opentelemetry-instrumentation` 0.48b0 still imports the now-removed
  `pkg_resources` API, which `setuptools>=81` no longer ships.

## References

- [docs/architecture/observability.md](../architecture/observability.md) —
  the full design this ADR's Decision defers to: identifier lifecycle,
  propagation mechanism per hop, per-language logging mechanism, the
  Outbox envelope technique, and a worked example from a real run.
- [ADR-0007](0007-remove-kafka-broker.md) — the RabbitMQ-only messaging
  model this tracing strategy propagates identifiers across; also the
  precedent this ADR's "revisit if the goal shifts" criterion is drawn
  from.
- [ADR-0016](0016-shared-spring-parent-pom.md) — the existing shared
  Maven parent this ADR's `correlation-commons` module sits alongside,
  without becoming a reactor module of it.
- [ADR-0023](0023-notification-service-persistence-evolution-strategy.md) —
  the sibling reasoning behind avoiding a new database column for the
  Outbox envelope technique described in
  [docs/architecture/observability.md](../architecture/observability.md).
- [Architectural Principles](../architecture/architectural-principles.md) —
  principle #1 (deliberate simplicity over engineered precision) and
  principle #2 (a component must earn its place) both apply directly to
  why a full tracing *backend* isn't justified here today; this ADR's
  shared-module decision is a deliberate, narrow exception to the
  document's "no shared library between services" convention, explained
  above and in observability.md.
- [ADR-0036](0036-metrics-via-prometheus-grafana.md) — the 2026-07-14
  Update above's narrower revisit: Prometheus/Grafana adopted for
  quantitative metrics, while this ADR's rejection of a tracing backend
  stood unchanged until the 2026-08-02 Update below; also the "one extra
  container, not a stack" shape the Jaeger addition mirrors.
- [ADR-0037](0037-consolidated-outbox-pattern-specification.md) — the
  Outbox pattern whose write-to-publish gap the 2026-08-02 Update's
  "real, honest gap" section identifies as the reason `traceparent`
  doesn't yet survive an outbox-mediated publish.
- [ADR-0041](0041-kafka-rabbitmq-broker-benchmark.md) — the contrasting
  case the 2026-08-02 Update draws on: a live second broker rejected
  there for duplicating an existing capability with no real requirement
  behind it, versus a tracing backend adopted here for providing a
  capability that had no existing equivalent at all.
