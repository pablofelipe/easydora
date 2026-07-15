# ADR-0034: payment compensation saga for approved-but-unfulfillable orders

## Status

Accepted - 2026-07-12

## Context

The main checkout flow works correctly end to end: `order.created` triggers
stock reservation and a pending `Payment`; once inventory is reserved, the
buyer processes payment and the order reaches `PAYMENT_APPROVED`. What was
never modeled is what happens when a payment is approved for an order that
can no longer be honored.

The literal scenario originally described — `Inventory Reserve →
Payment APPROVED → Inventory FAILED` — is not actually reproducible as
written: once `orders-service`'s state machine reaches `INVENTORY_RESERVED`,
there is no transition back to `INVENTORY_FAILED`, and `inventory-service`
only ever publishes one of `stock.reserved`/`stock.insufficient` per order,
atomically, inside the same Postgres transaction
(`postgres_repository.go`'s `ReserveStockForOrder`) — never both.

The real gap is narrower and different: `PaymentController.processPayment`
never checks the order's current state before approving a charge — by
design, since `billing-service` is the sole owner of `Payment`'s lifecycle
and this project deliberately avoids synchronous cross-service calls. The
frontend only shows the "Process payment" action once an order reaches
`INVENTORY_RESERVED`, but that is a UI convenience, not an enforced
invariant — any direct caller (Postman, e2e-tests, a future client) can call
`/api/payments/process` at any time. If that happens while an order is still
`PROCESSING` (before the stock outcome arrives) or after it has already
reached `CANCELLED`, `Payment` becomes `APPROVED` and orders-service's
`PaymentEventsConsumer` receives `payment.approved` for an order that cannot
be fulfilled. `OrderService.handlePaymentReceived` already detects this
today — `stateMachineService.sendEvent(orderId, PAYMENT_RECEIVED)` returns
`false` because no transition to `PAYMENT_APPROVED` exists from
`INVENTORY_FAILED`/`CANCELLED` — but the rejection was silently swallowed:
no log, no exception, no compensation. The message was simply acknowledged
as if nothing had happened.

A second, unrelated finding shaped this design: `OrderState.REFUNDING` and
`OrderEvent.INITIATE_REFUND`/`REFUND_COMPLETED` have existed, unused, since
this project's very first commit (`fc3e8bf`) — never wired into
`OrderStateMachineConfig`, never referenced by any consumer. This is the
same shape of gap [ADR-0029](0029-order-fulfillment-lifecycle.md) found with
`SHIPPED`/`DELIVERED`: dead scaffolding the original design already
anticipated, never connected.

## Alternatives considered

Seven strategies were evaluated before writing any code, per the ticket's
explicit request not to assume the obvious answer is correct:

- **Synchronous compensation (HTTP call, Orders → Billing)**: rejected —
  explicitly forbidden by this project's principles (no temporal coupling
  between services, no departure from the event-driven pattern already
  adopted everywhere else).
- **Immediate reversion**: rejected — would mean Orders writing directly to
  the `Payment` entity, violating "Billing is the sole owner of Payment's
  lifecycle" (already established, reinforced by
  [ADR-0031](0031-single-source-of-truth-for-payment-creation.md)). Also
  unrealistic: a real payment gateway must actually be called to reverse a
  charge, not have a field flipped from outside.
- **Saga Orchestrated**: rejected — would require a new coordinator
  component with its own state (where would it live? which service owns
  it?). No flow in this project uses orchestration; the entire happy path
  (`order.created → stock.reserve → stock.reserved/insufficient → ...`) is
  already choreographed. Introducing an orchestrator for one two-step
  compensation would be architecturally inconsistent with the rest of the
  domain for no proportional benefit.
- **Saga Choreographed**: **adopted** — the direct, minimal extension of the
  pattern already used for 100% of this domain's cross-service flows: each
  service reacts to an event and decides its own next step. No new
  architectural concept.
- **Compensation via events**: **adopted** — this is the concrete
  implementation of the choreographed saga above, not a separate
  alternative to it.
- **Intermediate states**: necessary, not optional — see the `REFUNDING`
  discussion below.
