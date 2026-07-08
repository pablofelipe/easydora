import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from app.auth_client import AuthServiceClient
from app.config import load_settings
from app.rabbitmq import run_consumer
from app.repository import NotificationRepository
from app.schema import ensure_schema
from app.sender import FakeNotificationSender

logging.basicConfig(level=logging.INFO)

settings = load_settings()
repository = NotificationRepository(settings.db_dsn)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    ensure_schema(settings.db_dsn)

    auth_client = AuthServiceClient(settings.auth_service_url)
    sender = FakeNotificationSender(repository)

    thread = threading.Thread(
        target=run_consumer,
        args=(settings.rabbitmq_url, auth_client, repository, sender),
        daemon=True,
    )
    thread.start()
    yield


app = FastAPI(title="notification-service", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "OK", "service": "notification-service"}


@app.get("/notifications/{order_id}")
def get_notifications(order_id: str):
    """Read-only lookup of every notification persisted for one order, in
    the order they were produced. Public-API replacement for querying
    notification_schema.notifications directly during flow validation --
    no edit/delete endpoints exist, this service never mutates a
    notification once persisted.
    """
    notifications = repository.find_by_aggregate_id(order_id)
    if not notifications:
        raise HTTPException(status_code=404, detail=f"no notifications found for order {order_id}")
    return notifications
