# ADR-0037: Consolidated Outbox Pattern specification

## Status

Accepted - 2026-07-14

## Context

[ADR-0003](0003-outbox-pattern-auth-service.md) added the Outbox Pattern
to auth-service's `verifyEmail` (`user.verified`). [ADR-0007](0007-remove-kafka-broker.md)'s
2026-07-06 Update records that inventory-service (Go) independently built
the same pattern for `stock.reserved`/`stock.insufficient`, going further
than that ADR's own plan by writing the outbox row in the same Postgres
transaction as the stock reservation itself. Between them, these are the
only two places in the system where a publish is protected against a
crash between commit and send — every other publish in the system,
including the other three events auth-service itself produces
(`user.registered`) and all of `products-service`, `orders-service`, and
`billing-service`, remains best-effort.

Revisiting both implementations before extending the pattern anywhere
else (as [ADR-0034](0034-payment-compensation-saga.md) already flagged as
open work — see its Roadmap item on `orders-service` having no Outbox at
all) surfaced two things:

**The two implementations already agree on everything structural.** Same
`outbox_events` table shape (`id`, `exchange`, `routing_key`, `payload`,
`created_at`, `published_at` + a partial index on unpublished rows), the
same 5-second fixed-delay poll (Go's own code comments record this as a
deliberate match to Java's `@Scheduled(fixedDelay = 5000)`, not an
independent derivation), and the same three-field envelope
(`correlationId`/`messageId`/`body`) wrapping the stored payload so a
row's tracing identifiers survive the write-now/publish-later gap. Both
mark a row published only after the broker accepts the send, and both
leave a failed row untouched for the next poll — at-least-once, never
silently dropped.

**Neither was ever specified as one cross-cutting concern, and it
shows.** Two independently-built implementations, in two languages,
without a single ADR describing them together, drifted in exactly the
places nothing pinned them down:

- Neither had any metric on the publisher itself — no visibility into
  whether the outbox backlog is draining or how long a row waits before
  being published.
- Logging was inconsistent in opposite directions. Java's
  `OutboxPublisher` logged nothing on a successful publish and, on
  failure, logged through plain SLF4J without the row's correlationId
  ever reaching MDC. Go's `OutboxPublisher` logged a structured, fully
  correlated line on success, but fell back to an unstructured
  `log.Printf` with no correlation fields on all four of its own failure
  paths (query, envelope decode, publish, mark-published).
- Auth-service's Outbox has its own ADR (ADR-0003); inventory-service's
  has none — it exists only as an aside inside ADR-0007, an ADR about
  removing Kafka.

## Decision

**Treat the Outbox Pattern as one specification, not two parallel
implementations, and harmonize the drift found above without changing
anything about the design that was already right.**

Kept as-is (proven, and now explicitly the canonical shape for any future
adoption):
- The `outbox_events` table shape, the three-field envelope, and the
  5-second fixed-delay poll.
- Each language's own idiomatic mechanism for marking a row published
  (JPA entity mutation in Java, a raw `UPDATE` in Go) — this was never a
  real inconsistency, just two idiomatic ways to do the same write.

Changed in both `auth-service/.../service/OutboxPublisher.java` and
`inventory-service/internal/messaging/outbox_publisher.go`:
- **Logging, symmetric in both directions.** Java now populates MDC with
  the row's correlationId/messageId before logging (removed in a
  `finally`, the same pattern already used by every `@RabbitListener` in
  this codebase) and logs a success line through `BusinessEventLog.info`
  — previously silent. A new `BusinessEventLog.error` overload
  (`correlation-commons`) gives failures the same `event=`/`aggregateId=`/
  `msg=` shape as successes. Go's four failure paths now go through a new
  `correlation.Error` function (`correlation-commons-go`, `Info`'s
  error-level counterpart) instead of unstructured `log.Printf`, carrying
  correlationId/messageId wherever they're already known (i.e.
  everywhere except a still-undecoded envelope).
- **Two new metrics per language**, following this project's existing
  convention (ADR-0036) of a business-facing counter answering a question
  infra-level metrics can't: `outbox_events_published_total` (a Counter,
  incremented once per real publish) and `outbox_publish_lag_seconds` (a
  Timer in Java / Histogram in Go, observing `now - created_at` at the
  moment of a successful publish). Both are wired the same way ADR-0036's
  five business counters already are — constructor-injected `MeterRegistry`
  in Java, a package-level `promauto` var in Go.

### Adoption criterion for future publishes

No ADR previously stated why `user.verified` and
`stock.reserved`/`stock.insufficient` got Outbox while every other
publish in the system didn't; the distribution looks incidental, not
deliberate, once every publish in the system is listed side by side. The
criterion this ADR adopts, for any future decision about extending Outbox
to another publish:

> Outbox is justified for a publish whose loss or non-persistence would
> leave a cross-service business process stalled, orphaned, or
> desynchronized — independent of whether the caller who triggered it
> would notice the failure, and independent of whether an unrelated
> mechanism (such as [ADR-0019](0019-message-consumption-resilience.md)'s
> consumer-side retry/DLQ, which protects a different failure class —
> a message that was delivered but failed processing, not a message that
> never left the process) happens to mitigate a related symptom.

This criterion, and which of `orders-service`'s and `billing-service`'s
publishes it applies to, is not decided by this ADR at the time it was
written — extending the pattern to either service was separate,
forthcoming work, tracked by the existing Roadmap item this ADR did not
close. This ADR's own scope, as originally written, was the specification
and the harmonization above. See the 2026-07-15 Update below for
`orders-service`'s extension.

