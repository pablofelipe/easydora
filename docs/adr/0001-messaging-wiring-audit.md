# ADR-0001: Messaging wiring audit (RabbitMQ + Kafka routing/field/listener bugs)

## Status

Accepted - 2026-07-03

## Context

The Etapa 2 JSON Schema contract-testing effort (ADR-0002) audited every event/command DTO in the repository for *schema drift* — field-name and type mismatches between publisher and consumer. That audit's byproduct was a full inventory of every Kafka topic and RabbitMQ queue/exchange/routing-key wired up across auth-service, products-service, orders-service, billing-service and inventory-service.

Reading that wiring closely surfaced a different class of bug: not schema drift, but routing, field-naming and listener mistakes that happened to keep "working" in a way that silently dropped data or silently dropped messages entirely, with nothing in the codebase (no test, no log, no telemetry) to reveal it. This ADR documents that follow-up audit — six findings, verified against live RabbitMQ/Kafka (docker-compose), not inferred from reading code alone.

## Decision

Fix the deterministic bugs (found to have one unambiguous correct fix); stop and get explicit authorization before fixing anything where the fix implied a design decision, not just a correction.

### 1. `ReleaseStockCommand.OrderID` arrived empty at inventory-service

orders-service (Java) serializes `ReleaseStockCommand.orderId` as `orderId` (camelCase, Jackson's default bean naming). inventory-service's Go struct tagged the same field `json:"order_id"` (snake_case) — inconsistent with `ReserveStockCommand`, which correctly uses `json:"orderId"` on the same broker. Every `ReleaseStockCommand` ever consumed decoded `OrderID` as `""`, breaking traceability of stock releases. Reproduced against a real RabbitMQ round trip (published the exact JSON shape Java produces, ran the real Go consumer). Fixed by aligning the tag to `orderId`.

### 2. `StockReservedEvent`/`StockInsufficientEvent` silently dropped their own fields

Both structs already existed in inventory-service's Go models with rich fields (`productId`, `required`, `available`, `success`, `message`), but the Kafka producer only ever published the raw `orderId` bytes — the structs were defined but never serialized. Confirmed against a live Kafka topic: the consumed payload failed `json.Unmarshal` into the struct (`invalid character 'o' looking for beginning of value` — a bare string isn't valid JSON). Fixed by serializing the full struct on publish, and updating orders-service's `InventoryEventsConsumer` to parse the JSON payload (still received as a Kafka `String` value — no consumer-factory-wide `JsonDeserializer` change, since that would affect every `@KafkaListener` in the service) instead of assuming a bare order ID.

### 3. `inventory.release` routing key had no binding — message always discarded, and the call site was redundant

`OrderService.publishInventoryRelease`, called from `cancelOrder` when the previous state was `INVENTORY_RESERVED`, published an ad-hoc `Map` (no `items` field at all) to routing key `inventory.release` on `order.exchange` — no queue in the system binds to that key. Proven with `mandatory=true` + `NotifyReturn` against a live broker (the broker returns `NO_ROUTE`), not inferred from a timeout.

Investigating *why* before just fixing the routing key (per the standing rule: stop on any new bug found mid-task) turned up that the call was entirely redundant: `ReleaseInventoryAction`, wired into the *same* state-machine transition (`INVENTORY_RESERVED` + `CANCEL_ORDER` → `CANCELLED`), already publishes a correct, complete `ReleaseStockCommand` to `stock.release` (see finding 1). Fixing the routing key alone would have made the ad-hoc message start arriving — but since it never carried `items`, it would have landed as a silent no-op (logs "released" while releasing nothing), trading one silent bug for a subtler one.

**Decision, made explicitly rather than assumed**: remove `publishInventoryRelease` and its call site entirely, rather than give it a working routing key. `ReleaseInventoryAction` already covers this transition correctly.

### 4. `JwtConsumer` / `UserEventsConsumer` competing on the same queue

See the incident writeup below — this one gets its own section because of its severity and duration, not because the technical fix was any more involved than the others.

### 5. `PaymentEventProducer.sendPaymentProcessedEvent` — dead code, and would have been incompatible anyway

