# ADR-0020: Complete the notification domain — consume order.status-changed, add a read-only API

## Status

Accepted - 2026-07-08

## Context

`notification-service` already consumed `order.created`, enriching it via
a real HTTP call to `auth-service` and persisting one observable
notification row. `order.status-changed` (published by `orders-service`
on every state-machine transition) had no consumer at all — a gap
tracked in the README Roadmap and ADR-0001's Update, which had already
settled the destination question (`notification-service`) without
implementing it.

Inspecting the codebase before writing anything surfaced one real
divergence: unlike `OrderCreatedEvent`, `OrderStatusChangedEvent`
(`orders-service/src/main/java/com/easydora/orders/event/OrderStatusChangedEvent.java`)
carries only `orderId`, `previousState`, and `newState` — no `userId`. The
existing `order.created` enrichment pattern (look up the user by id, call
auth-service) had nothing to key off of for this event. Three options
were considered:

1. Reuse the email/name already captured by that same order's
   `order.created` notification, queried from `notification-service`'s
   own table (same schema, no cross-service call).
2. Add `userId` to `OrderStatusChangedEvent` in `orders-service`.
3. Have `notification-service` call `orders-service`'s public
   `GET /{orderId}` to resolve the `userId`, then proceed as
   `order.created` does.

Option 1 was chosen: it requires no change to `orders-service` (out of
this ADR's scope), no cross-schema access, and adds no second synchronous
call — preserving `docs/architecture/overview.md`'s "notification-service's
call to auth-service is the one synchronous exception" characterization
instead of adding a second one.

The rest of the codebase was already well-suited to this addition:
`notification_schema.notifications` is modeled generically (`event_type`,
`aggregate_id`, `status`, `payload`) with no coupling to a specific event
shape, so no schema migration was needed — only a new query method.

## Decision

**Consumption**: `app/rabbitmq.py` binds a second queue
(`notification.order.status-changed.queue`) to `order.exchange`/
`order.status-changed`, alongside the existing `order.created` one, both
served by the same `consume_forever` loop (no change to the
retry/reconnect behavior ADR-0017 already established).

**Enrichment**: `app/consumer.py`'s new `process_order_status_changed`
looks up the most recent `order.created` notification for the same
`orderId` via a new `NotificationRepository.find_by_aggregate_id` method,
reuses its `email`/`firstName`/`lastName`/`userId`, and persists a new
`SENT` notification with `previousState`/`newState` added to the payload.
If no prior `SENT` order.created notification exists (e.g.
notification-service was down when it was published, or that lookup
itself had failed), the outcome is recorded as `FAILED` with an
explanatory error — never raises, matching the existing failure-handling
philosophy for a failed profile lookup. **Every event produces its own
new notification row; none is ever updated or replaced** — an order
accumulates one row per relevant event over its lifetime.

**Public read API**: `GET /notifications/{orderId}` (`app/main.py`),
read-only, returns every notification persisted for that order in
chronological order. No edit/delete endpoint exists or is planned — this
service never mutates a notification once persisted. This replaces the
one step in `docs/walkthrough.md` that previously required a direct
Postgres query to confirm the flow.

**Notification templates remain implemented directly in code, not as
externalized configuration.** No template engine, template files,
database-backed template store, or dynamic configuration was introduced.
With two event types and one delivery channel (`FakeNotificationSender`),
a templating mechanism would add indirection this stage has no use for —
the project prioritizes simplicity and readability over runtime
configurability it doesn't currently need.

**Simplicity, no new abstraction**: no strategy/factory/registry was
introduced to generalize "different notification types." The new event
is handled by one new function (`process_order_status_changed`) and one
new repository method (`find_by_aggregate_id`), mirroring the shape of
the existing `order.created` handling rather than generalizing ahead of
need — consistent with this project's recurring preference for small,
concrete extensions over premature extensibility (see
[architectural-principles.md](../architecture/architectural-principles.md)).

## Verification

- New domain tests (`tests/test_consumer_domain.py`): a status-changed
  event with a prior `SENT` order.created notification produces a `SENT`
  notification reusing its user info; a prior `FAILED` order.created
  notification is treated the same as no prior notification (produces a
  `FAILED` status-changed notification, does not fabricate user info from
  an incomplete payload); no prior notification at all also produces
  `FAILED`, never raises.
- New real-infrastructure tests (`tests/test_order_created_flow.py`,
  `pytest -m integration`, real Postgres/RabbitMQ/auth-service): an
  `order.created` publish followed by a real `order.status-changed`
  publish for the same order produces two notification rows, the second
  correctly enriched from the first; a `order.status-changed` publish
  with no prior notification produces a `FAILED` row; `GET /notifications/{orderId}`
  returns both rows in order via a `TestClient` against the real
  `FastAPI` app; an unknown order id returns `404`.
- Live end-to-end run against the real Docker Compose stack (all 9
  containers rebuilt and healthy): signup, product creation, and order
  creation were driven through public APIs only; the automatic stock
  reservation transition (`PROCESSING` → `INVENTORY_RESERVED`) produced a
  real `OrderStatusChangedEvent` from `orders-service`, and
  `GET http://localhost:8086/notifications/{orderId}` returned both the
  `order.created` and `order.status-changed` notifications, the second
  correctly carrying the reused email/name and the real `previousState`/
  `newState` values — proving the field names/casing `orders-service`'s
  Jackson serialization actually produces match what the Python consumer
  expects. `GET /notifications/{unknown-id}` returned `404`.
- Full test suite: 15 tests passed (9 domain/unit, 6 real-infrastructure
  integration) — up from 8 before this ADR.

## Consequences

**Positive**: `notification-service` now consumes every event ADR-0001
designated it as the consumer for; the notification domain is complete
for the events that currently exist in the system. The walkthrough no
longer has any step requiring direct database access — every claim in it
is now verifiable through a public API. No new abstraction, shared
library, or configuration mechanism was introduced to get here.

**Negative / known limitations, found but explicitly not fixed here**:
- `billing-service` still doesn't publish any payment outcome event — not
  even an unconsumed one. `orders-service`'s own
  `handlePaymentReceived`/`handlePaymentFailed` methods (which would
  publish `order.status-changed` for exactly this transition) have no
  caller anywhere in the codebase today, confirmed by inspection. This
  means the payment lifecycle never actually reaches its final business
  state (`APPROVED`/`FAILED`) in this system as it stands, and
  `order.status-changed` is consequently never emitted for a payment
  outcome — a deeper gap than "event without a consumer," since the event
  itself is never triggered. Tracked in the README Roadmap as its own
  item, separate from the consumption gap this ADR closes.
- No other publish-without-consumer notification-relevant event exists
  elsewhere in the system (verified against
  [docs/architecture/overview.md](../architecture/overview.md)'s
  Exchanges & Events table) — `order.status-changed` was the only such
  gap.
- The read API is deliberately minimal: no pagination, no filtering by
  status/event type, no endpoint beyond `GET /notifications/{orderId}`.
  Acceptable at this project's scale; revisit if the notification volume
  per order or the number of API consumers grows.

## Update — 2026-07-08

The payment-outcome gap named above (`billing-service` never publishing
any event once a payment resolves, so `order.status-changed` was never
emitted for a payment transition) was closed the same day by
[ADR-0021](0021-payment-outcome-integration.md): `billing-service` now
publishes `payment.approved`/`payment.failed`, and `orders-service`'s
previously-uncalled `handlePaymentReceived`/`handlePaymentFailed` finally
have a real caller. `notification-service` required no changes to react to
the resulting `order.status-changed` events — it already consumes that
routing key regardless of which transition produced it.

## References

- [ADR-0021](0021-payment-outcome-integration.md) — closes the
  payment-outcome gap this ADR found and left open.
- [ADR-0001](0001-messaging-wiring-audit.md) — where `order.status-changed`'s
  destination was originally settled.
- [ADR-0014](0014-notification-service.md) — the original notification-service
  implementation this ADR extends.
- [ADR-0017](0017-notification-service-startup-resilience.md) — the
  consumer resilience behavior this ADR's new queue binding reuses
  unchanged.
- [ADR-0018](0018-persistence-strategy.md) — the schema-ownership
  convention this ADR respects by querying its own table instead of
  `orders_schema` directly.
- [docs/architecture/architectural-principles.md](../architecture/architectural-principles.md) —
  "a component must earn its place" / avoid premature abstraction,
  reflected in this ADR's choice not to introduce a strategy/registry for
  notification types.
