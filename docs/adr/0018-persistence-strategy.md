# ADR-0018: Persistence strategy — shared PostgreSQL instance, schema-per-service ownership

## Status

Accepted - 2026-07-08

## Context

Microservices architectures face a recurring persistence decision:
**Database-per-Service** (each service owns a physically separate database
instance) versus **Shared Database** (services share an instance, isolated
some other way). This project has run with an answer to that question
since its first commit, but never as a reviewed, explicit decision — the
README has long carried a one-line disclaimer ("database per service is
not actually true") without an ADR backing it. This ADR
closes that gap: it registers, deliberately and explicitly, a decision
that has been implicit all along.

Confirmed against the current repository, not assumed:
- **One PostgreSQL instance.** `docker-compose.yml` declares exactly one
  `postgres` service; every Spring Boot service, `inventory-service` (Go),
  and `notification-service` (Python) connect to the same host/port/
  database (`easydora`).
- **One schema per service.** `init-scripts/01-create-schemas.sql`
  provisions `auth_schema`, `products_schema`, `inventory_schema`,
  `orders_schema`, `billing_schema`, `notification_schema` — one per
  service, matching the six services that have their own persistence at
  all.
- **No production code crosses a schema boundary.** Grepped every
  service's `src/main`/production source for another service's
  `<schema>.` prefix — zero hits. The only places a schema boundary is
  crossed directly are three test fixtures (`e2e-tests`'
  `CatalogOnboardingE2ETest`/`OrderLifecycleE2ETest`, already documented in
  ADR-0013's "why the intermediate Seller check reads Postgres directly";
  and `notification-service/tests/test_order_created_flow.py`, which seeds
  a row into `auth_schema.users` directly rather than driving a real
  signup, with its own docstring admitting exactly that). All three are
  test-setup shortcuts for prerequisite state, never the flow under test,
  and never production code — an accepted convention this project already
  uses elsewhere, not a new exception.
- **Communication between bounded contexts is exclusively events or public
  APIs.** No `RestTemplate`/`WebClient`/`FeignClient` exists in any Spring
  service's production code. The one synchronous exception in the entire
  system is `notification-service`'s call to auth-service's public
  `GET /users/{id}/notification-profile` endpoint (ADR-0014) — a single,
  deliberate exception, not a pattern.

## Decision

Formally adopt, as a reviewed architectural decision rather than an
unexamined default:

- **One PostgreSQL instance** serves every service that needs relational
  storage.
- **One schema per service**, and each service is the exclusive owner of
  its schema — no other service's code writes to it, and in production no
  other service's code reads from it either.
- **All cross-service communication happens through events (RabbitMQ) or
  public APIs** — never through a shared table, a cross-schema query, or a
  cross-schema join.

**The architectural boundary is the ownership of data, not the physical
database instance.** A service "owning" its data means no other service's
code touches it directly, regardless of which physical instance the bytes
happen to live on.

## Rationale

### 1. Data ownership is the relevant principle

The property that actually matters in a microservices architecture is
*who is allowed to read and write a given piece of data*, not how many
physical database processes are running. This project already enforces
that: every table belongs to exactly one schema, and exactly one service's
codebase ever queries it (see Context, verified by grep, not assumed).
**The architectural boundary is the ownership of data, not the physical
database instance** — splitting the schemas across separate Postgres
instances would not change who owns what; it would only change where the
bytes are stored.

### 2. Architectural properties preserved

None of the properties a microservices architecture is actually supposed
to deliver depend on there being more than one Postgres process:

- **Logical isolation** — every service's tables live in their own named
  schema, never mixed with another service's.
- **Ownership** — exactly one codebase writes to each schema.
- **Event-driven integration** — every cross-service fact already travels
  as a RabbitMQ event, not a query.
- **Asynchronous communication** — the whole system already operates this
  way (see [ADR-0007](0007-remove-kafka-broker.md)).
- **No cross-service joins** — nothing in this codebase ever joins across
  two services' tables in a single query; each service only ever queries
  its own schema.
- **No direct access to another service's data** — confirmed above, in
  production code.

All six of these hold today, on one shared instance. Splitting the
instance would not add a single one of them — they are already fully
realized by schema ownership and event-driven integration alone.

### 3. Trade-offs, honestly

Database-per-Service would genuinely provide things this project doesn't
have today:
- **Operational isolation** — a Postgres outage or resource-exhaustion
  incident in one service's database can't directly affect another
  service's database, since they'd be different processes (possibly
  different hosts).
- **Independent upgrades** — each service could move to a different
  Postgres major version, extension set, or tuning profile on its own
  schedule.
- **Independent backup/recovery strategies** — a service with different
  retention or point-in-time-recovery needs could have them, instead of
  inheriting one instance-wide policy.

Against that, adopting it here would introduce real, concrete costs:
- A larger `docker-compose.yml` — six Postgres services (or more) instead
  of one, each with its own volume, healthcheck, and credentials.
- A more complex CI pipeline — Phase 2/Phase 3 jobs (ADR-0012/ADR-0013)
  already provision Postgres per matrix entry; multiplying that per
  service compounds runner time and YAML surface for no behavior this
  project currently needs.
- Duplicated connection configuration across every service instead of one
  shared `DB_HOST`/`DB_PASSWORD` pattern.
- Multiple independent migration histories to keep straight (already true
  in spirit — each service owns its own Flyway/`init.sql` migrations, see
  ADR-0004/ADR-0011 — but today they all apply against one running
  instance, which is simpler to reason about locally than six).
- More operational effort to stand up, monitor, and reason about six (or
  more) database processes instead of one.
- More cognitive load for anyone studying this repository — a reader
  already has to track six schemas; six separate instances would add
  infrastructure ceremony without adding architectural insight.

### 4. What this project is actually for

EasyDora is an architecture and portfolio project. Its purpose is to
demonstrate microservices boundaries, event-driven integration, data
ownership, cross-bounded-context coordination, testing discipline, and
architectural decision-making — not to demonstrate distributed database
infrastructure operations (replication, multi-instance failover, per-service
backup tooling). This decision optimizes the ratio between architectural
value demonstrated and operational complexity introduced: every property
this project sets out to show is already fully present with one instance,
so adding more instances would spend real complexity to teach nothing new.

## Alternatives considered

**One PostgreSQL instance per service** was considered and rejected, for
now. It was rejected because none of the six architectural properties in
Rationale §2 depend on it, while every cost in Rationale §3 would be paid
in full — for a portfolio project whose goal is demonstrating patterns
(§4), that trade is not worth making today.

This could be revisited if any of the following become true:
- A real operational incident demonstrates the shared instance is an
  actual blast-radius problem in practice, not just a theoretical one.
- A specific service's data volume, traffic, or tuning needs genuinely
  diverge enough from the others that sharing an instance becomes a real
  constraint, not a hypothetical one.
- The project's goal shifts from demonstrating architectural patterns
  toward demonstrating distributed operations themselves (mirroring the
  same kind of goal shift [ADR-0007](0007-remove-kafka-broker.md) documents
  for the Kafka-removal decision).

## Consequences

**Positive**:
- Simpler architecture — one database process to reason about instead of
  six or more.
- Lower operational cost — one set of credentials, one connection
  configuration pattern, one volume, one healthcheck to maintain.
- Lower cognitive load for anyone studying or extending the project.
- Easier local execution — `docker-compose up -d` brings up one database
  instead of provisioning and waiting on several.
- Simpler CI — Phase 2/Phase 3's per-job Postgres service containers stay
  small and fast (ADR-0012/ADR-0013).
- Every architecturally relevant property (Rationale §2) is preserved in
  full.

**Negative**:
- Less operational isolation — a single Postgres outage affects every
  service simultaneously; there is no physical blast-radius containment.
- The schema-ownership boundary is enforced by convention and code review,
  not by the database itself: `init-scripts/01-create-schemas.sql` grants
  the same `admin` user `ALL PRIVILEGES` on every schema, so nothing at
  the Postgres ACL level would stop a service from querying another
  service's tables — only the fact that no production code does so today.
  Exclusive ownership is currently enforced by architectural rules and
  code reviews rather than by database credentials. This requires ongoing
  architectural discipline, not a one-time fix.
- Rigorous schema ownership must be maintained deliberately going forward;
  the three test-only cross-schema reads/writes found while writing this
  ADR (see Context) are an accepted convention, not a precedent to expand
  casually — any *production* code crossing a schema boundary would
  contradict this decision.

## References

- [Architecture Overview](../architecture/overview.md) — describes the
  current persistence architecture (single instance, schema-per-service);
  this ADR is where the reasoning behind it lives, per that document's own
  Persistence section.
- [ADR-0004](0004-auth-service-schema-authority-fix.md) and
  [ADR-0011](0011-flyway-schema-authority-all-services.md) — established
  Flyway as the schema authority *within* each service's own schema; a
  related but distinct decision from *whether* to share a physical
  instance, which this ADR covers.
- [ADR-0013](0013-ci-phase-3-cross-service-e2e.md) — the original precedent
  for a test seeding prerequisite state via a direct schema query instead
  of a full API-driven setup, the same convention this ADR's Context
  section confirms is still in use (and bounded to tests) today.
- [ADR-0007](0007-remove-kafka-broker.md) — the closest precedent for this
  ADR's shape: a decision justified primarily by what the project's goal
  actually requires, with the technical trade-offs as supporting detail
  rather than the primary driver.
- [Architectural Principles](../architecture/architectural-principles.md)
  — principle #4 ("behavior over technology") and principle #2 ("a
  component must earn its place") both apply directly to why a second
  physical database instance isn't justified here.
