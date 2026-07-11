# ADR-0029: activating the order fulfillment lifecycle (ship/deliver)

## Status

Accepted - 2026-07-11

## Context

`OrderStateMachineConfig` already declared real transitions for
`PAYMENT_APPROVED -> SHIPPED` (`SHIP_ORDER`) and `SHIPPED -> DELIVERED`
(`DELIVER_ORDER`), but nothing in the codebase ever dispatched either
event: no controller endpoint, no service method, no test. `SHIPPED` and
`DELIVERED` were configured states with no path to reach them — the same
category of finding this project already treats as technical debt
(structurally inert configuration).

A related, independent inconsistency existed alongside it:
`OrderService.canCancel()` hand-listed the states it considered
cancellable, and that list disagreed with the state machine's own
configured `CANCEL_ORDER` transitions for `PAYMENT_APPROVED` — a second,
drifted source of truth for the same question.

## Decision

**Activate the existing transitions rather than removing them.** The
event (`order.status-changed`), its publisher
(`OrderService.publishOrderStatusChanged`), the notification-service
consumer, and the frontend's state-to-badge mapping already supported
`SHIPPED`/`DELIVERED` with zero changes required — removing the states
would have discarded infrastructure already paid for, for no
simplification benefit.

**Single source of truth for transition eligibility.**
`OrderStateMachineService.isTransitionAllowed(OrderState, OrderEvent)` is
derived once, directly from the state machine factory's own configured
transition graph (`StateMachine.getTransitions()`), cached, and reused by
`cancelOrder`, `shipOrder`, and `deliverOrder` alike. `canCancel()`'s
hand-written list is gone; there is exactly one place that knows which
transitions are legal.

**Ship is a platform-operations action, not a seller action.**
`OrderItem` has no `sellerId` — an order can span more than one seller's
products, so "the seller of this order" is not a well-defined question.
Rather than approximate it (e.g. any seller owning at least one line item,
which would still act on the whole order rather than their own item),
`shipOrder` is authorized by the `ADMIN` role: a single, unambiguous actor
per order, consistent with how a real fulfillment/warehouse operation
would work regardless of how many sellers contributed items. This is the
first endpoint in this project authorized by role rather than ownership.
`deliverOrder` stays ownership-gated exactly like `cancelOrder`, since the
buyer is always a single, well-defined identity (`order.userId`).

**Closing a self-service privilege-escalation path this decision
exposed.** `UserService.validateAndConvertRole` (auth-service) accepted
any `UserRole.valueOf()`-able string from the public `/signup` endpoint,
including `ADMIN`. This was harmless while no endpoint checked the
`ADMIN` role; it stopped being harmless the moment `shipOrder` started
checking it. Signup now rejects any role other than `BUYER`/`SELLER`. The
one operations account is bootstrapped at application startup
(`AdminAccountInitializer`, an `ApplicationRunner`) from `ADMIN_EMAIL`/
`ADMIN_PASSWORD` environment variables if it doesn't already exist — never
through the public signup path, and no credential (hashed or plaintext)
is committed to a migration or any other versioned file. A companion
migration removes the placeholder `admin@easydora.com` row seeded by
`V1`/`V3`, whose `password_hash` was a hand-typed, never-actually-hashed
string that had never let anyone log in.

**No new event type.** `order.status-changed`'s existing payload
(`orderId`, `previousState`, `newState`) is sufficient — the minimal ship/
deliver design carries no extra data (no tracking number, no carrier, no
address). `notification-service`'s consumer is already fully generic over
`newState` (no per-state branch, no enum whitelist), so it required no
code change at all. A dedicated event type would have added exchange/
binding/class boilerplate for a distinction this design has no consumer
that needs.

**Read model reuses existing infrastructure.** The fulfillment queue
(`GET /orders/fulfillment`, orders awaiting shipment) is
`OrderRepository.findByState(OrderState.PAYMENT_APPROVED)` — already
present on the repository — with no join and no new table, precisely
because ship is not seller-scoped.

## Verification

- TDD, red-green throughout: `OrderStateMachineServiceTransitionTest`
  (loads the real `OrderStateMachineConfig`, proving
  `isTransitionAllowed` reflects the actual graph, including the
  `PAYMENT_APPROVED`/`CANCEL_ORDER` case `canCancel()` used to get wrong);
  `OrderServiceShipDeliverTest`; `OrderFulfillmentControllerTest` (role
  gate for ship/fulfillment queue, ownership gate for deliver);
  `UserServiceSignupRoleTest`; `AdminAccountInitializerTest`.
- A live run against a real Postgres/RabbitMQ stack caught a real bug this
  test suite's mocks could not: `previousState` was read from the same
  `Order` entity *after* calling `OrderStateMachineService.sendEvent`,
  which — within the same transaction — mutates that exact
  Hibernate-managed instance in place. Every affected method
  (`cancelOrder`, `shipOrder`, `deliverOrder`) was publishing
  `previousState == newState`. Fixed by capturing `previousState` before
  `sendEvent` is called; reproduced and locked down by
  `OrderServicePreviousStateTest`, which simulates the shared-entity
  mutation without needing a live database.
- Live end-to-end: an admin account (bootstrapped via environment
  variables) shipped a `PAYMENT_APPROVED` order; a buyer account
  (ownership-checked) confirmed its delivery; a buyer attempting to ship,
  and a non-admin attempting to read the fulfillment queue, both got 403;
  a premature delivery attempt got a clean 400 with the real state in the
  message; the fulfillment queue no longer listed the order once shipped;
  a public signup attempt with `role: "ADMIN"` was rejected while
  `BUYER`/`SELLER` signups kept working.

## Consequences

**Positive**: closes a Roadmap item describing configured-but-unreachable
state machine transitions. Introduces the project's first role-based (as
opposed to ownership-based) authorization check, alongside — not
replacing — the existing ownership model. Fixes a real, previously
undetected `previousState` bug affecting every `order.status-changed`
event this project has ever published, including `cancelOrder`. Closes a
latent self-service admin registration path before it became exploitable.

**Negative / known limitations**:
- Ship acts on the whole order, never a single seller's line items. An
  order with products from multiple sellers is shipped as one unit by
  platform operations; true per-seller partial fulfillment is not
  supported and is out of scope for this stage.
- The `ADMIN` role remains otherwise unused elsewhere in the project —
  this ADR gives it its first real, checked meaning, but there is still
  no general-purpose admin UI or admin-only endpoint beyond fulfillment.
- `AdminAccountInitializer` only ever creates the account; it does not
  update an existing account's password if `ADMIN_PASSWORD` changes after
  the first boot. Rotating the operations credential requires updating it
  directly (or dropping the row and letting the initializer recreate it).

## References

- [ADR-0007](0007-remove-kafka-broker.md) - the RabbitMQ-only messaging
  pattern `order.status-changed` already belongs to, reused unchanged
  here.
- [ADR-0027](0027-jwt-principal-as-sole-identity-source.md) - identity for
  business decisions comes exclusively from the JWT principal; this ADR's
  role check follows the same principal, just on `role` instead of
  ownership.
- [architectural-principles.md](../architecture/architectural-principles.md)
  - avoiding dead/hybrid configuration and preferring the smallest change
  that reuses existing mechanisms, both cited directly in the Decision
  section above.
- README Roadmap - the High item this ADR closes.