- **Eventual consistency**: not a competing alternative but the property
  that already governs this entire project (RabbitMQ, a partial Outbox, no
  distributed transactions) and the reason compensation via events is
  preferable to the synchronous options above. `Order` and `Payment` are
  allowed to disagree temporarily (`Order` already `INVENTORY_FAILED`/
  `REFUNDING` while `Payment` is still `APPROVED`) until the round trip
  completes.

## Who initiates compensation

Orders — but not by reacting to `INVENTORY_FAILED` as a standalone trigger,
which was the ticket's own suggested flow. The actual trigger is narrower
and already exists in the code: **`OrderService.handlePaymentReceived`
detects a `payment.approved` that the state machine rejects because the
order already reached `INVENTORY_FAILED` or `CANCELLED`.** This single guard
covers both a stray approval after a stock failure and a stray approval
after a cancellation, for free — both states share the same absence of a
`PAYMENT_RECEIVED` transition.

## Decision

### States

**`Payment`** gains exactly one new status: `REFUNDED`. No `REFUND_PENDING`
was added, deliberately: `PaymentMockService.refund` resolves synchronously,
within the same method call, exactly like `processPayment` already does for
the original charge — a pending sub-status that is set and immediately
overwritten inside one transaction would never be observable between two
separate operations, the same reasoning that already kept `@Version` off of
`Product`/`User` in [ADR-0033](0033-optimistic-locking-on-order-and-payment.md).

**`Order`** reactivates `REFUNDING` (already existed, never used) and adds
two new terminal states, `REFUNDED` and `REFUND_FAILED`. `REFUNDING` earns
its place for a reason that is true today, not a speculative one: even
though `PaymentMockService.refund` decides synchronously, the round trip
*as seen by Orders* still crosses two asynchronous RabbitMQ hops with no
immediate answer. Without a persisted intermediate state, Orders would have
no way to tell "compensation already requested, waiting" apart from "not
requested yet" when a redelivered/duplicate `payment.approved` arrives —
it would need to invent some other tracking mechanism to recreate what the
state machine already gives for free. `REFUNDING` is that guard: the
`INITIATE_REFUND` transition only exists from `INVENTORY_FAILED`/
`CANCELLED`, never from `REFUNDING` itself, so a duplicate stray approval is
a structural no-op. A secondary, honestly-labeled benefit is that this same
state would become even more necessary if `PaymentProvider.refund` were ever
replaced by a real, genuinely asynchronous gateway integration — but that is
not the deciding reason, only a welcome consequence.

`INVENTORY_FAILED`/`CANCELLED` are no longer unconditionally final in
`OrderStateMachineConfig` — each keeps ending the order's lifecycle by
default, but now has exactly one outgoing edge (`INITIATE_REFUND`) for this
case. Before committing to this, a dedicated spike test
(`OrderStateMachineServiceRefundTransitionTest`) confirmed empirically that
Spring State Machine accepts a real transition out of a state whose `.end()`
declaration was removed — `OrderStateMachineService` already discards and
rebuilds the machine on every call via `resetStateMachine`, and never
consults `isComplete()` to block anything, so this was a safe assumption to
verify, not one to build on blindly.

### Events: a command, not a fact

`RefundPaymentCommand` (routing key `payment.refund.requested`, published on
the existing `order.exchange`) is deliberately named and treated as a
**command**, not a fact-event, even though it lives alongside
past-tense fact-events like `order.created`/`payment.approved`. Orders is
instructing Billing to do something specific, not broadcasting something
that already happened — the same distinction `ReserveStockCommand`/
`stock.reserve` already draws with `inventory-service`. The routing key
keeps the past-tense phrasing already used when this event was first
named; the DTO's class name carries the real signal.

Its only field is `orderId`. `transactionId`/`amount` were deliberately left
out: Billing already owns the authoritative copies of both for this order's
`Payment` (it is the one that set them), so relaying Orders' own echo of
either would mean Billing trusting a foreign copy of data it is already the
source of truth for, for no benefit.

Billing publishes exactly one of two outcomes, both back on `order.exchange`,
both reusing the existing `PaymentEvent` DTO (`orderId`/`transactionId`/
`failureReason` already cover everything either outcome needs — no new
payload shape):

- `payment.refunded` — success.
- `payment.refund.failed` — see below.

### `payment.refund.failed`: two causes, one Order-side outcome

