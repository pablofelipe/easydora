# ADR-0036: Quantitative observability via Prometheus and Grafana

## Status

Accepted - 2026-07-14

## Context

[ADR-0024](0024-distributed-tracing-via-propagated-identifiers.md) gave
this project qualitative distributed observability: a CorrelationId
threaded through every hop lets a reader reconstruct exactly what
happened to one business operation, across seven services and three
languages, by grepping structured logs. That ADR's Decision also
explicitly declined Prometheus and Grafana at the time, bundled together
with OpenTelemetry/Jaeger/Zipkin under one rejection.

That bundling conflated two different capabilities. Tracing answers "what
happened to operation X, in order, across every service" — a question
about one instance, answered by following an identifier. What it cannot
answer, no matter how many CorrelationIds are grepped, is aggregate,
point-in-time state: which service is slowest right now, what fraction of
payments are failing this hour, whether a queue is backing up, how many
stock reservations fail per day. Those are quantitative questions —
counting and rating events across every instance of an operation, not
following one instance through its lifecycle — and no amount of log
correlation answers them without writing a one-off script every time the
question comes up.

### Re-examining ADR-0024's bundled rejection

ADR-0024's own cost analysis was aimed at a full **tracing** backend:
collector, trace store, span timing, service-dependency graphs, sampling
strategy — real operational surface this project's other ADRs don't
otherwise build toward. None of that cost analysis actually applies to
Prometheus and Grafana specifically. A metrics stack does not require a
collector (services expose their own `/metrics` endpoint; Prometheus
scrapes it directly), does not require a trace store (Prometheus's own
time-series storage is the whole stack), and does not require choosing a
sampling strategy (a scrape interval is not a sampling decision — every
scrape reads the service's current cumulative counters, nothing is
dropped). The two technologies were rejected together because they
arrived in the same sentence, not because the same argument actually
applies to both.

### What investigation found: most of this is already free

Before deciding to add anything, each candidate metric source was
checked against what the stack already runs, rather than assumed to need
a new component:

- **RabbitMQ** ships `rabbitmq_prometheus` built into the image already
  in use (`rabbitmq:3-management-alpine`) since RabbitMQ 3.8 — it only
  needed enabling, not a separate exporter container.
- **The four Spring services** get JVM metrics (heap, threads, GC) and
  HTTP request-rate/latency/error metrics (`http_server_requests_seconds`)
  automatically the moment `micrometer-registry-prometheus` is on the
  classpath — zero custom instrumentation code.
- **Postgres connection visibility** does not require a `postgres_exporter`
  side-car (a fifth long-running container beyond the four this project
  already runs). Each Spring service's own HikariCP pool already exposes
  `hikaricp_connections_active`/`_max`/`_pending` through the same
  Micrometer registry above — client-side, not server-side, but it
  answers the actual question ("how many connections is each service
  using") without new infrastructure. Server-side Postgres internals
  (cache hit ratio, lock waits, table bloat) stay invisible under this
  choice; see Consequences.
- **The two Go services** get runtime metrics (goroutines, heap) for free
  from `promhttp.Handler()`'s default collectors. HTTP request-rate/
  latency/errors, by contrast, is **not** free in Go the way it is in
  Java — `promhttp.Handler()` only serves whatever is registered, and
  nothing auto-instruments incoming requests the way Micrometer does.
  Since `api-gateway` is this system's single ingress point, leaving it
  blind on exactly the Overview dashboard's headline panels (request
  rate, latency, error rate) was the one gap infra alone didn't close —
  a small `http_requests_total`/`http_request_duration_seconds`
  counter+histogram pair, applied as Gin/`net/http` middleware, is the
  one deliberate piece of custom instrumentation this decision required.
- **`notification-service` (Python)** gets process metrics (memory, CPU)
  for free from `prometheus_client`'s default collectors.

Business volume — orders placed, payments approved/failed, stock
reservation failures, notifications sent — is the one category no
platform-native collector can answer, because it requires knowing what
the number *means*, not just that a request happened. This is the
deliberately small set of custom counters this decision adds; see
Decision.

## Decision

**Add Prometheus and Grafana as the metrics pillar alongside ADR-0024's
existing logging pillar. Do not adopt OpenTelemetry, Jaeger, or Zipkin —
ADR-0024's rejection of a full tracing backend is unchanged and stays in
effect.** Concretely:

- Every service exposes its own native metrics endpoint
  (`/actuator/prometheus` for the four Spring services, `/metrics` for
  the two Go services and `notification-service`) rather than pushing to
  a collector.
