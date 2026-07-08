# ADR-0016: Shared Maven parent POM for the four Spring Boot services

## Status

Accepted - 2026-07-07

## Context

auth-service, products-service, orders-service, and billing-service each declared `spring-boot-starter-parent` directly and hand-duplicated everything common between them: `java.version`, five identical `spring-boot-starter-*` dependencies, `flyway-core`, `spring-boot-starter-amqp`, `com.networknt:json-schema-validator` (pinned to `1.5.1`, test-scoped), and `spring-boot-maven-plugin`. This was concretely expensive twice already: removing `spring-kafka` (ADR-0007) and `maven-failsafe-plugin` (ADR-0008's update) both required hand-editing all four `pom.xml` files with no automatic detection if one drifted.

Inspecting all four `pom.xml` files surfaced one real divergence: billing-service's `spring-boot-starter-parent` was already at `3.2.12`, while the other three were on `3.2.0` — an inconsistency nobody had deliberately introduced.

## Decision

### A real (inheritance) parent, not just a documented convention

A new `pom.xml` at the repository root: `packaging=pom`, `groupId=com.easydora`, `artifactId=easydora-parent`, itself parented on `spring-boot-starter-parent:3.2.12` — the newer of the two versions found, chosen to bring auth-service/products-service/orders-service up to what billing-service already had, rather than downgrading billing-service. Deliberately **not** a multi-module reactor: there is no `<modules>` list, so each service still builds independently via `cd <service> && mvn ...`, exactly as before — CI's per-service `working-directory` jobs and every developer's local workflow are unaffected.

The parent declares directly (not just in `dependencyManagement`) every dependency and plugin that was byte-identical across all four: the five `spring-boot-starter-*` artifacts, `flyway-core`, `spring-boot-starter-amqp`, `json-schema-validator:1.5.1` (test), and `spring-boot-maven-plugin` — children inherit these with no re-declaration at all. `maven-failsafe-plugin` goes in `<pluginManagement>` only, since products-service deliberately has no `*IT` classes and no Failsafe execution (ADR-0008); the other three children opt in by declaring the plugin (without a version) in their own `<build><plugins>`.

Each child's `pom.xml` keeps only what's genuinely its own: `jjwt-*`/`spring-security-crypto`/`h2` (auth-service), `spring-statemachine-core`/`hibernate-core`/`spring-boot-starter-actuator` (orders-service), `spring-boot-starter`/`spring-tx`/`jakarta.persistence-api`/`spring-rabbit` (billing-service), and each service's own `postgresql` dependency — left un-hoisted because auth-service and billing-service pin an explicit version (`42.7.3`) while products-service and orders-service rely on Spring Boot's managed default, a genuine (if likely accidental) difference not worth erasing as part of this change. billing-service's leftover Spring Initializr boilerplate (empty `<url/>`, `<licenses>`, `<developers>`, `<scm>`, and the generic `<description>Demo project for Spring Boot</description>`) was deleted while touching this file.

### Docker build context: repository root, not per-service

A real Maven parent resolved via `<relativePath>../pom.xml</relativePath>` only works if that file is actually present wherever `mvn` runs. Each service's Dockerfile previously ran `docker build ./<service>`, scoping the build context to that directory alone — the sibling root `pom.xml` would be invisible to it. Fixed by changing `docker-compose.yml`'s `build:` block for all four Spring services from `build: ./<service>` to:
```yaml
build:
  context: .
  dockerfile: <service>/Dockerfile
```
and updating each of the four Dockerfiles to preserve the repository's real relative layout inside the build context (`COPY pom.xml pom.xml`, `COPY <service>/pom.xml <service>/pom.xml`, `RUN cd <service> && mvn dependency:go-offline`, etc.), so `../pom.xml` resolves identically to how it does for a local `mvn` invocation. `inventory-service`, `api-gateway` (Go) and `notification-service` (Python) don't participate in this parent and were left untouched.

CI needed no changes at all: Phase 1/2/3 jobs already run `mvn` directly with `working-directory: <service>` against a full `actions/checkout`, so `relativePath` resolution already worked there before this change.

## Verification

- `mvn test` (auth-service, products-service, orders-service) and `mvn verify` (billing-service) all green after the parent-POM swap and the 3.2.0 → 3.2.12 bump — no regression in any of the four services' existing test suites.
- `docker compose build auth-service products-service orders-service billing-service` — confirmed the new context/Dockerfile wiring actually resolves `easydora-parent` inside each build stage (the first attempt without the context change would fail outright: the parent simply wouldn't exist inside the old, service-scoped build context).
- `docker compose up -d` — all 7 implemented services (the four Spring services plus api-gateway, inventory-service, notification-service) and Postgres/RabbitMQ reported `healthy` simultaneously.
- `mvn test -Dtest=OrderLifecycleE2ETest` and `-Dtest=CatalogOnboardingE2ETest` (Phase 3 e2e, against the rebuilt containers) both green — confirming the rebuilt images behave correctly end to end, not just that they compile.

## Consequences

**Positive**: adding a dependency or plugin common to all four services is now a one-line change in `pom.xml` at the repository root instead of a four-file hand-edit — directly closing the gap that made the Kafka removal (ADR-0007) and the Failsafe cleanup (ADR-0008) more tedious than they needed to be. The 3.2.0/3.2.12 version drift is gone; all four now build against the identical Spring Boot patch version.

**Not fixed here / known limitations**:
- This is inheritance only, not a build-orchestration parent — there's still no single command that builds/tests all four services together (each is invoked independently, by design, matching how CI and Docker already worked before this change).
- Docker's build cache for all four services was fully invalidated by the `COPY` path changes (the layer that runs `mvn dependency:go-offline` now keys off different file paths than before), so the first rebuild after this change re-downloads every Maven dependency from scratch rather than reusing a cached layer. Subsequent rebuilds are unaffected.
- `postgresql` driver version remains split (pinned in auth-service/billing-service, managed-default in products-service/orders-service) — a pre-existing inconsistency this change intentionally left alone rather than silently resolving as a side effect.

## References

- [ADR-0007](0007-remove-kafka-broker.md) and [ADR-0008](0008-surefire-failsafe-test-split.md) — the two prior changes that had to hand-edit all four `pom.xml` files, the concrete cost this ADR closes.
- [ADR-0015](0015-billing-service-jwt-and-auth-securityconfig-fix.md) — implemented alongside this change in the same pass, unrelated in substance but touching billing-service's `pom.xml` in the same commits.
- [Architectural Principles](../architecture/architectural-principles.md)
  — the inheritance-only (no reactor) design is the clearest instance of
  principle #11 (reduce cognitive load without losing architectural
  capability): it removes the four-file hand-edit cost without taking away
  each service's independent build.
