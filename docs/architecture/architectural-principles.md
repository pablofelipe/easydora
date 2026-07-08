# Architectural Principles

This document was not written up front — it was extracted after the fact
from 17 accepted ADRs, by looking for reasoning that recurs across
*different* decisions rather than belonging to just one of them. Each
principle below is only included because at least two independent
decisions already rely on it; none were added because they sounded good in
the abstract. If a principle stops being reflected in how future decisions
are actually made, it should be edited or removed here, not left as
aspirational text.

This document records **general principles only**. It does not restate any
specific decision — for the decision itself, follow the ADR links in each
principle's evidence list. New ADRs should link back to the relevant
principle here instead of re-arguing the same reasoning from scratch.

## 1. Deliberate simplicity over engineered precision

When a concrete number or threshold is needed and no real measurement
exists to justify a specific value, pick a plain, unremarkable one and say
so explicitly — don't manufacture false precision by pretending it was
tuned.

**Evidence**: ADR-0006/ADR-0009's circuit breaker thresholds (5 failures /
30s) are "given directly, not derived or tuned against any measurement...
recorded here per this project's standing rule against inventing values
without justifying them"; ADR-0003's 5-second Outbox poll interval is "a
deliberately unremarkable middle value, not a tuned one."

## 2. A component must earn its place in the architecture

Every piece of infrastructure, abstraction, or mechanism has to justify
its presence by the value it actually provides today — not by what it
might provide, or by convention.

**Evidence**: ADR-0007 removes Kafka specifically because nothing in the
system uses any property that distinguishes it from RabbitMQ's existing
topic-exchange fan-out ("running two broker stacks... buys nothing here");
ADR-0016's shared parent POM is deliberately inheritance-only, not a full
Maven reactor, because a reactor would add build-coupling this project
doesn't need to get the value (shared dependency versions) it's actually
after.

## 3. Remove complexity that doesn't add architectural value

Closely related to #2, but about active removal rather than a gate on
adding something new: once a piece of complexity is found to add no
concept a reader would learn from, it gets deleted, not kept "just in
case."

**Evidence**: ADR-0007's primary driver — once the project's goal shifted
from demonstrating messaging-technology coexistence to demonstrating
architectural patterns, running two broker stacks stopped teaching
anything and started being pure overhead; ADR-0015 replaced auth-service's
unwired `.httpBasic()` with `denyAll()` once it was clear no endpoint
depended on it; ADR-0001 (finding 3) removed a redundant, silently-broken
publish call entirely rather than just fixing its routing key, once
investigation showed a different call site already covered the same
transition correctly.

## 4. Behavior over technology

The specific technology behind a capability matters less than the
capability and its observable behavior. Changing the wire format, broker,
or serialization mechanism is treated as a distinct decision from changing
what the system actually does.

