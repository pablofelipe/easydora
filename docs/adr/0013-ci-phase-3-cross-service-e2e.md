# ADR-0013: CI Phase 3 — cross-service end-to-end tests via real running processes

## Status

Accepted - 2026-07-07

## Context

CI Phase 2 (ADR-0012) validates real consumers against a real Postgres/RabbitMQ
pair, but every job in that phase runs exactly one service, with its own
isolated pair of containers. This was deliberate (parallelism, fault
isolation), but it has a consequence worth stating explicitly: no two
services ever share a broker/database instance in CI, so nothing in Phase 2
can prove that a producing service's real code and a consuming service's real
code actually agree on the wire, end to end. Every Phase 2 wiring test hand-
builds the incoming event (a raw JSON string, or the consuming service's own
duplicated copy of the event class) rather than obtaining it from the real
producer, because the real producer lives in a different Maven/Go module,
built and tested in a different, container-isolated job.

Auditing whether "real producer" tests could simply be added to each
producing service's own Phase 2 job (still one service per job) surfaced a
second, sharper constraint: `orders-service` and `products-service` both
guard their non-trivial endpoints with `anyRequest().authenticated()`, backed
by each service's own `JwtConsumer`/`JwtAuthenticationFilter` pair, which
caches the raw token string broadcast by `auth-service`'s `JwtCreatedEvent`
in an in-memory map and never re-verifies its signature locally. There is no
way to obtain a token that cache will accept other than `auth-service`
actually publishing a real `JwtCreatedEvent` that the target service actually
consumes. So a producer test for, say, `orders-service`'s `POST /createOrder`
cannot be self-contained within `orders-service`'s own job at all — it needs
`auth-service` running for real in the same job, sharing the same broker.

## Decision

Add a Phase 3 to `.github/workflows/ci.yml`: named jobs (not a matrix, since
each job starts a different, hand-picked set of services rather than
repeating one job shape) that build each participating service's real
artifact, start each as an actual running process (`java -jar`, or a built Go
binary) against one shared `postgres:15-alpine` + `rabbitmq:3-management-alpine`
pair, wait for every participant's real `/health` endpoint (bounded polling,
no fixed sleep), then drive the flow using a small dedicated test module
(`e2e-tests/`, plain JUnit 5 + `java.net.http.HttpClient` + Jackson, no Spring
dependency) that talks to each service's public HTTP API only. Every event
that actually crosses RabbitMQ in a Phase 3 test is produced by the real
production code of the service that owns it — no test ever hand-builds an
AMQP message body. Gated on Phase 2 passing first (`needs: [integration]`),
same fail-fast reasoning as ADR-0012.

Two groups cover the seven flows this initiative set out to validate:

- **`catalog-onboarding`** (`auth-service` + `products-service` +
  `inventory-service`) — implemented first. Covers Auth → Products, JWT
  Created → Products, Products → Inventory in one continuous run: real
  signup publishes a real `UserRegisteredEvent`; real login publishes a real
  `JwtCreatedEvent`; the resulting real token authenticates a real
  `POST /createProduct`, which publishes a real `ProductCreatedEvent` that
  `inventory-service` consumes.
