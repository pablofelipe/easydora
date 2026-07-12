# End-to-end walkthrough

A linear, reproducible way to run the whole business flow from a clean
checkout using only Docker Compose, `curl`, and the services' public HTTP
APIs — no external scripts, no GUI tools. Every command below was actually
executed against a real, freshly-started stack; every response shown is a
real response from that run (IDs, tokens, and timestamps will differ on
your own run).

For the same flow as a diagram, see
[docs/sequence-diagram.md](sequence-diagram.md). For the high-level map
this flow fits into (services, communication, persistence), see
[docs/architecture/overview.md](architecture/overview.md). For the same
flow as an importable, runnable collection (automatic ID/token capture
instead of shell variables), see [postman/](../postman/).

This walkthrough goes through the API Gateway (port 8080) as the primary
entry point — every call below uses the same self-namespaced segment the
Gateway forwards unchanged (`/auth`, `/products`, `/orders`, `/billing`,
`/inventory`; see [ADR-0025](adr/0025-gateway-transparent-routing.md)).
`notification-service` also has a Gateway route now
(`/notification/notifications/{orderId}`, see
[ADR-0026](adr/0026-frontend-thin-client.md)) — step 9 (and every later
reference to it) keeps calling it directly on port 8086 instead, since
that's what this walkthrough has always validated and nothing about that
call changed.

## Prerequisites

- Docker Desktop (Windows/Mac) or Docker Engine (Linux) + Docker Compose
- `curl`
- A `.env` file at the repo root with `DB_PASSWORD`, `RABBITMQ_PASSWORD`,
  `JWT_SECRET`, `APP_JWT_SECRET` set (see ADR-0005) — required for
  `docker compose up` to start at all

## 1. Start everything

```bash
git clone <repo-url>
cd easydora
docker compose up -d --build
```

One command brings up Postgres, RabbitMQ, and all seven implemented
services (api-gateway, auth, products, inventory, orders, billing,
notification).

## 2. Confirm every service is healthy

```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

**Success criterion**: all 9 rows (2 infra + 7 services) show `healthy`.
This can take 1-2 minutes on first boot (Java services take longer than
Go/Python to start). If a service takes longer than that, check its logs:

```bash
docker compose logs -f <service-name>
```

> **Known race, self-healing**: `notification-service`'s RabbitMQ consumer
> may log a connection failure on the very first attempt if RabbitMQ isn't
> quite ready yet, then recover on its own a few seconds later (`RabbitMQ
> connection lost or unavailable; retrying in 5s`). This is expected and
> requires no action — the container still reports `healthy` throughout,
> since `/health` and the RabbitMQ consumer are independent.

## 3. Create a seller and authenticate

```bash
curl -s -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"seller-demo@example.com","password":"Sup3rSecret","firstName":"Sam","lastName":"Seller","role":"SELLER"}'
```

Response (real example):
```json
{"id":8,"email":"seller-demo@example.com","firstName":"Sam","lastName":"Seller","role":"SELLER","status":"PENDING","createdAt":"2026-07-08T02:11:02.556839","verificationToken":"eyJhbGc...","verificationUrl":"/auth/verify-email?token=eyJhbGc..."}
```

**What happened**: `auth-service` published a real `UserRegisteredEvent` on
`auth.exchange`/`user.registered`. `products-service` consumed it and
created an inactive `Seller` row (role `SELLER`, `active=false`) — you
cannot create a product yet.

Verify the email (use the exact `verificationToken` from your own response):

```bash
curl -s "http://localhost:8080/auth/verify-email?token=<verificationToken>"
```

Expected: `{"message":"Email verified successfully","status":"ACTIVE"}`.
This makes `auth-service` publish `user.verified`, which `products-service`
consumes to flip the `Seller` row's `active` flag to `true`.

Log in:

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"seller-demo@example.com","password":"Sup3rSecret"}'
```

Response (real example, trimmed):
```json
{"token":"eyJhbGc...","type":"Bearer","userId":8,"email":"seller-demo@example.com","role":"SELLER","expiresAt":"2026-07-09T02:11:23.911771779"}
```

