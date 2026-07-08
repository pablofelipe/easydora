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

    def find_by_aggregate_id(self, aggregate_id: str) -> list[dict]:
        """Every notification persisted for one order, oldest first. Used
        both by the public read-only API and internally to look up a prior
        order.created notification's enriched payload when processing a
        later order.status-changed event for the same order.
        """
        with psycopg2.connect(self._dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT event_type, status, payload, created_at
                    FROM notification_schema.notifications
                    WHERE aggregate_id = %s
                    ORDER BY created_at ASC
                    """,
                    (aggregate_id,),
                )
                rows = cur.fetchall()
        return [
            {
                "eventType": row[0],
                "status": row[1],
                "payload": row[2],
                "createdAt": row[3].isoformat(),
            }
            for row in rows
        ]
