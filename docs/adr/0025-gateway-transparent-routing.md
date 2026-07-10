# ADR-0025: Gateway transparent routing — every service is self-namespaced

## Status

Accepted - 2026-07-09

## Context

`api-gateway`'s reverse proxy (`setupServiceRoutes`/`createReverseProxy` in
`main.go`) mounted each service under its own path prefix (`/auth`,
`/products`, `/inventory`, `/orders`, `/billing`) and then **stripped that
prefix** before forwarding the request, so `GET /inventory/123` reached
`inventory-service` as `GET /123`.

This happened to still work for four of the five services —
`auth-service`, `products-service`, `orders-service`, `billing-service` —
purely because their own routes were mounted bare (e.g. `AuthController`
exposes `/signup`, not `/auth/signup`). `inventory-service` is the
exception: its own HTTP handlers are registered directly under
`/inventory/` (`GET /inventory/{productId}`, `POST /inventory`), so
stripping the prefix left a bare path with no matching route — every call
to `inventory-service` through the Gateway 404'd, while the identical call
made directly against port 8083 succeeded. This was found and documented
as open debt while validating ADR-0024's CorrelationId propagation through
the Gateway (see the README Roadmap entry it left behind), and is the
concrete trigger for this ADR.

Before writing any code, the actual dependents of the existing (stripping)
behavior were audited directly rather than assumed:

- `docs/walkthrough.md`, the Postman collection, and `e2e-tests/` all call
  every service **directly by its own port** — none of them exercised the
  Gateway's proxying at all. There was no internal client whose existing
  behavior depended on the strip-prefix mechanism for any of the five
  services.
- The only direct inter-service HTTP call anywhere in the system is
  `notification-service` calling `auth-service`'s
  `GET /users/{id}/notification-profile` (`app/auth_client.py`) — every
  other cross-service interaction goes through RabbitMQ.

Two designs were considered to fix `inventory-service`'s 404 specifically:

1. **A per-service `PassthroughPath` flag in the Gateway**, defaulting to
   the existing strip behavior and set to bypass it only for
   `inventory-service`. This is the minimal, zero-blast-radius fix: no
   service's HTTP contract changes, only the Gateway's internal
   forwarding logic. It was implemented first and then explicitly
   rejected in favor of the option below, on the grounds that it
   preserves an architectural inconsistency (four services are *not*
   self-namespaced, one *is*) as a permanent, silently-diverging
   exception baked into the Gateway rather than resolving it.
2. **Make the Gateway a fully transparent routing layer — stop stripping
   entirely — and make every service self-namespaced**, matching the
   convention `inventory-service` already happened to follow. This closes
   the inconsistency instead of special-casing around it, at the cost of
   changing the HTTP contract of four services (their own base path now
   includes their Gateway segment, even when called directly).

Option 2 was chosen.

## Decision

**The Gateway forwards the incoming request path unchanged — no prefix is
added, stripped, or otherwise rewritten. Every service is self-namespaced
under the same segment the Gateway already used to route to it
(`/auth`, `/products`, `/orders`, `/billing`, `/inventory`), so a direct
call and a call proxied through the Gateway hit the exact same path.**

### Gateway (`api-gateway/main.go`)

`createReverseProxy` no longer reads `c.Param("proxyPath")` to compute a
stripped forwarding path; it forwards `c.Request.URL.Path` exactly as
received. `setupServiceRoutes`'s per-service route groups are unchanged
(still needed for Gin to dispatch to the right upstream and circuit
breaker), only the path that reaches the upstream changed.

### The four Spring services (`auth`, `products`, `orders`, `billing`)

Each gets one line in its `application.properties`:

```properties
server.servlet.context-path=/auth       # (or /products, /orders, /billing)
```

This was chosen over manually prefixing every `@RequestMapping`/
`@GetMapping`/`@PostMapping` because:

- It is a single-line, whole-service change instead of touching every
  controller.
- Spring Security's `requestMatchers(...)`/`permitAll()` patterns in each
  service's `SecurityConfig` match paths **after** the servlet
  container has already stripped the context path — confirmed empirically
  by running each service's existing test suite unchanged after adding
  the property, not assumed. No `SecurityConfig` needed a single change.
- `@WebMvcTest`-based `MockMvc` tests (`UserQueryControllerTest`,
  `SecurityConfigTest` in `auth-service`; `PaymentControllerSecurityTest`,
  `HealthControllerTest` in `billing-service`) also do not apply
  `server.servlet.context-path` — confirmed the same way. None of the
  four services' test suites needed a single path changed.

### `inventory-service` (Go)

Already self-namespaced for its business routes
(`GET /inventory/{productId}`, `POST /inventory`) — no change needed
there. Its `/health` route, however, was bare (used only by Docker's own
`HEALTHCHECK` hitting the container directly), so a Gateway-proxied
`GET /inventory/health` would have been swallowed by the `/inventory/`
catch-all handler (which treats anything after the prefix as a product
ID) instead of reaching a real health check. A second route,
`/inventory/health`, was added pointing at the same extracted
`healthHandler` function; the bare `/health` route is kept unchanged for
Docker's own direct `HEALTHCHECK`.

