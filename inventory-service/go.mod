module inventory-service

go 1.23.0

require (
	easydora/correlation-commons v0.0.0
	github.com/joho/godotenv v1.5.1
	github.com/lib/pq v1.10.9
	github.com/rabbitmq/amqp091-go v1.10.0
	github.com/santhosh-tekuri/jsonschema/v5 v5.3.1
	github.com/stretchr/testify v1.11.1
)

// Shared CorrelationId/RequestId/MessageId tracing infra, used by both Go
// services (inventory-service, api-gateway). Not published to a real module
// proxy -- resolved locally via this replace directive, the standard Go
// pattern for an internal shared package split across separate modules in
// the same monorepo (see docs/architecture/observability.md).
replace easydora/correlation-commons => ../correlation-commons-go

require (
	github.com/davecgh/go-spew v1.1.1 // indirect
	github.com/kr/pretty v0.3.0 // indirect
	github.com/pmezard/go-difflib v1.0.0 // indirect
	github.com/rogpeppe/go-internal v1.8.0 // indirect
	gopkg.in/check.v1 v1.0.0-20190902080502-41f04d3bba15 // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
)