- `docker-compose.yml` gains exactly two new containers: `prometheus`
  (scrapes every endpoint above plus RabbitMQ's native plugin) and
  `grafana` (datasource and every dashboard provisioned from versioned
  files under `observability/grafana/provisioning`, not configured by
  hand in the UI).
- Platform-native metrics are used everywhere they already answer the
  question (JVM, Go runtime, RabbitMQ, HikariCP-based Postgres
  visibility). Custom instrumentation is added only where the platform
  genuinely has no answer: the Go HTTP-metrics middleware described
  above, and five business counters —
  `orders_created_total`, `payments_approved_total`,
  `payments_failed_total`, `inventory_reservations_failed_total`,
  `notifications_sent_total` — each incremented at the single point in
  its service where that outcome is already decided (e.g.
  `PaymentService.publishPaymentEvent`, which already distinguishes
  approved from failed to pick a routing key). No metric was added for
  every domain event this system produces; five was a deliberate,
  reviewed stopping point, not an arbitrary one — see the "reject" list
  below.

### What was deliberately not added

- **No per-domain-event metric spam.** Counters like
  `seller_created_total`, `inventory_checked_total`, or one counter per
  RabbitMQ routing key were considered and rejected — a metric earns its
  place by answering a question infra-level metrics can't, not by
  existing for every event this system happens to publish.
- **No Alertmanager.** Prometheus without an alerting/paging layer is a
  query-and-dashboard tool, not an on-call system — appropriate for a
  project with no on-call rotation to page.
- **No Loki.** ADR-0024's structured logfmt output is queried by grep
  today; centralizing it behind a log-aggregation UI is a genuinely
  separate decision with its own cost, not a natural extension of adding
  metrics.
- **No `postgres_exporter`.** See the investigation above — HikariCP's
  client-side view answers the connection-count question this project
  actually has without a fifth container.

## Objective criteria for revisiting this decision

In the same spirit as [ADR-0018](0018-persistence-strategy.md),
[ADR-0023](0023-notification-service-persistence-evolution-strategy.md),
and [ADR-0035](0035-reject-dto-code-generation-from-json-schema.md):

- **A real need to page someone emerges** — at that point Alertmanager
  earns its place; today there is no on-call to page.
- **Server-side Postgres internals become a real question** (lock
  contention, cache hit ratio, table bloat) that HikariCP's client-side
  view genuinely cannot answer — at that point `postgres_exporter`
  earns its fifth container.
- **A business question keeps getting answered by a one-off log grep or
  SQL query more than once** — that repetition is the actual signal a
  sixth counter has earned its place, not a hunch that "more metrics"
  is generically good.
- **ADR-0024's own reopening criteria fire** (a branching/fan-out flow
  needing a CausationId, or a real need to measure latency *between*
  hops) — that reopens the tracing-backend question, which remains
  entirely separate from this ADR.

## Consequences

**Positive**:
- The concrete questions in this ADR's Context — which service is
  slowest, what fraction of payments fail, whether a queue is backing
  up, how many stock reservations fail — are now answerable from a
  dashboard instead of unanswerable by design, closing the gap
  ADR-0024 knowingly left open.
- Zero new containers were needed for RabbitMQ metrics (native plugin)
  and Postgres connection visibility (HikariCP), keeping the
  infrastructure footprint to exactly two new containers
  (`prometheus`, `grafana`) despite covering seven services plus the
  broker.
- Dashboards are versioned files, reviewable in a diff like any other
  code, not tribal knowledge trapped in a UI someone configured once.

**Negative / residual, not fixed here**:
- **No trace visualization, no per-hop latency breakdown, no
  service-dependency graph.** Unchanged from ADR-0024 — this ADR adds
  aggregate metrics, not distributed tracing; the two remain separate
  capabilities with separate trade-offs.
- **No alerting.** A dashboard must be looked at; nothing here pages
  anyone when a threshold is crossed.
- **Postgres server-side internals stay invisible** (lock waits, cache
  hit ratio, bloat) — the deliberate trade described above.
- **Five business counters, not a general framework.** A sixth
  question that comes up will need its own deliberate counter added at
  its own decision point, not a generic "track everything" mechanism —
  consistent with this project's principle of a component earning its
  place rather than being added speculatively.

## Update — 2026-07-15: a sixth business counter, for a different reason than the original five

The five original counters each answer a business-volume question ("how
many of X happened"). A follow-up architecture review of
`inventory-service`'s idempotency cache (extended from `ReserveStock` to
`ReleaseStock` the same day — see
[ADR-0039](0039-jwt-broadcast-cache-restart-and-ttl.md)'s sibling finding
for the JWT cache side of the same gap) found a different kind of
question this ADR's five counters don't answer either: **is a known,
accepted residual risk actually happening**, not just theoretically
possible. Both `ReserveStock` and `ReleaseStock` cache a command's outcome
per `OrderID` to survive RabbitMQ redelivery; whether a redelivery is
actually arriving and being caught was, until now, provable only by unit
test (`TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation`),
never observable in a running instance.

