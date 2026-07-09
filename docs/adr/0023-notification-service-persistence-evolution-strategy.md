# ADR-0023: Notification Service Persistence Evolution Strategy

## Status

Accepted - 2026-07-09

## Context

`notification-service` has used a single idempotent `scripts/init.sql`
(one schema, one table) since ADR-0014 introduced the service, with no
versioned migration tool (no Alembic, no equivalent to the four Spring
services' Flyway). ADR-0014 explicitly listed this as a "Not fixed here"
item, and the README Roadmap has since carried it as an open, undated
technical-debt entry — never a closed decision.

This ADR re-examines that gap on its own merits, against the project's
actual history, rather than assuming Alembic is simply owed by symmetry
with the Spring services.

### Inspection performed before deciding anything

- **`scripts/init.sql` has not changed once** since the commit that
  introduced `notification-service` (`f0df4ac`), confirmed via
  `git log --follow`. In that time the service went through four further
  ADRs of real functional growth — ADR-0017 (startup resilience),
  ADR-0020 (consuming `order.status-changed`, adding a read API),
  ADR-0021 (reacting to payment-outcome-driven status changes, with no
  changes required here), and ADR-0022 (consumption resilience) — and
  none of them touched the table.
- **The table's shape absorbed two new event types with zero schema
  change**: `event_type`/`aggregate_id`/`status`/`payload JSONB` is
  generic enough that `order.status-changed` (both the stock-outcome and
  payment-outcome variants) is just new values in existing columns, not
  new columns.
- **`NotificationRepository`** (`app/repository.py`) has exactly two
  queries against exactly one table — an `INSERT` and a
  `SELECT ... WHERE aggregate_id = %s`. No joins, no foreign keys, no
  ORM.
- **A closer look at `inventory-service` (Go)**, which follows the same
  idempotent-init strategy, shows it under more real change pressure than
  `notification-service` ever has: its `scripts/init.sql` changed twice
  (two `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` additions, then an
  entirely new `outbox_events` table for ADR-0007's Outbox pattern) —
  and each change was handled safely with plain idempotent SQL, no
  versioned tool. This is direct evidence that the idempotent-script
  approach tolerates more schema evolution than `notification-service`
  has needed so far, not just the zero-change case this service happens
  to be in.
- **The four Spring services' actual Flyway migration counts**, for
  comparison rather than assumption: auth-service (3 migrations — users,
  outbox_events, a schema-authority fix), products-service (2 —
  sellers, products), orders-service (2 — orders/items, buyers),
  billing-service (1 — payments). billing-service is the closest
  counterpart to `notification-service`'s simplicity (one migration,
  schema never changed since), yet it still got Flyway under
  ADR-0011 — but for ecosystem uniformity across the four Spring
  services (idiomatic Java tooling, and `ddl-auto=validate` needs *some*
  schema authority to validate against), not because its own schema
  demanded versioning. That reasoning doesn't transfer to Python, which
  has no equivalent "every service in this stack already uses this tool"
  pressure.

No evidence surfaced that the decision documented in ADR-0014 has stopped
being valid — if anything, the comparison against `inventory-service`
shows the current approach has headroom beyond what `notification-service`
has used.

## Decision

**Keep `scripts/init.sql` as the sole schema-management mechanism for
`notification-service`. Do not adopt Alembic or any other versioned
migration tool at this time.**

This is a conscious, evidence-based decision, not an oversight left
unaddressed since ADR-0014 — the technical-debt entry it was tracked as
is closed by this ADR, not deferred further.

### Why Alembic would be overengineering right now

| Factor | Finding |
|---|---|
| Expected schema evolution | One generic table has already absorbed two new event types with zero structural change; nothing on the roadmap requires a new table or column. |
| Table count | One. |
| Frequency of structural change | Zero, across four ADRs of functional growth since the service was introduced. |
| Operational impact | Alembic would add a `alembic/versions` directory, `env.py`, `alembic.ini`, and an upgrade step in the boot sequence or CI — real infrastructure for a problem that has not occurred once. |
| Maintenance cost | A second schema-management convention in the codebase (Flyway for Java, Alembic for Python) to keep straight, for a service with a single `CREATE TABLE IF NOT EXISTS`. |
| Additional complexity | Real and disproportionate to the problem — see Architectural Principles #2 ("a component must earn its place in the architecture") and #1 (deliberate simplicity over engineered precision). |
| Effective benefit for this portfolio project | None demonstrable today: nothing in the current or planned data model requires ordered, reversible, environment-tracked schema changes. |

## Consequences

**Positive**:
- No new tooling, dependency, or convention introduced for a problem this
  service does not have.
- The technical debt item is now a closed, explicit architectural
  decision instead of an indefinite Roadmap entry — anyone reading the
  Roadmap or ADR-0014 no longer has to wonder whether this was simply
  forgotten.
- Consistent with this project's recurring principle of not adding
  complexity for symmetry alone (see
  [Architectural Principles](../architecture/architectural-principles.md)).

**Negative / residual**:
- If the data model does grow, `scripts/init.sql`'s idempotent
  `CREATE ... IF NOT EXISTS`/`ALTER ... ADD COLUMN IF NOT EXISTS` style
  has no rollback mechanism and no per-environment version record — every
  environment just re-runs the same script and converges to the same
  end state, with no history of *when* each piece was added. Acceptable
  today; would not be acceptable under the reopening criteria below.

## Criteria to reopen this decision

This decision should be revisited if any of the following becomes true —
not on a vague sense that "more tables might show up someday," but on one
of these concretely observed:

1. **The data model grows into multiple related tables** (foreign keys,
   joins across `notification-service`'s own schema) — not just more rows
   in the existing single table.
2. **A structural change requires transforming existing data**, not just
   adding a column with a default — e.g. splitting a column, changing a
   column's type with existing rows to convert, or a change whose
   correctness depends on the order it's applied relative to other
   changes. A simple idempotent statement stops being sufficient at that
   point.
3. **Multiple environments need to run divergent schema versions
   simultaneously** (e.g. a staged rollout where not every environment
   is on the same schema at the same time) — today there is exactly one
   schema state per environment, always.
4. **A real operational requirement emerges for an auditable history of
   schema changes** (e.g. a compliance or audit need to show exactly what
   changed and when) — not a hypothetical one.
5. **The schema begins changing frequently enough that maintaining
   idempotent initialization scripts becomes error-prone** — e.g. new
   statements start depending on the exact prior state of the script
   (ordering-sensitive `ALTER`s), or verifying that each addition is
   still safely re-runnable stops being something a reviewer can confirm
   by inspection alone, the way `inventory-service`'s two
   `ADD COLUMN IF NOT EXISTS` additions still are today.

## References

- [ADR-0014](0014-notification-service.md) — where this gap was first
  identified as a "Not fixed here" item; closed by this ADR.
- [ADR-0007](0007-remove-kafka-broker.md) — `inventory-service`'s Outbox
  migration, the source of the comparative evidence that idempotent
  `init.sql` tolerates real schema evolution, not just the zero-change
  case `notification-service` has been in so far.
- [ADR-0011](0011-flyway-schema-authority-all-services.md) — why all four
  Spring services standardized on Flyway (ecosystem uniformity), a
  rationale that does not transfer to a single Python service.
- [ADR-0018](0018-persistence-strategy.md) — the schema-ownership
  decision this ADR's scope sits inside; that ADR covers *whether to
  share a Postgres instance*, this one covers *how one service manages
  its own schema*, a related but distinct question.
- [Architectural Principles](../architecture/architectural-principles.md)
  — principle #1 (deliberate simplicity over engineered precision) and
  principle #2 (a component must earn its place) both apply directly to
  why Alembic isn't justified here today.