- **`order-lifecycle`** (`auth-service` + `orders-service` +
  `inventory-service` + `billing-service`). Covers Auth → Orders, Order
  Created → Billing, and both Stock Reserved → Orders / Stock Insufficient →
  Orders in one run: real login creates/activates the real `Buyer`
  `orders-service`'s own authorization requires; one real `POST /createOrder`
  against an inventory row seeded with enough stock reaches
  `INVENTORY_RESERVED`, a second against one seeded without reaches
  `INVENTORY_FAILED`, and both publish a real `order.created` that
  `billing-service` turns into a `Payment`. Product creation itself is out of
  scope for this group (that producer is `catalog-onboarding`'s job) — the
  two inventory rows are seeded directly in Postgres, prerequisite state, not
  the flow under test, the same way Phase 2's own wiring tests seed state
  outside their focus.

### Why the intermediate Seller check reads Postgres directly

`CatalogOnboardingE2ETest` reads `products_schema.sellers` directly with a
plain JDBC query at exactly one point: right after signup, before login. At
that point in the flow, `products-service` has never consumed any JWT event
for this user, so its token cache is empty and no authenticated endpoint of
its own can be called to observe anything yet — the first authenticated call
in this test is deliberately the one that proves the JWT flow, further down.
A direct database read is also the only way to isolate `user.registered`'s
effect from `jwt.created`'s effect at all:
`UserEventConsumer.handleJwtCreated` creates a `Seller` row itself if none
exists yet, so checking for the row's existence only after login would not
prove `user.registered` specifically ran — it would pass even if that event
had been silently dropped. Every other assertion in the test goes through a
real public HTTP endpoint.

### Two real, unrelated bugs this surfaced

Booting `auth-service` as a bare process (no `docker-compose`, no local
`.env`) failed immediately: `jwt.secret`/`app.jwt.secret` both default to a
placeholder literal short enough (168 bits) to violate the HMAC-SHA minimum
key size (RFC 7518 §3.2 requires ≥ 256 bits), so `VerificationTokenService`
throws `WeakKeyException` during construction. This is a latent defect in
`application-dev.properties`'s fallback values, invisible until now because
every existing way of running `auth-service` (`docker-compose`, or a
developer's local `.env`) already overrides both properties with a strong
value. Phase 3 works around it by setting `JWT_SECRET`/`APP_JWT_SECRET` to
CI-only placeholder strings long enough to satisfy the key-size check (still
no repository secret needed, consistent with ADR-0012). The weak default
itself is not fixed here — flagged to the requester as out of this
initiative's scope, left for a separate, dedicated fix.

Second, and more serious: `order-lifecycle`'s Order Created → Billing
assertion calls `GET /api/payments/order/{orderId}` with HTTP Basic
credentials, and got a `403` regardless of whether the credentials were
absent, correct, or wrong. `billing-service`'s `SecurityConfig` built a
custom `SecurityFilterChain` bean that declared `anyRequest().authenticated()`
but never called `.httpBasic(...)` — building a custom filter chain opts out
of Spring Boot's automatic HTTP Basic setup entirely, so no authentication
mechanism was wired up at all. Every protected endpoint had been unreachable
by design since `SecurityConfig` was introduced; ADR-0010's own live
verification had recorded "`curl .../api/payments/1` returns 403
unauthenticated" as the expected, correct behavior, without anyone trying the
same call *with* valid credentials to notice they'd 403 identically. Flagged
to the requester and fixed in its own commit: `.httpBasic(Customizer.withDefaults())`
added to the filter chain, confirmed with `curl -u` that correct credentials
now return a different status (`404`, the payment genuinely doesn't exist
yet) than wrong ones (`401`).

## Verification

Ran both groups locally against a throwaway, isolated `postgres:15-alpine` +
`rabbitmq:3-management-alpine` pair (standard ports, confirmed free first)
before trusting the CI change, each service packaged/built the same way its
CI job does (`mvn -DskipTests package` for Java, `go build` for
`inventory-service`), started as real processes, waited on every
participant's real `/health` endpoint:

- `catalog-onboarding`: `CatalogOnboardingE2ETest` run three consecutive
  times — all green, no flakes.
- `order-lifecycle`: `OrderLifecycleE2ETest` run three consecutive times
  after the `billing-service` fix below — all green, no flakes.
- Both test classes run together in the same `e2e-tests` module (five
  services up at once: auth/orders/billing/inventory/products) to confirm
  they don't interfere with each other — green.
- `mvn test` re-run in `billing-service` after its `SecurityConfig` change,
  confirming no regression in `HealthControllerTest` or the rest of Phase 1's
  coverage.

One real defect surfaced during this run, not a flake: the initial version
of `CatalogOnboardingE2ETest` polled only on HTTP status code while waiting
for the post-login Seller-activation assertion, but
`UserEventConsumer.handleJwtCreated` caches the token (in-memory, instant) a
statement or two before it saves the Seller's `active = true` (a database
write) — a real, brief window where authentication already succeeds but the
field hasn't flipped yet. Fixed by polling on the actual field value, not
just the status code — not a sleep or a retry added to mask instability, the
same bounded-condition-with-timeout idiom Phase 2's own wiring tests already
use.

## Consequences

**Positive**: `catalog-onboarding` is the first test anywhere in this
repository that proves a producer's real serialization and a consumer's real
deserialization agree, across a real process boundary, driven by public APIs
only. It would have caught a real field-name or type drift between
`products-service`'s `ProductCreatedEvent` and `inventory-service`'s Go
`ProductCreatedEvent` struct — something no existing test (Phase 1 unit tests
mock the broker; Phase 2 wiring tests hand-build the event on the consumer
side) is positioned to catch.

**Not fixed here / known limitations**:
- The weak default `jwt.secret`/`app.jwt.secret` fallback in
  `auth-service`'s `application-dev.properties` remains unfixed — every
  existing deployment path happens to override it, so this has never
  surfaced before Phase 3 tried to run the service without `docker-compose`.
- `billing-service`'s `/api/payments/**` now authenticates correctly via
  Basic Auth (fixed above), but still uses Spring Boot's single fixed user
  rather than the cross-service JWT broadcast every other authenticated
  service uses — that's ADR-0010's residual gap, unrelated to this ADR, and
  still open.
- Each Phase 3 job adds real JVM/binary boot time on top of Phase 2's
  container-startup cost (roughly another 10-20s per participating process)
  — acceptable for two small groups, worth watching if more groups are
  added later.
- `products-service`'s and `orders-service`'s own real read endpoints
  (`/sellers/**`, `/{orderId}`) still require an authenticated request for
  every check except the one deliberately exempted in
  `catalog-onboarding` (see above) — this is intentional, not a workaround,
  since it's exactly the real security behavior being validated.
- No group exercises `auth-service`'s `user.registered`/`user.verified`
  consumption by `orders-service` as a separately-attributable checkpoint the
  way `catalog-onboarding` does for `products-service` (see "Why the
  intermediate Seller check reads Postgres directly" above) — `Buyer`
  creation/activation is proven only as a precondition of order placement
  succeeding at all, not isolated hop by hop. Acceptable here since
  `order-lifecycle` only has one combined "Auth → Orders" flow to prove, not
  two separate ones the way seller onboarding does.

## Update — 2026-07-07

The Basic-Auth gap named above (`billing-service`'s `/api/payments/**`
using Spring Boot's single fixed user instead of the cross-service JWT
broadcast) was closed the same day by
[ADR-0015](0015-billing-service-jwt-and-auth-securityconfig-fix.md):
`billing-service` now authenticates via the same Bearer JWT broadcast
cache as `products-service`/`orders-service`, fully replacing Basic Auth.
`OrderLifecycleE2ETest`'s Basic Auth call was updated to a Bearer token
accordingly.

## References

- [ADR-0012](0012-ci-phase-2-real-infrastructure.md) — Phase 2, whose
  consumer-only, hand-built-message tests this phase complements rather than
  replaces (Phase 2 keeps running; nothing in it was removed).
- [ADR-0010](0010-uniform-service-healthchecks.md) — the `/health` endpoints
  Phase 3's readiness polling depends on.
- [ADR-0015](0015-billing-service-jwt-and-auth-securityconfig-fix.md) —
  closes the Basic-Auth gap this ADR found and referenced above.
- [ADR-0007](0007-remove-kafka-broker.md) — the RabbitMQ-only messaging model
  every Phase 3 flow runs against.
