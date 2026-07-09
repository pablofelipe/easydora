# ADR-0006: Circuit breaker in the API Gateway

## Status

Accepted - 2026-07-04

## Context

The baseline audit catalogued the gateway as a plain reverse proxy with no circuit breaker or retry anywhere in the codebase: a downstream failure (service down, connection refused) propagated straight through with no protection against cascading load — every request to a dead service still pays the full dial-timeout cost, indefinitely, one request at a time.

The gateway's routing (`api-gateway/main.go`) is already keyed by a fixed `services` map (`auth`, `products`, `inventory`, `orders`, `billing`), each entry proxied through `createReverseProxy(target, serviceName string)`. Because the target service is already resolved by the time this function runs — no runtime path inspection needed — adding a per-service breaker only required a `serviceName`-keyed `gobreaker.CircuitBreaker` built once per entry, not a redesign of the routing itself.

**Scope for this ADR: `auth`, `products`, `inventory`, `orders`.** `billing-service` was only wired into the gateway's routing table in the immediately preceding change (see the README Roadmap and git history) and is deliberately left out of this round — see the open Roadmap item below.

## Decision

Use [`sony/gobreaker`](https://github.com/sony/gobreaker) (v1.0.0), one `*gobreaker.CircuitBreaker` per service, constructed once in `createReverseProxyWithBreaker` (called once per service at route-registration time, in `setupServiceRoutes`) and shared across every request that handler serves afterward.

- **Thresholds — 5 consecutive failures to open, 30s cooldown before a half-open trial.** These specific numbers were given directly, not derived or tuned against any measurement; recorded here per this project's standing rule against inventing values without justifying them. Both are gobreaker's own `Settings` fields (`ReadyToTrip: func(counts) bool { return counts.ConsecutiveFailures >= 5 }`, `Timeout: 30 * time.Second`); `MaxRequests` and `Interval` are left at gobreaker's documented defaults (1 trial request in half-open; consecutive-failure counting isn't affected by `Interval` since `ConsecutiveFailures` resets on any success regardless).
- **Failure definition: downstream unreachable, not downstream returned an error.** `createReverseProxy`'s existing `proxy.ErrorHandler` already writes `502 Bad Gateway` only when `httputil.ReverseProxy`'s transport fails outright (connection refused, dial timeout, broken pipe) — never for a valid HTTP response from the backend, even a 4xx/5xx one. `createReverseProxyWithBreaker` wraps the plain proxy and checks exactly that: if the response status is `502`, the breaker call is reported as failed; any other status (including a real `500` from a healthy backend) counts as success. This keeps the breaker scoped to "can we even reach this service," matching the failure mode a stopped container actually produces.
- **`createReverseProxyWithBreaker` is a wrapper around `createReverseProxy`, not a change to it.** `createReverseProxy` itself is untouched. `setupServiceRoutes` picks which one to use per entry — `billing` still gets the plain, breaker-less `createReverseProxy`; every other implemented entry gets `createReverseProxyWithBreaker`. This was a deliberate structural choice so the "billing has no breaker yet" fact is visible directly in `setupServiceRoutes`'s branch, not hidden behind a config flag inside a single shared function.

## Testing

Table-driven Go tests in `api-gateway/main_test.go`, each downstream simulated locally (no live containers needed for these):

- **`TestCircuitBreaker_OpensAfterThreshold`** (table-driven over all four in-scope services): 5 requests against an unreachable target each get the proxy's own `502`; the 6th gets `503` from the breaker itself, proving it opens exactly at the configured threshold, for every service.
- **`TestCircuitBreaker_PerServiceIndependence`**: tripping `inventory-service`'s breaker leaves a separate `orders-service` breaker (against a healthy target) fully functional — proves breakers don't share state.
- **`TestCircuitBreaker_ProxyBypassedWhenOpen`**: a downstream that counts every TCP connection it receives is dialed at least 5 times while the breaker is closed; once open, three more calls all return `503` and the dial count doesn't move — proves the downstream is never touched while open, not just that the response looks right.
- **`TestPlainProxy_DoesNotShortCircuit`**: the "Red" counterpart — `createReverseProxy` (billing's code path) keeps dialing an always-failing downstream on every one of 10 calls, with no `503` ever appearing. Swapping `createReverseProxyWithBreaker` for `createReverseProxy` in any of the three tests above turns their `503` assertions false, which is what actually establishes these tests exercise the breaker and not some other status-code coincidence.

**Live verification against real containers** (not just the Go tests above): built and started `auth-service`, `products-service`, `orders-service`, `inventory-service`, and `api-gateway` for real, then `docker compose stop inventory-service`. Requests 1–5 to `/inventory/health` through the gateway each took ~2.3s and returned `502` (the proxy actually attempting and failing the dial each time); requests 6 and 7 returned `503` in ~72ms — the breaker open, short-circuiting before touching the network at all. In the same window, `/auth/actuator/health`, `/products/actuator/health`, and `/orders/actuator/health` all kept returning their normal `403` (Spring Security, unrelated to this change) in ~100–115ms, identical to the pre-failure baseline — proving the other three breakers were completely unaffected. `inventory-service` was restarted afterward to restore the environment.

## Consequences

**Positive**: a downstream outage no longer costs a full dial-timeout on every single request once the breaker trips — `auth`, `products`, `inventory`, and `orders` all fail fast and independently. The pattern (a thin wrapper around the existing proxy function) is directly reusable for `billing` once it's migrated.

**Negative / residual**:
- `billing-service` still has no circuit breaker — tracked as an open Roadmap item, not fixed here.
- The failure signal (checking for the proxy's own `502`) is a status-code heuristic, not a first-class error return from `createReverseProxy`. It's accurate today because `502` is only ever written by this codebase's own `ErrorHandler`, but it would misfire if a real backend ever legitimately returned `502` itself.
- Thresholds (5 failures / 30s) are fixed, given values — not validated against this project's actual traffic patterns or measured failure-recovery times, since none exist yet for a portfolio-scale deployment.

## Update — 2026-07-04

`billing-service`'s exclusion from this ADR's scope, named above as an
open Roadmap item, was closed the same week by
[ADR-0009](0009-billing-circuit-breaker.md): the same `sony/gobreaker`
pattern (5 failures / 30s cooldown) applied to `billing`'s gateway route,
using the identical wrapper structure this ADR established.

## References

- Baseline audit (2026-07-03 entry in this repo's history) — original catalogue of "no circuit breaker/retry anywhere in the codebase."
- [ADR-0009](0009-billing-circuit-breaker.md) — closes the billing-service
  gap this ADR left open.
