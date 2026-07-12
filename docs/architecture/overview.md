# Architecture Overview

EasyDora is a distributed e-commerce system composed of seven
independently deployable backend services plus a thin-client frontend,
communicating almost entirely through asynchronous events over a single
message broker.
This document is the map: what each service owns, how they talk to each
other, how data is persisted, and where to go for more depth on any of it.

## Bounded Contexts

| Service | Responsibility (domain) | Publishes | Consumes |
|---|---|---|---|
| **auth-service** | Identity: accounts, credentials, JWT issuance | `user.registered`, `user.verified`, `jwt.created` | — |
| **products-service** | Catalog: sellers and products | `product.created`, `product.updated`, `product.deleted` | `user.registered`, `user.verified`, `jwt.created` (role `SELLER` only) |
| **inventory-service** | Stock: quantity and reservation per product | `stock.reserved`, `stock.insufficient` | `product.created`/`updated`/`deleted`, `stock.reserve`, `stock.release` |
| **orders-service** | Order lifecycle: a state machine from creation to delivery, cancellation, or payment compensation | `order.created`, `order.status-changed`, `stock.reserve`, `stock.release`, `payment.refund.requested` (a command, not a fact-event) | `user.registered`, `user.verified`, `jwt.created`, `stock.reserved`, `stock.insufficient`, `payment.approved`, `payment.failed`, `payment.refunded`, `payment.refund.failed`, `product.created` |
| **billing-service** | Payment: simulated processing per order, plus compensation | `payment.approved`, `payment.failed`, `payment.refunded`, `payment.refund.failed` | `jwt.created`, `order.created`, `payment.refund.requested` |
| **notification-service** | Notification: one record per order event | — | `order.created`, `order.status-changed`, `jwt.created` |
| **api-gateway** | Edge: routing and circuit breaking — not a domain context | — | — |
| **frontend** | Thin client: browses and drives the business flow through the Gateway, no business logic of its own | — | — |

`notification-service` is the only backend service not on the JVM/Go split
above (Python/FastAPI) — not a language-diversity showcase, but a
deliberate choice of *which* service to prove technological heterogeneity
on: it has no financial correctness invariant, no lifecycle/state machine
to keep consistent, and the smallest synchronous surface (one outbound call)
of any service here, making it the safest candidate to validate that this
architecture's event-driven, contract-based design allows a real language
swap without coupling to a specific framework. See
[ADR-0014](../adr/0014-notification-service.md)'s 2026-07-12 Update.

## Business Flows

- **User onboarding** — signup, email verification, and login, driven
  entirely by auth-service; every downstream service builds its own view
  of the user from the same three broadcast events. See
  [walkthrough](../walkthrough.md) and the
  [sequence diagram](../sequence-diagram.md).
- **Product management** — a verified seller creates a product against
  products-service; inventory-service reacts by creating its own stock
  record, with no separate provisioning step. See the
  [walkthrough](../walkthrough.md).
- **Order → Inventory → Payment → Notification** — creating an order
  triggers, from the same two events, a stock-reservation round trip that
  drives the order's state machine, a payment record in billing-service,
  and a notification record in notification-service. Every subsequent
  order state transition (`order.status-changed`) produces its own
  additional notification record, queryable via notification-service's
  `GET /notifications/{orderId}` — never replacing an earlier one. The
  payment outcome itself closes this loop the same way: once
  billing-service resolves a payment to `APPROVED`/`FAILED`, it publishes
  `payment.approved`/`payment.failed`; orders-service consumes it, drives
  the same state machine into `PAYMENT_APPROVED`/`PAYMENT_FAILED`, and
  publishes `order.status-changed` through the same path stock reservation
  already used — notification-service needs no payment-domain knowledge at
  all to react to it. See the [walkthrough](../walkthrough.md) and the
  [sequence diagram](../sequence-diagram.md).
- **Fulfillment (ship → deliver)** — the same state machine continues past
  `PAYMENT_APPROVED`: a platform-operations account (role `ADMIN`, the
  first role-gated rather than ownership-gated action in this project)
  marks an order `SHIPPED` (`POST /orders/{orderId}/ship`), and the
  order's own buyer confirms `DELIVERED` (`POST /orders/{orderId}/deliver`,
  ownership-gated like cancellation). Both transitions reuse
  `order.status-changed` unchanged — no new event type, no new consumer
  code in notification-service. See
  [ADR-0029](../adr/0029-order-fulfillment-lifecycle.md).
- **Payment compensation** — a `payment.approved` can legitimately arrive
  for an order that already reached `INVENTORY_FAILED`/`CANCELLED` (Billing
  never checks an order's current state before approving a charge, by
  design). orders-service detects this the moment its own
  `PAYMENT_RECEIVED` transition is rejected, moves the order to `REFUNDING`,
  and publishes `payment.refund.requested` — a command, not a fact-event,
  the same distinction `stock.reserve` already draws with inventory-service.
  billing-service alone decides the outcome (it never lets Orders touch
  `Payment` directly) and publishes `payment.refunded`/
  `payment.refund.failed` back; orders-service closes the loop into
  `REFUNDED`/`REFUND_FAILED`, riding the same `order.status-changed`
  notification-service already reacts to generically — no new consumer code
  there either. See [ADR-0034](../adr/0034-payment-compensation-saga.md).

