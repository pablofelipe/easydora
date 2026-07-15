"""Unit tests for JwtCache's userId-indexed view, added so a consumer that
only knows a userId (e.g. process_order_created, which never sees the JWT
token itself) can look up the same broadcast profile data the token-keyed
index already stores -- see CachingAuthClient in app/auth_client.py.
"""

from app.auth import JwtCache


def test_get_by_user_id_returns_none_when_never_cached():
    cache = JwtCache()
    assert cache.get_by_user_id(42) is None


def test_get_by_user_id_returns_the_same_broadcast_data_added_by_token():
    cache = JwtCache()
    cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
    )

    assert cache.get_by_user_id(42) == {
        "userId": 42,
        "email": "buyer@example.com",
        "firstName": "Casey",
        "lastName": "Buyer",
        "role": "BUYER",
    }


def test_get_by_user_id_does_not_require_knowing_the_token():
    """Both indexes are populated by the same jwt.created broadcast; a
    userId-based lookup must not depend on the caller also having the
    token that produced it."""
    cache = JwtCache()
    cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
    )

    assert cache.get("tok-1") is not None
    assert cache.get_by_user_id(42) is not None


def test_first_name_and_last_name_default_to_empty_string_when_not_provided():
    """Backward compatibility: call sites that only care about token-based
    authentication (not notification enrichment) never pass
    first_name/last_name."""
    cache = JwtCache()
    cache.add("tok-1", user_id=42, email="buyer@example.com", role="BUYER")

    cached = cache.get("tok-1")
    assert cached["firstName"] == ""
    assert cached["lastName"] == ""
