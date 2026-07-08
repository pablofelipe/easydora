# ADR-0012: CI Phase 2 — real-infrastructure integration tests via service containers

## Status

Accepted - 2026-07-06

## Context

CI Phase 1 (`.github/workflows/ci.yml`) runs `mvn test`/`go test` for all six
implemented services, but by design (ADR-0008) that excludes every test that
needs a live Postgres or RabbitMQ. Auditing what those tests actually are
turned up a gap: only one class in the whole repository touches real
infrastructure — `BillingServiceApplicationIT`, a placeholder
`@SpringBootTest` that only asserts the Spring context boots. It's bound to
`maven-failsafe-plugin` (`mvn verify`), but Phase 1 only ever runs `mvn
test`, so it had never executed in CI either, live or otherwise.

ADR-0008's 2026-07-06 update explains why: three other real `*IT` classes —
`VerifyEmailOutboxIT`/`VerifyEmailOutboxHappyPathIT` (auth-service) and
`JwtCreatedFanoutIT` (orders-service) — were deliberately replaced with
mock-based `*BehaviorTest` equivalents during the Kafka-to-RabbitMQ
migration (ADR-0007), specifically because no CI existed yet to run them
against real infrastructure. `maven-failsafe-plugin` was removed from
auth-service, orders-service and products-service at the same time, since
they had zero `*IT` classes left to run.

No other wiring/Outbox/contract path in the system (product.* events,
stock.reserve/release, the JWT broadcast fan-out itself) had ever had a
real-infrastructure test — only the mock-based unit coverage already
running in Phase 1.

## Decision

Add a Phase 2 to `.github/workflows/ci.yml`: a single new `integration` job,
gated on Phase 1 passing first (`needs: [gateway, inventory, spring-build]`)
so a broken unit test fails fast without spending time provisioning
containers. It uses one mixed java/go matrix (`include:` with an
`auth-service`/`orders-service`/`billing-service`/`inventory-service` ×
`language: java|go` axis, `fail-fast: false`) rather than one job per
language, specifically so the `postgres:15-alpine`/
`rabbitmq:3-management-alpine` service-container block is written once in
the YAML — a matrix fans out one job definition into several runs, each
still getting its own fresh pair of containers, so this loses nothing
compared to separate jobs. Steps for `setup-java`/`mvn verify` vs.
`setup-go`/`go test -tags=integration` are gated with `if: matrix.language
== 'java'|'go'`. Credentials are the same `admin`/`local_dev_placeholder`
every service's `application-dev.properties` (Spring) or `config.Load()`
(Go) already default to, so Phase 2 needs no repository secrets.

A composite action was considered and ruled out: composite actions run as
steps inside an existing job and cannot declare `services:` at all — that
key only exists at the job level, so a composite action could not have
provisioned the containers this phase depends on. A reusable workflow
(`workflow_call`) can define a job with `services:`, but its main benefit is
letting other workflows/repos call it — not applicable to a single
monorepo's own CI — and it would not have reduced the YAML any further
than the matrix already does, since each job invocation still declares its
own `services:` block either way.

`maven-failsafe-plugin` is re-added to auth-service's and orders-service's
`pom.xml` (removed by ADR-0008 when they had nothing for it to run) — this
doesn't reverse that ADR, it just gives Failsafe something real to do
again now that a runner exists (see the ADR-0008 update below).
products-service is intentionally excluded from the matrix: it still has
no `*IT` class and no `pom.xml` failsafe plugin, so there's nothing for
Phase 2 to run there (see Consequences).

Test inventory, by hop:
  - auth-service: restored `VerifyEmailOutboxIT` (no event published if the
    DB save fails after activation) and `VerifyEmailOutboxHappyPathIT`
    (Outbox row → real publish via the poller) verbatim from the commit
    that removed them, with one change — the hardcoded
    `new CachingConnectionFactory("localhost", 5672)` now reads
    `RABBITMQ_HOST`/`RABBITMQ_PORT` from the environment (falling back to
    the same literals), matching the already-externalized
    `RABBITMQ_PASSWORD`. This was needed to validate the restored tests
    locally against an isolated container without colliding with a
    developer's own docker-compose stack on the standard ports; in CI it's
    a no-op since the service container already listens on 5672.
  - orders-service: restored `JwtCreatedFanoutIT` verbatim (same env-var
    change), regression-testing the exact competing-consumer bug ADR-0001
    found (two consumers on one queue silently splitting deliveries).
    Added `StockOutcomeWiringIT`, new: publishes a real `stock.reserved`/
    `stock.insufficient` event onto `order.exchange` and asserts
    `InventoryEventsConsumer` drives the real order through its state
    machine to `INVENTORY_RESERVED`/`INVENTORY_FAILED` in real Postgres.
  - billing-service: added `OrderCreatedWiringIT`, new: publishes a real
    `order.created` event and asserts `OrderEventListener`/`PaymentService`
    persist a pending `Payment` in real Postgres.
    `BillingServiceApplicationIT` is unchanged (still just the context-load
    smoke test) and keeps running under the same `mvn verify`.
  - inventory-service (Go): new `//go:build integration` tests in
    `internal/messaging/`, none of which existed before in any form:
    `TestReserveStockCommand_WiringAndOutbox_{Sufficient,Insufficient}Stock`
    (drives `ConsumeReserveStockCommands` end to end, including the real
    Outbox publish of `stock.reserved`/`stock.insufficient` —
    `ReserveStockForOrder`'s atomicity is exactly what ADR-0007 introduced
    the Go-side Outbox to guarantee), `TestReleaseStockCommand_Wiring_...`,
    and `TestProduct{Created,Updated,Deleted}Event_Wiring_...` (drives the
    three `product.exchange` consumers against real Postgres).
