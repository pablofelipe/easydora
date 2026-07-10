"""JWT-principal authentication, mirroring every other service's broadcast
JWT cache (see docs/architecture/overview.md) rather than local signature
verification: this service already has a RabbitMQ consumer thread, so it
consumes jwt.created the same way products-service/orders-service/
billing-service each do with their own JwtConsumer + in-memory token map.
No X-User-Id or any other client-supplied header is ever consulted.
"""

import threading

from fastapi import Header, HTTPException


class JwtCache:
    """Thread-safe token -> authenticated-user-info map, populated only by
    the RabbitMQ jwt.created consumer. A service restart wipes it, exactly
    like the Spring services' own in-memory caches -- an accepted, already
    documented limitation of the broadcast-JWT-cache pattern project-wide.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._tokens: dict[str, dict] = {}

    def add(self, token: str, user_id: int, email: str, role: str) -> None:
        with self._lock:
            self._tokens[token] = {"userId": user_id, "email": email, "role": role}

    def get(self, token: str) -> dict | None:
        with self._lock:
            return self._tokens.get(token)


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
