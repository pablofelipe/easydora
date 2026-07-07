# ADR-0014: Notification Service — first Python/FastAPI service

## Status

Accepted - 2026-07-07

## Context

`notification-service` (port 8086) was, until now, an empty scaffold
directory — a `docker-compose.yml` block commented out, no source, no
Dockerfile. The roadmap called for a FastAPI + RabbitMQ consumer that reacts
to order events and produces a notification, without ever standing up a real
email/SMS/push provider.

Two design questions had to be resolved before writing any code:

1. **How does the service learn the recipient's email?** `order.created`
   (published by `orders-service`, already consumed by `billing-service`)
   carries `userId`, not an email address — `auth-service` is the sole owner
   of user data, and this project's architecture forbids any service reading
   another service's database directly. The only sync escape hatch allowed
   is a public HTTP API call. `auth-service` had no endpoint fit for this:
   `/signup`, `/login`, `/verify-email`, `/ping`, `/health` are the entire
   public surface, none of which return a user's email/name by id.
2. **What counts as "sending" a notification, given delivery must stay
   fake?** The requirement was one observable effect, chosen deliberately,
   not several bolted on speculatively.

## Decision

### New minimal endpoint on auth-service

`GET /users/{id}/notification-profile` — a new `UserQueryController`
(kept separate from `AuthController`, which owns authentication actions, not
data lookups), backed by a new `UserNotificationProfileResponse` DTO
(`id`, `email`, `firstName`, `lastName` only — no password, role, or status).
`404` if the id doesn't exist. Added to `SecurityConfig`'s `permitAll()` list
alongside the other unauthenticated endpoints, since this is a
service-to-service call with no JWT available. Deliberately narrow: a
generic `/users/{id}` endpoint was rejected in favor of one scoped exactly to
what notification-service needs, to avoid growing auth-service's public
surface beyond this use case.

### notification-service (Python/FastAPI)

First Python service in the repository — kept intentionally simple rather
than importing this project's full polyglot toolbox:

- **FastAPI** exposes only `GET /health` (uniform with the other six
  services, ADR-0010); the service is fundamentally a background consumer,
  not an API provider.
- **pika** (synchronous `BlockingConnection`), not `aio-pika` — the whole
  service is small enough that mixing sync and async libraries (httpx,
  psycopg2, pika) would add complexity with no payoff. The consumer runs on
  its own dedicated thread, started from FastAPI's `lifespan`.
- **httpx** (used synchronously) calls `GET {AUTH_SERVICE_URL}/users/{id}/notification-profile`.
- **psycopg2**, raw SQL, no ORM — same philosophy as `inventory-service`
  (Go, `database/sql`, no ORM) for a domain this small.
- **`scripts/init.sql`**, run idempotently at startup (`ensure_schema`),
  mirroring `inventory-service`'s own boot-time schema pattern instead of
  introducing Alembic for one table.

Storage lives in a new `notification_schema` (added to
`init-scripts/01-create-schemas.sql`, same shape as the other five schemas),
modeled generically as a processed notification — `event_type`,
`aggregate_id`, `status`, `payload JSONB`, `created_at`, `processed_at` — not
coupled to the concept of "email", so a future channel doesn't need a schema
change.

### NotificationSender: one implementation, one observable effect

`NotificationSender` is a `Protocol` with a single `send(notification)`
method. `FakeNotificationSender` is the only implementation: it persists the
notification via `NotificationRepository`. That Postgres row **is** the
chosen observable effect — inspectable directly by querying
`notification_schema.notifications`, with no extra inspection endpoint added
(keeping the public surface as small as the auth-service endpoint above).

### Consumer flow (`app/consumer.py`, `app/rabbitmq.py`)

Binds `notification.order.created.queue` to the existing `order.exchange` /
`order.created` routing key — no changes to `orders-service`, no new event,
no contract change. `process_order_created` is pure and synchronous, and
never raises:

- **Success** → `status="SENT"`, payload carries `userId`, `email`,
  `firstName`, `lastName`, `totalAmount`.
- **Profile not found (404) or lookup failure (timeout/connection error)**
  → `status="FAILED"`, payload carries `userId` and the error reason.

