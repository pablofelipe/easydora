# ADR-0039: Broadcast JWT cache — formalizing the restart-recovery limitation and adding a token-lifetime TTL

## Status

Accepted - 2026-07-15

## Context

A targeted architecture review of the broadcast-JWT-cache authentication
model (`auth-service` publishes `jwt.created`; `orders-service`,
`products-service`, `billing-service` via `JwtAuthenticationFilter`'s
`ConcurrentHashMap<String, JwtUserInfo>`, and `notification-service` via
`JwtCache` in `app/auth.py`, each cache the raw token without verifying its
signature locally) found that the model's best-known limitation — a
service restart wipes its cache, and a previously-issued token becomes
unusable against that service until the user's `jwt.created` is replayed
by a fresh login — has never had its own ADR. It is currently only
mentioned as an aside inside two ADRs about other subjects:
[ADR-0027](0027-jwt-principal-as-sole-identity-source.md)'s Consequences
section and [ADR-0028](0028-notification-service-authentication.md)'s
Consequences section, both phrased as "same limitation every other
broadcast-cache service already has."

The same review found one asymmetry never previously identified: none of
the four caches has any expiration of its own. Each entry is removed only
when the owning service restarts — never when the underlying JWT's own
`expiresIn` (a field `JwtCreatedEvent` already carries) elapses. A token
whose JWT has genuinely expired therefore continues to authenticate
successfully against any service that hasn't restarted since it was
cached, for as long as that service stays up. This is the mirror image of
the already-accepted "restart invalidates a still-valid token" limitation,
and was not previously documented anywhere.

Also confirmed: no test, in any of the four services, exercises the
cache-miss scenario (a syntactically valid token absent from the local
cache because of a restart). The behavior is implemented consistently
(`JwtAuthenticationFilter`/`AuthenticatedUserDependency` both reject a
cache-miss as an unauthenticated request), but it has never been verified
by an automated test — only by the reasoning recorded in ADR-0027/ADR-0028.

## Decision

**Keep the broadcast-JWT-cache model exactly as it is, for all four
services — no local JWKS signature verification, no persisted or shared
cache.** This ADR does not change the authentication mechanism itself; it
formalizes a decision that was already de facto in place, gives it a
single dedicated record, and closes the one real gap the review found.

Rejected explicitly, so this doesn't need re-litigating on the next
review:

- **Local JWKS-based signature verification**, replacing the broadcast
  entirely. Technically the more conventional choice, but it would
  discard the one part of this project that demonstrates a genuine
  distributed-systems trade-off (eventual consistency of identity via
  message broadcast, with a real, visible failure mode) in favor of a
  textbook-correct mechanism with nothing left to discuss in an
  architecture review. Rejected for this project's stated purpose, not
  because it is technically inferior in general.
- **A persisted or shared cache (Postgres-backed, or Redis-backed) so a
  restart no longer wipes it.** Would fix the restart-recovery gap
  directly, but introduces a new stateful dependency (Redis has never
  earned a place in this system for any other reason — see the "Objective
  criteria" below for exactly when it would) to solve a problem whose
  current workaround — the affected user logs in again — costs the user
  one request and costs the system nothing. Not worth the new component
  for a demo/portfolio-scale project with no concurrent-instance
  requirement.

**Adopted, to close the one real gap found:** each cache entry gets a
lifetime equal to the JWT's own `expiresIn` (already present on
`JwtCreatedEvent`, already received by every consumer), instead of no
lifetime at all. This removes the asymmetry where a token can outlive its
own JWT's expiry as a cached credential. It does **not** change the
restart-wipes-cache behavior, which remains exactly as accepted in
ADR-0027/ADR-0028.

**Not implemented by this ADR** — tracked as open follow-up work, not
bundled into this decision record:

- The `expiresIn`-based TTL itself, in all four caches.
- An automated test, in at least one of the four services, that exercises
  the cache-miss-by-restart scenario end-to-end (today verified only by
  design reasoning, in this ADR and its two predecessors).

## Objective criteria for revisiting this decision

In the same spirit as [ADR-0037](0037-consolidated-outbox-pattern-specification.md)'s
own deferred items:

- **Any service in this project ever needs to run more than one replica.**
  At that point the per-instance in-memory cache breaks in a second way
  beyond restart (a token cached on instance A is invisible to instance
  B, regardless of restarts) — a shared cache (Redis, or equivalent)
  would earn its place at that point, not before, and not merely to
  demonstrate the technology.
- **A production-scale (not demo-scale) uptime requirement is ever adopted
  for this project**, where asking a user to log in again after a restart
  stops being an acceptable cost. Not the case today.

## Consequences

**Positive**: the broadcast-JWT-cache model's best-known limitation now
has one dedicated, citable decision record instead of being an aside
inside two unrelated ADRs; the one real asymmetry the review found (no
token-lifetime TTL on the cache itself) now has a concrete, low-cost fix
path defined, even though not yet implemented.

**Negative / known limitations, not fixed here**:
- Restart still wipes the cache; recovery is still "the user logs in
  again." Unchanged from ADR-0027/ADR-0028, by design.
- The `expiresIn` TTL and the cache-miss test described above remain
  open work, tracked on the README Roadmap, not implemented by this ADR.

## References

- [ADR-0027](0027-jwt-principal-as-sole-identity-source.md) — first
  documented the restart-wipes-cache limitation, as an aside.
- [ADR-0028](0028-notification-service-authentication.md) — repeated the
  same limitation for notification-service's own cache, also as an aside.
- [ADR-0037](0037-consolidated-outbox-pattern-specification.md) — the
  "objective criteria for revisiting" format this ADR follows for its own
  deferred items.