Two categorically different conditions can produce this event:

1. **A genuine business decline from the provider** — `PaymentProvider`'s
   contract supports `PaymentResult.failed(...)` for `refund(...)` exactly
   as it already does for `processPayment(...)`, in case a real gateway is
   ever plugged in. `PaymentMockService.refund` never exercises this branch:
   unlike the original charge, a refund of money already captured has no
   meaningful "decline" worth simulating with a fake rule (a real gateway's
   own decline modes — funds already settled, account balance — have no
   equivalent here).
2. **An architectural inconsistency Billing finds before even calling the
   provider** — the `Payment` for this `orderId` doesn't exist, or isn't in
   `APPROVED` status. Given the trigger that leads to a
   `RefundPaymentCommand` ever being published (Orders only requests a
   refund after having just received a `payment.approved` for this exact
   order), neither condition should ever occur in correct operation; if one
   does, it signals a bug or a data desync between the two services, not a
   business outcome.

Both currently require the same action from Orders: a terminal,
human-reviewable state. Multiplying `Order`-side states to mirror a
distinction that does not change Orders' own behavior would be complexity
without payoff — so both map to the same `REFUND_FAILED`. The distinction is
not lost, though: it is carried in the event's `failureReason` text (e.g.
"Payment not found for order X" vs. "Payment not in APPROVED status
(current: Y)" vs. whatever a real provider might one day report), which is
enough for the one consumer of that text today — a human investigating logs
or the order's notification trail — to tell "go check the bank" apart from
"go check for a bug," without a second formal taxonomy nothing else needs
yet.

No automatic retry follows a `REFUND_FAILED`. `REFUNDING → REFUND_FAILED`
is deliberately a dead end from the domain's perspective:
[ADR-0019](0019-message-consumption-resilience.md)'s transport-level retry
(3 attempts, exponential backoff, then DLQ) already covers transient
delivery failures, and this failure mode is not transient — retrying "Payment
not found" or "Payment not APPROVED" against the same data would fail
identically every time. `REFUND_FAILED` exists precisely so this doesn't
stay indistinguishable from "still waiting normally" (`REFUNDING` forever)
or get silently reverted as if nothing had happened — it surfaces the
anomaly for manual/operational review. No remediation tooling for that
review was built as part of this change; it is a plausible future Roadmap
item, not a gap this ADR closes.

### No refund-specific transaction identifier

`PaymentMockService.refund` returns `PaymentResult.approved(null)` — no new
identifier is generated for the refund itself, and `Payment.transactionId`
is never overwritten; it keeps referring to the original charge. No
consumer (`Order`, `notification-service`, the frontend) reads a
refund-specific identifier today, so inventing one would be decorative
realism with no behavioral payoff, unlike the original charge's
`transactionId`, which is actually persisted and displayed.

### Refunds are operational compensation, not a first-class business workflow

This system has no user-facing refund feature. Refunds exist exclusively to
restore consistency after an already-approved payment can no longer be
honored — a stray `payment.approved` for an order that already failed or was
cancelled. Consequently, the model intentionally omits concepts a real
refund feature would need: refund identifiers, refund history, refund
requests initiated by a customer, partial refunds, and provider-specific
refund metadata. None of these were deferred by oversight; none has a use
case in this domain today.

### Billing's ownership is unchanged

Billing remains the sole owner of `Payment`'s lifecycle. Orders never
touches the entity directly — it only publishes intent
(`RefundPaymentCommand`); Billing decides the outcome and publishes it back.
`PaymentService.refundPayment` is idempotent: a redelivered/duplicate
command for a `Payment` already `REFUNDED` is a no-op (no second provider
call, no second publish) rather than an error.

### Notification and frontend: no special paths

`notification-service` requires **zero code changes**. `REFUNDING`/
`REFUNDED`/`REFUND_FAILED` are just more `order.status-changed` transitions,
and `process_order_status_changed` is already generic over
`previousState`/`newState` — a new notification row is produced
automatically, the same way every other transition already works. This
also preserves the existing precedent that `notification-service` never
consumes `payment.*` events directly, only `order.*`.

The frontend gains two `OrderState` union members (`REFUNDING`, `REFUNDED`,
`REFUND_FAILED`) and matching entries in `BADGE_BY_STATE` — no new screens,
no new buttons: these are system-driven transitions with no user action to
trigger or resolve them.

