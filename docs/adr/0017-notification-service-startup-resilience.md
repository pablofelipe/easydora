# ADR-0017: notification-service survives a slow/restarting RabbitMQ and Postgres

## Status

Accepted - 2026-07-08

## Context

While producing the end-to-end business-flow walkthrough (`docs/walkthrough.md`),
step-by-step validation against a real, freshly-started `docker compose up -d --build`
stack surfaced a real defect blocking the documented flow: after creating a
real order and confirming `billing-service` produced a real `Payment`,
`notification-service` never processed the same `order.created` event at
all. `notification_schema.notifications` had zero rows for that order, and
`rabbitmqctl list_queues` showed `notification.order.created.queue` didn't
even exist.

The root cause, found in `docker compose logs notification-service`:
`app/rabbitmq.py`'s `run_consumer()` made exactly one connection attempt to
RabbitMQ at container startup, in a daemon thread with no supervisor. When
that attempt raced ahead of RabbitMQ actually being ready to accept
connections — a real race with `docker-compose.yml`'s own
`depends_on: condition: service_healthy` ordering — the thread died with an
uncaught `pika.exceptions.AMQPConnectionError` and never tried again. The
container's own `HEALTHCHECK` only covers FastAPI's `GET /health`, which has
nothing to do with this thread, so the container reported `healthy`
indefinitely while its RabbitMQ consumer was permanently dead. A later
broker restart mid-run would kill the same thread the same way, for the
rest of the container's life.

This is a stricter failure than the already-documented "no retry/DLQ for
individual poison messages" gap (README Roadmap): that gap only drops one
malformed message; this one drops every message, forever, the first time
the broker is even briefly unavailable.

`ensure_schema()` (`app/schema.py`), which runs synchronously against
Postgres before the app starts serving anything, has the same shape of
risk for the same reason (a container can start slightly before Postgres
is fully ready despite the healthcheck-based ordering), though the failure
mode there is louder (the whole app fails to start) rather than silent.

## Decision

Two matching fixes, both bounded to "surviving a slow or restarting
dependency at process start (or later)" — no change to how individual
message failures are handled, which remains the accepted, documented gap
it already was:

- **`app/rabbitmq.py`, `run_consumer()`**: wrapped the whole
  `connect() -> declare_topology() -> consume_forever()` cycle in a loop
  that never gives up. Any failure — at startup or from a later
  disconnect — is logged and retried after a fixed 5-second delay,
  indefinitely. There is no restart policy configured for any service in
  `docker-compose.yml`, so a bounded-then-crash strategy (as
  `inventory-service`'s Go consumer uses, see below) would leave this
  container silently broken forever with nothing to restart it; retrying
  forever inside the running process is the correct fit for this
  project's current setup.
- **`app/schema.py`, `ensure_schema()`**: added a bounded retry (10
  attempts, 3 seconds apart) around the initial Postgres connection,
  mirroring the shape (not the unbounded nature) of the RabbitMQ fix. This
  step must succeed before the app does anything else, so failing loudly
  after exhausting all attempts (re-raising the original
  `psycopg2.OperationalError`) is still correct — retrying forever here
  would just hide a real, permanent misconfiguration behind endless
  silent retries.

Both fixes mirror an already-established pattern in this same repository:
`inventory-service/internal/messaging/rabbitmq_consumer.go` already retries
its initial RabbitMQ connection up to 10 times, 3 seconds apart, before
giving up (and crashing the whole Go process via `log.Fatal`, a valid
choice there since a fresh Go binary restart is cheap and the failure is
visible in the process's own exit code). The four Spring services don't
need an equivalent fix: `spring-boot-starter-amqp`'s `CachingConnectionFactory`
already retries broker connections and reconnects on disconnects by
default, so this class of bug simply doesn't reach them.

## Verification

- Two new unit test files (`tests/test_rabbitmq_reconnect.py`,
  `tests/test_schema_retry.py`), all mocked, no real infrastructure
  needed: prove `run_consumer` retries past both a startup failure and a
  mid-run disconnect without giving up, and that `ensure_schema` retries
  transient failures but still raises after exhausting all attempts.
- Existing real-infrastructure integration tests
  (`tests/test_order_created_flow.py`, `pytest -m integration`) re-run
  green against a live Postgres/RabbitMQ/auth-service.
- Live reproduction: a genuine `docker compose down` (container loss, not
  a deliberate teardown) followed by `docker compose up -d --build`
  reproduced the exact original race on the first connection attempt —
  the logs now show `RabbitMQ connection lost or unavailable; retrying in
  5s` followed by a successful reconnect a few seconds later, instead of
  the thread dying. The full business flow (signup through payment and
  notification) was then re-run end to end against that same stack,
  producing a real `SENT` notification row for a real order — see
  `docs/walkthrough.md` for the exact commands and responses.

## Consequences

**Positive**: the documented end-to-end flow (`docs/walkthrough.md`) is now
genuinely reproducible from a clean `docker compose up -d --build` without
manual intervention. notification-service also now recovers from a
mid-run RabbitMQ restart, which it never could before regardless of the
startup race specifically.

**Not fixed here / known limitations**:
- Per-message failure handling is unchanged: a malformed message or a
  failed `auth-service` lookup still doesn't retry or land in a
  dead-letter queue (this was already a documented, accepted gap, see
  README Roadmap — this ADR does not touch it).
- The 5-second RabbitMQ retry delay is fixed, not exponential-backoff —
  acceptable for this project's scale; worth revisiting if this pattern
  is reused somewhere with a noisier failure mode.
- notification-service still has no public API to inspect a notification
  directly (only the Postgres row) — unchanged from ADR-0014; a minimal
  read endpoint remains a candidate for future work, out of scope here.

## Update — 2026-07-08

Both limitations named above are now closed by later ADRs, not this one:
per-message retry/backoff/dead-lettering was added by
[ADR-0022](0022-notification-service-consumption-resilience.md); the
read-only `GET /notifications/{orderId}` endpoint was added by
[ADR-0020](0020-notification-domain-completion.md). This update only
records that the gaps this ADR named are resolved elsewhere — it doesn't
change anything this ADR itself decided about startup/reconnection
resilience.

## References

- [ADR-0014](0014-notification-service.md) — the original implementation
  this ADR hardens; also the source of the "no public inspection API"
  limitation reiterated above.
- `inventory-service/internal/messaging/rabbitmq_consumer.go` — the
  existing in-repo precedent for bounded startup-connection retry this
  ADR's shape (not its unbounded nature) is based on.
