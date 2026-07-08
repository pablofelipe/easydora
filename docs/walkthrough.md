# End-to-end walkthrough

A linear, reproducible way to run the whole business flow from a clean
checkout using only Docker Compose, `curl`, and the services' public HTTP
APIs — no Postman, no external scripts, no GUI tools. Every command below
was actually executed against a real, freshly-started stack; every response
shown is a real response from that run (IDs, tokens, and timestamps will
differ on your own run).

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
curl -s -X POST http://localhost:8081/signup \
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
curl -s "http://localhost:8081/verify-email?token=<verificationToken>"
```

Expected: `{"message":"Email verified successfully","status":"ACTIVE"}`.
This makes `auth-service` publish `user.verified`, which `products-service`
consumes to flip the `Seller` row's `active` flag to `true`.

Log in:

```bash
curl -s -X POST http://localhost:8081/login \
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
curl -s -X POST http://localhost:8082/createProduct \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "X-User-Id: 8" \
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
curl -s http://localhost:8083/inventory/$PRODUCT_ID
```

Expected: `{"productId":"...","quantity":10,"reserved":0,"available":true,...}`.

## 5. Create a buyer and authenticate

Same three calls as step 3, with a different email and `"role":"BUYER"`:

```bash
curl -s -X POST http://localhost:8081/signup -H "Content-Type: application/json" \
  -d '{"email":"buyer-demo@example.com","password":"Sup3rSecret","firstName":"Bea","lastName":"Buyer","role":"BUYER"}'

curl -s "http://localhost:8081/verify-email?token=<verificationToken>"

curl -s -X POST http://localhost:8081/login -H "Content-Type: application/json" \
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
curl -s -X POST http://localhost:8084/createOrder \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "X-User-Id: 9" \
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
curl -s http://localhost:8084/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN" -H "X-User-Id: 9"
```

Expected: `"state":"INVENTORY_RESERVED"` (real example confirmed).

```bash
curl -s http://localhost:8083/inventory/$PRODUCT_ID
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
curl -s http://localhost:8085/api/payments/order/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
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
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8085/api/payments/order/$ORDER_ID
# expected: 403
```

## 9. Validate the notification (optional Postgres check)

**Event published**: the same `order.created` from step 8.
**Who consumes it**: `notification-service`.
**Observable effect**: `notification-service` calls `auth-service`'s
`GET /users/{id}/notification-profile` to enrich the event, then persists
one row describing the outcome.
**How to confirm it**: `notification-service` has no public API beyond
`/health` — its only observable effect at this stage is the persisted row
itself (a deliberate design choice, see `docs/adr/0014-notification-service.md`).
This is the one validation in this walkthrough that requires direct
Postgres access, and it's optional (skip it if you're only interested in
the public-API-visible parts of the flow):

```bash
docker exec easydora-postgres-1 psql -U admin -d easydora -c \
  "SELECT event_type, aggregate_id, status, payload FROM notification_schema.notifications WHERE aggregate_id = '$ORDER_ID';"
```

Expected (real example):
```
 event_type   |             aggregate_id             | status |  payload
--------------+--------------------------------------+--------+------------
order.created | fee3fef1-49ca-4be3-a107-b73f316a7396  | SENT   | {"email": "buyer-demo@example.com", "userId": 9, ...}
```

`status` is `SENT` when the profile lookup succeeds, `FAILED` (with an
`error` field in the payload) if it doesn't — either way, exactly one row
is produced per order, never zero.

## 10. Final state

At this point you have, driven entirely by 6 HTTP calls plus 4 read-only
checks:
- 1 verified seller, 1 active product, 1 verified buyer
- 1 order in `INVENTORY_RESERVED`
- 1 `Payment` in `PENDING`
- 1 persisted notification in `SENT` status

```bash
curl -s http://localhost:8084/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN" -H "X-User-Id: 9"
curl -s http://localhost:8085/api/payments/order/$ORDER_ID -H "Authorization: Bearer $BUYER_TOKEN"
```

Both calls succeeding with the states above is the end-to-end success
criterion for this walkthrough.

## Event flow summary

| Step | Event | Producer | Consumer | Observable effect |
|---|---|---|---|---|
| Signup | `user.registered` | auth-service | products-service, orders-service | inactive Seller/Buyer row |
| Verify email | `user.verified` | auth-service | products-service | Seller activated |
| Login | `jwt.created` | auth-service | products-service, orders-service, billing-service | token cached; Buyer created (orders-service) |
| Create product | `product.created` | products-service | inventory-service | inventory row created |
| Create order | `stock.reserve` / `stock.reserved` | orders-service / inventory-service | inventory-service / orders-service | order → `INVENTORY_RESERVED`, stock reserved |
| Create order | `order.created` | orders-service | billing-service, notification-service | Payment created; notification persisted |

## Troubleshooting

- **A step returns `403`**: the Bearer token wasn't cached yet by that
  service — wait a second and retry, or re-check you're using the token
  from the right login.
- **Order stuck in `PROCESSING`**: `docker compose logs orders-service inventory-service` — look for the `ReserveStockCommand`/`stock.reserved` exchange.
- **Any service unhealthy**: `docker compose logs <service>`; confirm
  Postgres/RabbitMQ are healthy first, since every service depends on both.
- **Tear down and start over**: `docker compose down -v` removes containers
  and volumes for a genuinely fresh run (a plain `down` keeps the data).
