# ADR-0033: optimistic locking on Order and Payment

## Status

Accepted - 2026-07-12

## Context

No persistent entity in EasyDora uses concurrency control (`@Version`).
[ADR-0032](0032-accept-order-state-machine-hybrid.md) surfaced this gap
while investigating `orders-service`'s state machine and opened it as a
Low Roadmap item, but explicitly left the decision of *which* aggregates
actually need it, and *how*, for a dedicated pass.

That absence is not a uniform problem across the domain. Only two
aggregates have a real, observed path for two writers to race on the same
row:

- **`Order`**: `InventoryEventsConsumer`, `PaymentEventsConsumer`, and the
  HTTP endpoints (`cancelOrder`/`shipOrder`/`deliverOrder`) all read and
  write the same `orders` row with no version check today. A stock
  outcome and a cancellation for the same order can arrive close enough
  together to overwrite each other silently.
- **`Payment`**: `PaymentController.processPayment`/`retryPayment` can
  both be invoked twice for the same `orderId` (a duplicated gateway
  callback, a client retry after a timeout) — exactly the kind of
  duplication a real payment gateway integration produces routinely.

`Product` and `User` were deliberately left out: `products-service` writes
`Product` only from its own `ProductController`/`ProductService`,
sequentially per request from the owning seller — its one RabbitMQ
consumer (`UserEventConsumer`) never touches `Product` — and `auth-service`
has no concurrent writer to `User` either. Adding `@Version` there would
protect against a race that has never been observed and has no plausible
trigger in the current domain — exactly the kind of "just in case"
addition this project's principles reject (see
[architectural-principles.md](../architecture/architectural-principles.md),
principle 2).

### Risks found while implementing, not just while adding the column

Adding `@Version` alone would not have been sufficient, and this ADR
exists partly to record why:

1. **`Order.id` is manually assigned** (`UUID.randomUUID().toString()`),
   not `@GeneratedValue`. Spring Data's `isNew()` detection for an entity
   with a version property switches to checking whether `version` is
   null instead of the id — verified correct in practice by
   `OrderOptimisticLockingIT`, which persists a brand-new `Order` and
   still gets a normal `INSERT`.
2. **Publishing happens before the version check was otherwise guaranteed
   to run.** `orderRepository.save(...)`/`paymentRepository.save(...)`
   does not force a flush; without changing that, a version conflict
   could be discovered *after* `publishOrderStatusChanged`/
   `publishPaymentEvent` had already gone out on RabbitMQ — a phantom
   event for a write that never actually committed. Both services now
   call `saveAndFlush(...)` at the exact point the transition is
   persisted, strictly before publishing anything.
3. **Generic `catch (Exception e)` blocks would have swallowed the
   conflict into the wrong outcome.** `PaymentService.processPayment`'s
   existing catch-all treated any failure as a business payment failure
   -- without a dedicated `catch (OptimisticLockingFailureException e) { throw e; }`
   placed before it, a version conflict would have been recorded as a
   real `FAILED` payment and published as a false `payment.failed` event.
   `OrderStateMachineService.sendEvent`'s own catch-all was checked and
   does **not** have this problem: its internal `save()` is never
   flushed early, so the conflict only ever surfaces later, at the
   `saveAndFlush` call in the calling `OrderService` method, outside that
   catch.

## Decision

Add `@Version private Long version;` to `Order` and `Payment` only
(`V4__Add_version_to_orders.sql`, `V2__Add_version_to_payments.sql`).
`OrderService`'s seven order-mutating methods
(`cancelOrder`/`shipOrder`/`deliverOrder`/`handlePaymentReceived`/
`handlePaymentFailed`/`handleInventoryReserved`/`handleInventoryFailed`)
and `PaymentService.processPayment`/`retryPayment` now call
`saveAndFlush` instead of `save` at the point the state transition is
persisted, and surface a real conflict as
`org.springframework.dao.OptimisticLockingFailureException` — Spring's
own translated supertype, so this is caught consistently regardless of
whether the conflict is detected inside a repository call or at
transaction commit. `orders-service`'s `GlobalExceptionHandler` gained a
dedicated `@ExceptionHandler(OptimisticLockingFailureException.class)` →
`409 Conflict`, checked ahead of the generic `RuntimeException` handler.
`billing-service` has no `@RestControllerAdvice` (a deliberate choice,
see [ADR-0031](0031-single-source-of-truth-for-payment-creation.md)), so
`PaymentController` gained the equivalent `catch
(OptimisticLockingFailureException e)` → `409` in the same try/catch
style it already uses, on both `/process` and `/{orderId}/retry`.
`createOrder` was left untouched: it only ever inserts a brand-new row,
with no possible concurrent writer for an `orderId` that didn't exist a
moment earlier.

### Optimistic vs. pessimistic locking

This is an architectural decision, not a default reached by convention or
by "best practice." Two strategies were considered:

- **Optimistic locking** (`@Version`, the one adopted): every write
  proceeds without acquiring a database lock; a conflict is detected only
  at write time, by comparing the version the writer read against the
  version currently stored, and rejected if they differ.
