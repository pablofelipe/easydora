import logging
import time
from pathlib import Path

import psycopg2

logger = logging.getLogger(__name__)

INIT_SCRIPT_PATH = Path(__file__).resolve().parent.parent / "scripts" / "init.sql"

MAX_ATTEMPTS = 10
RETRY_DELAY_SECONDS = 3


def ensure_schema(dsn: str, script_path: Path = INIT_SCRIPT_PATH) -> None:
    """Runs the idempotent init script on every boot, mirroring
    inventory-service's own runInitScript pattern in Go -- no Alembic, no
    versioned migration tool, appropriate for a schema this small.

    Retries the initial connection a bounded number of times: this runs
    once, synchronously, before the app starts serving traffic or
    consuming RabbitMQ messages at all, and a container can start before
    Postgres is fully ready to accept connections despite docker-compose's
    own healthcheck-based ordering. If it still can't connect after
    MAX_ATTEMPTS, the original exception propagates -- failing loudly here
    is correct, since nothing in this service can do anything useful
    without its own schema in place.
    """
    sql = script_path.read_text()
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            with psycopg2.connect(dsn) as conn:
                with conn.cursor() as cur:
                    cur.execute(sql)
            return
        except psycopg2.OperationalError:
            if attempt == MAX_ATTEMPTS:
                raise
            logger.warning(
                "Postgres not ready yet (attempt %d/%d); retrying in %ss",
                attempt, MAX_ATTEMPTS, RETRY_DELAY_SECONDS,
            )
            time.sleep(RETRY_DELAY_SECONDS)
