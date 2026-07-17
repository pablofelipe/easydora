"""Proves GET /health/liveness is actually wired to ProgressWatchdog, the
same way the other five services' liveness indicators were proven with a
dedicated test (see e.g. orders-service's RabbitMqProgressHealthIndicator).
No real Postgres/RabbitMQ needed: progress_watchdog.is_stuck is monkeypatched
directly, mirroring how this service's other unit tests fake their
collaborators instead of hitting real infrastructure.
"""

from unittest.mock import patch

from fastapi.testclient import TestClient

from app import main


def test_liveness_reports_up_when_watchdog_is_not_stuck():
    client = TestClient(main.app)

    with patch.object(main.progress_watchdog, "is_stuck", return_value=False):
        response = client.get("/health/liveness")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_liveness_reports_503_when_watchdog_is_stuck():
    client = TestClient(main.app)

    with patch.object(main.progress_watchdog, "is_stuck", return_value=True):
        response = client.get("/health/liveness")

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
