# ADR-0022: notification-service consumption resilience (retry, backoff, dead-lettering)

## Status

Accepted - 2026-07-08

## Context

ADR-0019 gave `products-service`, `orders-service`, and `billing-service`
a uniform retry/backoff/DLQ policy, built natively on Spring Boot's
listener retry support. `notification-service`'s own `pika` consumer was
explicitly left out of that ADR's scope and tracked separately: it always
acknowledged a message regardless of outcome, so a message that failed
for a genuinely unexpected reason (a malformed body, an unhandled
exception) was logged once and silently dropped — never retried, never
parked anywhere for inspection.

Before writing anything, this change re-confirmed that gap still existed:
`app/rabbitmq.py`'s `on_order_created`/`on_order_status_changed`
callbacks both called `ch.basic_ack(...)` unconditionally in a `finally`
block, regardless of whether the `try` body raised. In practice this
almost never fired, since `process_order_created`/
`process_order_status_changed` already catch every *expected* failure
(an unknown user, a missing prior notification) internally and return a
`FAILED` `Notification` instead of raising — but a genuinely unexpected
exception (malformed JSON, an unreachable Postgres, a bug) would still be
silently swallowed.

Pika (the library `notification-service` uses) has no equivalent to
Spring AMQP's `SimpleRabbitListenerContainerFactoryConfigurer`/
`RetryTemplate`/`MessageRecoverer` — there is no built-in retry template
to configure. The policy has to be built directly on RabbitMQ's own
primitives instead. This project has exactly one Python message
consumer, so a generic, reusable "retry framework" would be solving a
problem this codebase doesn't have — this change was deliberately scoped
narrowly to avoid exactly that, and the same "prefer a small, direct, one-off
implementation over a generalized mechanism" principle already governs
the rest of this project's simplicity choices (see
[architectural-principles.md](../architecture/architectural-principles.md)).

## Decision

**Same numbers as ADR-0019, for direct conceptual comparability**: 3 max
attempts, 200ms initial backoff, 2.0 multiplier, 2000ms cap. Not a
requirement of Pika or RabbitMQ — a deliberate choice to make the two
services' policies easy to compare, since the goal was equivalent
*behavior*, not equivalent *implementation*.

**RabbitMQ-native retry via a wait queue with per-message TTL**, not a
manual sleep or a polling loop:
- One retry exchange (`notification.retry.exchange`, topic) and one
  retry queue (`notification.retry.queue`), declared once alongside the
  existing `order.created`/`order.status-changed` queues.
- The retry queue's own `x-dead-letter-exchange` points back at
  `order.exchange` (no override routing key) — RabbitMQ re-publishes an
  expired message using the same routing key it entered the retry queue
  with, so it lands back on whichever original queue (`order.created` or
  `order.status-changed`) it came from.
- A failed message is republished to `notification.retry.exchange` using
  its **original** routing key (captured from `method.routing_key`) and a
  `BasicProperties.expiration` set to the current backoff delay in
  milliseconds — a **per-message** TTL, not a fixed queue-level one, so a
  single shared retry queue can still produce a genuinely exponential
  delay across attempts (200ms, then 400ms) rather than a flat one. An
  `x-notification-attempts` header, incremented on each retry, is the
  only state carried between attempts — no external counter, no
  database row, no scheduler.
- After the 3rd failed attempt, the message is published directly to
  `notification.dlx`/`notification.dlq` (mirroring the `<service>.dlx`/
  `<service>.dlq` naming ADR-0019 already established) instead of being
  retried again.
- A successful attempt just acks normally, exactly as before — no
  behavior change on the happy path.

**The business functions remain unaware this exists**: `process_order_created`
and `process_order_status_changed` were not touched. The retry/attempt-count/
DLQ logic lives entirely in `app/rabbitmq.py`, in one small helper
(`_route_to_retry_or_dlq`) called from both callbacks' `except` blocks —
the same "infrastructure owns retry, business code just succeeds or
raises" separation ADR-0019 established for the Spring side.

**No generic framework**: a single shared helper function, not a class
hierarchy, plugin system, or reusable library — this service has exactly
one consumer process and two callbacks, and both needed the identical
policy, so one function covers both without introducing an abstraction
for hypothetical future consumers.

