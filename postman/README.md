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

## Run the flow

Use Postman's **Collection Runner** against the whole collection, top to
bottom:

1. Start the stack first: `docker-compose up -d` (see the main README).
2. Open the Collection Runner, select **EasyDora** + the **Local**
   environment.
3. Set a small delay between requests (500–1000ms) in the Runner's
   settings. This isn't strictly required — the retry loops described
   below will still eventually succeed without it — but it avoids
   hammering the stack while RabbitMQ/Postgres catch up asynchronously.
4. Run. No manual steps are needed once it starts: signup emails are
   generated uniquely on each run (via a pre-request script), so the
   whole collection can be re-run repeatedly without resetting anything.

### Folder order

| # | Folder | Mirrors walkthrough step |
|---|---|---|
| 0 | Health Checks | step 2 (`docker compose ps`, executable analog) |
| 1 | Seller Onboarding | step 3 |
| 2 | Product Management | step 4 |
| 3 | Buyer Onboarding | step 5 |
| 4 | Order Creation | step 6 |
| 5 | Stock Reservation | step 7 |
| 6 | Payment | step 8 |
| 7 | Notification (limitation: no public API) | step 9 |
| 8 | Final State | step 10 |
| 9 | Extras — Beyond the Walkthrough (optional) | — |

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

Captured automatically while running:

- `seller_email`, `seller_id`, `seller_verification_token`,
  `seller_token`
- `buyer_email`, `buyer_id`, `buyer_verification_token`, `buyer_token`
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

- **Notification cannot be verified through a public API.**
  `notification-service` exposes only `GET /health` — there is no
  endpoint to read a persisted notification back (see the README
  Roadmap). Folder 7 only confirms the service is reachable; to inspect
  the actual row, run the optional direct Postgres query documented in
  `docs/walkthrough.md` step 9.
- **"Process Payment (Simulation)" (folder 9) is not part of the
  documented walkthrough flow.** It's a real endpoint
  (`PaymentService.processPayment`), included for completeness, but the
  walkthrough deliberately stops at a `PENDING` payment — see
  `docs/sequence-diagram.md`'s reading notes. Its outcome is a random
  ~90% approval simulation, so the request accepts either `APPROVED` or
  `FAILED` rather than asserting one.
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
  this etapa is documentation-only.
- If `notification-service` ever gains a public read endpoint (an
  already-tracked Roadmap item), folder 7 should be updated to call it
  instead of just checking `/health`.
