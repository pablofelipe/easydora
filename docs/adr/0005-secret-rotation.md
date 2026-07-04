# ADR-0005: Secret rotation and removal of hardcoded credentials

## Status

Accepted - 2026-07-04

## Context

While closing out ADR-0003/ADR-0004, `docker-compose.yml` was found to hardcode three real credentials in plaintext, repeated across every service that needed them:

- Postgres password (`POSTGRES_PASSWORD`/`SPRING_DATASOURCE_PASSWORD`/`DB_PASSWORD`)
- RabbitMQ password (`RABBITMQ_DEFAULT_PASS`/`SPRING_RABBITMQ_PASSWORD`/`RABBITMQ_PASSWORD`)
- JWT signing secret (`JWT_SECRET`, and a second, differently-named, differently-valued one, `app.jwt.secret`, hardcoded in each Spring service's dev properties)

**This repository is public on GitHub** (confirmed via `gh repo view`). Checking history (`git log -S`) showed these values have been present since very early in the project's life:

- The Postgres password, RabbitMQ password, and `JWT_SECRET` have been in `docker-compose.yml` since `cc21f4c` ("first commit", 2025-09-24) — the project's very first commit.
- `app.jwt.secret`'s value has been in auth-service's properties since `5bb6807` ("feat: add email verification flow", 2025-09-30).

Practical consequence: these values must be treated as **permanently compromised**, independent of anything done in this ADR. A public GitHub repository's history is not meaningfully private even after a later commit removes a secret — it can be cloned, forked, or crawled before any fix lands, and Git history retains the old blob regardless of what HEAD looks like afterward. Rewriting history (`git filter-repo`, BFG Repo-Cleaner) does not undo this exposure — anyone who already has a clone keeps the old commits — and would rewrite every commit hash in the repository's history, breaking any existing fork, clone, or reference to a commit SHA. That trade-off was rejected here: rotate the secrets so the exposed values stop being valid, but leave history intact.

## Decision

**Rotate, don't rewrite history.** Every credential above was rotated to a fresh, randomly generated value (`openssl rand -hex`), applied both in configuration and against the actual running Postgres/RabbitMQ instances (`ALTER USER ... WITH PASSWORD`, `rabbitmqctl change_password`) — changing an env var alone does not rotate a password on an already-initialized data volume, so this had to be done in-place against the live containers, not just in `docker-compose.yml`.

- **`docker-compose.yml`**: every hardcoded value replaced with `${VAR}` interpolation (`${DB_PASSWORD}`, `${RABBITMQ_PASSWORD}`, `${JWT_SECRET}`, `${APP_JWT_SECRET}` for auth-service specifically, since it's the only service with a real consumer for that one — see the "orphan config" finding below).
- **`.env`** (new, gitignored — confirmed already covered by the existing `.gitignore` entry) holds the real rotated values. **`.env.example`** (tracked) documents the required variable names with `changeme` placeholders.
- **The four Spring services' `application-dev.properties` / `application.properties`** no longer contain any real secret value. Each now reads `spring.datasource.password=${DB_PASSWORD:local_dev_placeholder}` (and the equivalent for `spring.rabbitmq.password` / auth-service's `app.jwt.secret`) — a non-sensitive, clearly-fake fallback if the real env var isn't set. This is the same pattern already used by the pre-existing `jwt.secret=${JWT_SECRET:default-secret-key}` line in `application.properties`, just extended to the properties that were still hardcoded.
- **Three integration test files** that connect to a real RabbitMQ with a hardcoded password literal (`JwtCreatedFanoutIntegrationTest` in orders-service, `VerifyEmailOutboxIntegrationTest` and `VerifyEmailOutboxHappyPathIntegrationTest` in auth-service — the latter two added by ADR-0003) had that literal updated to the new rotated value, since they don't load a Spring context and so don't benefit from the `${VAR}` mechanism above.

## Orphan JWT configuration found and removed

Tracing every consumer of `app.jwt.secret` (`grep` across all four services' `src/main`) found it — along with its siblings `app.jwt.expiration-ms` and `app.jwt.issuer` — has a real code consumer (`JwtProperties`, `VerificationTokenService`) **only in auth-service**. products-service, orders-service, and billing-service each carried an identical, unused copy of all three properties (billing-service's `app.jwt.issuer` was even still set to the literal string `auth-service`, never adapted — clear evidence of copy-paste with no review). These three dead blocks were removed entirely from products-service, orders-service, and billing-service, rather than just rotating a secret value nothing reads. auth-service's copy is the one real, live copy and was rotated in place, not removed.

Separately, the plain `jwt.secret=${JWT_SECRET:default-secret-key}` property (distinct from `app.jwt.secret`) also has a real consumer only in auth-service (`JwtService`) by the same evidence standard — products-service/orders-service/billing-service likely carry this as equally dead configuration too, consistent with the project's "no local JWT signature verification" architecture (services trust a broadcast token cache instead — see the main README's cross-service auth notes). This was **not** removed here: confirming it definitively and removing the `JWT_SECRET` env var from three services' `docker-compose.yml` blocks was judged a separate cleanup decision from credential rotation, left open.

## Verification

- `docker compose config --quiet` — validates `docker-compose.yml`'s syntax and `${VAR}` substitution against `.env` without errors.
- Real credential rotation confirmed against the live containers: `SELECT 1` via `psql` with the new Postgres password, and `rabbitmqctl authenticate_user` with the new RabbitMQ password, both succeeded.
- Full test suite re-run per service after rotation: auth-service 4/4, orders-service 5/5, products-service 1/1, billing-service 2/2 — all passing, including billing-service's `BillingServiceApplicationIT` (renamed from `BillingServiceApplicationTests` under ADR-0007's Surefire/Failsafe split), a real `@SpringBootTest` that boots a full Spring context against live Postgres/RabbitMQ, confirmed both failing (without the new env vars set) and passing (with `.env` sourced) — proving the fallback-to-placeholder behavior works as intended rather than silently succeeding with stale credentials.

## Consequences

**Positive**: no real secret value is committed to the repository going forward; the mechanism (`.env` + `${VAR:placeholder}`) is consistent across all four Spring services and matches the pattern the codebase already used for `JWT_SECRET`; three services lost dead, copy-pasted JWT configuration they never used.

**Negative / residual**:
- The old, now-rotated values remain permanently readable in this public repository's git history. This is accepted, not fixed — see the Decision section's reasoning against history rewriting.
- Running any service locally outside Docker (`mvn test`, `mvn spring-boot:run`) now requires `DB_PASSWORD` / `RABBITMQ_PASSWORD` (and `APP_JWT_SECRET` for auth-service) to be set as real environment variables — e.g. by sourcing `.env` — for anything that actually connects to Postgres/RabbitMQ. Before this change, those commands worked with no setup because the real credentials were hardcoded. This is a deliberate trade-off, not an oversight.
- Whether `JWT_SECRET`/`jwt.secret` is genuinely dead configuration in products-service, orders-service, and billing-service (mirroring the `app.jwt.secret` finding above) was not conclusively resolved — left open for a future decision.

## References

- ADR-0003 (outbox pattern for auth-service) and ADR-0004 (auth-service schema authority fix) — the work during which `docker-compose.yml` was reopened and this exposure was found.
- Baseline audit (2026-07-03 entry in this repo's history) — did not catalogue this specific finding; hardcoded secrets in `docker-compose.yml` were present from the project's first commit and went unnoticed until this ADR.
