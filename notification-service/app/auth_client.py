import httpx

from app.auth import JwtCache
from app.correlation import CORRELATION_ID_HEADER
from app.models import UserNotificationProfile


class ProfileNotFoundError(Exception):
    """Raised when auth-service has no user for the given id (HTTP 404)."""


class ProfileLookupError(Exception):
    """Raised for any other failure calling auth-service (timeout, connection error, 5xx)."""


class AuthServiceClient:
    """Real HTTP client for auth-service's GET /auth/users/{id}/notification-profile.

    This is the only synchronous call notification-service makes to another
    service, and the only thing it knows about auth-service beyond that one
    HTTP contract.
    """

    def __init__(self, base_url: str, timeout_seconds: float = 5.0):
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def get_notification_profile(self, user_id: int, correlation_id: str = "") -> UserNotificationProfile:
        url = f"{self._base_url}/auth/users/{user_id}/notification-profile"
        headers = {CORRELATION_ID_HEADER: correlation_id} if correlation_id else {}
        try:
            response = httpx.get(url, headers=headers, timeout=self._timeout_seconds)
        except httpx.HTTPError as exc:
            raise ProfileLookupError(f"error calling auth-service for user {user_id}: {exc}") from exc

        if response.status_code == 404:
            raise ProfileNotFoundError(f"auth-service has no user {user_id}")
        if response.status_code != 200:
            raise ProfileLookupError(
                f"auth-service returned unexpected status {response.status_code} for user {user_id}"
            )

        body = response.json()
        return UserNotificationProfile(
            user_id=body["id"],
            email=body["email"],
            first_name=body["firstName"],
            last_name=body["lastName"],
        )


class CachingAuthClient:
    """Wraps a real auth client (AuthServiceClient in production) with a
    JwtCache-backed fast path.

    auth-service already broadcasts firstName/lastName/email on
    jwt.created -- the same event notification-service consumes to
    authenticate GET /notifications/{orderId} -- so a synchronous HTTP
    call for the exact same fields on every order.created is redundant
    whenever that broadcast has already been consumed for this user. Tries
    the cache first; falls back to the real client only on a cache miss,
    the narrow case of a cache-cold restart between a user's login and
    their order (see README Roadmap, opened 2026-07-15). Implements the
    same AuthClient protocol consumer.py already depends on, so
    process_order_created needs no changes at all -- only main.py's
    wiring swaps which client it's given.
    """

    def __init__(self, jwt_cache: JwtCache, fallback) -> None:
        self._jwt_cache = jwt_cache
        self._fallback = fallback

    def get_notification_profile(self, user_id: int, correlation_id: str = "") -> UserNotificationProfile:
        cached = self._jwt_cache.get_by_user_id(int(user_id))
        if cached is not None:
            return UserNotificationProfile(
                user_id=cached["userId"],
                email=cached["email"],
                first_name=cached["firstName"],
                last_name=cached["lastName"],
            )
        return self._fallback.get_notification_profile(user_id, correlation_id)
