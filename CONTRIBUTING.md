# Contributing to Easydora

Thank you for considering a contribution to Easydora. This project is
maintained primarily as an educational and portfolio showcase of
event-driven microservices architecture, but genuine contributions —
bug reports, fixes, documentation improvements, or well-scoped
enhancements — are welcome.

Please read this guide before opening a pull request.

## Ground rules

- This is a polyglot repository (Go, Java/Spring Boot, Python/FastAPI).
  Match the conventions already established in the service you're
  touching rather than introducing a new style or a shared abstraction
  across services.
- Keep changes scoped. Prefer several small, focused pull requests over
  one large one that mixes unrelated concerns.
- Do not introduce a new cross-service shared library without discussing
  it first — this project deliberately keeps most business logic
  duplicated per service (see
  [docs/architecture/architectural-principles.md](docs/architecture/architectural-principles.md)).
  The two existing exceptions (`correlation-commons`,
  `correlation-commons-go`) carry no business logic and exist only to
  keep a cross-cutting identifier contract identical everywhere.

## Fork and branch workflow

1. Fork the repository and clone your fork locally.
2. Create a branch off `main` using a descriptive, prefixed name:
   - `feature/<short-description>` — new functionality
   - `fix/<short-description>` — bug fixes
   - `docs/<short-description>` — documentation-only changes
   - `chore/<short-description>` — tooling, CI, dependency maintenance
3. Keep your branch rebased on `main` as you work to minimize merge
   conflicts.

## Commit messages

- Write commit messages in English, in the imperative mood (e.g. "Fix
  stock reservation race condition", not "Fixed" or "Fixes").
- Explain *why* a change was made when it isn't obvious from the diff
  itself, not just what changed.
- Keep the subject line under ~72 characters; use the body for context
  when needed.
- Reference the relevant ADR number when a commit implements or closes
  a decision documented in `docs/adr/`.

## Pull request expectations

- Describe the problem being solved and the approach taken, not just a
  restatement of the diff.
- Link any related issue, ADR, or Roadmap item.
- Keep the PR's scope aligned with its description — unrelated cleanup
  belongs in a separate PR.
- Ensure CI passes (build, unit tests, and, where applicable,
  integration tests) before requesting review.
- Be responsive to review feedback; this is a portfolio project
  maintained on a best-effort basis, so review turnaround may take a
  few days.

## Coding standards

- **Go** (`api-gateway`, `inventory-service`): run `go vet ./...` and
  `gofmt` before committing. Prefer the standard library over adding a
  new dependency unless there's a clear reason.
- **Spring Boot** (`auth-service`, `products-service`, `orders-service`,
  `billing-service`): follow the existing package layout
  (`controller`/`service`/`repository`/`config`/`messaging`) and keep
  Flyway (`src/main/resources/db/migration`) as the single schema
  authority — never rely on `ddl-auto` to create schema, and never edit
  an already-applied migration in place; add a new one instead.
- **FastAPI** (`notification-service`): follow the existing module
  layout (`app/`) and keep domain logic separate from the RabbitMQ
  transport/consumer code.
- Avoid adding new abstractions, configuration flags, or "just in case"
  code paths that aren't exercised by the change at hand.

## Testing expectations

This project follows test-driven development where practical — write
or update the failing test before the implementation. At minimum:

- New behavior should come with unit tests in the affected service.
- Changes to messaging (producers/consumers, routing keys, event
  shape) should be validated against the relevant contract test where
  one exists (see [ADR-0002](docs/adr/0002-json-schema-contract-testing.md)),
  or a new one added if the event type has none yet.
- If your change touches wiring that CI's integration or end-to-end
  phases exercise (see [ADR-0012](docs/adr/0012-ci-phase-2-real-infrastructure.md)
  and [ADR-0013](docs/adr/0013-ci-phase-3-cross-service-e2e.md)), verify
  it against real Postgres/RabbitMQ locally before opening the PR.
- Do not mock away the boundary a test exists to verify (e.g. don't
  mock the database in a test meant to catch a migration or query bug).

## Documentation updates

- Update `README.md` if your change affects setup, service behavior, or
  the architecture diagram.
- Update the relevant file under `docs/` (`docs/walkthrough.md`,
  `docs/sequence-diagram.md`, `docs/architecture/overview.md`) if your
  change affects the business flow it documents.
- All committed documentation and code comments are written in English.

## Architecture Decision Records (ADRs)

Architectural changes should include a new ADR under `docs/adr/`
whenever appropriate — that is, whenever the change introduces a new
pattern, reverses or extends a prior decision, or makes a non-obvious
trade-off future contributors would otherwise have to re-derive from
the diff alone. Follow the numbering and structure of the existing
ADRs (Context, Decision, Consequences) and link it from the ADR table
in `README.md`.

Small, purely mechanical changes (formatting, dependency bumps, typo
fixes) do not need an ADR.

## Questions

If anything in this guide is unclear, open an issue describing what
you're trying to do — that's also a useful signal that the guide
itself needs improvement.
