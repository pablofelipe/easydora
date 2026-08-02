# ADR-0026: SvelteKit frontend as a thin client over the API Gateway

## Status

Accepted - 2026-07-10

## Context

`frontend/` had been an empty scaffold directory since the project's first
commit — every other service was implemented, tested, and reachable through
the Gateway, but there was no client demonstrating the distributed flow
visually. The goal was never a full storefront: it is a small, read-mostly
UI whose purpose is to make the event-driven architecture already built
across the other seven services observable and explorable, not to add new
backend capability.

Building it surfaced several real integration defects that had no prior
client to catch them, since every existing caller (`docs/walkthrough.md`,
the Postman collection, `e2e-tests/`) drives the backend with `curl`/direct
HTTP clients, never a real browser subject to CORS. Each is fixed here
because the frontend cannot function without it — not as unrelated cleanup.

### Defect 1 — CORS was either missing or inert everywhere

`products-service`/`orders-service` had a `WebConfig implements
WebMvcConfigurer` with a permissive `CorsRegistry`, but Spring MVC's
CORS resolution only runs once a request reaches `DispatcherServlet` — and
Spring Security's `AuthorizationFilter` runs first. Any CORS preflight
(`OPTIONS`) to a path under `anyRequest().authenticated()` was rejected by
Security before `WebConfig` ever got a chance to add a CORS header,
making it dead code for exactly the endpoints that needed it most.
`auth-service`/`billing-service`/`inventory-service`/`notification-service`
had no CORS mechanism at all. Confirmed empirically (principle #8, see
[Architectural Principles](../architecture/architectural-principles.md)):
a real preflight `curl -X OPTIONS` against a protected endpoint returned
403 with no `Access-Control-Allow-Origin` header, before any fix.

### Defect 2 — a JWT this service doesn't recognize terminated the request outright

`products-service`'s `JwtAuthenticationFilter` (and, per
[docs/architecture overview.md](../architecture/overview.md)'s "broadcast
JWT cache" design, presumably its per-service duplicates in
`orders-service`/`billing-service` too, though only `products-service`'s
copy was touched here) wrote a `401` directly and returned whenever a
`Bearer` token was present but not found in its in-memory cache —
regardless of which path was being requested. This only mattered once a
public, `permitAll()` path could receive an Authorization header from a
caller whose token this specific service was never going to cache in the
first place (see Defect 3): the filter's own early exit pre-empted
Security's `authorizeHttpRequests` from ever getting to decide.

### Defect 3 — products-service's catalog was never actually public

`GET /all-products` and `GET /{id}` sat under `anyRequest().authenticated()`,
with no route to becoming authenticated for a `BUYER`: per
[docs/sequence-diagram.md](../sequence-diagram.md)'s reading notes,
`products-service`'s `UserEventConsumer.handleJwtCreated` only caches
`jwt.created` for role `SELLER` — a buyer's own token is deliberately
ignored. No previous client ever called these endpoints as an authenticated
buyer (only as an unauthenticated `curl`, or as a seller managing their own
catalog), so this had never been exercised end to end before.

### Defect 4 — the Gateway echoed two different values under the same response header

