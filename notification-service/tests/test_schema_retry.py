from unittest.mock import MagicMock, patch

import psycopg2
import pytest

from app.schema import ensure_schema


def test_ensure_schema_retries_past_a_startup_connection_failure(tmp_path):
    script = tmp_path / "init.sql"
    script.write_text("SELECT 1;")

    attempts = []

    def fake_connect(dsn):
        attempts.append(dsn)
        if len(attempts) < 3:
            raise psycopg2.OperationalError("simulated: postgres not ready yet")
        return MagicMock()

    with patch("app.schema.psycopg2.connect", side_effect=fake_connect), \
            patch("app.schema.time.sleep"):
        ensure_schema("postgresql://fake", script_path=script)

    assert len(attempts) == 3


def test_ensure_schema_raises_after_exhausting_all_attempts(tmp_path):
    script = tmp_path / "init.sql"
    script.write_text("SELECT 1;")

    def always_fail(dsn):
        raise psycopg2.OperationalError("simulated: postgres never comes up")

    with patch("app.schema.psycopg2.connect", side_effect=always_fail), \
            patch("app.schema.time.sleep"):
        with pytest.raises(psycopg2.OperationalError):
            ensure_schema("postgresql://fake", script_path=script)