- Contract tests (`*ContractTest.java`) are deliberately **not** added to
  Phase 2. They validate a DTO against a JSON Schema and touch no broker or
  database, so Surefire's default include pattern (`**/*Test.java`) already
  runs them in Phase 1 — adding them here would be pure duplication.

## Verification

Ran the exact new/restored test suites locally against a throwaway,
isolated `postgres:15-alpine` + `rabbitmq:3-management-alpine` pair (ports
15432/25672, distinct from a developer's standard docker-compose stack, so
as not to interfere with one running alongside this work) before trusting
the CI change:

| Service | Command | Result |
|---|---|---|
| auth-service | `mvn verify` | 2/2 IT tests pass (`VerifyEmailOutboxIT`, `VerifyEmailOutboxHappyPathIT`) |
| orders-service | `mvn verify` | 3/3 IT tests pass (`JwtCreatedFanoutIT`, `StockOutcomeWiringIT` × 2 cases) |
| billing-service | `mvn verify` | 2/2 IT tests pass (`BillingServiceApplicationIT`, `OrderCreatedWiringIT`) |
| inventory-service | `go test -tags=integration ./...` | 6/6 new wiring tests pass |

Re-ran plain `mvn test` (auth-service: 4, orders-service: 8, billing-service:
5) and `go test ./...` (unchanged) afterwards to confirm Phase 1's test
counts and pass/fail status are unaffected — the new `*IT`/`integration`-tagged
files are invisible to those commands, exactly as intended.

One real defect surfaced during this local run, not a flake: the three new
Go product.\*/stock.\* wiring tests initially failed because the test
published its message immediately after starting the consumer goroutine,
racing its internal queue declaration — a topic exchange drops a message
outright (it does not buffer it) if no queue is bound yet at publish time.
Fixed by having the test declare/bind the real production queue name
itself, synchronously, before starting the consumer goroutine — not by
adding a retry or a sleep, per this etapa's explicit "investigate before
adding retries" instruction.

## Consequences

**Positive**: these routing keys now have at least one test that runs a
real broker end to end in CI — noted below as (producer), (consumer), or
both, since most hops are only proven from one side:

- `auth.exchange`/`jwt.created` — fan-out topology (consumer, both of
  orders-service's queues): `JwtCreatedFanoutIT`.
- `order.exchange`/`stock.reserve`, `stock.release` — consumer
  (inventory-service): the new Go wiring tests. Not producer-tested (see
  below).
- `order.exchange`/`stock.reserved`, `stock.insufficient` — both sides:
  inventory-service's Go tests prove the real Outbox publish, and
  orders-service's `StockOutcomeWiringIT` proves the real consumption. This
  is the one hop with full two-sided coverage.
- `order.exchange`/`order.created` — consumer (billing-service):
  `OrderCreatedWiringIT`. Not producer-tested.
- `product.exchange`/`product.created`, `product.updated`,
  `product.deleted` — consumer (inventory-service): the new Go wiring
  tests. Not producer-tested.
- `user.verified` (auth.exchange) — Outbox correctness only
  (`VerifyEmailOutboxIT`/`HappyPathIT` prove auth-service publishes, or
  doesn't, correctly); not that any downstream consumer acts on it
  correctly.

Both Outbox implementations (auth-service, inventory-service) are verified
against a real Postgres + RabbitMQ pair, not just mocks.

**Not fixed here / known limitations** — genuine gaps, not judged
out-of-scope on architectural grounds, just not reached in this pass:
- No producer-side real-infrastructure test exists anywhere: nothing boots
  products-service, orders-service, or auth-service for real and asserts
  *they* put the right message on the wire. Every consumer-side test above
  hand-builds the incoming event itself, so a real drift in a producer's
  serialization would not be caught by Phase 2 — only by the JSON Schema
  contract tests (Phase 1, and only for `OrderCreatedEvent`/
  `UserRegisteredEvent` — ADR-0002 never extended JSON Schema coverage to
  `product.*`/`stock.*` events, so those have no schema to drift-check
  against either).
- `auth.exchange`/`user.registered` and `user.verified` consumption by
  products-service and orders-service (`UserEventConsumer`/
  `UserEventsConsumer`) has no real-infrastructure test at all, before or
  after this etapa.
- products-service has no real-infrastructure test of its own kind,
  anywhere — not scaled back from something bigger, never attempted. It's
  the only one of the four Spring services with neither an `*IT` class nor
  `maven-failsafe-plugin` in its `pom.xml` (see the ADR-0008 update above).
- `order.status-changed` still has no consumer, so there's nothing to
  wiring-test on the receiving end. (See ADR-0001 and the README Roadmap
  for the intended consumer and current status.)
- products-service's own `jwt.created` queue (separate from
  orders-service's two) isn't covered by `JwtCreatedFanoutIT`, which only
  asserts delivery to orders-service's `orders.jwt.created.queue`/
  `orders.jwt.created.profile.queue`.
- Total Phase 2 wall time adds roughly 30-45s of container startup plus
  15-30s of test time per matrix entry, all four running in parallel with
  each other (but after Phase 1 completes) — a future optimization could
  investigate whether `services:` health-check intervals can be tightened
  without losing reliability.

## References

- [ADR-0007](0007-remove-kafka-broker.md) — introduced the Go-side Outbox
  this ADR's inventory-service tests verify, and the RabbitMQ-only
  messaging model these tests all run against.
- [ADR-0008](0008-surefire-failsafe-test-split.md) — the Surefire/Failsafe
  split this ADR's CI job relies on, and the four original `*IT` classes
  three of which are restored here.
- [ADR-0001](0001-messaging-wiring-audit.md) — the competing-consumer
  incident `JwtCreatedFanoutIT` regression-tests.
