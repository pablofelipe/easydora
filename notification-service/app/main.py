import threading
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from prometheus_client import make_asgi_app

from app.auth import AuthenticatedUserDependency, JwtCache
from app.auth_client import AuthServiceClient
from app.config import load_settings
from app.correlation import CORRELATION_ID_HEADER, REQUEST_ID_HEADER, correlation_scope, new_id
from app.logging_config import configure_logging
from app.rabbitmq import run_consumer
from app.repository import NotificationRepository
from app.schema import ensure_schema
from app.sender import FakeNotificationSender

configure_logging()

settings = load_settings()
repository = NotificationRepository(settings.db_dsn)
jwt_cache = JwtCache()
get_authenticated_user = AuthenticatedUserDependency(jwt_cache)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    ensure_schema(settings.db_dsn)

    auth_client = AuthServiceClient(settings.auth_service_url)
    sender = FakeNotificationSender(repository)

    thread = threading.Thread(
        target=run_consumer,
        args=(settings.rabbitmq_url, auth_client, repository, sender, jwt_cache),
        daemon=True,
    )
    thread.start()
    yield


app = FastAPI(title="notification-service", lifespan=lifespan)

# The frontend calls this service through the Gateway with an Authorization
# header, which the browser treats as a non-simple request requiring a
# preflight OPTIONS -- mirrors the CORS policy applied to the other five
# services (allow any origin, no credentials, since there is no cookie-based
# session anywhere in this project).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
    allow_credentials=False,
    # Response headers are NOT readable by browser JS unless explicitly
    # exposed -- without this, fetch()'s response.headers.get(...) always
    # returns null for these in a real browser even though curl (not
    # subject to CORS) sees them fine.
    expose_headers=["X-Correlation-Id", "X-Request-Id"],
)


@app.middleware("http")
async def correlation_middleware(request: Request, call_next):
    """Birthplace of a business operation's CorrelationId for this
    service's own HTTP surface: reused from the client if present,
    generated otherwise. RequestId is always freshly generated, once per
    request. Mirrors the Java services' CorrelationIdFilter."""
    incoming = request.headers.get(CORRELATION_ID_HEADER)
    correlation_id = incoming if incoming else new_id()
    request_id = new_id()

    with correlation_scope(correlation_id=correlation_id, request_id=request_id):
        response = await call_next(request)

    response.headers[CORRELATION_ID_HEADER] = correlation_id
    response.headers[REQUEST_ID_HEADER] = request_id
    return response


@app.get("/health")
@app.get("/notification/health")
def health():
    return {"status": "OK", "service": "notification-service"}


# Prometheus scrape endpoint (see ADR-0036). make_asgi_app() serves the
# default registry, which already includes process metrics (memory, CPU)
# with zero custom collectors.
app.mount("/metrics", make_asgi_app())


@app.get("/notifications/{order_id}")
@app.get("/notification/notifications/{order_id}")
def get_notifications(order_id: str, current_user: dict = Depends(get_authenticated_user)):
    """Read-only lookup of every notification persisted for one order, in
    the order they were produced. Public-API replacement for querying
    notification_schema.notifications directly during flow validation --
    no edit/delete endpoints exist, this service never mutates a
    notification once persisted.

    Registered under both the bare path (existing direct callers, e.g.
    docs/walkthrough.md) and the self-namespaced /notification path (the
    one reachable through the Gateway, which forwards paths unchanged --
    see ADR-0025).

    Restricted to the order's own buyer: the order.created notification's
    payload already carries the real buyerId (captured from the event
    itself, not a client-supplied header), so no second lookup is needed
    to enforce ownership.
    """
    notifications = repository.find_by_aggregate_id(order_id)
    if not notifications:
        raise HTTPException(status_code=404, detail=f"no notifications found for order {order_id}")

    order_created = next((n for n in notifications if n["eventType"] == "order.created"), None)
    buyer_id = order_created["payload"].get("userId") if order_created else None
    if buyer_id is None or int(buyer_id) != int(current_user["userId"]):
        raise HTTPException(status_code=403, detail="not authorized to view this order's notifications")

    return notifications
