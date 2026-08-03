# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows the policy documented in
[docs/project-governance/versioning-and-release-policy.md](docs/project-governance/versioning-and-release-policy.md).

## [0.4.0] - 2026-08-03

Structural ADRs this release: 1 (ADR-0024's 2026-08-02 Update — adopting
OpenTelemetry and a Jaeger backend, a new observability signal type,
additive to CorrelationId rather than replacing it). Consecutive
non-structural releases: 0. See the Architecture Stability tracker in
the versioning policy doc above.

### Added
- OpenTelemetry distributed tracing with a Jaeger backend across all
  eight services (ADR-0024's 2026-08-02 Update): a real login produces
  one trace spanning 6 services and 13 spans across three languages.
- Trace context (`traceparent`) now propagates across the Outbox
  pattern's write-to-publish gap in auth-service, orders-service,
  billing-service, and inventory-service, closing the one gap the
  tracing adoption above left open (ADR-0024's 2026-08-03 Update). Live
  -validated: a real order-creation trace now spans 20 spans across 5
  services, rooted at the original HTTP request.
- Standalone RabbitMQ vs Kafka broker benchmark harness and ADR-0041,
  measuring the ADR-0007 broker decision instead of only arguing it.
- Fast local dev loop via `docker compose watch` (ADR-0042): a `dev`
  Dockerfile stage per service, applied through a
  `docker-compose.watch.yml` override so the default production build
  path is unchanged.
- `notification_retry_total{outcome}` metric and a Grafana panel,
  surfacing a retry or dead-lettering that was previously visible only
  in log lines.
- `boot-race` CI job (one per Spring service), replacing a one-time
  manual confirmation of Postgres/RabbitMQ startup-race tolerance with a
  test that runs on every push.
- billing-service now rejects `processPayment` for an order that isn't
  `INVENTORY_RESERVED`; orders-service gains an ADMIN-gated retry
  endpoint (and a frontend `/refunds` page) for orders stuck in
  `REFUND_FAILED`.

### Fixed
- inventory-service's `OutboxPublisher` never set `DeliveryMode` on
  published messages, so a hard broker kill could lose a message even
  after a positive publisher confirm — the root cause of the 2/207
  message loss ADR-0041's benchmark found.
- `ReserveStock`'s post-TTL duplication window: a redelivered command
  after the in-memory idempotency cache expires (or a process restart
  wipes it) no longer reserves stock a second time.
- `registerUser` now writes `user.registered` through the Outbox instead
  of publishing directly, closing the gap where a publish failure after
  a successful save could silently drop the event.
- A real regression the change above surfaced: with `user.registered`
  delayed by the Outbox's poll cadence, it could arrive after
  `jwt.created` had already activated a buyer/seller, and both consumers
  unconditionally reset `active` back to `false` — now only forced on a
  genuinely new record.
- auth-service's weak dev JWT secret fallback (168 bits, below
  HMAC-SHA's 256-bit minimum).
- `notification-service`'s RabbitMQ boot connection retried forever with
  no upper bound, unlike its own bounded Postgres connection — bounded
  to 10 attempts, matching it.
- `JWT_SECRET`/`jwt.secret` removed as dead configuration from all four
  Spring services, including `auth-service` itself (never read anywhere
  — `app.jwt.secret` is the one real secret).
- api-gateway's circuit breaker inferred a downstream failure from a
  `502` status code alone, indistinguishable from a backend that
  legitimately returns one; now flagged directly on the request context.
  Its `ResponseHeaderTimeout` (30s) also meant the breaker's worst case
  took 150s to open — reduced to 5s (worst case 25s), measured against a
  real frozen (not just stopped) container.
- `/health` across all four Spring services and both Go services now
  performs a real, timeout-bounded database connectivity probe instead
  of an unconditional "Connected".
- A recurring e2e flake: `createOrder` no longer asserts on the first
  attempt against a buyer activation that's asynchronous relative to
  login's own HTTP response.

### Changed
- README and `docs/architecture/` rewritten in Simplified Technical
  English (ASD-STE100 style).
- `CLAUDE.md` citations removed from every committed doc (gitignored, so
  a fresh clone can't follow them).
- Every `pom.xml` and `frontend/package.json`/`package-lock.json`
  bumped to `0.4.0`.

[0.4.0]: https://github.com/pablofelipe/easydora/compare/v0.3.0...v0.4.0

## [0.3.0] - 2026-07-23

Structural ADRs this release: 1 (ADR-0038's 2026-07-20 Update — RabbitMQ
topology redeclaration on reconnect and publisher confirms in the Outbox
publisher). Consecutive non-structural releases: 0. See the Architecture
Stability tracker in the versioning policy doc above.

### Added
- Reconnection observability contract across all six services:
  `rabbitmq_reconnect_attempts_total`,
  `rabbitmq_topology_setup_total{outcome}`,
  `messaging_last_progress_timestamp_seconds`.
- "EasyDora / Resilience" Grafana dashboard.
- Publisher confirms in inventory-service's Outbox publisher, with
  per-event channel validation so one bad row can't poison a poll batch.
- Automatic RabbitMQ topology redeclaration after reconnect in
  inventory-service.
- Frontend: signup screen with an on-screen email-verification token flow.
- Frontend: seller product creation screen.
- Frontend: buyer order cancellation (PENDING / PROCESSING /
  INVENTORY_RESERVED).
- Postman collection coverage for notification retrieval and payment
  compensation (ADR-0034).
- `.dockerignore` for every service's Docker build context.
- Versioning and release governance policy
  (`docs/project-governance/versioning-and-release-policy.md`): what a
  version number communicates here, the objective test for an
  architectural change, and the release checklist.

### Fixed
- inventory-service no longer silently marks an event "published" when
  the broker never accepted it (fire-and-forget to publisher-confirms).
- orders-service and billing-service's JWT filters let the chain
  continue on an unknown token instead of returning 401 directly,
  matching products-service.
- Every `pom.xml` (root and all child modules) and
  `frontend/package.json`/`package-lock.json` now track the release
  version instead of the generator's untouched default
  (`0.0.1-SNAPSHOT`/`0.0.1`, or, in `e2e-tests`, an unrelated `1.0.0`).
- README: corrected stale claims that contract-test coverage was limited
  to four services/two event types (ADR-0002's 2026-07-13 Update already
  covers all six messaging services and all 17 published messages), that
  the Outbox Pattern was limited to two services (ADR-0037 extended it
  to four), and the ADR count (26 -> 40).

### Changed
- ADR-0038 updated with the 2026-07-20 findings and the reconnection
  observability contract.

[0.3.0]: https://github.com/pablofelipe/easydora/compare/v0.2.0...v0.3.0
