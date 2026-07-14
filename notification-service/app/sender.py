from prometheus_client import Counter

from app.models import Notification
from app.repository import NotificationRepository

# Business metric (ADR-0036): infra-level metrics already answer "is the
# system healthy"; this one answers a question infra can't -- how much
# notification volume is actually flowing through the system.
notifications_sent_total = Counter(
    "notifications_sent_total",
    "Total notifications persisted (this service's stand-in for an actual send, see FakeNotificationSender).",
)


class FakeNotificationSender:
    """The current NotificationSender: no real email/SMS/push provider,
    just a persisted, observable record of what would have been sent.
    This persistence *is* the chosen observable effect for this stage --
    inspect it by querying notification_schema.notifications directly,
    the same way this project's other real-infrastructure tests already
    assert outcomes by querying Postgres.
    """

    def __init__(self, repository: NotificationRepository):
        self._repository = repository

    def send(self, notification: Notification) -> None:
        self._repository.save(notification)
        notifications_sent_total.inc()
