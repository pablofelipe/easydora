"""Distributed tracing setup (ADR-0024's 2026-08-02 Update): exports spans
over OTLP/HTTP to the Jaeger container declared in docker-compose.yml.
Additive to app/correlation.py's existing CorrelationId propagation, not a
replacement -- see the ADR update for why. Mirrors api-gateway's/
inventory-service's own setup_tracing, duplicated rather than shared,
since this project keeps no cross-language shared library (only
correlation-commons/-go, and only for the byte-for-byte-identical
CorrelationId infra -- see docs/architecture/observability.md)."""

import logging
import os

from opentelemetry import propagate, trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

logger = logging.getLogger(__name__)


def setup_tracing(service_name: str) -> None:
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not endpoint:
        # No collector configured (e.g. running outside Compose) -- leave
        # the global no-op tracer in place rather than fail startup.
        return

    resource = Resource.create({SERVICE_NAME: service_name})
    provider = TracerProvider(resource=resource)
    exporter = OTLPSpanExporter(endpoint=f"{endpoint}/v1/traces")
    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)

    logger.info("otel: tracing enabled, exporting to %s", endpoint)


def extract_trace_context(headers: dict | None):
    """Reads an incoming delivery's headers for a traceparent the publisher
    injected -- the RabbitMQ-hop equivalent of _scope_from_properties'
    CorrelationId reuse. Returns a Context usable with
    trace.use_span/start_as_current_span(context=...)."""
    return propagate.extract(headers or {})


tracer = trace.get_tracer("notification-service.messaging")