## Verification

- New `tests/test_consumer_resilience_flow.py` (real Postgres/RabbitMQ,
  `pytest -m integration`):
  - A message that fails `MAX_ATTEMPTS - 1` times then succeeds
    eventually produces a `SENT` notification, and the injected failure
    collaborator was called exactly `MAX_ATTEMPTS` times (proving retry
    actually happens and stops once it succeeds).
  - A message that always fails is attempted exactly `MAX_ATTEMPTS`
    times, never produces a persisted notification, and is found on the
    real dead letter queue instead — proving both "no infinite retry"
    and "no silent loss" in the same test.
  - Found and fixed a test-isolation bug while writing these: every `*IT`
    test in this file leaves its own consumer thread running for the rest
    of the process (an existing, previously-tolerated pattern in this
    test suite, since older tests' behavior didn't depend on call
    counts). A per-instance failure counter gave the wrong count when
    RabbitMQ round-robinned a later test's delivery to an earlier test's
    still-running consumer thread. Fixed by keying failure/attempt state
    by order id in shared module-level state instead of per object
    instance, making the simulated failure behave correctly regardless of
    which thread actually executes it.
- Full suite: 17 tests passed (9 domain/unit, 8 real-infrastructure
  integration) — up from 15 before this ADR.
- Live verification against the rebuilt Docker Compose stack: confirmed
  via the RabbitMQ management API that `notification.retry.queue` and
  `notification.dlq` are declared correctly on a fresh container start.
- While validating, inspected `products.dlq` (populated by ADR-0019's
  policy, already live in production) and found 3 real dead-lettered
  messages: `products-service`'s `UserEventConsumer.handleUserVerified`
  assumes every `user.verified` event is for a `SELLER` and has no role
  filter (unlike its sibling `handleUserRegistered`/`handleJwtCreated`),
  so every `BUYER`'s email verification throws `Seller not found` and is
  now correctly dead-lettered instead of looping forever — evidence
  ADR-0019's policy works in practice, and a genuine, separate bug this
  ADR does not fix (`products-service` is out of scope here).

## Consequences

**Positive**: `notification-service` no longer silently drops a message
on an unexpected failure. Its consumption policy is now conceptually
equivalent to the Spring services' — bounded retry, exponential backoff,
terminal dead-lettering — despite using a structurally different
mechanism appropriate to the library actually available in Python.

**Negative / known limitations**:
- The retry delay is enforced by RabbitMQ's per-message TTL expiry, which
  is not a hard real-time guarantee (RabbitMQ documents TTL expiry as
  "at least this long," not exact) — acceptable here, matching this
  project's existing tolerance for approximate timing elsewhere (e.g. the
  Outbox pattern's 5-second poll).
- `products-service`'s `handleUserVerified` role-filter bug (found above)
  remains unfixed — tracked as its own follow-up, out of this ADR's
  scope.
- Like ADR-0019's dead letter queues, `notification.dlq` is a terminal
  parking spot, not a replay mechanism — nothing currently re-drives a
  message off it.

## Update — 2026-07-09

The `products-service` `handleUserVerified` gap named above as unfixed has
since been closed: `UserEventConsumer.handleUserVerified` now treats "no
matching Seller row" as an expected no-op (`ifPresentOrElse`) instead of
throwing, since `auth-service` publishes `user.verified` as a bare userId
with no role field to filter on. See the README Roadmap for the closing
entry; this fix did not warrant its own ADR, as it corrects a single
consumer method to match the behavior its siblings already had, rather
than introducing a new architectural decision.

## References

- [ADR-0019](0019-message-consumption-resilience.md) - the Spring-side
  policy this ADR mirrors conceptually; its Update section records this
  gap as closed.
- [ADR-0017](0017-notification-service-startup-resilience.md) - the
  connection-level resilience (surviving a slow/restarting broker) this
  ADR is distinct from; that ADR's Context explicitly called out this
  per-message gap as a separate, then-still-open concern.
- [docs/architecture/architectural-principles.md](../architecture/architectural-principles.md) -
  avoiding a generic retry framework for a single consumer process.
