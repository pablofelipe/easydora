FROM golang:1.23-alpine

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