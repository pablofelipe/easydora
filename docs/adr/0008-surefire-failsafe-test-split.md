# ADR-0008: Separate unit and integration tests via Surefire/Failsafe

## Status

Accepted - 2026-07-04

## Context

`mvn test` in all four Spring services (auth, products, orders, billing) ran every test class indiscriminately — pure unit tests alongside classes that open a real connection to Postgres or RabbitMQ (docker-compose). This meant `mvn test` silently required live infrastructure to fully pass, with no way to run a fast, hermetic unit-only pass, and no structural signal in the codebase distinguishing the two categories beyond reading each file.

Four classes across the four services actually touch real infrastructure, found by grepping for `CachingConnectionFactory`/`@SpringBootTest`/real broker or DB connections rather than assumed from naming: `VerifyEmailOutboxIT` and `VerifyEmailOutboxHappyPathIT` (auth-service, both connect to a real RabbitMQ), `JwtCreatedFanoutIT` (orders-service, same), and `BillingServiceApplicationIT` (billing-service, a real `@SpringBootTest` booting a full context against Postgres and RabbitMQ). Every other test class in all four services (contract tests validating a DTO against a JSON Schema, Mockito-based unit tests) touches no live infrastructure at all.

## Decision

Adopt the standard Maven convention: **Surefire runs unit tests during `mvn test`; Failsafe runs integration tests during `mvn verify`.**

- The four infrastructure-touching classes were renamed from `*IntegrationTest`/`*Tests` to the `*IT` suffix (`VerifyEmailOutboxIT`, `VerifyEmailOutboxHappyPathIT`, `JwtCreatedFanoutIT`, `BillingServiceApplicationIT`). No behavior change — only the file and class name.
- No Surefire configuration was added or changed. Surefire's own default include patterns (`**/Test.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`) already exclude anything ending in `*IT.java`, so the rename alone was enough to remove these four from `mvn test`.
- `maven-failsafe-plugin` was added to all four services' `pom.xml` (not just the ones with an `*IT` class today — products-service has none yet, but gets the same plugin for consistency, so a future integration test there needs no build-file change to be picked up), bound to the `integration-test`/`verify` goals with no explicit version — `spring-boot-starter-parent` (3.2.0) manages a matching version (3.1.2, same as the already-inherited Surefire) via its own dependency management, confirmed by `mvn verify` resolving and running correctly with no version specified.
- No parent POM was introduced to share this configuration across the four services. They remain four independent Maven projects (each with its own `<parent>spring-boot-starter-parent</parent>`), consistent with how this repository has always structured them; the `<plugin>` block is duplicated four times rather than factored into a shared parent, which would be a materially larger, separate restructuring decision.

## Verification

Ran all four services both ways, confirming the split actually changes what runs:

| Service | `mvn test` (before → after) | `mvn verify` |
|---|---|---|
| auth-service | 4 → 2 (the 2 `*IT` moved out) | 4 (2 unit + 2 IT) |
| products-service | 1 → 1 (no `*IT` class exists) | 1 |
| orders-service | 5 → 4 (`JwtCreatedFanoutIT` moved out) | 5 (4 unit + 1 IT) |
| billing-service | 2 → 1 (`BillingServiceApplicationIT` moved out) | 2 (1 unit + 1 IT) |

All `mvn verify` runs pass with real Postgres/RabbitMQ (docker-compose) and `RABBITMQ_PASSWORD`/`DB_PASSWORD` sourced from `.env` — the same credentials ADR-0005 rotated.

One real flake surfaced and diagnosed during this verification, not a defect in this change: `JwtCreatedFanoutIT` failed once because the real `orders-service` Docker container (left running from ADR-0006's live verification) was actively consuming from the same `orders.jwt.created.queue` the test itself expects to drain — whichever consumer RabbitMQ round-robins the message to wins. Stopping that container made the test pass immediately. This test uses the actual production queue name deliberately (to prove the real topology works, per ADR-0001's incident), so it will always be sensitive to a live competing consumer on the same broker — worth knowing when running `mvn verify` locally alongside a running docker-compose stack, not something this ADR changes.

## Consequences

**Positive**: `mvn test` is now fast and hermetic across all four services — no live Postgres/RabbitMQ required, and it can't silently fail from infrastructure being down. `mvn verify` is the one command that runs everything, matching normal Maven/CI expectations. The `*IT` suffix is now a reliable, greppable signal for "this test needs real infrastructure," which the pre-existing `*IntegrationTest` naming was not consistently applied.
- Not fixed here: at the time of this ADR, this repository had no CI configured anywhere (per the README status line then), so this split's main near-term benefit was local developer ergonomics, not an automated gate — see the 2026-07-06 (2) update below for how CI Phase 2 later gave the `*IT` classes somewhere to actually run.

## Update — 2026-07-06

A later step of the Kafka-to-RabbitMQ migration ([ADR-0007](0007-remove-kafka-broker.md)) revisited three of the four `*IT` classes named above and replaced them with broker-agnostic behavior tests, validating the same outcomes without requiring live infrastructure to compile or pass:

- `VerifyEmailOutboxIT` and `VerifyEmailOutboxHappyPathIT` (auth-service) → replaced by `VerifyEmailOutboxBehaviorTest`, which mocks `RabbitMQProducerService`/`OutboxEventRepository` directly.
- `JwtCreatedFanoutIT` (orders-service) → replaced by `JwtCreatedFanoutBehaviorTest`, which calls `JwtConsumer`/`UserEventsConsumer` directly against mocked collaborators.

`BillingServiceApplicationIT` is the only class from the original four still exercising real infrastructure. `mvn verify` in auth-service, orders-service and products-service now runs zero `*IT` classes, so `maven-failsafe-plugin` has been removed from those three services' `pom.xml`; it remains only in billing-service, where it's still load-bearing.

This doesn't reverse the decision above — Surefire for unit tests, Failsafe for real-infrastructure integration tests is still the convention. It's a narrower scope reduction: this project currently has only one class in the second category, so only one service needs the plugin.

## Update — 2026-07-06 (2)

A later CI hardening pass ([ADR-0012](0012-ci-phase-2-real-infrastructure.md)) added
a CI Phase 2 with real Postgres/RabbitMQ service containers, which gave
`VerifyEmailOutboxIT`/`VerifyEmailOutboxHappyPathIT` and `JwtCreatedFanoutIT`
somewhere to actually run. Both classes were restored verbatim from the
commit that removed them, and `maven-failsafe-plugin` was added back to
auth-service's and orders-service's `pom.xml` — the paragraph above stating
it "remains only in billing-service" no longer reflects the current state.
This still doesn't reverse the Surefire/Failsafe convention itself, only the
narrower "one service currently needs the plugin" fact, which was always
scoped to "as of when this was written," not a permanent ceiling.
products-service is still the one service with neither a real `*IT` class
nor the plugin — see ADR-0012's Consequences section for why that gap
remains open.

## References

- ADR-0003 (outbox pattern for auth-service) and ADR-0005 (secret rotation) — introduced two of the four `*IT` classes renamed here.
- ADR-0001 (messaging wiring audit) — the JWT/UserEvents competing-consumer incident that `JwtCreatedFanoutIT` (formerly `JwtCreatedFanoutIntegrationTest`) exists to regression-test, and the same reason it's sensitive to a live competing consumer.
- ADR-0006 (gateway circuit breaker) — the live container verification that was still running when the `JwtCreatedFanoutIT` flake above was diagnosed.
