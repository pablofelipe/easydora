# ADR-0042: Fast local dev loop via `docker compose watch`

## Status

Accepted - 2026-08-03

## Context

Every application service's Dockerfile is a multi-stage build: compile
(Maven/Go/npm) in one stage, copy only the built artifact into a minimal
runtime stage. This keeps production images small, but it means validating
a one-line code change against the real containers requires re-running the
*entire* build stage from scratch — `mvn clean package` (no incremental
compilation, since `clean` wipes `target/`), a fresh `go build`, or a fresh
`npm run build` — inside `docker build`, followed by a new image layer and
container recreation. On Docker Desktop for Windows this is slow enough
(the four Spring Boot services are the worst offenders: full Maven
recompilation plus a Spring Boot repackage every time) to be a real cost
during a multi-fix debugging session, and it was registered on the Roadmap
as open debt on 2026-08-02.

## Decision

Adopt `docker compose watch` — native to Docker Compose (this repo's
installed version, v5.3.0, supports it) — rather than introducing Tilt or
Skaffold. It requires no new tool to install, works against the exact same
`docker-compose.yml` already used for everyday `docker compose up`, and
covers what this project actually needs: syncing changed source into a
running container and triggering a lightweight in-place restart, without
a full image rebuild.

It is applied through a **separate override file**,
`docker-compose.watch.yml`, rather than folded into `docker-compose.yml`
itself:

```
docker compose -f docker-compose.yml -f docker-compose.watch.yml up --watch
```

This keeps the default `docker compose up` (what CI's Phase 2/3 and any
existing documented workflow already use) building the same production
image it always has — nothing about default behavior changes. The
override only redirects `build.target` to a new `dev` stage per Dockerfile
and adds each service's `develop.watch` rules.

### Why every service needed a `dev` Dockerfile stage, not just a watch rule

`docker compose watch`'s `sync` action only copies files into whatever
filesystem the running container already has. For the two Go services and
the four Spring Boot services, the production runtime stage never
contained a compiler or the source tree — only the final binary/jar — so
there was nothing for a synced `.go`/`.java` file to be recompiled *by*.
Each service's Dockerfile (except notification-service, see below) gained
a `dev` stage that extends its own `builder` stage (already named and
already holding the full toolchain, downloaded dependencies, and source):

- **Go (api-gateway, inventory-service)**: `dev` overrides `CMD` to
  `go run .` instead of the prebuilt `./main`. `inventory-service` had no
  named build stage at all before this (its Dockerfile only ever had one,
  implicit, undivided stage) — it gained an explicit `AS builder` name and
  a trailing empty `FROM builder AS final` stage, added purely so `dev`
  (now textually in the middle of the file) doesn't become the new default
  `docker build` target by virtue of being last. No behavior in that
  stage changed.
- **Spring Boot (auth/products/orders/billing-service)**: `dev` overrides
  `CMD` to `mvn -o spring-boot:run` (offline — the builder stage already
  ran `mvn dependency:go-offline` for both `correlation-commons` and the
  service itself, so no network access is needed at restart time). Watch's
  `sync+restart` action restarts the *container*, not `docker run` from
  scratch — the container's filesystem (including Maven's `target/classes`
  from the `builder` stage's `mvn clean package`) persists across restarts,
  so each `spring-boot:run` after the first recompiles only the changed
  `.java` files via Maven's own incremental compiler, not the whole
  module.
- **notification-service (Python/FastAPI)**: no Dockerfile change at all.
  Its existing single-stage image already contains the interpreter and
  installed dependencies; `uvicorn --reload` (added only in the watch
  override's `command:`) already watches its own working directory and
  hot-reloads the ASGI app in-process. `sync` (not `sync+restart`) is
  enough — the fastest path of any service here, no container restart at
  all.
- **frontend (SvelteKit)**: `dev` stage runs `vite dev` instead of the
  production `npm run build` + `node build`. Vite's own dev server already
  does HMR; `sync` just needs a container with `node_modules` already
  installed. Vite's default dev port (5173) had to be pinned to `3000`
  explicitly to match this project's existing published port mapping.

### `rebuild` rules for anything a running container can't absorb

Dependency manifests (`pom.xml`, `go.mod`/`go.sum`,
`package.json`/`package-lock.json`) and the two shared libraries
(`correlation-commons`, `correlation-commons-go`) all get `action: rebuild`
instead of `sync`/`sync+restart` — a running dev container only has
whatever dependency graph existed at its last `docker build`; there's no
way to add a new Maven/npm/Go dependency, or pick up a
correlation-commons change, into an already-running container without
rebuilding the image once.

## Consequences

**Positive**: the common case during a debugging session — editing
already-existing source files in one service — no longer pays for a full
multi-stage `docker build`. Editing a dependency manifest or a shared
library still falls back to `docker compose watch`'s own `rebuild` action
automatically (still faster to trigger than doing it by hand, since watch
detects the change itself). Zero new tools to install; the same
`docker-compose.yml` remains the single source of truth for production
container definitions.

**Negative / residual**:
- `dev`-stage images are larger and slower to build once (full JDK+Maven,
  full Go toolchain, `node_modules`) than their production counterparts —
  acceptable, since they're built once per session, not per code change,
  and never pushed anywhere.
- `mvn -o spring-boot:run`'s first startup after a `rebuild` is not
  meaningfully faster than today's `docker compose build && up` for that
  one service — the win is specifically on the *next* N source-only edits
  in the same session, not the first one.
- No automated test proves the `sync+restart`/`sync` cycle actually
  reflects a change end-to-end (e.g. hitting a modified endpoint and
  getting the new response) — validated manually per stack instead (see
  Verification). `docker compose watch` itself has no meaningful unit-test
  surface in this repository; it's tooling configuration, not application
  code.
- `notification-service`'s `--reload` and the frontend's `vite dev` are
  both weaker isolation than the Spring/Go containers: they hot-reload
  in-process, so a crash-on-import bug leaves the process in a different
  failure mode (an unhandled exception in the reloader) than a full
  container restart would. Acceptable for a local dev loop, not a change
  to how either service behaves outside `--watch`.

## Verification

- `docker compose -f docker-compose.yml -f docker-compose.watch.yml config
  --quiet` — validates the override file's syntax and merge against the
  base compose file.
- Manual `--watch` smoke test across all four stacks represented in this
  project (Go, Spring Boot, Python, SvelteKit): edited an existing source
  file in one service of each stack while `--watch` was running, confirmed
  the change was reflected without a manual `docker compose build`/`up`.

## References

- README Roadmap, "Opened 2026-08-02 (Low)" — the entry this ADR closes.
- ADR-0016 (shared parent `pom.xml`) and ADR-0035 (rejected DTO
  code-generation) — background on `correlation-commons`'s status as a
  sibling Maven project, not a reactor module, relevant to why its own
  source changes need `rebuild` rather than `sync`.
