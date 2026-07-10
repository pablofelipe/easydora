# ADR-0027: JWT principal as the sole identity source for orders/products

## Status

Accepted - 2026-07-10

## Context

A targeted review (the same one that produced the batch of technical-debt
items appended to the README Roadmap earlier the same day) flagged that
`orders-service` and `products-service` derived business identity from a
client-supplied `X-User-Id` header, not from the authenticated JWT
principal `JwtAuthenticationFilter` already places in
`SecurityContextHolder` on every authenticated request.

Confirmed before writing anything: both services' `JwtAuthenticationFilter`
already builds a `UsernamePasswordAuthenticationToken` whose principal is a
`JwtUserInfo` carrying `userId`, `email`, `role` (and, in orders-service,
`active`) straight from the cached `JwtCreatedEvent`. Nothing needed to be
added there. The gap was entirely on the read side: `OrderController`
(`createOrder`, `getOrder`, `getUserOrders`, `cancelOrder`) and
`ProductController` (`createProduct`, `getMyProducts`, `updateProduct`,
`deleteProduct`) read `@RequestHeader("X-User-Id")` instead, so any request
carrying a valid token for user A could act as user B simply by setting a
different header value. This invalidated IDOR protection on order lookups,
seller ownership checks on product writes, and the self-purchase rule added
the same day (it compared `X-User-Id` against `seller_id` — a check that
proves nothing if the header itself is untrustworthy).

Live reproduction before the fix: signed up two real buyers (A, B) against
a locally running `auth-service`/`orders-service` pair (real Postgres +
RabbitMQ), logged in as A, then called `POST /orders/createOrder` with
`Authorization: Bearer <A's token>` and `X-User-Id: <B's id>`. The order
was created under B's identity — full impersonation with a genuine,
unmodified JWT.

## Decision

**Derive identity exclusively from `@AuthenticationPrincipal`, never from a
header**, in both services:

- `orders-service`'s `OrderController`: all four endpoints now take
  `@AuthenticationPrincipal JwtAuthenticationFilter.JwtUserInfo principal`
  and call `principal.getUserId()` where the header used to be read.
- `products-service`'s `ProductController`: same pattern; since
  `Seller.userId`/`ProductService`'s `sellerId` parameter are `String` (not
  `Long`, as in orders-service), the four affected endpoints use
  `principal.getUserId().toString()`.
- No change to either `JwtAuthenticationFilter` or `JwtUserInfo` — the
  principal already carried everything needed.
- `frontend/src/lib/api/client.ts` no longer sends `X-User-Id` (it was
  dead weight once the backend stopped reading it); `Authorization` alone
  is sufficient.
- `e2e-tests`' `OrderLifecycleE2ETest`/`CatalogOnboardingE2ETest` no longer
  attach `X-User-Id` either — both already derived the buyer/seller id from
  the real signup response rather than an attacker-controlled value, so
  this was a cleanup, not a behavior change for those tests.

**No new endpoint, no JWT format change, no change to authentication
itself** — this only removes a second, untrusted identity channel that
existed alongside the real one.

## Verification

- TDD, red-green, both services:
  - New `OrderControllerAuthenticationTest`/`ProductControllerAuthenticationTest`
    (`@WebMvcTest` + `spring-security-test`'s
    `SecurityMockMvcRequestPostProcessors.authentication(...)`): each test
    authenticates as a real principal and sends a *divergent* `X-User-Id`
    header, then asserts (via `Mockito.verify`) that the service layer was
    called with the principal's id, never the header's. Confirmed red first
    — before the fix, 3 of 4 assertions in each suite failed with
    Mockito's "Argument(s) are different", and the 4th (products'
    `createProduct`) failed with a 500 caused by the header-driven call
    hitting an unstubbed mock — both are direct evidence of the
    vulnerability, not test artifacts.
  - Green after the controller changes: 4/4 in each new test class: 19/19
    orders-service, 10/10 products-service full suites, no regressions.
- Live re-verification of the exact reproduction above, after the fix:
  same two-buyer signup/login/spoofed-header flow against a real running
  `auth-service` + `orders-service` (Postgres/RabbitMQ via Docker Compose).
  The created order's `userId` matched the authenticated principal (A),
  not the spoofed header (B).
- Self-purchase prevention (`SelfPurchasePreventionTest`, added by the
  self-purchase Roadmap item earlier the same day) required no changes —
  it already operated on the `Long userId`/`String buyerId` values passed
  in from the controller; only the source of that value changed.

## Consequences

**Positive**: order/product identity checks (self-purchase, ownership,
IDOR protection on order lookups) are now backed by the actually
authenticated principal, closing the Critical Roadmap item this ADR
resolves. `X-User-Id` no longer exists as a concept anywhere in the
request path for these two services.

**Negative / known limitations**:
- `auth-service` was never in scope: it issues the JWT and has no
  `X-User-Id`-reading endpoints of its own.
- The broadcast-JWT-cache authentication model itself (in-memory token
  cache per service, wiped on restart) is unchanged; this ADR only removes
  a second, redundant identity channel that bypassed it.

## Update — 2026-07-10 (same day): extended to billing-service

`billing-service`'s `PaymentController` had the same class of gap this ADR
closed for orders/products-service, but worse: not a spoofable header, no
ownership check of *any* kind. `GET /api/payments` returned every payment
in the system; `GET /api/payments/{id}`/`GET /api/payments/order/{orderId}`
never confirmed the caller owned the payment; `DELETE /api/payments/{id}`
deleted any payment for anyone.

Fixed the same way, using the principal `billing-service`'s own
`JwtAuthenticationFilter` already populates (no changes needed there,
same as the original fix): `PaymentController` derives
`@AuthenticationPrincipal JwtUserInfo` and compares `payment.getUserId()`
against `principal.getUserId()`, 403 on a mismatch, on the three
single-payment endpoints; `GET /api/payments` now calls a new
`PaymentService.findAllForUser(userId)` (backed by a new
`PaymentRepository.findByUserId`) instead of the removed `findAll()`,
which returned everyone's payments and had no legitimate remaining
caller once the endpoint was scoped.

TDD, red-green: new `PaymentControllerOwnershipTest` (`@WebMvcTest` +
the real `SecurityConfig`/`JwtAuthenticationFilter`, matching the
existing `PaymentControllerSecurityTest`'s own style for this service —
a real cached token via `addValidToken`, not a security-context
postprocessor) — 7 tests covering owner/non-owner for all three
single-payment endpoints plus the list endpoint's scoping. Full suite:
20/20, no regressions. Live re-verification against a real running
`billing-service`: buyer A created a real order (billing-service's own
consumer creates a pending `Payment` from `order.created`); A's own
`GET`/`DELETE` calls succeeded, B's (a different authenticated buyer)
got 403 on all four endpoints, and A's `GET /api/payments` list
contained only A's own payment.

`processPayment`/`retryPayment`/`createPendingPayment` (the three
`POST` endpoints) were not touched — out of this update's scope, which
was limited to the four endpoints the Roadmap item named.

## References

- [docs/architecture/overview.md](../architecture/overview.md) - the
  broadcast-JWT-cache authentication section this ADR's fix operates
  within, not against.
- README Roadmap - the Critical item this ADR closes, and the High item
  the 2026-07-10 Update closes for billing-service.
