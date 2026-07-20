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

## Update — 2026-07-17: steady-state reconnection after a broker restart

### Context

This ADR's original scope was boot-time only: what happens if a
dependency isn't ready *yet* when a service starts. A separate incident
exposed the neighboring, previously untested question: what happens if a
dependency that was already connected gets restarted *while the service
is running*. Generating test data against the `kind`-based Kubernetes
deployment ([ADR-0040](0040-kubernetes-deployment.md)) — which gives
RabbitMQ no PersistentVolume, so a pod restart there is a real, not
hypothetical, mid-run broker restart — left `inventory-service` silently
and permanently unable to consume any further messages after RabbitMQ
came back up. No crash, no error surfaced anywhere; the process just
stopped making progress.

Rather than accept the first hypothesis (a missing reconnect loop) or
jump straight to a fix, every service's Connection/Channel/Exchange/
Queue/Binding/Consumer lifecycle was read and classified first, Spring
AMQP's automatic-recovery and topology-recovery mechanisms were verified
by direct jar/bytecode inspection rather than assumed from documentation,
and two candidate blast-radius reductions — giving RabbitMQ a
PersistentVolume, and relying on a liveness probe alone with no
in-process reconnect code — were considered and explicitly rejected (the
first only protects queues surviving the restart, not any client's
ability to reconnect to them; the second isn't portable to Docker
Compose, which doesn't restart a container on a failed healthcheck the
way Kubernetes does, violating the "behaves the same in both
environments" principle this project has held since
[ADR-0040](0040-kubernetes-deployment.md)).

### The causal model: four layers, only one a real code gap

1. **RabbitMQ loses its queues on restart** (ADR-0040's accepted
   trade-off, not a bug — a `kind` node with no PersistentVolume for
   RabbitMQ was always going to behave this way).
2. **What each client does when it notices its own connection is dead**
   — this is where the actual gap lived, and it differed sharply by
   language:
   - **Java** (`amqp-client` 5.19.0, underlying all four Spring
     services): Automatic Connection Recovery and Topology Recovery are
     both enabled by default and were never disabled anywhere in this
     codebase — confirmed, not assumed, by reading the actual dependency
     jar. A real integration test (`RabbitMQReconnectionIT`-style, one
     per Spring service) that force-closes a live connection and asserts
     the consumer resumes on its own passed on the first attempt in all
     four services — this layer needed **verification, not code**.
   - **Go** (`amqp091-go`, `inventory-service`): the library exposes
     `NotifyClose` but nothing in this codebase was subscribed to it —
     zero reconnection, confirmed and real. This is the one genuine code
     gap in the whole investigation.
   - **Python** (`pika`, `notification-service`): `run_consumer`'s outer
     `while True` loop was already structurally correct (it already had
     a test proving it survives a *mocked* mid-run disconnect), but with
     no heartbeat configured, how quickly — or whether — `pika` notices
     a real broker-initiated close was untested and unverified.
3. **No service tied its liveness signal to the health of its own AMQP
   consumer loop** — the readiness/health endpoints every service
   already exposed only ever checked instantaneous HTTP/connectivity
   state, never "is my messaging loop still making progress." This
   compounds layers 1–2: even where reconnection *does* work
   automatically, nothing would have forced a restart if it hadn't.

**Verdict, explicit and by layer, as demanded before any implementation
work began**: layer 1 is an accepted trade-off, not a defect. Layer 2 was
a real, confirmed code gap in Go only — Java and Python needed
verification and a small hardening, not new reconnection logic. Layer 3
was a real, cross-cutting gap, closed as a defense-in-depth measure, not
a substitute for layer 2 actually working.

### What changed

1. **Progress Watchdog, all six services** (four Spring services, Go,
   Python) — a small class/struct (`recordProgress()`/`isStuck(threshold)`)
   fed by every reconnect attempt (successful or not), every message
   processed, and a periodic idle tick — deliberately *not* fed by
   whether the broker happens to be reachable right now, so an ordinary,
   already-tolerated broker outage can never by itself trigger a
   liveness-probe restart storm. Investigated first whether Spring AMQP
   already provided an equivalent signal before building anything custom:
   `ListenerContainerIdleEvent`/`ListenerContainerConsumerFailedEvent`
   exist and are the right building blocks, but no ready-made
   "stuck" indicator does (`RabbitHealthIndicator` is a pure connectivity
   check, confirmed via `javap`). Each service's `livenessProbe`/
   `/health/liveness` now reads this watchdog instead of instantaneous
   connection state, and documents explicitly that it detects process/
   loop stall, not external-dependency unavailability.
