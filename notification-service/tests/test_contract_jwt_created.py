"""Contract test for an intentionally partial consumer:
app.rabbitmq._cache_jwt_created reads token/userId/email/firstName/
lastName/role from the jwt.created payload -- still missing createdAt/
expiresIn, which the full schema requires. firstName/lastName were added
so notification-service's own JwtCache can serve order-notification
enrichment from the same broadcast it already consumes for
authentication, instead of always making a synchronous HTTP call to
auth-service for data that already arrived on this event (see README
Roadmap, opened 2026-07-15). Same asymmetric strategy as the Java/Go
equivalents (start from a schema-conformant example payload, run it
through the real production function, assert the fields it does read came
through correctly) instead of demanding this consumer declare fields it
deliberately ignores.
"""
import jsonschema

from schema_contract_support import load_schema
from app.rabbitmq import _cache_jwt_created


JWT_CREATED_EVENT = {
    "token": "jwt-token-value",
    "userId": 42,
    "email": "buyer@example.com",
    "firstName": "Ana",
    "lastName": "Silva",
    "role": "BUYER",
    "createdAt": "2026-07-13T10:00:00",
    "expiresIn": 3600,
}


class RecordingJwtCache:
    def __init__(self):
        self.added = []

    def add(self, token, user_id, email, role, first_name, last_name):
        self.added.append((token, user_id, email, role, first_name, last_name))


def test_example_payload_conforms_to_shared_schema():
    schema = load_schema("jwt-created.schema.json")
    jsonschema.validate(instance=JWT_CREATED_EVENT, schema=schema)


def test_owned_fields_are_correctly_extracted_from_a_schema_conformant_payload():
    schema = load_schema("jwt-created.schema.json")
    jsonschema.validate(instance=JWT_CREATED_EVENT, schema=schema)

    cache = RecordingJwtCache()
    _cache_jwt_created(JWT_CREATED_EVENT, cache)

    assert cache.added == [("jwt-token-value", 42, "buyer@example.com", "BUYER", "Ana", "Silva")]
