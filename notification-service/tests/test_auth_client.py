from unittest.mock import MagicMock, patch

from app.auth import JwtCache
from app.auth_client import AuthServiceClient, CachingAuthClient
from app.models import UserNotificationProfile


class _FailingFallback:
    """A fallback whose call is a test failure -- proves the cache-hit path
    never reaches the real HTTP client at all."""

    def get_notification_profile(self, user_id, correlation_id=""):
        raise AssertionError("fallback must not be called on a cache hit")


class _RecordingFallback:
    def __init__(self, profile):
        self._profile = profile
        self.calls = []

    def get_notification_profile(self, user_id, correlation_id=""):
        self.calls.append((user_id, correlation_id))
        return self._profile


def test_caching_auth_client_returns_the_cached_profile_without_calling_the_fallback():
    jwt_cache = JwtCache()
    jwt_cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
    )

    client = CachingAuthClient(jwt_cache, _FailingFallback())

    profile = client.get_notification_profile(42)

    assert profile == UserNotificationProfile(
        user_id=42, email="buyer@example.com", first_name="Casey", last_name="Buyer",
    )


def test_caching_auth_client_falls_back_to_the_real_client_on_a_cache_miss():
    """Simulates the narrow case this fallback exists for: a
    notification-service restart between a user's login and their order,
    so jwt.created was never (re)consumed for this user yet."""
    empty_cache = JwtCache()
    expected_profile = UserNotificationProfile(
        user_id=99, email="other@example.com", first_name="O", last_name="Ther",
    )
    fallback = _RecordingFallback(expected_profile)

    client = CachingAuthClient(empty_cache, fallback)

    profile = client.get_notification_profile(99, correlation_id="corr-1")

    assert profile is expected_profile
    assert fallback.calls == [(99, "corr-1")]


def test_caching_auth_client_falls_back_when_the_cache_has_a_different_user():
    jwt_cache = JwtCache()
    jwt_cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
    )
    fallback = _RecordingFallback(
        UserNotificationProfile(user_id=99, email="other@example.com", first_name="O", last_name="Ther"),
    )

    client = CachingAuthClient(jwt_cache, fallback)
    profile = client.get_notification_profile(99)

    assert profile.user_id == 99
    assert fallback.calls == [(99, "")]


def test_caching_auth_client_cache_hit_works_even_when_user_id_arrives_as_a_string():
    """order.created's userId decodes from JSON as an int today, but the
    cache lookup normalizes defensively rather than assuming that forever."""
    jwt_cache = JwtCache()
    jwt_cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
    )
    client = CachingAuthClient(jwt_cache, _FailingFallback())

    profile = client.get_notification_profile("42")

    assert profile.email == "buyer@example.com"


def test_get_notification_profile_calls_auth_prefixed_path():
    """The Gateway forwards the incoming path unchanged (ADR-0025), so
    auth-service is self-namespaced under /auth -- this is the only
    synchronous inter-service HTTP call in the system, and it must target
    the same path a call proxied through the Gateway would use."""
    fake_response = MagicMock()
    fake_response.status_code = 200
    fake_response.json.return_value = {
        "id": 42,
        "email": "buyer@example.com",
        "firstName": "Casey",
        "lastName": "Buyer",
    }

    with patch("app.auth_client.httpx.get", return_value=fake_response) as mock_get:
        client = AuthServiceClient("http://auth-service:8081")
        client.get_notification_profile(42)

        called_url = mock_get.call_args.args[0]
        assert called_url == "http://auth-service:8081/auth/users/42/notification-profile"
