import logging

from app.correlation import correlation_scope
from app.logging_config import CorrelationFilter


def test_correlation_filter_injects_current_context_into_the_log_record(caplog):
    logger = logging.getLogger("test.correlation-filter")
    logger.addFilter(CorrelationFilter())
    logger.setLevel(logging.INFO)

    with correlation_scope(correlation_id="corr-1", request_id="req-1", message_id="msg-1"):
        with caplog.at_level(logging.INFO, logger="test.correlation-filter"):
            logger.info("hello")

    record = caplog.records[0]
    assert record.correlationId == "corr-1"
    assert record.requestId == "req-1"
    assert record.messageId == "msg-1"
    assert record.service == "notification-service"


def test_correlation_filter_defaults_to_empty_strings_outside_any_scope():
    logger = logging.getLogger("test.correlation-filter-default")
    filter_ = CorrelationFilter()
    record = logging.LogRecord("test", logging.INFO, __file__, 1, "hello", None, None)

    filter_.filter(record)

    assert record.correlationId == ""
    assert record.requestId == ""
    assert record.messageId == ""
