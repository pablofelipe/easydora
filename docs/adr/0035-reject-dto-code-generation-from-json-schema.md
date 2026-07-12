# ADR-0035: reject DTO code generation from JSON Schema, at the project's current scale

## Status

Accepted - 2026-07-13

## Context

[ADR-0002](0002-json-schema-contract-testing.md)'s Update gave every one
of this project's 17 published messages a versioned JSON Schema contract
and a producer/consumer test per service that touches it. That closes
the "silent drift" problem — a shape mismatch between a producer and a
consumer now fails a fast, infra-free
test instead of surfacing as a production incident. What it does not
close is the underlying duplication: the same contract is still typed out
by hand in every language that touches it — a Java DTO here, a Go struct
there, nothing at all in `notification-service` (it reads events as plain
dicts). A standing Roadmap item names this explicitly: even with a
schema, nothing stops a hand-written DTO from silently drifting from it
until a test catches the mismatch — the schema is correct, but the
implementation can still diverge.

The proposed fix — generate each service's DTOs from the shared JSON
Schema at build time (`jsonschema2pojo` for the four Spring services,
`go-jsonschema` for `inventory-service`, `datamodel-code-generator` for
`notification-service`) — was evaluated on its merits before writing any
code, per this project's principle of not assuming a technically
interesting solution is automatically the right one.

### Is the problem this solves actually happening?

The data says no, measurably. Every one of the 17 schema files has exactly
one creation commit in this project's entire history; the two that
predate this broadened coverage have two and three commits respectively,
and neither of those extra commits changed the contract's shape (one was
an unrelated consumer removal, the other a filename rename). The DTOs
with the most git history in the whole codebase — `inventory_models.go` (5 commits),
`JwtCreatedEvent.java`/`OrderCreatedEvent.java` (4 and 4) — were each
inspected commit by commit: every touch is either the file's own
creation or a deliberate feature addition (new fields for a new
capability), except exactly one, `JwtCreatedEvent.userId`, which *was* a
real drift (a `String` field every consumer already treated as numeric).
That one case is not evidence the current approach fails — it is
evidence it works: the drift was caught by the contract test added in the
very same change that introduced it, before it ever reached production.
Across the whole project's lifetime, contract testing has been asked to
catch exactly one real drift, and did.

### What generation would actually cost

Three separate toolchains, one per language, each wired into a different
build system (`generate-sources` in four independent `pom.xml` files —
not the shared parent, since each service owns a different subset of
events; `go generate` or a Makefile target for `inventory-service`; a new
Pydantic-generation step for `notification-service`). None of the three
tools resolve the one drift class ADR-0002 already documented as
uncatchable by schema validation alone (`price` as `BigDecimal` vs.
`float64`) — generated code is exactly as blind to numeric-precision
mismatches as hand-written code is.

More importantly, generation would cost a real design pattern this
project already relies on deliberately: several consumers capture only
the fields they use, not the producer's full shape —
`orders-service`'s `ProductCreatedEvent` (2 of 6 fields, documented
in-code as intentional), `billing-service`'s `JwtEvent` (6 of 8 fields).
A generator emits the schema's complete shape; there is no proportionate
way to keep a consumer's narrower, self-documenting DTO without either
hand-editing generated code (explicitly disallowed by this decision's own
would-be requirements) or writing a second, bespoke tool to generate
per-consumer subsets — which would be building a contracts framework this
project has already ruled out.

### Alternatives compared

- **A — keep hand-written DTOs (status quo).** ~29 hand-written
  representations for 17 contracts, now backed by a contract test each.
  Drift risk exists in principle, caught in practice by CI before merge.
- **B — generate DTOs from JSON Schema at build time.** Removes drift by
  construction instead of by test, at the cost of three new toolchains,
  three new per-language integration points, and the loss of the
  intentional-partial-consumer pattern described above — for a drift rate
  measured at one occurrence in the project's history, already caught.
- **C — a hybrid.** This is not a third option still to design — it is
  what ADR-0002's Update already put in place: JSON Schema as the single
  source of truth for the contract's *shape*, verified by fast tests,
  with the *implementation* left to each service. There is no
  proportionate intermediate point between A and B beyond what already
  exists.

B does not win against A given the measured data. Nothing about B being
newer or more automated makes it the default-correct choice; the schema
churn and duplication volume it would need to justify its cost simply
aren't present at this project's scale.

### Independence between services