`PaymentEventProducer` (billing-service) is never called anywhere in the repository. Even if it were, it would have been broken on arrival: `PaymentProcessedEvent.orderId` is typed `Long`, but `Order.id` in orders-service is a `String` UUID (`UUID.randomUUID().toString()`) — the types can never match — and its intended RabbitMQ listeners (`PaymentEventsConsumer`) declared their parameter as a bare `String orderId`, not the JSON object the producer would have sent.

**Decision**: removed as dead code — `PaymentEventProducer`, `PaymentProcessedEvent`, and orders-service's `PaymentEventsConsumer`, along with their now-unused queue/binding declarations in both services' `RabbitMQConfig`. `OrderService.handlePaymentReceived`/`handlePaymentFailed` were deliberately *not* removed: they're real state-machine transitions (part of the documented order lifecycle), not messaging plumbing, and removing them would be a larger, different decision than "delete dead messaging code." They are simply unreachable now, until payment processing is wired to call them by some other means.

### Incident: `orders.jwt.created.queue` shared by two competing consumers

`JwtConsumer` (session/auth — populates the in-memory JWT cache) and `UserEventsConsumer` (profile update, buyer creation) both declare `@RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)` — the identical, hardcoded queue name, not a routing-key coincidence. This has been true since orders-service's very first commit (`fc3e8bf`, 2025-10-22); no later commit touched either listener to address it, and there is no TODO/FIXME/test/log anywhere referencing it.

RabbitMQ's competing-consumers semantics mean every individual `jwt.created` message is delivered to *exactly one* of the two listeners, round-robinned — not both. Since both consumers process the *same* event for two unrelated purposes, this means each side has, for the entire life of the project, received roughly half of all `jwt.created` events and silently missed the other half. There is no telemetry anywhere in this repository (no metrics, no structured logs retained) to confirm or rule out real-world impact — this is reported as a known, dated exposure window, not a measured one.

**Fix**: `UserEventsConsumer` now listens on its own queue, `orders.jwt.created.profile.queue`, bound to the same exchange and routing key as `JWT_CREATED_QUEUE`. auth-service (the publisher) is unchanged — it still sends one message per event. RabbitMQ's topic exchange now fans out one independent copy to each queue, so both consumers reliably receive every message. Verified against a live broker: one publish, one message observed on each queue.

## Consequences

**Positive**: stock-release traceability restored (finding 1); insufficient-stock/reserved events carry their full data over the wire again (finding 2); one redundant, silently-broken publish path removed instead of patched into a quieter bug (finding 3); the JWT/profile event-loss incident is closed with a verified fix (finding 4); one dead, format-incompatible code path removed instead of left to confuse a future reader (finding 5).

**Negative / residual**:
- The JWT/UserEvents incident's real-world impact (finding 4) cannot be retroactively measured — there is no historical telemetry. The fix stops it going forward; it does not tell us what was actually lost.
- `OrderService.handlePaymentReceived`/`handlePaymentFailed` are now confirmed unreachable dead ends in the call graph — a future payment integration will need to wire something new to call them, not just restore what existed.

**Pending — not resolved by this ADR**: `OrderStatusChangedEvent` (Kafka topic `order-status-changed`) is published at several points in the order lifecycle with no consumer anywhere in the repository. This is published ahead of the order lifecycle with no consumer defined yet — a pending design decision on whether notification-service (currently empty) or another service should consume it, not an oversight. No action was taken on it as part of this audit; it is tracked here for whoever makes that call.

## Update — 2026-07-08

notification-service ([ADR-0014](0014-notification-service.md)) has since been implemented — no longer the speculative "currently empty" candidate this ADR named above. Its designated future consumer is now settled: `notification-service` is the intended consumer of `order.status-changed`, the same way it already consumes `order.created` today. Implementation status and tracking belong in the README Roadmap, not here — this Update only records that the destination question this ADR left open is decided.

## References

- ADR-0002 (JSON Schema contract testing) — the audit that led to this one.
- Baseline audit (2026-07-03 entry in this repo's history) — original catalogue of architectural debt (no outbox pattern, no circuit breaker/retry, no contract testing), which this ADR and ADR-0002 both partially address.
- [Architectural Principles](../architecture/architectural-principles.md)
  — every finding here was reproduced against a live broker before being
  called a bug (principle #8, evidence over assumption); finding 5's
  removal of `PaymentEventProducer` follows principle #5 (avoid dead code).
