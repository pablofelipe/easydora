# ADR-0002: JSON Schema contract testing between services

## Status

Accepted - 2026-07-03

## Context

The baseline audit (2026-07-03) catalogued this repository's complete lack of contract testing between services: every event/command DTO exchanged over Kafka or RabbitMQ is hand-duplicated per service and per language, with nothing to catch drift when a copy silently diverges. Three concrete instances of drift were already known at that point: `UserEvent.userId` typed `Long` in orders-service but `String` in products-service; `OrderCreatedEvent.items` present in orders-service but absent in billing-service; `price` typed `BigDecimal` in the Java services but `float64` in inventory-service (Go).

The payload format itself (JSON over the wire) was not in question — the goal was to add a versioned, testable contract on top of the existing format, not to migrate to a new serialization scheme (Avro, Protobuf, etc.), which would be a much larger and differently-motivated change.

## Decision

Adopt JSON Schema (draft 2020-12) as the contract format, with schemas versioned directly in this repository — no separate schema registry service, consistent with this project's otherwise lightweight, no-extra-infrastructure approach.

- Schemas live in `/schemas/json/`, one file per event, named after the event (`OrderCreatedEvent.schema.json`, `UserRegisteredEvent.schema.json`).
- Each service that publishes or consumes a given event gets its own contract test (e.g. `OrderCreatedEventContractTest`, `UserEventContractTest`) that builds an instance of its local DTO the same way the real producer/consumer does, serializes it, and validates the result against the shared schema file using `com.networknt:json-schema-validator` (pinned to the 1.5.x line — the library's 2.0.x line is a from-scratch API rewrite with a different, incompatible class surface, discovered by trying it first and hitting compile errors).
- Applied with strict red-green TDD: write the contract test against the current DTO first, confirm it fails for the reason already catalogued, then fix the DTO, then confirm green and re-run the full service suite.

Two of the three catalogued drifts were closed this way:

- **`OrderCreatedEvent.items`**: billing-service's copy had no `items` field at all — Jackson's ignore-unknown-properties setting was silently dropping it on the wire, so billing-service never saw order line items. Confirmed failing (`items` reported missing) against the shared schema, then added a matching `items`/`OrderItem` shape to billing-service's DTO.
- **`UserEvent.userId`**: products-service typed it `String`; auth-service (the actual publisher, via `UserRegisteredEvent`) sends it as a number. Confirmed failing (`string found, integer expected`), then changed products-service's field to `Long`, updating the two call sites that key the JPA-backed, `String`-keyed `Seller` entity to convert via `.toString()` at that boundary — the entity's own ID type was intentionally left alone, since changing it was a separate, larger decision than fixing the wire contract.

## price: BigDecimal vs float64 — known limitation, not covered

The third catalogued drift, `price` as `BigDecimal` in products-service/orders-service versus `float64` in inventory-service (Go), is **not** covered by a schema. JSON Schema's `number` type does not distinguish decimal precision — a schema for `ProductCreatedEvent` would validate that `price` is *a* number on both sides and would not catch the precision loss between a fixed-point `BigDecimal` and a floating-point `float64`. Catching this class of drift would need a different technique entirely (e.g. asserting on the exact serialized string form, or a numeric-precision-aware comparison), which was out of scope for this round. This is recorded as a known gap, not something contract testing was expected to close incidentally.

## Consequences

**Positive**: two of three catalogued schema drifts are fixed and now regression-tested; the contract-testing pattern (schema file + per-service test) is established and repeatable for future events.

**Negative / residual**:
- Validation is local and manual only — there is no CI anywhere in this repository running these tests. A schema or DTO can drift again after this point without anyone noticing until the next person runs `mvn test` by hand.
- No schema exists yet for `ProductCreatedEvent` (or any event beyond the two covered here); the `price` type drift remains open and would not be caught even if a schema were added, per the limitation above.
- The messaging-wiring audit that followed this one (ADR-0001) found a materially different, and in one case (the JWT/UserEvents incident) more severe, category of bug that schema-level contract testing structurally cannot catch, since it validates payload shape, not routing or listener topology.

## Update — 2026-07-13: from a one-off fix to a permanent architectural rule

### Why only two events got a schema initially

Contract testing landed on `OrderCreatedEvent` and `UserRegisteredEvent`
specifically because a baseline audit had already flagged real drift on
them — they got schemas *because* they had already caused a problem, not
because a rule required every event to have one. Every other event
published in this project — `product.*`, `stock.*`, `payment.*`,
`order.status-changed`, and later the commands `stock.reserve`/
`stock.release`/`payment.refund.requested` — kept relying on implicit
compatibility between hand-duplicated DTOs, with nothing to catch drift
until it broke something.

### Why this was revisited

Two different, unrelated production paths coexisting is a bigger problem
than any single missing schema: a reader can't tell whether an event has
no schema because it was never a risk, or because nobody got around to it
yet. The fix isn't "add more schemas" by itself — it's making the decision
a standing rule so this asymmetry can't recur. The survey below also
surfaced a real, previously undetected drift that a schema
alone would never have caught without someone deliberately writing one:
`JwtCreatedEvent.userId` was a raw `String` (the JWT subject claim,
unconverted) while every consumer already treated it as numeric, working
only by Jackson/Python's implicit string-to-number coercion. Fixed to
`Long`, matching every consumer's real assumption — direct confirmation
that broadening coverage finds real bugs, not just paperwork.

### Alternatives reconsidered: AsyncAPI, OpenAPI Event Extensions

Both were evaluated again, specifically for this broader rollout, not just
inherited from the original decision:

- **AsyncAPI** describes channels/bindings/operations for event-driven
  APIs, but a message's `payload` in an AsyncAPI document is itself
  typically a JSON Schema — adopting it would layer a new spec format and
  toolchain (generally Node-based) on top of the JSON Schema this project
  already has working, with no additional drift-catching power over the
  actual point of this exercise.
- **OpenAPI Event Extensions** don't really exist as a standard for
  broker-based pub/sub: OpenAPI models HTTP; its `webhooks` section (3.1)
  covers HTTP callbacks, not RabbitMQ topic-exchange messaging with
  routing keys. Using it here would mean non-standard `x-` extensions no
  mainstream tool understands — a worse structural fit than either other
  option.
- **JSON Schema** remains the right choice: it is already integrated in
  four of six services with a real track record (this ADR's own two fixed
  drifts, plus the `userId` fix above), and it is genuinely
  language-agnostic by design — Go (`santhosh-tekuri/jsonschema/v5`) and
  Python (`jsonschema`) both have mature, single-purpose validator
  libraries, so extending coverage to `inventory-service`/
  `notification-service` was a natural continuation, not a stretch.

### Scope: all 17 currently-published messages, commands included

A full survey (producer, consumer(s), schema present, location) found 17
distinct routing keys in active use, only 2 covered:

| Event | Producer | Consumer(s) | Kind |
|---|---|---|---|
| `user.registered` | auth-service | products-service (SELLER), orders-service | fact |
| `user.verified` | auth-service | products-service (SELLER), orders-service | fact |
| `jwt.created` | auth-service | products-service, orders-service, billing-service, notification-service | fact |
| `product.created` | products-service | inventory-service, orders-service | fact |
| `product.updated` | products-service | inventory-service | fact |
| `product.deleted` | products-service | inventory-service | fact |
| `stock.reserve` | orders-service | inventory-service | command |
| `stock.release` | orders-service | inventory-service | command |
| `stock.reserved` | inventory-service | orders-service | fact |
| `stock.insufficient` | inventory-service | orders-service | fact |
| `order.created` | orders-service | billing-service, notification-service | fact |
| `order.status-changed` | orders-service | notification-service | fact |
| `payment.approved` | billing-service | orders-service | fact |
| `payment.failed` | billing-service | orders-service | fact |
| `payment.refund.requested` | orders-service | billing-service | command |
| `payment.refunded` | billing-service | orders-service | fact |
| `payment.refund.failed` | billing-service | orders-service | fact |

Commands (`stock.reserve`/`stock.release`/`payment.refund.requested`) got
schemas too, deliberately, alongside the fact-events: a drift risk between
producer and consumer doesn't care whether the message is phrased as an
instruction or an announcement. `notification-service` publishes no public
event at all — confirmed, not assumed, so there was nothing to cover on
its producer side.

What stays out of scope: `OrderEvent`/`OrderState` (the enums driving
`orders-service`'s Spring State Machine) never leave that process — they
are internal transition signals, never serialized onto RabbitMQ — so they
are not "events" in the contract-testing sense at all, and get no schema.

### Organization: `/schemas/json/`, kept — files renamed to routing key

The ticket that reopened this decision suggested a `contracts/` directory;
this project keeps `/schemas/json/`, the location ADR-0002 already
established and five existing tests already reference — moving it would
be pure churn with no benefit.

One real change: schema file names now follow the **routing key**
(`payment-approved.schema.json`), not the DTO class name
(`OrderCreatedEvent.schema.json`, `UserRegisteredEvent.schema.json` — both
renamed to `order-created.schema.json`/`user-registered.schema.json`).
Reason: `PaymentEvent` is the identical Java class used for four different
routing keys (`payment.approved`/`payment.failed`/`payment.refunded`/
`payment.refund.failed`), each with its own distinct required-field
guarantee (approved always carries `transactionId`, never
`failureReason`; failed is the reverse) — naming by class would have been
ambiguous the moment a second routing key shared a class, and one schema
per routing key checks each guarantee precisely instead of merging them
into a looser, shared shape.

### Testing strategy for asymmetric (partial) consumers

Not every consumer captures every field a schema requires — and
deliberately so. `orders-service`'s own `ProductCreatedEvent` (consuming
`product.created`) declares only `productId`/`sellerId` by design;
`billing-service`'s `JwtEvent` (consuming `jwt.created`) omits
`createdAt`/`expiresIn`; `notification-service`'s `_cache_jwt_created`
reads only 4 of `jwt.created`'s 8 fields. The established pattern
(serialize the local DTO, then validate it against the schema) does not
work for these: a partial DTO would always fail the full producer schema's
`required` list, for no real contract violation.

The rule adopted for these cases: start from a schema-conformant example
payload (what the real producer sends), run it through the real
consumer-side code (deserialize into the local DTO, or call the actual
parsing function directly), and assert only that the fields this consumer
*does* declare came through with the right name and type. This is not a
weaker check — it validates the exact same real production code path a
symmetric consumer's test does; it just doesn't demand a consumer capture
fields it has already decided, elsewhere and for good reason, to ignore.
Raw-`JsonNode`/dict consumers with no fixed DTO at all (`orders-service`'s
`InventoryEventsConsumer`, `notification-service`'s dict-based handlers)
use the same shape of test: validate the example payload, then feed it
through the real handler function and assert the real side effect.

### CI: already sufficient, corrected a stale claim

This ADR's original "Negative / residual" section claimed no CI ran these
tests — true when written, but stale since: CI Phase 1
(`.github/workflows/ci.yml`) now runs `mvn test` for every Spring service,
`go test -race ./...` for the Go services, and `pytest -m "not
integration"` for `notification-service`. Every contract test added here
is a plain unit test in one of those three categories, so it already runs
on every push/PR, and any failure already fails the pipeline immediately
— no new CI job, no new tooling, was needed to satisfy that goal.

### Governance: the standing rule

**Every new event or command introduced into this project's messaging
must be born with its JSON Schema and its contract test(s), in the same
change that introduces the event itself.** "Add the schema later" is no
longer an acceptable phase of shipping a new message — a producer or
consumer for an event with no schema should not merge. This is now
enforced by convention and code review (documented in `CONTRIBUTING.md`
and the README), the same way this project already enforces its other
architectural rules (schema-only DB changes, no synchronous cross-service
calls, etc.) without a dedicated tool — consistent with not building a
bespoke contracts framework for a project this size.

### Consequences of this update

**Positive**: every currently-active message in this system now has a
versioned, testable contract, closing the exact asymmetry that motivated
revisiting this decision; a real drift (`JwtCreatedEvent.userId`) was
found and fixed as a direct result; the asymmetric-consumer testing
pattern is now established for future partial consumers instead of being
invented ad hoc each time.

**Costs accepted**: two new dependencies, one per newly-covered language
(`santhosh-tekuri/jsonschema/v5` for Go, `jsonschema` for Python) — both
single-purpose and already the natural choice, not a framework; ~35 new
small test files across six services, all fast (no live infra, matching
the existing pattern); the `price` `BigDecimal`-vs-`float64` precision gap
this ADR already documented remains open for `product.*` — still not
something JSON Schema's `number` type can catch, unchanged by this update.

## References

- ADR-0001 (messaging wiring audit) — the follow-up audit this contract-testing work's event inventory made possible.
- [ADR-0034](0034-payment-compensation-saga.md) — the command/fact-event
  distinction this update applies uniformly to schema naming, first drawn
  there for `RefundPaymentCommand`.
- [ADR-0024](0024-distributed-tracing-via-propagated-identifiers.md) — the
  `correlation-commons` shared package, the one precedent in this repo for
  a small cross-service shared library, deliberately not extended to
  event DTOs themselves (manual duplication stays, per this project's own
  documented polyglot trade-off).
