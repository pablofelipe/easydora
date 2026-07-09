# ADR-0021: Payment outcome integration — billing-service publishes payment.approved/payment.failed

## Status

Accepted - 2026-07-08

## Context

`billing-service` persisted payments but never published anything back
onto RabbitMQ once a payment resolved. `orders-service` already had the
state-machine methods for this — `OrderService.handlePaymentReceived`/
`handlePaymentFailed`, which transition `INVENTORY_RESERVED` into
`PAYMENT_APPROVED`/`PAYMENT_FAILED` and publish `order.status-changed` —
but nothing in the codebase ever called them. ADR-0001 (finding 5)
documents why: a prior `PaymentEventProducer`/`PaymentProcessedEvent`/
`PaymentEventsConsumer` set existed, was dead code, and was removed
because it was also broken on arrival (`PaymentProcessedEvent.orderId`
was typed `Long`, while `Order.id` in `orders-service` is a `String`
UUID — the types could never have matched). That ADR explicitly left
`handlePaymentReceived`/`handlePaymentFailed` in place, noting they were
"unreachable now, until wired by some other means." ADR-0020 (completing
the notification domain) re-confirmed the gap while investigating a
different question, and deferred fixing it to this ADR.

Before writing anything, this change re-verified both halves of that
finding still held: no producer of any payment outcome event exists
anywhere in `billing-service`, and `handlePaymentReceived`/
`handlePaymentFailed` still have zero callers in the codebase. No
adequate existing event could be reused for the missing link — the only
prior attempt was removed specifically for being incompatible with
`orders-service`'s real `orderId` type, so reusing its shape would repeat
the same defect. A new, correctly-typed event was necessary, and its
predecessor's removal is the justification for why it's new rather than
resurrected.

Writing the first tests that ever exercised `handlePaymentReceived`/
`handlePaymentFailed` surfaced a second, unrelated pre-existing bug: both
methods published `order.status-changed` with `previousState` hardcoded
to `OrderState.PENDING`, regardless of the order's actual prior state.
Per the state machine (`OrderStateMachineConfig`), the only valid source
state for either transition is `INVENTORY_RESERVED` — so every payment
outcome notification would have carried an incorrect `previousState`.
This was invisible before because nothing had ever called these methods;
it does not affect the state machine itself (the *new* state was always
computed correctly from `stateMachineService.getCurrentState`), only the
`previousState` value in the published event and, downstream, in the
resulting notification's payload.

## Decision

**New event, minimal shape**: `PaymentEvent` (`orderId`, `transactionId`,
`failureReason`), duplicated in both services under their own
`event`/`messaging.events` packages — following this project's existing
precedent (ADR-0002) of duplicating small event DTOs per service rather
than sharing a library, since each service stays independently buildable
and the duplication here is a few fields, not a maintenance burden.

**Reused `order.exchange`, no new exchange**: `billing-service` publishes
`payment.approved`/`payment.failed` on the same `order.exchange` it
already had a `TopicExchange` bean for (used to consume `order.created`).
This mirrors the existing precedent of `stock.reserved`/
`stock.insufficient` — an outcome event published by a *different*
service (`inventory-service`) onto `order.exchange` because it's
semantically part of the order's lifecycle, not the publishing service's
own domain. Payment outcomes fit the identical shape.

