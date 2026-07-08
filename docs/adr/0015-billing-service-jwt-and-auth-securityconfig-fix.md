# ADR-0015: billing-service joins the JWT broadcast pattern; auth-service's latent `.httpBasic()` gap fixed

## Status

Accepted - 2026-07-07

## Context

Two related debts, both in the same "missing or incomplete authentication mechanism" family already documented in this project:

1. **auth-service's `SecurityConfig`** builds a custom `SecurityFilterChain` with `anyRequest().authenticated()` but never calls `.httpBasic(...)` — the exact defect class ADR-0013 found and fixed in billing-service. Confirmed by inspection: every one of auth-service's six endpoints (`/ping`, `/health`, `/signup`, `/login`, `/verify-email`, `/users/{id}/notification-profile`) is already `permitAll()`-ed, so the bug is latent, not active — nothing currently falls through to the broken fallback.
2. **billing-service's `/api/payments/**`** has been protected only by Spring Boot's auto-generated single-user HTTP Basic auth since ADR-0013's fix — the same ADR flagged this as a residual gap: unlike its three sibling services (auth/products/orders), billing-service never joined the project's cross-service JWT-broadcast cache (`JwtConsumer` + `JwtAuthenticationFilter`, fed by `auth-service`'s `JwtCreatedEvent` over `auth.exchange`/`jwt.created`).

## Decision

### auth-service: the same one-line fix as ADR-0013

Added `.httpBasic(Customizer.withDefaults())` to `SecurityConfig`'s filter chain, with the identical explanatory comment ADR-0013 used for billing-service. No new test: there is still no protected endpoint in auth-service to meaningfully exercise it against, and ADR-0013 itself fixed the same defect in billing-service with zero test changes, relying on live verification instead. `mvn test` re-confirmed no regression (6/6 tests).

### billing-service: full replacement of Basic Auth with the JWT broadcast pattern

Ported from **products-service** (not orders-service): products-service's `JwtUserInfo` has no `active` flag and its `JwtConsumer` has no side effect beyond caching the token, which matches billing-service exactly — billing-service has no Buyer/Seller-style local entity to activate, unlike orders-service's version.

New:
- `billing-service/.../event/JwtEvent.java` — `token`, `userId`, `email`, `firstName`, `lastName`, `role`.
- `billing-service/.../config/JwtAuthenticationFilter.java` — in-memory `ConcurrentHashMap<String, JwtUserInfo>` cache, same `doFilterInternal` behavior as products-service's filter (missing header → request proceeds unauthenticated; unrecognized Bearer token → immediate `401`).
- `billing-service/.../consumer/JwtConsumer.java` — `@RabbitListener` on a new `billing.jwt.created.queue`, bound to the existing `auth.exchange`/`jwt.created` (no changes to auth-service's producer side).

Modified:
- `RabbitMQConfig` — added the `auth.exchange`/`jwt.created`/`billing.jwt.created.queue` constants and beans, alongside the existing `order.exchange` wiring. The existing Jackson `MessageConverter` (already configured with `INFERRED` type precedence) needed no changes to deserialize `JwtEvent`.
- `SecurityConfig` — removed `.httpBasic(Customizer.withDefaults())` entirely; added `STATELESS` session management, `.formLogin(...).disable()`, `.httpBasic(...).disable()`, and `.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)` — the same structure as products-service's `SecurityConfig`. `/ping`, `/health`, `/error` remain `permitAll()`.

This was a deliberate, confirmed decision (not a default): billing-service's Basic Auth is **fully replaced**, not kept alongside JWT, for parity with the other three services — accepting that `OrderLifecycleE2ETest` and the CI `order-lifecycle` job needed matching updates (see Consequences).

### New test coverage this task added that ADR-0013 never had

ADR-0013's original billing-service Basic Auth fix was verified only live, via `curl -u`. This task adds real regression coverage:
- `JwtConsumerBehaviorTest` (pure Mockito) — the token-caching behavior, and that an event with no token is ignored.
- `PaymentControllerSecurityTest` (`@WebMvcTest` + real `SecurityConfig`/`JwtAuthenticationFilter`) — empirically confirms the exact status codes: **no `Authorization` header → `403`** (Spring Security's default `AccessDeniedHandler`, since no login mechanism is configured to fall back to) and **an unrecognized Bearer token → `401`** (`JwtAuthenticationFilter` short-circuits explicitly). A valid, pre-cached token succeeds (`200`).
- `BillingJwtCreatedWiringIT` (real RabbitMQ, real running Spring context) — publishes a real `JwtEvent` onto `auth.exchange`/`jwt.created` and confirms the token becomes usable. Deliberately uses the **same** `@SpringBootTest` configuration (no `webEnvironment` override) as its sibling `*IT` classes rather than `RANDOM_PORT`: an earlier version of this test used `RANDOM_PORT`, which started a second, independent Spring context/bean set alongside the one `BillingServiceApplicationIT`/`OrderCreatedWiringIT` share — two separate `RabbitListenerContainer`s then became competing consumers on the same queue name, the exact "shared queue → dropped message" bug class ADR-0001 documents, causing the test to occasionally observe a message delivered to the *other* context's listener instead of its own. A shared context config makes there only ever be one listener for this queue during the whole `mvn verify` run.

### Cross-service fallout

- `e2e-tests/.../OrderLifecycleE2ETest.java` — the Order-Created-to-Billing assertion (`GET /api/payments/order/{orderId}`) now sends `bearer(token)` — the same JWT already obtained from login earlier in the test — instead of `basicAuth(BILLING_USER, BILLING_PASSWORD)`. The now-unused `BILLING_USER`/`BILLING_PASSWORD` constants and `E2ETestSupport.basicAuth(...)` helper (no longer called anywhere) were removed rather than left dead.
- `.github/workflows/ci.yml` (`order-lifecycle` Phase 3 job) — removed the `SPRING_SECURITY_USER_NAME`/`SPRING_SECURITY_USER_PASSWORD` env vars and their explanatory comment on billing-service's start step; dead once Basic Auth is gone.

## Verification

- `mvn test` in auth-service: 6/6, no regression.
- `mvn verify` in billing-service, three consecutive runs: 10 unit tests + 3 `*IT` tests, all green, no flakes.
- `docker compose build` + `up -d`: all 7 implemented services + Postgres/RabbitMQ `healthy` simultaneously with the rebuilt billing-service image.
- `mvn test -Dtest=OrderLifecycleE2ETest` against that live stack: green — billing-service's payment lookup now succeeds via the shared JWT bearer token.
- `mvn test -Dtest=CatalogOnboardingE2ETest` re-run against the same stack to confirm no regression in the unrelated e2e group.

## Consequences

**Positive**: billing-service now authenticates identically to auth-service/products-service/orders-service — one less special case in the codebase, and real regression tests exist for a security mechanism that previously had none. auth-service's `SecurityConfig` is no longer a landmine waiting for its first protected endpoint.

**Not fixed here / known limitations**:
- No retry/backoff/DLQ on the new `billing.jwt.created.queue` consumer — same accepted gap as every other RabbitMQ consumer in this project.
- A service restart still wipes billing-service's token cache in-memory, exactly like every other service using this pattern — previously-issued tokens become invalid until the next login or JWT rebroadcast. This is a pre-existing, project-wide characteristic of the broadcast-cache design (see `CLAUDE.md`'s "Cross-service auth" section), not something introduced or worsened here.

## References

- [ADR-0013](0013-ci-phase-3-cross-service-e2e.md) — found and fixed the same defect class in billing-service's `SecurityConfig` (missing `.httpBasic()`), and explicitly flagged billing-service's Basic-Auth-only gap as unresolved.
- [ADR-0001](0001-messaging-wiring-audit.md) — the shared-queue/competing-consumer bug class this task's own IT test had to design around.
- [ADR-0014](0014-notification-service.md) — found the same latent `.httpBasic()` defect in auth-service while adding an unrelated endpoint; this ADR is where it's actually fixed.
