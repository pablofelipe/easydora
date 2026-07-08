# Sequence diagram — end-to-end business flow

Companion to [`docs/walkthrough.md`](walkthrough.md): the same flow (signup
through order, stock reservation, payment, and notification), as a Mermaid
sequence diagram. Every message shown here has a corresponding `curl` call
and expected response in the walkthrough — use that document to actually
run the flow; use this one to see the shape of it at a glance. For the
higher-level map (bounded contexts, persistence, the full exchange/event
table), see [docs/architecture/overview.md](architecture/overview.md).

RabbitMQ is drawn as its own participant (not a direct service-to-service
arrow) because that's what's actually happening: every cross-service event
in this system is an async publish/consume through a topic exchange, never
a direct call. The `Outbox (ADR-000x)` notes mark the only two services
(`auth-service`, `inventory-service`) where the publish itself is
asynchronous relative to the write that triggered it (a background poller,
not an in-request publish) — everywhere else, the publish happens
synchronously inside the same request that produced it ("write-then-publish",
best-effort, no outbox).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Auth as auth-service
    participant MQ as RabbitMQ
    participant Products as products-service
    participant Inventory as inventory-service
    participant Orders as orders-service
    participant Billing as billing-service
    participant Notif as notification-service

    rect rgb(235, 245, 255)
    Note over Client,Products: Seller onboarding
    Client->>Auth: POST /signup (role=SELLER)
    Auth-->>Client: 201 seller PENDING + verificationToken
    Auth->>MQ: publish user.registered (auth.exchange)
    Note right of Auth: Outbox (ADR-0003) - polled every 5s
    MQ->>Products: user.registered
    Products->>Products: create inactive Seller row

    Client->>Auth: GET /verify-email?token=...
    Auth-->>Client: 200 status=ACTIVE
    Auth->>MQ: publish user.verified (auth.exchange)
    Note right of Auth: Outbox (ADR-0003)
    MQ->>Products: user.verified
    Products->>Products: activate Seller (active=true)

    Client->>Auth: POST /login (seller credentials)
    Auth-->>Client: 200 JWT (SELLER_TOKEN)
    Auth->>MQ: publish jwt.created (auth.exchange)
    Note right of Auth: Outbox (ADR-0003)
    MQ->>Products: jwt.created
    Products->>Products: cache SELLER_TOKEN
    end

    rect rgb(235, 255, 240)
    Note over Client,Inventory: Product creation
    Client->>Products: POST /createProduct (Bearer SELLER_TOKEN)
    Products-->>Client: 201 product (active=true)
    Products->>MQ: publish product.created (product.exchange)
    MQ->>Inventory: product.created
    Inventory->>Inventory: create inventory row (quantity=initialStock)
    end

    rect rgb(235, 245, 255)
    Note over Client,Billing: Buyer onboarding (same signup/verify/login shape)
    Client->>Auth: POST /signup, GET /verify-email, POST /login (role=BUYER)
    Auth-->>Client: 200 JWT (BUYER_TOKEN)
    Auth->>MQ: publish jwt.created (auth.exchange)
    Note right of Auth: Outbox (ADR-0003)
    MQ->>Orders: jwt.created
    MQ->>Billing: jwt.created
    Orders->>Orders: cache BUYER_TOKEN + create Buyer if not exists
    Billing->>Billing: cache BUYER_TOKEN
    end

    rect rgb(255, 245, 235)
    Note over Client,Notif: Order creation -> stock reservation, payment, notification
    Client->>Orders: POST /createOrder (Bearer BUYER_TOKEN)
    Orders-->>Client: 201 order state=PROCESSING
    Orders->>MQ: publish OrderCreatedEvent (order.created, order.exchange)
    Orders->>MQ: publish ReserveStockCommand (stock.reserve, order.exchange)

    MQ->>Inventory: stock.reserve
    Inventory->>Inventory: reserve stock + write outbox row (same DB transaction)
    Inventory->>MQ: publish stock.reserved / stock.insufficient
    Note right of Inventory: Outbox (ADR-0007) - polled every 5s
    MQ->>Orders: stock.reserved
    Orders->>Orders: state machine -> INVENTORY_RESERVED
    Orders->>MQ: publish OrderStatusChangedEvent (order.status-changed, order.exchange)

    MQ->>Billing: order.created
    Billing->>Billing: create Payment (status=PENDING)

    MQ->>Notif: order.created
    Notif->>Auth: GET /users/{userId}/notification-profile
    Auth-->>Notif: profile data
    Notif->>Notif: persist notification row (SENT or FAILED)

    MQ->>Notif: order.status-changed
    Notif->>Notif: reuse this order's order.created notification's email/name
    Notif->>Notif: persist a second notification row (SENT or FAILED)
    end

    rect rgb(240, 235, 255)
    Note over Client,Notif: Payment processing -> order status change -> notification
    Client->>Billing: POST /api/payments/process (Bearer BUYER_TOKEN)
    Billing->>Billing: resolve Payment to APPROVED or FAILED (random simulation)
    Billing-->>Client: 200 Payment status=APPROVED|FAILED
    Billing->>MQ: publish PaymentEvent (payment.approved | payment.failed, order.exchange)

    MQ->>Orders: payment.approved | payment.failed
    Orders->>Orders: state machine -> PAYMENT_APPROVED | PAYMENT_FAILED
    Orders->>MQ: publish OrderStatusChangedEvent (order.status-changed, order.exchange)

    MQ->>Notif: order.status-changed
    Notif->>Notif: reuse this order's order.created notification's email/name
    Notif->>Notif: persist a third notification row (SENT or FAILED)
    end

    rect rgb(245, 245, 245)
    Note over Client,Notif: Final state validation (public APIs only)
    Client->>Orders: GET /{orderId} (Bearer BUYER_TOKEN)
    Orders-->>Client: state=PAYMENT_APPROVED|PAYMENT_FAILED
    Client->>Billing: GET /api/payments/order/{orderId} (Bearer BUYER_TOKEN)
    Billing-->>Client: Payment status=APPROVED|FAILED
    Client->>Notif: GET /notifications/{orderId}
    Notif-->>Client: [order.created, order.status-changed, order.status-changed]
    end
