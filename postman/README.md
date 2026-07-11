# EasyDora — Postman Collection

Executable documentation for EasyDora's main business flow (signup →
product → order → stock reservation → payment → notification), driven
entirely through each service's public HTTP API. This complements
[`docs/walkthrough.md`](../docs/walkthrough.md) — it does not replace it.
Every request here corresponds to a `curl` command already documented and
verified there; where wording differs, this collection is the one that
does token/ID handling automatically instead of showing it as a shell
variable.

## Import

1. Import `EasyDora.postman_collection.json` (File → Import, or drag it
   into Postman).
2. Import `environments/Local.postman_environment.json` the same way.
3. Select the **Local** environment in the environment dropdown (top
   right).

## Configure the environment

The `Local` environment already points at each service's default
docker-compose port (`auth_url` → `:8081`, `products_url` → `:8082`,
`inventory_url` → `:8083`, `orders_url` → `:8084`, `billing_url` →
`:8085`, `notification_url` → `:8086`, `gateway_url` → `:8080`). Nothing
needs to change if you're running `docker-compose up -d` per the main
[README](../README.md)'s Quick Start.

`seller_password`/`buyer_password` have a fixed default value. Every other
variable (emails, IDs, tokens, retry counters) starts empty and is
populated automatically while the collection runs — you never need to
copy a value by hand.

## Two parallel folder trees

The collection has two top-level folders, both covering the same flow:

- **`Via Gateway (primary)`** — every request goes through the Gateway
  (`gateway_url`, port 8080), using each service's own self-namespaced
  segment (`/auth`, `/products`, `/orders`, `/billing`, `/inventory`).
  The Gateway forwards the path unchanged (ADR-0025), so this is
  functionally identical to calling each service directly — this is now
  the primary way to run the walkthrough, matching
  [`docs/walkthrough.md`](../docs/walkthrough.md).
- **`Direct (debug)`** — the original per-service requests, unchanged,
  calling each service's own port directly. Useful for isolating whether
  a failure belongs to a service or to the Gateway's routing, without
  needing to reason about an extra hop.
- `notification-service` now has a Gateway route too
  (`/notification/notifications/{orderId}`, see
  [ADR-0026](../docs/adr/0026-frontend-thin-client.md)), but its folder
  still only exists under `Direct (debug)` here — the collection itself
  wasn't restructured to add it, since this is Postman-collection
  maintenance, not part of the frontend work that added the route. See
  "Known limitations" below.

Both trees share the same variables, retry loops, and assertions — pick
whichever folder matches what you're trying to verify.

## Run the flow

Use Postman's **Collection Runner** against **`Via Gateway (primary)`**,
top to bottom (or `Direct (debug)` if you're isolating a per-service
issue):

1. Start the stack first: `docker-compose up -d` (see the main README).
2. Open the Collection Runner, select **EasyDora**, the folder you want
   to run, and the **Local** environment.
3. Set a small delay between requests (500–1000ms) in the Runner's
   settings. This isn't strictly required — the retry loops described
   below will still eventually succeed without it — but it avoids
   hammering the stack while RabbitMQ/Postgres catch up asynchronously.
4. Run. No manual steps are needed once it starts: signup emails are
   generated uniquely on each run (via a pre-request script), so the
   whole collection can be re-run repeatedly without resetting anything.

### Folder order

Shared by both trees:

| # | Folder | Mirrors walkthrough step |
|---|---|---|
| 0 | Health Checks | step 2 (`docker compose ps`, executable analog) |
| 1 | Seller Onboarding | step 3 |
| 2 | Product Management | step 4 |
| 3 | Buyer Onboarding | step 5 |
| 4 | Order Creation | step 6 |
| 5 | Stock Reservation | step 7 |
| 6 | Payment | step 8 |
| 8 | Final State | step 10 |
| 9 | Extras — Beyond the Walkthrough (optional) | — |

