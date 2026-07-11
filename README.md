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

**Status: in active development.** All eight services are implemented and
building (api-gateway, auth, products, inventory, orders, billing,
notification, frontend). Four of the backend services (auth-service, orders-service, products-service,
billing-service) have contract tests validating their event/message DTOs
against JSON Schemas shared in `/schemas/json/`; inventory-service has eight
unit tests, four of which cover its stock-reservation idempotency logic
specifically, not the service broadly. notification-service consumes both
`order.created` and `order.status-changed` via RabbitMQ, enriches the
former with a real HTTP call to auth-service, and persists an observable
notification per event — never overwriting a previous one — queryable via
its own read-only `GET /notifications/{orderId}` (no real email/SMS
provider yet — see [ADR-0014](docs/adr/0014-notification-service.md)).
A SvelteKit frontend now exists as a thin, read-mostly client over the API
Gateway — login, catalog browsing, checkout, order tracking, and a
notification/observability view, deliberately not a full storefront (see
[ADR-0026](docs/adr/0026-frontend-thin-client.md)). See
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
- **Transparent Gateway routing** — the Gateway forwards every request's
  path unchanged; it never rewrites a service's contract. Every service
  is self-namespaced under its own Gateway segment (`/auth`, `/products`,
  `/orders`, `/billing`, `/inventory`), so a direct call and a call
  proxied through the Gateway hit the exact same path. See
  [ADR-0025](docs/adr/0025-gateway-transparent-routing.md).
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
- **Distributed tracing via propagated identifiers** — a CorrelationId
  born at the first HTTP request (or reused from the client) rides every
  hop's HTTP headers and native AMQP message properties unchanged, so one
  business operation can be followed through every service's logs with a
  single grep, in three languages, with no tracing backend. See
  [docs/architecture/observability.md](docs/architecture/observability.md)
  and
  [ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md).
- **A thin-client frontend, not a parallel architecture** — the SvelteKit
  UI has no business logic of its own; it calls the Gateway exclusively
  and surfaces the backend's own tracing identifiers instead of inventing
  a client-side observability layer. See
  [ADR-0026](docs/adr/0026-frontend-thin-client.md).

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

