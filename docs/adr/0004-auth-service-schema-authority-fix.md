# ADR-0004: Make auth_schema the single source of truth for auth-service's tables

## Status

Accepted - 2026-07-04

## Context

While investigating Passo 1 of the outbox-pattern work (ADR-0003), booting auth-service and querying Postgres directly surfaced a bug unrelated to the outbox itself: `V1__Create_users_table.sql` and `V2__Create_outbox_events_table.sql` both created their tables in `public` — Flyway's resolved default schema, since no `spring.flyway.schemas`/`spring.flyway.default-schema` was ever configured — while `spring.jpa.hibernate.ddl-auto=update` (dev profile) independently auto-created a second, real copy of both tables in `auth_schema`, per `hibernate.default_schema=auth_schema`. The application's JPA queries only ever touched the `auth_schema` copies (confirmed via the Hibernate-generated SQL logs), so the `public` copies — including `V1`'s seeded admin user (`admin@easydora.com`) — were silently dead: present in the database, invisible to the running application.

Checked before deciding anything: `auth_schema.users` and `auth_schema.outbox_events` (the live, actually-used tables) had 0 rows each; `public.users` had exactly 1 row (the V1 seed); `public.outbox_events` had 0. Nothing of real value existed anywhere that a fix could destroy.

## Decision

Adopt the stricter of the two options considered (documented, not both implemented): make Flyway the single authority for schema in `auth_schema`, and stop letting Hibernate create or alter tables at all.

### Alternative considered and rejected

The less strict option: leave `spring.jpa.hibernate.ddl-auto=update` in place and just point Flyway's own target at `auth_schema` (`spring.flyway.schemas=auth_schema`) so both tools at least write to the same schema — fixing the immediate duplication bug (two different schemas) without taking away Hibernate's ability to auto-create or auto-alter tables going forward.

This was rejected because it only treats the symptom, not the mechanism that caused it: Hibernate would still be free to silently create or alter schema with no migration file behind it, so the same class of surprise drift (a live table whose shape was never reviewed or versioned) could recur the moment an entity changed — just no longer split across two schemas while it happens. The stricter option (`ddl-auto=validate` + Flyway as sole authority) closes the actual mechanism: every future schema change must go through a reviewable, versioned migration, and Hibernate can only ever confirm its mappings match what Flyway already built, never author schema itself. This later proved to be the right call, not just the more cautious one: ADR-0011 found the identical missing-`flyway-core`/silent-Hibernate-drift pattern independently in products-service and billing-service, and applied this same stricter approach to all four services rather than the lighter fix considered and rejected here.

- **`V3__Fix_schema_authority_move_tables_to_auth_schema.sql`**: drops all four tables (the two dead `public` copies and the two Hibernate-created `auth_schema` copies, which were never tracked by any migration) and recreates `auth_schema.users`/`auth_schema.outbox_events` from scratch, with column types copied **exactly** from what Hibernate's auto-DDL had actually produced (verified column-by-column via `\d` against the live database before writing the migration — e.g. `first_name`/`role`/`status` are `VARCHAR(255)`, not the `VARCHAR(100)`/`VARCHAR(50)`/`VARCHAR(20)` `V1` originally used), including the `users_role_check`/`users_status_check` CHECK constraints Hibernate 6 auto-generates for `@Enumerated(EnumType.STRING)` fields, and the `idx_outbox_events_unpublished` partial index `V2` had defined but that never existed in the live (Hibernate-managed) table. The admin seed row is recreated directly in `auth_schema.users`, reachable by the application for the first time.
- **`spring.jpa.hibernate.ddl-auto` switched from `update` to `validate`** in `application-dev.properties` (matching what `application-prod.properties` already defaulted to via `${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}` — prod was never exposed to this bug). Hibernate can no longer silently create or alter schema in any environment; it only verifies its entity mappings match what Flyway has already built.
- **Not done**: reconfiguring `spring.flyway.schemas` to move Flyway's own bookkeeping (`flyway_schema_history`) into `auth_schema`. It stays in `public`, which Flyway already resolves to correctly and consistently — moving it would have meant replaying `V1`/`V2` against a schema where objects of the same name already exist (from this very fix), for no benefit. Flyway's history table living in `public` while the schemas it manages live in `auth_schema` is a cosmetic asymmetry, not a repeat of the original bug: every future migration will keep landing in `auth_schema`, because that's where the tables the migrations touch now actually exist.

## Verification

No JPA-context-loading test exists in this repo's Java services (by established convention — see the `easydora-tdd` skill's guidance to prefer mocks over `@SpringBootTest`), so this was verified the same way ADR-0003's boot check was: running the real service against real Postgres/RabbitMQ (docker-compose).

- Booted with `ddl-auto=validate`: no `SchemaManagementException`, `Started AuthServiceApplication` reached cleanly — confirms the `V3`-created schema matches the JPA entity mappings exactly.
- Queried Postgres directly afterward: `public` now contains only `flyway_schema_history`; `auth_schema` contains `users` and `outbox_events`; `SELECT email, role, status FROM auth_schema.users` returns the admin row (`admin@easydora.com`, `ADMIN`, `ACTIVE`) — reachable by the application for the first time.
- Re-ran the full auth-service suite after the change: 4/4 passing, unaffected — none of those tests load a Spring context, so this fix couldn't have been exercised by them, only by the manual boot check above.

## Consequences

**Positive**: `auth_schema` is now the one physical copy of every auth-service table, and it is Flyway-tracked, not Hibernate-improvised. The previously-invisible seed admin user is now reachable. Any future schema change goes through a reviewable migration file and is checked by `validate` at boot, instead of being silently applied by `ddl-auto`.

**Negative / residual**:
- Flyway's `flyway_schema_history` bookkeeping table remains in `public`, physically separate from the schemas it manages — a minor asymmetry, not a functional problem, left as-is per the reasoning above.
- This fix is auth-service-specific. Whether the same `ddl-auto`/Flyway-schema mismatch exists in any other Spring Boot service (products, orders, billing) was not checked as part of this task — it was out of scope, discovered and fixed only where it was found.

## References

- ADR-0003 (outbox pattern for auth-service) — the task during which this bug was found; its own "Residual debt" section documents the original discovery and points here for the resolution.
- [ADR-0011](0011-flyway-schema-authority-all-services.md) — extends this same stricter approach to products-service, orders-service, and billing-service once the identical pattern was found there too, confirming the alternative rejected above stayed rejected project-wide.