**Evidence**: ADR-0002 explicitly scoped contract testing to sit "on top
of the existing format," not to migrate serialization (Avro/Protobuf) —
"a much larger and differently-motivated change"; ADR-0007's migration
target was RabbitMQ specifically because it's transport-agnostic to
existing JSON Schema contracts ("switching the wire from Kafka to RabbitMQ
has no bearing on how contracts are defined or enforced"); the [Design
notes](../../README.md#design-notes) choose Go/Spring Boot/FastAPI per
workload characteristics, not preference.

## 5. Avoid dead code

Code with no live caller or no longer-relevant purpose is deleted when
found, not left in place for hypothetical future use.

**Evidence**: ADR-0001 (finding 5) removed `PaymentEventProducer`/
`PaymentProcessedEvent`/`PaymentEventsConsumer` entirely once confirmed
unreachable; ADR-0005 removed orphaned `app.jwt.secret`/`jwt.secret`
configuration from three services that never consumed it; ADR-0015 removed
`E2ETestSupport.basicAuth(...)` once nothing called it anymore;
`inventory-service`'s dead `InventoryHandler` struct (built on gin, never
referenced by `main.go`'s real `net/http` routes) — found during
documentation work and initially left alone as out of scope for that
task — was later deleted along with the `gin-gonic/gin` dependency it was
the only user of.

## 6. Avoid unnecessary compatibility or hybrid modes

When a mechanism is replaced, it is replaced — not kept running alongside
its successor "just in case," and not bridged with a temporary
compatibility shim unless something external actually depends on the old
behavior.

**Evidence**: ADR-0007's Kafka-to-RabbitMQ migration is explicit about
this — "no hybrid mode, no dual-write, no temporary compatibility shim...
a breaking, single-cutover migration is acceptable and preferable to a
hybrid state," justified by there being no external consumers to protect
(a portfolio project); ADR-0015 fully replaces billing-service's Basic
Auth with the JWT broadcast pattern rather than keeping both — "a
deliberate, confirmed decision (not a default)."

## 7. Incremental, verifiable rollout toward a single clean end state

This is not in tension with #6. The *destination* of a decision is a single
mechanism, never a permanent hybrid — but *getting there* happens in
verified stages, each confirmed against real behavior before the next
begins, rather than as one large, unverifiable leap.

**Evidence**: ADR-0007's own Migration Strategy section sequences the
Kafka removal one hop at a time specifically "so each step can be verified
live against real containers before the next begins"; ADR-0004 fixed
auth-service's Flyway/Hibernate schema mismatch narrowly first, and only
once that pattern was confirmed did ADR-0011 generalize the same fix to
the other three services, rather than assuming upfront it applied
everywhere; CI itself was built the same way — Phase 1 (ADR-0008), then
Phase 2 (ADR-0012) gated on Phase 1 passing, then Phase 3 (ADR-0013) gated
on Phase 2.

## 8. Evidence over assumption

A claim about system behavior is only trusted once it's been reproduced —
against real containers, a real broker, or a real failing test — never
inferred from reading code alone or from what "should" happen.

**Evidence**: this is the single most repeated pattern across the ADR set.
Named explicitly in ADR-0001 ("confirmed again by reading the method fresh
for this ADR, not assumed from the prior catalogue"; every finding
reproduced against a live broker); ADR-0006/0009's live container
verification steps (stopping a real service, measuring real response
times and status codes); ADR-0012's investigation of a real race condition
"per this etapa's explicit 'investigate before adding retries'
instruction" instead of papering over a flaky test with a sleep.

## 9. TDD as the change driver

Changes are made by writing a test that fails for the expected reason
first, then making the minimal change that turns it green, then
re-confirming the rest of the suite — not by changing code and writing
tests afterward to match.

**Evidence**: ADR-0002's contract tests are written against the current
(broken) DTO first, confirmed failing for the catalogued reason, then
fixed; ADR-0003's Outbox tests are explicitly documented as Red-then-Green
against real RabbitMQ; ADR-0009's `TestSetupServiceRoutes_BillingUsesBreaker`
is "the actual Red test for this change... written and run *before* the
`main.go` change." This project's `easydora-tdd` skill (referenced
directly in ADR-0004) codifies this as the standing discipline, not just a
per-ADR habit.

## 10. ADR-driven architectural documentation, honest about what's unresolved

Every non-trivial architectural decision gets its own ADR with Context,
Decision, and Consequences — and every Consequences section names what's
*not* fixed, not just what's improved. Older ADRs are updated in place
(via a dated "Update" section) when a later change changes their premise,
rather than left to silently go stale.

**Evidence**: 17 ADRs, each with a "Negative/residual" or "Not fixed
here" section rather than a purely positive summary; ADR-0007, ADR-0008,
ADR-0001, and ADR-0015 all carry dated "Update" sections recording how a
later change affected an earlier decision, instead of a second ADR
re-explaining the same context.

## 11. Reduce cognitive load without losing architectural capability

When the same boilerplate, convention, or judgment call is repeated across
services, prefer consolidating it into one shared, greppable pattern — but
only where doing so doesn't remove a capability (independent buildability,
independent deployability) the project actually relies on.

**Evidence**: ADR-0016's shared parent POM centralizes dependency/plugin
versions duplicated four times over, while deliberately keeping each
service independently buildable (no `<modules>` reactor); ADR-0008's `*IT`
suffix convention makes "this test needs real infrastructure" a fact
greppable in the filename, not something you have to read each file to
know; ADR-0010 replaced four services' bespoke, broken health-check setups
with one consistent pattern (`/health`, unauthenticated, same probe shape)
across all six services.

## References

- [README](../../README.md) — project overview and the ADR index these
  principles were extracted from.
- [Architecture Overview](overview.md) — the system map (bounded contexts,
  communication, persistence, events) these principles were applied to.
- Individual ADRs are cited inline above per principle; see
  `docs/adr/` for the full set.