- **Pessimistic locking** (`SELECT ... FOR UPDATE` /
  `LockModeType.PESSIMISTIC_WRITE`): the first reader to touch a row
  holds a database-level lock for the duration of its transaction,
  forcing every other writer to block until it releases.

The goal of this change is **not to prevent concurrency by serializing
access at the database** — it is to prevent lost updates by letting
conflicts be detected and handled consciously by the application. That
distinction drove the choice:

- **Why optimistic fits EasyDora's current domain**: contention on any
  single `Order`/`Payment` row is low (each aggregate belongs to one
  order's lifecycle, not a shared pool multiple actors constantly fight
  over); the transactions touching them are short (a handful of column
  updates, not long-running work); the architecture is event-driven
  (RabbitMQ, with a partial Outbox), which already assumes and tolerates
  redelivery and retry rather than blocking; and a conflict, when it does
  happen, is expected to be a rare event that is nonetheless important to
  detect explicitly rather than silently overwrite.
- **Why pessimistic locking was considered and rejected**: it would hold
  a lock for the full duration of each transaction with no proportional
  benefit for this workload; it reduces parallelism and throughput
  precisely on the two aggregates the whole checkout flow depends on; it
  would increase wait time between an HTTP request and a RabbitMQ
  consumer racing for the same row (a consumer thread blocking on a lock
  held by a slow HTTP request risks message redelivery/timeout, the
  opposite of what ADR-0019's retry policy assumes); and, most simply, it
  would solve a problem — genuine lock contention — that does not occur
  frequently enough today to justify that cost.

**This is not a permanent decision.** It should be revisited if any of
the following becomes true, none of which describes the system today:

- A new aggregate emerges with genuinely high contention (a flash sale, a
  highly-contested limited-stock reservation).
- Contention on `Order` or `Payment` increases significantly in practice
  (observed retry/conflict rates climbing, not a hypothetical concern).
- A concrete need for serialized operations is demonstrated — a case
  where detecting and retrying after a conflict is no longer good enough
  and operations must not interleave at all.
- A business requirement emerges that needs mutual exclusion for the
  entire duration of a transaction, not just a check at write time.

The locking strategy is part of this domain's architectural design, tied
to its current, observed shape — not a technology preference. The
standing criterion is the same one this project applies everywhere else:
adopt the simplest mechanism that resolves the real problem observed, and
revisit only when the system's actual behavior demands a different one.

## Verification

- TDD throughout: `OrderOptimisticLockingIT`/`PaymentOptimisticLockingIT`
  (new, real Postgres via the existing failsafe `*IT` pattern — no new
  plugin or library) each load two independent copies of the same row,
  save the first, then assert the second's `saveAndFlush` throws
  `OptimisticLockingFailureException` and that the first writer's update
  was not overwritten.
- `OrderServiceOptimisticLockingTest` (new) proves `cancelOrder`/
  `shipOrder`/`deliverOrder`/`handlePaymentReceived` propagate the
  conflict undisturbed and publish no event when it happens.
- `PaymentServiceDeterministicApprovalTest` gained a test proving a
  conflict during `processPayment` is never recorded as a business
  `FAILED` payment.
- `GlobalExceptionHandlerTest` (new) and `PaymentControllerOptimisticLockingTest`
  (new) prove the HTTP mapping is `409`, not the generic `400`, in both
  services.
- Full suite green end to end against real Postgres/RabbitMQ: 67 tests in
  `orders-service` (58 unit + 9 `*IT`), 36 in `billing-service` (29 unit +
  7 `*IT`).

## Consequences

**Positive**: a lost update on `Order` or `Payment` is now impossible by
construction, not just unlikely in practice. Conflicts are detected
explicitly and reported as `409`, instead of one writer's change quietly
disappearing. For the two RabbitMQ-driven paths
(`handlePaymentReceived`/`handlePaymentFailed`/`handleInventoryReserved`/
`handleInventoryFailed`), a conflict is retried automatically by the
existing listener retry policy ([ADR-0019](0019-message-consumption-resilience.md))
with no new code — a message that lost a race simply gets redelivered
after the winning transaction has already committed.

**Negative / known limitations**: an HTTP caller that hits a genuine
conflict now sees `409` instead of the earlier (incorrect) behavior of
one write silently overwriting the other — callers that don't already
retry on `409` will need to. `Product` and `User` remain unprotected by
design; a future change to their write patterns should reopen this
decision for them specifically, not assume this ADR already covers them.

## References

- [architectural-principles.md](../architecture/architectural-principles.md)
  — principle 2 ("a component must earn its place"), applied here to
  justify excluding `Product`/`User`.
- [ADR-0032](0032-accept-order-state-machine-hybrid.md) — found and opened
  this gap as a new Roadmap item while investigating the state machine.
- [ADR-0031](0031-single-source-of-truth-for-payment-creation.md) — why
  `billing-service` has no `@RestControllerAdvice`, followed here for the
  same 409 mapping.
- [ADR-0019](0019-message-consumption-resilience.md) — the listener retry
  policy that now also absorbs optimistic-lock conflicts on the
  RabbitMQ-driven paths, for free.
- README Roadmap — the item this ADR closes.