### What was deliberately not done here

- **No `attempts` counter or poison-pill visibility.** A row that can
  never be published (a permanently malformed envelope, for instance)
  still retries silently forever, once every 5 seconds, with nothing
  beyond a log line marking each attempt. Adding a bounded-attempts
  escape hatch (a counter column, a threshold, a dedicated metric once
  crossed) is a real, identified gap, deliberately deferred rather than
  bundled into a logging/metrics harmonization pass — see the objective
  criteria below.
- **No batch size on the poll query.** Both implementations still fetch
  every unpublished row on every poll. Acceptable at this project's
  volume; deferred for the same reason as above.
- **No distributed locking or leader election on the poller.** Unchanged
  from ADR-0003 — still fine for a single-instance deployment of each
  service, still would need one before either service could run more
  than one replica.

## Objective criteria for revisiting this decision

In the same spirit as [ADR-0018](0018-persistence-strategy.md),
[ADR-0035](0035-reject-dto-code-generation-from-json-schema.md), and
[ADR-0036](0036-metrics-via-prometheus-grafana.md):

- **A row is found stuck retrying indefinitely in a real environment** —
  at that point the deferred `attempts`/poison-pill visibility earns its
  place; today it's a theoretical gap, not an observed one.
- **The unpublished backlog regularly exceeds a few hundred rows** — at
  that point the unbounded poll query needs a `LIMIT`/batch size; today's
  volume never approaches it.
- **A service adopting this pattern needs more than one running
  instance** — at that point the poller needs distributed locking or
  `SELECT ... FOR UPDATE SKIP LOCKED`; every current adopter is
  single-instance.

## Consequences

**Positive**:
- Auth-service's and inventory-service's Outbox implementations are now
  described by one specification instead of two independently-evolved
  ones, closing the documentation gap ADR-0007's Update section left
  open (inventory-service's Outbox never had its own ADR).
- Both publishers are now equally observable: a failure to publish is
  structured, correlated, and logged the same way in both languages, and
  the Grafana dashboards gain a real signal (backlog draining, publish
  lag) where there was none before.
- Future adoption of Outbox in another service has an explicit,
  falsifiable criterion to apply, instead of each future decision
  re-deriving one from scratch (or worse, copying whichever publish felt
  most urgent at the time).

**Negative / residual, not fixed here**:
- The poison-pill and unbounded-batch gaps identified above remain open,
  by deliberate choice — see the objective criteria for when they'd earn
  fixing.
- `billing-service` still has no Outbox for any of its publishes
  (`payment.approved`/`payment.failed`/`payment.refunded`/
  `payment.refund.failed`) — this ADR's adoption criterion applies to all
  four, per the architectural analysis that produced this ADR, but
  extending the pattern there is separate, still-forthcoming work.
- The poller still has no distributed-locking story; unchanged limitation
  from ADR-0003, still acceptable at single-instance scale.

## Update — 2026-07-15: extended to all four of orders-service's publishes

The adoption criterion above was applied to every publish `orders-service`
makes — `order.created`, `stock.reserve`, `order.status-changed`, and
`payment.refund.requested` — and all four qualified: none is purely
informative, and losing or failing to persist any of them would leave a
cross-service business process stalled, orphaned, or desynchronized, per
[ADR-0034](0034-payment-compensation-saga.md)'s own Update. `orders-service`
now has an `outbox_events` table, `OutboxEvent`/`OutboxEventRepository`,
and an `OutboxPublisher` identical in shape to auth-service's and
inventory-service's (same schema, same envelope, same 5s poll, same
metrics/logging from the start rather than as a later harmonization
pass) — see `orders-service/.../service/OutboxPublisher.java`.
`OrderService` no longer holds a `RabbitTemplate` at all: every one of its
four publishes now goes through a single `writeOutboxEvent` helper that
serializes the event with the same `ObjectMapper` the RabbitMQ message
converter uses, so the stored payload text is byte-for-byte what a direct
`convertAndSend` would have put on the wire. This closes the README
Roadmap item this ADR previously left open for `orders-service`;
`billing-service` remains open, per the residual bullet above.

## References

- [ADR-0003](0003-outbox-pattern-auth-service.md) — the original
  implementation this ADR formalizes into a shared specification.
- [ADR-0007](0007-remove-kafka-broker.md) — its 2026-07-06 Update is
  where inventory-service's independent Outbox implementation was first
  recorded; this ADR is the dedicated specification that implementation
  never had.
- [ADR-0019](0019-message-consumption-resilience.md) — the different,
  complementary failure class (delivered-but-failed-to-process) its
  retry/DLQ protects against; cited above only to distinguish it from
  what Outbox protects against, not reused as a mechanism here.
- [ADR-0034](0034-payment-compensation-saga.md) — where the
  `orders-service` Outbox gap was first tracked as an open Roadmap item,
  still open after this ADR.
- [ADR-0036](0036-metrics-via-prometheus-grafana.md) — the business-metric
  convention (constructor-injected `MeterRegistry` in Java, package-level
  `promauto` vars in Go) this ADR's two new metrics follow.
- [architectural-principles.md](../architecture/architectural-principles.md)
  — principle 11 ("reduce cognitive load without losing architectural
  capability") is the direct driver for consolidating two
  independently-evolved implementations into one specification.