This doesn't fit the "one-off log grep repeated" reopening criterion
below — it's the first time this specific question needed answering —
but it fits this ADR's underlying principle the same way: a metric earns
its place by answering a question infra-level metrics and existing
counters can't, not by existing for every event a system happens to
produce. `inventory_idempotent_duplicate_detected_total{operation}`
(`operation` is `"reserve"` or `"release"`) is incremented exactly once
per cache hit, in `inventoryService.ReserveStock`/`ReleaseStock`,
following the same `promauto`/`CounterVec` shape `httpRequestsTotal` and
`infraStartupRetryAttempts` already use in this same service. Proven by
`TestReserveStock_DuplicateDetectionIncrementsMetric`/
`TestReleaseStock_DuplicateDetectionIncrementsMetric` (a before/after
delta via `testutil.ToFloat64`, since the counter is a package-level,
process-global metric shared by every test in the binary).

The JWT broadcast cache's equivalent gap (no hit/miss/duplicate visibility
on any of the four services' token caches) remained open at the time this
Update was written — tracked as follow-up work alongside the `expiresIn`
TTL [ADR-0039](0039-jwt-broadcast-cache-restart-and-ttl.md) had decided
but not yet implemented. See that ADR's own 2026-07-15 Update: both landed
together, the same day, once the TTL work made this cache's own hit/miss/
expired outcomes concrete enough to instrument.

## Update — 2026-07-15: a seventh counter, closing the JWT cache's own gap

`jwt_cache_lookup_total{outcome}` (`hit`/`miss`/`expired`) in all four
services — `orders-service`, `products-service`, `billing-service`
(Micrometer, constructor-injected `ObjectProvider<MeterRegistry>` rather
than a direct `MeterRegistry` dependency, so `JwtAuthenticationFilter`
still constructs cleanly inside a `@WebMvcTest` slice that doesn't
autoconfigure a real registry bean) and `notification-service`
(`prometheus_client.Counter`, the same mechanism `notifications_sent_total`
already uses). Incremented at the exact point [ADR-0039](0039-jwt-broadcast-cache-restart-and-ttl.md)'s
TTL check already runs, so this landed as part of that same change rather
than a separate pass over the same four files. Same justification as the
sixth counter above: this answers whether the two known, accepted
residual risks of this cache (restart wiping it; an entry outliving its
own JWT until read) are actually happening at runtime, not a
business-volume question.

## Update — 2026-07-20: an eighth dashboard, no new counters beyond ADR-0038's own

[ADR-0038](0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update
added three reconnection-observability metrics
(`rabbitmq_reconnect_attempts_total`, `rabbitmq_topology_setup_total{outcome}`,
`messaging_last_progress_timestamp_seconds`) across all six
RabbitMQ-connected services, plus a new dashboard,
`EasyDora / Resilience`, that also picks up the two existing counters
that never had a panel of their own (`jwt_cache_lookup_total`,
`inventory_idempotent_duplicate_detected_total` — the latter tracked as
a residual gap in this ADR's own 2026-07-15 Update, closed here). Five
panels, the same "small, focused dashboard" shape every dashboard this
ADR already lists uses — not a reason to revisit this ADR's own decision
against a general "track everything" mechanism, since every metric
behind this new dashboard already had its own individual justification
recorded at the ADR that added it.

## References

- [ADR-0024](0024-distributed-tracing-via-propagated-identifiers.md) —
  the logging/tracing pillar this ADR complements; its rejection of a
  full tracing backend (OpenTelemetry/Jaeger/Zipkin) is unchanged. See
  that ADR's own Update for the narrow correction to its bundled
  Prometheus/Grafana rejection.
- [docs/architecture/observability.md](../architecture/observability.md) —
  the correlation-id design this ADR's metrics sit alongside, not
  replace.
- [ADR-0018](0018-persistence-strategy.md),
  [ADR-0023](0023-notification-service-persistence-evolution-strategy.md),
  [ADR-0035](0035-reject-dto-code-generation-from-json-schema.md) — the
  precedent for a reviewed decision with explicit, measurable reopening
  criteria, followed here.
- [architectural-principles.md](../architecture/architectural-principles.md)
  — principle 2 ("a component must earn its place") directly decided
  both what was added (native-first) and what was deliberately left out
  (Alertmanager, Loki, `postgres_exporter`, per-event metric spam).
