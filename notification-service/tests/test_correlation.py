from app.correlation import (
    CORRELATION_ID_HEADER,
    REQUEST_ID_HEADER,
    correlation_scope,
    current_correlation_id,
    current_message_id,
    current_or_new_correlation_id,
    current_request_id,
    new_id,
)


def test_new_id_produces_non_empty_unique_values():
    first = new_id()
    second = new_id()

    assert first
    assert second
    assert first != second


def test_correlation_scope_sets_and_restores_context():
    assert current_correlation_id() == ""
    assert current_request_id() == ""
    assert current_message_id() == ""

    with correlation_scope(correlation_id="corr-1", request_id="req-1", message_id="msg-1"):
        assert current_correlation_id() == "corr-1"
        assert current_request_id() == "req-1"
        assert current_message_id() == "msg-1"

    assert current_correlation_id() == ""
    assert current_request_id() == ""
    assert current_message_id() == ""


def test_current_or_new_correlation_id_reuses_whats_in_scope():
    with correlation_scope(correlation_id="existing-corr"):
        assert current_or_new_correlation_id() == "existing-corr"


def test_current_or_new_correlation_id_generates_one_when_absent():
    assert current_or_new_correlation_id() != ""


def test_header_names_are_stable():
    assert CORRELATION_ID_HEADER == "X-Correlation-Id"
    assert REQUEST_ID_HEADER == "X-Request-Id"