**What happened**: `auth-service` published a real `JwtCreatedEvent` on
`auth.exchange`/`jwt.created`. `products-service` consumed it and cached
this token — it's now usable as a Bearer credential against
`products-service`. Save it:

```bash
SELLER_TOKEN=<token from the response above>
```

**Validation point**: without this step, `POST /createProduct` below would
return `403` (no cached token yet).

## 4. Create a product

```bash
curl -s -X POST http://localhost:8080/products/createProduct \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -d '{"name":"Mechanical Keyboard","description":"Hot-swappable, brown switches","price":249.90,"initialStock":10}'
```

Response (real example):
```json
{"id":"a07382af-0fff-4ba7-bbb6-1bd07da4973e","name":"Mechanical Keyboard","description":"Hot-swappable, brown switches","price":249.90,"seller":{"userId":"8","name":"Sam Seller","avatarUrl":null},"active":true,"createdAt":"2026-07-08T02:11:26.159547754","updatedAt":null}
```

Save the product id: `PRODUCT_ID=<id from the response above>`.

**Event published**: `products-service` publishes `ProductCreatedEvent` on
`product.exchange`/`product.created`.
**Who consumes it**: `inventory-service`.
**Observable effect**: a new inventory row for this product, with
`quantity` equal to the `initialStock` you sent.
**How to confirm it** (public API):

```bash
curl -s http://localhost:8080/inventory/$PRODUCT_ID
```

Expected: `{"productId":"...","quantity":10,"reserved":0,"available":true,...}`.

## 5. Create a buyer and authenticate

Same three calls as step 3, with a different email and `"role":"BUYER"`:

```bash
curl -s -X POST http://localhost:8080/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"buyer-demo@example.com","password":"Sup3rSecret","firstName":"Bea","lastName":"Buyer","role":"BUYER"}'

curl -s "http://localhost:8080/auth/verify-email?token=<verificationToken>"

curl -s -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" \
  -d '{"email":"buyer-demo@example.com","password":"Sup3rSecret"}'
```

Save `BUYER_TOKEN` and note the buyer's `userId` (real example: `9`).

**What happened this time**: the same `jwt.created` broadcast is also
consumed by `orders-service` and `billing-service`, each caching the token
independently — and `orders-service` additionally creates a local `Buyer`
record for this user. This is why no separate "register as buyer with
orders-service" step exists.

## 6. Create an order

```bash
curl -s -X POST http://localhost:8080/orders/createOrder \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -d "{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":2,\"unitPrice\":249.90}]}"
```

Response (real example):
```json
{"id":"fee3fef1-49ca-4be3-a107-b73f316a7396","userId":9,"totalAmount":499.80,"state":"PROCESSING","items":[...],"createdAt":"...","updatedAt":"..."}
```

Save `ORDER_ID=<id from the response above>`. The state is `PROCESSING` —
stock reservation happens asynchronously over RabbitMQ, so it hasn't
resolved yet.

## 7. Validate the stock reservation

**Event published**: `orders-service` publishes `ReserveStockCommand` on
`order.exchange`/`stock.reserve`.
**Who consumes it**: `inventory-service`, which publishes `stock.reserved`
(or `stock.insufficient`) back on the same exchange; `orders-service`
consumes that outcome and drives its state machine.
**Observable effect**: the order's `state` becomes `INVENTORY_RESERVED`,
and the product's `reserved` count goes up by the ordered quantity.
**How to confirm it** (public APIs, wait a couple of seconds first):

```bash
curl -s http://localhost:8080/orders/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

Expected: `"state":"INVENTORY_RESERVED"` (real example confirmed).

```bash
curl -s http://localhost:8080/inventory/$PRODUCT_ID
```

Expected: `"quantity":10,"reserved":2` (real example confirmed).

**Success criterion**: if `state` is still `PROCESSING` after a few
seconds, something is wrong — check `docker compose logs orders-service
inventory-service`. If it becomes `INVENTORY_FAILED` instead, the product
didn't have enough stock left.

## 8. Validate the payment

**Event published**: `orders-service` publishes `OrderCreatedEvent` on
`order.exchange`/`order.created` (the same call in step 6 that triggered
the reservation above also does this).
**Who consumes it**: `billing-service`.
**Observable effect**: a new `Payment` record with `status:"PENDING"`.
**How to confirm it** (public API):

```bash
curl -s http://localhost:8080/billing/api/payments/order/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

