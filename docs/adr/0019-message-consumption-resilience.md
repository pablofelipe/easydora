# ADR-0019: Uniform message consumption resilience (limited retry, exponential backoff, dead-lettering)

## Status

Accepted - 2026-07-08

## Context

The README Roadmap documented a known gap since ADR-0010's era: none of
`products-service`, `orders-service`, or `billing-service` configured any
`AcknowledgeMode`, `MessageRecoverer`, or requeue policy on their
`SimpleRabbitListenerContainerFactory` beans, so every listener ran on
Spring AMQP's defaults (`defaultRequeueRejected=true`). A message that a
listener method fails to process is nacked and requeued indefinitely -
there was no bound on retries, no backoff, and no dead-letter queue for a
message that can never succeed.

Inspecting each service's actual `RabbitMQConfig` and `@RabbitListener`
consumers before making any change surfaced two things worth recording
here:

- **`auth-service` has no consumer at all.** It only publishes
  `JwtCreatedEvent`/user-lifecycle events (see the "Cross-service auth"
  section of `docs/architecture/overview.md`); it never binds an
  `@RabbitListener`. There is no queue there for a consumption resilience
  policy to apply to, so this ADR's scope is `products-service`,
  `orders-service`, and `billing-service` only.
- **Exception handling inside `@RabbitListener` methods was itself
  inconsistent**, and that inconsistency mattered more than it looked:
  `UserEventConsumer` (products-service), `UserEventsConsumer` and
  `JwtConsumer` (orders-service), and `OrderEventListener`
  (billing-service) all caught business exceptions internally and only
  logged them - the exception never reached the listener container, so
  RabbitMQ acked the message regardless of whether processing actually
  succeeded. Only `InventoryEventsConsumer` (orders-service) and
  `JwtConsumer` (billing-service) let exceptions propagate. A
  container-level retry/backoff/DLQ policy only ever triggers on an
  exception the container actually sees; applied on top of the swallowing
  consumers as they stood, it would have had no effect on business-logic
  failures at all, only on message deserialization failures (which do
  happen outside those `try`/`catch` blocks). Those eight listener
  methods (across the four classes above) were changed to log and then
  re-throw, so a real processing failure is now visible to the container
  the same way a malformed message already was.

`notification-service` (Python) has the equivalent gap by a different
mechanism - it always acks, even on failure, so a poison message is
logged once and dropped rather than retried forever (silent loss instead
of infinite loop). That consumer is explicitly out of scope for this ADR;
it is tracked separately in the README Roadmap and was addressed by a
later change (see [ADR-0022](0022-notification-service-consumption-resilience.md)).

## Decision

Configure the same policy identically in all three services'
`RabbitMQConfig`, using Spring Boot's native listener retry support - no
custom retry code, no manual sleeps, no polling:

- Each service's `rabbitListenerContainerFactory` bean now takes a
  `SimpleRabbitListenerContainerFactoryConfigurer` (autoconfigured by
  Spring Boot) and calls `configurer.configure(factory, connectionFactory)`
  before setting the message converter. That configurer reads
  `spring.rabbitmq.listener.simple.retry.*` from `application.properties`
  and wires a `RetryTemplate`-backed advice around every listener
  invocation - retry is enabled, limited to 3 total attempts, with
  exponential backoff (200ms initial interval, multiplier 2.0, 2000ms
  cap).
- Each service also declares one dead letter exchange/queue pair
  (`<service>.dlx` / `<service>.dlq`, topic exchange, DLQ bound with
  routing key `#`) and a `MessageRecoverer` bean
  (`RepublishMessageRecoverer`) pointed at that exchange. The same
  autoconfigured configurer picks up this single `MessageRecoverer` bean
  automatically and uses it once the retry budget is exhausted: the
  recoverer republishes the failed message (with the exception recorded
  in its headers) to the dead letter exchange, and the interceptor then
  reports success to the container, so the original message is acked
  normally - deterministic, no reject, no further requeue.
- One DLX/DLQ pair per service, not one per queue, keeps the shared
  configuration a straightforward duplication of the same handful of
  beans across the three `RabbitMQConfig` files rather than growing with
  every queue added later; `RepublishMessageRecoverer` preserves the
  original routing key in the republished message, so the origin queue
  is still identifiable from the DLQ.