Folder 7 differs between the two trees (a known asymmetry, see "Known
limitations" below — neither tree has both):

| Tree | Folder 7 | Mirrors walkthrough step |
|---|---|---|
| `Via Gateway (primary)` | Fulfillment (Ship & Deliver) — the new `ADMIN`-role-gated ship endpoint and ownership-gated deliver endpoint | step 12 |
| `Direct (debug)` | Notification (limitation: no public API — stale, see below) | step 9 |

## Async waits: retry loops, not sleeps

Three hops in this flow are asynchronous (RabbitMQ round trips), so the
request that validates them uses a bounded self-retry instead of a fixed
`sleep`: the request re-runs itself (via `pm.execution.setNextRequest`) up
to N times until the expected state appears, then asserts on it.

| Request | What it waits for | Max retries |
|---|---|---|
| Verify Inventory Created | `product.created` → inventory row exists | 8 |
| Verify Order State | `stock.reserve`/`stock.reserved` → order reaches `INVENTORY_RESERVED` | 15 |
| Verify Payment Created | `order.created` → a `Payment` row exists | 10 |

If a retry loop exhausts its budget, the request still runs its normal
assertions on whatever the last response was — you'll see a clear
failure (e.g. "order reached a terminal reservation state" failing with
`state: "PROCESSING"`) instead of a silent timeout.

## Variables

Set once (fixed):

- `auth_url`, `products_url`, `inventory_url`, `orders_url`,
  `billing_url`, `notification_url`, `gateway_url` — service base URLs.
- `seller_password`, `buyer_password` — fixed passwords used for both
  test users.
- `admin_email`, `admin_password` — the platform-operations account used
  by folder 7's "Login (Admin)" request (`Via Gateway (primary)` only).
  Unlike the seller/buyer credentials above, this isn't a throwaway demo
  signup — it's the one account bootstrapped from auth-service's
  `ADMIN_EMAIL`/`ADMIN_PASSWORD` environment variables (see ADR-0029), so
  these two are left blank in the committed environment file; set them
  locally to match your own `.env` before running that request.

Captured automatically while running:

- `seller_email`, `seller_id`, `seller_verification_token`,
  `seller_token`
- `buyer_email`, `buyer_id`, `buyer_verification_token`, `buyer_token`
- `admin_token` — captured by "Login (Admin)" (folder 7)
- `product_id`, `product_price`, `product_initial_stock`
- `order_id`, `order_quantity`, `order_total_amount`
- `payment_id`
- `_retry_inventory_created`, `_retry_order_state`, `_retry_payment` —
  internal retry counters, reset to `0` once each loop settles.

## Validations

Each request asserts, where it makes sense: HTTP status code,
`Content-Type` (JSON endpoints only), presence of the required response
fields, and — where the walkthrough itself asserts a specific outcome
(e.g. `state: INVENTORY_RESERVED`, `status: PENDING`, a `403` without a
token) — that exact value. This is intentionally not a full test suite:
the goal is executable documentation of the happy path already documented
in the walkthrough, not exhaustive coverage of every edge case.

## Known limitations

- **Folder 7 only exists under `Direct (debug)`, not `Via Gateway
  (primary)`**, even though `notification-service` has had a public
  `GET /notifications/{orderId}` since ADR-0020 and a Gateway route
  since ADR-0026 — this note above described a real limitation before
  ADR-0020, went stale, and stayed unfixed through ADR-0025; still
  unfixed here, since restructuring the collection itself is
  Postman-maintenance work, not something either of those changes
  required. `docs/walkthrough.md` step 9 calls the real endpoint
  directly.
- **"Process Payment" (folder 9) is not part of the documented walkthrough
  flow.** It's a real endpoint (`PaymentService.processPayment`), included
  for completeness, but the walkthrough deliberately stops at a `PENDING`
  payment — see `docs/sequence-diagram.md`'s reading notes. Its outcome is
  now deterministic (`PaymentProvider`, ADR-0030: amount parity, no
  `Math.random()`) — this collection's fixed test data (2 units at a fixed
  `249.90` price) always resolves to `FAILED`, and the request asserts
  exactly that instead of accepting either outcome.
- **Re-running against a stack that still has data from a previous run**
  works for signup (emails are generated uniquely per run) but each run
  creates a new seller/product/order/payment rather than reusing the
  previous one — this is a demonstration flow, not a idempotent seed
  script.
- Requires the full stack running via `docker-compose up -d` — no
  request in this collection targets a mocked or partially-running
  environment.

## Future improvements (out of scope for this version)

- A Newman-based CI job could run this collection against a
  freshly-started stack as an additional smoke test, alongside the
  existing Phase 3 e2e suite (`e2e-tests/`) — not implemented here, since
  this change is documentation-only.
- Folder 7 should be restructured with a `Via Gateway (primary)` twin
  calling `GET /notification/notifications/{orderId}` through the
  Gateway, matching every other folder — not done here (see "Known
  limitations").
