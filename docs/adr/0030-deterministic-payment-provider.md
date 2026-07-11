# ADR-0030: deterministic payment provider

## Status

Accepted - 2026-07-11

## Context

`billing-service` had a complete, wired-but-unused abstraction: a
`PaymentProvider` interface and a `PaymentMockService` implementation
(`@Service @Primary`, ready to be injected). `PaymentService.
processPayment` never depended on either — it decided approval inline
with `boolean approved = Math.random() < 0.9`. This produced three real
problems: `PaymentProvider` was dead code; the domain's approval decision
was non-deterministic; and no test or documented walkthrough could assert
a specific expected outcome for a payment, only "it may approve or fail."

## Decision

**Activate the existing abstraction rather than redesigning it.**
`PaymentProvider`'s shape was already correct — a single method returning
a rich `PaymentResult` (`approved(transactionId)`/`failed(reason)`), not a
bare boolean — and stayed that way. The one real defect in the interface
was a type mismatch that had never been caught because it had never been
compiled against real usage: `orderId` was declared `Long`, but
`Payment.orderId` (the real domain type) is `String`. Fixed to `String`.

**Kept `PaymentMockService`'s existing amount-parity rule instead of
switching to a hash of `orderId`.** The mock was already deterministic —
`amount.remainder(2) == 0` → approved — it just was never called, and it
ignored the `orderId` parameter it received. Two deterministic strategies
were on the table: a hash of `orderId` (more granular: any two orders,
regardless of amount, can differ), or keeping amount parity as-is
(simpler, and the total amount is something a walkthrough or test author
already controls and can reason about directly, without needing to know
what a hash function does). Chosen: **keep amount parity.** This
followed directly from this project's own standing principle of avoiding
complexity that doesn't pay for itself (see
[architectural-principles.md](../architecture/architectural-principles.md))
— a hash function is harder to explain and adds a dependency on
`String.hashCode()`'s stability for no benefit a portfolio walkthrough
actually needs. `orderId` stays in `PaymentProvider`'s contract (a real
provider would need it for idempotency/reference) but is not used by this
particular decision — documented as intentional, not an oversight.

**`PaymentService` now depends on `PaymentProvider` via constructor
injection** (Spring wires the `@Primary` `PaymentMockService`
automatically); `processPayment` calls `paymentProvider.processPayment
(orderId, amount)` and maps `isApproved()`/`getTransactionId()`/
`getFailureReason()` onto the `Payment` entity. The artificial
`Thread.sleep(1000)` that used to simulate processing latency was removed
along with the random check — it served no purpose related to
determinism and would have made every new approval-decision test a full
second slower for nothing.

**Removed a second, unrelated dead class found in the same area**:
`service/PaymentResult.java` (mutable, `isSuccess()`/`getErrorMessage()`),
never referenced by anything, sitting in the same package as
`PaymentService`. Left in place, it would have silently shadowed the real
`provider.PaymentResult` for any unqualified reference in `PaymentService`
(same-package classes resolve before imports) — a real landmine directly
in the path of this exact change, not a tangential finding.

**Accepted consequence: `retryPayment` now always repeats the same
outcome for a given order.** Before, resetting a `FAILED` payment to
`PENDING` and reprocessing gave a fresh 90% chance each time. Now, since
the amount doesn't change on retry, the decision doesn't either — a
"bad" order stays failed forever. Considered acceptable, and arguably
more realistic than random retries eventually succeeding for no
domain reason.

## Verification

- TDD, red-green: `PaymentServiceDeterministicApprovalTest` (new) —
  confirmed red first via a compile failure (the 3-argument constructor
  didn't exist yet), then green covering an even amount (approved), an
  odd amount (rejected), the provider itself as a pure function of
  amount, a failed order repeating its outcome on retry, and a
  source-text assertion that `PaymentService.java` contains no
  `Math.random` reference. `PaymentEventPublishBehaviorTest`/
  `PaymentServiceOrderCreatedBehaviorTest` updated for the new
  constructor parameter (a mocked `PaymentProvider`, never invoked by
  either test). Full `mvn verify` (25 unit + 6 `*IT`, real Postgres/
  RabbitMQ) green, no regressions.
- Live validation against a real running billing-service: an even-amount
  order resolved `APPROVED` with a provider-generated `transactionId`; an
  odd-amount order resolved `FAILED` with the mock's stated reason;
  calling `process` again on the approved order returned the identical
  cached result; calling `retry` on the failed order reproduced the exact
  same `FAILED` outcome.
- Found, not fixed here (outside this change's scope, pre-existing,
  unrelated to determinism): calling `POST /api/payments/process` for an
  `orderId` with no existing `Payment` row hits an "API fallback" branch
  that never sets `userId`, violating a `NOT NULL` constraint. This only
  manifests when the endpoint is called directly without going through
  the real `order.created` → `createPendingPayment` flow first (which
  always sets `userId`) — the documented walkthrough and Postman flows
  never hit it, since they always process an order that was created for
  real first.

## Consequences

**Positive**: `PaymentProvider` is now a real, exercised seam instead of
dead code. `docs/walkthrough.md`, `docs/sequence-diagram.md`, and
`postman/README.md` no longer hedge with "either outcome" — this
project's fixed walkthrough numbers (2 units at `249.90` = `499.80`, not
evenly divisible by 2) deterministically resolve to `FAILED`, stated as
fact rather than a possibility.

**Negative / known limitations**:
- Amount-parity determinism is coarser than a hash: any two orders
  sharing the same amount parity always coincide in outcome. Accepted as
  a deliberate simplicity trade-off, not an oversight.
- The pre-existing `userId`-not-set bug on the direct-API fallback path
  (see Verification) remains open, tracked separately.
- `retryPayment` is now permanently ineffective for any order whose
  amount doesn't change — see Decision.

## References

- [architectural-principles.md](../architecture/architectural-principles.md)
  — the "avoid complexity without real benefit" principle this ADR's
  amount-parity-over-hash choice follows directly.
- [ADR-0021](0021-payment-outcome-integration.md) — the payment-outcome
  event integration this ADR's `PaymentService` change sits inside;
  unaffected by this change (still publishes the same `payment.approved`/
  `payment.failed` events on the same resolution).
- README Roadmap — the Medium item this ADR closes.
