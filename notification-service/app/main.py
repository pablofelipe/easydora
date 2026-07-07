import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.auth_client import AuthServiceClient
from app.config import load_settings
from app.rabbitmq import run_consumer
from app.repository import NotificationRepository
from app.schema import ensure_schema
from app.sender import FakeNotificationSender

logging.basicConfig(level=logging.INFO)

settings = load_settings()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    ensure_schema(settings.db_dsn)

    auth_client = AuthServiceClient(settings.auth_service_url)
    repository = NotificationRepository(settings.db_dsn)
    sender = FakeNotificationSender(repository)

    thread = threading.Thread(
        target=run_consumer,
        args=(settings.rabbitmq_url, auth_client, sender),
        daemon=True,
    )
    thread.start()
    yield


app = FastAPI(title="notification-service", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "OK", "service": "notification-service"}
