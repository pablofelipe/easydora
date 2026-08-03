# Versioning and Release Policy

This is project governance, not architecture: it documents how EasyDora is
versioned and released, not how the system itself is designed or behaves.
It deliberately does not live in `docs/adr/` — nothing here changes,
constrains, or explains the running system, which is the bar every ADR in
this project has to clear. See `docs/architecture/` for how the system
actually works.

## Context

Two releases had already been tagged (`v0.1.0`, `v0.2.0`) with no
documented policy for what a version number means in this project, nor a
checklist for what must stay in sync at release time. This surfaced as two
concrete gaps while preparing `v0.3.0`:

- Every `pom.xml`'s own version (the root `easydora-parent`, and every
  child module through parent-version inheritance) had been left at
  Maven's untouched `0.0.1-SNAPSHOT` default since the repository's
  creation, as had `frontend/package.json`/`package-lock.json` (`0.0.1`)
  and `e2e-tests/pom.xml` (a spurious, unrelated `1.0.0`) — none of these
  ever tracked the Git tag that is the project's actual released version.
  A jar built from the `v0.2.0` tag reported itself as `0.0.1-SNAPSHOT`, a
  direct contradiction: `SNAPSHOT` means "never released" in Maven's own
  convention.
- There was no criterion for when `1.0.0` would be warranted, beyond an
  intuitive sense of "feels done" — a genuine risk for a project whose own
  README Disclaimer states it is "not intended to be a production-ready
  commercial platform." Reaching for `1.0.0` on the popular reading of
  that number (production-ready) would contradict this project's own
  stated purpose; reaching for it purely because a large number of
  features had shipped would repeat the well-known anti-pattern of
  inflating a version number to look more mature than the underlying
  contract actually is.

## Single source of truth for the version

The Git tag is this project's one version. Every `pom.xml` (root and all
child modules: `auth-service`, `orders-service`, `billing-service`,
`products-service`, `correlation-commons`, `e2e-tests`) and
`frontend/package.json`/`package-lock.json` must carry that same version —
never left at a generator's default.

Container images (Docker Compose, `k8s/`) are intentionally out of scope
while they are not published to a registry — every image today is built
and tagged `:local`/`:latest` for local use only
([ADR-0040](../adr/0040-minimal-kubernetes-kind-deployment.md)), so there
is nothing yet for a stale image tag to misrepresent. If any image is ever
published to a registry, image tags should be brought under this same
policy — until then, don't "fix" this; it isn't broken.

## Versioning philosophy: what 1.0.0 means here

EasyDora is not a commercial product; it is an engineering demonstration
project (see README Disclaimer). Accordingly, the meaning of `1.0.0` is
architectural, not commercial:

> Version 1.0.0 marks the point where the project's core architectural
> decisions have stabilized and subsequent releases become predominantly
> additive, rather than introducing or revising cross-cutting architectural
> patterns. This is not "production-ready commercial software" — the
> repository remains an educational and portfolio project.

Below `1.0.0`, any release may introduce or revise a cross-cutting
architectural decision, and MINOR bumps are unrestricted — standard pre-1.0
SemVer semantics. At and after `1.0.0`, the same test governs MAJOR bumps: a
new or substantially-revised cross-cutting architectural decision requires
one; purely additive work stays MINOR; fixes stay PATCH.

## Objective definition of "architectural change"

The operative test is the last bullet below; the others are illustrative of
the kind of change that usually requires it, not an independent trigger.

Counts as architectural:
- a new cross-service pattern;
- replacing an existing pattern;
- a change in messaging strategy, authentication model, observability model
  (e.g. adding a new signal type such as a tracing backend — not just a new
  metric within the existing model), or persistence strategy;
- **any change that requires a new ADR, or substantially revises an
  existing one** — meaning it changes the ADR's actual Decision or
  Consequences, not just documents extending the same already-accepted
  decision to a new instance, and it actually changes how the system
  behaves (see the Kubernetes example below for a case that fails this
  test despite having its own ADR).

Does not count, even when it produces a commit, a PR, or an ADR "Update"
section:
- new endpoints or screens;
- new metrics or dashboards;
- UX improvements;
- new tests or documentation;
- bug fixes;
- rolling out an already-approved pattern to another service (e.g.
  [ADR-0037](../adr/0037-consolidated-outbox-pattern-specification.md)'s
  Outbox pattern reaching a fourth service was not architectural; the
  ADR-0037 update that first specified the pattern's shape was);
- adopting a new execution/deployment platform for the *same* architecture,
  with no cross-cutting pattern changed (e.g.
  [ADR-0040](../adr/0040-minimal-kubernetes-kind-deployment.md)'s
  Kubernetes deployment — its own text states "this decision does not
  change the application's architecture").

## Architecture Stability tracker

Updated at every tagged release. Resets to 0 whenever a release contains
at least one architectural change per the definition above.

```text
v0.4.0
Structural ADRs: 1 (ADR-0024's 2026-08-02 Update — adopting OpenTelemetry
  and a Jaeger backend is a new observability signal type, additive to
  CorrelationId rather than replacing it, not just a new metric within
  the existing model)
Consecutive non-structural releases: 0

v0.3.0
Structural ADRs: 1 (ADR-0038's 2026-07-20 update — RabbitMQ topology
  redeclaration on reconnect and publisher confirms in the Outbox
  publisher are a real revision of the messaging resilience strategy,
  not just an extension of an already-decided one)
Consecutive non-structural releases: 0
```

Note: ADR-0040 (Kubernetes) was evaluated against the definition above and
does **not** count as structural — it explicitly does not change the
application's architecture, only how the existing one is executed. It is
included here as a worked example of the rule holding up against a case
that looks architectural at first glance but isn't.

Two to three consecutive releases at 0 is the signal this project is
treated as having entered its incremental-evolution phase — i.e., a strong
candidate for `1.0.0`.

## Release checklist

In this order:

1. CI green on the exact commit being tagged.
2. `CHANGELOG.md` entry for the new version.
3. Release Notes drafted.
4. `README.md`'s ADR index updated, if the release closed or added an ADR.
5. `README.md` updated elsewhere, where the release affects setup, service
   behavior, or the Service Status table.
6. Version bumped identically across every `pom.xml` and
   `frontend/package.json`/`package-lock.json`.
7. Tag created.
