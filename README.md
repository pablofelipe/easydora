# EasyDora

[![CI](https://github.com/pablofelipe/easydora/actions/workflows/ci.yml/badge.svg)](https://github.com/pablofelipe/easydora/actions/workflows/ci.yml)

## Overview

EasyDora is a microservices-based e-commerce platform that demonstrates how
to build an end-to-end distributed business flow using event-driven
architecture, RabbitMQ, the Outbox Pattern, contract testing, and continuous
integration.

Each service is implemented in the language/stack suited to its workload,
not for convenience — Go for performance-sensitive gateway/inventory paths,
Spring Boot for domain-rich business logic, FastAPI for async notification
processing.

**Status: in active development.** Seven of eight services are implemented
and building (api-gateway, auth, products, inventory, orders, billing,
notification). Four of them (auth-service, orders-service, products-service,
billing-service) have contract tests validating their event/message DTOs
against JSON Schemas shared in `/schemas/json/`; inventory-service has eight
unit tests, four of which cover its stock-reservation idempotency logic
specifically, not the service broadly. notification-service consumes both
`order.created` and `order.status-changed` via RabbitMQ, enriches the
former with a real HTTP call to auth-service, and persists an observable
notification per event — never overwriting a previous one — queryable via
its own read-only `GET /notifications/{orderId}` (no real email/SMS
provider yet — see [ADR-0014](docs/adr/0014-notification-service.md)).
Frontend is the only remaining empty scaffold. See
[Service Status](#service-status) for the current breakdown.

## What This Project Demonstrates

- **Event-driven architecture on RabbitMQ** — every cross-service
  interaction (user lifecycle, product catalog, order/stock/payment/
  notification) flows through topic exchanges instead of synchronous
  calls. See [Architecture](#architecture) and
  [ADR-0007](docs/adr/0007-remove-kafka-broker.md).
- **Outbox Pattern** — auth-service and inventory-service write their
  outbound event in the same database transaction as the state change that
  triggers it, so an event is never silently lost on a crash between
  commit and publish. See
  [ADR-0003](docs/adr/0003-outbox-pattern-auth-service.md) and
  [ADR-0007](docs/adr/0007-remove-kafka-broker.md).
- **Contract testing** — event/message DTOs are validated against
  versioned JSON Schemas so producer/consumer drift is caught
  automatically instead of discovered in production. See
  [ADR-0002](docs/adr/0002-json-schema-contract-testing.md).
- **Cross-service JWT broadcast authentication** — auth-service issues the
  token once; every other service builds its own in-memory cache from a
  broadcast event instead of re-verifying signatures locally. See the
  Overview's [Communication](docs/architecture/overview.md#communication)
  section.
- **Circuit breaker at the API Gateway** — outbound proxy calls fail fast
  instead of piling up when a downstream service is down. See
  [ADR-0006](docs/adr/0006-gateway-circuit-breaker.md) and
  [ADR-0009](docs/adr/0009-billing-circuit-breaker.md).
- **Three-phase CI**: unit tests with no infrastructure (Phase 1),
  integration tests against real Postgres/RabbitMQ service containers
  (Phase 2), and cross-service end-to-end tests driven through public HTTP
  APIs against real running processes (Phase 3). See
  [ADR-0012](docs/adr/0012-ci-phase-2-real-infrastructure.md) and
  [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md).
- **A polyglot stack matched to workload**, not convenience — Go, Spring
  Boot, and FastAPI each doing the job they're best suited for. See
  [Design notes](#design-notes).
- **A fully reproducible, real-command business flow** — signup through
  order, stock reservation, payment, and notification — documented and
  validated against real containers. See the
  [walkthrough](docs/walkthrough.md) and
  [sequence diagram](docs/sequence-diagram.md).

## Quick Start

**Prerequisites**: Docker Desktop (Windows/Mac) or Docker Engine (Linux),
Docker Compose, Git.

```bash
docker --version
docker-compose --version
```

```bash
git clone <repo-url>
cd easydora

# Start all implemented services
docker-compose up -d

# Check status
docker-compose ps
```

The seven implemented services (API Gateway, Auth, Products, Inventory,
Orders, Billing, Notification) come up and respond on their ports (see
[Service Status](#service-status) for the full port list). The frontend is
the only service still commented out in `docker-compose.yml` — no
Dockerfile or source exists for it yet.

For a full, reproducible business-flow walkthrough (signup → product →
order → stock reservation → payment → notification), driven entirely by
`curl` against each service's public API with real request/response
examples, see [docs/walkthrough.md](docs/walkthrough.md). For the same flow
as a Mermaid sequence diagram, see
[docs/sequence-diagram.md](docs/sequence-diagram.md).

### Troubleshooting (Windows)

If `docker-compose` fails to connect:

1. Open Docker Desktop and wait for "Docker Desktop is running".
2. Verify with `docker version`.
3. If `docker-compose` doesn't work, try `docker compose` (no hyphen).
4. If issues persist, restart Docker Desktop via its system tray icon.

## Architecture

For the full breakdown — bounded contexts, business flows, communication,
persistence, and the exchange/event table — see the
[Architecture Overview](docs/architecture/overview.md). The diagram below
is just the component topology at a glance:

```
                    ┌─────────────┐
                    │ API Gateway │  Go + Gin
                    └──────┬──────┘
                           │
        ┌──────────┬───────┴───────┬──────────┐
        │           │               │          │
   ┌────▼───┐  ┌────▼─────┐   ┌────▼────┐ ┌────▼─────┐
   │  Auth   │  │ Products │   │ Orders  │ │Inventory │
   │ Spring  │  │ Spring + │   │Spring + │ │  Go +    │
   │  Boot   │  │ Postgres │   │RabbitMQ │ │ Postgres │
   └─────────┘  └──────────┘   └────┬────┘ └──────────┘
                                     │
                          ┌──────────┴──────────┐
                          │                      │
                    ┌─────▼─────┐         ┌──────▼──────┐
                    │  Billing  │         │Notification │
                    │  Spring   │         │  FastAPI +  │
                    │  Boot     │         │  RabbitMQ   │
                    └───────────┘         └─────────────┘

Frontend (SvelteKit, planned) consumes the API Gateway.
```

## Documentation

- [Architecture Overview](docs/architecture/overview.md) — the map: bounded
  contexts, business flows, communication, persistence, and the
  exchange/event table.
- [Architecture Decision Records](#architecture-decision-records) — 20
  ADRs, one per architectural decision made along the way, in chronological
  order.
- [Postman collection](postman/) — the same main flow as an importable,
  runnable collection with automatic ID/token capture, complementing the
  walkthrough.
- [Architectural Principles](docs/architecture/architectural-principles.md)
  — the recurring principles behind those decisions, extracted from the
  ADRs rather than declared up front.
- [End-to-end walkthrough](docs/walkthrough.md) — the full business flow
  driven entirely by `curl`, with real requests/responses from an actual
  run.
- [Sequence diagram](docs/sequence-diagram.md) — the same flow as a
  Mermaid diagram.
- [Design notes](#design-notes) — why each service uses the stack it uses.
- [Service Status](#service-status) — per-service ports, stack, and test
  coverage.

## Service Status

| Service | Stack | Port | Status |
|---|---|---|---|
| API Gateway | Go + Gin | 8080 | Implemented (tests: 5 test functions covering the circuit breaker, see ADR-0006/ADR-0009) |
| Auth | Spring Boot + JWT | 8081 | Implemented (tests: 4/4 — `mvn test` only, no `*IT`) |
| Products | Spring Boot + PostgreSQL | 8082 | Implemented (tests: 4/4 — `mvn test` only, no `*IT`) |
| Inventory | Go + PostgreSQL | 8083 | Implemented (tests: 8/8 passing) |
| Orders | Spring Boot + RabbitMQ | 8084 | Implemented (tests: 8/8 — `mvn test` only, no `*IT`) |
| Billing | Spring Boot | 8085 | Implemented (tests: 6/6 — `mvn test` 5/5 unit (contract test + `HealthControllerTest` + `PaymentServiceOrderCreatedBehaviorTest`), `mvn verify` adds 1 `*IT` real-context smoke test against Postgres/RabbitMQ) |
| Notification | FastAPI + RabbitMQ | 8086 | Implemented (tests: 5 domain unit tests + 6 real-infra integration tests against Postgres/RabbitMQ/auth-service — see [ADR-0014](docs/adr/0014-notification-service.md)) |
| Frontend | SvelteKit | 3000 | Planned (empty scaffold) |

"Implemented" means the service builds and runs; it does not imply full test coverage. Six services have real test source so far (see the table above); the paragraph below covers billing-service's history specifically, since it's where the original baseline audit's test-fixing work happened. billing-service has `BillingServiceApplicationIT` (a Spring Initializr default, renamed from `BillingServiceApplicationTests` under ADR-0008's Surefire/Failsafe split), and its `mvn verify` now passes against a real Postgres/RabbitMQ. Getting there required fixing three independent bugs uncovered by actually running the test: a package mismatch between the test class and `@SpringBootApplication`; a missing `rabbitmq.queue.order-created` property; and a Kafka consumer `TYPE_MAPPINGS` entry pointing at `com.easydora.orders.event.OrderCreatedEvent` (another service's class) instead of billing-service's own `OrderCreatedEvent`. That last one is a concrete instance of this project's lack of contract testing between services: each service hand-duplicates its own copy of shared event DTOs, and nothing catches it when a copy silently references the wrong service's class or a diverged field/type.

Event contracts validated via JSON Schema — see [ADR-0002](docs/adr/0002-json-schema-contract-testing.md) for the two schemas migrated, the drifts fixed, and the known `price` type gap that schema validation can't catch. Messaging layer audited for wiring bugs (routing keys, field names, a competing-consumer incident) — see [ADR-0001](docs/adr/0001-messaging-wiring-audit.md) for all six findings and what's still open.

inventory-service has four unit tests covering `ReserveStock`'s idempotency, and two known duplication scenarios around it. A redelivered `ReserveStockCommand` for the same order (the retry scenario that follows a consumer crash or dropped connection between the Postgres commit and the Ack) previously reserved stock a second time; the service now caches the outcome per `OrderID` for 10 minutes and returns it on retry instead of reserving again, with a background sweep so the cache doesn't grow unbounded with order volume. That 10-minute window covers short-lived retries — an immediate RabbitMQ requeue, or the consumer's own reconnect loop, which backs off for at most 30s — with margin for a full container restart during a redeploy. Two truly concurrent redeliveries of the same order (not sequential retries — actual simultaneous calls) used to both slip past the cache check before either had written its result back, double-reserving; that race is now closed by serializing `ReserveStock` per `OrderID` (a fixed-size striped mutex, not a second unbounded map), verified by a 50-goroutine concurrency test and by `go test -race` (run in a Linux container, since this environment's native Windows Go toolchain has no cgo/gcc for the race detector) reporting no data races. What remains open, deliberately not fixed here: a redelivery that arrives *after* the 10-minute cache entry has expired — e.g. a message reprocessed late from a dead-letter queue — is indistinguishable from a first delivery and will still duplicate the reservation (verified by a test, not assumed). The cache is also in-memory and per-process, so a service restart clears it outright. The Outbox Pattern added as part of ADR-0007 closes a different gap (the reservation outcome event is never lost once a reservation commits); it doesn't make message redelivery itself idempotent, so this residual gap remains open — closing it for good would need message-level deduplication (e.g. a processed-message-id table), not a bigger TTL. CI (Phase 1: build/vet/unit-test only, no service containers) is configured — see the badge above and `.github/workflows/ci.yml`; Phase 2 (contract/wiring tests against real brokers) is future work.

Infrastructure: RabbitMQ Management (15672), PostgreSQL (5432).

## Design notes

The stack split is deliberate:

- **Go** (Gateway, Inventory) — performance-sensitive, high-throughput
  paths.
- **Spring Boot** (Auth, Products, Orders, Billing) — domain-rich business
  logic where Java's ecosystem (validation, transactions, ORM) pays off.
- **FastAPI** (Notification) — async I/O-bound processing (currently a
  synchronous RabbitMQ consumer + HTTP client; see
  [ADR-0014](docs/adr/0014-notification-service.md) for why sync was chosen
  over `aio-pika`/asyncpg at this size).
- **SvelteKit** (Frontend) — lightweight reactive UI.

## Architecture Decision Records

| ADR | Title | Status | Summary |
|---|---|---|---|
| [0001](docs/adr/0001-messaging-wiring-audit.md) | Messaging wiring audit | Accepted, updated 2026-07-08 | Five routing/field-name/listener bugs fixed (RabbitMQ + Kafka), one JWT-queue message-loss incident dated back to the project's first commit, one dead payment-event code path removed; `OrderStatusChangedEvent`'s designated consumer (notification-service) is now implemented — see the Roadmap. |
| [0002](docs/adr/0002-json-schema-contract-testing.md) | JSON Schema contract testing | Accepted | JSON Schema (draft 2020-12) adopted for event contracts, versioned in `/schemas/json/`; two catalogued DTO drifts fixed; `price` type drift (BigDecimal vs float64) documented as a known gap schema validation can't catch. |
| [0003](docs/adr/0003-outbox-pattern-auth-service.md) | Outbox pattern for auth-service | Accepted | `verifyEmail`'s publish-before-save ordering fixed with a polled `outbox_events` table; `inventory-service`'s equivalent risk (Go) closed the same way as part of ADR-0007's RabbitMQ migration; a Flyway/Hibernate schema-duplication bug found along the way, resolved in ADR-0004. |
| [0004](docs/adr/0004-auth-service-schema-authority-fix.md) | auth-service schema authority fix | Accepted, extended by ADR-0011 | Fixes the schema duplication found in ADR-0003: `V1`/`V2` created tables in `public` while Hibernate's `ddl-auto=update` silently created the real, actually-used copies in `auth_schema`. A `V3` migration recreates both tables in `auth_schema` matching Hibernate's live schema exactly, and `ddl-auto` is locked to `validate`. Left checking the other three services as explicit future work — see ADR-0011. |
| [0005](docs/adr/0005-secret-rotation.md) | Secret rotation and removal of hardcoded credentials | Accepted | Three credentials hardcoded in `docker-compose.yml` since the project's first commit (public repo) rotated for real against the live Postgres/RabbitMQ, replaced with `${VAR}`/`.env`; orphaned JWT config removed from three services that never consumed it. History not rewritten — old values are treated as permanently compromised. |
| [0006](docs/adr/0006-gateway-circuit-breaker.md) | Circuit breaker in the API Gateway | Accepted | `sony/gobreaker` added, one breaker per service (`auth`, `products`, `inventory`, `orders`; billing excluded, see ADR-0009), 5 consecutive failures to open / 30s cooldown. Verified against real containers: stopping inventory-service made it fail fast while the other three kept responding normally. |
| [0007](docs/adr/0007-remove-kafka-broker.md) | Remove Kafka broker (migrate to RabbitMQ) | Accepted, implemented | Kafka removed from every service; product.\*/stock.\*/order.\* events all move over RabbitMQ topic exchanges; inventory-service's stock reservation outcome is now written through an Outbox table (see ADR-0003) instead of a direct post-commit publish. |
| [0008](docs/adr/0008-surefire-failsafe-test-split.md) | Separate unit and integration tests via Surefire/Failsafe | Accepted, partially superseded | Originally: four test classes touching real Postgres/RabbitMQ renamed to the `*IT` suffix and moved to `maven-failsafe-plugin` across all four Spring services. Since updated (see the ADR's 2026-07-06 addendum): Etapa 5 of ADR-0007's migration replaced three of those four `*IT` classes with broker-agnostic behavior tests; only billing-service's `BillingServiceApplicationIT` remains, and `maven-failsafe-plugin` was removed from the other three services' `pom.xml`. |
| [0009](docs/adr/0009-billing-circuit-breaker.md) | Extend the API Gateway circuit breaker to billing-service | Accepted | Same structure as ADR-0006 (`sony/gobreaker`, 5 failures / 30s cooldown), applied to the one remaining entry left on the plain proxy. Closes ADR-0006's open Roadmap item. |
| [0010](docs/adr/0010-uniform-service-healthchecks.md) | Uniform health checks across all six services | Accepted | Every Docker `HEALTHCHECK` now targets each service's own unauthenticated `/health` endpoint instead of `/actuator/health`; billing-service gained a `HealthController`/`SecurityConfig`; orders-service's broken `docker-compose.yml` override removed; auth-service/inventory-service/api-gateway gained a `HEALTHCHECK` they never had. All six services verified `healthy` simultaneously for the first time. |
| [0011](docs/adr/0011-flyway-schema-authority-all-services.md) | Flyway as the single schema authority in every Spring Boot service | Accepted | Closes the gap ADR-0004 explicitly left open. `flyway-core` was silently missing from products-service and billing-service's `pom.xml`, making their Flyway config dead and letting `ddl-auto=update` author their entire live schema (with visible drift from what the migrations specify). billing-service gets its first real migration; all four services now baseline correctly and run with `ddl-auto=validate` everywhere. |
| [0012](docs/adr/0012-ci-phase-2-real-infrastructure.md) | CI Phase 2 — real-infrastructure integration tests via service containers | Accepted | New `integration` job in CI, matrix of auth-service/orders-service/billing-service/inventory-service, each against its own fresh Postgres/RabbitMQ service-container pair. Restores three previously-removed `*IT` classes and adds new ones; every hop is tested from at most one side (producer or consumer), never both, and never across a real process boundary — see ADR-0013. |
| [0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md) | CI Phase 3 — cross-service end-to-end tests via real running processes | Accepted | Two named jobs that start multiple real services as actual processes against one shared Postgres/RabbitMQ pair, driving flows through public HTTP APIs only: `catalog-onboarding` (auth/products/inventory) and `order-lifecycle` (auth/orders/inventory/billing). Surfaced and fixed a real bug where billing-service's Basic Auth never actually worked (403 regardless of credentials). |
| [0014](docs/adr/0014-notification-service.md) | Notification Service — first Python/FastAPI service | Accepted | Consumes `order.created` via a new RabbitMQ queue, enriches it via a real HTTP call to a new minimal auth-service endpoint (`GET /users/{id}/notification-profile`), and persists an observable notification in a new `notification_schema` — no real email/SMS provider, one `FakeNotificationSender` implementation. Found (not fixed, currently latent) the same missing-`.httpBasic()` defect class ADR-0013 fixed in billing-service, this time in auth-service. |
| [0015](docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md) | billing-service joins the JWT broadcast pattern; auth-service's latent `.httpBasic()` gap fixed | Accepted, updated 2026-07-07 | auth-service originally got the same one-line `.httpBasic()` fix ADR-0013 applied to billing-service; later simplified further to `denyAll()` once it was clear no endpoint depended on any auth mechanism (see the ADR's Update). billing-service was the only service protected solely by Spring Boot's default Basic Auth; it now authenticates via the same Bearer JWT broadcast cache already used by products-service and orders-service (auth-service is the producer of that broadcast, not a consumer of it). New `JwtConsumerBehaviorTest`/`PaymentControllerSecurityTest`/`BillingJwtCreatedWiringIT` give this mechanism real regression coverage for the first time. |
| [0016](docs/adr/0016-shared-spring-parent-pom.md) | Shared Maven parent POM for the four Spring Boot services | Accepted | New root `pom.xml` (inheritance only, no reactor) standardizes all four services on Spring Boot 3.2.12 (previously split 3.2.0/3.2.12) and centralizes every dependency/plugin that was identical across all four by hand. Required changing Docker's build context to the repository root for all four services so the parent resolves inside each build. |
| [0017](docs/adr/0017-notification-service-startup-resilience.md) | notification-service survives a slow/restarting RabbitMQ and Postgres | Accepted | Found while validating the end-to-end walkthrough against a real, freshly-started stack: the RabbitMQ consumer thread made exactly one connection attempt and died silently on a real startup race, leaving the container "healthy" but permanently unable to process any event. Both the RabbitMQ consumer and the Postgres schema-init step now retry instead of giving up. |
| [0018](docs/adr/0018-persistence-strategy.md) | Persistence strategy — shared PostgreSQL instance, schema-per-service ownership | Accepted | Formally registers a decision that was implicit since the project's first commit: one Postgres instance, one schema per service, ownership enforced by convention rather than by database ACLs. Argues the architectural boundary is data ownership, not the physical instance, and that every property this project demonstrates already holds without database-per-service. |
| [0019](docs/adr/0019-message-consumption-resilience.md) | Uniform message consumption resilience (limited retry, exponential backoff, dead-lettering) | Accepted | `products-service`, `orders-service`, and `billing-service` now share the same native Spring Boot listener retry policy (3 attempts, exponential backoff) and dead-letter exchange/queue per service; eight listener methods that previously swallowed exceptions internally were fixed to propagate them, so container-level retry/DLQ actually applies to business-logic failures, not just malformed messages. `auth-service` has no consumer at all, so is out of scope; `notification-service` (Python) keeps its separate, unrelated gap. |
| [0020](docs/adr/0020-notification-domain-completion.md) | Complete the notification domain — consume `order.status-changed`, add a read-only API | Accepted | `notification-service` now consumes `order.status-changed` in addition to `order.created`, reusing the prior notification's enriched user info instead of a second synchronous call (the event carries no `userId`). Adds a read-only `GET /notifications/{orderId}`, closing the walkthrough's one direct-Postgres-access step. Found, not fixed: `billing-service` never publishes a payment outcome event, so `order.status-changed` is never emitted for a payment transition. |

## Roadmap

<details>
<summary>Click to expand — closed items marked <code>[x]</code>, open items <code>[ ]</code></summary>

- [x] Notification service (FastAPI + RabbitMQ consumer): consumes
      `order.created`, enriches via a real HTTP call to a new minimal
      auth-service endpoint, persists an observable notification in a new
      `notification_schema` — see
      [ADR-0014](docs/adr/0014-notification-service.md).
- [x] `order.status-changed` (`orders-service`, published on
      `order.exchange`, see [ADR-0001](docs/adr/0001-messaging-wiring-audit.md))
      is now consumed by `notification-service`, the same way it already
      consumes `order.created`. It carries no `userId` of its own, so
      notification-service reuses the email/name already captured by that
      order's `order.created` notification instead of a second
      auth-service call. Every event produces its own new notification
      row — none are ever overwritten. See
      [ADR-0020](docs/adr/0020-notification-domain-completion.md).
- [ ] `billing-service` still doesn't publish payment outcome events,
      therefore the payment lifecycle never reaches its final business
      state (`APPROVED`/`FAILED`) and `order.status-changed` is never
      emitted for payment transitions. `orders-service`'s own
      `handlePaymentReceived`/`handlePaymentFailed` (which would publish
      exactly that) have no caller anywhere in the codebase today — found
      while completing the notification domain, see
      [ADR-0020](docs/adr/0020-notification-domain-completion.md).
- [ ] SvelteKit frontend
- [x] End-to-end integration tests across the implemented services — see CI
      Phase 3 below (`catalog-onboarding`, `order-lifecycle`, and
      `notification-flow` groups).
- [x] CI pipeline, Phase 1 (`.github/workflows/ci.yml`): parallel build/vet/unit-test jobs for all seven services, no service containers
- [x] CI pipeline, Phase 2 (`.github/workflows/ci.yml`): wiring and Outbox
      integration tests against real Postgres/RabbitMQ service containers —
      see [ADR-0012](docs/adr/0012-ci-phase-2-real-infrastructure.md).
- [x] CI pipeline, Phase 3 (`.github/workflows/ci.yml`): cross-service
      end-to-end tests that start multiple real services as actual running
      processes against one shared Postgres/RabbitMQ pair and drive each
      flow through public HTTP APIs only — `catalog-onboarding`
      (auth-service, products-service, inventory-service),
      `order-lifecycle` (auth-service, orders-service, inventory-service,
      billing-service) — see
      [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md) — and
      `notification-flow` (auth-service, notification-service) — see
      [ADR-0014](docs/adr/0014-notification-service.md).
- [x] inventory-service (Go): Outbox Pattern implemented for stock
      reservation — see [ADR-0007](docs/adr/0007-remove-kafka-broker.md).
      `ReserveStockForOrder` writes the `stock.reserved`/`stock.insufficient`
      event to `inventory_schema.outbox_events` in the same Postgres
      transaction as the reservation itself, and a poller publishes it to
      RabbitMQ — closing the "event lost if the process crashes right
      after commit" gap. This doesn't make message redelivery itself
      idempotent (a separate, still-open concern — see below); it only
      guarantees the reservation outcome is never silently lost once
      committed.
- [x] products-service, orders-service, billing-service: limited retry
      (3 attempts, exponential backoff) plus a dead-letter exchange/queue
      per service, configured natively via Spring Boot's
      `SimpleRabbitListenerContainerFactoryConfigurer` and a
      `RepublishMessageRecoverer` — no custom retry code. Eight listener
      methods across four consumer classes that used to swallow business
      exceptions internally (so the container never saw a failure to
      retry) were fixed to propagate them. `auth-service` has no
      `@RabbitListener` at all, so was out of scope. See
      [ADR-0019](docs/adr/0019-message-consumption-resilience.md).
- [ ] notification-service (Python): still no retry limit/backoff/DLQ on
      its `pika` consumer — it always acks, even on failure, so a poison
      message is logged once and dropped rather than retried forever
      (silent loss, unlike the infinite-requeue failure mode ADR-0019
      closed on the Spring side). Candidate: a dead-letter queue plus a
      bounded nack-and-requeue count. Blocked by prioritization, not a
      technical dependency — tracked here as its own follow-up item.
- [x] notification-service's RabbitMQ consumer and Postgres schema-init
      step now both retry a failed initial connection instead of dying
      silently — found as a real, blocking defect while validating the
      end-to-end walkthrough against a real freshly-started stack, see
      [ADR-0017](docs/adr/0017-notification-service-startup-resilience.md).
      Distinct from the per-message retry/DLQ gap above: this was about
      the consumer never even getting a chance to process *any* message.
- [ ] notification-service has no versioned migration tool (no Alembic
      equivalent to Flyway) — `scripts/init.sql` is idempotent but not
      versioned, matching inventory-service's (Go) level of simplicity, not
      the four Spring services'. Acceptable for a single-table schema today;
      revisit if the schema grows. See [ADR-0014](docs/adr/0014-notification-service.md).
- [x] auth-service's `SecurityConfig` was missing `.httpBasic(...)` (the same
      defect class ADR-0013 found and fixed in billing-service) — fixed,
      see [ADR-0015](docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md).
- [x] api-gateway: billing-service now has a circuit breaker like every
      other implemented entry — see ADR-0009 (closes the gap ADR-0006 left
      open).
- [x] All six services' health checks fixed and unified — see
      [ADR-0010](docs/adr/0010-uniform-service-healthchecks.md). Started
      from billing-service's and orders-service's Dockerfiles hard-coding
      port 8082 (products-service's port), which uncovered two deeper bugs
      once the ports were corrected: every Spring service's `HEALTHCHECK`
      targeted `/actuator/health` — a path that isn't uniformly exposed or
      `permitAll()`-ed — instead of each service's own working `/health`
      endpoint; and orders-service's `docker-compose.yml` healthcheck
      override was a no-op (a YAML-folding bug swallowed the real `curl`
      command in a shell comment, so it always reported "healthy"
      regardless of the app's actual state). Also added: a
      `HealthController`/`SecurityConfig` for billing-service (which had
      neither), and a `HEALTHCHECK` for auth-service, inventory-service, and
      api-gateway (which never had one). All six services now verified
      `healthy` simultaneously.
- [x] billing-service was the only service protected solely by Spring
      Boot's default Basic Auth; it now authenticates via the same Bearer
      JWT broadcast cache already used by products-service and
      orders-service, fully replacing Basic Auth — see
      [ADR-0015](docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md).
      (auth-service is the producer of that broadcast, not a consumer of
      it, and remains on `.httpBasic()` — currently unexercised, since
      every one of its endpoints is `permitAll()`.)
- [x] Shared parent POM across the four Spring services (auth, products,
      orders, billing) — see
      [ADR-0016](docs/adr/0016-shared-spring-parent-pom.md). Also
      standardized all four on Spring Boot 3.2.12 (previously split
      3.2.0/3.2.12) and required moving Docker's build context to the
      repository root for these four services.
- [ ] `inventory-service/internal/handlers/http_handlers.go` (an
      `InventoryHandler` struct built on gin) is dead code — `main.go`
      registers its actual `/inventory` and `/inventory/{productId}`
      routes with plain `net/http.HandleFunc` directly and never
      references this struct at all. Found while writing
      [docs/walkthrough.md](docs/walkthrough.md); doesn't affect behavior
      (main.go's real handlers work correctly), so left alone rather than
      removed outside that task's scope. Candidate for a future cleanup
      pass: either delete the file or migrate main.go's routing to use it.
- [x] notification-service now has a minimal, read-only public API —
      `GET /notifications/{orderId}` — mirroring the same "smallest
      endpoint for the use case" principle ADR-0014 already applied to
      auth-service's `notification-profile` endpoint. Closes the gap that
      previously made step 9 of [docs/walkthrough.md](docs/walkthrough.md)
      the one step in that walkthrough requiring direct Postgres access;
      no direct database query is needed anywhere in that walkthrough
      anymore.

</details>
