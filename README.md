# EasyDora

[![CI](https://github.com/pablofelipe/easydora/actions/workflows/ci.yml/badge.svg)](https://github.com/pablofelipe/easydora/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/pablofelipe/easydora)](https://github.com/pablofelipe/easydora/releases)
[![Last Commit](https://img.shields.io/github/last-commit/pablofelipe/easydora)](https://github.com/pablofelipe/easydora/commits/main)

## Overview

EasyDora is a microservices-based e-commerce platform. It demonstrates how
to build an end-to-end distributed business flow. It uses event-driven
architecture, RabbitMQ, the Outbox Pattern, contract testing, and continuous
integration.

Each service uses the language and stack suited to its workload, not the
stack chosen for convenience. Go handles performance-sensitive gateway and
inventory paths. Spring Boot handles domain-rich business logic. FastAPI
handles async notification processing.

**Status: in active development.** All eight services are implemented and
building: api-gateway, auth, products, inventory, orders, billing,
notification, frontend. All six messaging services (auth-service,
products-service, orders-service, billing-service, inventory-service,
notification-service) have contract tests. These tests validate each
service's event/message DTOs against JSON Schemas shared in
`/schemas/json/`. The tests cover all 17 currently-published messages (see
[ADR-0002](docs/adr/0002-json-schema-contract-testing.md)'s 2026-07-13
Update). notification-service consumes both `order.created` and
`order.status-changed` via RabbitMQ. It enriches `order.created` with a
real HTTP call to auth-service. It persists an observable notification per
event, and never overwrites a previous one. You can query these
notifications via its own read-only `GET /notifications/{orderId}`. No
real email/SMS provider exists yet (see
[ADR-0014](docs/adr/0014-notification-service.md)). A SvelteKit frontend
now exists as a thin, read-mostly client over the API Gateway. It covers
login, catalog browsing, checkout, order tracking, and a
notification/observability view. It is deliberately not a full storefront
(see [ADR-0026](docs/adr/0026-frontend-thin-client.md)). See
[Service Status](#service-status) for the current breakdown.

## What This Project Demonstrates

- **Event-driven architecture on RabbitMQ** — every cross-service
  interaction (user lifecycle, product catalog, order/stock/payment/
  notification) flows through topic exchanges. No domain service calls
  another synchronously. See [Architecture](#architecture) and
  [ADR-0007](docs/adr/0007-remove-kafka-broker.md).
- **Outbox Pattern** — auth-service, inventory-service, orders-service, and
  billing-service each write their outbound events in the same database
  transaction as the state change that triggers them. This way, an event
  is never silently lost on a crash between commit and publish. See
  [ADR-0003](docs/adr/0003-outbox-pattern-auth-service.md),
  [ADR-0007](docs/adr/0007-remove-kafka-broker.md), and
  [ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)
  for the consolidated specification and its extension to orders-service
  and billing-service.
- **Contract testing** — event/message DTOs are validated against
  versioned JSON Schemas. This catches producer/consumer drift
  automatically, instead of in production. See
  [ADR-0002](docs/adr/0002-json-schema-contract-testing.md).
- **Cross-service JWT broadcast authentication** — auth-service issues the
  token once. Every other service builds its own in-memory cache from a
  broadcast event, instead of re-verifying signatures locally. See the
  Overview's [Communication](docs/architecture/overview.md#communication)
  section.
- **Circuit breaker at the API Gateway** — outbound proxy calls fail fast
  instead of piling up when a downstream service is down. See
  [ADR-0006](docs/adr/0006-gateway-circuit-breaker.md) and
  [ADR-0009](docs/adr/0009-billing-circuit-breaker.md).
- **Transparent Gateway routing** — the Gateway forwards every request's
  path unchanged. It never rewrites a service's contract. Every service
  is self-namespaced under its own Gateway segment (`/auth`, `/products`,
  `/orders`, `/billing`, `/inventory`). A direct call and a call proxied
  through the Gateway hit the exact same path. See
  [ADR-0025](docs/adr/0025-gateway-transparent-routing.md).
- **Three-phase CI**: unit tests with no infrastructure (Phase 1),
  integration tests against real Postgres/RabbitMQ service containers
  (Phase 2), and cross-service end-to-end tests driven through public HTTP
  APIs against real running processes (Phase 3). See
  [ADR-0012](docs/adr/0012-ci-phase-2-real-infrastructure.md) and
  [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md).
- **A polyglot stack matched to workload**, not convenience — Go, Spring
  Boot, and FastAPI each do the job they are best suited for. See
  [Design notes](#design-notes).
- **A fully reproducible, real-command business flow** — signup through
  order, stock reservation, payment, and notification. This flow is
  documented and validated against real containers. See the
  [walkthrough](docs/walkthrough.md) and
  [sequence diagram](docs/sequence-diagram.md).
- **Distributed tracing, two complementary mechanisms** — a CorrelationId
  is born at the first HTTP request, or reused from the client. It rides
  every hop's HTTP headers and native AMQP message properties unchanged,
  letting you follow one business operation through every service's logs
  with a single grep, in three languages. Alongside it, every service
  also exports OpenTelemetry spans to a Jaeger backend, giving a visual
  span tree and real per-hop latency across HTTP and RabbitMQ hops, in
  the same three languages. See
  [docs/architecture/observability.md](docs/architecture/observability.md)
  and
  [ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)
  (including its 2026-08-02 Update).
- **A thin-client frontend, not a parallel architecture** — the SvelteKit
  UI has no business logic of its own. It calls the Gateway exclusively.
  It surfaces the backend's own tracing identifiers, instead of inventing
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
Billing, Notification, Frontend) come up and respond on their ports. See
[Service Status](#service-status) for the full port list. Open
`http://localhost:3000` for the frontend once `docker-compose ps` shows
everything healthy.

[docs/walkthrough.md](docs/walkthrough.md) has a full, reproducible
business-flow walkthrough: signup → product → order → stock reservation →
payment → notification. It runs entirely through `curl` against each
service's public API, with real request/response examples.
[docs/sequence-diagram.md](docs/sequence-diagram.md) shows the same flow
as a Mermaid sequence diagram. If Docker Compose fails to connect on
Windows, see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

### Running on Kubernetes (kind)

Docker Compose remains the recommended way to run EasyDora locally. As a
parallel, optional alternative, the backend and observability stack can
also run on a local [kind](https://kind.sigs.k8s.io/) cluster. See
[ADR-0040](docs/adr/0040-minimal-kubernetes-kind-deployment.md) for why,
and [k8s/README.md](k8s/README.md) for the full, step-by-step guide:

```bash
kind create cluster --config k8s/kind-config.yaml
# build + kind load docker-image each service (see k8s/README.md)
kubectl apply -f k8s/base/secrets.yaml   # copy from secrets.example.yaml first
kubectl kustomize k8s/base --load-restrictor LoadRestrictionsNone | kubectl apply -f -
```

## Architecture

See the [Architecture Overview](docs/architecture/overview.md) for the
full breakdown: bounded contexts, business flows, communication,
persistence, and the exchange/event table. The diagram below shows just
the component topology, at a glance:

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
- [Architecture Decision Records](#architecture-decision-records) — 40
  ADRs, one per architectural decision made along the way, in chronological
  order.
- [Observability](docs/architecture/observability.md) — how one business
  operation is traced end to end through every service's logs, via a
  propagated CorrelationId, without a tracing backend.
- [Postman collection](postman/) — the same main flow as an importable,
  runnable collection, with automatic ID/token capture. It complements the
  walkthrough.
- [Architectural Principles](docs/architecture/architectural-principles.md)
  — the recurring principles behind those decisions. These principles were
  extracted from the ADRs, not declared up front.
- [End-to-end walkthrough](docs/walkthrough.md) — the full business flow,
  driven entirely by `curl`, with real requests/responses from an actual
  run.
- [Sequence diagram](docs/sequence-diagram.md) — the same flow as a
  Mermaid diagram.
- [Design notes](#design-notes) — why each service uses the stack it uses.
- [Service Status](#service-status) — per-service ports, stack, and test
  coverage.
- [Versioning and Release Policy](docs/project-governance/versioning-and-release-policy.md)
  — what a version number means here, and what must stay in sync at
  release time. This is project governance, not architecture. It is kept
  separate from the ADRs above on purpose.

## Service Status

| Service | Stack | Port | Status | Test Coverage |
|---|---|---|---|---|
| API Gateway | Go + Gin | 8080 | Implemented | 9 test functions — circuit breaker ([ADR-0006](docs/adr/0006-gateway-circuit-breaker.md)/[ADR-0009](docs/adr/0009-billing-circuit-breaker.md)), correlation middleware ([ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)), and transparent routing across all 6 services ([ADR-0025](docs/adr/0025-gateway-transparent-routing.md)) |
| Auth | Spring Boot + PostgreSQL + JWT + Outbox | 8081 | Implemented | 29 tests — unit + `*IT` (Outbox, real Postgres/RabbitMQ), including 3 contract tests covering `user.registered`/`user.verified`/`jwt.created` ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)), a Progress Watchdog backing its `/health/liveness` signal, and a `ConnectionListener`-based reconnection-metrics test ([ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update) |
| Products | Spring Boot + PostgreSQL + RabbitMQ | 8082 | Implemented | 32 unit tests, including 6 contract tests covering `product.created`/`product.updated`/`product.deleted`/`user.verified`/`jwt.created` ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)), a Progress Watchdog backing its `/health/liveness` signal, and a `ConnectionListener`-based reconnection-metrics test ([ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update) |
| Inventory | Go + PostgreSQL + RabbitMQ + Outbox | 8083 | Implemented | 35 tests — 26 unit + 9 integration (real Postgres/RabbitMQ, includes concurrency via `go test -race`), including 7 contract tests covering `product.*`/`stock.*` ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)), a `NotifyClose`-based reconnect supervisor that redeclares topology after every reconnect, and publisher-confirms in the Outbox publisher (both proven against a real broker restart, [ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update) |
| Orders | Spring Boot + PostgreSQL + RabbitMQ + Outbox | 8084 | Implemented | 112 tests — unit + `*IT` (real Postgres/RabbitMQ), including 4 covering self-purchase prevention, 31 covering the order fulfillment lifecycle (ship/deliver, single-source-of-truth transitions, the `previousState` fix), 6 covering optimistic locking on `Order`, 23 covering the payment compensation saga ([ADR-0034](docs/adr/0034-payment-compensation-saga.md)), Outbox coverage for all four publishes ([ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)), 13 contract tests ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)), a Progress Watchdog backing its `/health/liveness` signal, and a `ConnectionListener`-based reconnection-metrics test ([ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update) |
| Billing | Spring Boot + PostgreSQL + RabbitMQ + JWT + Outbox | 8085 | Implemented | 60 tests — unit + `*IT` (real Postgres/RabbitMQ), including 6 covering the deterministic payment provider and its single-creation-path guarantee, 4 covering optimistic locking on `Payment`, 7 covering the payment compensation saga ([ADR-0034](docs/adr/0034-payment-compensation-saga.md)), Outbox coverage for all four publishes ([ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)), 6 contract tests ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)), a Progress Watchdog backing its `/health/liveness` signal, and a `ConnectionListener`-based reconnection-metrics test ([ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update) |
| Notification | FastAPI + PostgreSQL + RabbitMQ | 8086 | Implemented | 64 tests — 55 unit + 9 integration (real Postgres/RabbitMQ/auth-service), including 6 contract tests covering `order.created`/`order.status-changed`/`jwt.created` ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)) and an explicit heartbeat + Progress Watchdog proven to resume consuming after a real broker-forced disconnect, now also exposing the same reconnection metrics as every other service ([ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s 2026-07-20 Update) |
| Frontend | SvelteKit + TypeScript | 3000 | Implemented | 0 automated tests — validated manually end to end (see [ADR-0026](docs/adr/0026-frontend-thin-client.md)) |

"Implemented" means the service builds, runs, and has the test coverage shown above. It does not mean every known gap is closed. See the Roadmap below and each ADR's Consequences section for what's still open.

Event contracts are validated via JSON Schema for all 17 currently-published messages. See [ADR-0002](docs/adr/0002-json-schema-contract-testing.md) for what this covers, and for the known `price` type gap schema validation can't catch. A separate audit covered the messaging layer's wiring bugs: routing keys, field names, a competing-consumer incident. See [ADR-0001](docs/adr/0001-messaging-wiring-audit.md).

billing-service's `mvn verify` suite needed three real bug fixes the first time it ran against Postgres/RabbitMQ. One bug was a `TYPE_MAPPINGS` entry pointing at another service's event class. This bug is a concrete instance of this project's lack of contract testing between services, since each service hand-duplicates its own copy of shared event DTOs. See [ADR-0008](docs/adr/0008-surefire-failsafe-test-split.md).

inventory-service's stock-reservation idempotency uses redelivery-safe caching and per-`OrderID` locking for genuine concurrency. A residual gap remains once a cache entry expires. [ADR-0007](docs/adr/0007-remove-kafka-broker.md) and the service's own source document this; this README does not repeat it.

Infrastructure: RabbitMQ Management (15672), PostgreSQL (5432), Prometheus
(9090), Grafana (3001, see [ADR-0036](docs/adr/0036-metrics-via-prometheus-grafana.md)).

## Design notes

The stack split is deliberate:

- **Go** (Gateway, Inventory) — performance-sensitive, high-throughput
  paths.
- **Spring Boot** (Auth, Products, Orders, Billing) — domain-rich business
  logic, where Java's ecosystem (validation, transactions, ORM) pays off.
- **FastAPI** (Notification) — async I/O-bound processing. It currently
  runs a synchronous RabbitMQ consumer plus an HTTP client. See
  [ADR-0014](docs/adr/0014-notification-service.md) for why the project
  chose sync over `aio-pika`/asyncpg at this size.
- **SvelteKit** (Frontend) — lightweight reactive UI.

## Architecture Decision Records

| ADR | Title | Status | Summary |
|---|---|---|---|
| [0001](docs/adr/0001-messaging-wiring-audit.md) | Messaging wiring audit | Accepted, updated 2026-07-08 | Fixed five routing/field-name/listener bugs (RabbitMQ and Kafka). Fixed one JWT-queue message-loss incident dated back to the project's first commit. Removed one dead payment-event code path. `OrderStatusChangedEvent`'s designated consumer (notification-service) is now implemented. See the Roadmap. |
| [0002](docs/adr/0002-json-schema-contract-testing.md) | JSON Schema contract testing | Accepted | Adopted JSON Schema (draft 2020-12) for event contracts, versioned in `/schemas/json/`. Fixed two catalogued DTO drifts. Documented the `price` type drift (BigDecimal vs float64) as a known gap schema validation cannot catch. Its 2026-07-13 Update extends coverage from 2 to all 17 currently-published messages (fact-events and commands alike) across all six services. It fixes a real drift found along the way (`JwtCreatedEvent.userId`). It makes a schema-plus-contract-test mandatory for every new event from now on. See [CONTRIBUTING.md](CONTRIBUTING.md). |
| [0003](docs/adr/0003-outbox-pattern-auth-service.md) | Outbox pattern for auth-service | Accepted | Fixed `verifyEmail`'s publish-before-save ordering with a polled `outbox_events` table. Closed `inventory-service`'s equivalent risk (Go) the same way, as part of ADR-0007's RabbitMQ migration. Found and resolved a Flyway/Hibernate schema-duplication bug along the way, in ADR-0004. |
| [0004](docs/adr/0004-auth-service-schema-authority-fix.md) | auth-service schema authority fix | Accepted, extended by ADR-0011 | Fixes the schema duplication found in ADR-0003. `V1`/`V2` created tables in `public`, while Hibernate's `ddl-auto=update` silently created the real, actually-used copies in `auth_schema`. A `V3` migration recreates both tables in `auth_schema`, matching Hibernate's live schema exactly. `ddl-auto` is locked to `validate`. Left checking the other three services as explicit future work. See ADR-0011. |
| [0005](docs/adr/0005-secret-rotation.md) | Secret rotation and removal of hardcoded credentials | Accepted | Rotated three credentials hardcoded in `docker-compose.yml` since the project's first commit (public repo), for real, against the live Postgres/RabbitMQ. Replaced them with `${VAR}`/`.env`. Removed orphaned JWT config from three services that never consumed it. History was not rewritten; old values are treated as permanently compromised. |
| [0006](docs/adr/0006-gateway-circuit-breaker.md) | Circuit breaker in the API Gateway | Accepted | Added `sony/gobreaker`, one breaker per service (`auth`, `products`, `inventory`, `orders`; billing excluded, see ADR-0009). 5 consecutive failures open the breaker; the cooldown is 30s. Verified against real containers: stopping inventory-service made it fail fast, while the other three kept responding normally. |
| [0007](docs/adr/0007-remove-kafka-broker.md) | Remove Kafka broker (migrate to RabbitMQ) | Accepted, implemented | Removed Kafka from every service. Moved product.\*/stock.\*/order.\* events over to RabbitMQ topic exchanges. inventory-service's stock reservation outcome is now written through an Outbox table (see ADR-0003), instead of a direct post-commit publish. |
| [0008](docs/adr/0008-surefire-failsafe-test-split.md) | Separate unit and integration tests via Surefire/Failsafe | Accepted, partially superseded | Originally: renamed four test classes touching real Postgres/RabbitMQ to the `*IT` suffix, and moved them to `maven-failsafe-plugin`, across all four Spring services. Since updated (see the ADR's 2026-07-06 addendum): a later step of ADR-0007's Kafka-to-RabbitMQ migration replaced three of those four `*IT` classes with broker-agnostic behavior tests. Only billing-service's `BillingServiceApplicationIT` remains. `maven-failsafe-plugin` was removed from the other three services' `pom.xml`. |
| [0009](docs/adr/0009-billing-circuit-breaker.md) | Extend the API Gateway circuit breaker to billing-service | Accepted | Same structure as ADR-0006 (`sony/gobreaker`, 5 failures / 30s cooldown), applied to the one remaining entry left on the plain proxy. Closes ADR-0006's open Roadmap item. |
| [0010](docs/adr/0010-uniform-service-healthchecks.md) | Uniform health checks across all six services | Accepted | Every Docker `HEALTHCHECK` now targets each service's own unauthenticated `/health` endpoint, instead of `/actuator/health`. billing-service gained a `HealthController`/`SecurityConfig`. orders-service's broken `docker-compose.yml` override was removed. auth-service/inventory-service/api-gateway gained a `HEALTHCHECK` they never had. All six services verified `healthy` simultaneously for the first time. |
| [0011](docs/adr/0011-flyway-schema-authority-all-services.md) | Flyway as the single schema authority in every Spring Boot service | Accepted | Closes the gap ADR-0004 explicitly left open. `flyway-core` was silently missing from products-service and billing-service's `pom.xml`. This made their Flyway config dead, and let `ddl-auto=update` author their entire live schema, with visible drift from what the migrations specify. billing-service gets its first real migration. All four services now baseline correctly and run with `ddl-auto=validate` everywhere. |
| [0012](docs/adr/0012-ci-phase-2-real-infrastructure.md) | CI Phase 2 — real-infrastructure integration tests via service containers | Accepted | Added a new `integration` job in CI: a matrix of auth-service/orders-service/billing-service/inventory-service, each against its own fresh Postgres/RabbitMQ service-container pair. Restores three previously-removed `*IT` classes and adds new ones. Every hop is tested from at most one side, producer or consumer, never both, and never across a real process boundary. See ADR-0013. |
| [0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md) | CI Phase 3 — cross-service end-to-end tests via real running processes | Accepted | Two named jobs start multiple real services as actual processes against one shared Postgres/RabbitMQ pair. Both drive flows through public HTTP APIs only: `catalog-onboarding` (auth/products/inventory) and `order-lifecycle` (auth/orders/inventory/billing). This work surfaced and fixed a real bug: billing-service's Basic Auth never actually worked (403 regardless of credentials). |
| [0014](docs/adr/0014-notification-service.md) | Notification Service — first Python/FastAPI service | Accepted | Consumes `order.created` via a new RabbitMQ queue. Enriches it via a real HTTP call to a new minimal auth-service endpoint (`GET /users/{id}/notification-profile`). Persists an observable notification in a new `notification_schema`. No real email/SMS provider exists; one `FakeNotificationSender` implementation stands in. Found, but did not fix, the same missing-`.httpBasic()` defect class ADR-0013 fixed in billing-service, this time in auth-service — currently latent. |
| [0015](docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md) | billing-service joins the JWT broadcast pattern; auth-service's latent `.httpBasic()` gap fixed | Accepted, updated 2026-07-07 | auth-service originally got the same one-line `.httpBasic()` fix ADR-0013 applied to billing-service. Later this was simplified further to `denyAll()`, once it was clear no endpoint depended on any auth mechanism (see the ADR's Update). billing-service was the only service protected solely by Spring Boot's default Basic Auth. It now authenticates via the same Bearer JWT broadcast cache already used by products-service and orders-service (auth-service is the producer of that broadcast, not a consumer of it). New `JwtConsumerBehaviorTest`/`PaymentControllerSecurityTest`/`BillingJwtCreatedWiringIT` give this mechanism real regression coverage for the first time. |
| [0016](docs/adr/0016-shared-spring-parent-pom.md) | Shared Maven parent POM for the four Spring Boot services | Accepted | A new root `pom.xml` (inheritance only, no reactor) standardizes all four services on Spring Boot 3.2.12, previously split 3.2.0/3.2.12. It centralizes every dependency/plugin that was identical across all four by hand. This required changing Docker's build context to the repository root for all four services, so the parent resolves inside each build. |
| [0017](docs/adr/0017-notification-service-startup-resilience.md) | notification-service survives a slow/restarting RabbitMQ and Postgres | Accepted | Found while validating the end-to-end walkthrough against a real, freshly-started stack: the RabbitMQ consumer thread made exactly one connection attempt, and died silently on a real startup race. This left the container "healthy" but permanently unable to process any event. Both the RabbitMQ consumer and the Postgres schema-init step now retry, instead of giving up. |
| [0018](docs/adr/0018-persistence-strategy.md) | Persistence strategy — shared PostgreSQL instance, schema-per-service ownership | Accepted | Formally registers a decision that was implicit since the project's first commit: one Postgres instance, one schema per service, ownership enforced by convention rather than by database ACLs. Argues the architectural boundary is data ownership, not the physical instance. Every property this project demonstrates already holds without database-per-service. |
| [0019](docs/adr/0019-message-consumption-resilience.md) | Uniform message consumption resilience (limited retry, exponential backoff, dead-lettering) | Accepted | `products-service`, `orders-service`, and `billing-service` now share the same native Spring Boot listener retry policy: 3 attempts, exponential backoff, and a dead-letter exchange/queue per service. Eight listener methods that previously swallowed exceptions internally were fixed to propagate them, so container-level retry/DLQ actually applies to business-logic failures, not just malformed messages. `auth-service` has no consumer at all, so is out of scope. `notification-service` (Python) keeps its separate, unrelated gap. |
| [0020](docs/adr/0020-notification-domain-completion.md) | Complete the notification domain — consume `order.status-changed`, add a read-only API | Accepted | `notification-service` now consumes `order.status-changed` in addition to `order.created`. It reuses the prior notification's enriched user info instead of a second synchronous call, since the event carries no `userId`. It adds a read-only `GET /notifications/{orderId}`, closing the walkthrough's one direct-Postgres-access step. Found, but did not fix: `billing-service` never publishes a payment outcome event, so `order.status-changed` is never emitted for a payment transition. |
| [0021](docs/adr/0021-payment-outcome-integration.md) | Payment outcome integration — billing-service publishes `payment.approved`/`payment.failed`, orders-service reacts | Accepted | Closes the gap ADR-0020 found. `billing-service` now publishes a payment outcome event once `PaymentService.processPayment` resolves it. A new `PaymentEventsConsumer` in `orders-service` finally calls `OrderService.handlePaymentReceived`/`handlePaymentFailed`, previously unreachable since ADR-0001 removed their incorrectly-typed predecessor. Also fixed a latent bug those methods had: `previousState` was hardcoded to `PENDING` instead of the order's real prior state, found by the first tests that ever exercised them. `notification-service` required no changes. |
| [0022](docs/adr/0022-notification-service-consumption-resilience.md) | notification-service consumption resilience (retry, backoff, dead-lettering) | Accepted | Closes notification-service's last remaining gap from ADR-0019/ADR-0017. Its `pika` consumer used to ack even on failure, silently dropping malformed or unexpected-error messages. It now retries up to 3 times with exponential backoff, using a RabbitMQ retry queue with a per-message TTL, not a sleep or poll, before dead-lettering. This matches the same numbers and conceptual behavior as the Spring services, built on different primitives, since Pika has no retry-template equivalent. Found, and since fixed (see ADR-0022's Update): `products-service`'s `handleUserVerified` had no role filter, so every buyer's `user.verified` event was dead-lettered. |
| [0023](docs/adr/0023-notification-service-persistence-evolution-strategy.md) | Notification Service Persistence Evolution Strategy | Accepted | Formally reviews and closes the "no Alembic" gap left open since ADR-0014. `scripts/init.sql` has not changed once across four ADRs of functional growth. A comparison against `inventory-service`, whose own idempotent init.sql *has* evolved twice, safely, with no versioned tool, shows the current approach has headroom beyond what notification-service has needed. Keeps the idempotent script. Documents concrete criteria for reopening the decision if the schema outgrows it. |
| [0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md) | Distributed tracing via propagated correlation identifiers, not a tracing backend | Accepted | CorrelationId/RequestId/MessageId propagate through HTTP headers and native AMQP message properties, not a new wire format. All seven services log them in a consistent structured format. Two small shared modules exist: `correlation-commons` for the four Spring services, `correlation-commons-go` for the two Go services. This is a deliberate, narrow exception to this project's "no shared library" convention, since this code has no business meaning and must stay identical to hold the CorrelationId contract. Found and fixed two real bugs: notification-service's retry path silently dropped correlation/message ids, and the Go shared module's logger had a service name hardcoded from before it had a second consumer. See [docs/architecture/observability.md](docs/architecture/observability.md) for the full design. A 2026-08-02 Update adopts OpenTelemetry and a Jaeger backend alongside this design, not instead of it: every service now also exports spans, with a real login producing a single trace spanning 6 services and 13 spans across three languages. Outbox-mediated publishes (orders-service, inventory-service, billing-service) don't yet carry a trace across their write-to-publish gap — a documented, not-yet-closed residual gap, unlike CorrelationId's own envelope trick for the same gap. |
| [0025](docs/adr/0025-gateway-transparent-routing.md) | Gateway transparent routing — every service is self-namespaced | Accepted | Closes the `inventory-service` 404-through-the-gateway bug ADR-0024 found. The Gateway no longer strips any service prefix; it forwards the incoming path unchanged. `auth-service`/`products-service`/`orders-service`/`billing-service` each gained a `server.servlet.context-path` matching their own Gateway segment (`inventory-service` was already self-namespaced). Every direct caller of those four services (Dockerfile `HEALTHCHECK`s, CI readiness checks, `e2e-tests`, the Postman collection) was updated in lockstep. The Postman collection now has parallel `Via Gateway (primary)` and `Direct (debug)` folder trees. |
| [0026](docs/adr/0026-frontend-thin-client.md) | SvelteKit frontend as a thin client over the API Gateway | Accepted | A new `frontend/` (SvelteKit + TypeScript, SSR disabled, `adapter-node`) consumes only the Gateway. Building it surfaced and fixed four real defects no prior `curl`-based client could catch: CORS wired but shadowed by Spring Security in two services and entirely missing in four; `products-service`'s JWT filter terminating requests before `permitAll()` paths could ever be reached; its catalog endpoints unreachable by any buyer token by design; and the Gateway echoing `X-Correlation-Id`/`X-Request-Id` twice under one header. `notification-service` gains a Gateway route, closing the one gap ADR-0025 left open. |
| [0027](docs/adr/0027-jwt-principal-as-sole-identity-source.md) | JWT principal as the sole identity source for orders/products | Accepted | Closes a Critical Roadmap item. `orders-service`/`products-service` derived business identity from a client-supplied `X-User-Id` header, instead of the authenticated JWT principal. This let any valid token impersonate any other user by changing one header, confirmed live with a real two-buyer impersonation before the fix. Both controllers now derive identity exclusively from `@AuthenticationPrincipal`. `X-User-Id` no longer exists anywhere in either service's request path. Its 2026-07-10 Update extends the same pattern to `billing-service`'s `PaymentController`, all four endpoints, closing a separate High Roadmap item. |
| [0028](docs/adr/0028-notification-service-authentication.md) | notification-service authentication and ownership check | Accepted | Closes a High Roadmap item, a real IDOR: `GET /notifications/{orderId}` had no authentication at all. notification-service gains its own JWT broadcast cache, consuming `jwt.created` for the first time, the same pattern as every Spring service's `JwtConsumer`. It now requires the caller to be the order's own buyer, read from that order's real `order.created` payload, or 403 otherwise. Confirmed live with a real two-buyer test. |
| [0029](docs/adr/0029-order-fulfillment-lifecycle.md) | Activating the order fulfillment lifecycle (ship/deliver) | Accepted | Closes a Medium Roadmap item. `SHIPPED`/`DELIVERED` were configured state machine transitions with no code path to reach them. Adds `POST /{orderId}/ship`, the project's first role-gated, not ownership-gated, endpoint, using the new `ADMIN` platform-operations role. Adds `POST /{orderId}/deliver`, ownership-gated like `cancelOrder`. Replaces `canCancel()`'s hand-written eligibility list with a single `isTransitionAllowed` derived from the state machine's own configured graph. Closes a self-registration-as-admin path in auth-service's `/signup`. Live validation caught and fixed a real `previousState`-after-mutation bug in `cancelOrder` and both new methods. No new event type, no new table. |
| [0030](docs/adr/0030-deterministic-payment-provider.md) | Deterministic payment provider | Accepted | Closes a Medium Roadmap item. `billing-service`'s `PaymentProvider`/`PaymentMockService` abstraction was dead code; `PaymentService.processPayment` decided approval with `Math.random() < 0.9` directly instead. `PaymentService` now depends exclusively on `PaymentProvider`. The fake's existing amount-parity rule, kept rather than replaced with a hash, makes the same order always resolve the same way. Also removed a second, unused `PaymentResult` class sharing `PaymentService`'s package, a real name-collision risk once the real one got imported. `docs/walkthrough.md`/`docs/sequence-diagram.md`/`postman/README.md` no longer hedge with "either outcome". |
| [0031](docs/adr/0031-single-source-of-truth-for-payment-creation.md) | Single source of truth for payment creation | Accepted | Closes a Low Roadmap item. `PaymentService.processPayment` had an "API fallback" branch that created a new `Payment` on the spot when none existed, and it never set `userId`. Investigation found no legitimate caller ever exercised it; the frontend, walkthrough, and Postman collection always process an order that already went through `order.created`. Removed the fallback, and the dead `amount` parameter it alone used, instead of patching the bug. A missing `Payment` is now a `404` domain error via a new `PaymentNotFoundException`. Also removed `POST /api/payments/pending`, an already-dead second alias for `/process`. |
| [0032](docs/adr/0032-accept-order-state-machine-hybrid.md) | Accept the hybrid Spring State Machine pattern in orders-service | Accepted | Closes an Architectural-note Roadmap item without changing code. Investigated making the state machine a live authority (low value: no guards/extended state, single replica), and dropping the framework for a plain transition table (low risk, but no functional gain). Decided the migration cost of either outweighs a benefit that resolves no active bug. Also opens a new, unrelated Low Roadmap item found along the way: `Order` has no `@Version` column, so concurrent writes from multiple RabbitMQ consumers and HTTP endpoints have no conflict detection. |
| [0033](docs/adr/0033-optimistic-locking-on-order-and-payment.md) | Optimistic locking on Order and Payment | Accepted | Closes the `@Version` Low Roadmap item ADR-0032 opened. Adds `@Version` to `Order` and `Payment` only, not `Product`/`User`, which have no real concurrent-writer path. Backs it with `saveAndFlush` at the point each transition is persisted, so a conflict is never discovered after its event already published. Adds a `409 Conflict` mapping in both services. Documents evaluating and rejecting pessimistic locking for this domain's current low-contention, event-driven shape, with explicit criteria for revisiting that later. |
| [0034](docs/adr/0034-payment-compensation-saga.md) | Payment compensation saga for approved-but-unfulfillable orders | Accepted | Closes a Medium Roadmap item. A `payment.approved` arriving for an order already `INVENTORY_FAILED`/`CANCELLED` was silently swallowed, leaving `Payment` wrongly `APPROVED` with nothing to refund it. Evaluated and rejected synchronous compensation, immediate reversion, and Saga Orchestrated. Adopted a choreographed saga consistent with the rest of the domain instead: Orders publishes a `RefundPaymentCommand`, a command not a fact-event, after reactivating the pre-existing, never-wired `REFUNDING`/`INITIATE_REFUND`/`REFUND_COMPLETED`. Billing alone decides and owns `Payment`, publishing `payment.refunded`/`payment.refund.failed` back. No `REFUND_PENDING`, no refund-specific transaction id; each was deliberately rejected and documented, not omitted by oversight. Outbox was also deliberately not adopted for the new publish alone, to avoid an asymmetry with this service's other best-effort publishes. A 2026-07-15 Update closes that gap, once a broader analysis extended Outbox to all four of `orders-service`'s publishes together (see [ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)). |
| [0035](docs/adr/0035-reject-dto-code-generation-from-json-schema.md) | Reject DTO code generation from JSON Schema, at the project's current scale | Accepted | Closes a Low Roadmap item by decision, not implementation. Measured every schema's real git history (one content-change each, ever) and the one real DTO drift ever found, already caught by its own contract test. Concluded that `jsonschema2pojo`/`go-jsonschema`/`datamodel-code-generator` would cost three new build toolchains and the intentional-partial-consumer DTO pattern already in deliberate use, for a drift rate of one occurrence. This is a cost/benefit conclusion, not a rejection of the technique. Documents explicit, measurable criteria (event count, schema churn, a second missed drift, partial-consumer DTOs becoming the exception) that would reopen it. |
| [0036](docs/adr/0036-metrics-via-prometheus-grafana.md) | Quantitative observability via Prometheus and Grafana | Accepted | Narrows ADR-0024's bundled rejection of Prometheus/Grafana; that cost analysis targeted a full tracing backend, which these two don't actually need. Adopts them for the aggregate questions CorrelationId logging was never meant to answer: error rate, latency, queue depth, business volume. RabbitMQ's own `rabbitmq_prometheus` plugin and each Spring service's HikariCP pool cover RabbitMQ and Postgres connection visibility, with zero new exporter containers. The two Go services needed one small custom HTTP-metrics middleware, since `promhttp` alone, unlike Micrometer, doesn't auto-instrument request rate/latency. Five deliberately-scoped business counters, dashboards provisioned as code. No Alertmanager, no Loki, no per-event metric spam. ADR-0024's rejection of a full tracing backend (OpenTelemetry/Jaeger/Zipkin) stays unchanged. A 2026-07-15 Update adds a sixth counter, `inventory_idempotent_duplicate_detected_total{operation}`, answering a different kind of question than the original five: whether a known, accepted residual risk, duplicate command delivery caught by `inventory-service`'s idempotency cache, is actually happening at runtime, not just theoretically possible. A second 2026-07-15 Update adds a seventh, `jwt_cache_lookup_total{outcome}` (hit/miss/expired) in all four broadcast-JWT-cache services, landed together with ADR-0039's TTL implementation. |
| [0037](docs/adr/0037-consolidated-outbox-pattern-specification.md) | Consolidated Outbox Pattern specification | Accepted | auth-service's (ADR-0003) and inventory-service's (an aside inside ADR-0007, never its own ADR) Outbox implementations agreed on everything structural, but had never been specified as one concern. They drifted where nothing pinned them down: neither had a metric on the publisher itself, and logging was inconsistent in opposite directions between the two languages. Harmonizes both: structured, correlated logging on every path, and two new metrics, `outbox_events_published_total`/`outbox_publish_lag_seconds`, following ADR-0036's convention. Adopts an explicit adoption criterion for future decisions about extending Outbox elsewhere: impact of loss on a cross-service business process, not caller observability or an unrelated retry mechanism. Did not itself extend Outbox to any new service at the time. Two 2026-07-15 Updates close the `orders-service` Roadmap item ADR-0034 opened and the `billing-service` gap, extending Outbox to all four publishes of each service. Every publish in the system that qualifies under this ADR's own criterion now has it. A third 2026-07-15 Update formalizes an outbox retention policy, no automated cleanup, with explicit criteria for revisiting, and explicitly rejects CDC/Debezium in favor of the existing 5-second poll. |
| [0038](docs/adr/0038-infrastructure-startup-resilience.md) | Infrastructure startup resilience | Accepted, updated 2026-07-20 | `orders-service` crashed on boot when RabbitMQ's Erlang node answered its own healthcheck before its AMQP listener accepted connections, the same class of bug ADR-0017 claimed the four Spring services were already immune to. Verified live instead of trusting that claim: confirmed both a listener-less and a listener-bearing Spring service already tolerate this race via Spring Boot's own autoconfigured `SimpleMessageListenerContainer`. The actual cause was `orders-service`'s own redundant, imperative `RabbitMQInitializer`, since deleted. The same investigation found and closed a second, unrelated, more severe gap, live-tested the same way: all four Spring services crashed immediately with zero retry if Postgres was slow instead of RabbitMQ, fixed with `spring.datasource.hikari.initialization-fail-timeout=30000` per service. `inventory-service`'s own Postgres connection gained the same bounded retry its RabbitMQ connection already had, closing an internal asymmetry within that one service. Its 2026-07-17 Update tackled the neighboring, previously-untested question: what happens when an *already-connected* client's broker restarts mid-run, as `kind`'s no-PersistentVolume RabbitMQ (ADR-0040) does on every pod restart. Verified live that Java's automatic connection/topology recovery already handles it. Closed the one gap that Update believed it had found, `inventory-service`'s Go client never subscribed to `NotifyClose`, with a reconnect supervisor. Gave `notification-service`'s `pika` consumer an explicit heartbeat, and added a Progress Watchdog to all six services. Its 2026-07-20 Update found that supervisor's own validation had a gap: it only tested the TCP connection dying, never the exchange itself disappearing, the actual Kubernetes failure mode. Re-testing against real topology loss found the Go supervisor reconnected the socket but never redeclared `order.exchange`/`product.exchange`, leaving every consumer's `QueueBind` failing forever. Fixed, and proven this time by deleting the exchange itself, not just closing the connection. The same fix surfaced two more real bugs in the same file: the Outbox publisher's fire-and-forget `Publish` could silently mark an event "published" that the broker never actually accepted, and a single permanently-bad outbox row could poison an entire poll batch's channel. Both closed with publisher confirms and per-event channel validation. `orders-service`/`billing-service`'s JWT filters were standardized to match `products-service`'s already-correct behavior. A reconnection observability contract, auto-reconnect, redeclare topology, progress-based liveness, is now stated explicitly. New `rabbitmq_reconnect_attempts_total`/`rabbitmq_topology_setup_total{outcome}`/`messaging_last_progress_timestamp_seconds` metrics apply across all six services. A new Grafana dashboard (`EasyDora / Resilience`) and a completed Postman collection cover the payment-compensation saga (ADR-0034) end to end. `restart: on-failure` was added on every `docker-compose.yml` service, so the same self-healing applies outside Kubernetes too. |
| [0039](docs/adr/0039-jwt-broadcast-cache-restart-and-ttl.md) | Broadcast JWT cache — restart-recovery limitation and token-lifetime TTL | Accepted | Centralizes a limitation previously only mentioned as an aside inside ADR-0027 and ADR-0028, that a service restart wipes its JWT cache and recovery means the user logging in again, into its own dedicated decision record. Identifies one asymmetry never previously documented: none of the four broadcast-JWT caches expires an entry when the underlying JWT's own `expiresIn` elapses; only a restart clears it. Keeps the broadcast-cache model as-is. Explicitly rejects local JWKS verification and a persisted/shared cache, with objective criteria for when a shared cache like Redis would earn its place. Decides to add an `expiresIn`-based TTL to close the asymmetry. A 2026-07-15 Update implements the TTL, and the cache-miss-by-restart test, in all four services: `orders-service`, `products-service`, `billing-service` (a new `JwtUserInfo` constructor overload, evicted lazily on the next read that finds an expired entry) and `notification-service` (an optional `expires_at` on `JwtCache.add`). Landed together with `jwt_cache_lookup_total` (see ADR-0036's second Update). |
| [0040](docs/adr/0040-minimal-kubernetes-kind-deployment.md) | Minimal Kubernetes (kind) deployment as a parallel execution platform, alongside Docker Compose | Accepted | Docker Compose already abstracts several properties of a declarative deployment platform: continuous reconciliation, health checks driving availability/recovery, configuration/application separation, declarative resource representation. A minimal `kind` cluster (`k8s/`) makes them explicit without changing the application. Every service already resolves the others by hostname and already exposes a real `/health` endpoint, so the same images run unchanged, only reused via Kubernetes Service DNS and readiness/liveness probes. Scope: one Namespace, Kustomize base with no overlays, a single PersistentVolumeClaim (Postgres only), Deployment rather than StatefulSet for Postgres/RabbitMQ, since their real value needs more than one replica, which this project doesn't have. `kind` was chosen over Minikube/k3d for running upstream Kubernetes components without a distribution's own substitutions or default-enabled add-ons. Explicit Non-Goals: HA, autoscaling, GitOps, service mesh, cloud provisioning, CI against kind. Docker Compose remains the recommended environment for local development. A 2026-07-17 Update adds the frontend, reusing the same image already built for Docker Compose unmodified. |
| [0041](docs/adr/0041-kafka-rabbitmq-broker-benchmark.md) | Kafka vs. RabbitMQ broker benchmark | Accepted | ADR-0007 removed Kafka and kept RabbitMQ as the sole broker, reasoned entirely from workload properties, never measured. A standalone benchmark harness (`benchmarks/broker-comparison/`, its own `docker-compose.yml` and Go module, never part of any service's runtime) ran both brokers side by side on the same machine: publish-then-wait-for-ack throughput (the same call pattern `OutboxPublisher` uses everywhere it exists) and behavior across a hard broker-container restart. RabbitMQ measured roughly 14x the throughput and recovered automatically from the restart; Kafka's default client did not resume publishing within the same test window. A second-broker adapter living inside a real service was considered and rejected — no concrete use case (e.g. offset-based replay) exists today to justify one, so the comparison stays outside the production topology entirely. One new, previously undocumented finding: RabbitMQ's automatic recovery lost 2 of 207 broker-acknowledged messages under the hard kill, a narrow gap in the outbox's at-least-once guarantee (ADR-0037) left open for a future ADR if it recurs. |

## Roadmap

<details>
<summary>Click to expand — closed items marked <code>[x]</code>, open items <code>[ ]</code></summary>

- [x] **Opened 2026-07-04.** CI pipeline, Phase 1
      (`.github/workflows/ci.yml`): parallel build/vet/unit-test jobs for
      all seven services, no service containers.
- [x] **Opened 2026-07-04.** Shared parent POM across the four Spring
      services (auth, products, orders, billing) — see
      [ADR-0016](docs/adr/0016-shared-spring-parent-pom.md). This also
      standardized all four on Spring Boot 3.2.12, previously split
      3.2.0/3.2.12, and required moving Docker's build context to the
      repository root for these four services.
- [x] **Opened 2026-07-04.** api-gateway: billing-service now has a
      circuit breaker like every other implemented entry. See ADR-0009.
      This closes the gap ADR-0006 left open the same day it was added.
- [x] **Opened 2026-07-04.** products-service, orders-service,
      billing-service: limited retry (3 attempts, exponential backoff),
      plus a dead-letter exchange/queue per service. This is configured
      natively via Spring Boot's
      `SimpleRabbitListenerContainerFactoryConfigurer` and a
      `RepublishMessageRecoverer`, with no custom retry code. Eight
      listener methods across four consumer classes used to swallow
      business exceptions internally, so the container never saw a
      failure to retry. These methods were fixed to propagate exceptions.
      `auth-service` has no `@RabbitListener` at all, so was out of
      scope. See
      [ADR-0019](docs/adr/0019-message-consumption-resilience.md).
- [x] **Opened 2026-07-04.** inventory-service (Go): Outbox Pattern
      implemented for stock reservation. See
      [ADR-0007](docs/adr/0007-remove-kafka-broker.md).
      `ReserveStockForOrder` writes the `stock.reserved`/`stock.insufficient`
      event to `inventory_schema.outbox_events`, in the same Postgres
      transaction as the reservation itself. A poller then publishes it
      to RabbitMQ. This closes the "event lost if the process crashes
      right after commit" gap. This does not make message redelivery
      itself idempotent, a separate, still-open concern, see below. It
      only guarantees the reservation outcome is never silently lost
      once committed.
- [x] **Opened 2026-07-05.** billing-service was the only service
      protected solely by Spring Boot's default Basic Auth. It now
      authenticates via the same Bearer JWT broadcast cache already used
      by products-service and orders-service, fully replacing Basic Auth.
      See [ADR-0015](docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md).
      auth-service is the producer of that broadcast, not a consumer of
      it, and remains on `.httpBasic()`, currently unexercised, since
      every one of its endpoints is `permitAll()`.
- [x] **Opened 2026-07-05.** All six services' health checks fixed and
      unified. See [ADR-0010](docs/adr/0010-uniform-service-healthchecks.md).
      This started from billing-service's and orders-service's
      Dockerfiles hard-coding port 8082, products-service's port. This
      uncovered two deeper bugs once the ports were corrected: every
      Spring service's `HEALTHCHECK` targeted `/actuator/health`, a path
      that isn't uniformly exposed or `permitAll()`-ed, instead of each
      service's own working `/health` endpoint. Also, orders-service's
      `docker-compose.yml` healthcheck override was a no-op: a
      YAML-folding bug swallowed the real `curl` command in a shell
      comment, so it always reported "healthy" regardless of the app's
      actual state. Also added: a `HealthController`/`SecurityConfig`
      for billing-service, which had neither, and a `HEALTHCHECK` for
      auth-service, inventory-service, and api-gateway, which never had
      one. All six services now verified `healthy` simultaneously.
- [x] **Opened 2026-07-06.** CI pipeline, Phase 2
      (`.github/workflows/ci.yml`): wiring and Outbox integration tests
      against real Postgres/RabbitMQ service containers. See
      [ADR-0012](docs/adr/0012-ci-phase-2-real-infrastructure.md).
- [x] **Opened 2026-07-07.** Notification service (FastAPI + RabbitMQ
      consumer): consumes `order.created`, enriches it via a real HTTP
      call to a new minimal auth-service endpoint, and persists an
      observable notification in a new `notification_schema`. See
      [ADR-0014](docs/adr/0014-notification-service.md).
- [x] **Opened 2026-07-07.** CI pipeline, Phase 3
      (`.github/workflows/ci.yml`): cross-service end-to-end tests. These
      tests start multiple real services as actual running processes
      against one shared Postgres/RabbitMQ pair, and drive each flow
      through public HTTP APIs only. Two groups: `catalog-onboarding`
      (auth-service, products-service, inventory-service) and
      `order-lifecycle` (auth-service, orders-service, inventory-service,
      billing-service). See
      [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md). A third
      group, `notification-flow` (auth-service, notification-service),
      see [ADR-0014](docs/adr/0014-notification-service.md).
- [x] **Opened 2026-07-07.** End-to-end integration tests across the
      implemented services. See CI Phase 3 above (`catalog-onboarding`,
      `order-lifecycle`, and `notification-flow` groups).
- [x] **Opened 2026-07-07.** notification-service's lack of a versioned
      migration tool, no Alembic equivalent to Flyway, is a gap inherent
      since the service's own creation. This gap was formally reviewed,
      not left indefinite. `scripts/init.sql` has not changed once across
      four ADRs of functional growth. A comparison against
      inventory-service, whose own idempotent init.sql *has* evolved
      twice, safely, with no versioned tool, shows the current approach
      has headroom beyond what notification-service has needed so far.
      The decision was kept as-is, with concrete criteria for reopening
      it if the schema outgrows this approach. See
      [ADR-0023](docs/adr/0023-notification-service-persistence-evolution-strategy.md).
- [x] **Opened 2026-07-08.** `order.status-changed` (`orders-service`,
      published on `order.exchange`, see
      [ADR-0001](docs/adr/0001-messaging-wiring-audit.md)) is now
      consumed by `notification-service`, the same way it already
      consumes `order.created`. It carries no `userId` of its own, so
      notification-service reuses the email/name already captured by
      that order's `order.created` notification, instead of a second
      auth-service call. Every event produces its own new notification
      row; none are ever overwritten. See
      [ADR-0020](docs/adr/0020-notification-domain-completion.md).
- [x] **Opened 2026-07-08.** notification-service now has a minimal,
      read-only public API: `GET /notifications/{orderId}`. This mirrors
      the same "smallest endpoint for the use case" principle ADR-0014
      already applied to auth-service's `notification-profile` endpoint.
      This closes the gap that previously made step 9 of
      [docs/walkthrough.md](docs/walkthrough.md) the one step in that
      walkthrough requiring direct Postgres access. No direct database
      query is needed anywhere in that walkthrough anymore. Same
      [ADR-0020](docs/adr/0020-notification-domain-completion.md) as the
      item above.
- [x] **Opened 2026-07-08.** `billing-service` now publishes
      `payment.approved`/`payment.failed` once a payment resolves.
      `orders-service`'s own `handlePaymentReceived`/`handlePaymentFailed`,
      previously unreachable dead-code-adjacent methods (see ADR-0001,
      finding 5), finally have a real caller: `PaymentEventsConsumer`.
      This drives the order into `PAYMENT_APPROVED`/`PAYMENT_FAILED`, and
      publishes `order.status-changed` through the same path stock
      reservation already used. `notification-service` required no
      changes to react to it. See
      [ADR-0021](docs/adr/0021-payment-outcome-integration.md).
- [x] **Opened 2026-07-08.** notification-service (Python) now has the
      same conceptual retry/backoff/DLQ policy as the Spring services:
      limited retries, exponential backoff, dead-lettering after the
      retry budget is exhausted. This is built natively on RabbitMQ, a
      retry queue with a per-message TTL and `x-dead-letter-exchange`
      back to the original exchange, then a terminal dead letter
      exchange/queue, since Pika has no built-in retry template
      equivalent to Spring AMQP's. No message is silently dropped or
      retried forever anymore. See
      [ADR-0022](docs/adr/0022-notification-service-consumption-resilience.md).
- [x] **Opened 2026-07-08.** notification-service's RabbitMQ consumer and
      Postgres schema-init step now both retry a failed initial
      connection instead of dying silently. Found as a real, blocking
      defect while validating the end-to-end walkthrough against a real
      freshly-started stack. See
      [ADR-0017](docs/adr/0017-notification-service-startup-resilience.md).
      This is distinct from the per-message retry/DLQ gap above: this was
      about the consumer never even getting a chance to process *any*
      message.
- [x] **Opened 2026-07-09.** Distributed tracing via propagated
      CorrelationId/RequestId/MessageId across HTTP and RabbitMQ, in a
      consistent structured logfmt format across all seven services. See
      [ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)
      and [docs/architecture/observability.md](docs/architecture/observability.md).
- [x] **Opened 2026-07-09.** `api-gateway`'s reverse proxy no longer
      strips the service prefix before forwarding. It forwards the
      incoming path unchanged. Every service (`auth-service`,
      `products-service`, `orders-service`, `billing-service` via
      `server.servlet.context-path`; `inventory-service` already this
      way) is self-namespaced under its own Gateway segment. This closes
      the `inventory-service` 404 found while live-validating ADR-0024's
      CorrelationId propagation through the gateway. See
      [ADR-0025](docs/adr/0025-gateway-transparent-routing.md).
- [x] **Opened 2026-07-09.** `notification-service` now has a Gateway
      route (`/notification`, self-namespaced the same way as the other
      five services). This closes the gap ADR-0025 explicitly left open
      the same day it was found. Found while building the frontend: CORS
      was wired but shadowed by Spring Security in two services, and
      entirely missing in four. `products-service`'s JWT filter
      terminated requests before a `permitAll()` catalog endpoint could
      ever be reached by a buyer's token. The Gateway also echoed
      `X-Correlation-Id`/`X-Request-Id` twice under one header. All
      fixed. See [ADR-0026](docs/adr/0026-frontend-thin-client.md).
- [x] **Opened 2026-07-10.** `orders-service` now rejects a `SELLER`
      buying their own product (`400 Bad Request`, "Cannot purchase your
      own product"). A `SELLER` can still buy normally, including other
      sellers' products. A `BUYER` is unaffected either way. A new
      consumer builds a minimal `orders_schema.product_ownership`
      (`product_id`, `seller_id`, nothing else) from `product.created`,
      the same event-projection pattern `inventory-service` already
      uses. No synchronous call to products-service was added. Found
      while building the frontend's checkout flow, deliberately not
      implemented as part of
      [ADR-0026](docs/adr/0026-frontend-thin-client.md). Closed the same
      day as a natural domain evolution, not a new architectural
      decision. No new ADR.
- [x] **Opened 2026-07-10 (Critical).** `orders-service` and
      `products-service` trusted the `X-User-Id` header directly, with no
      cross-check against the authenticated JWT's own claims. Any request
      carrying a valid token could claim to be any other `userId`, simply
      by setting a different header value. Fixed by deriving identity
      exclusively from `@AuthenticationPrincipal`, the principal
      `JwtAuthenticationFilter` already populates, in both controllers.
      This removes `X-User-Id` from the request path entirely. See
      [ADR-0027](docs/adr/0027-jwt-principal-as-sole-identity-source.md),
      which includes a live before/after reproduction of the
      impersonation this closes.
- [x] **Opened 2026-07-10.** `docker-compose.yml` set
      `SPRING_JPA_HIBERNATE_DDL_AUTO=update` for all four Spring services,
      overriding `application.properties`' `validate` at runtime. The
      containers never actually ran under Flyway-only schema management,
      contradicting [ADR-0004](docs/adr/0004-auth-service-schema-authority-fix.md)/
      [ADR-0011](docs/adr/0011-flyway-schema-authority-all-services.md).
      Root cause traced via git history: the override was added in commit
      `5fbdad5` (2025-10-19), months before Flyway existed in this project
      at all (ADR-0004 landed 2026-07-04). This was orphaned pre-Flyway
      scaffolding nobody audited when the properties files were switched
      to `validate`. Not a compensating fix for any real migration/entity
      gap. Fixed by removing the override. Empirically validated by
      wiping the dev Postgres volume and running `docker compose up
      --build` for all four services. Each ran its Flyway migrations from
      an empty schema and started cleanly under `validate`, with no
      Hibernate schema error. No new ADR: this corrects the running
      containers to match a decision ADR-0004/ADR-0011 already made, not
      a new one.
- [x] **Opened 2026-07-10.** `notification-service`'s
      `GET /notifications/{orderId}` had no authentication or
      authorization check at all. Anyone who knew or guessed an
      `orderId` (a UUID) could read that order's notification history,
      including the buyer's name and email. Fixed by giving
      notification-service its own JWT broadcast cache, consuming
      `jwt.created` for the first time, and requiring the caller to be
      the order's own buyer, compared against the real `order.created`
      notification's `userId`, never a client header, or 403 otherwise.
      See [ADR-0028](docs/adr/0028-notification-service-authentication.md).
- [x] **Opened 2026-07-10.** `billing-service`'s `PaymentController`
      had no ownership checks on any endpoint. `GET /api/payments`
      returned every payment in the system. `GET /api/payments/{id}`
      and `GET /api/payments/order/{orderId}` never confirmed the caller
      was the payment's own buyer. `DELETE /api/payments/{id}` deleted
      any payment with no check. Fixed by scoping `GET /api/payments` to
      the authenticated principal's own payments, and adding an
      ownership check (403 for a non-owner) to the other three. This
      extends [ADR-0027](docs/adr/0027-jwt-principal-as-sole-identity-source.md)'s
      JWT-principal-as-sole-identity-source pattern to billing-service
      (see that ADR's Update section).
- [x] **Opened 2026-07-10.** `products-service`'s `SecurityConfig`
      had `/debug/**` fully `permitAll()`. `GET /debug/tokens`
      triggered `JwtAuthenticationFilter.listTokens()`, logging every
      cached user's email, an anonymous way to enumerate active
      sessions. Fixed by removing `DebugController` and the `/debug/**`
      permitAll rule entirely, chosen over profile-gating or admin auth
      for the smallest attack surface and simplest fix consistent with
      this project's minimalism. An anonymous request now gets 403
      before Spring MVC would even resolve a handler. No new ADR: a
      removal, not a new architectural decision. A structurally
      identical `/debug/tokens` (plus `/debug/buyers`, which dumps every
      buyer row, and a no-op `/debug/clear-tokens`) exists in
      `orders-service`'s `OrderDebugController`, under the same
      `permitAll()` pattern. Found while fixing this item, but out of
      its scope, not fixed here. Tracked below as a new item.
- [x] **Opened 2026-07-10.** `orders-service`'s `OrderDebugController`
      had the same unauthenticated `/debug/**` surface products-service's
      equivalent had just been fixed for above: `GET /debug/tokens`
      (logged cached user emails), `GET /debug/buyers` (returned every
      buyer row, real PII, not just a count), and a no-op
      `POST /debug/clear-tokens`. Also found while fixing this: the
      controller's class-level `@RequestMapping("/debug")` combined with
      each method's own `/debug/...` mapping duplicated the path segment.
      The real, live-confirmed endpoint was `/debug/debug/buyers`, not
      `/debug/buyers`. This is moot now, but is the actual reason a quick
      manual `curl /debug/buyers` during earlier review would have found
      nothing and looked already safe. Fixed the same way as
      products-service: `OrderDebugController` and the `/debug/**`
      permitAll rule removed entirely. Confirmed live: 403 on all three
      real paths. No new ADR.
- [x] **Opened 2026-07-10, closed 2026-07-11 (Medium).**
      `OrderStateMachineConfig` wires real transitions for `SHIPPED`/
      `DELIVERED` (`SHIP_ORDER`/`DELIVER_ORDER`), but no code anywhere in
      `orders-service` ever sends either event. Both states are
      configured but structurally unreachable. Separately,
      `OrderService.canCancel()` allows attempting a cancellation from
      `PAYMENT_APPROVED`, but the state machine's real transition table
      only accepts `CANCEL_ORDER` from `PENDING`/`PROCESSING`/
      `INVENTORY_RESERVED`. A cancel attempt from `PAYMENT_APPROVED`
      isn't cleanly rejected upfront; it fails later with "event not
      accepted" once the mismatch is hit. Two different sources of truth
      exist for the same business rule, and they disagree. Resolved by
      activating both transitions: `POST /{orderId}/ship`, gated by the
      new `ADMIN` platform-operations role, and `POST /{orderId}/deliver`,
      ownership-gated like `cancelOrder`. This replaced deleting the dead
      states. Also replaced `canCancel()` with a single
      `isTransitionAllowed(state, event)`, derived directly from the state
      machine's own configured transition graph, reused by cancel/ship/
      deliver alike. Live validation against a real running stack also
      caught and fixed a related, previously undetected bug: `cancelOrder`,
      and the two new methods copied from its pattern, read
      `previousState` from the same `Order` entity *after* calling
      `sendEvent`. This mutates that same Hibernate-managed instance in
      place, within the shared transaction. Every `order.status-changed`
      event these methods ever published had `previousState == newState`.
      See [ADR-0029](docs/adr/0029-order-fulfillment-lifecycle.md).
- [x] **Opened 2026-07-10, closed 2026-07-11 (Medium).** `billing-service`
      had a `PaymentProvider` interface and a `PaymentMockService`
      implementing it (`service/provider/`). But `PaymentService.
      processPayment` never called either; it inlined
      `Math.random() < 0.9` directly instead. The abstraction was dead
      code, and the actual approval logic was non-deterministic, with no
      seam to make it reproducible. Resolved by wiring `PaymentService` to
      depend exclusively on `PaymentProvider` (constructor injection,
      `PaymentMockService` is `@Primary`), instead of deciding anything
      itself. Kept `PaymentMockService`'s existing amount-parity rule,
      already deterministic, just never called, rather than switching to
      a hash of `orderId`. This was simpler to explain. The interface's
      `orderId` parameter was fixed from `Long` to `String`, to match the
      real domain type regardless. Also removed a second, entirely unused
      `PaymentResult` class (`service/PaymentResult.java`, same package as
      `PaymentService`), a real name-collision risk once the real
      `provider.PaymentResult` got imported, found in the same area.
      `docs/walkthrough.md`, `docs/sequence-diagram.md`, and
      `postman/README.md` no longer hedge with "either outcome". This
      walkthrough's fixed numbers (2 x `249.90` = `499.80`) now
      deterministically resolve to `FAILED`, stated as fact. See
      [ADR-0030](docs/adr/0030-deterministic-payment-provider.md).
- [x] **Opened 2026-07-10, closed 2026-07-12 (Architectural note).**
      `orders-service`'s Spring State Machine usage
      (`OrderStateMachineService.sendEvent`) rebuilds and rehydrates a
      brand new state machine instance from the database on every single
      event. It reads the resulting state back out, writes it to
      `Order.state`, then stops and discards the machine. The actual
      persisted source of truth is always the `Order.state` column, never
      the machine itself, which never stays alive between calls.
      Investigated both clean options: make the machine a live authority,
      or drop the framework for a validated plain transition table. Also
      investigated whether the hybrid is actually causing harm today.
      Decided to keep the hybrid as-is. No bug is traceable to this
      pattern today. Existing tests mock around it entirely. Both
      alternatives would touch the order lifecycle end-to-end for zero
      functional gain. Accepted as a conscious, documented trade-off,
      rather than left open by omission. See
      [ADR-0032](docs/adr/0032-accept-order-state-machine-hybrid.md),
      which also opens the `Order.@Version` item below, found along the
      way.
- [x] **Opened 2026-07-11, closed 2026-07-11 (Low).** `billing-service`'s
      `PaymentService.processPayment` had an "API fallback" branch that
      built a new `Payment` directly, for a call whose `orderId` has no
      existing row yet, but never set `userId`. This violated the table's
      `NOT NULL` constraint. Found while live-validating ADR-0030's
      determinism fix. Investigation showed no legitimate caller ever
      exercised this branch. The frontend, `docs/walkthrough.md`, and
      the Postman collection all process an order that already went
      through `order.created` first. Instead of patching the bug, the
      fallback, and the `amount` parameter it alone used, was removed
      entirely. A missing `Payment` is now a `404` domain error. See
      [ADR-0031](docs/adr/0031-single-source-of-truth-for-payment-creation.md).
- [x] **Opened 2026-07-12, closed 2026-07-12 (Low).** `orders-service`'s
      `Order` entity has no `@Version` column: no optimistic-locking
      protection against concurrent writes to the same order.
      `InventoryEventsConsumer`, `PaymentEventsConsumer`, and the HTTP
      endpoints (`cancelOrder`/`shipOrder`/`deliverOrder`) could all race
      to update the same `Order` row with no conflict detection. Found
      while investigating the state machine item resolved by
      [ADR-0032](docs/adr/0032-accept-order-state-machine-hybrid.md).
      Fixed by adding `@Version` to `Order`, and, on the same
      investigation, to `Payment`, since a real gateway integration
      receives duplicated callbacks/retries. This is backed by
      `saveAndFlush`, so a conflict is detected before its event ever
      publishes, mapped to `409 Conflict` in both services.
      `Product`/`User` were deliberately excluded: neither has an
      observed concurrent-writer path. See
      [ADR-0033](docs/adr/0033-optimistic-locking-on-order-and-payment.md).
- [x] **Opened 2026-07-12, closed 2026-07-13 (Architectural note).** JSON
      Schema contract testing ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md))
      had only ever been added for `OrderCreatedEvent` and
      `UserRegisteredEvent`, after they had already shipped without it.
      These were the two events that had already drawn the most scrutiny,
      not a project-wide default applied from each event's first
      consumer. `product.*`/`stock.*` and every event introduced since
      ADR-0002 landed had no schema. A field/type drift there was caught
      by nothing until it failed at runtime. Resolved by extending
      coverage to all 17 currently-published messages, fact-events and
      commands alike, across all six services. A real drift was found and
      fixed along the way: `JwtCreatedEvent.userId` was a `String`, but
      every consumer already treated it as numeric. This change also made
      a schema plus contract test mandatory for every new event from now
      on, enforced by `CONTRIBUTING.md` and code review. See ADR-0002's
      Update.
- [x] **Opened 2026-07-12, closed 2026-07-12 (Medium).** `orders-service`'s
      state machine had no compensation path for a `Payment` already
      `APPROVED` when its order could no longer be fulfilled, stock
      failure or cancellation. `OrderService.handleInventoryFailed`/
      `cancelOrder` transitioned the order to a terminal state and never
      touched billing-service's `Payment` at all. A buyer's payment could
      end up approved for an order that would never ship, with nothing
      that refunded it. Investigation found the real trigger narrower
      than expected: `handlePaymentReceived` already detected a stray
      `payment.approved`, its `PAYMENT_RECEIVED` transition rejected from
      `INVENTORY_FAILED`/`CANCELLED`, but silently swallowed the
      rejection. Resolved by activating the pre-existing, never-wired
      `OrderState.REFUNDING`/`OrderEvent.INITIATE_REFUND`/
      `REFUND_COMPLETED`, present since this project's first commit, plus
      a new `REFUND_FAILED`, and a choreographed compensation saga
      (`payment.refund.requested`/`payment.refunded`/`payment.refund.failed`).
      Orders publishes intent; Billing alone decides and owns `Payment`.
      See [ADR-0034](docs/adr/0034-payment-compensation-saga.md).
- [x] **Opened 2026-07-12, closed 2026-07-13 (Low).** Event DTOs are
      hand-duplicated per service/language, with no shared library. This
      is a deliberate polyglot trade-off that keeps every service's build
      independent, not an oversight. All 17 published messages now have a
      schema ([ADR-0002](docs/adr/0002-json-schema-contract-testing.md)),
      but nothing stops a hand-written DTO from silently drifting from
      its schema, until a test catches it. Investigated generating each
      language's DTO directly from its JSON Schema (`jsonschema2pojo`/
      `go-jsonschema`/`datamodel-code-generator`), as a way to remove that
      risk by construction. Rejected at the project's current scale, by
      decision rather than by implementation. Every schema in this
      project has exactly one real content-change in its history. The one
      drift ever found was already caught by the contract test added in
      the same change that introduced it. Generation would have cost the
      intentional-partial-consumer DTO pattern already in deliberate use
      (`orders-service`'s `ProductCreatedEvent` captures 2 of 6 fields on
      purpose), for a problem occurring at a measured rate of once. See
      [ADR-0035](docs/adr/0035-reject-dto-code-generation-from-json-schema.md)
      for the full analysis and the objective criteria that would reopen
      this decision.
- [x] **Opened 2026-07-12, closed 2026-07-15.** `orders-service` had no
      Outbox Pattern for any of its publishes (`order.created`,
      `order.status-changed`, the `stock.reserve` command, and
      `payment.refund.requested`), unlike `auth-service`/
      `inventory-service`, which do. Found while deciding, for
      [ADR-0034](docs/adr/0034-payment-compensation-saga.md), not to give
      the new `payment.refund.requested` publish alone the same
      crash-between-commit-and-publish protection Outbox provides. Doing
      so would have created an asymmetry with every other publish this
      same service already makes the same way. Resolved by a broader
      architectural analysis that re-evaluated publish reliability across
      the whole system by impact of loss, not by whether a caller notices
      the failure, or whether ADR-0019's unrelated consumer-side retry
      happens to cover a related symptom. All four of this service's
      publishes qualified, and now go through an `OutboxPublisher`
      identical in shape to `auth-service`/`inventory-service`'s own. See
      [ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)'s
      2026-07-15 Update and [ADR-0034](docs/adr/0034-payment-compensation-saga.md)'s
      own Update.
- [x] **Opened 2026-07-14, closed 2026-07-14.** Quantitative observability
      (metrics) was the one pillar ADR-0024 knowingly left open. Logs
      answer "what happened to operation X", but not "which service is
      slowest" or "what fraction of payments are failing right now".
      Resolved by narrowing ADR-0024's bundled Prometheus/Grafana
      rejection; that cost analysis targeted a full tracing backend,
      which neither actually needs. See
      [ADR-0036](docs/adr/0036-metrics-via-prometheus-grafana.md).
      RabbitMQ's own Prometheus plugin and each Spring service's HikariCP
      pool cover the broker and Postgres connection visibility, with zero
      new exporter containers. The two Go services needed one small
      custom HTTP-metrics middleware, since `promhttp` doesn't
      auto-instrument request rate/latency the way Micrometer does.
      Dashboards are provisioned as code
      (`observability/grafana/provisioning`), not configured by hand.
- [x] **Opened 2026-07-14, closed 2026-07-15.** `orders-service`'s
      `RabbitMQInitializer` declared `order.exchange` at boot with no
      retry, unlike `inventory-service`'s RabbitMQ connection setup (10
      attempts with backoff). Found live while validating ADR-0036's
      docker-compose stack against a freshly reinstalled Docker.
      RabbitMQ's own healthcheck (`rabbitmq-diagnostics ping`) only
      confirms the Erlang node itself is up, not that the AMQP listener
      is already accepting connections. This is a real, narrow race,
      regardless of `depends_on`'s `condition: service_healthy`.
      `inventory-service` hit the same window on the same boot and simply
      retried into success. Without an equivalent retry, `orders-service`
      threw `AmqpConnectException`/`Connection refused` from its exchange
      declaration, and Spring Boot exited the JVM (`exit code 1`) instead
      of retrying. A dedicated investigation
      ([ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md))
      found the real cause was narrower than "Spring needs retry too".
      Confirmed empirically, live, RabbitMQ stopped, that both a
      listener-less service and one with real `@RabbitListener`s already
      survive this exact race via Spring Boot's own autoconfigured
      `SimpleMessageListenerContainer`, which retries every ~5s on its
      own. `RabbitMQInitializer` was the one piece of imperative code
      bypassing that built-in tolerance. It has been deleted, with its
      one non-redundant declaration folded into the existing `@Bean`
      pattern. The same investigation also found and closed an unrelated,
      more severe gap: all four Spring services crashed immediately, no
      retry at all, if Postgres, not just RabbitMQ, was slow to become
      ready. Fixed with one property
      (`spring.datasource.hikari.initialization-fail-timeout=30000`) per
      service. `inventory-service`'s own internal asymmetry, RabbitMQ
      retried but Postgres didn't, was closed too.
- [x] **Opened 2026-07-14, closed 2026-07-14.** `billing-service`'s
      `PaymentService.processPayment` wrapped both the payment-provider
      decision and the event publish that follows it, in a single generic
      `catch (Exception e)`. A failure to publish `payment.approved`, for
      example a momentarily unavailable RabbitMQ, was silently
      reinterpreted as the payment itself having failed. This flipped an
      already-approved payment to `FAILED`, and reported a wrong outcome
      to the caller instead of erroring. Found while investigating whether
      `orders-service`/`billing-service` need the Outbox Pattern (see the
      item above about `orders-service` having none at the time). This is
      a distinct, independently-fixable correctness bug, not itself an
      Outbox question. Fixed by narrowing the catch to the provider call
      only. A failure at or after persisting the decision, including the
      publish, now propagates and rolls back the transaction, instead of
      silently overwriting an already-decided outcome.
- [x] **Opened 2026-07-14, closed 2026-07-14.** auth-service's and
      inventory-service's independently-built Outbox implementations
      (ADR-0003, and an aside inside ADR-0007 that never got its own ADR)
      agreed on everything structural, but were never specified as one
      concern. They had drifted where nothing pinned them down: neither
      had a metric on the publisher itself, and logging was inconsistent
      in opposite directions between the two languages. Harmonized both,
      and formalized the shared design. See
      [ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md).
      Did not, at the time, extend Outbox to any new service. The item
      above about `orders-service` having none was closed separately the
      next day, per ADR-0037's own 2026-07-15 Update.
- [x] **Opened 2026-07-15, closed 2026-07-15.** `billing-service` had no
      Outbox Pattern for any of its publishes (`payment.approved`,
      `payment.failed`, `payment.refunded`, `payment.refund.failed`),
      the same gap `orders-service` had until the item above closed it.
      Resolved the same way, same day: all four publishes qualified under
      [ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)'s
      adoption criterion (impact of loss on a cross-service process, not
      caller observability), and now go through an `OutboxPublisher`
      identical in shape to the other three services'. See ADR-0037's
      second 2026-07-15 Update. `PaymentService` no longer holds a
      `RabbitTemplate`. Every publish goes through the same
      `writeOutboxEvent` helper `orders-service`'s `OrderService` already
      uses. The Outbox Pattern now covers every publish in the system that
      qualifies under ADR-0037's criterion. `products-service` was out of
      scope for that analysis and remains untouched.
- [x] **Opened 2026-07-15, closed 2026-07-15 (Architectural note).** The
      Outbox Pattern (ADR-0037) had never explicitly decided a retention
      policy for already-published `outbox_events` rows, which accumulate
      forever, nor explicitly weighed polling against CDC/Debezium.
      Closed by ADR-0037's third 2026-07-15 Update: no automated cleanup
      added at this project's volume, with objective criteria for
      revisiting. CDC was explicitly rejected. It would reintroduce
      Kafka-adjacent infrastructure ADR-0007 already removed in full, for
      a latency improvement nothing measured in `outbox_publish_lag_seconds`
      has shown to be needed.
- [x] **Opened 2026-07-15, closed 2026-07-15 (Architectural note).** The
      broadcast-JWT-cache model's best-known limitation, a service restart
      wipes its cache and recovery means the user logging in again, had
      never had its own ADR. It was only mentioned as an aside inside
      ADR-0027 and ADR-0028. A second review also found a previously
      undocumented asymmetry: no cache entry expires on its own JWT's
      `expiresIn`, only on restart. See
      [ADR-0039](docs/adr/0039-jwt-broadcast-cache-restart-and-ttl.md):
      keeps the broadcast-cache model as-is, explicitly rejects local
      JWKS verification and a persisted/shared cache, and decides to add
      an `expiresIn`-based TTL to close the asymmetry. The TTL itself and
      a cache-miss test remain open, not implemented by that ADR.
- [x] **Opened 2026-07-15, closed 2026-07-15 (High).** `inventory-service`'s
      `ReleaseStock` (`PostgresRepository.ReleaseStock`,
      `UPDATE inventory_schema.inventory SET reserved = reserved - $1 ...
      WHERE reserved >= $1`) had no idempotency protection at all, unlike
      `ReserveStock`, which has a TTL-based dedup cache. A duplicate
      delivery of a stock-release message, already possible under this
      project's own RabbitMQ redelivery/retry configuration, decremented
      `reserved` a second time. It often failed silently rather than
      erroring, because the guard only trips once `reserved` itself runs
      out. Fixed by extending `ReserveStock`'s existing per-OrderID
      TTL/cache/lock-stripe mechanism to `ReleaseStock`: a second,
      independent `processedReleases` map, same TTL and cleanup sweep,
      same `lockForOrder` stripe locking. This was the lower-cost of the
      two candidates the review identified. The database-level
      idempotency check, which would also close `ReserveStock`'s own
      known post-TTL duplication window, remains a further, not-yet-adopted
      option. TDD: `TestReleaseStock_RetryDoesNotDuplicateRelease`,
      `TestReleaseStock_ConcurrentRedeliveriesOfSameOrderReleaseOnce`,
      `TestReleaseStock_CacheDoesNotGrowUnboundedWithVolume` were all red
      before the fix (the first two by assertion failure, the third by a
      compile error against the not-yet-existing cache field), green
      after. The full `inventory-service` suite (`go build`, `go vet`,
      `go test ./...`) is green, with no regressions. No metric yet
      distinguishes a cache hit from a duplicate delivery on either path;
      still open, not addressed by this fix.
- [x] **Opened 2026-07-15, closed 2026-07-15 (Architectural note).**
      `notification-service` made the system's one synchronous
      cross-service call (`process_order_created` calling `auth-service`
      over HTTP via `AuthServiceClient.get_notification_profile`) to fetch
      `email`/`firstName`/`lastName` for the first notification of an
      order. This data already arrives, in full, on the same
      `jwt.created` broadcast this service already consumes for
      authentication (`JwtCreatedEvent` carries all three fields). The
      service's own `JwtCache` discarded `firstName`/`lastName` on write,
      and was indexed by token, not by `userId`, so it could not serve
      this lookup. Fixed: `JwtCache` now keeps a second, `userId`-keyed
      view of the same broadcast data (`get_by_user_id`). A new
      `CachingAuthClient` (`app/auth_client.py`) tries that view first,
      falling back to the real HTTP call only on a cache miss, the narrow
      case of a cache-cold restart between a user's login and their
      order. `process_order_created`/`consumer.py` needed no changes at
      all; only `main.py`'s wiring swaps which client it hands the
      consumer. `_cache_jwt_created` (`app/rabbitmq.py`) now also reads
      `firstName`/`lastName` from the broadcast, previously deliberately
      ignored (see the updated contract test,
      `tests/test_contract_jwt_created.py`). TDD: 8 new/updated tests
      (`tests/test_auth.py`, `tests/test_auth_client.py`, the contract
      test) were red before the change, missing method/class, or
      asserting the old, narrower field set, and green after. The full
      non-integration suite (`pytest -m "not integration"`, 40 tests) is
      green, with no regressions. The 8 real-infra integration tests
      (`test_order_created_flow.py`, `test_consumer_resilience_flow.py`)
      exercise the raw `AuthServiceClient` directly, unaffected by this
      change.
- [x] **Opened 2026-07-15, closed 2026-07-15 (Architectural note).** No
      metric distinguished a cache hit from a duplicate delivery for
      either of `inventory-service`'s two idempotency caches. This was a
      known, accepted residual risk, documented above and provable only
      by unit test, with zero runtime observability. Closed by
      ADR-0036's 2026-07-15 Update: a sixth business counter,
      `inventory_idempotent_duplicate_detected_total{operation}`,
      incremented exactly once per cache hit on `ReserveStock` or
      `ReleaseStock`. The JWT broadcast cache's equivalent gap, in any
      service, was tracked alongside the `expiresIn` TTL ADR-0039 had
      decided but not yet implemented. Both were closed the same day; see
      the two items below.
- [x] **Opened 2026-07-15, closed 2026-07-15.** The `expiresIn`-based TTL
      ADR-0039 decided but did not implement, and the cache-miss-by-restart
      test it asked for in "at least one" service, were both still open.
      Closed by ADR-0039's 2026-07-15 Update: implemented in all four
      broadcast-JWT-cache services (`orders-service`'s `JwtConsumer` and
      `UserEventsConsumer`, `products-service`'s `UserEventConsumer`,
      `billing-service`'s `JwtConsumer`, `notification-service`'s
      `JwtCache`). `JwtUserInfo` (Java) gained a constructor overload
      carrying `expiresAt`, computed as `createdAt.plusSeconds(expiresIn)`.
      The old constructor delegates to it with "never expires", so the
      six existing test classes across three services that construct a
      `JwtUserInfo` directly as a Spring Security principal needed no
      changes. An expired entry is evicted lazily on the next read that
      finds it, not via a background sweep. `orders-service`'s and
      `billing-service`'s own `JwtEvent` DTOs gained `createdAt`/
      `expiresIn` for the first time, previously absent, unlike
      `UserEvent`, which already had both. `notification-service`'s
      `_cache_jwt_created` now reads every field the shared jwt-created
      schema declares. Every service gained a
      `JwtAuthenticationFilterExpiryTest` (or the Python equivalent in
      `tests/test_auth.py`) proving hit, expired, and never-cached (the
      restart scenario) as three distinct outcomes, not just "at least
      one" service, all four. TDD throughout; full suite per service, no
      regressions: 48/48 (`billing-service`), 22/22 (`products-service`),
      99/99 (`orders-service`), 48/48 non-integration
      (`notification-service`).
- [x] **Opened 2026-07-15, closed 2026-07-15.** No metric distinguished a
      cache hit from a miss or an expired-and-evicted entry for any of the
      four broadcast-JWT caches. This was the same kind of
      runtime-observability gap ADR-0036's first 2026-07-15 Update closed
      for `inventory-service`'s idempotency cache. Closed by ADR-0036's
      second 2026-07-15 Update: `jwt_cache_lookup_total{outcome}`
      (`hit`/`miss`/`expired`) in all four services, incremented at the
      same point the TTL check above already runs. The three Java
      services use Micrometer via a constructor-injected
      `ObjectProvider<MeterRegistry>`, not a direct `MeterRegistry`
      dependency. This is specifically so `JwtAuthenticationFilter` still
      constructs cleanly inside a `@WebMvcTest` slice, which doesn't
      autoconfigure a real `MeterRegistry` bean, falling back to a
      private, unscraped `SimpleMeterRegistry` in that case, with zero
      changes needed to any existing controller test.
      `notification-service` uses `prometheus_client.Counter`, the same
      mechanism `notifications_sent_total` already uses.
- [x] **Opened 2026-07-17, closed 2026-07-17 (Architectural note).** Docker
      Compose already abstracts several properties of a declarative
      deployment platform: continuous reconciliation, health checks
      driving availability/recovery, configuration/application separation,
      declarative resource representation. Added a minimal `kind`-based
      Kubernetes deployment (`k8s/`) making them explicit, with zero
      application code changes. Every service already resolves the others
      by hostname and already exposes a real `/health` endpoint, reused
      unchanged via Kubernetes Service DNS and readiness/liveness probes.
      One PersistentVolumeClaim (Postgres only). Deployment rather than
      StatefulSet for Postgres/RabbitMQ. Kustomize base with no overlays.
      `kind` was chosen over Minikube/k3d on technical grounds. Docker
      Compose remains the recommended environment for local development.
      A 2026-07-17 Update adds the frontend, reusing the same image
      already built for Docker Compose unmodified; its `VITE_GATEWAY_URL`
      build-time value already matches the Gateway's address under
      `kind`. See
      [ADR-0040](docs/adr/0040-minimal-kubernetes-kind-deployment.md).
- [x] **Opened 2026-07-17, closed 2026-07-17.** Generating test data against
      the `kind` deployment surfaced a real gap: RabbitMQ has no
      PersistentVolume there (ADR-0040), so a pod restart leaves every
      client's existing connection dead. `inventory-service` (Go) never
      noticed; it stayed permanently unable to consume until manually
      restarted. Investigated every service's connection/channel/consumer
      lifecycle before changing anything. Verified Java's tolerance live,
      rather than assuming it: `amqp-client`'s automatic
      connection/topology recovery already handles this. Rejected both a
      PersistentVolume for RabbitMQ, which only helps queues survive, not
      client reconnection, and a liveness-probe-only fix, not portable to
      Docker Compose, which doesn't restart a container on a failed
      healthcheck. Closed the one real code gap with a `NotifyClose`-based
      reconnect supervisor in `inventory-service`. Gave
      `notification-service`'s `pika` consumer an explicit heartbeat.
      Added a Progress Watchdog to all six services, feeding each one's
      `/health/liveness`, fed by reconnect attempts and messages
      processed, deliberately never by instantaneous broker reachability.
      Fixed a found-along-the-way `RabbitMQHealthIndicator` exchange-type
      bug. Added `restart: on-failure` to every `docker-compose.yml`
      service. See
      [ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s
      2026-07-17 Update.
- [x] **Opened 2026-07-20, closed 2026-07-20.** Re-testing the
      2026-07-17 reconnect supervisor against real topology loss, not
      just a dropped TCP connection, the gap its own validation had,
      found `inventory-service` never redeclared `order.exchange`/
      `product.exchange` on reconnect. This was the actual cause of the
      original incident, now closed and proven by deleting the exchange
      itself. The same fix surfaced two more real bugs: the Outbox
      publisher's fire-and-forget `Publish` could silently mark an event
      "published" that the broker never accepted, and one
      permanently-bad outbox row could poison an entire poll batch's
      channel. Both closed with publisher confirms and per-event channel
      validation. `orders-service`/`billing-service`'s JWT filters were
      standardized to match `products-service`. A reconnection
      observability contract is now stated explicitly, evaluated, and
      rejected, forcing identical reconnection code across Go/Java/Python
      (see [ADR-0035](docs/adr/0035-reject-dto-code-generation-from-json-schema.md)'s
      precedent). It is backed by new
      `rabbitmq_reconnect_attempts_total`/
      `rabbitmq_topology_setup_total{outcome}`/
      `messaging_last_progress_timestamp_seconds` metrics across all six
      services, a new Grafana dashboard (`EasyDora / Resilience`), and a
      completed Postman collection covering the payment-compensation saga
      (ADR-0034) end to end. See
      [ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md)'s
      2026-07-20 Update.
- [x] **Opened 2026-08-02, closed 2026-08-02.** ADR-0024 revisited its own
      rejection of a tracing backend: a Jaeger container plus OpenTelemetry
      in all eight services, additive to the existing CorrelationId design,
      not a replacement of it. A real login produced one trace spanning 6
      services and 13 spans across three languages and five parallel
      consumers of the same broadcast. Found and fixed a real, unrelated
      bug while rebuilding every image from scratch for the first time
      since the parent POM's version was fixed: `auth-service/Dockerfile`
      still hardcoded a stale jar filename its three siblings had already
      moved off. Also found, and left open, a genuine gap: outbox-mediated
      publishes (`orders-service`, `inventory-service`, `billing-service`)
      don't yet carry a trace across their write-to-publish gap, unlike
      CorrelationId's own envelope trick for the same gap. See
      [ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)'s
      2026-08-02 Update.

The following open items were surveyed and registered together on
2026-08-02, ordered by severity rather than by the date each was
originally found (every other entry in this Roadmap orders by date
found). Each keeps its own true "Opened" date. None of these are
deliberately accepted trade-offs — those already have their own
"Objective criteria for revisiting" section in the relevant ADR and are
not repeated here.

- [ ] **Opened 2026-08-01 (High).** RabbitMQ's automatic recovery is not
      lossless under a hard container kill: 2 of 207 broker-acknowledged
      messages never reached the consumer in
      [ADR-0041](docs/adr/0041-kafka-rabbitmq-broker-benchmark.md)'s
      benchmark. A narrow, real gap in the Outbox pattern's at-least-once
      guarantee ([ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md))
      under non-graceful broker failure, unresolved.
- [ ] **Opened 2026-07-15 (High).** `inventory-service`'s `ReserveStock`
      idempotency protection only covers the TTL cache window. The
      database-level check that would also close the post-TTL
      duplication window remains a further, not-yet-adopted option.
- [ ] **Opened 2026-07-04 (High).** `auth-service`'s `registerUser` still
      publishes `user.registered` directly rather than through the
      Outbox — a publish failure after a successful save can silently
      drop the event. Never evaluated against the adoption criterion
      [ADR-0037](docs/adr/0037-consolidated-outbox-pattern-specification.md)
      later introduced for `orders-service`/`billing-service`. See
      [ADR-0003](docs/adr/0003-outbox-pattern-auth-service.md).
- [ ] **Opened 2026-07-07 (High).** `auth-service`'s
      `application-dev.properties` default `jwt.secret`/`app.jwt.secret`
      fallback is shorter than HMAC-SHA's recommended minimum key length.
      See [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md).
- [ ] **Opened 2026-07-04 (Medium).** `api-gateway`'s circuit breaker
      detects a failed call only by checking for the proxy's own `502` —
      a status-code heuristic that would misfire if a real backend ever
      legitimately returned `502` itself. See
      [ADR-0006](docs/adr/0006-gateway-circuit-breaker.md).
- [ ] **Opened 2026-07-04 (Medium).** The circuit breaker's thresholds (5
      consecutive failures, 30s cooldown) are fixed values, never
      validated against this project's actual traffic patterns or
      measured failure-recovery times. See
      [ADR-0006](docs/adr/0006-gateway-circuit-breaker.md).
- [ ] **Opened 2026-07-05 (Medium).** `/health` across all four Spring
      services is a shallow liveness check with no real dependency probe
      — `products-service`'s and `auth-service`'s even claim
      `"database": "Connected"` unconditionally. See
      [ADR-0010](docs/adr/0010-uniform-service-healthchecks.md).
- [ ] **Opened 2026-07-06 (Medium).** `products-service`'s live schema in
      this long-running environment still carries Hibernate-era drift; a
      fresh environment running the same Flyway migrations from scratch
      would end up with a subtly different schema. See
      [ADR-0011](docs/adr/0011-flyway-schema-authority-all-services.md).
- [ ] **Opened 2026-07-06 (Medium).** Neither `products-service` nor
      `billing-service` has ever run a real `*IT` integration test
      exercising its own Flyway migration path. See
      [ADR-0011](docs/adr/0011-flyway-schema-authority-all-services.md).
- [ ] **Opened 2026-07-10 (Medium).** Payment processing stays a manual
      button; nothing publishes an event that triggers
      `PaymentService.processPayment` automatically. See
      [ADR-0026](docs/adr/0026-frontend-thin-client.md).
- [ ] **Opened 2026-07-12 (Medium).** No remediation tooling exists for
      the manual review a `REFUND_FAILED` order needs — a genuine dead
      end today. See
      [ADR-0034](docs/adr/0034-payment-compensation-saga.md).
- [ ] **Opened 2026-07-04 (Low).** Whether `JWT_SECRET`/`app.jwt.secret`
      is genuinely dead configuration in `products-service`,
      `orders-service`, and `billing-service` was never conclusively
      resolved. See [ADR-0005](docs/adr/0005-secret-rotation.md).
- [ ] **Opened 2026-07-09 (Low).** `notification-service`'s
      `process_order_created`/`process_order_status_changed` remain
      unaware that a retry/DLQ policy exists at all — the whole policy
      lives only in `app/rabbitmq.py`'s `_route_to_retry_or_dlq`.
- [ ] **Opened 2026-07-15 (Low).** The four Spring services' tolerance of
      a RabbitMQ/Postgres startup race rests on framework behavior
      confirmed empirically here but not owned or tested by this
      project's own code. See
      [ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md).
- [ ] **Opened 2026-07-15 (Low).** `notification-service`'s unbounded
      RabbitMQ reconnect retry is inconsistent with its own bounded
      Postgres retry. See
      [ADR-0038](docs/adr/0038-infrastructure-startup-resilience.md).
- [ ] **Opened 2026-08-02 (Low).** Outbox-mediated publishes
      (`orders-service`, `inventory-service`, `billing-service`) don't
      yet carry a trace across their write-to-publish gap, unlike
      CorrelationId's own envelope trick for the same gap. See
      [ADR-0024](docs/adr/0024-distributed-tracing-via-propagated-identifiers.md)'s
      2026-08-02 Update.

</details>

## License

This repository is licensed under the [Apache License 2.0](LICENSE). You
are free to use, modify, and distribute this code, including for
commercial purposes, subject to the terms of that license. See the
[NOTICE](NOTICE) file for a summary of the practices this project
demonstrates, and for the attribution requirements that carry over into
derivative works.

Easydora is primarily an educational and portfolio project. It exists to
demonstrate distributed-systems architecture and engineering practices.
It is not meant to be adopted as a dependency or a production platform.
Contributions (bug reports, fixes, documentation improvements) are
welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). The author retains
copyright over the original work, as stated in [LICENSE](LICENSE) and
[NOTICE](NOTICE).

## Disclaimer

This repository was built for learning and portfolio purposes. It
demonstrates event-driven microservices architecture, testing
discipline, and the kind of decision-making documented throughout the
ADR set. It is **not** intended to be a production-ready commercial
platform.

Some external integrations are intentionally simplified. notification-service
sends no real email/SMS (see [ADR-0014](docs/adr/0014-notification-service.md)).
billing-service's payment provider is not a real payment gateway.
Credentials and secrets in this repository's local `.env.example` are
meant for local development only, never for production use.

Architectural decisions throughout this project prioritize demonstrating
sound engineering practices, resilience, observability, testing, contract
validation, over full business completeness. Known gaps are tracked
openly in the [Roadmap](#roadmap) and in each ADR's own Consequences
section, rather than hidden.