`api-gateway`'s `correlationMiddleware` sets `X-Correlation-Id`/
`X-Request-Id` on the Gin response before the reverse-proxy handler runs;
`httputil.ReverseProxy` then copies the backend response's own headers on
top via `Header.Add`, not `Header.Set`. Since every downstream service
also echoes those same two header names back (by design, see
[ADR-0024](0024-distributed-tracing-via-propagated-identifiers.md)), every
Gateway response carried each header twice. `curl -i` prints this as two
separate lines (easy to miss); a browser's `fetch().headers.get(...)`
joins repeated values with `", "`, so a real client-side observability
panel would have shown a garbled `"id-a, id-b"` string instead of one
value. Existing tests only asserted on `Header().Get(...)`, which silently
returns just the first value, so this bug was invisible to them (confirmed
by reproducing it live with a real browser via a throwaway Playwright
script, then again with `curl -i`, before writing the fix — principle #8).

## Decision

### Frontend

A SvelteKit + TypeScript app in `frontend/`, deliberately kept to what the
ticket's scope needs:

- **Consumes the Gateway exclusively** (`http://localhost:8080` by
  default, `VITE_GATEWAY_URL` to override) — no module anywhere references
  a service's own port.
- **Single HTTP layer** (`src/lib/api/client.ts`): every call goes through
  one `apiFetch` wrapper that attaches `Authorization` when a session
  exists and records the response's `X-Correlation-Id`/`X-Request-Id`
  into a store. It originally also sent an `X-User-Id` header, mirroring
  the backend's header-based identity of the time -- that was a real
  security gap (the header was trusted with no cross-check against the
  JWT), closed by
  [ADR-0027](0027-jwt-principal-as-sole-identity-source.md), and the
  header is no longer sent. Per-service modules
  (`auth.ts`, `products.ts`, `orders.ts`, `notifications.ts`, `billing.ts`)
  are thin — no business logic, just typed request/response shapes.
- **SSR disabled** (`export const ssr = false` in the root `+layout.ts`):
  the JWT lives in `localStorage`, which doesn't exist on the server, and
  this is a demo client, not a product needing SEO — matches the ticket's
  explicit "avoid advanced SSR" scope.
- **Native Svelte stores only** — `auth` (session, persisted to
  `localStorage`) and `lastRequest` (the tracing panel's data source). No
  state-management library.
- **A manual "Process payment" action** on the order detail page, calling
  billing-service's `POST /api/payments/process` directly. Nothing in the
  backend triggers a payment automatically (see
  [docs/sequence-diagram.md](../sequence-diagram.md)) — without this
  button, an order would never be observable past `INVENTORY_RESERVED`
  through the UI, defeating the frontend's stated purpose of making the
  full event-driven lifecycle visible.
- **`adapter-node`** for Docker packaging, wired into `docker-compose.yml`
  as a seventh application service on port 3000. `VITE_GATEWAY_URL` is a
  *build-time* value baked into the client bundle — it is set to
  `http://localhost:8080` (the Gateway's host-published port), not the
  Docker-network hostname `api-gateway`, because it has to be reachable
  from the user's own browser, not from inside the compose network.

### Backend (only what the frontend integration required)

- **CORS**, one consistent policy (`allowedOriginPatterns("*")`, no
  credentials, `X-Correlation-Id`/`X-Request-Id` in `exposedHeaders`)
  wired the same way in all six services that needed it:
  - `auth-service`/`products-service`/`orders-service`/`billing-service`:
    a `CorsConfigurationSource` bean actually wired via
    `.cors(cors -> cors.configurationSource(...))` in each
    `SecurityFilterChain`, plus an explicit
    `.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()` ahead of
    `anyRequest().authenticated()`/`denyAll()` so preflight never depends
    on an `Authorization` header it isn't sent with. `products-service`'s
    `WebConfig`/`orders-service`'s `WebConfig` (the dead, Security-shadowed
    mechanism from Defect 1) were deleted rather than kept alongside the
    working one (principle #5/#6 — no hybrid CORS mechanisms).
  - `inventory-service` (Go): a small `withCORS` handler wrapper — no
    Security layer to integrate with, just headers plus an `OPTIONS`
    short-circuit.
  - `notification-service` (FastAPI): `CORSMiddleware`.
- **`products-service`'s `JwtAuthenticationFilter`** no longer terminates
  the request when a token isn't in its cache — it leaves the request
  unauthenticated and calls `filterChain.doFilter(...)`, letting
  `authorizeHttpRequests` (not a hand-rolled short-circuit) decide the
  outcome per path.
- **`products-service`'s `SecurityConfig`** makes `GET /all-products`,
  `GET /{id}`, and `GET /seller/*` `permitAll()` — read-only catalog
  browsing needs no authentication, matching how any real storefront
  works, and how nothing in `anyRequest().authenticated()` could ever be
  satisfied by a buyer's token in the first place (Defect 3).
  `GET /my-products` is declared `authenticated()` explicitly, ahead of
  the general rule, since `/{id}`'s pattern would otherwise also match it.
- **`api-gateway`'s `createReverseProxy`** sets `proxy.ModifyResponse` to
  delete `X-Correlation-Id`/`X-Request-Id` from the backend's response
  before it's copied onto the client-facing one, so only the Gateway's own
  hop values (already correct — reused `CorrelationId`, fresh
  `RequestId`) reach the client. The client's actual request/response pair
  is with the Gateway; the downstream service's own hop identifiers stay
  in its own logs, which is where `docs/architecture/observability.md`
  already says they're read from.
- **`notification-service` gains a Gateway route.** It keeps its existing
  bare routes (`/health`, `/notifications/{order_id}`) for its existing
  direct callers (`docs/walkthrough.md`, `e2e-tests`), and gains
  self-namespaced twins (`/notification/health`,
  `/notification/notifications/{order_id}`) registered on the same handler
  functions, following the exact convention ADR-0025 established for the
  other five services. `api-gateway` gets a sixth `services` map entry
  (`notification` → `NOTIFICATION_SERVICE_URL`, default
  `http://notification-service:8086`).

## Consequences

**Positive**:
- The full business flow (login → browse → checkout → track → notify) is
  now drivable end to end through a browser, hitting only the Gateway —
  validated with a real headless browser (Chromium via a throwaway
  Playwright script, not committed to the repo) confirming zero requests
  reached any service's own port.
- Four real, previously-latent defects fixed, none of which were
  reachable by any prior client: dead CORS config, a filter that
  short-circuited ahead of Security's own authorization decision, a
  catalog endpoint no buyer could ever actually call, and a Gateway
  response header silently carrying two values.
- `notification-service` is no longer the one service with no Gateway
  route — closes the residual item ADR-0025's Consequences explicitly left
  open.

**Negative / residual, not fixed here**:
- **No automated frontend tests.** `svelte-check` (0 errors) and a
  production build (`adapter-node`) were verified, and the full flow was
  validated with a real browser, but no Vitest/Playwright suite was added
  to the repository — out of scope per the ticket's explicit "avoid heavy
  tooling" instruction; the project's own `e2e-tests` module already
  covers cross-service correctness at the API level.
- **The same short-circuit pattern in Defect 2 likely exists in
  `orders-service`/`billing-service`'s own `JwtAuthenticationFilter`
  copies** (each service keeps its own duplicate, per the project's
  "no shared library for business logic" convention). Only
  `products-service`'s copy was fixed, because it's the only one with a
  concretely broken, `permitAll()` path today — fixing the other two
  preemptively, with no failing case to point at, was left alone rather
  than speculatively changed.
- **Payment processing stays a manual button, not automatic.** This
  mirrors a real, pre-existing backend gap (nothing publishes a trigger
  for `PaymentService.processPayment`), not a frontend simplification —
  documented here so it isn't mistaken for an oversight.
- **No rule prevents a `SELLER` from purchasing their own product.**
  `orders-service` never consumes `product.*` events, so it has no local
  notion of product ownership to check against. Registered as open
  technical debt in the README Roadmap rather than fixed here — closing
  it needs a new consumer, a new `orders_schema` table, and a check in
  `OrderService.createOrder`, which is backend scope beyond this frontend
  ticket.
- **A brief, real eventual-consistency window is visible in the UI.**
  Immediately after `POST /createOrder` or a payment call, the order's
  state may still read its pre-transition value until the async
  RabbitMQ round trip (stock reservation, payment-outcome event) lands —
  the same lag the backend has always had (see
  [docs/sequence-diagram.md](../sequence-diagram.md)), now visible to a
  user instead of hidden behind a `curl` script that happened to pause
  between calls. Reloading the order detail page shows the settled state;
  no artificial delay or retry loop was added to mask this, since doing so
  would hide the exact architectural property this frontend exists to
  make visible.

## Update — 2026-08-02: the manual payment button is correct, not a gap

This ADR's own residual item — "payment processing stays a manual button,
not automatic" — was revisited and re-scoped, not simply closed by adding
automation. A real payment fundamentally requires an external actor: the
customer completing checkout, or a payment gateway's own callback. Having
`billing-service` auto-trigger `processPayment` the moment an order
reaches `INVENTORY_RESERVED` would model something this domain doesn't
actually have — a system charging a customer's card with no customer
action or gateway confirmation involved. The manual button stays exactly
as this ADR originally built it: a correct simulation of that missing
external step, using the deterministic mock provider
([ADR-0030](0030-deterministic-payment-provider.md)) already standing in
for a real gateway.

The real gap this ADR's own Context implicitly pointed at was different:
`PaymentController.processPayment` accepted a charge for an order in
*any* state, at any time — the reason [ADR-0034](0034-payment-compensation-saga.md)'s
whole compensation saga had to exist in the first place. That gap is now
closed with a guard, not automation: `billing-service` consumes
`order.status-changed` (a new `OrderEventListener.handleOrderStatusChanged`)
to keep each `Payment`'s own `orderState` column current, and
`processPayment` rejects (`400`, a new `OrderNotReadyForPaymentException`)
unless the order has actually reached `INVENTORY_RESERVED` — checked
after the existing already-`APPROVED` short-circuit, so a
duplicate/replayed call for a payment that already succeeded stays a
no-op even once the order has moved past that state into
`PAYMENT_APPROVED`. ADR-0034's saga is unchanged and still exists for the
races this guard can't see (e.g. the order moves on between this check
and the provider call). Proven by `PaymentServiceOrderStateGuardTest` and
confirmed live: an immediate payment attempt on a still-`PROCESSING`
order returned `400`; the same order returned `200` once
`order.status-changed` had propagated it to `INVENTORY_RESERVED`.

## References

- [ADR-0025](0025-gateway-transparent-routing.md) — established the
  self-namespaced-service-per-Gateway-segment convention this ADR extends
  to `notification-service`.
- [ADR-0024](0024-distributed-tracing-via-propagated-identifiers.md) —
  defines `X-Correlation-Id`/`X-Request-Id`'s semantics; this ADR's
  Gateway header fix and frontend "Request Details" panel are both built
  directly on that design.
- [ADR-0014](0014-notification-service.md) — introduced
  `notification-service` and its original bare routes, kept unchanged
  here alongside the new self-namespaced ones.
- [docs/architecture/overview.md](../architecture/overview.md) — the
  "broadcast JWT cache" design (Defect 2/3's root cause) and the exchange
  table this frontend's checkout/payment flow exercises.
- [Architectural Principles](../architecture/architectural-principles.md)
  — principle #8 (evidence over assumption) underlies every defect in this
  ADR's Context, each reproduced live before being fixed; principle #5/#6
  (no dead code, no hybrid mechanisms) is why the shadowed `WebConfig`
  classes were deleted rather than left alongside the working CORS
  mechanism.
