# ADR-0003: Outbox pattern for auth-service's verifyEmail

## Status

Accepted - 2026-07-04

## Context

The baseline audit (2026-07-03) catalogued auth-service's `UserService.verifyEmail` as a specific, higher-risk instance of this repository's broader lack of an outbox pattern: it published `user.verified` over RabbitMQ *before* saving the activated user. Confirmed again by reading the method fresh for this ADR (not assumed from the prior catalogue): the publish call sat directly above `userRepository.save(user)`, both inside the same `@Transactional`-annotated method. If the save failed after the publish had already gone out — a DB constraint violation, a connection drop, anything — a downstream consumer (orders-service, products-service) would activate a buyer/seller record for a user whose own activation was never actually persisted.

This ADR covers only that one call site. `inventory-service` (Go) has a related but structurally different risk — it publishes to Kafka *after* its own Postgres commit, in a separate function, with a documented duplicate-reservation exposure on retry — catalogued as the same category of debt but requiring its own implementation in a future stage, not this one.

The chosen fix is a transactional outbox via simple polling: an `outbox_events` table written in the same transaction as the business change, plus a scheduled job that reads and publishes. No CDC/Debezium — that infrastructure piece was explicitly ruled out for this project.

## Decision

- **`outbox_events` table** (`auth-service/src/main/resources/db/migration/V2__Create_outbox_events_table.sql`): `exchange`, `routing_key`, `payload` (raw JSON text), `created_at`, `published_at` (`NULL` until published). A partial index on `created_at` where `published_at IS NULL` keeps the poll query cheap as the table grows.
- **Same-transaction write**: `UserService` is `@Transactional` at the class level. `verifyEmail` now calls `userRepository.save(user)` and then `outboxEventRepository.save(new OutboxEvent(...))` in the same method — one transaction, one commit. If the save throws, the method never reaches the outbox insert, and the whole transaction rolls back: no row, no event, ever.
- **`OutboxPublisher`** (`auth-service/src/main/java/com/easydora/authservice/service/OutboxPublisher.java`), `@Scheduled(fixedDelay = 5000)`: polls `outbox_events` for unpublished rows ordered by `created_at`, publishes each via `RabbitTemplate.send(exchange, routingKey, message)` using the row's raw payload bytes with `Content-Type: application/json`, and marks the row published only after `send` returns without throwing. A failed send is logged and the row is left untouched for the next poll — at-least-once, never silently dropped.
  - **Interval — 5 seconds, and why**: auth-service is low-traffic in this project (a portfolio exercise, not production load), so a 5s poll adds no meaningful Postgres contention; it keeps end-to-end latency for email verification low enough that a user checking their inbox and clicking "activated" a few seconds later never notices the extra hop. There's no SLA driving a tighter number, so this is a deliberately unremarkable middle value, not a tuned one.