```

## Reading notes

- **`jwt.created` is bound to three queues** (products-service,
  orders-service, billing-service), each independent — this is the same
  pattern ADR-0001 fixed after finding two consumers competing on one
  shared queue. `auth-service` is the producer only; it never consumes its
  own broadcast (see
  [`docs/adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md`](adr/0015-billing-service-jwt-and-auth-securityconfig-fix.md)).
  `products-service`'s consumer (`UserEventConsumer.handleJwtCreated`)
  only caches the token when the event's role is `SELLER` — a `BUYER`'s
  `jwt.created` event reaches that queue but is logged and ignored, which
  is why the buyer flow above shows only `orders-service`/`billing-service`
  caching it. `orders-service` and `billing-service`'s consumers cache
  unconditionally, regardless of role.
- **`user.registered`/`user.verified` are also bound in `orders-service`**
  (`UserEventsConsumer`), not just `products-service` — this diagram omits
  those arrows in the seller-onboarding block because they have no
  observable effect on the documented flow (orders-service creates/activates
  its own generic `Buyer` row for the seller's user id too, which nothing
  in this walkthrough ever reads back), but they do fire. The event flow
  summary table in [`docs/walkthrough.md`](walkthrough.md) lists both
  consumers for accuracy even where this diagram simplifies.
- **`order.created` fans out to two consumers** (billing-service,
  notification-service), independent of the `stock.reserve` /
  `stock.reserved` round trip with inventory-service — a payment is created
  and a notification is persisted regardless of whether stock reservation
  later succeeds or fails. This is a deliberate current gap, not modeled as
  a fix here: `orders-service` does not currently compensate/cancel the
  `Payment` if the order later transitions to `INVENTORY_FAILED`.
- **Outbox pattern applies to exactly two services**, not universally:
  `auth-service` (ADR-0003) and `inventory-service` (ADR-0007). Everywhere
  else in this diagram (products-service, orders-service, billing-service),
  the publish is a direct, in-request, best-effort call — no transactional
  guarantee that the write and the publish both succeed or both fail
  together.
- **notification-service's call to `auth-service` is the one synchronous,
  direct HTTP call in this entire flow** — everything else crosses
  services exclusively through RabbitMQ. See
  [`docs/adr/0014-notification-service.md`](adr/0014-notification-service.md).
- **`order.status-changed` is consumed the same way `order.created` is**
  (its destination was decided in
  [`docs/adr/0001-messaging-wiring-audit.md`](adr/0001-messaging-wiring-audit.md)'s
  Update, implemented here), but it carries no `userId` of its own — unlike
  `OrderCreatedEvent`. `notification-service` resolves this by reusing the
  email/name already captured in that same order's `order.created`
  notification row (its own schema, no second synchronous call, no
  cross-schema access) rather than calling `auth-service` a second time. A
  status change never replaces an earlier notification — every event
  produces its own new row, so an order can have any number of
  notifications over its lifetime, oldest first.
- `notification-service`'s notification message content is implemented
  directly in code, not as an externalized template — deliberately, to
  keep this stage simple and readable rather than runtime-configurable.
- **The payment outcome closes the loop the same way the stock outcome
  does**: `billing-service`'s `POST /api/payments/process`
  (`PaymentService.processPayment`, a random 90%-approval simulation)
  now publishes `payment.approved`/`payment.failed` once it resolves,
  and `orders-service`'s `PaymentEventsConsumer` reacts by calling
  `OrderService.handlePaymentReceived`/`handlePaymentFailed` — the same
  state-machine methods that existed before this diagram's payment block
  was added, previously unreachable (removed as dead, incorrectly-typed
  code in [ADR-0001](adr/0001-messaging-wiring-audit.md), finding 5;
  wired up correctly here). `notification-service` required zero changes
  to react to the resulting `order.status-changed` — it already consumes
  that routing key regardless of which service's state transition
  produced it. `POST /api/payments/{orderId}/retry` (resets a `FAILED`
  payment back to `PENDING`) is not part of the documented flow and still
  doesn't appear in this diagram.
