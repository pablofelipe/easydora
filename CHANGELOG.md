# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows the policy documented in
[docs/project-governance/versioning-and-release-policy.md](docs/project-governance/versioning-and-release-policy.md).

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