- Configuration, not a shared library: this repeats the same
  properties/bean shapes in three files rather than factoring them into
  a shared module, following the same precedent already established for
  event DTOs (ADR-0002) - each service stays independently buildable and
  deployable, and the duplication is small and mechanical enough that a
  shared abstraction would add more indirection than it would save.

## Verification

- New `MessageConsumptionResilienceWiringIT` in `orders-service` and
  `billing-service`, each backed by a dedicated `ResilienceProbeSupport`
  test-only queue/listener bound to the same production
  `rabbitListenerContainerFactory` bean (proving the shared policy itself,
  not any one business consumer's logic), run against real Postgres/
  RabbitMQ via `mvn verify`: a message that succeeds on the first attempt
  is never retried; a message that fails twice then succeeds is retried
  automatically and completes on the 3rd attempt; a message that always
  fails is attempted exactly 3 times (never more) and is then found on
  the service's DLQ.
- `products-service` has no equivalent real-broker test: it deliberately
  has no `maven-failsafe-plugin` and is not part of the CI Phase 2
  `integration` matrix (see ADR-0008's Update sections).
  Its `RabbitMQConfig` uses the identical bean shapes and properties
  already proven at runtime in the other two services, so this is a
  documented coverage gap, not an unverified mechanism.
- Full `mvn verify` for `orders-service` and `billing-service` against
  the project's real `docker-compose` Postgres/RabbitMQ instance passed
  (one unrelated pre-existing failure, `JwtCreatedFanoutIT`, was traced to
  the already-running `orders-service` container competing for the same
  production queue during a local test run, not to this change - it
  passed cleanly once that container was stopped).
- All three services' containers were rebuilt and restarted
  (`docker compose up -d --build`); all 9 containers reported healthy,
  and `products.dlq`, `orders.dlq`, and `billing.dlq` were confirmed
  declared via the RabbitMQ management API.

## Consequences

**Positive**: no consumer in `products-service`, `orders-service`, or
`billing-service` can loop on a message forever anymore, and no business
processing failure is silently acked away either - both classes of
failure now behave the same way: limited retry, exponential backoff, then
a message parked on a real queue for inspection. The policy is uniform
and lives entirely in configuration, so adding a new listener to any of
these services inherits it automatically without any per-listener code.

**Negative / known limitations**:
- `notification-service`'s equivalent gap (silent ack-and-drop on
  failure) is unchanged - tracked separately in the README Roadmap.
- `products-service`'s configuration is unverified against a real broker
  in this repository's own test suite (see Verification above).
- The DLQ is a terminal parking spot, not a replay mechanism - nothing in
  this repository currently re-drives a message off a dead letter queue.
  That remains manual/out of scope, consistent with this being a
  portfolio-scale demonstration rather than an operated production system.

## Update — 2026-07-08

`notification-service`'s equivalent gap, named above as unchanged, is now
closed by [ADR-0022](0022-notification-service-consumption-resilience.md) -
built natively on RabbitMQ (a retry queue with a per-message TTL and
dead-lettering back to the original exchange, then a terminal DLX/DLQ)
since Pika has no built-in retry template equivalent to the
`SimpleRabbitListenerContainerFactoryConfigurer`/`RetryTemplate` this ADR
uses. Same numbers (3 attempts, 200ms/2.0/2000ms), different mechanism,
equivalent behavior - this update doesn't change anything this ADR
decided for the Spring services.

## References

- [docs/architecture/architectural-principles.md](../architecture/architectural-principles.md)
  - this policy is infrastructure-only by design (principle: behavior
    above technology / avoid custom code the framework already provides).
- [ADR-0002](0002-json-schema-contract-testing.md) - the existing
  per-service-duplication-over-shared-library precedent this ADR follows
  for the same reason.
- [ADR-0008](0008-surefire-failsafe-test-split.md) - the Surefire/Failsafe
  split; its Update sections explain why `products-service` is the one
  service with neither a real `*IT` class nor `maven-failsafe-plugin`,
  hence no real-broker test for this policy there.
- [ADR-0010](0010-uniform-service-healthchecks.md) - where this gap was
  first documented in the README Roadmap.