2. **`RabbitMQHealthIndicator`'s exchange-type bug fixed**
   (`orders-service`) — the `"direct"` vs. `"topic"` inconsistency this
   ADR's original Consequences section left as residual is now resolved,
   ahead of reusing this indicator's shape as part of the liveness work
   above.
3. **`inventory-service` (Go) gained a real reconnect supervisor** —
   `watchConnection()`, subscribed to `NotifyClose`, unbounded retry
   (mirroring this ADR's boot-time retry cadence but never giving up),
   swapping the shared `*amqp.Connection` behind a `sync.RWMutex` so
   every consumer and the Outbox publisher pick up the new connection
   without restarting themselves. Proven against a real broker (not a
   mock): a test force-closes the live connection mid-run and asserts
   both the direct consumer path and the Outbox-publish path resume
   automatically. `RabbitMQConsumer.Close()` was also given a `stop`
   channel so shutting a consumer down cleanly no longer leaves its
   reconnect/consume goroutines spinning forever against an
   intentionally-closed connection — found only because the resulting
   goroutine leak was noisy enough to fail CI once the reconnect
   supervisor started actually retrying on every `Close()`.
4. **`notification-service` (Python) gained an explicit `pika`
   heartbeat** (30s) so a broker-initiated close is detected instead of
   relying on an unconfigured default, plus a Progress Watchdog fed by
   every message processed, a periodic idle tick, and every
   `run_consumer` retry attempt. Proven against a real broker: a test
   force-closes the connection via RabbitMQ's management API (chosen
   over calling `.close()` from a second thread, since
   `pika.BlockingConnection` isn't thread-safe) and asserts the consumer
   resumes within 20 seconds.
5. **`docker-compose.yml`: every service gained `restart: on-failure`** —
   closes this ADR's own previously-residual gap (a bounded retry-then-exit
   strategy only narrows the failure window; something still has to
   restart the container once retries are exhausted, and nothing did).
   This also makes the liveness-probe-driven self-healing added in this
   Update behave equivalently in Compose, not just Kubernetes — the same
   "behaves the same in both environments" principle the two rejected
   alternatives above were held to.

### Consequences

**Positive**: the incident that triggered this investigation (a
`kind`-restarted RabbitMQ leaving `inventory-service` permanently unable
to consume) cannot recur — the one real code gap (Go's missing
reconnection) is closed and proven against a real broker, not a mock.
Java's and Python's tolerance of the same event is now verified rather
than assumed. Every service's liveness probe now reflects the thing that
actually matters (is the messaging loop making progress) instead of
instantaneous connectivity, in both Compose and Kubernetes.

**Negative / residual, not fixed here**: the four Spring services'
tolerance of this failure class still rests on `amqp-client`'s own
automatic recovery — confirmed empirically here, same caveat this ADR
already carries for HikariCP's `initialization-fail-timeout`, that a
future library upgrade changing that default wouldn't be caught by
anything this project owns. No PersistentVolume was added for RabbitMQ
in Kubernetes (deliberately rejected, see Context above) — a broker
restart there still means every queue is recreated empty; this Update
closes the *client-reconnection* half of that event, not the
*message-loss* half, which remains an accepted trade-off of
[ADR-0040](0040-kubernetes-deployment.md).

## Update — 2026-07-20: the previous Update's own validation had a gap, and a reconnection observability contract

### Context

An architectural-stabilization session revisited this ADR's 2026-07-17
Update with a specific, adversarial question: does the fix actually
survive the real Kubernetes scenario that motivated it, or only the
scenario the test happened to exercise? The 2026-07-17 Update's own
integration test forces the *TCP connection* closed while `order.exchange`
still exists on the broker the entire time — a real reconnection test, but
not the same event as a `kind` pod restart with no PersistentVolume
([ADR-0040](0040-kubernetes-deployment.md)), which loses the exchange
itself, not just the client's socket to it. The Update's own Consequences
section claimed the triggering incident "cannot recur" — that claim was
re-examined rather than taken at face value, and found to be incomplete.

### What was found: the real gap was topology, not the connection object

