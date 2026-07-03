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

## References

- ADR-0001 (messaging wiring audit) — the follow-up audit this contract-testing work's event inventory made possible.
