from unittest.mock import MagicMock, patch

from app.auth_client import AuthServiceClient


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