Either way, `sender.send(...)` is called and the message is acked
unconditionally — consistent with every other RabbitMQ consumer in this
project (see the Roadmap's existing "no retry limit/backoff/DLQ" entry): a
malformed or unprocessable message is logged, not retried, and does not loop
forever. This ADR does not close that gap; it just doesn't make it worse.

## A latent bug this surfaced, not fixed here

Wiring the new endpoint into `auth-service`'s `SecurityConfig` required
reading the whole file, which showed the same defect ADR-0013 found and
fixed in `billing-service`: the custom `SecurityFilterChain` bean declares
`anyRequest().authenticated()` but never calls `.httpBasic(...)` (or any
other authentication mechanism) — building a custom filter chain opts out of
Spring Boot's automatic HTTP Basic setup entirely. Unlike billing-service's
case, this is currently **latent, not active**: every existing endpoint in
`auth-service` (`/ping`, `/health`, `/signup`, `/login`, `/verify-email`, and
now `/users/*/notification-profile`) is already `permitAll()`-ed, so nothing
in `auth-service` today actually falls through to `anyRequest().authenticated()`.
The bug only becomes reachable the day a genuinely protected endpoint is
added to this service. Flagged to the requester and deliberately left
unfixed here — out of this task's scope, per this project's TDD discipline
of not fixing bugs found outside a task's stated boundary without explicit
authorization.

## Verification

- Unit tests (`tests/test_consumer_domain.py`): domain behavior against
  in-process stubs, no real infrastructure — success and profile-lookup
  failure paths.
- Integration test (`tests/test_order_created_flow.py`, marked
  `@pytest.mark.integration`): real Postgres, real RabbitMQ, and a real
  running `auth-service`, exercised by publishing a realistic `order.created`
  body directly onto `order.exchange` (the same "seed prerequisite state /
  publish the real event shape directly" pattern this project's own Phase 2
  wiring tests already use) and polling `notification_schema.notifications`
  until the row appears — no fixed sleep.
- CI: new Phase 1 job (`notification`, Python 3.12, `pytest -m "not integration"`)
  and new Phase 3 job (`e2e-notification-flow`, builds and starts a real
  `auth-service`, then runs `pytest -m integration` against it), both green,
  with no regression in the eleven pre-existing jobs.
- `docker compose up -d --build`: all seven implemented services
  (api-gateway, auth, products, inventory, orders, billing,
  notification-service) plus Postgres/RabbitMQ reported `healthy`
  simultaneously; `curl http://localhost:8086/health` returned
  `{"status":"OK","service":"notification-service"}`.

## Consequences

**Positive**: the project's first fully event-driven, HTTP-enriched
consumer service, following every existing architectural rule (async only
via RabbitMQ, sync only via public HTTP, no shared DTOs/libraries, no direct
cross-service database access) without requiring any exception to them.

**Not fixed here / known limitations**:
- No retry/backoff/DLQ on the new consumer — same accepted gap as every
  other RabbitMQ consumer in this project (see README Roadmap).
- No Alembic or other versioned migration tool for the Python service —
  `scripts/init.sql` is idempotent but not versioned, matching
  `inventory-service`'s own level of simplicity, not Flyway's.
- `auth-service`'s `SecurityConfig` is missing `.httpBasic()` (or any other
  auth mechanism) for its `anyRequest().authenticated()` fallback — currently
  latent (no endpoint reaches it), but will misbehave exactly like
  billing-service did (ADR-0013) the moment a protected endpoint is added.
- Delivery is fake by design (a Postgres row, not an email/SMS/push send).
  Future evolution — a real `SendGridNotificationSender`, SMS, or push
  channel, potentially several `NotificationSender` implementations
  dispatched together — is deliberately left for a later, separate task.

## References

- [ADR-0007](0007-remove-kafka-broker.md) — the RabbitMQ topic-exchange model
  this service's consumer follows exactly (`order.exchange`/`order.created`).
- [ADR-0010](0010-uniform-service-healthchecks.md) — the uniform
  unauthenticated `/health` convention this service's `GET /health` follows.
- [ADR-0013](0013-ci-phase-3-cross-service-e2e.md) — the same
  `anyRequest().authenticated()`-without-`.httpBasic()` defect class, found
  and fixed there for billing-service; found again here for auth-service,
  but left unfixed since it isn't currently reachable.
