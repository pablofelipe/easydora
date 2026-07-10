# ADR-0028: notification-service authentication and ownership check

## Status

Accepted - 2026-07-10

## Context

`notification-service`'s `GET /notifications/{orderId}` had no
authentication or authorization check at all — it was, unlike every other
service in this project, the one HTTP surface with zero identity concept.
Anyone who knew or guessed an `orderId` (a UUID, but still enumerable via
other means — e.g. observing one's own orders) could read that order's
entire notification history, including the buyer's name and email captured
from `order.created`. A real IDOR (Insecure Direct Object Reference).

ADR-0027 had just established that identity for business decisions must
come exclusively from the JWT principal, never a client-supplied header —
but `notification-service` (FastAPI/Python) had no principal to derive
identity from in the first place: it never had a `JwtAuthenticationFilter`
equivalent, since it never needed one before this endpoint existed.

## Decision

**Give notification-service its own JWT broadcast cache**, mirroring the
same pattern every Spring service already uses (`JwtConsumer` +
`ConcurrentHashMap<token, JwtUserInfo>`), not local JWT signature
verification:

- `app/auth.py` (new): `JwtCache` (a thread-safe `token -> {userId, email,
  role}` map) and `AuthenticatedUserDependency`, a FastAPI dependency that
  resolves `Authorization: Bearer <token>` against that cache, raising 401
  for a missing/malformed header or an uncached token.
- `app/rabbitmq.py`: binds a new queue (`notification.jwt.created.queue`)
  to `auth.exchange`/`jwt.created` — the same broadcast every other
  service already consumes — and populates the cache on each message,
  going through the same retry/DLQ policy (ADR-0022) as the two existing
  consumers.
- `main.py`: `GET /notifications/{orderId}` now depends on
  `get_authenticated_user`, then compares the authenticated `userId`
  against the order's real buyer id — read directly from that order's own
  `order.created` notification payload (already persisted, already
  carries the real `userId` from the event, never a client-supplied
  value) — returning 403 on a mismatch. A missing `order.created` row
  (so no buyer id can be determined) also fails closed to 403, not 200.

**Why broadcast-cache over local JWT signature verification**: this
service already runs a RabbitMQ consumer thread; extending it to one more
routing key is a few lines using existing, already-tested infrastructure
(connection retry, resilience policy). Verifying the signature locally
would require adding a new shared secret to this service's environment
and a JWT library it doesn't currently depend on, for no benefit over the
mechanism already proven across four other services — and it would make
this one service behave differently from the rest for no architectural
reason. Consistent with this project's own preference for the smallest
change that fits the existing pattern (see
[architectural-principles.md](../architecture/architectural-principles.md)).

**Contract unchanged**: same URL (both the bare and `/notification`-prefixed
variants), same JSON response shape, same 404 for a genuinely nonexistent
order. Only `Authorization` is now required, and a non-owner gets 403
instead of the same 200 everyone got before.

## Verification

- TDD, red-green: new `tests/test_notification_authorization.py` (no real
  Postgres/RabbitMQ — `repository` and `jwt_cache` swapped for fakes
  directly on the `app.main` module, matching this service's existing
  unit-test style). Confirmed red first: 4 of 6 assertions failed against
  the unmodified endpoint (200 where 401/403 was expected), proving the
  IDOR was real. Green after the fix: 6/6, and the full unit suite (26
  tests, `pytest -m "not integration"`) passed with no regressions once
  `run_consumer`/`consume_forever`'s new `jwt_cache` parameter was
  threaded through the two integration test files that call them
  directly.
- Live re-verification against a real running stack (Postgres, RabbitMQ,
  auth-service, orders-service, notification-service, fresh Docker
  builds): two real buyers signed up and logged in; buyer A created a
  real order; buyer A reading `/notifications/{orderId}` got 200 with the
  real payload; buyer B (a different authenticated user, real token) got
  403; no `Authorization` header at all got 401.

## Consequences

**Positive**: closes the Critical/High-adjacent IDOR this project's own
Roadmap review flagged. notification-service's authentication model is
now structurally consistent with every other service instead of being the
one exception.

**Negative / known limitations**:
- Same limitation every other broadcast-cache service already has and
  documents: a notification-service restart wipes its token cache, and a
  previously-issued JWT becomes unusable against this service until the
  user's `jwt.created` is replayed (next login) or reprocessed. Not new
  behavior introduced by this ADR — this service simply now has the same
  limitation the rest of the project already accepted.
- `order.status-changed` notifications are still checked via the same
  order's `order.created` row for ownership; if that row was somehow lost
  (e.g. a payload key changed upstream without a schema/contract test
  catching it — see the Roadmap's contract-testing gap), every lookup for
  that order fails closed to 403 rather than crashing, but also rather
  than serving a legitimate owner. Considered acceptable: fail-closed is
  the correct default for an authorization check.

## References

- [ADR-0027](0027-jwt-principal-as-sole-identity-source.md) - the
  JWT-principal-as-identity-source decision this ADR extends to a service
  that had no principal-based auth of any kind before.
- [ADR-0022](0022-notification-service-consumption-resilience.md) - the
  retry/backoff/DLQ policy the new `jwt.created` consumer reuses
  unchanged.
- README Roadmap - the High item this ADR closes.
