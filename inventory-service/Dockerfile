FROM golang:1.25-alpine AS builder

WORKDIR /build

# Install dependencies
RUN apk add --no-cache git

# correlation-commons-go (docs/architecture/observability.md): shared
# CorrelationId/RequestId/MessageId tracing infra used by both Go services
# (inventory-service, api-gateway), resolved via a relative `replace`
# directive in inventory-service/go.mod -- not a real module proxy
# dependency, so the build context must include this sibling directory.
COPY correlation-commons-go/ correlation-commons-go/

# Copy go mod files
COPY inventory-service/go.mod inventory-service/go.sum inventory-service/
WORKDIR /build/inventory-service
RUN go mod download

# Copy source code
COPY inventory-service/ .

# Build the application
RUN go build -o main .

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8083/health || exit 1

CMD ["./main"]

# Dev stage (docker compose watch, ADR-0042): reuses the already-downloaded
# module cache and source tree, swapping the prebuilt binary for `go run`
# so a `sync+restart` cycle recompiles only the changed source instead of
# triggering a full `docker build`.
FROM builder AS dev
WORKDIR /build/inventory-service
CMD ["go", "run", "."]

# This service has no separate minimal runtime stage (unlike api-gateway) --
# `builder` doubles as production today. Re-tagging it keeps that behavior
# as the default `docker build` target now that `dev` is no longer the last
# stage in this file.
FROM builder AS final