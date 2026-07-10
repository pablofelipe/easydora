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
  [architectural-principles.md](../architecture/architectural-principles.md)
  and `CLAUDE.md`). That convention exists to let *business* DTOs evolve
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
  why a full tracing/metrics stack isn't justified here today; this ADR's
  shared-module decision is a deliberate, narrow exception to the
  document's "no shared library between services" convention, explained
  above and in observability.md.
