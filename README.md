# EasyDora

A polyglot, event-driven e-commerce system built as a microservices
architecture exercise: each service is implemented in the language/stack
suited to its workload, not for convenience — Go for performance-sensitive
gateway/inventory paths, Spring Boot for domain-rich business logic,
FastAPI for async notification processing.

**Status: in active development.** Five of seven services are implemented and
building (auth, products, inventory, orders, billing); none has meaningful
automated test coverage yet. Notification and frontend are empty scaffolds,
not yet functional. See [Service Status](#service-status) below for the
current breakdown.

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
                    │ (planned) │         │  RabbitMQ   │
                    └───────────┘         │  (planned)  │
                                           └─────────────┘

Frontend (SvelteKit, planned) consumes the API Gateway.
```

Async order flow via RabbitMQ; JWT-based cross-service authentication;
each service independently deployable via Docker Compose.

## Service Status

| Service | Stack | Port | Status |
|---|---|---|---|
| API Gateway | Go + Gin | 8080 | Implemented (no automated tests) |
| Auth | Spring Boot + JWT | 8081 | Implemented (no automated tests) |
| Products | Spring Boot + PostgreSQL | 8082 | Implemented (no automated tests) |
| Inventory | Go + PostgreSQL | 8083 | Implemented (no automated tests) |
| Orders | Spring Boot + RabbitMQ | 8084 | Implemented (no automated tests) |
| Billing | Spring Boot | 8085 | Implemented (1 test, package fixed — full pass unconfirmed, local infra unavailable) |
| Notification | FastAPI + RabbitMQ | 8086 | Planned (empty scaffold) |
| Frontend | SvelteKit | 3000 | Planned (empty scaffold) |

"Implemented" means the service builds and runs; it does not imply test coverage. Only billing-service has any test source (`BillingServiceApplicationTests`, a Spring Initializr default). It previously failed on a package mismatch between the test class and `@SpringBootApplication`, which has been fixed; the test now correctly loads Spring context configuration but requires a running Postgres/RabbitMQ to fully pass (`mvn test`), so a green run has not yet been confirmed. No service has CI configured.

Infrastructure: RabbitMQ Management (15672), PostgreSQL (5432).

## Quick Start

```bash
git clone <repo-url>
cd easydora

# Start all implemented services
docker-compose up -d

# Check status
docker-compose ps
```

The four implemented services (Auth, Products, Inventory, Orders) come up
and respond on their ports above. Billing, Notification, and the frontend
are present in `docker-compose.yml` as scaffolding but are not yet
functional.

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

- [ ] Billing service (Spring Boot)
- [ ] Notification service (FastAPI + RabbitMQ consumer)
- [ ] SvelteKit frontend
- [ ] End-to-end integration tests across the four implemented services

## Docker Troubleshooting (Windows)

If `docker-compose` fails to connect:

1. Open Docker Desktop and wait for "Docker Desktop is running".
2. Verify with `docker version`.
3. If `docker-compose` doesn't work, try `docker compose` (no hyphen).
4. If issues persist, restart Docker Desktop via its system tray icon.
