"""Consumes order.created and order.status-changed, producing exactly one
Notification per event.

Notification templates are intentionally implemented as code rather than
externalized configuration (no template engine, no template files, no
database-backed template store, no dynamic configuration). The project
prioritizes simplicity and readability over runtime configurability -- with
two events and one delivery channel (a fake sender), a templating
mechanism would add indirection this stage has no use for.
"""

from typing import Any, Protocol

from app.auth_client import ProfileNotFoundError, ProfileLookupError
from app.models import Notification

ORDER_CREATED_EVENT_TYPE = "order.created"
ORDER_STATUS_CHANGED_EVENT_TYPE = "order.status-changed"


class AuthClient(Protocol):
    def get_notification_profile(self, user_id: int) -> Any: ...


class NotificationSender(Protocol):
    def send(self, notification: Notification) -> None: ...


class NotificationLookup(Protocol):
    def find_by_aggregate_id(self, aggregate_id: str) -> list[dict]: ...


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


def process_order_status_changed(event: dict, lookup: NotificationLookup, sender: NotificationSender) -> Notification:
    """Consumes one order.status-changed event. Unlike order.created, this
    event carries no userId to enrich via auth-service, so it reuses the
    email/name already captured by this order's own prior order.created
    notification (same schema, no cross-service call, no cross-schema
    access) instead of introducing a second lookup mechanism. If no such
    prior notification exists (e.g. this service was down when
    order.created was published), the outcome is recorded as FAILED,
    consistent with how a failed profile lookup is already handled for
    order.created -- never raises, always produces exactly one row.
    """
    order_id = event["orderId"]
    previous_state = event.get("previousState")
    new_state = event.get("newState")

    prior = next(
        (
            n
            for n in reversed(lookup.find_by_aggregate_id(order_id))
            if n["eventType"] == ORDER_CREATED_EVENT_TYPE and n["status"] == "SENT"
        ),
        None,
    )

    if prior is None:
        notification = Notification(
            event_type=ORDER_STATUS_CHANGED_EVENT_TYPE,
            aggregate_id=order_id,
            status="FAILED",
            payload={
                "previousState": previous_state,
                "newState": new_state,
                "error": f"no prior order.created notification found for order {order_id}",
            },
        )
    else:
        prior_payload = prior["payload"]
        notification = Notification(
            event_type=ORDER_STATUS_CHANGED_EVENT_TYPE,
            aggregate_id=order_id,
            status="SENT",
            payload={
                "userId": prior_payload.get("userId"),
                "email": prior_payload.get("email"),
                "firstName": prior_payload.get("firstName"),
                "lastName": prior_payload.get("lastName"),
                "previousState": previous_state,
                "newState": new_state,
            },
        )

    sender.send(notification)
    return notification