Response (real example):
```json
{"id":11,"orderId":"fee3fef1-49ca-4be3-a107-b73f316a7396","userId":9,"amount":499.80,"status":"PENDING","transactionId":"35c81470-3335-4b0b-b51d-537e22951362","failureReason":null,"createdAt":"2026-07-08T02:11:55.06837","processedAt":null}
```

`billing-service` authenticates via the same JWT broadcast cache as
`orders-service`/`products-service` — the buyer's own token from step 5
works here too, with no separate billing-specific login. Calling this
endpoint without a token confirms the other direction:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/billing/api/payments/order/$ORDER_ID
# expected: 403
```

## 9. Validate the notifications

**Events published**: `order.created` (step 8) and `order.status-changed`
(step 7's automatic transition to `INVENTORY_RESERVED` also publishes this
— see `orders-service`'s `publishOrderStatusChanged`).
**Who consumes them**: `notification-service`, for both.
**Observable effect**: one persisted notification row per event — never
replacing a previous one, so an order accumulates a new row each time a
relevant event fires. For `order.created`, `notification-service` calls
`auth-service`'s `GET /auth/users/{id}/notification-profile` directly
(port 8081, not through the Gateway — this is the one synchronous
inter-service HTTP call in the system) to enrich the event.
`order.status-changed` carries no `userId` of its own, so it
reuses the email/name already captured by that same order's
`order.created` notification instead of a second enrichment call.
**How to confirm it** (requires the buyer's own token -- see ADR-0028):

```bash
curl -s http://localhost:8086/notifications/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

Response (real example):
```json
[
  {"eventType":"order.created","status":"SENT","payload":{"email":"buyer-demo@example.com","userId":9,"firstName":"Bea","lastName":"Buyer","totalAmount":499.80},"createdAt":"2026-07-08T17:19:11.489475+00:00"},
  {"eventType":"order.status-changed","status":"SENT","payload":{"email":"buyer-demo@example.com","userId":9,"firstName":"Bea","lastName":"Buyer","previousState":"PROCESSING","newState":"INVENTORY_RESERVED"},"createdAt":"2026-07-08T17:19:13.691928+00:00"}
]
```

`status` is `SENT` when enrichment succeeds, `FAILED` (with an `error`
field in the payload) if it doesn't — either way, exactly one row is
produced per event, never zero, and never overwriting an earlier row. A
`404` means no notification exists yet for that order id.

`notification-service`'s message content (what goes into each
notification's `payload`) is implemented directly in code
(`app/consumer.py`), not as an externalized template — the project
prioritizes simplicity and readability over a runtime-configurable
templating mechanism it has no present need for.

## 10. Process the payment

```bash
curl -s -X POST "http://localhost:8080/billing/api/payments/process?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $BUYER_TOKEN"
```

Response (real example):
```json
{"id":30,"orderId":"d35207ec-b9bd-44db-b62e-2aa31c5f8e68","userId":26,"amount":499.80,"status":"FAILED","transactionId":"35c81470-3335-4b0b-b51d-537e22951362","failureReason":"Odd amount rejected by the mock policy","createdAt":"2026-07-08T17:59:35.022814","processedAt":"2026-07-08T17:59:42.000457217"}
```

This endpoint (`PaymentService.processPayment`) now delegates the
approval decision entirely to `PaymentProvider` (see
[ADR-0030](adr/0030-deterministic-payment-provider.md)) -- no
`Math.random()` anywhere in the domain. The mock provider's rule is
simple and stable: an order whose total amount is evenly divisible by 2
is `APPROVED`, otherwise `FAILED`. `$499.80` (2 × `$249.90` from step 6)
is not evenly divisible by 2, so this call is **always** `FAILED` for
this walkthrough's numbers -- not "may fail," will fail, every time you
run this exact flow. Calling it again on the same order repeats the
identical result (same `transactionId`, same `failureReason`), since the
amount driving the decision hasn't changed. To see the `APPROVED` branch
instead, create an order whose total is an even whole-dollar amount (e.g.
2 units at `$250.00`).

