from typing import Any, Protocol

from app.auth_client import ProfileNotFoundError, ProfileLookupError
from app.models import Notification

ORDER_CREATED_EVENT_TYPE = "order.created"


class AuthClient(Protocol):
    def get_notification_profile(self, user_id: int) -> Any: ...


class NotificationSender(Protocol):
    def send(self, notification: Notification) -> None: ...


def process_order_created(event: dict, auth_client: AuthClient, sender: NotificationSender) -> Notification:
    """Consumes one order.created event: enriches it via a real call to
    auth-service and produces exactly one observable Notification, sent or
    failed. Never raises -- a failed profile lookup is a recorded outcome,
    not an exception the RabbitMQ listener has to handle, consistent with
    how every other consumer in this project treats a failure it can't
    retry (log/record, don't crash the listener, don't reject the message).
    """
    order_id = event["orderId"]
    user_id = event["userId"]

    try:
        profile = auth_client.get_notification_profile(user_id)
        notification = Notification(
            event_type=ORDER_CREATED_EVENT_TYPE,
            aggregate_id=order_id,
            status="SENT",
            payload={
                "userId": user_id,
                "email": profile.email,
                "firstName": profile.first_name,
                "lastName": profile.last_name,
                "totalAmount": event.get("totalAmount"),
            },
        )
    except (ProfileNotFoundError, ProfileLookupError) as exc:
        notification = Notification(
            event_type=ORDER_CREATED_EVENT_TYPE,
            aggregate_id=order_id,
            status="FAILED",
            payload={
                "userId": user_id,
                "error": str(exc),
            },
        )

    sender.send(notification)
    return notification