A generation pipeline could, in principle, respect this project's
no-shared-library rule: each service would run its own generator locally,
against the same schema files, producing no shared artifact and no new
cross-service dependency. This was confirmed technically feasible — and
is precisely why this rejection rests on cost/benefit, not on a
constraint that would have made B impossible regardless. The absence of a
disqualifying technical obstacle is not, on its own, a reason to build
something whose measured benefit is close to zero.

### Developer experience

Today: change the schema, change the DTO, the contract test tells you
immediately if you missed a spot. With generation: change the schema, run
the generator, then still reconcile every consumer that captured a
narrower shape than the schema now emits, then still run the same
contract test to confirm the result. Generation does not remove a step —
it replaces "remember to update the DTO" with "remember to regenerate,
review the diff, and reconcile any partial consumer," which is not less
manual work, only differently-shaped manual work, layered under a new
tool three different ways.

### Portfolio value

This is the deciding factor for a project whose explicit purpose is
demonstrating engineering judgment, not shipping a product. Adding code
generation here would not demonstrate architectural maturity — measuring
actual schema churn, counting real DTOs, tracing every historical change
to confirm the drift rate before reaching for a tool, and then declining
to add one when the data doesn't support it, does. ADR-0002's Update
already delivers the architectural value that matters (a versioned,
enforced, mandatory-from-birth contract); this ADR's value is having
verified that before spending anything further on it.

## Decision

**Reject DTO code generation from JSON Schema at the project's current
scale.** This is a cost/benefit conclusion, not a rejection of the
technique itself — `jsonschema2pojo`/`go-jsonschema`/
`datamodel-code-generator` are all legitimate, well-established tools;
they simply don't pay for themselves against 17 events whose schemas have
never materially changed and whose one real drift was already caught by
the mechanism built for exactly that purpose. The Roadmap's technical
debt item asking whether this duplication should be closed by generation
is itself closed **by decision, not by implementation** — the duplication
it names is real and stays exactly as documented, but is judged an
acceptable, deliberately-chosen cost rather than a gap to close with more
tooling.

### Objective criteria for revisiting this decision

This is not a permanent, closed-forever verdict — it is a decision tied to
this project's current, observed shape, in the same spirit as
[ADR-0018](0018-persistence-strategy.md), [ADR-0023](0023-notification-service-persistence-evolution-strategy.md),
and [ADR-0032](0032-accept-order-state-machine-hybrid.md). Revisit it if
any of the following becomes true — none of which describes the project
today:

- **Event count grows by an order of magnitude.** Seventeen contracts
  across six services is not where hand-duplication becomes genuinely
  heavy; a few hundred would be a different question entirely.
- **Schema churn stops being near-zero.** Every schema here has one
  creation commit and no real content changes since. If schemas start
  changing regularly — a new field added or a type tightened every few
  weeks, say — the cost of manually propagating each change across every
  language would start to outweigh generation's setup cost.
- **A second real drift is found that a contract test did not catch
  before merge.** One occurrence, caught as designed, is not a pattern.
  A second occurrence that slips past the existing safety net would be
  real evidence the current approach's failure mode is more than
  theoretical.
- **A consumer's need for a full-shape, schema-exact DTO becomes common**,
  making the intentional-partial-consumer pattern this ADR protects the
  exception rather than the norm — at that point, generation's cost to
  that pattern stops being a real cost at all.

## Consequences

**Positive**: no new toolchain, no new per-language build integration, no
loss of the intentional-partial-consumer DTO pattern already in use; this
ADR now answers "why isn't this generated" for any future reader who
wonders, with the actual data behind the answer, instead of leaving the
question open by omission.

**Negative / known limitations**: the duplication itself is unchanged — a
new field added to a schema still requires a human to propagate it by
hand to every DTO that needs it, with the contract test as the only
backstop if one is missed. The `price` `BigDecimal`-vs-`float64` gap
[ADR-0002](0002-json-schema-contract-testing.md) already documented
remains open regardless of this decision — generation would not have
closed it either.

## References

- [ADR-0002](0002-json-schema-contract-testing.md) — the contract-testing
  rollout this ADR evaluates extending into code generation, and declines
  to.
- [ADR-0018](0018-persistence-strategy.md), [ADR-0023](0023-notification-service-persistence-evolution-strategy.md),
  [ADR-0032](0032-accept-order-state-machine-hybrid.md) — the precedent
  for a reviewed-and-kept decision with explicit, measurable reopening
  criteria, followed here.
- [architectural-principles.md](../architecture/architectural-principles.md)
  — principle 2 ("a component must earn its place") and principle 8
  (evidence over assumption), both directly deciding here.
- README Roadmap — the item this ADR closes by decision.