### `notification-service` (Python)

Not routed through the Gateway at all (no entry in `api-gateway`'s
`services` map) — out of scope for this ADR, unchanged. Its one outbound
HTTP call, however, targets `auth-service` directly and had to be updated
in lockstep with `auth-service`'s new context path:
`app/auth_client.py`'s URL changed from `/users/{id}/notification-profile`
to `/auth/users/{id}/notification-profile`.

### Everything else that called a service directly, updated in lockstep

Adding a context path changes what "calling a service directly" means,
not just what the Gateway forwards — every existing direct caller had to
move in the same commit or it would have silently started 404ing:

- `{auth,products,orders,billing}-service/Dockerfile`'s own `HEALTHCHECK`
  (`wget ... http://localhost:PORT/health`) now targets
  `http://localhost:PORT/{segment}/health`.
- `.github/workflows/ci.yml`'s three readiness-check `curl` loops (CI
  Phase 3 jobs, which start real service processes and poll their health
  endpoint before running `e2e-tests`).
- `e2e-tests/`'s `CatalogOnboardingE2ETest`/`OrderLifecycleE2ETest` — both
  call services directly by port (by design, see ADR-0013), so every
  hardcoded path (`/signup`, `/login`, `/verify-email`, `/createProduct`,
  `/sellers/{id}`, `/createOrder`, `/{orderId}`,
  `/api/payments/order/{id}`) gained its service's segment.
- The Postman collection: rather than rewriting the existing direct-URL
  requests in place, the whole tree was duplicated into two top-level
  folders — `Via Gateway (primary)` (new, using `gateway_url` plus each
  service's segment) and `Direct (debug)` (the original per-service
  requests, unchanged) — so a failure can still be isolated to "the
  service" vs. "the Gateway's routing" without reasoning about an extra
  hop. `notification-service`'s folder only exists under `Direct (debug)`,
  matching its lack of a Gateway route.

## Consequences

**Positive**:
- `inventory-service` is now reachable through the Gateway at all — the
  concrete bug this ADR exists to fix.
- The Gateway is now a genuinely transparent routing layer: what you call
  directly and what you call through the Gateway are byte-for-byte the
  same path, for every one of the five wired services. This removes a
  class of future bugs where a service's own route design and the
  Gateway's forwarding behavior can silently diverge again.
- `docs/walkthrough.md` and the Postman collection now exercise the
  Gateway as the primary entry point, closing the gap this ADR's own
  Context section found: nothing previously validated the Gateway's
  proxying at all.

**Negative / residual, not fixed here**:
- **Every direct caller of the four Spring services had to change in the
  same commit.** This is a wider blast radius than the alternative
  (`PassthroughPath`, rejected above) would have had. Accepted because the
  alternative would have left a permanent, undocumented architectural
  inconsistency instead of resolving it.
- **`notification-service` still has no Gateway route.** Its own read
  endpoint (`GET /notifications/{orderId}`) and health check remain
  reachable only directly (port 8086) — unchanged, and out of scope here;
  wiring it in remains contingent on a real client needing it there, per
  the existing Roadmap entry.
- **`postman/README.md`'s "Known limitations" section already noted, from
  before this ADR, that `notification-service` exposes only `/health`
  with no way to read a notification back** — that description is now
  stale (a `GET /notifications/{orderId}` endpoint exists, per
  ADR-0020) independently of this ADR's change. Found while updating the
  surrounding Postman docs; left unfixed as out of scope for this ADR,
  per this project's rule of not silently fixing unrelated bugs found
  mid-task.

## References

- [ADR-0013](0013-ci-phase-3-cross-service-e2e.md) — the CI Phase 3
  design (`e2e-tests` calling real running processes directly by port)
  whose hardcoded paths this ADR updates in lockstep.
- [ADR-0014](0014-notification-service.md) — introduces
  `notification-service`'s direct HTTP call to `auth-service`, the one
  inter-service call this ADR's context-path change required updating.
- [ADR-0020](0020-notification-domain-completion.md) — adds
  `GET /notifications/{orderId}`, which supersedes the "no public API"
  limitation `postman/README.md` still described (found stale during this
  ADR, left unfixed as out of scope).
- [ADR-0006](0006-gateway-circuit-breaker.md) /
  [ADR-0009](0009-billing-circuit-breaker.md) — the per-service circuit
  breaker this ADR's routing change sits alongside unchanged;
  `setupServiceRoutes`'s route groups (and therefore which breaker a
  request goes through) are untouched by this ADR.
- [Architectural Principles](../architecture/architectural-principles.md)
  — principle #6 ("avoid unnecessary compatibility or hybrid modes")
  applies directly to rejecting the `PassthroughPath` flag: it would have
  been a permanent, silently-diverging per-service exception baked into
  the Gateway rather than a single clean mechanism every service follows.
