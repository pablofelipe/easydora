# ADR-0010: Uniform health checks across all six services

## Status

Accepted - 2026-07-05

## Context

ADR-0009 fixed billing-service's and orders-service's Dockerfiles, which
both hard-coded `HEALTHCHECK`/`EXPOSE` against port 8082 (products-service's
port, a copy-paste artifact) instead of their own real ports. Fixing the
port surfaced two deeper, unrelated problems, tracked in the README Roadmap
and now resolved together here:

1. **Wrong health path, not just wrong port.** `auth-service`,
   `orders-service`, and `products-service` each already expose their own
   lightweight `@GetMapping("/health")` (and `/ping`) endpoint, explicitly
   `permitAll()`-ed in their `SecurityConfig` — a simple liveness check, not
   Spring Boot Actuator. Only `orders-service` even has
   `spring-boot-starter-actuator` on its classpath at all; `products-service`
   and `billing-service` don't. Yet every Spring service's Dockerfile
   `HEALTHCHECK` pointed at `/actuator/health` — a path that either doesn't
   exist (products/billing, no actuator dependency) or exists but isn't
   `permitAll()`-ed (orders). Either way, Spring Security's authorization
   filter rejects the unauthenticated request before routing can even
   matter, so the check always failed with `401`/`403`, never
   connection-refused, once the port itself was correct. Verified live for
   `products-service`: after confirming its port was already correct (8082
   is genuinely its own port), it still ended up "unhealthy" — evidence
   requested before treating it as a template, and it disproved the
   assumption that it was already working correctly.
2. **`orders-service`'s `docker-compose.yml` healthcheck override was a
   no-op.** Documented in ADR-0009's addendum: a YAML folded block (`test:
   >`) turned an inline `#` comment into one that swallowed the real `curl`
   command, so the check always exited `0` regardless of the app's actual
   state. `products-service` has no such override — it relies purely on its
   Dockerfile's own `HEALTHCHECK`, which Compose honors automatically.
3. **`billing-service` had no `/health`/`/ping` endpoint and no
   `SecurityConfig` at all.** Everything in billing-service required
   authentication via Spring Boot's auto-configured default (a single
   generated in-memory user, random password logged at startup) — so even
   once the Dockerfile pointed at the right port, there was no unauthenticated
   endpoint for it to reach.
4. **`auth-service`, `inventory-service`, and `api-gateway` had no
   `HEALTHCHECK` at all** — a separate, previously-reported gap, not the
   wrong-port/wrong-path bug, but blocking the same underlying goal (every
   service reporting an accurate health status to Compose).

## Decision

Apply one consistent pattern across all six services:

- **Every service's Docker healthcheck targets its own lightweight,
  unauthenticated `/health` endpoint** (not `/actuator/health`, which isn't
  uniformly available or exposed): `auth-service`, `products-service`,
  `orders-service`, and `billing-service` at their respective Spring
  `@GetMapping("/health")`; `inventory-service` and `api-gateway` at their
  existing plain `net/http`/Gin `/health` handlers (already unauthenticated,
  no change needed there beyond adding the `HEALTHCHECK` instruction
  itself).
- **`billing-service` gets a `HealthController` (`/health`, `/ping`) and a
  `SecurityConfig`**, modeled on `orders-service`'s, permitting `/ping`,
  `/health`, `/error` and requiring authentication for everything else.
  Deliberately **not** copying `orders-service`'s/`products-service`'s
  `.httpBasic(disable)`/`.formLogin(disable)`/JWT-filter wiring: those
  services replace Spring's default authentication with their own
  `JwtAuthenticationFilter` fed by the cross-service JWT broadcast (each
  service's own `JwtConsumer` + in-memory token cache, populated from
  `auth-service`'s `JwtCreatedEvent` on `auth.exchange`); `billing-service` has never
  joined that broadcast (confirmed: no `JwtConsumer`/`JwtAuthenticationFilter`
  anywhere in the service). Building that integration is a separate, much
  larger task — out of scope here. This `SecurityConfig` only carves out
  `/health`/`/ping`/`/error` as public; `/api/payments/**` keeps whatever
  authentication behavior it already had (Spring Boot's default generated-user
  Basic auth), unchanged.
- **`orders-service`'s broken `docker-compose.yml` healthcheck override is
  deleted outright**, not patched — bringing it in line with
  `products-service`'s and `billing-service`'s pattern (Dockerfile-level
  `HEALTHCHECK` only, no Compose-level duplicate to drift out of sync
  again).
- **`auth-service`, `inventory-service`, and `api-gateway` each get a
  `HEALTHCHECK` added** (previously absent), same shape as the other
  services: `wget --spider` against `/health` on the service's own port,
  30s interval / 3s timeout / 5s start-period / 3 retries.

## Testing

- `billing-service`: new `HealthControllerTest` (`@WebMvcTest` +
  `@Import(SecurityConfig.class)`) — written first and run failing (didn't
  compile: neither class existed yet), then `HealthController` and
  `SecurityConfig` added, test passes (`/health` and `/ping` both `200`
  without authentication). Full `mvn test` for billing-service still green
  afterward.
- `auth-service`, `products-service`, `orders-service`: no Java source
  changed (only Dockerfiles/docker-compose.yml), so their existing `mvn
  test` suites were re-run as a regression check only — all still green.
- `api-gateway`, `inventory-service`: no Go source changed (Dockerfile
  only); `go build && go vet && go test ./...` re-run as a regression check
  — both still green.
- **Live verification, full stack**: `docker compose up -d --build` (all
  six services + infra). Result: `auth-service`, `products-service`,
  `orders-service`, `billing-service`, `inventory-service`, and
  `api-gateway` **all** report `healthy` in `docker compose ps` — the first
  time all six have done so simultaneously. Direct `curl .../health` on
  every service's own port returned the expected JSON body. Spot-checked
  that this didn't loosen anything: `curl http://localhost:8085/api/payments/1`
  (billing's real API) still returns `403` unauthenticated, unchanged; the
  gateway's `/billing/health` and `/orders/health` routes (through the
  circuit breaker from ADR-0006/0009) still return `200`.

## Consequences

**Positive**: `docker compose ps` now gives an accurate signal for every
service. `inventory-service`'s `depends_on: orders-service: condition:
service_healthy` (previously gated on a no-op check that always passed) now
actually gates on orders-service being ready.

**Negative / residual**:
- `billing-service` still has no JWT integration — its real API
  (`/api/payments/**`) is protected only by Spring Boot's default
  single-generated-user Basic auth, unlike its three sibling services. Not
  addressed here; a separate, larger task.
- The `/health` endpoints across all four Spring services are shallow
  liveness checks (hardcoded `"status": "OK"`, no real DB/broker
  connectivity probe) — `products-service`'s and `auth-service`'s even
  claim `"database": "Connected"` unconditionally, without checking.
  Pre-existing pattern, not introduced or deepened here.

## References

- [ADR-0009](0009-billing-circuit-breaker.md) — where the port bug and its
  two follow-on discoveries (billing's missing SecurityConfig, orders'
  broken Compose override) were first found and logged.
- `orders-service`'s `JwtConsumer`/`JwtAuthenticationFilter` pair
  (`src/main/java/com/easydora/orders/consumer/JwtConsumer.java` and its
  `security` package) — the broadcast JWT cache pattern billing-service's
  `SecurityConfig` deliberately doesn't mirror.