## Communication

- RabbitMQ is the **only** message broker in the system (see
  [ADR-0007](../adr/0007-remove-kafka-broker.md)).
- Services communicate **exclusively through events** on topic exchanges —
  there is no synchronous service-to-service call between domain services.
- **JWT is distributed via broadcast, not verified locally**: auth-service
  issues a token once and publishes it; every consuming service caches it
  in memory and trusts the cache rather than re-verifying the signature on
  each request.
- **No service reads another service's database** directly — every
  cross-service fact travels as an event.
- **The one architectural exception**: notification-service makes a
  synchronous HTTP call to auth-service's public
  `GET /users/{id}/notification-profile` endpoint to enrich a notification
  (see [ADR-0014](../adr/0014-notification-service.md)). This is a
  deliberate, singular exception, not a pattern used anywhere else — even
  `order.status-changed`, added later, deliberately avoids a second one by
  reusing the enrichment already captured in that order's `order.created`
  notification instead of calling auth-service again.
- **Every hop carries a CorrelationId**, born at the first HTTP request
  (or reused from the client) and propagated unchanged through every
  subsequent HTTP header and native AMQP message property, so one
  business operation is traceable across every service's logs without a
  tracing backend. See [ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md)
  and [Observability](observability.md).
- **Every message crossing a service boundary has a versioned JSON Schema
  contract** (`/schemas/json/`, one file per routing key) and a
  producer/consumer test per service that touches it — commands
  (`stock.reserve`, `payment.refund.requested`) included, not just
  fact-events. This is a standing rule for every new event from the
  moment it's introduced, not something added after the fact — see
  [ADR-0002](../adr/0002-json-schema-contract-testing.md) and
  [CONTRIBUTING.md](../../CONTRIBUTING.md).

## Persistence

- A single PostgreSQL instance backs every service that needs one — there
  is no database-per-service.
- Each service owns exactly one schema (`auth_schema`, `products_schema`,
  `inventory_schema`, `orders_schema`, `billing_schema`,
  `notification_schema`) and is the only writer to its own tables.
- No service queries another service's schema directly in production
  code (a small number of test-fixture exceptions exist, see
  [ADR-0018](../adr/0018-persistence-strategy.md)).

The rationale behind this persistence strategy — including the trade-offs
against database-per-service and when this decision might be revisited —
is documented separately in
[ADR-0018](../adr/0018-persistence-strategy.md), not here.

## Exchanges & Events

| Exchange | Routing Key | Publisher | Consumer(s) |
|---|---|---|---|
| `auth.exchange` | `user.registered` | auth-service | products-service (SELLER only), orders-service |
| `auth.exchange` | `user.verified` | auth-service | products-service (SELLER only), orders-service |
| `auth.exchange` | `jwt.created` | auth-service | products-service (SELLER only), orders-service, billing-service, notification-service |
| `product.exchange` | `product.created` / `product.updated` / `product.deleted` | products-service | inventory-service |
| `product.exchange` | `product.created` | products-service | orders-service (ownership projection only — see [ADR-0026](../adr/0026-frontend-thin-client.md)'s Roadmap follow-up) |
| `order.exchange` | `stock.reserve` | orders-service | inventory-service |
| `order.exchange` | `stock.release` | orders-service | inventory-service |
| `order.exchange` | `stock.reserved` | inventory-service | orders-service |
| `order.exchange` | `stock.insufficient` | inventory-service | orders-service |
| `order.exchange` | `order.created` | orders-service | billing-service, notification-service |
| `order.exchange` | `order.status-changed` | orders-service | notification-service |
| `order.exchange` | `payment.approved` | billing-service | orders-service |
| `order.exchange` | `payment.failed` | billing-service | orders-service |

## Architectural Principles

This project follows a small set of recurring principles — deliberate
simplicity, removing complexity that doesn't earn its place, avoiding dead
code and unnecessary compatibility modes, evidence over assumption, TDD as
the change driver, among others. See
[Architectural Principles](architectural-principles.md) for the full list
and the decisions each one is drawn from.

## Where to go next

- [README](../../README.md) — quick start, service status, and the ADR
  index.
- [Walkthrough](../walkthrough.md) — the full business flow, driven
  entirely by `curl`, with real requests and responses.
- [Sequence diagram](../sequence-diagram.md) — the same flow as a Mermaid
  diagram.
- [Observability](observability.md) — how one business operation is
  traced end to end through every service's logs via a propagated
  CorrelationId.
- [Architectural Principles](architectural-principles.md) — the
  philosophy behind the decisions summarized here.
- `docs/adr/` — one ADR per architectural decision, with full context and
  trade-offs.
