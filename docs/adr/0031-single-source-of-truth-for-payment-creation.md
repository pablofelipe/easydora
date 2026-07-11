# ADR-0031: single source of truth for payment creation

## Status

Accepted - 2026-07-11

## Context

While validating [ADR-0030](0030-deterministic-payment-provider.md),
calling `POST /api/payments/process` directly for an `orderId` with no
prior `Payment` row surfaced a bug: an "API fallback" branch inside
`PaymentService.processPayment` built a brand-new `Payment` on the spot,
but never set `userId`, violating the table's `NOT NULL` constraint. That
bug was logged on the README Roadmap rather than fixed immediately,
because it pointed at a bigger question worth answering first: should
that branch exist at all?

`processPayment` had two different ways of arriving at a `Payment` for a
given `orderId`:
1. `createPendingPayment`, reacting to `order.created` — the real,
   documented flow (see `docs/walkthrough.md` step 8 and
   `docs/sequence-diagram.md`). Always sets `userId`, `amount`, and a
   fresh `transactionId`.
2. `processPayment`'s own fallback, triggered only when
   `findByOrderId` came back empty — building a second, parallel,
   incomplete `Payment` for the same aggregate.

Investigation (not assumption) confirmed the fallback has no legitimate
caller:
- The frontend's "Process payment" button only appears once an order has
  reached `INVENTORY_RESERVED`, which requires the `stock.reserve` /
  `stock.reserved` round trip to have already completed — by which point
  `order.created` (published in the same request that started that round
  trip) has already been consumed by `createPendingPayment` too.
- `docs/walkthrough.md` and the Postman collection always call `/process`
  for an order that went through the real flow first; neither ever
  reaches the fallback.
- `retryPayment` requires an existing `Payment` (throws if missing) before
  it ever calls `processPayment`, so it never exercises the fallback
  either.
- The only place the fallback branch actually ran was the test suite
  written around it (`PaymentServiceDeterministicApprovalTest`'s
  even/odd-amount tests mocked `findByOrderId` to return empty) — the
  tests existed to accommodate the fallback, not because any real caller
  needed it.
- `PaymentController.createPendingPayment` (`POST /api/payments/pending`)
  was a second, already-dead entry point: despite its name, it called
  `processPayment(orderId, amount)` — the same method, not the real
  `PaymentService.createPendingPayment(OrderCreatedEvent)` — and had no
  caller anywhere (frontend, walkthrough, Postman, or tests). Its own
  comment already admitted this was confusing.

Additionally: in the one branch that *is* real (`Payment` found),
`processPayment`'s own `amount` request parameter was already ignored —
only the fallback ever read it. Removing the fallback made this
parameter entirely dead too.

## Decision

**There is exactly one way for a `Payment` to come into existence:
reacting to `order.created`.** `processPayment` no longer creates
anything — it looks up the `Payment` for the given `orderId` and treats
its absence as a domain error, not an opportunity to improvise one:

- `PaymentService.processPayment(String orderId)` — dropped the `amount`
  parameter (redundant: the amount lives on the `Payment` row already).
  `paymentRepository.findByOrderId(orderId).orElseThrow(...)` now throws
  a new `PaymentNotFoundException` instead of falling through to a
  constructor call.
- `PaymentController`: `POST /api/payments/process` catches
  `PaymentNotFoundException` and returns `404 Not Found` (checked before
  the generic `catch (Exception e)` that still maps everything else to
  `400`). Takes only `orderId` — the `amount` query parameter is gone.
- `POST /api/payments/pending` removed entirely. It was already dead
  before this change (see Context) and its only remaining purpose — a
  differently-spelled alias for `/process` — became actively
  uncompilable once `processPayment` stopped taking an `amount` argument.
  Deleting it is not new scope; it is the same call site this decision
  already had to touch.
- `retryPayment` (which resets a `FAILED` payment to `PENDING` and calls
  `processPayment` again) updated to the new single-argument signature —
  no behavior change, since it always operates on a `Payment` it already
  looked up itself.

This directly follows the project's own "single source of truth, single
creation path per aggregate" principle (see
[architectural-principles.md](../architecture/architectural-principles.md)),
the same reasoning [ADR-0029](0029-order-fulfillment-lifecycle.md) applied
when it replaced `canCancel()`'s hand-written transition list with the
state machine's own configured graph. The `userId`-not-set bug that
started this investigation isn't "fixed" here — it's deleted, along with
the entire code path it lived in.

## Verification

- TDD: updated `PaymentServiceDeterministicApprovalTest`'s
  `evenAmountIsApproved`/`oddAmountIsRejected` to mock an existing
  `Payment` (previously they mocked an *absent* one, exercising the now-
  removed fallback) and added
  `processingAnOrderWithNoExistingPaymentThrowsADomainNotFoundError`,
  asserting `PaymentNotFoundException` for an `orderId` with no `Payment`
  row. Full `mvn test` (26 unit tests) green, no regressions.
- Confirmed empirically before implementing (see Context) that no real
  caller — frontend, `docs/walkthrough.md`, the Postman collection, or
  `retryPayment` — ever depended on the fallback creating a `Payment`.
- Frontend (`frontend/src/lib/api/billing.ts`,
  `routes/orders/[id]/+page.svelte`) updated to stop sending `amount`;
  `npm run check` passes with no new errors.
- `docs/walkthrough.md`, `docs/sequence-diagram.md`, `postman/README.md`,
  and both "Process Payment" requests in the Postman collection updated
  to match the new, `orderId`-only signature.

## Consequences

**Positive**: exactly one code path creates a `Payment`, matching the
project's existing pattern for `Order`/`Buyer`/`Seller`/inventory rows.
The `userId`-not-set bug is gone by construction, not patched. Calling
`/api/payments/process` for a made-up or not-yet-real `orderId` now fails
fast and clearly (`404`) instead of quietly writing an incomplete row.

**Negative / known limitations**:
- Calling `/process` before `order.created` has actually been consumed
  (a narrow timing window, not observed in practice given the UI/
  walkthrough's own state-gating) now returns `404` instead of eventually
  succeeding via the fallback. Considered acceptable: the correct
  response to "not created yet" is to retry once the event has been
  consumed, not to create a second, competing `Payment`.
- `POST /api/payments/pending` is gone; nothing in this codebase ever
  called it.

## References

- [architectural-principles.md](../architecture/architectural-principles.md)
  — single source of truth / single creation path per aggregate.
- [ADR-0030](0030-deterministic-payment-provider.md) — the change whose
  live validation surfaced the `userId` bug that led to this decision.
- [ADR-0029](0029-order-fulfillment-lifecycle.md) — prior instance of the
  same pattern (replacing a hand-written rule with the real source of
  truth) in `orders-service`.
- README Roadmap — the Low item this ADR closes.