Reading `inventory-service/main.go` and
`internal/messaging/rabbitmq_consumer.go` directly (not re-deriving from
memory) confirmed: `SetupOrderExchange`/`SetupProductExchange` run exactly
once, at boot, before `watchConnection`'s supervisor loop starts.
`watchConnection` correctly redials and swaps the `*amqp.Connection` on
loss, but never redeclares either exchange — so every consumer's own
`setupQueue` (which redeclares its queue and re-binds it on every cycle,
correctly) fails forever with `NOT_FOUND - no exchange 'order.exchange'`
the moment the broker has actually lost it, exactly matching the original
incident's symptom (silent, permanent inability to consume, no crash).
Proven by forcing this condition against a real broker — deleting
`order.exchange` and closing the connection together, mirroring what a
`kind` pod restart without a PersistentVolume actually does — rather than
only closing the connection as the 2026-07-17 test did.

The four Spring services and `notification-service` do **not** share this
gap: Spring's autoconfigured `RabbitAdmin` redeclares every
`@Bean`-declared exchange/queue/binding on each new connection as part of
Topology Recovery (confirmed already in the 2026-07-17 Update, reconfirmed
here), and `notification-service`'s `run_consumer` loop already redeclares
its whole topology on every reconnect cycle. This was never a six-service
problem — it was one service's supervisor missing one call.

A second, related bug surfaced while fixing the first: `OutboxPublisher`'s
`channel.Publish` was fire-and-forget — a publish against a target that
does not exist still returns a nil client-side error, so
`MarkOutboxEventPublished` could mark an event published that the broker
never actually accepted, silently contradicting this file's own
at-least-once delivery comment. Fixed with publisher confirms
(`channel.Confirm` + `PublishWithDeferredConfirmWithContext`), which
surfaced a third bug during its own testing: a single permanently-bad
outbox row (targeting a nonexistent exchange) closes the whole RabbitMQ
*channel* on that protocol violation, and reusing that now-dead channel
for the rest of the same poll batch failed every other, otherwise-healthy
event in it too. Fixed by re-validating the channel before each event in
the batch, not just once per poll.

Separately, `orders-service`'s and `billing-service`'s
`JwtAuthenticationFilter` were found still returning an explicit 401 on an
unknown/expired cached token, diverging from `products-service`'s
already-fixed behavior ([ADR-0026](0026-frontend-thin-client.md)) of
letting the chain continue and leaving the decision to Spring Security's
own `authorizeHttpRequests()`. Standardized to match — the one full-chain
security test this exposed (`billing-service`'s
`PaymentControllerSecurityTest`) needed its expected status updated from
401 to 403, which is not a regression: both "no token" and "unknown
token" now correctly converge on the same 403 Spring Security already
returns for the former, instead of two different codes for what is
functionally the same outcome.

### What changed

1. **`inventory-service` (Go): `watchConnection` now redeclares both
   exchanges after every reconnect**, before considering the reconnect
   complete — a redeclaration failure is treated as a failed reconnect
   attempt (the loop keeps retrying) rather than handing consumers a
   connection with no usable topology behind it. Proven against a real
   broker with a new test that deletes `order.exchange` and closes the
   connection together, then confirms a fresh publish is actually
   consumed and reserved — not just that the connection object changed.
2. **`inventory-service`'s `OutboxPublisher` now uses publisher
   confirms**, and re-validates its channel before every event in a poll
   batch rather than once per poll — closing both the silent-mark-as-
   published gap and the one-bad-row-blocks-the-batch gap described
   above. Proven with a test seeding a row that targets a permanently
   nonexistent exchange and asserting it is never marked published.
3. **JWT filter consistency**: `orders-service` and `billing-service` no
   longer short-circuit with a 401 on a cache miss/expired token —
   matching `products-service`. Verified safe before applying: both
   services' `SecurityConfig` already has `anyRequest().authenticated()`
   as the same safety net `products-service` relies on, so removing the
   filter's own early return does not open any request path that was
   previously protected only by the filter.