### Outbox: evaluated, consciously not adopted here

Whether `payment.refund.requested`'s publish deserves the same
crash-between-commit-and-publish protection that motivated
`inventory-service`'s Outbox ([ADR-0007](0007-remove-kafka-broker.md)) was
considered explicitly, not skipped by omission. `orders-service` has never
had an Outbox for *any* of its publishes — `order.created`,
`order.status-changed`, and the `stock.reserve` command are all best-effort
today, the same as `products-service` (see the project's own documented
architectural debt). Adding an Outbox just for this one new event, while
`order.status-changed` — published in the very same method, immediately
after the same `saveAndFlush` — stays best-effort, would create exactly the
asymmetry this decision needs to avoid: a future reader would have no way to
understand why the refund path alone got protected. The conscious decision
is to keep this event at the same reliability level as the rest of
`orders-service`'s publishes, and to track the broader gap — not just this
one event — as a Roadmap item: `orders-service` has no Outbox Pattern at
all, unlike `auth-service`/`inventory-service`.

### A risk found while implementing, not just while adding the status

`billing_schema.payments`'s original migration
(`V1__Create_payments_table.sql`) hard-codes a `CHECK (status IN ('PENDING',
'APPROVED', 'FAILED'))` constraint — adding `PaymentStatus.REFUNDED` in Java
alone was not sufficient, and this was only caught by
`RefundPaymentRequestedWiringIT` running against a real Postgres: the first
run failed with `new row for relation "payments" violates check constraint
"payments_status_check"`. Fixed by a new migration
(`V3__Add_refunded_to_payments_status_check.sql`) that drops and recreates
the constraint with `REFUNDED` included. The same class of gap ADR-0033
already documented for `@Version`/`saveAndFlush` — the entity change alone
does not prove correctness; only exercising it against real infrastructure
does.

## Verification

- TDD throughout, RED confirmed before each production change (a compile
  error for signature changes, a failing assertion for behavior changes).
- `OrderStateMachineServiceRefundTransitionTest` — the spike proving a
  former `.end()` state still accepts a real outgoing transition, plus the
  full transition-table coverage for the new states/events.
- `OrderServicePaymentCompensationTest` — a stray `payment.approved` for an
  `INVENTORY_FAILED` or `CANCELLED` order initiates compensation and
  publishes exactly one `RefundPaymentCommand`; a duplicate for an order
  already `REFUNDING` does not re-trigger anything.
- `OrderServiceRefundOutcomeTest` — `payment.refunded`/`payment.refund.failed`
  correctly close the loop into `REFUNDED`/`REFUND_FAILED`; a redelivered
  outcome for an order that already left `REFUNDING` is a no-op.
- `PaymentServiceRefundTest` — the success path, the idempotent
  already-`REFUNDED` short-circuit, and both `payment.refund.failed`
  triggers (missing `Payment`, wrong status).
- `PaymentCompensationWiringIT` (orders-service) /
  `RefundPaymentRequestedWiringIT` (billing-service) — the same scenarios
  against real Postgres/RabbitMQ (CI Phase 2 service containers), each
  proving its own side of the handshake independently, mirroring
  `PaymentOutcomeWiringIT`/`OrderCreatedWiringIT`'s existing precedent.
- Full suite green against real Postgres/RabbitMQ: 90 tests in
  `orders-service` (76 unit + 14 `*IT`, up from 67), 43 in `billing-service`
  (33 unit + 10 `*IT`, up from 36).
- Manual validation, against both services running as real local processes
  against real Postgres/RabbitMQ: an order seeded directly into
  `INVENTORY_FAILED` with a matching `Payment` already `APPROVED`
  (reproducing the stray-approval window without needing the full
  signup/order/inventory chain), then a real `payment.approved` published
  onto `order.exchange` with a known `correlationId`. The full chain —
  `PAYMENT_RECEIVED` rejected → `INITIATE_REFUND` accepted → `REFUNDING` →
  `order.status-changed` published → `RefundPaymentCommand` published →
  received by `billing-service` → `payment.refunded` published → received
  back by `orders-service` → `REFUND_COMPLETED` accepted → `REFUNDED` →
  `order.status-changed` published — appeared under that exact
  `correlationId` in both services' logs
  ([ADR-0024](0024-distributed-tracing-via-propagated-identifiers.md)).
  Re-publishing the same `payment.approved` afterward, against the
  now-`REFUNDED` order, logged `PAYMENT_RECEIVED not accepted for order ...
  in state REFUNDED -- no compensation triggered` and left the order
  untouched — the idempotency guard confirmed live, not just in `*IT`.

