# ADR-0007: Remove Kafka broker (migrate to RabbitMQ Topic Exchange)

## Status

Accepted - 2026-07-05

## Context

Two brokers currently run side by side for no technical reason that survives
scrutiny. RabbitMQ already carries the auth broadcast (`auth.exchange`) and
the order/inventory command exchange (`order.exchange`), both declared as
durable `topic` exchanges (`auth-service/.../RabbitMQConfig.java`,
`inventory-service/internal/messaging/rabbitmq_consumer.go`) — this pattern
is proven, not hypothetical: ADR-0001 fixed a real fan-out incident on it by
adding a second bound queue to the same exchange.

Kafka, meanwhile, carries four independent flows, none of which use any
property that distinguishes Kafka from a topic-exchange fan-out:

| Kafka topic | Producer | Consumer(s) |
|---|---|---|
| `product.created` / `product.updated` / `product.deleted` | products-service | inventory-service |
| `stock-reserved` / `stock-insufficient` | inventory-service | orders-service |
| `order.created.topic` | orders-service | billing-service |
| `order-status-changed` | orders-service | *(none — open item, ADR-0001)* |

No consumer group scales beyond one instance, nothing replays from an
offset, and no domain logic relies on partition-ordering guarantees. Every
one of these is a single producer fanning out to one or two known
consumers — exactly what `auth.exchange`/`order.exchange` already do
reliably. Running two broker stacks (Kafka + Zookeeper, plus a second set of
client libraries, health checks, and reconnect logic per language) buys
nothing here.

This is a portfolio project: there are no external consumers to preserve
compatibility for, so a breaking, single-cutover migration is acceptable and
preferable to a hybrid state.

## Decision

RabbitMQ becomes the **only** broker in the project. Kafka and Zookeeper are
removed entirely — no hybrid mode, no dual-write, no temporary compatibility
shim between the two brokers during or after the migration.

Every Kafka topic maps one-to-one to a routing key on a `topic` exchange,
reusing the two exchanges that already exist wherever the owning service
already has one, and adding exactly one new exchange where none does:

| Kafka topic (today) | RabbitMQ target |
|---|---|
| `product.created` | `product.exchange` (new, owned by products-service) / `product.created` |
| `product.updated` | `product.exchange` / `product.updated` |
| `product.deleted` | `product.exchange` / `product.deleted` |
| `stock-reserved` | `order.exchange` (existing) / `stock.reserved` |
| `stock-insufficient` | `order.exchange` (existing) / `stock.insufficient` |
| `order.created.topic` | `order.exchange` (existing) / `order.created` |
| `order-status-changed` | `order.exchange` (existing) / `order.status-changed` |

`order.exchange` already carries `stock.release`/`ReserveStockCommand`
traffic between orders-service and inventory-service, so `stock.reserved`/
`stock.insufficient` (the reverse direction) and `order.created` (consumed by
billing-service) join it rather than spawning new exchanges per hop.
`product.exchange` is the one genuinely new exchange, since products-service
doesn't currently own any RabbitMQ exchange — it is declared defensively on
both the producer and consumer side, mirroring how `order.exchange` is
already independently declared by both orders-service (Java) and
inventory-service (Go).

`order-status-changed` keeps its current status: published, with zero
consumers. Moving its transport doesn't resolve that open design question
from ADR-0001 — it remains a pending decision, not something this ADR
settles.

JSON Schema (ADR-0002) remains the single authority for event/command
contracts, unchanged: schema validation is transport-agnostic, so switching
the wire from Kafka to RabbitMQ has no bearing on how contracts are defined
or enforced. New schemas for the `product.*` and `stock.*` payloads should be
added during implementation, following the same process already used for
`OrderCreatedEvent` and `UserRegisteredEvent`.

inventory-service's still-open outbox pattern (tracked since the baseline
audit, same shape as ADR-0003's auth-service implementation) will publish to
RabbitMQ once built — no redesign of the outbox concept itself, just a
single target broker instead of a choice between two.

## Consequences

**Positive**: one broker to operate, monitor, and reason about instead of
two; every asynchronous interaction in the system — broadcast, command, or
event — goes through the same already-proven topic-exchange pattern;
removes an entire parallel stack of client libraries, container health
checks, and reconnect/backoff logic that existed only to do what RabbitMQ
already does elsewhere in the same project.

**Negative / residual**:
- Loses Kafka-specific capabilities nothing here currently depends on
  (partitioned ordered log, offset replay, consumer-group horizontal
  scaling). If a genuine future need for those emerges, it means
  reintroducing a log-based broker, not a small configuration change.
- `order-status-changed` still has no consumer after the migration — an
  unrelated, still-open decision from ADR-0001.
- Every current Kafka producer/consumer (products-service, orders-service,
  billing-service, inventory-service) needs code changes to execute this
  decision; `docker-compose.yml`, four `pom.xml` files (`spring-kafka`),
  inventory-service's `go.mod` (`segmentio/kafka-go`), and the existing Kafka
  integration test (`kafka_stock_events_integration_test.go`) all become
  obsolete. None of that is done by this ADR — it records and designs the
  decision only; implementation is the immediate next step of Etapa 4,
  covered by its own PRs/commits.

## Migration Strategy

Direct cutover, no dual-broker window, sequenced one hop at a time so each
step can be verified live against real containers before the next begins —
consistent with this project's existing TDD + live-Docker-verification
discipline:

1. **products-service → inventory-service** (`product.*` events). Smallest,
   self-contained pair; migrate first. products-service declares and
   publishes to `product.exchange`; inventory-service's Kafka readers
   (`kafka_consumer.go`) are replaced with RabbitMQ consumers bound to the
   same exchange.
2. **inventory-service → orders-service** (`stock.reserved` /
   `stock.insufficient`). inventory-service starts publishing these to
   `order.exchange` instead of its Kafka writers (`kafka_producer.go`);
   orders-service's `InventoryEventsConsumer` moves from `@KafkaListener` to
   `@RabbitListener` on the same exchange.
3. **orders-service → billing-service** (`order.created`). orders-service
   publishes to `order.exchange` / `order.created` instead of
   `order.created.topic`; billing-service's `OrderCreatedConsumer` moves
   from `@KafkaListener` to `@RabbitListener`.
4. **orders-service `order-status-changed`**. Transport swapped for
   consistency only — no functional change, since there is still no
   consumer.
5. **Decommission**. Once all four hops are migrated and live-verified,
   remove Kafka and Zookeeper from `docker-compose.yml`; remove
   `spring-kafka`/`kafka-clients` from the four Spring services' `pom.xml`
   and `segmentio/kafka-go` from inventory-service's `go.mod`; delete the
   now-dead `Kafka*Config` classes and the Kafka integration test; update
   README and ADR cross-references accordingly. This is the first
   implementation task after this ADR, not part of it.
6. **Contracts and outbox**: add JSON Schemas for `product.*` and `stock.*`
   payloads during step 1–2's implementation, following ADR-0002's existing
   process; inventory-service's future outbox implementation targets
   RabbitMQ from the start, per the Decision above.
