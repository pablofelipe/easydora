# RabbitMQ vs. Kafka benchmark (ADR-0041)

Standalone tool, isolated from EasyDora's real service topology (which
stays 100% RabbitMQ, ADR-0007). Exists only to measure — not to justify —
whether Kafka has anything to offer this project. Raw results are in
`results.jsonl` (one JSON object per run, exactly as printed); this file
explains how they were produced and reads them.

## Why standalone

The eight EasyDora services are not touched by this benchmark and gained
no new dependency because of it. `docker-compose.yml` in this directory
spins up its own RabbitMQ and Kafka containers, on ports (`5677`/`15677`
for RabbitMQ, `29093` for Kafka) that don't collide with the project's own
`docker-compose.yml`, so this can run alongside a normal dev stack without
disturbing it.

## Running it

```bash
cd benchmarks/broker-comparison
docker compose up -d
GOWORK=off go build -o benchmark.exe .   # GOWORK=off: this module is not part of the repo's root Go workspace

# Throughput + latency, N messages published one at a time, confirmed
# before the next is sent -- the same call pattern EasyDora's own outbox
# publishers use (publish, wait for the broker's ack, then move on).
./benchmark.exe -broker=rabbitmq -mode=throughput -count=5000 -out=results.jsonl
./benchmark.exe -broker=kafka    -mode=throughput -count=3000 -out=results.jsonl

# Failure behavior: publishes at a fixed rate while you stop/start the
# broker container in a second terminal partway through the run.
./benchmark.exe -broker=rabbitmq -mode=failover -duration=30s -rate=20 -out=results.jsonl
# in another shell, ~10s in: docker compose stop rabbitmq
# ~8s later:                 docker compose start rabbitmq

docker compose down -v
```

## What was actually measured (2026-08-01, single run each, local machine)

### Throughput / latency

| Broker | Messages | Producer throughput | Publish p50 | Publish p99 | End-to-end p50 | End-to-end p99 |
|---|---|---|---|---|---|---|
| RabbitMQ | 5000 | **1199 msg/s** | 0.69 ms | 2.65 ms | 0.71 ms | 2.78 ms |
| Kafka | 3000 | **84 msg/s** | 11.6 ms | 15.9 ms | 11.7 ms | 16.0 ms |

Both numbers are for one publisher, one consumer, synchronous
publish-then-wait-for-ack per message — deliberately the same pattern
`OutboxPublisher` already uses in every service that owns one
(ADR-0037), not Kafka's own batched high-throughput mode (a producer
that lets `kafka-go`'s default ~1s batch window fill before flushing
would show a very different, much higher number, at the cost of the
per-message confirmation this project's outbox design relies on).
Under that specific, honest comparison, RabbitMQ is roughly **14x**
faster end to end on this machine — the gap is `kafka-go`'s per-publish
round trip against a single-node broker, not a fundamental Kafka
limitation, but it is the number that would actually apply if EasyDora's
outbox pattern were retargeted at Kafka unchanged.

### Broker-down behavior

Both runs: publish continuously at a fixed rate; the broker container is
stopped, then restarted, partway through a 30-second window.

| Broker | Attempted | Confirmed | Failed | Ever reached consumer | Never recovered before test ended |
|---|---|---|---|---|---|
| RabbitMQ | 207 | 206 | 1 | 205 | No — resumed publishing on its own |
| Kafka | 230 | 82 | 148 | 82 | Yes — zero successful publishes after the failure started |

Two findings worth calling out, because they cut in different directions:

- **RabbitMQ recovered automatically, but two "confirmed" messages were
  never actually delivered.** Sequence 205 and 206 both received a
  positive broker acknowledgment (`confirmation.Wait() == true`) yet
  never reached the consumer — a genuine, if rare (2 of 207, ~1%), gap in
  the at-least-once guarantee under a *hard* container kill (`docker
  compose stop`, not a graceful shutdown). The reconnect logic itself
  (dial/channel/confirm-mode re-establishment on the next publish
  attempt, mirroring `RabbitMQPublisherAdapter`'s real
  `ensureChannel`) is hand-written in this benchmark tool, not automatic
  — RabbitMQ's client library doesn't reconnect for you either.
- **Kafka (`kafka-go`'s default `Writer`) never resumed publishing
  within the remaining ~20 seconds of the test after the broker came
  back**, even though the broker itself reported healthy again well
  before the test ended. This is very likely `kafka-go`'s own metadata
  cache/backoff defaults, not a Kafka protocol limitation — a production
  Kafka client would need the equivalent of the reconnect loop this
  benchmark had to hand-write for RabbitMQ, and that work does not exist
  today in this project for either language.

## What this does and doesn't settle

This is a single machine, single run per scenario, single-node Kafka
(KRaft, no replication) against a single-node RabbitMQ — enough to
replace "RabbitMQ is probably fine" with a real number, not enough to
claim either broker's ceiling. See [ADR-0041](../../docs/adr/0041-kafka-rabbitmq-broker-benchmark.md)
for how this factors into the decision to keep RabbitMQ as EasyDora's
only live broker.
