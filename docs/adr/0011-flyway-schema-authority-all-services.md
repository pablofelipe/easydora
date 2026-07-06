# ADR-0011: Make Flyway the single schema authority in every Spring Boot service

## Status

Accepted - 2026-07-06

## Context

ADR-0004 fixed the ddl-auto/Flyway mismatch in auth-service, but explicitly flagged two things as out of scope: whether the same problem existed in products-service, orders-service or billing-service was never checked, and auth-service's `flyway_schema_history` staying in `public` (instead of `auth_schema`) was left as a "cosmetic asymmetry" because moving it looked like it would mean replaying `V1`/`V2` for no benefit.

Asked to standardize schema handling across all four services and fix billing-service's Flyway situation specifically, a full audit found:

- **orders-service**: genuinely Flyway-managed already — `flyway-core` present, `spring.flyway.schemas=orders_schema` set, its own migration self-contains `CREATE SCHEMA IF NOT EXISTS orders_schema` and schema-qualifies every table. The only one of the four with no gap.
- **products-service**: `spring.flyway.enabled=true` and `spring.flyway.schemas=products_schema` were configured, but `flyway-core` was **never added as a Maven dependency** — Spring Boot's `FlywayAutoConfiguration` silently no-ops when the class isn't on the classpath, so every Flyway property had been dead configuration since the service was created. `V1__Create_sellers_table.sql`/`V2__Create_products_table.sql` never ran; every live table was created entirely by `ddl-auto=update`, with visible drift from what the migrations specify — e.g. the live foreign key is named `fkepbha8uixgrmnejm27n6e1kkd` (Hibernate's own auto-generated name), not `V2`'s explicit `fk_products_seller`; `avatar_url`/`role` are `VARCHAR(255)` live, not the `VARCHAR(500)`/`VARCHAR(50)` `V1` specifies.
- **billing-service**: the same missing-dependency bug as products-service, plus worse: `ddl-auto=update` lived in the single shared `application.properties` (no dev/prod split existed at all, so it was active even outside dev), and zero migration files ever existed — `Payment`'s table was 100% Hibernate-authored from day one.
- **auth-service**: correctly fixed by ADR-0004, but still missing `spring.flyway.schemas=auth_schema` (works only because `V3` manually moved the tables there with raw SQL) and still had `spring.flyway.clean-on-validation-error=true`/`validate-on-migrate=false` in dev — the only one of the four with automatic schema-wipe-on-validation-failure enabled and checksum validation turned off.

## Decision

Flyway becomes the single schema authority in all four services; Hibernate only ever validates, never creates or alters.

1. **Added `flyway-core` to products-service and billing-service's `pom.xml`** — previously absent, making every `spring.flyway.*` property inert.
2. **billing-service gets its first real migration**, `V1__Create_payments_table.sql`, written from the live, already-running table (exact column types, exact constraint names) rather than from the `Payment` entity — this guarantees zero drift the first time Flyway actually runs for this service.
3. **billing-service's single `application.properties` split into base + `-dev` + `-prod`**, matching the other three services' convention. `ddl-auto=update` is now dev-only, like everywhere else.
4. **`spring.flyway.schemas=<service>_schema` added explicitly to auth-service and billing-service** (products-service and orders-service already had it declared; for products it had simply never taken effect).
5. **`spring.flyway.baseline-on-migrate=true` plus an explicit `spring.flyway.baseline-version` pinned to each service's latest pre-existing migration** (auth: `3`, products: `2`, billing: `1`). This is necessary because all three services' target schemas already contain live tables predating Flyway's real activation — baselining tells Flyway "everything up to this version is already established," instead of trying to replay migrations against tables that already exist (which fails outright without a baseline, and would otherwise fail again trying to re-create existing objects). This is the mechanism ADR-0004 didn't use, and its absence is exactly why that ADR left auth-service's `flyway_schema_history` in `public` as unresolved debt — baselining makes it safe to finally point Flyway straight at `auth_schema`.
6. **`ddl-auto` flipped from `update` to `validate`** in orders-service and products-service's dev profile, matching what ADR-0004 already did for auth-service. Hibernate no longer creates or alters schema in any of the four services, in any profile.
7. **auth-service's dev-only Flyway safety flags replaced** with orders/products' safer convention: `clean-on-validation-error=true` → removed, `validate-on-migrate=false` → `true`, `clean-disabled=false` added. auth-service was the only one of the four that could silently wipe its schema on a validation mismatch and the only one not actually checking migration checksums.

## Verification

No JPA-context-loading test exists in this repo (same convention ADR-0004 already noted), so every change was verified the same way: booting the real service against the real, already-populated database.

- Each service boot-tested locally (`mvn spring-boot:run`, offline where the dependency was already cached) against the live database before touching Docker, to catch a bad config cheaply. auth-service failed once as expected (`Found non-empty schema "auth_schema" but no schema history table`) until `baseline-version=3` was added; every other combination booted clean on the first or second try.
- All four containers rebuilt (`docker compose up -d --build`) and confirmed `healthy`.
- Full business flow re-verified live end to end: signup → email verify → login → create product (products-service) → inventory auto-created via RabbitMQ (inventory-service) → create order (orders-service) → `INVENTORY_RESERVED` → payment created (billing-service) — exercising all four services' now-authoritative schemas in the same run.
- Full unit suite re-run afterward: unaffected — auth 4/4, products 4/4, orders 8/8, billing 5/5 (`mvn test`) plus billing's `BillingServiceApplicationIT` (`mvn verify`).

## Consequences

**Positive**: every service's schema is now genuinely reviewable and versioned through a migration file, not silently improvised by `ddl-auto`. products-service and billing-service's Flyway configuration, previously decorative, is now real. auth-service's `flyway_schema_history` finally lives where its tables do, closing the exact residual asymmetry ADR-0004 left open.

**Negative / residual**:
- products-service's *live* schema (in this long-running environment) still carries the Hibernate-era drift described above (shorter `avatar_url`/`role` columns, Hibernate-named FK) — not rolled back, since `ddl-auto=validate` doesn't care about column length or constraint names and reconciling it wasn't asked for. A **fresh** environment (new clone, empty database) will run `V1`/`V2` from scratch and end up with the *original*, non-drifted schema (`VARCHAR(500)`/`VARCHAR(50)`, `fk_products_seller`) — subtly different from what this long-running environment has. This asymmetry between "this dev environment" and "a fresh install" is the new known residual debt, in the same spirit as what ADR-0004 documented for auth-service.
- Neither products-service nor billing-service has ever run `mvn verify` with a real `*IT` test exercising this Flyway path — verification here is manual boot-testing, same limitation ADR-0004 already had.

## References

- ADR-0004 (auth-service schema authority fix) — the original, narrower version of this same decision; this ADR closes the gap it explicitly left open ("whether the same mismatch exists in any other service... was not checked").
- ADR-0003 (outbox pattern for auth-service) — where ADR-0004's bug was originally found.