All eight services (API Gateway, Auth, Products, Inventory, Orders,
Billing, Notification, Frontend) come up and respond on their ports (see
[Service Status](#service-status) for the full port list). Open
`http://localhost:3000` for the frontend once `docker-compose ps` shows
everything healthy.

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

Frontend (SvelteKit, thin client) consumes the API Gateway only.
```

## Documentation

- [Architecture Overview](docs/architecture/overview.md) — the map: bounded
  contexts, business flows, communication, persistence, and the
  exchange/event table.
- [Architecture Decision Records](#architecture-decision-records) — 26
  ADRs, one per architectural decision made along the way, in chronological
  order.
- [Observability](docs/architecture/observability.md) — how one business
  operation is traced end to end through every service's logs via a
  propagated CorrelationId, without a tracing backend.
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

| Service | Stack | Port | Status | Test Coverage |
|---|---|---|---|---|
| API Gateway | Go + Gin | 8080 | Implemented | 9 test functions — circuit breaker ([ADR-0006](docs/adr/0006-gateway-circuit-breaker.md)/[ADR-0009](docs/adr/0009-billing-circuit-breaker.md)), correlation middleware ([ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)), and transparent routing across all 6 services ([ADR-0025](docs/adr/0025-gateway-transparent-routing.md)) |
| Auth | Spring Boot + PostgreSQL + JWT + Outbox | 8081 | Implemented | 20 tests — unit + `*IT` (Outbox, real Postgres/RabbitMQ) |
| Products | Spring Boot + PostgreSQL + RabbitMQ | 8082 | Implemented | 12 unit tests |
| Inventory | Go + PostgreSQL + RabbitMQ + Outbox | 8083 | Implemented | 14 tests — 8 unit + 6 integration (real Postgres/RabbitMQ, includes concurrency via `go test -race`) |
| Orders | Spring Boot + PostgreSQL + RabbitMQ | 8084 | Implemented | 61 tests — unit + `*IT` (real Postgres/RabbitMQ), including 4 covering self-purchase prevention and 31 covering the order fulfillment lifecycle (ship/deliver, single-source-of-truth transitions, the `previousState` fix) |
| Billing | Spring Boot + PostgreSQL + RabbitMQ + JWT | 8085 | Implemented | 26 tests — unit + `*IT` (real Postgres/RabbitMQ) |
| Notification | FastAPI + PostgreSQL + RabbitMQ | 8086 | Implemented | 34 tests — 26 unit + 8 integration (real Postgres/RabbitMQ/auth-service) |
| Frontend | SvelteKit + TypeScript | 3000 | Implemented | 0 automated tests — validated manually end to end (see [ADR-0026](docs/adr/0026-frontend-thin-client.md)) |

"Implemented" means the service builds, runs, and has the test coverage shown above — it does not imply every known gap is closed; see the Roadmap below and each ADR's Consequences section for what's still open.

Event contracts are validated via JSON Schema for two of the system's event types — see [ADR-0002](docs/adr/0002-json-schema-contract-testing.md) for what's covered and the known `price` type gap schema validation can't catch. The messaging layer itself was separately audited for wiring bugs (routing keys, field names, a competing-consumer incident) — see [ADR-0001](docs/adr/0001-messaging-wiring-audit.md).

billing-service's `mvn verify` suite required fixing three real bugs the first time it actually ran against Postgres/RabbitMQ, including a `TYPE_MAPPINGS` entry pointing at another service's event class — a concrete instance of this project's lack of contract testing between services, since each service hand-duplicates its own copy of shared event DTOs. See [ADR-0008](docs/adr/0008-surefire-failsafe-test-split.md).

inventory-service's stock-reservation idempotency — redelivery-safe caching, per-`OrderID` locking for genuine concurrency, and the residual gap once a cache entry expires — is documented in [ADR-0007](docs/adr/0007-remove-kafka-broker.md) and the service's own source, not repeated here.

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
| [0008](docs/adr/0008-surefire-failsafe-test-split.md) | Separate unit and integration tests via Surefire/Failsafe | Accepted, partially superseded | Originally: four test classes touching real Postgres/RabbitMQ renamed to the `*IT` suffix and moved to `maven-failsafe-plugin` across all four Spring services. Since updated (see the ADR's 2026-07-06 addendum): a later step of ADR-0007's Kafka-to-RabbitMQ migration replaced three of those four `*IT` classes with broker-agnostic behavior tests; only billing-service's `BillingServiceApplicationIT` remains, and `maven-failsafe-plugin` was removed from the other three services' `pom.xml`. |
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
| [0021](docs/adr/0021-payment-outcome-integration.md) | Payment outcome integration — billing-service publishes `payment.approved`/`payment.failed`, orders-service reacts | Accepted | Closes the gap ADR-0020 found: `billing-service` now publishes a payment outcome event once `PaymentService.processPayment` resolves it; a new `PaymentEventsConsumer` in `orders-service` finally calls `OrderService.handlePaymentReceived`/`handlePaymentFailed` (previously unreachable since ADR-0001 removed their incorrectly-typed predecessor). Also fixed a latent bug those methods had (`previousState` hardcoded to `PENDING` instead of the order's real prior state), found by the first tests that ever exercised them. `notification-service` required no changes. |
| [0022](docs/adr/0022-notification-service-consumption-resilience.md) | notification-service consumption resilience (retry, backoff, dead-lettering) | Accepted | Closes notification-service's last remaining gap from ADR-0019/ADR-0017: its `pika` consumer used to ack even on failure, silently dropping malformed or unexpected-error messages. Now retries up to 3 times with exponential backoff (a RabbitMQ retry queue with a per-message TTL, not a sleep or poll) before dead-lettering — same numbers and conceptual behavior as the Spring services, built on different primitives since Pika has no retry-template equivalent. Found, and since fixed (see ADR-0022's Update): `products-service`'s `handleUserVerified` had no role filter, so every buyer's `user.verified` event was dead-lettered. |
| [0023](docs/adr/0023-notification-service-persistence-evolution-strategy.md) | Notification Service Persistence Evolution Strategy | Accepted | Formally reviews and closes the "no Alembic" gap left open since ADR-0014. `scripts/init.sql` hasn't changed once across four ADRs of functional growth, and a comparison against `inventory-service` (whose own idempotent init.sql *has* evolved twice, safely, with no versioned tool) shows the current approach has headroom beyond what notification-service has needed. Keeps the idempotent script, documents concrete criteria for reopening the decision if the schema outgrows it. |
| [0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md) | Distributed tracing via propagated correlation identifiers, not a tracing backend | Accepted | CorrelationId/RequestId/MessageId propagated through HTTP headers and native AMQP message properties (not a new wire format), logged in a consistent structured format across all seven services. Two small shared modules (`correlation-commons` for the four Spring services, `correlation-commons-go` for the two Go services) — a deliberate, narrow exception to this project's "no shared library" convention, since this code has no business meaning and must stay identical to hold the CorrelationId contract. Found and fixed two real bugs: notification-service's retry path silently dropped correlation/message ids, and the Go shared module's logger had a service name hardcoded from before it had a second consumer. See [docs/architecture/observability.md](docs/architecture/observability.md) for the full design. |
| [0025](docs/adr/0025-gateway-transparent-routing.md) | Gateway transparent routing — every service is self-namespaced | Accepted | Closes the `inventory-service` 404-through-the-gateway bug ADR-0024 found. The Gateway no longer strips any service prefix — it forwards the incoming path unchanged — and `auth-service`/`products-service`/`orders-service`/`billing-service` each gained a `server.servlet.context-path` matching their own Gateway segment (`inventory-service` was already self-namespaced). Every direct caller of those four services (Dockerfile `HEALTHCHECK`s, CI readiness checks, `e2e-tests`, the Postman collection) was updated in lockstep; the Postman collection now has parallel `Via Gateway (primary)` and `Direct (debug)` folder trees. |
| [0026](docs/adr/0026-frontend-thin-client.md) | SvelteKit frontend as a thin client over the API Gateway | Accepted | New `frontend/` (SvelteKit + TypeScript, SSR disabled, `adapter-node`) consumes only the Gateway. Building it surfaced and fixed four real defects no prior `curl`-based client could catch: CORS wired but shadowed by Spring Security in two services and entirely missing in four; `products-service`'s JWT filter terminating requests before `permitAll()` paths could ever be reached; its catalog endpoints unreachable by any buyer token by design; and the Gateway echoing `X-Correlation-Id`/`X-Request-Id` twice under one header. `notification-service` gains a Gateway route, closing the one gap ADR-0025 left open. |
| [0027](docs/adr/0027-jwt-principal-as-sole-identity-source.md) | JWT principal as the sole identity source for orders/products | Accepted | Closes a Critical Roadmap item: `orders-service`/`products-service` derived business identity from a client-supplied `X-User-Id` header instead of the authenticated JWT principal, letting any valid token impersonate any other user by changing one header — confirmed live with a real two-buyer impersonation before the fix. Both controllers now derive identity exclusively from `@AuthenticationPrincipal`; `X-User-Id` no longer exists anywhere in either service's request path. Its 2026-07-10 Update extends the same pattern to `billing-service`'s `PaymentController` (all four endpoints, closing a separate High Roadmap item). |
| [0028](docs/adr/0028-notification-service-authentication.md) | notification-service authentication and ownership check | Accepted | Closes a High Roadmap item (a real IDOR): `GET /notifications/{orderId}` had no authentication at all. notification-service gains its own JWT broadcast cache (consuming `jwt.created` for the first time, same pattern as every Spring service's `JwtConsumer`) and now requires the caller to be the order's own buyer, read from that order's real `order.created` payload — 403 otherwise. Confirmed live with a real two-buyer test. |
| [0029](docs/adr/0029-order-fulfillment-lifecycle.md) | Activating the order fulfillment lifecycle (ship/deliver) | Accepted | Closes a Medium Roadmap item: `SHIPPED`/`DELIVERED` were configured state machine transitions with no code path to reach them. Adds `POST /{orderId}/ship` (the project's first role-gated, not ownership-gated, endpoint — the new `ADMIN` platform-operations role) and `POST /{orderId}/deliver` (ownership-gated like `cancelOrder`); replaces `canCancel()`'s hand-written eligibility list with a single `isTransitionAllowed` derived from the state machine's own configured graph; closes a self-registration-as-admin path in auth-service's `/signup`. Live validation caught and fixed a real `previousState`-after-mutation bug in `cancelOrder` and both new methods. No new event type, no new table. |

## Roadmap

<details>
<summary>Click to expand — closed items marked <code>[x]</code>, open items <code>[ ]</code></summary>

- [x] **Opened 2026-07-04.** CI pipeline, Phase 1
      (`.github/workflows/ci.yml`): parallel build/vet/unit-test jobs for
      all seven services, no service containers.
- [x] **Opened 2026-07-04.** Shared parent POM across the four Spring
      services (auth, products, orders, billing) — see
      [ADR-0016](docs/adr/0016-shared-spring-parent-pom.md). Also
      standardized all four on Spring Boot 3.2.12 (previously split
      3.2.0/3.2.12) and required moving Docker's build context to the
      repository root for these four services.
- [x] **Opened 2026-07-04.** api-gateway: billing-service now has a
      circuit breaker like every other implemented entry — see ADR-0009
      (closes the gap ADR-0006 left open the same day it was added).
- [x] **Opened 2026-07-04.** products-service, orders-service,
      billing-service: limited retry (3 attempts, exponential backoff)
      plus a dead-letter exchange/queue per service, configured natively
      via Spring Boot's `SimpleRabbitListenerContainerFactoryConfigurer`
      and a `RepublishMessageRecoverer` — no custom retry code. Eight
      listener methods across four consumer classes that used to swallow
      business exceptions internally (so the container never saw a
      failure to retry) were fixed to propagate them. `auth-service` has
      no `@RabbitListener` at all, so was out of scope. See
      [ADR-0019](docs/adr/0019-message-consumption-resilience.md).
- [x] **Opened 2026-07-04.** inventory-service (Go): Outbox Pattern
      implemented for stock reservation — see
      [ADR-0007](docs/adr/0007-remove-kafka-broker.md).
      `ReserveStockForOrder` writes the `stock.reserved`/`stock.insufficient`
      event to `inventory_schema.outbox_events` in the same Postgres
      transaction as the reservation itself, and a poller publishes it to
      RabbitMQ — closing the "event lost if the process crashes right
      after commit" gap. This doesn't make message redelivery itself
      idempotent (a separate, still-open concern — see below); it only
      guarantees the reservation outcome is never silently lost once
      committed.
- [x] **Opened 2026-07-05.** billing-service was the only service
      protected solely by Spring Boot's default Basic Auth; it now
      authenticates via the same Bearer JWT broadcast cache already used
      by products-service and orders-service, fully replacing Basic Auth
      — see [ADR-0015](docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md).
      (auth-service is the producer of that broadcast, not a consumer of
      it, and remains on `.httpBasic()` — currently unexercised, since
      every one of its endpoints is `permitAll()`.)
- [x] **Opened 2026-07-05.** All six services' health checks fixed and
      unified — see [ADR-0010](docs/adr/0010-uniform-service-healthchecks.md).
      Started from billing-service's and orders-service's Dockerfiles
      hard-coding port 8082 (products-service's port), which uncovered
      two deeper bugs once the ports were corrected: every Spring
      service's `HEALTHCHECK` targeted `/actuator/health` — a path that
      isn't uniformly exposed or `permitAll()`-ed — instead of each
      service's own working `/health` endpoint; and orders-service's
      `docker-compose.yml` healthcheck override was a no-op (a
      YAML-folding bug swallowed the real `curl` command in a shell
      comment, so it always reported "healthy" regardless of the app's
      actual state). Also added: a `HealthController`/`SecurityConfig`
      for billing-service (which had neither), and a `HEALTHCHECK` for
      auth-service, inventory-service, and api-gateway (which never had
      one). All six services now verified `healthy` simultaneously.
- [x] **Opened 2026-07-06.** CI pipeline, Phase 2
      (`.github/workflows/ci.yml`): wiring and Outbox integration tests
      against real Postgres/RabbitMQ service containers — see
      [ADR-0012](docs/adr/0012-ci-phase-2-real-infrastructure.md).
- [x] **Opened 2026-07-07.** Notification service (FastAPI + RabbitMQ
      consumer): consumes `order.created`, enriches via a real HTTP call
      to a new minimal auth-service endpoint, persists an observable
      notification in a new `notification_schema` — see
      [ADR-0014](docs/adr/0014-notification-service.md).
- [x] **Opened 2026-07-07.** CI pipeline, Phase 3
      (`.github/workflows/ci.yml`): cross-service end-to-end tests that
      start multiple real services as actual running processes against
      one shared Postgres/RabbitMQ pair and drive each flow through
      public HTTP APIs only — `catalog-onboarding` (auth-service,
      products-service, inventory-service), `order-lifecycle`
      (auth-service, orders-service, inventory-service, billing-service)
      — see [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md) —
      and `notification-flow` (auth-service, notification-service) — see
      [ADR-0014](docs/adr/0014-notification-service.md).
- [x] **Opened 2026-07-07.** End-to-end integration tests across the
      implemented services — see CI Phase 3 above (`catalog-onboarding`,
      `order-lifecycle`, and `notification-flow` groups).
- [x] **Opened 2026-07-07.** notification-service's lack of a versioned
      migration tool (no Alembic equivalent to Flyway) — a gap inherent
      since the service's own creation — was formally reviewed, not left
      as an indefinite gap: `scripts/init.sql` hasn't changed once across
      four ADRs of functional growth, and a comparison against
      inventory-service (whose own idempotent init.sql *has* evolved
      twice, safely, with no versioned tool) shows the current approach
      has headroom beyond what notification-service has needed so far.
      Decision kept as-is, with concrete criteria for reopening it if the
      schema outgrows this approach — see
      [ADR-0023](docs/adr/0023-notification-service-persistence-evolution-strategy.md).
- [x] **Opened 2026-07-08.** `order.status-changed` (`orders-service`,
      published on `order.exchange`, see
      [ADR-0001](docs/adr/0001-messaging-wiring-audit.md)) is now
      consumed by `notification-service`, the same way it already
      consumes `order.created`. It carries no `userId` of its own, so
      notification-service reuses the email/name already captured by that
      order's `order.created` notification instead of a second
      auth-service call. Every event produces its own new notification
      row — none are ever overwritten. See
      [ADR-0020](docs/adr/0020-notification-domain-completion.md).
- [x] **Opened 2026-07-08.** notification-service now has a minimal,
      read-only public API — `GET /notifications/{orderId}` — mirroring
      the same "smallest endpoint for the use case" principle ADR-0014
      already applied to auth-service's `notification-profile` endpoint.
      Closes the gap that previously made step 9 of
      [docs/walkthrough.md](docs/walkthrough.md) the one step in that
      walkthrough requiring direct Postgres access; no direct database
      query is needed anywhere in that walkthrough anymore. Same
      [ADR-0020](docs/adr/0020-notification-domain-completion.md) as the
      item above.
- [x] **Opened 2026-07-08.** `billing-service` now publishes
      `payment.approved`/`payment.failed` once a payment resolves;
      `orders-service`'s own `handlePaymentReceived`/`handlePaymentFailed`
      (previously unreachable dead-code-adjacent methods — see ADR-0001,
      finding 5) finally have a real caller (`PaymentEventsConsumer`),
      driving the order into `PAYMENT_APPROVED`/`PAYMENT_FAILED` and
      publishing `order.status-changed` through the same path stock
      reservation already used. `notification-service` required no
      changes to react to it. See
      [ADR-0021](docs/adr/0021-payment-outcome-integration.md).
- [x] **Opened 2026-07-08.** notification-service (Python) now has the
      same conceptual retry/backoff/DLQ policy as the Spring services
      (limited retries, exponential backoff, dead-lettering after the
      retry budget is exhausted) — built natively on RabbitMQ (a retry
      queue with a per-message TTL and `x-dead-letter-exchange` back to
      the original exchange, then a terminal dead letter exchange/queue)
      since Pika has no built-in retry template equivalent to Spring
      AMQP's. No message is silently dropped or retried forever anymore.
      See [ADR-0022](docs/adr/0022-notification-service-consumption-resilience.md).
- [x] **Opened 2026-07-08.** notification-service's RabbitMQ consumer and
      Postgres schema-init step now both retry a failed initial
      connection instead of dying silently — found as a real, blocking
      defect while validating the end-to-end walkthrough against a real
      freshly-started stack, see
      [ADR-0017](docs/adr/0017-notification-service-startup-resilience.md).
      Distinct from the per-message retry/DLQ gap above: this was about
      the consumer never even getting a chance to process *any* message.
- [x] **Opened 2026-07-09.** Distributed tracing via propagated
      CorrelationId/RequestId/MessageId across HTTP and RabbitMQ, in a
      consistent structured logfmt format across all seven services —
      see [ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)
      and [docs/architecture/observability.md](docs/architecture/observability.md).
- [x] **Opened 2026-07-09.** `api-gateway`'s reverse proxy no longer
      strips the service prefix before forwarding — it forwards the
      incoming path unchanged, and every service (`auth-service`,
      `products-service`, `orders-service`, `billing-service` via
      `server.servlet.context-path`; `inventory-service` already this
      way) is self-namespaced under its own Gateway segment. Closes the
      `inventory-service` 404 found while live-validating ADR-0024's
      CorrelationId propagation through the gateway. See
      [ADR-0025](docs/adr/0025-gateway-transparent-routing.md).
- [x] **Opened 2026-07-09.** `notification-service` now has a Gateway
      route (`/notification`, self-namespaced the same way as the other
      five services) — closes the gap ADR-0025 explicitly left open the
      same day it was found. Found building the frontend: CORS was wired
      but shadowed by Spring Security in two services and entirely
      missing in four; `products-service`'s JWT filter terminated
      requests before a `permitAll()` catalog endpoint could ever be
      reached by a buyer's token; and the Gateway echoed
      `X-Correlation-Id`/`X-Request-Id` twice under one header. All fixed
      — see [ADR-0026](docs/adr/0026-frontend-thin-client.md).
- [x] **Opened 2026-07-10.** `orders-service` now rejects a `SELLER`
      buying their own product (`400 Bad Request`, "Cannot purchase your
      own product") — a `SELLER` can still buy normally, including other
      sellers' products, and a `BUYER` is unaffected either way. A new
      consumer builds a minimal `orders_schema.product_ownership`
      (`product_id`, `seller_id` — nothing else) from `product.created`,
      the same event-projection pattern `inventory-service` already uses;
      no synchronous call to products-service was added. Found while
      building the frontend's checkout flow, deliberately not implemented
      as part of [ADR-0026](docs/adr/0026-frontend-thin-client.md); closed
      the same day as a natural domain evolution, not a new architectural
      decision — no new ADR.
- [x] **Opened 2026-07-10 (Critical).** `orders-service` and
      `products-service` trusted the `X-User-Id` header directly, with no
      cross-check against the authenticated JWT's own claims. Any request
      carrying a valid token could claim to be any other `userId` simply
      by setting a different header value. Fixed by deriving identity
      exclusively from `@AuthenticationPrincipal` (the principal
      `JwtAuthenticationFilter` already populates) in both controllers,
      removing `X-User-Id` from the request path entirely — see
      [ADR-0027](docs/adr/0027-jwt-principal-as-sole-identity-source.md),
      which includes a live before/after reproduction of the
      impersonation this closes.
- [x] **Opened 2026-07-10.** `docker-compose.yml` set
      `SPRING_JPA_HIBERNATE_DDL_AUTO=update` for all four Spring services,
      overriding `application.properties`' `validate` at runtime — the
      containers never actually ran under Flyway-only schema management,
      contradicting [ADR-0004](docs/adr/0004-auth-service-schema-authority-fix.md)/
      [ADR-0011](docs/adr/0011-flyway-schema-authority-all-services.md).
      Root cause traced via git history: the override was added in commit
      `5fbdad5` (2025-10-19), months before Flyway existed in this project
      at all (ADR-0004 landed 2026-07-04) — orphaned pre-Flyway scaffolding
      nobody audited when the properties files were switched to `validate`.
      Not a compensating fix for any real migration/entity gap. Fixed by
      removing the override; empirically validated by wiping the dev
      Postgres volume and running `docker compose up --build` for all four
      services — each ran its Flyway migrations from an empty schema and
      started cleanly under `validate`, with no Hibernate schema error. No
      new ADR: this corrects the running containers to match a decision
      ADR-0004/ADR-0011 already made, not a new one.
- [x] **Opened 2026-07-10.** `notification-service`'s
      `GET /notifications/{orderId}` had no authentication or
      authorization check at all — anyone who knew or guessed an
      `orderId` (a UUID) could read that order's notification history,
      including the buyer's name and email. Fixed by giving
      notification-service its own JWT broadcast cache (consuming
      `jwt.created` for the first time) and requiring the caller to be
      the order's own buyer (compared against the real `order.created`
      notification's `userId`, never a client header) — 403 otherwise.
      See [ADR-0028](docs/adr/0028-notification-service-authentication.md).
- [x] **Opened 2026-07-10.** `billing-service`'s `PaymentController`
      had no ownership checks on any endpoint: `GET /api/payments`
      returned every payment in the system; `GET /api/payments/{id}`
      and `GET /api/payments/order/{orderId}` never confirmed the caller
      was the payment's own buyer; `DELETE /api/payments/{id}` deleted
      any payment with no check. Fixed by scoping `GET /api/payments` to
      the authenticated principal's own payments and adding an
      ownership check (403 for a non-owner) to the other three —
      extends [ADR-0027](docs/adr/0027-jwt-principal-as-sole-identity-source.md)'s
      JWT-principal-as-sole-identity-source pattern to billing-service
      (see that ADR's Update section).
- [x] **Opened 2026-07-10.** `products-service`'s `SecurityConfig`
      had `/debug/**` fully `permitAll()`, and `GET /debug/tokens`
      triggered `JwtAuthenticationFilter.listTokens()`, logging every
      cached user's email — an anonymous way to enumerate active
      sessions. Fixed by removing `DebugController` and the `/debug/**`
      permitAll rule entirely (chosen over profile-gating or admin auth
      for the smallest attack surface and simplest fix consistent with
      this project's minimalism); an anonymous request now gets 403
      before Spring MVC would even resolve a handler. No new ADR: a
      removal, not a new architectural decision. A structurally
      identical `/debug/tokens` (plus `/debug/buyers`, which dumps every
      buyer row, and a no-op `/debug/clear-tokens`) exists in
      `orders-service`'s `OrderDebugController` under the same
      `permitAll()` pattern — found while fixing this item, out of its
      scope, not fixed here; tracked below as a new item.
- [x] **Opened 2026-07-10.** `orders-service`'s `OrderDebugController`
      had the same unauthenticated `/debug/**` surface products-service's
      equivalent had just been fixed for above: `GET /debug/tokens`
      (logged cached user emails), `GET /debug/buyers` (returned every
      buyer row — real PII, not just a count), and a no-op
      `POST /debug/clear-tokens`. Also found while fixing this: the
      controller's class-level `@RequestMapping("/debug")` combined with
      each method's own `/debug/...` mapping duplicated the path segment
      (the real, live-confirmed endpoint was `/debug/debug/buyers`, not
      `/debug/buyers`) — moot now, but the actual reason a quick manual
      `curl /debug/buyers` during earlier review would have found nothing
      and looked already safe. Fixed the same way as products-service:
      `OrderDebugController` and the `/debug/**` permitAll rule removed
      entirely; confirmed live (403 on all three real paths). No new ADR.
- [x] **Opened 2026-07-10, closed 2026-07-11 (Medium).**
      `OrderStateMachineConfig` wires real transitions for `SHIPPED`/
      `DELIVERED` (`SHIP_ORDER`/`DELIVER_ORDER`), but no code anywhere in
      `orders-service` ever sends either event — both states are
      configured but structurally unreachable. Separately,
      `OrderService.canCancel()` allows attempting a cancellation from
      `PAYMENT_APPROVED`, but the state machine's real transition table
      only accepts `CANCEL_ORDER` from `PENDING`/`PROCESSING`/
      `INVENTORY_RESERVED` — a cancel attempt from `PAYMENT_APPROVED`
      isn't cleanly rejected upfront, it fails later with "event not
      accepted" once the mismatch is hit. Two different sources of truth
      for the same business rule, and they disagree. Resolved by
      activating both transitions (`POST /{orderId}/ship`, gated by the
      new `ADMIN` platform-operations role; `POST /{orderId}/deliver`,
      ownership-gated like `cancelOrder`) instead of deleting the dead
      states, and replacing `canCancel()` with a single
      `isTransitionAllowed(state, event)` derived directly from the state
      machine's own configured transition graph, reused by cancel/ship/
      deliver alike. Live validation against a real running stack also
      caught and fixed a related, previously undetected bug: `cancelOrder`
      (and the two new methods, copied from its pattern) read
      `previousState` from the same `Order` entity *after* calling
      `sendEvent`, which mutates that same Hibernate-managed instance in
      place within the shared transaction — every `order.status-changed`
      event these methods ever published had `previousState == newState`.
      See [ADR-0029](docs/adr/0029-order-fulfillment-lifecycle.md).
- [ ] **Opened 2026-07-10 (Medium).** `billing-service` has a
      `PaymentProvider` interface and a `PaymentMockService` implementing
      it (`service/provider/`), but `PaymentService.processPayment` never
      calls either — it inlines `Math.random() < 0.9` directly instead.
      The abstraction is dead code, and the actual approval logic is
      non-deterministic with no seam to make it reproducible, which is
      why `docs/walkthrough.md` can only describe the payment step as
      "accepts either outcome" rather than asserting one.
- [ ] **Opened 2026-07-10 (Architectural note).** `orders-service`'s
      Spring State Machine usage
      (`OrderStateMachineService.sendEvent`) rebuilds and rehydrates a
      brand new state machine instance from the database on every single
      event, reads the resulting state back out, writes it to
      `Order.state`, then stops and discards the machine — the actual
      persisted source of truth is always the `Order.state` column, never
      the machine itself, which never stays alive between calls. This
      pays the full complexity cost of a state machine framework
      (factory, accessor, context reset boilerplate) without the benefit
      state machines are supposed to provide (a live authority you query,
      not just a disposable transition validator). Worth revisiting as
      one of two clean options: make the state machine the actual live
      authority, or drop the framework for a validated plain transition
      table — keeping both halves of the current hybrid is the one option
      that isn't defensible.

</details>

## License

This repository is licensed under the [Apache License 2.0](LICENSE). You are
free to use, modify, and distribute this code, including for commercial
purposes, subject to the terms of that license. See the [NOTICE](NOTICE)
file for a summary of the practices this project demonstrates and the
attribution requirements that carry over into derivative works.

Easydora is primarily an educational and portfolio project: it exists to
demonstrate distributed-systems architecture and engineering practices,
not to be adopted as a dependency or a production platform. Contributions
(bug reports, fixes, documentation improvements) are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md). The author retains copyright over the
original work as stated in [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Disclaimer

This repository was built for learning and portfolio purposes, to
demonstrate event-driven microservices architecture, testing discipline,
and the kind of decision-making documented throughout the ADR set. It is
**not** intended to be a production-ready commercial platform.

Some external integrations are intentionally simplified: notification-service
sends no real email/SMS (see [ADR-0014](docs/adr/0014-notification-service.md)),
billing-service's payment provider is not a real payment gateway, and
credentials/secrets in this repository's local `.env.example` are meant for
local development only, never for production use.

Architectural decisions throughout this project prioritize demonstrating
sound engineering practices — resilience, observability, testing, contract
validation — over full business completeness. Known gaps are tracked
openly in the [Roadmap](#roadmap) and in each ADR's own Consequences
section rather than hidden.