## Consequences

**Positive**: a payment approved for an order that can no longer be
fulfilled is no longer a silent, permanent inconsistency — it is detected
at the exact point Orders already had the information to detect it, and
compensated through the same event-driven model the rest of the domain
already uses. `Product`/`User` remain outside this ADR's scope; nothing
here touches them.

**Negative / known limitations**:
- `REFUND_FAILED` is a dead end from the domain's perspective — no
  automatic retry, no remediation tooling. Recovering from it today is a
  manual/operational action outside this system.
- The compensation trigger only fires from `handlePaymentReceived`'s own
  rejection branch. If a future change adds a legitimate path back to
  `INVENTORY_FAILED` from a later state (none exists today), this ADR's
  transition graph would need revisiting.

## Update — 2026-07-15: the Outbox gap this ADR opened is now closed

The "Outbox: evaluated, consciously not adopted here" passage above
reasoned from a real constraint at the time: adding Outbox for
`payment.refund.requested` alone, while `order.created`,
`stock.reserve` and `order.status-changed` stayed best-effort, would have
created exactly the asymmetry this ADR was trying to avoid. That
constraint no longer holds — a separate architectural analysis re-examined
`orders-service`'s publish reliability as a whole (not just this one
event), using the impact of losing each publish as the adoption criterion
rather than whether a caller happens to notice the failure or whether
ADR-0019's transport-level retry mitigates a related but distinct failure
mode. All four of `orders-service`'s publishes — `order.created`,
`stock.reserve`, `order.status-changed`, and `payment.refund.requested`
itself — qualified under that criterion and are now written as
`OutboxEvent` rows in the same transaction as the domain change that
produces them, exactly the pattern `auth-service`/`inventory-service`
already used and [ADR-0037](0037-consolidated-outbox-pattern-specification.md)
now formalizes. `OrderService` no longer holds a `RabbitTemplate` at all —
every publish this class makes goes through a single `writeOutboxEvent`
helper, and a dedicated `OutboxPublisher` (mirroring
`auth-service`/`inventory-service`'s own) polls and sends on the same 5s
cadence. The asymmetry this ADR's original passage worried about is gone
because every publish moved together, not because the concern was wrong.

## References

- [ADR-0032](0032-accept-order-state-machine-hybrid.md) — found and opened
  the `Order.@Version` gap that led to
  [ADR-0033](0033-optimistic-locking-on-order-and-payment.md); this ADR
  continues investigating the same state machine's completeness.
- [ADR-0029](0029-order-fulfillment-lifecycle.md) — the precedent for
  activating pre-existing, unused states/events instead of redesigning the
  machine (`SHIPPED`/`DELIVERED` there, `REFUNDING`/`INITIATE_REFUND`/
  `REFUND_COMPLETED` here).
- [ADR-0031](0031-single-source-of-truth-for-payment-creation.md) — the
  established precedent that Billing alone decides `Payment`'s lifecycle.
- [ADR-0019](0019-message-consumption-resilience.md) — the transport-level
  retry/DLQ policy this ADR deliberately does not duplicate for
  `REFUND_FAILED`.
- [ADR-0030](0030-deterministic-payment-provider.md) — `PaymentProvider`/
  `PaymentResult`, reused here for `refund(...)` instead of introducing a
  parallel abstraction.
- [ADR-0007](0007-remove-kafka-broker.md) — the Outbox precedent evaluated
  and consciously not extended to this event at the time.
- [ADR-0037](0037-consolidated-outbox-pattern-specification.md) — the
  consolidated specification `orders-service`'s Outbox now follows, per
  the 2026-07-15 Update above.
- README Roadmap — the item this ADR originally closed, and the item about
  `orders-service` having no Outbox Pattern at all, now closed by the
  Update above.