Note there is no `amount` parameter on this call anymore (see
[ADR-0031](adr/0031-single-source-of-truth-for-payment-creation.md)):
`amount` is read from the `Payment` row already created when
`order.created` was consumed in step 8, the only place a `Payment` is
ever created. Calling this endpoint for an `orderId` with no such row
(e.g. a made-up id, or an order that was never actually created) now
returns `404 Not Found` instead of silently creating an incomplete
payment record.

**Event published**: `billing-service` publishes a `PaymentEvent` on
`order.exchange`/`payment.approved` (or `payment.failed`) once the
outcome resolves.
**Who consumes it**: `orders-service`.
**Observable effect**: the order's state moves to `PAYMENT_APPROVED` (or
`PAYMENT_FAILED`, which also releases the reserved stock) — the same
`OrderService.handlePaymentReceived`/`handlePaymentFailed` state-machine
transitions that existed before this event was wired up to call them
(see [ADR-0021](adr/0021-payment-outcome-integration.md)). `orders-service`
then publishes `order.status-changed` through the exact same path step 7's
stock reservation already used, so `notification-service` reacts with no
payment-specific changes at all.
**How to confirm it** (public APIs):

```bash
curl -s http://localhost:8080/orders/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

Expected: `"state":"PAYMENT_FAILED"` -- deterministic for this walkthrough's
numbers, not a hedge.

```bash
curl -s http://localhost:8086/notifications/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

Response (real example, captured before the payment provider became
deterministic -- the notification shape itself is unaffected by that
change, only the terminal `newState` value would now read
`PAYMENT_FAILED` instead of `PAYMENT_APPROVED` for this walkthrough's
exact numbers):
```json
[
  {"eventType":"order.created","status":"SENT","payload":{"email":"buyer-e19b-1783533570@example.com","userId":26,"firstName":"Bea","lastName":"Buyer","totalAmount":15.0},"createdAt":"2026-07-08T17:59:35.034452+00:00"},
  {"eventType":"order.status-changed","status":"SENT","payload":{"email":"buyer-e19b-1783533570@example.com","userId":26,"firstName":"Bea","lastName":"Buyer","previousState":"PROCESSING","newState":"INVENTORY_RESERVED"},"createdAt":"2026-07-08T17:59:38.422602+00:00"},
  {"eventType":"order.status-changed","status":"SENT","payload":{"email":"buyer-e19b-1783533570@example.com","userId":26,"firstName":"Bea","lastName":"Buyer","previousState":"INVENTORY_RESERVED","newState":"PAYMENT_APPROVED"},"createdAt":"2026-07-08T17:59:42.091826+00:00"}
]
```

Three notifications now exist for this order — one per relevant event, in
order. This is the same read-only endpoint step 9 already used; nothing
about it changed to support the payment outcome (`notification-service`
already treated `newState` as an opaque string with no per-value branch).

## 11. Final state

At this point you have, driven entirely by public HTTP APIs:
- 1 verified seller, 1 active product, 1 verified buyer
- 1 order in `PAYMENT_FAILED` for this walkthrough's numbers (deterministic
  -- see step 10; use an even whole-dollar total instead to land in
  `PAYMENT_APPROVED`)
- 1 `Payment` in `FAILED` (or `APPROVED`, for an even-amount order)
- 3 persisted notifications (`order.created`, and two `order.status-changed`), all `SENT`

