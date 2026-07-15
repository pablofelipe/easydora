"""JWT-principal authentication, mirroring every other service's broadcast
JWT cache (see docs/architecture/overview.md) rather than local signature
verification: this service already has a RabbitMQ consumer thread, so it
consumes jwt.created the same way products-service/orders-service/
billing-service each do with their own JwtConsumer + in-memory token map.
No X-User-Id or any other client-supplied header is ever consulted.
"""

import threading
from datetime import datetime

from fastapi import Header, HTTPException
from prometheus_client import Counter

# Business metric (ADR-0036's Update): infra-level metrics can't answer
# whether the two known, accepted residual risks of this cache -- restart
# wiping it, and an entry outliving its own JWT's expiresIn until read --
# are actually happening at runtime, as opposed to only in a unit test.
jwt_cache_lookup_total = Counter(
    "jwt_cache_lookup_total",
    "Total JWT cache lookups, by outcome (hit, miss, expired).",
    ["outcome"],
)


class JwtCache:
    """Thread-safe token -> authenticated-user-info map, populated only by
    the RabbitMQ jwt.created consumer. A service restart wipes it, exactly
    like the Spring services' own in-memory caches -- an accepted, already
    documented limitation of the broadcast-JWT-cache pattern project-wide.

    Also keeps a second, userId-keyed view of the same data (see
    get_by_user_id) -- added so CachingAuthClient (app/auth_client.py) can
    serve order-notification enrichment (email/firstName/lastName) from
    this already-consumed broadcast, instead of always making a
    synchronous HTTP call to auth-service for data that already arrived on
    jwt.created. A caller with only a userId (never the token itself, e.g.
    process_order_created) has no other way to reach the token-keyed view.

    Each entry can carry an expires_at (ADR-0039): a lifetime equal to the
    JWT's own expiresIn, checked lazily by get()/get_by_user_id() and
    evicted from whichever index was just read. expires_at is optional
    (None means "never expires by TTL") so call sites that predate
    ADR-0039 -- or that only care about token-based authentication, not
    notification enrichment -- keep working unchanged.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._tokens: dict[str, dict] = {}
        self._by_user_id: dict[int, dict] = {}

    def add(
        self, token: str, user_id: int, email: str, role: str,
        first_name: str = "", last_name: str = "",
        expires_at: datetime | None = None,
    ) -> None:
        with self._lock:
            entry = {
                "userId": user_id,
                "email": email,
                "firstName": first_name,
                "lastName": last_name,
                "role": role,
                "expiresAt": expires_at,
            }
            self._tokens[token] = entry
            self._by_user_id[user_id] = entry

    def get(self, token: str) -> dict | None:
        with self._lock:
            entry = self._tokens.get(token)
            if entry is not None and self._is_expired(entry):
                del self._tokens[token]
                jwt_cache_lookup_total.labels(outcome="expired").inc()
                return None
            jwt_cache_lookup_total.labels(outcome="hit" if entry is not None else "miss").inc()
            return entry

    def get_by_user_id(self, user_id: int) -> dict | None:
        with self._lock:
            entry = self._by_user_id.get(user_id)
            if entry is not None and self._is_expired(entry):
                del self._by_user_id[user_id]
                jwt_cache_lookup_total.labels(outcome="expired").inc()
                return None
            jwt_cache_lookup_total.labels(outcome="hit" if entry is not None else "miss").inc()
            return entry

    @staticmethod
    def _is_expired(entry: dict) -> bool:
        expires_at = entry.get("expiresAt")
        return expires_at is not None and datetime.now() > expires_at


class AuthenticatedUserDependency:
    """FastAPI dependency: resolves the caller's identity exclusively from
    a cached, previously-broadcast JWT. Raises 401 for a missing/malformed
    Authorization header or a token this service never cached -- never
    reads any other header.
    """

    def __init__(self, jwt_cache: JwtCache):
        self._jwt_cache = jwt_cache

    def __call__(self, authorization: str | None = Header(default=None)) -> dict:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="missing or malformed Authorization header")

        token = authorization[len("Bearer "):]
        user = self._jwt_cache.get(token)
        if user is None:
            raise HTTPException(status_code=401, detail="invalid or expired token")

        return user
