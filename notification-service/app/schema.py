from pathlib import Path

import psycopg2

INIT_SCRIPT_PATH = Path(__file__).resolve().parent.parent / "scripts" / "init.sql"


def ensure_schema(dsn: str, script_path: Path = INIT_SCRIPT_PATH) -> None:
    """Runs the idempotent init script on every boot, mirroring
    inventory-service's own runInitScript pattern in Go -- no Alembic, no
    versioned migration tool, appropriate for a schema this small.
    """
    sql = script_path.read_text()
    with psycopg2.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(sql)
