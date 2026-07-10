# Build stage
FROM golang:1.23-alpine AS builder

WORKDIR /build

# correlation-commons-go (docs/architecture/observability.md): shared
# CorrelationId/RequestId/MessageId tracing infra used by both Go services
# (api-gateway, inventory-service), resolved via a relative `replace`
# directive in api-gateway/go.mod -- not a real module proxy dependency,
# so the build context must include this sibling directory.
COPY correlation-commons-go/ correlation-commons-go/

COPY api-gateway/go.mod api-gateway/go.sum api-gateway/
WORKDIR /build/api-gateway
RUN go mod download

COPY api-gateway/ .
RUN CGO_ENABLED=0 GOOS=linux go build -o main .

# Runtime stage  
FROM alpine:3.18

WORKDIR /app

RUN apk add --no-cache ca-certificates

COPY --from=builder /build/api-gateway/main .

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

CMD ["./main"]