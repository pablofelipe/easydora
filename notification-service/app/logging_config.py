"""Structured (logfmt-style key=value) console logging -- same field set
and order as the four Spring services' logging.pattern.console, see
docs/architecture/observability.md. No JSON, no external dependency: a
stdlib logging.Filter injects the current CorrelationId/RequestId/MessageId
(from app.correlation) into every LogRecord, and a plain Formatter renders
them alongside the standard fields.
"""

import logging

from app.correlation import current_correlation_id, current_message_id, current_request_id

SERVICE_NAME = "notification-service"

LOG_FORMAT = (
    "%(asctime)s service=" + SERVICE_NAME + " level=%(levelname)s "
    "correlationId=%(correlationId)s requestId=%(requestId)s messageId=%(messageId)s "
    "logger=%(name)s - %(message)s"
)


class CorrelationFilter(logging.Filter):
    """Injects the current CorrelationId/RequestId/MessageId (empty string
    if none is in scope) into every LogRecord it sees."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.correlationId = current_correlation_id()
        record.requestId = current_request_id()
        record.messageId = current_message_id()
        record.service = SERVICE_NAME
        return True


def configure_logging() -> None:
    handler = logging.StreamHandler()
    handler.addFilter(CorrelationFilter())
    handler.setFormatter(logging.Formatter(LOG_FORMAT))

    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)
