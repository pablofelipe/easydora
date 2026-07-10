"""Shared CorrelationId/RequestId/MessageId tracing infrastructure for this
service. Single point of contact for id generation and context propagation
-- nothing else in the codebase should call uuid/contextvars directly for
these ids.

Context propagation uses contextvars rather than explicit parameter
threading for *logging* purposes only (the equivalent of the Java services'
SLF4J MDC): a value set once at the top of an HTTP request or a RabbitMQ
message callback is automatically visible to every log statement executed
synchronously afterward, without changing every intermediate function
signature. Where a value must actually be *used* (e.g. forwarded as an
outbound HTTP header), it is still passed explicitly as a normal function
argument -- contextvars are for logging enrichment, not a hidden channel
for business logic.
"""

import contextvars
import uuid

CORRELATION_ID_HEADER = "X-Correlation-Id"
REQUEST_ID_HEADER = "X-Request-Id"

_correlation_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("correlation_id", default="")
_request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="")
_message_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("message_id", default="")


def new_id() -> str:
    return str(uuid.uuid4())


def current_correlation_id() -> str:
    return _correlation_id_var.get()


def current_request_id() -> str:
    return _request_id_var.get()


def current_message_id() -> str:
    return _message_id_var.get()


def current_or_new_correlation_id() -> str:
    """The CorrelationId already in scope, or a freshly generated one if
    none is set -- callers should never publish an event or log a business
    operation with no CorrelationId at all."""
    existing = _correlation_id_var.get()
    return existing if existing else new_id()


class correlation_scope:
    """Context manager that sets correlationId/requestId/messageId for the
    duration of a request or a message being handled, restoring the
    previous values on exit -- the equivalent of SLF4J MDC.put/remove in
    the Java services."""

    def __init__(self, correlation_id: str = "", request_id: str = "", message_id: str = ""):
        self._correlation_id = correlation_id
        self._request_id = request_id
        self._message_id = message_id
        self._tokens: list = []

    def __enter__(self) -> "correlation_scope":
        self._tokens = [
            _correlation_id_var.set(self._correlation_id),
            _request_id_var.set(self._request_id),
            _message_id_var.set(self._message_id),
        ]
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        for var, token in zip((_correlation_id_var, _request_id_var, _message_id_var), self._tokens):
            var.reset(token)
