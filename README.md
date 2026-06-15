# EasyDora

A polyglot, event-driven e-commerce system built as a microservices
architecture exercise: each service is implemented in the language/stack
suited to its workload, not for convenience — Go for performance-sensitive
gateway/inventory paths, Spring Boot for domain-rich business logic,
FastAPI for async notification processing.

**Status: in active development.** Four of seven services are implemented
and tested; the remaining three (billing, notification, frontend) are
scaffolded but not yet functional. See [Service Status](#service-status)
below for the current breakdown.

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
| API Gateway | Go + Gin | 8080 | Implemented |
| Auth | Spring Boot + JWT | 8081 | **Implemented & tested** |
| Products | Spring Boot + PostgreSQL | 8082 | **Implemented & tested** |
| Inventory | Go + PostgreSQL | 8083 | **Implemented & tested** |
| Orders | Spring Boot + RabbitMQ | 8084 | **Implemented & tested** |
| Billing | Spring Boot | 8085 | **Implemented ** |
| Notification | FastAPI + RabbitMQ | 8086 | Planned |
| Frontend | SvelteKit | 3000 | Planned |

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
