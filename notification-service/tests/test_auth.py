"""Unit tests for JwtCache's userId-indexed view, added so a consumer that
only knows a userId (e.g. process_order_created, which never sees the JWT
token itself) can look up the same broadcast profile data the token-keyed
index already stores -- see CachingAuthClient in app/auth_client.py.

Also covers the expires_at TTL added by ADR-0039: each entry gets a
lifetime equal to the JWT's own expiresIn, instead of none at all (a
service restart was, until this ADR, the only way an entry ever went
away).
"""

from datetime import datetime, timedelta

from app.auth import JwtCache, jwt_cache_lookup_total


def _counter_value(outcome: str) -> float:
    return jwt_cache_lookup_total.labels(outcome=outcome)._value.get()


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
        "expiresAt": None,
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


def test_get_returns_none_and_evicts_an_expired_entry():
    cache = JwtCache()
    cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
        expires_at=datetime.now() - timedelta(seconds=1),
    )

    assert cache.get("tok-1") is None


def test_get_by_user_id_returns_none_and_evicts_an_expired_entry():
    cache = JwtCache()
    cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
        expires_at=datetime.now() - timedelta(seconds=1),
    )

    assert cache.get_by_user_id(42) is None


def test_get_still_returns_a_not_yet_expired_entry():
    cache = JwtCache()
    cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        first_name="Casey", last_name="Buyer",
        expires_at=datetime.now() + timedelta(minutes=5),
    )

    assert cache.get("tok-1") is not None


def test_entry_without_expires_at_never_expires():
    """Backward compatibility: call sites that never learned expiresIn (or
    predate ADR-0039) get an entry that behaves like before -- present
    until a restart, never expired by TTL."""
    cache = JwtCache()
    cache.add("tok-1", user_id=42, email="buyer@example.com", role="BUYER")

    assert cache.get("tok-1") is not None


def test_get_increments_the_hit_metric():
    before = _counter_value("hit")
    cache = JwtCache()
    cache.add("tok-1", user_id=42, email="buyer@example.com", role="BUYER")

    cache.get("tok-1")

    assert _counter_value("hit") == before + 1


def test_get_increments_the_miss_metric_for_the_restart_scenario():
    before = _counter_value("miss")
    cache = JwtCache()  # empty, exactly like right after a restart

    cache.get("a-token-issued-before-restart")

    assert _counter_value("miss") == before + 1


def test_get_increments_the_expired_metric():
    before = _counter_value("expired")
    cache = JwtCache()
    cache.add(
        "tok-1", user_id=42, email="buyer@example.com", role="BUYER",
        expires_at=datetime.now() - timedelta(seconds=1),
    )

    cache.get("tok-1")

    assert _counter_value("expired") == before + 1


def test_get_by_user_id_increments_the_same_metric():
    before_hit = _counter_value("hit")
    before_miss = _counter_value("miss")
    cache = JwtCache()
    cache.add("tok-1", user_id=42, email="buyer@example.com", role="BUYER")

    cache.get_by_user_id(42)
    cache.get_by_user_id(99)

    assert _counter_value("hit") == before_hit + 1
    assert _counter_value("miss") == before_miss + 1