- **Wire format preserved**: the payload stored is just `String.valueOf(user.getId())` (e.g. `"888"`) — the same raw numeric body `RabbitTemplate.convertAndSend(exchange, key, userId)` produced before, for a `Long`. This matters because both existing consumers (orders-service and products-service's `handleUserVerified(Long userId)` `@RabbitListener` methods) expect exactly that shape. No consumer-side change was needed or made.
- **Direct publish call removed**: `verifyEmail` no longer calls `rabbitMQProducerService.sendUserVerifiedEvent(...)`. That method is deleted from `RabbitMQProducerService` — it had no other caller once removed, so keeping it would have been dead code introduced by this very change, not a separate pre-existing case.
- **`registerUser` untouched, deliberately**: its existing publish already happens *after* `userRepository.save(user)` succeeds — the correct order. This change was scoped to the one confirmed-backwards call site; `registerUser`'s save-then-publish-directly pattern still carries the project's already-catalogued residual risk (a publish failure after a successful save silently loses the event, since nothing retries it), but converting it to the outbox is a separate future decision, not bundled into this one.
- **`@EnableScheduling`** added to `AuthServiceApplication` — the only scheduling in this service so far.

## Testing (Red-Green, real RabbitMQ)

- **`VerifyEmailOutboxIT`** (originally `VerifyEmailOutboxIntegrationTest`, renamed under ADR-0008's Surefire/Failsafe split) — Red: run against the pre-fix code with a mocked `UserRepository` whose `save()` throws, publish path real (actual `RabbitMQProducerService`/`RabbitTemplate` against docker-compose RabbitMQ). Failed for the expected reason: the test asserted no message should arrive on a dedicated test queue, and one did — proving the bug. Green after the outbox change: same test, same assertion, now passes — nothing is published when save fails, because no outbox row was ever committed.
- **`VerifyEmailOutboxHappyPathIT`** (originally `VerifyEmailOutboxHappyPathIntegrationTest`) — save succeeds; captures the `OutboxEvent` `UserService` hands to (a mocked) `OutboxEventRepository`; feeds that same event to a real `OutboxPublisher` wired to the real `RabbitTemplate`; confirms the message lands on a real consumer-side queue with the same payload (`"888"`) a direct publish would have produced. Proves the event still reaches its consumer, just one hop later than before.
- **`OutboxPublisherRetryTest`** — pure unit test (both `OutboxEventRepository` and `RabbitTemplate` mocked): a `send` that throws leaves the row's `publishedAt` `null` and the repository's `save` is never called for it; the same row is retried on the next `publishPendingEvents()` call and, once the mocked broker stops throwing, gets marked published. Proves a broker outage at poll time delays delivery rather than losing it.

## Residual debt found during this task, explicitly not fixed here

Investigating Passo 1 (confirming `verifyEmail`'s exact operation order) surfaced a materially bigger, pre-existing issue, unrelated to the outbox change itself, reported to the user rather than fixed:

**Flyway's migrations and Hibernate's auto-DDL are writing to two different schemas, silently.** `application-dev.properties` sets `hibernate.default_schema=auth_schema` (used by JPA at query time) but also `spring.jpa.hibernate.ddl-auto=update` (Hibernate auto-creates/updates tables from entity mappings) with Flyway enabled and no `spring.flyway.schemas` configured. Booting the service and querying Postgres directly confirmed: `V1__Create_users_table.sql` and this ADR's `V2__Create_outbox_events_table.sql` both log `Migrating schema "public"` and create their tables there, while Hibernate's `ddl-auto=update` independently auto-creates a second, identically-named copy of each table in `auth_schema` — and it's that second copy the running application actually reads and writes (confirmed via the Hibernate-generated SQL logs querying `auth_schema.outbox_events`). Practical consequence: `V1`'s seeded admin user (`admin@easydora.com`) lives in `public.users`, invisible to the running app, which only ever sees `auth_schema.users`. This ADR's `V2` migration deliberately mirrors `V1`'s existing (flawed) convention — an unqualified table name — rather than fixing schema targeting as a side effect of an unrelated task; the outbox pattern still works correctly at runtime because Hibernate resolves both `User` and `OutboxEvent` to the same actual schema (`auth_schema`) inside the same transaction, so this doesn't undermine the fix above.

**Resolved the same day, in [ADR-0004](0004-auth-service-schema-authority-fix.md)**: a follow-up migration (`V3`) recreates both tables directly in `auth_schema` (matching Hibernate's own generated column types exactly, verified against the live database) and drops every dead copy in both schemas; `ddl-auto` is switched to `validate`, so Hibernate can no longer silently create schema going forward.

## Consequences

**Positive**: `verifyEmail` can no longer publish an event for a state change that didn't persist — the failure mode this ADR targets is closed and regression-tested. The event still reaches consumers on the happy path, and survives a broker outage at poll time without being lost. The pattern (`outbox_events` table + `OutboxPublisher`) is reusable if a future stage decides to move `registerUser` or other auth-service events onto the same mechanism.

**Negative / residual**:
- `registerUser`'s save-then-direct-publish path keeps its already-catalogued exposure (a publish failure after a successful save silently drops the event) — not addressed here, by explicit scope decision.
- `inventory-service`'s publish-after-commit risk (Go, Kafka) remains open, needing a separate, language-appropriate implementation.
- The public/auth_schema table duplication described above is a newly surfaced, unrelated architectural problem that this ADR's own migration inherited — resolved the same day in ADR-0004.
- The poller is a single `@Scheduled` method with no distributed locking; this is fine for auth-service's current single-instance deployment, but would need a leader-election or `SELECT ... FOR UPDATE SKIP LOCKED` style guard before this service could run more than one replica.

## Update — 2026-07-14: formalized into a shared specification

This ADR's design (table shape, envelope, 5-second poll, at-least-once
semantics) is now the canonical Outbox Pattern specification for this
project, not just this one call site's implementation — see
[ADR-0037](0037-consolidated-outbox-pattern-specification.md), which also
documents inventory-service's independently-built Go implementation
(previously only recorded as an aside inside ADR-0007) as the same
pattern. `OutboxPublisher.java` itself was updated as part of that work:
it now logs a structured success line (previously silent) and populates
MDC with the row's correlationId/messageId before logging, and gained two
new metrics (`outbox_events_published_total`,
`outbox_publish_lag_seconds`). No change to this ADR's actual Decision —
the table shape, envelope, and poll cadence described above are unchanged
and are exactly what ADR-0037 formalizes.

## Update — 2026-08-02: registerUser moved to the Outbox too

The residual risk this ADR explicitly left open in `registerUser`'s
save-then-direct-publish path — a publish failure after a successful save
silently drops the event — is now closed. `registerUser` builds the same
`UserRegisteredEvent` it always did, serializes it to JSON, and writes it
as an `OutboxEvent` row (`auth.exchange`, `user.registered`) in the same
transaction as `userRepository.save`, using the exact same
`OutboxEnvelopeCodec`/`OutboxPublisher` machinery `verifyEmail` already
used. Unlike `verifyEmail`'s envelope (a bare numeric user ID, preserving
the pre-existing wire shape two consumers already expected), this one
carries the full JSON event body, so `UserService` now takes a
Jackson `ObjectMapper` as a constructor dependency, configured with
`JavaTimeModule` and `WRITE_DATES_AS_TIMESTAMPS` disabled — the same
configuration `RabbitMQConfig`'s own message converter and the
`UserRegisteredEventContractTest` already used, so the JSON shape on the
outbox path is identical to what a live `convertAndSend` would have
produced.

`RabbitMQProducerService.sendUserRegisteredEvent` had no other caller once
`registerUser` stopped using it, so it was deleted as dead code — the same
call this ADR already made for `sendUserVerifiedEvent`. `UserService`'s
`RabbitMQProducerService` dependency itself is now gone too, since nothing
in the class used it anymore.

Proven by `RegisterUserOutboxBehaviorTest`
(`auth-service/src/test/java/com/easydora/authservice/service/`), mirroring
`VerifyEmailOutboxBehaviorTest`'s shape: one test proves no outbox row is
written when the save fails, the other captures the written row and
round-trips its JSON body back into a `UserRegisteredEvent` to prove the
wire shape survived the envelope.

While investigating this call site, an unrelated, already-dead piece of
code was found and removed: `UserRegisteredEventListener`, a Spring
`@EventListener` bean that was never actually wired to fire — nothing in
the codebase ever called `ApplicationEventPublisher.publishEvent` with a
`UserRegisteredEvent`, in this version of the class or any prior one this
task touched. Its removal is unrelated to the outbox change itself.

## References

- Baseline audit (2026-07-03 entry in this repo's history) — original catalogue of the outbox-pattern debt, including this exact `verifyEmail` ordering and the separate `inventory-service` risk.
- ADR-0001 (messaging wiring audit), ADR-0002 (JSON Schema contract testing) — prior stages in this same debt-remediation sequence.
- [ADR-0037](0037-consolidated-outbox-pattern-specification.md) — the
  consolidated specification this implementation is now formalized into.
