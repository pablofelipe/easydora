# ADR-0032: accept the hybrid Spring State Machine pattern in orders-service

## Status

Accepted - 2026-07-12

## Context

`orders-service` uses Spring State Machine to model an order's lifecycle
(`PENDING` → `PROCESSING` → `INVENTORY_RESERVED` → `PAYMENT_APPROVED` →
`SHIPPED` → `DELIVERED`, plus the terminal failure/cancellation states).
`OrderStateMachineService.sendEvent` rebuilds and rehydrates a brand new
state machine instance from `Order.state` on every single event, reads the
resulting state back out, persists it to that same column, then stops and
discards the machine. `Order.state` is always the real source of truth;
the machine itself never stays alive between calls. This was flagged as an
open Roadmap item ("Architectural note", opened 2026-07-10): the pattern
pays the full complexity cost of a state machine framework (factory,
accessor, context-reset boilerplate) without the benefit a state machine
is supposed to provide — a live authority you query, not a disposable
transition validator — and keeping both halves of that hybrid indefinitely
without a decision was called out as the one option that isn't defensible.

Two clean alternatives were investigated in parallel, plus a neutral
assessment of whether acting now is actually warranted:

**Make the machine a live authority.** `OrderStateMachineConfig` uses no
guards and no extended state anywhere — nothing in the domain today needs
context that survives between calls. `orders-service` also runs as a
single replica; the multi-instance divergence a live machine would need to
solve doesn't exist yet. Implementing this for real would mean a new
Spring State Machine persistence dependency (JPA/Redis/Zookeeper-backed),
a new table to serialize machine context, and new operational risk (leaked
in-memory machines for abandoned orders, a cold-start split between
"machine state" and `Order.state` after a restart) — for a domain with ten
states and no feature that would use the live behavior once built.

**Drop the framework for a plain, validated transition table.** The real
graph is small and fully linear: ten states, eleven transitions, no
junction or choice states, only two transitions carrying a side-effecting
`Action` (`ReleaseInventoryAction`). That maps cleanly onto an `EnumMap`
plus a small side-effect table, and the migration itself would be low-risk
— only one test (`OrderStateMachineServiceTransitionTest`) is coupled to
the framework directly; the other order-lifecycle tests already mock
`OrderStateMachineService` and wouldn't change. This is a real, available
simplification. It is also, on its own, a change with **no functional
payoff**: it fixes no bug, and changes no observable behavior for any
caller.

**Historical and risk check.** Spring State Machine was present in
`orders-service`'s first commit with no ADR ever explaining the choice —
it was a starting assumption, not an argued decision. The pattern did
cause one real production bug (fixed 2026-01-08, "Order state not found"),
patched defensively rather than at the root cause, and stable since. The
one clearly *architectural* bug this area produced — `sendEvent` mutating
the same Hibernate-managed `Order` instance within a transaction, so
`previousState` always read back as the already-updated state — was found
and fixed by [ADR-0029](0029-order-fulfillment-lifecycle.md) without
touching the hybrid itself. No bug traceable to the hybrid pattern is open
today, and every test exercising `OrderService`'s lifecycle methods mocks
`OrderStateMachineService` entirely, so the pattern's opacity has no
measured cost on test reliability either.

## Decision

**Keep the current hybrid as-is. No code changes.** The cost of migrating
today — touching the most critical flow in the system (the full order
lifecycle, including the RabbitMQ-triggered `ReleaseInventoryAction` side
effect) for a change with no functional gain and no active bug to fix —
outweighs the legibility benefit either alternative would provide. The
framework is stable, fully covered by existing tests, and already part of
how this codebase's contributors reason about order transitions; disturbing
that for a change that resolves nothing real is not worth the risk.

This sits in real tension with two of this project's own default
positions — [principle 2](../architecture/architectural-principles.md)
("a component must earn its place") and
[principle 6](../architecture/architectural-principles.md) ("avoid
unnecessary hybrid modes") would, taken alone, favor picking one of the two
clean alternatives above. What tips the balance here is
[principle 8](../architecture/architectural-principles.md), evidence over
assumption: unlike ADR-0007's Kafka removal (zero migration cost, an
actively confusing dual-broker setup) or ADR-0015's `.httpBasic()` removal
(a trivial, risk-free delete), this change would touch order fulfillment
end-to-end for a documented, non-hypothetical benefit of exactly zero. This
ADR is the record of that trade-off having been considered and decided
against action, not a decision made by omission.

If the order domain later grows real need for what a live state machine
provides — guards that depend on accumulated context, hierarchical or
timed states, concurrent events on the same order that need arbitration —
this decision should be revisited against that concrete need, not before.

**Separately, this investigation surfaced a new, unrelated technical debt
item, tracked on the README Roadmap rather than fixed here**: the `Order`
entity has no `@Version` column, so there is no optimistic-locking
protection against concurrent writes to the same order — a real gap given
that `InventoryEventsConsumer`, `PaymentEventsConsumer`, and HTTP endpoints
(`cancelOrder`/`shipOrder`/`deliverOrder`) can all race to update the same
`Order` row. This is orthogonal to the state machine question — it would
exist under either alternative above, and under the current hybrid too —
and is out of scope for this ADR.

## Consequences

**Positive**: no risk introduced into the order lifecycle, no engineering
time spent on a change with no behavioral payoff, and this ADR now answers
"why does this half-alive state machine still exist" for any future
reader who wonders — closing the open Roadmap item without pretending the
tension it raised wasn't real.

**Negative / known limitations**: the hybrid itself is unchanged — a new
contributor still has to learn the "rebuild-and-discard" mental model to
work in this area, and `OrderStateMachineService.sendEvent` still carries
defensive logging and a `Thread.sleep` left over from the 2026-01-08 fix.
Both alternatives investigated remain available and inexpensive to revisit
if a real need appears.

## References

- [architectural-principles.md](../architecture/architectural-principles.md)
  — principles 2, 3, 6 (weighed against) and 8 (evidence over assumption,
  the deciding one here).
- [ADR-0029](0029-order-fulfillment-lifecycle.md) — the one real bug this
  area produced, already fixed without touching the hybrid.
- README Roadmap — the Architectural note this ADR closes, and the new
  `Order.@Version` item it opens.