```bash
curl -s http://localhost:8080/orders/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
curl -s http://localhost:8080/billing/api/payments/order/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
curl -s http://localhost:8086/notifications/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

All three calls succeeding with the states above is the end-to-end success
criterion for this walkthrough.

## 12. Ship and deliver the order (fulfillment)

The commands and responses in this step are real, but come from a
separate verification run rather than a continuation of the exact order
from steps 1–11 above (a different `$ORDER_ID`/`$BUYER_TOKEN`) — the
order there was already fast-forwarded to `PAYMENT_APPROVED` directly
(steps 1–10 demonstrate the real checkout/stock-reservation/payment path
itself, but land on `PAYMENT_FAILED` for this walkthrough's specific
numbers -- see step 10). See
[ADR-0029](adr/0029-order-fulfillment-lifecycle.md).

Shipping is a platform-operations action (role `ADMIN`), not a buyer or
seller action — an order can contain products from more than one seller,
so no single seller unambiguously owns "the" order. The one operations
account is bootstrapped at auth-service startup from the `ADMIN_EMAIL`/
`ADMIN_PASSWORD` environment variables (never through `/auth/signup`,
which now rejects `role: "ADMIN"` outright):

```bash
curl -s -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" \
  -d '{"email":"<ADMIN_EMAIL>","password":"<ADMIN_PASSWORD>"}'
```

Save `ADMIN_TOKEN` from the response, same shape as any other login.

```bash
curl -s http://localhost:8080/orders/fulfillment -H "Authorization: Bearer $ADMIN_TOKEN"
```

Response (real example — every order currently waiting to be shipped,
across every buyer):
```json
[{"id":"test-order-fulfillment-1","userId":24,"totalAmount":42.50,"state":"PAYMENT_APPROVED","items":[...],"createdAt":"...","updatedAt":"..."}]
```

The same call with a `BUYER_TOKEN` instead returns `403` — this endpoint
is role-gated, not ownership-gated.

```bash
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/ship -H "Authorization: Bearer $ADMIN_TOKEN"
```

Response (real example):
```json
{"id":"test-order-fulfillment-2","userId":24,"totalAmount":15.00,"state":"SHIPPED","items":[],"createdAt":"2026-07-11T12:28:09.595788Z","updatedAt":"2026-07-11T12:28:10.934621100Z"}
```

**Event published**: the same `order.status-changed` used by every other
transition in this walkthrough — `previousState":"PAYMENT_APPROVED"`,
`"newState":"SHIPPED"`. No new event type. Calling `/orders/fulfillment`
again with `$ADMIN_TOKEN` no longer lists this order.

The buyer — not the operations account — confirms delivery, the same
ownership check `cancelOrder` already uses:

```bash
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/deliver -H "Authorization: Bearer $BUYER_TOKEN"
```

Response (real example):
```json
{"id":"test-order-fulfillment-2","userId":24,"totalAmount":15.00,"state":"DELIVERED","items":[],"createdAt":"2026-07-11T12:28:09.595788Z","updatedAt":"2026-07-11T12:28:11.089714800Z"}
```

Attempting `/deliver` before the order was shipped (real example, tried
against a `PAYMENT_APPROVED` order) fails clean with the real state in the
message, not a generic error:
```json
{"error":"Bad Request","message":"Cannot deliver order in state: PAYMENT_APPROVED","timestamp":"2026-07-11T12:22:13.335839200Z","status":400}
```

## Payment compensation (a stray approval after cancellation)

`POST /billing/api/payments/process` never checks the order's current
state before approving a charge (`billing-service` is the sole owner of
`Payment`'s lifecycle, and this project deliberately avoids synchronous
cross-service calls) — so it can be called for an order that has already
been cancelled. This is deterministically reproducible via pure `curl`,
not a timing race: cancel an order right after it reaches
`INVENTORY_RESERVED`, then call `/process` anyway.

```bash
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/cancel -H "Authorization: Bearer $BUYER_TOKEN"
# order.state is now CANCELLED, stock released

curl -s -X POST "http://localhost:8080/billing/api/payments/process?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $BUYER_TOKEN"
# Payment becomes APPROVED (or FAILED, by the same amount-parity rule as
# step 10) regardless of the order's CANCELLED state
```

If the payment resolves to `APPROVED`, `orders-service` detects the stray
approval the moment its own `PAYMENT_RECEIVED` transition is rejected
(no such transition exists from `CANCELLED`), moves the order to
`REFUNDING`, and publishes a `RefundPaymentCommand` (a command, not a
fact-event) on `payment.refund.requested`. `billing-service` alone decides
the outcome and publishes `payment.refunded`/`payment.refund.failed` back;
`orders-service` closes the loop into `REFUNDED`/`REFUND_FAILED`:

