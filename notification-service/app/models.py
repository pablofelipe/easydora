from dataclasses import dataclass, field
from typing import Any


@dataclass
class UserNotificationProfile:
    user_id: int
    email: str
    first_name: str
    last_name: str


@dataclass
class Notification:
    event_type: str
    aggregate_id: str
    status: str
    payload: dict[str, Any] = field(default_factory=dict)
