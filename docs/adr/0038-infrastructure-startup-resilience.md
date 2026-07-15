# ADR-0038: Infrastructure startup resilience — one root cause, not a missing cross-service pattern

## Status

Accepted - 2026-07-15

## Context

While validating ADR-0036's docker-compose stack against a freshly
reinstalled Docker, `orders-service` crashed on boot. RabbitMQ's Erlang
node was already answering its own healthcheck (`rabbitmq-diagnostics
ping`), but its AMQP listener on port 5672 wasn't yet accepting
connections — a well-known, generic Docker/RabbitMQ gotcha, not a
project-specific misconfiguration. `RabbitMQInitializer` (an
`ApplicationRunner` calling `AmqpAdmin.declareExchange/declareQueue`
directly) hit `AmqpConnectException`/`Connection refused`, logged it, and
rethrew — `ApplicationRunner` exceptions propagate straight out of
`SpringApplication.run()`, so the JVM exited with code 1. A manual restart
resolved it once RabbitMQ was up.

[ADR-0017](0017-notification-service-startup-resilience.md) had already
made an explicit claim about this exact class of problem, six days
earlier: *"The four Spring services don't need an equivalent fix:
`spring-boot-starter-amqp`'s `CachingConnectionFactory` already retries
broker connections and reconnects on disconnects by default, so this
class of bug simply doesn't reach them."* This incident directly
contradicted that claim — which made accepting it without verification
the wrong move for this investigation.

### Inventory

Every boot-time infrastructure touchpoint in the system was read and
classified before deciding anything:

- **`inventory-service` (Go)**: RabbitMQ connection (`amqp.Dial`) retries
  up to 10 times, fixed 3s delay, `log.Fatal` on exhaustion
  (`internal/messaging/rabbitmq_consumer.go`). Exchange/queue declaration
  after a successful connect has no retry of its own — safe, since a
  successful `Dial` already completed the full AMQP handshake. Postgres
  connectivity (`pkg/database/postgres.go`), until this ADR, had **zero**
  retry — a single `db.Ping()` — a real internal inconsistency within the
  very same `main.go` that retries RabbitMQ ten times.
- **`api-gateway` (Go)**: no infrastructure dependency at boot at all. Its
  circuit breaker (ADR-0006) is a runtime/outbound-call concern, unrelated
  to boot resilience.