```bash
curl -s http://localhost:8080/orders/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```
```json
{"id":"...","state":"REFUNDED", ...}
```

The same mechanism handles a stray approval after `INVENTORY_FAILED`, via
the identical guard. No new screen, no new user-facing action — this is a
system-driven compensation, not a feature a buyer requests. See
[ADR-0034](adr/0034-payment-compensation-saga.md) for the
full design (including why compensation is not modeled as a first-class
refund feature) and a real trace of this exact sequence, captured with a
shared `CorrelationId` across both services' logs.

## Tracing this whole flow with one CorrelationId

Every `curl` call above can carry an `X-Correlation-Id` header. Add the
same value to every call in this walkthrough (step 3 onward) and every
service's logs — and every RabbitMQ message published along the way —
carry that exact same id, letting you grep one identifier across all
five services this flow touches instead of correlating by order id and
timestamps:

```bash
curl -s -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: my-walkthrough-trace" \
  -d '{"email":"seller-demo@example.com", ...}'
```

If you don't send one, each service generates its own on first contact
and echoes it back as a response header (`X-Correlation-Id`) — so it's
always visible, whether you supplied it or not. A `X-Request-Id` header
is also always present, but is regenerated fresh at every hop (it
identifies one HTTP request, not the whole operation) — it's
`X-Correlation-Id` that stays constant end to end. See
[docs/architecture/observability.md](architecture/observability.md) for
the full design, including a worked example from a real run of this
exact flow.

## Event flow summary

| Step | Event | Producer | Consumer | Observable effect |
|---|---|---|---|---|
| Signup | `user.registered` | auth-service | products-service (role=SELLER only), orders-service (either role) | inactive Seller row (products-service, SELLER only); inactive generic Buyer row (orders-service, either role) |
| Verify email | `user.verified` | auth-service | products-service (role=SELLER only), orders-service (either role) | Seller row activated (products-service); the same user's generic Buyer row is also activated in orders-service, with no observable effect on this flow |
| Login | `jwt.created` | auth-service | products-service (role=SELLER only), orders-service (either role), billing-service (either role) | token cached; Buyer created/updated (orders-service, either role) |
| Create product | `product.created` | products-service | inventory-service | inventory row created |
| Create order | `stock.reserve` / `stock.reserved` | orders-service / inventory-service | inventory-service / orders-service | order → `INVENTORY_RESERVED`, stock reserved |
| Create order | `order.created` | orders-service | billing-service, notification-service | Payment created; notification persisted |
| Stock reserved | `order.status-changed` | orders-service | notification-service | second notification persisted (`PROCESSING` → `INVENTORY_RESERVED`), reusing the `order.created` notification's enriched user info |
| Process payment | `payment.approved` / `payment.failed` | billing-service | orders-service | order → `PAYMENT_APPROVED`/`PAYMENT_FAILED` |
| Process payment | `order.status-changed` | orders-service | notification-service | third notification persisted (`INVENTORY_RESERVED` → `PAYMENT_APPROVED`/`PAYMENT_FAILED`) |
| Ship (`ADMIN` role) | `order.status-changed` | orders-service | notification-service | order → `SHIPPED`; notification persisted (`PAYMENT_APPROVED` → `SHIPPED`) |
| Confirm delivery (buyer) | `order.status-changed` | orders-service | notification-service | order → `DELIVERED`; notification persisted (`SHIPPED` → `DELIVERED`) |

## Troubleshooting

- **A step returns `403`**: the Bearer token wasn't cached yet by that
  service — wait a second and retry, or re-check you're using the token
  from the right login.
- **Order stuck in `PROCESSING`**: `docker compose logs orders-service inventory-service` — look for the `ReserveStockCommand`/`stock.reserved` exchange.
- **Any service unhealthy**: `docker compose logs <service>`; confirm
  Postgres/RabbitMQ are healthy first, since every service depends on both.
- **Tear down and start over**: `docker compose down -v` removes containers
  and volumes for a genuinely fresh run (a plain `down` keeps the data).
