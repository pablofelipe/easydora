# ADR-0009: Extend the API Gateway circuit breaker to billing-service

## Status

Accepted - 2026-07-05

## Context

ADR-0006 added a per-service `sony/gobreaker` circuit breaker to the API
Gateway, but deliberately scoped it to `auth`, `products`, `inventory`, and
`orders` — `billing-service` had only just been wired into the gateway's
routing table in the immediately preceding change at the time, so it was
left on the plain, breaker-less `createReverseProxy` and tracked as an open
Roadmap item ("api-gateway: billing-service is routable ... but has no
circuit breaker").

Nothing about billing-service's routing is actually different from the
other four entries — same `ServiceConfig` shape in the `services` map, same
reverse-proxy mechanics. The exclusion was purely about not having migrated
it yet, not a technical constraint.

## Decision

Apply the exact structure ADR-0006 already established, with no new
mechanism: remove the `if path == "billing" { ... } else { ... }` branch in
`setupServiceRoutes` so every implemented entry — billing included — goes
through `createReverseProxyWithBreaker`. Same thresholds
(`circuitBreakerSettings`: 5 consecutive downstream-unreachable failures to
open, 30s cooldown before a half-open trial), same failure definition
(proxy's own `502` = unreachable, not a real 4xx/5xx from a healthy
backend), same per-service breaker instance built once at route-registration
time.

`createReverseProxy` itself is untouched and still exists — it's now used
only internally by `createReverseProxyWithBreaker`, no longer routed to
directly for any service.

## Testing

Following ADR-0006's precedent, `api-gateway/main_test.go`:

- **`TestSetupServiceRoutes_BillingUsesBreaker`** — the actual Red test for
  this change: routes through `setupServiceRoutes` itself (not just the
  generic `createReverseProxyWithBreaker` helper), with `services["billing"]`
  temporarily pointed at an unreachable address. Written and run *before*
  the `main.go` change; it failed as expected (6th call still returned `502`
  instead of `503`, since billing was still special-cased to the plain
  proxy). Passes after the branch was removed.
- `TestCircuitBreaker_OpensAfterThreshold`, `TestCircuitBreaker_
  PerServiceIndependence`, and `TestCircuitBreaker_ProxyBypassedWhenOpen`
  (ADR-0006's original tests, unchanged) continue to cover the generic
  breaker-wrapper behavior and still pass.
- `TestPlainProxy_DoesNotShortCircuit` (ADR-0006's "Red" counterpart for the
  plain proxy) was updated to stop naming billing-service as the excluded
  case — it now exercises `createReverseProxy` as a generic internal helper,
  since no service is routed through it directly anymore.

**Live verification against real containers**: `billing-service` and
`api-gateway` built and started via `docker compose up -d --build
postgres rabbitmq billing-service api-gateway`, then `docker compose stop
billing-service`. Requests 1–5 to `/billing/actuator/health` through the
gateway each took ~3.9–4.1s and returned `502` (the proxy actually attempting
and failing the dial each time); requests 6, 7, and 8 returned `503` in
57–84ms — the breaker open, short-circuiting before touching the network at
all. Matches the exact pattern ADR-0006 recorded for inventory-service. In
the same window, `/auth/actuator/health`, `/products/actuator/health`, and
`/orders/actuator/health` all kept returning their normal `403` (Spring
Security, unrelated to this change), proving the other three breakers were
unaffected. `billing-service` was restarted afterward to restore the
environment.

Note: while verifying, `billing-service` was observed reporting "unhealthy"
in `docker compose ps` even though the process and the gateway route both
work correctly (confirmed by hitting `/actuator/health` directly on 8085
and through the gateway, both returning `401` as expected pre-stop). Cause:
`billing-service/Dockerfile:38` hard-coded its `HEALTHCHECK` against
`http://localhost:8082/actuator/health` — port 8082 is products-service's
port, not billing-service's own 8085 (the `EXPOSE` line had the same
copy-paste artifact). Unrelated to this ADR's scope, so left unfixed and
reported separately at the time — since authorized and the port itself
fixed as a follow-up (both `EXPOSE` and `HEALTHCHECK` corrected to 8085).
Verifying that fix surfaced a second, independent issue: `wget` now reaches
billing-service (getting `401` instead of connection-refused), but the
container still never reports "healthy" because `billing-service` has
`spring-boot-starter-security` with no `SecurityConfig`, so Spring
Security's default deny-all covers `/actuator/health` too. That second
issue is not fixed — see README Roadmap. A copy of the same wrong-port
pattern was also found in `orders-service/Dockerfile` while comparing the
three services' Dockerfiles; since authorized and fixed too (`EXPOSE`/
`HEALTHCHECK` corrected from 8082 to orders-service's real 8084).

Verifying the orders-service port fix surfaced a third, more serious issue:
`orders-service`'s health status in `docker compose ps` is driven entirely
by a `healthcheck:` override in `docker-compose.yml` (lines 181-190), not by
the Dockerfile's `HEALTHCHECK` — and that override's `test: >` YAML folded
block turns its own `# Verifica se aplicação Spring está respondendo`
comment line into one that swallows the actual `curl -f ... || exit 1` test
once folding joins every line with spaces instead of newlines. The container
health check therefore always exits `0` — reports "healthy" no matter what.
Confirmed by reproducing the exact folded string `docker compose config`
generates directly inside the container. Practical consequence:
`inventory-service`'s `depends_on: orders-service: condition:
service_healthy` never actually gates on orders-service being ready. Not
fixed here — outside this task's authorized scope (a `docker-compose.yml`
bug, not the Dockerfile copy-paste pattern this round covered) — see README
Roadmap. Also, independent of that: orders-service's own `SecurityConfig`
doesn't `permitAll()` `/actuator/health` either (only `/ping`, `/health`,
`/error`, `/debug/**`), so a corrected healthcheck would still see `403` —
the same class of gap already open for billing-service.

## Consequences

**Positive**: all five implemented gateway entries (`auth`, `products`,
`inventory`, `orders`, `billing`) now fail fast and independently on a
downstream outage — no asymmetric gap left in the gateway's protection.
ADR-0006's open Roadmap item is closed.

**Negative / residual**: none beyond what ADR-0006 already documented for
the other four services — same status-code heuristic for failure detection,
same fixed, unvalidated thresholds.

## References

- [ADR-0006](0006-gateway-circuit-breaker.md) — original decision and full
  rationale for the thresholds and failure-detection approach, reused here
  unchanged.
- README Roadmap — the item this ADR closes.