4. **A reconnection observability contract, stated explicitly rather
   than left implicit**: every RabbitMQ consumer/producer in this system
   must (a) reconnect automatically, (b) redeclare its topology on every
   reconnect, and (c) expose liveness based on messaging-loop progress,
   never on instantaneous broker connectivity — in whatever form is
   idiomatic to its own language/framework, not as identical code across
   Go, Java, and Python. The investigation behind this Update evaluated
   the alternative (one shared implementation/pattern enforced across all
   three languages) and rejected it: Java's Automatic Connection Recovery
   and Topology Recovery are a mature, already-battle-tested framework
   mechanism, and reimplementing that by hand to match Go's supervisor
   shape would replace a proven mechanism with new, untested code for
   no behavioral gain — the same reasoning
   [ADR-0035](0035-reject-dto-code-generation-from-json-schema.md) already
   applied to rejecting a shared DTO-generation mechanism across the same
   three languages. The investigation realized before implementation is
   the one that matters here: **the investigation performed to date
   indicates five of the six consumers already satisfy these guarantees
   using native mechanisms from their respective frameworks. The
   `inventory-service` remains the one identified exception** — now
   closed by item 1 above.
5. **New metrics, same names/shapes across every service that has them**:
   `rabbitmq_reconnect_attempts_total`,
   `rabbitmq_topology_setup_total{outcome}` — added in
   `inventory-service` (Go, where the code path is new) and in all four
   Spring services (`auth-service`, `products-service`, `orders-service`,
   `billing-service`, via a `RabbitMqReconnectionMetrics`
   `ConnectionListener` registered on the autoconfigured
   `ConnectionFactory` — observing Automatic Connection Recovery's own
   events, not reimplementing them). `inventory-service` additionally
   gained `messaging_last_progress_timestamp_seconds`, exposing the
   `ProgressWatchdog`'s own internal clock as a gauge so
   `time() - this gauge` is queryable directly in Grafana. Deliberately
   not replicated to Python or expanded further in this same pass — see
   Deferred below.

### Deferred to a later phase of this same stabilization effort

Working in explicit phases rather than one large batch: the equivalent
`messaging_last_progress_timestamp_seconds`/reconnect-attempt metrics for
`notification-service` (Python), a small new Grafana dashboard
surfacing all of the above alongside the two metrics that already had no
panel (`jwt_cache_lookup_total`, `inventory_idempotent_duplicate_detected_total`),
and unrelated product-polish work (frontend signup/seller/cancel screens,
completing the Postman collection) are scoped but not yet implemented as
of this Update. None of the five services' actual reconnection behavior
depends on any of this remaining work — it is observability and product
finish, not correctness.

Two items evaluated and deliberately **not** implemented in this pass, on
the same "does this reduce real technical debt or just add complexity"
test applied to everything else: `inventory-service`'s idempotency-cache
TTL gap (a post-TTL duplicate delivery is genuinely indistinguishable from
a new one without adding a persistent per-order ledger table — new
persistence, not a bug fix, and the existing gap is already correctly
documented and tested) and any attempt to force topology-redeclaration
observability into a single cross-language abstraction (see item 4 above).

### Consequences (this Update)

**Positive**: the specific incident this ADR's 2026-07-17 Update believed
closed is now actually closed, proven against the exact failure mode
(topology loss, not just connection loss) that motivated the original
investigation — not the adjacent, easier-to-simulate one the previous
test happened to cover. The outbox publisher's at-least-once delivery
claim is now true under exchange loss, not just under normal operation.
JWT filter behavior is uniform across all three consuming Spring services.
A reconnection contract now exists in writing, phrased as a finding backed
by investigation rather than a permanent guarantee, so it can be revisited
honestly if a future service or library change invalidates it.

**Negative / residual, not fixed here**: the reconnection observability
metrics do not yet exist for `notification-service`, so Grafana cannot yet
show "time since last progress" for all six services uniformly — tracked
as the next phase, not forgotten. No dashboard yet surfaces any of the
metrics this Update added. The idempotency-cache TTL gap and the
decision not to build a cross-language reconnection abstraction are both
now explicit, reviewed trade-offs rather than open questions.

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
- [ADR-0035](0035-reject-dto-code-generation-from-json-schema.md) — the
  precedent this ADR's 2026-07-20 Update follows in rejecting a shared
  cross-language reconnection mechanism in favor of a behavioral contract.
- [ADR-0026](0026-frontend-thin-client.md) — the origin of the
  let-the-chain-continue JWT filter fix this ADR's 2026-07-20 Update
  extends from `products-service` to `orders-service`/`billing-service`.
- README Roadmap — the item this ADR closes.