- **Four Spring services, RabbitMQ**: all declare exchanges/queues/bindings
  as `@Bean`s in `RabbitMQConfig.java`, relying on Spring Boot's
  autoconfigured `RabbitAdmin`/listener-container machinery — no custom
  `RabbitAdmin`, no `spring.rabbitmq.template.retry.*`. `orders-service`
  alone also had `RabbitMQInitializer.java`, redundantly re-declaring
  `order.exchange` and `inventory.reserve.queue`/its binding (already
  covered by existing `@Bean`s) and `inventory.release.queue`/its binding
  (the only piece not covered by a `@Bean` — itself independently declared
  a third time by `inventory-service`'s own consumer-side Go code). A
  separate, on-demand `RabbitMQHealthIndicator` added a third, inconsistent
  declaration path (it declares the exchange as `"direct"` instead of
  `"topic"` in its fallback branch) — not boot-blocking (exceptions caught
  locally, surfaced only as `Health.down()`), left as found, not fixed
  here.
- **Four Spring services, Postgres**: Flyway/Hibernate's `DataSource` bean
  (HikariCP) is created during context refresh, well before any
  `ApplicationRunner` could intervene even if one existed. No retry
  configuration of any kind (`initialization-fail-timeout` at its default,
  1ms) was present anywhere in any of the four services.
- **`notification-service` (Python)**: already has the two fixes ADR-0017
  added — unbounded retry (fixed 5s delay, background thread) for
  RabbitMQ, bounded retry (10 attempts, fixed 3s delay, blocking) for
  Postgres schema init. Two independently-written loops, different bound
  philosophies, no shared helper — even within the one service that
  already had a dedicated ADR on this topic.
- **Docker layer**: `billing-service`, `orders-service`, and
  `products-service` (not `auth-service`) Dockerfiles carried a comment
  ("Instala wait-for-it para aguardar dependências") installing `bash` and
  `postgresql-client` — but no `wait-for-it.sh`/`entrypoint.sh` existed
  anywhere in the repository. Dead weight from an abandoned intention,
  never finished, inconsistent even among the services that had it.

### Empirical verification (the deciding step)

Rather than accept either ADR-0017's original claim or the incident's
apparent implication ("Spring services need retry too") at face value,
both were tested live:

**RabbitMQ:**

1. RabbitMQ stopped entirely. `auth-service` (zero `@RabbitListener`s, one
   `@Bean TopicExchange`) started and served HTTP traffic for the full
   45-second observation window with no crash and no propagated
   exception.
2. Same setup, `billing-service` (real `@RabbitListener`s consuming
   `jwt.created`, `order.created`, `payment.refund.requested`): also
   started and kept running the full window. The logs showed Spring's own
   `SimpleMessageListenerContainer` logging `"Broker not available; cannot
   force queue declarations during start"` and retrying every ~5 seconds,
   indefinitely, entirely on its own — never propagating the failure into
   the application context.

This confirms ADR-0017's claim was correct about the mechanism it named,
but incomplete: it didn't anticipate a service adding its *own* imperative
code (`RabbitMQInitializer`) that bypasses the declarative path's built-in
tolerance by explicitly rethrowing.

**Postgres — tested the same way, opposite result:**

3. With RabbitMQ left up and Postgres stopped, `auth-service` failed in
   ~2.4 seconds: `HikariPool-1 - Exception during pool initialization`
   (`checkFailFast`), propagating through `flywayInitializer`'s
   `entityManagerFactory` dependency and aborting `SpringApplication.run()`
   — exit code 1, no retry attempted at all. This directly disproved the
   working assumption (carried into this ADR's own first draft) that
   Postgres's stronger healthcheck (`pg_isready`, a real protocol-level
   check, unlike RabbitMQ's `rabbitmq-diagnostics ping`) meant no incident
   was plausible here — a healthcheck being stronger doesn't mean the
   *application framework's own connection code* tolerates a race,
   independent of whether that race is common in practice.
4. `spring.datasource.hikari.initialization-fail-timeout=30000` was then
   tested the same way: Postgres stopped, `auth-service` started
   (`HikariPool-1 - Starting...`), Postgres restarted ~6 seconds later,
   and the pool connected the moment it became reachable (`HikariPool-1 -
   Added connection`) with the app starting normally afterward. **No
   per-attempt log line appeared during the wait** — HikariCP's internal
   retry-until-timeout loop is silent by default, unlike
   `inventory-service`'s and `notification-service`'s explicit
   "attempt N/M" loops. This is a real, accepted trade-off (see Decision).

## Decision

**RabbitMQ needed no new cross-service retry pattern — it already had
one.** Spring Boot's autoconfigured `RabbitAdmin`/listener-container
machinery for the four Spring services, and `inventory-service`'s own
bounded-retry loop for Go, both already tolerate the exact race that
caused the incident. The fix there is to stop bypassing the mechanism
that already works, not to add a new one.

**Postgres, for the four Spring services, genuinely had no tolerance at
all** — confirmed by live reproduction, not assumed from healthcheck
strength. Two shapes were evaluated for the fix:

- **A**: a custom, explicit retry loop (mirroring
  `inventory-service`'s/`notification-service`'s "attempt N/M" shape),
  run in each service's `main()` before `SpringApplication.run()`. Rejected:
  it would need to re-resolve the JDBC URL/credentials outside Spring's
  own property system (profile-specific resolution — hardcoded `localhost`
  in dev, environment variables in prod) to run early enough to matter,
  duplicating logic that already lives in `application*.properties` and
  risking silent drift between the two if one is ever changed without the
  other.
- **B** (**adopted**): `spring.datasource.hikari.initialization-fail-timeout=30000`,
  a single property in each of the four services' base `application.properties`.
  HikariCP's own pool-initialization code already retries internally up to
  this bound before giving up — framework-native, zero new code, zero
  duplicated credential-resolution logic. The one real cost, confirmed
  empirically: no per-attempt log line during the wait, unlike option A's
  shape — accepted as a reasonable trade-off given the alternative's
  duplication risk, and because the *outcome* (tolerate ~30s of Postgres
  unavailability, fail loudly after) is what actually matters here, not
  attempt-by-attempt narration of a window that, per the evidence
  gathered, resolves in single-digit seconds in practice.

### What changed

1. **`RabbitMQInitializer.java` deleted.** Its one non-redundant
   declaration (`inventory.release.queue` + its `stock.release` binding)
   moved into `RabbitMQConfig.java` as a `@Bean`, in the exact same shape
   as the neighboring `inventory.reserve.queue`. `orders-service` now
   declares 100% of its RabbitMQ topology declaratively, like the other
   three Spring services always have — closing the incident by removing
   code, not by adding a retry loop to code that shouldn't have existed as
   written.
2. **`inventory-service`'s `InitPostgres` gained the same bounded retry
   its RabbitMQ connection already had** (10 attempts, fixed 3s delay, log
   per attempt, wrapped error on exhaustion) — mirroring
   `rabbitmq_consumer.go`'s own shape exactly. This closes the one
   internal asymmetry found (RabbitMQ tolerant, Postgres not) inside the
   same service, independent of the RabbitMQ investigation above.
3. **All four Spring services gained
   `spring.datasource.hikari.initialization-fail-timeout=30000`** in their
   base `application.properties` — closes the confirmed Postgres
   fail-fast-with-zero-retry gap described above, uniformly, with a single
   property per service rather than new code.
4. **The three Dockerfiles' dead "wait-for-it" comment/package install
   removed** (`billing-service`, `orders-service`, `products-service`) —
   no such tooling was ever implemented, and this ADR does not introduce a
   second layer (Docker-level wait script) alongside the retry mechanisms
   that already exist at the application level.
5. **`RabbitMQHealthIndicator`'s exchange-type inconsistency
   (`"direct"` vs. `"topic"`) is left as found, not fixed here** — it
   doesn't block boot and is out of this ADR's scope; noted for a future,
   separate cleanup.

### Adoption criterion for future infrastructure-startup decisions

> Boot-time retry is warranted whenever the *code that actually makes the
> connection* — not the container orchestrator's healthcheck — has no
> tolerance of its own for a dependency that answers slightly later than
> `depends_on: condition: service_healthy` guarantees. A stronger
> healthcheck (Postgres's `pg_isready`, a real protocol-level check, vs.
> RabbitMQ's `rabbitmq-diagnostics ping`, which only confirms the Erlang
> node answers) lowers the *probability* of the race, but does not by
> itself prove the application framework tolerates it — that has to be
> verified against the actual connecting code, empirically, the way both
> dependencies were tested here, not assumed from the healthcheck's own
> strength. It must never extend to steady-state publish/consume
> operations: those are already owned by the Outbox Pattern
> ([ADR-0037](0037-consolidated-outbox-pattern-specification.md), for a
> publish that never left the process) and by
> [ADR-0019](0019-message-consumption-resilience.md)'s consumer-side
> retry/DLQ (for a message that was delivered but failed processing) —
> conflating either with boot-time retry would blur separations this
> project already deliberately maintains.

### Observability

No new metric for either Spring RabbitMQ or Spring Postgres — in both
cases the retrying is done by framework code (`SimpleMessageListenerContainer`,
HikariCP's pool initialization) this project doesn't own or instrument,
and neither exposes a clean extension point for a custom counter without
adding the exact kind of bypass code this ADR just removed. `inventory-service`'s
new Postgres retry (the one case that *is* this project's own code)
gained a single counter, `infra_startup_retry_attempts_total{dependency}`
(`pkg/database/postgres.go`), incremented once per failed attempt —
following the same convention as ADR-0036's five business counters. No
Timer/Histogram: a boot-time retry sequence happens at most once per
process lifetime, not a recurring distribution worth characterizing. No
dedicated "definitive failure" metric either — a process that exits is
already visible via container orchestration (`docker ps`, a crash-looping
container), and would be dead before Prometheus could scrape it anyway.

## Consequences

**Positive**: the exact incident (`orders-service` crashing on a
RabbitMQ-not-ready boot race) cannot recur, because the code path that
caused it no longer exists — closed by deletion, not by a new retry loop.
A second, previously undiscovered and unrelated gap (all four Spring
services crashing immediately, with zero retry, if Postgres answers the
same way RabbitMQ did) was found and closed by a one-line property change
per service, before it ever caused its own incident. `inventory-service`
no longer has an internal asymmetry between how it treats its two
infrastructure dependencies. Three Dockerfiles are smaller and no longer
carry dead, misleading tooling references.

**Negative / residual, not fixed here**:
- `RabbitMQHealthIndicator`'s exchange-type inconsistency (`"direct"` vs.
  `"topic"`) remains, found but out of scope for this ADR.
- The four Spring services' tolerance of both a RabbitMQ and a Postgres
  startup race now rests on framework behavior
  (`SimpleMessageListenerContainer`'s internal retry; HikariCP's
  `initialization-fail-timeout`), confirmed empirically here but not owned
  or tested by this project's own code — a future Spring Boot/HikariCP
  upgrade changing either default would not be caught by anything in this
  repository today.
- The Postgres fix (`initialization-fail-timeout`) produces no per-attempt
  log line during the retry window, unlike every other retry loop in this
  system (`inventory-service`'s two Go loops, `notification-service`'s two
  Python loops) — a deliberate, accepted trade-off (see Decision), not an
  oversight, but a real asymmetry in observability across the system's
  various startup-retry mechanisms.
- `notification-service`'s unbounded RabbitMQ retry (ADR-0017) is
  internally inconsistent with its own bounded Postgres retry; left
  unchanged, as a minor, non-urgent follow-up, not blocking this ADR.
- No restart policy (`restart: on-failure`/`unless-stopped`) is configured
  for any service in `docker-compose.yml` — a bounded retry-then-exit
  strategy (`inventory-service`'s pattern, now applied twice, and
  HikariCP's own) only narrows the failure window; it doesn't eliminate
  the need for a human (or an orchestrator) to restart a container that
  exhausts its retries. A compose-level restart policy is a cheap,
  complementary fix, out of this ADR's scope.

## References

- [ADR-0017](0017-notification-service-startup-resilience.md) — the
  earlier, partially-incorrect claim this ADR corrects; see its own
  2026-07-15 Update.
- [ADR-0007](0007-remove-kafka-broker.md) — `inventory-service`'s
  RabbitMQ retry loop, the in-repo shape this ADR extends to
  `InitPostgres` and confirms (rather than blindly copies, given its own
  internal Postgres/RabbitMQ asymmetry) as worth reusing.
- [ADR-0019](0019-message-consumption-resilience.md) — the different,
  complementary failure class (delivered-but-failed-to-process) its
  retry/DLQ protects against; cited to distinguish it from boot-time
  retry, not reused as a mechanism here.
- [ADR-0037](0037-consolidated-outbox-pattern-specification.md) — the
  other failure class (message never left the process after startup) this
  ADR's adoption criterion explicitly excludes from boot-time retry's
  scope.
- [ADR-0036](0036-metrics-via-prometheus-grafana.md) — the business-metric
  convention `infra_startup_retry_attempts_total` follows.
- README Roadmap — the item this ADR closes.