**`orders-service`'s `PaymentEventsConsumer`** is a new, small, thin
consumer class (mirroring `InventoryEventsConsumer`'s shape) with two
`@RabbitListener` methods, one per routing key, each doing nothing but
call the corresponding existing `OrderService` method. No business logic
was duplicated or reimplemented — this is purely the missing wire.

**Bug fix, not a new design**: `handlePaymentReceived`/
`handlePaymentFailed` now capture `OrderState previousState = order.getState()`
before triggering the state machine transition, instead of hardcoding
`OrderState.PENDING` — the same pattern this same file's `cancelOrder`
method already used correctly. Confirmed with the user before applying,
since it touches pre-existing logic beyond just adding a caller.

**No new abstraction**: no strategy/factory/registry was introduced to
generalize "different order-affecting outcome events." The payment path
reuses the exact same `publishOrderStatusChanged` private method,
`OrderStatusChangedEvent` class, and `order.exchange` binding the stock
outcome path already established — `notification-service` required zero
changes to react to it, since it already consumes `order.status-changed`
regardless of which upstream transition produced it (ADR-0020).

## Verification

- New behavior tests (`PaymentEventPublishBehaviorTest` in
  `billing-service`, `PaymentEventsConsumerBehaviorTest` in
  `orders-service`, both pure Mockito): a `Payment` resolved to `APPROVED`
  publishes `payment.approved` with the right `orderId`/`transactionId`;
  one resolved to `FAILED` publishes `payment.failed` with the right
  `failureReason`; a still-`PENDING` payment publishes nothing;
  `PaymentEventsConsumer` calls `handlePaymentReceived`/
  `handlePaymentFailed` with the event's `orderId`.
- New `PaymentOutcomeWiringIT` (`orders-service`, real Postgres/RabbitMQ):
  publishing a real `payment.approved`/`payment.failed` message moves a
  seeded `INVENTORY_RESERVED` order to `PAYMENT_APPROVED`/`PAYMENT_FAILED`,
  and — via a dedicated test-only probe queue bound to
  `order.status-changed` — confirms a real `order.status-changed` message
  is actually published with `previousState: "INVENTORY_RESERVED"` and
  the correct `newState`, not just inferred from the resulting order
  state.
- Full `mvn verify` for `orders-service` and `billing-service` against
  the project's real `docker-compose` Postgres/RabbitMQ passed.
- Live end-to-end run against the rebuilt Docker Compose stack: signup,
  product, order, and automatic stock reservation were driven through
  public APIs; `POST /api/payments/process` resolved a real payment to
  `APPROVED`; `GET /{orderId}` confirmed the order moved to
  `PAYMENT_APPROVED`; `GET /notifications/{orderId}` returned three real
  notifications in order — `order.created`, `order.status-changed`
  (`PROCESSING` → `INVENTORY_RESERVED`), and `order.status-changed`
  (`INVENTORY_RESERVED` → `PAYMENT_APPROVED`) — the last produced with no
  code change to `notification-service` at all.

## Consequences

**Positive**: the payment decision now travels through the same
event-driven architecture as every other outcome in this system, all the
way to an observable, queryable notification. `OrderService.handlePaymentReceived`/
`handlePaymentFailed` are no longer dead-adjacent code — they have a real
caller and, for the first time, real test coverage. The latent
`previousState` bug is fixed as a side effect of finally being able to
observe it.

**Negative / known limitations**:
- `PaymentService.processPayment`'s outcome is a random 90%-approval
  simulation with no seam to force a specific branch, so no automated
  test forces `processPayment` itself down the `FAILED` path against a
  live broker — the `FAILED` branch is covered deterministically via
  `PaymentOutcomeWiringIT` (which publishes the event directly) and via
  `PaymentEventPublishBehaviorTest`, not by observing the random
  simulation choose it.
- `POST /api/payments/{orderId}/retry` (resets a `FAILED` payment back to
  `PENDING`) does not publish anything — retrying a payment doesn't move
  the order's state machine back to `INVENTORY_RESERVED` either, so a
  retried-then-approved payment would not currently produce a fully
  consistent state trail. Out of scope here; not currently part of any
  documented flow.
- `notification-service`'s Python consumer retry/DLQ gap remains separate
  and untouched (tracked in the README Roadmap).

## References

- [ADR-0001](0001-messaging-wiring-audit.md) (finding 5) — removed the
  previous, incorrectly-typed `PaymentEventProducer`/`PaymentProcessedEvent`/
  `PaymentEventsConsumer`; the reason this ADR introduces a new event
  instead of reusing anything.
- [ADR-0007](0007-remove-kafka-broker.md) — the `stock.reserved`/
  `stock.insufficient`-on-`order.exchange` precedent this ADR's routing
  choice mirrors.
- [ADR-0020](0020-notification-domain-completion.md) — found this exact
  gap while completing the notification domain; this ADR closes it.
- [docs/architecture/architectural-principles.md](../architecture/architectural-principles.md) —
  reusing an existing exchange/event shape instead of introducing a new
  abstraction for "payment outcome" as its own concept.
