# EasyDora

[![CI](https://github.com/pablofelipe/easydora/actions/workflows/ci.yml/badge.svg)](https://github.com/pablofelipe/easydora/actions/workflows/ci.yml)

A polyglot, event-driven e-commerce system built as a microservices
architecture exercise: each service is implemented in the language/stack
suited to its workload, not for convenience — Go for performance-sensitive
gateway/inventory paths, Spring Boot for domain-rich business logic,
FastAPI for async notification processing.

**Status: in active development.** Five of seven services are implemented and
building (auth, products, inventory, orders, billing). Four of them
(auth-service, orders-service, products-service, billing-service) now have
contract tests validating their event/message DTOs against JSON Schemas
shared in `/schemas/json/`; inventory-service has four unit tests covering
its stock-reservation idempotency logic specifically, not the service
broadly. Notification and frontend are empty scaffolds, not yet functional.
See [Service Status](#service-status) below for the current breakdown.

## Architecture

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
                    └───────────┘         │  (planned)  │
                                           └─────────────┘

Frontend (SvelteKit, planned) consumes the API Gateway.
```

Async order flow via RabbitMQ; JWT-based cross-service authentication;
each service independently deployable via Docker Compose.

## Service Status

| Service | Stack | Port | Status |
|---|---|---|---|
| API Gateway | Go + Gin | 8080 | Implemented (tests: 5 test functions covering the circuit breaker, see ADR-0006/ADR-0009) |
| Auth | Spring Boot + JWT | 8081 | Implemented (tests: 4/4 — `mvn test` 2/2 unit, `mvn verify` adds 2 `*IT` against real Postgres/RabbitMQ) |
| Products | Spring Boot + PostgreSQL | 8082 | Implemented (tests: 1/1 passing — contract test, `mvn test` only, no `*IT` yet) |
| Inventory | Go + PostgreSQL | 8083 | Implemented (tests: 4/4 passing) |
| Orders | Spring Boot + RabbitMQ | 8084 | Implemented (tests: 5/5 — `mvn test` 4/4 unit, `mvn verify` adds 1 `*IT` against real RabbitMQ) |
| Billing | Spring Boot | 8085 | Implemented (tests: 3/3 — `mvn test` 2/2 unit (contract test + `HealthControllerTest`), `mvn verify` adds 1 `*IT` real-context smoke test against Postgres/RabbitMQ) |
| Notification | FastAPI + RabbitMQ | 8086 | Planned (empty scaffold) |
| Frontend | SvelteKit | 3000 | Planned (empty scaffold) |

"Implemented" means the service builds and runs; it does not imply full test coverage. Five services have real test source so far (see the table above); the paragraph below covers billing-service's history specifically, since it's where the original baseline audit's test-fixing work happened. billing-service has `BillingServiceApplicationIT` (a Spring Initializr default, renamed from `BillingServiceApplicationTests` under ADR-0008's Surefire/Failsafe split), and its `mvn verify` now passes against a real Postgres/RabbitMQ. Getting there required fixing three independent bugs uncovered by actually running the test: a package mismatch between the test class and `@SpringBootApplication`; a missing `rabbitmq.queue.order-created` property; and a Kafka consumer `TYPE_MAPPINGS` entry pointing at `com.easydora.orders.event.OrderCreatedEvent` (another service's class) instead of billing-service's own `OrderCreatedEvent`. That last one is a concrete instance of this project's lack of contract testing between services: each service hand-duplicates its own copy of shared event DTOs, and nothing catches it when a copy silently references the wrong service's class or a diverged field/type.

Event contracts validated via JSON Schema — see [ADR-0002](docs/adr/0002-json-schema-contract-testing.md) for the two schemas migrated, the drifts fixed, and the known `price` type gap that schema validation can't catch. Messaging layer audited for wiring bugs (routing keys, field names, a competing-consumer incident) — see [ADR-0001](docs/adr/0001-messaging-wiring-audit.md) for all six findings and what's still open.

inventory-service has four unit tests covering `ReserveStock`'s idempotency, and two known duplication scenarios around it. A redelivered `ReserveStockCommand` for the same order (the retry scenario that follows a Kafka publish failure after the Postgres commit) previously reserved stock a second time; the service now caches the outcome per `OrderID` for 10 minutes and returns it on retry instead of reserving again, with a background sweep so the cache doesn't grow unbounded with order volume. That 10-minute window covers short-lived retries — an immediate RabbitMQ requeue, or the consumer's own reconnect loop, which backs off for at most 30s — with margin for a full container restart during a redeploy. Two truly concurrent redeliveries of the same order (not sequential retries — actual simultaneous calls) used to both slip past the cache check before either had written its result back, double-reserving; that race is now closed by serializing `ReserveStock` per `OrderID` (a fixed-size striped mutex, not a second unbounded map), verified by a 50-goroutine concurrency test and by `go test -race` (run in a Linux container, since this environment's native Windows Go toolchain has no cgo/gcc for the race detector) reporting no data races. What remains open, deliberately not fixed here: a redelivery that arrives *after* the 10-minute cache entry has expired — e.g. a message reprocessed late from a dead-letter queue — is indistinguishable from a first delivery and will still duplicate the reservation (verified by a test, not assumed). The cache is also in-memory and per-process, so a service restart clears it outright. Closing that residual gap for good needs the outbox-pattern work already catalogued as technical debt, not a bigger TTL. CI (Phase 1: build/vet/unit-test only, no service containers) is configured — see the badge above and `.github/workflows/ci.yml`; Phase 2 (contract/wiring tests against real brokers) is future work.

Infrastructure: RabbitMQ Management (15672), PostgreSQL (5432).

## Architecture Decision Records

| ADR | Title | Status | Summary |
|---|---|---|---|
| [0001](docs/adr/0001-messaging-wiring-audit.md) | Messaging wiring audit | Accepted | Five routing/field-name/listener bugs fixed (RabbitMQ + Kafka), one JWT-queue message-loss incident dated back to the project's first commit, one dead payment-event code path removed; `OrderStatusChangedEvent` left as an open design decision. |
| [0002](docs/adr/0002-json-schema-contract-testing.md) | JSON Schema contract testing | Accepted | JSON Schema (draft 2020-12) adopted for event contracts, versioned in `/schemas/json/`; two catalogued DTO drifts fixed; `price` type drift (BigDecimal vs float64) documented as a known gap schema validation can't catch. |
| [0003](docs/adr/0003-outbox-pattern-auth-service.md) | Outbox pattern for auth-service | Accepted | `verifyEmail`'s publish-before-save ordering fixed with a polled `outbox_events` table; `inventory-service`'s equivalent risk (Go, Kafka) left as separate future work; a Flyway/Hibernate schema-duplication bug found along the way, resolved in ADR-0004. |
| [0004](docs/adr/0004-auth-service-schema-authority-fix.md) | auth-service schema authority fix | Accepted | Fixes the schema duplication found in ADR-0003: `V1`/`V2` created tables in `public` while Hibernate's `ddl-auto=update` silently created the real, actually-used copies in `auth_schema`. A `V3` migration recreates both tables in `auth_schema` matching Hibernate's live schema exactly, and `ddl-auto` is locked to `validate`. |
| [0005](docs/adr/0005-secret-rotation.md) | Secret rotation and removal of hardcoded credentials | Accepted | Three credentials hardcoded in `docker-compose.yml` since the project's first commit (public repo) rotated for real against the live Postgres/RabbitMQ, replaced with `${VAR}`/`.env`; orphaned JWT config removed from three services that never consumed it. History not rewritten — old values are treated as permanently compromised. |
| [0006](docs/adr/0006-gateway-circuit-breaker.md) | Circuit breaker in the API Gateway | Accepted | `sony/gobreaker` added, one breaker per service (`auth`, `products`, `inventory`, `orders`; billing excluded, see ADR-0009), 5 consecutive failures to open / 30s cooldown. Verified against real containers: stopping inventory-service made it fail fast while the other three kept responding normally. |
| [0007](docs/adr/0007-remove-kafka-broker.md) | Remove Kafka broker (migrate to RabbitMQ) | Proposed (planning) | Stub — full decision and consequences to be written as part of Etapa 4 (Kafka → RabbitMQ migration). |
| [0008](docs/adr/0008-surefire-failsafe-test-split.md) | Separate unit and integration tests via Surefire/Failsafe | Accepted | The four test classes that touch real Postgres/RabbitMQ renamed to the `*IT` suffix and moved to `maven-failsafe-plugin` (`mvn verify`) across all four Spring services; `mvn test` is now unit-only and needs no live infrastructure. |
| [0009](docs/adr/0009-billing-circuit-breaker.md) | Extend the API Gateway circuit breaker to billing-service | Accepted | Same structure as ADR-0006 (`sony/gobreaker`, 5 failures / 30s cooldown), applied to the one remaining entry left on the plain proxy. Closes ADR-0006's open Roadmap item. |
| [0010](docs/adr/0010-uniform-service-healthchecks.md) | Uniform health checks across all six services | Accepted | Every Docker `HEALTHCHECK` now targets each service's own unauthenticated `/health` endpoint instead of `/actuator/health`; billing-service gained a `HealthController`/`SecurityConfig`; orders-service's broken `docker-compose.yml` override removed; auth-service/inventory-service/api-gateway gained a `HEALTHCHECK` they never had. All six services verified `healthy` simultaneously for the first time. |

## Quick Start

```bash
git clone <repo-url>
cd easydora

# Start all implemented services
docker-compose up -d

# Check status
docker-compose ps
```

The five implemented services (Auth, Products, Inventory, Orders, Billing)
come up and respond on their ports above. Notification and the frontend are
commented out in `docker-compose.yml` — no Dockerfile or source exists for
either yet, unlike Billing, which is a real, working service.

**Before running `mvn verify` locally**: run `docker compose down` first.
`orders-service`'s `JwtCreatedFanoutIT` (see ADR-0008) drains the real
production queue `orders.jwt.created.queue` to prove the fan-out fix works —
if a container from an earlier `docker compose up` session (especially
`orders-service` itself) is still running and consuming from that same
queue, it competes with the test for the same messages and the test fails
intermittently, not because of a code regression. Confirmed via grep: this
is the only integration test in the repository touching that queue, so a
single running `orders-service` container is the whole exposure — this
doesn't apply once CI (Phase 2, not yet implemented) gives each Spring
job its own isolated RabbitMQ.

## Prerequisites

- Docker Desktop (Windows/Mac) or Docker Engine (Linux)
- Docker Compose
- Git

```bash
docker --version
docker-compose --version
```

## Design notes

The stack split is deliberate:

- **Go** (Gateway, Inventory) — performance-sensitive, high-throughput
  paths.
- **Spring Boot** (Auth, Products, Orders, Billing) — domain-rich business
  logic where Java's ecosystem (validation, transactions, ORM) pays off.
- **FastAPI** (Notification) — async I/O-bound processing.
- **SvelteKit** (Frontend) — lightweight reactive UI.

## Roadmap

- [ ] Notification service (FastAPI + RabbitMQ consumer)
- [ ] SvelteKit frontend
- [ ] End-to-end integration tests across the five implemented services
- [x] CI pipeline, Phase 1 (`.github/workflows/ci.yml`): parallel build/vet/unit-test jobs for all six services, no service containers
- [ ] CI pipeline, Phase 2: contract and messaging-wiring tests against real brokers/Postgres (Etapa 8, not yet implemented)
- [ ] inventory-service (Go): outbox pattern still not implemented. Publish
      happens directly post-commit (no outbox table); a reservation can
      duplicate on redelivery in a late dead-letter scenario past the
      10-minute idempotency cache TTL. See ADR-0003 (auth-service) for the
      reference pattern. Blocked by: no technical dependency, prioritization
      only.
- [ ] auth/products/orders/billing (Spring): no retry limit/backoff/DLQ on
      RabbitMQ message consumption. Verified there's no synchronous
      inter-service HTTP call anywhere (no RestTemplate/WebClient/
      FeignClient in any of the four services) — the real gap isn't a
      circuit breaker for calls that don't exist, it's on the consumer
      side: `SimpleRabbitListenerContainerFactory` in products-service,
      orders-service, and billing-service is built with no
      `AcknowledgeMode`, `MessageRecoverer`, or requeue policy set, so it
      runs on Spring AMQP's defaults — a listener exception nacks and
      requeues the message indefinitely (`defaultRequeueRejected=true`),
      with no dead-letter queue and no backoff. A poison message (one that
      always throws) loops forever instead of landing somewhere for
      inspection. Candidate: Spring Retry (`@Retryable`/`RetryTemplate`) or
      a dead-letter exchange with limited retries. Blocked by
      prioritization, not a technical dependency.
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
- [ ] billing-service's `/api/payments/**` API is still protected only by
      Spring Boot's default auto-generated single-user Basic auth — unlike
      its three sibling services, it never joined the cross-service JWT
      broadcast cache (no `JwtConsumer`/`JwtAuthenticationFilter`). Noticed
      while adding billing-service's `SecurityConfig` for the health-check
      fix above; deliberately not addressed there (a real JWT integration
      is a separate, larger task). See ADR-0010's Consequences.
- [ ] No shared parent POM across the four Spring services (auth,
      products, orders, billing). Not a deliberate decoupling decision —
      it happened by omission during the project's initial setup.
      Consequence: any plugin or dependency common to all four (Failsafe
      today, a future Resilience4j) has to be replicated by hand in all
      four `pom.xml` files, with no automatic detection if one of them
      drifts to a different version.

## Docker Troubleshooting (Windows)

If `docker-compose` fails to connect:

1. Open Docker Desktop and wait for "Docker Desktop is running".
2. Verify with `docker version`.
3. If `docker-compose` doesn't work, try `docker compose` (no hyphen).
4. If issues persist, restart Docker Desktop via its system tray icon.
