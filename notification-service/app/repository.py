import json

import psycopg2

from app.models import Notification


class NotificationRepository:
    """Plain SQL persistence for processed notifications -- no ORM, mirroring
    inventory-service's own raw-SQL approach in Go for a domain this small.
    """

    def __init__(self, dsn: str):
        self._dsn = dsn

    def save(self, notification: Notification) -> None:
        with psycopg2.connect(self._dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO notification_schema.notifications
                        (event_type, aggregate_id, status, payload)
                    VALUES (%s, %s, %s, %s)
                    """,
                    (
                        notification.event_type,
                        notification.aggregate_id,
                        notification.status,
                        json.dumps(notification.payload),
                    ),
                )
