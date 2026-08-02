# ADR-0041: Kafka vs. RabbitMQ broker benchmark

## Status

Accepted - 2026-08-01

## Context

ADR-0007 removed Kafka and made RabbitMQ the project's only broker,
reasoned entirely from properties of the workload (no consumer group ever
scaled beyond one instance, nothing replayed from an offset, no domain
logic relied on partition ordering) rather than from a measurement of
either broker. That reasoning has held up — nothing in the three years
since has needed a partitioned log, offset replay, or consumer-group
scaling — but it left "RabbitMQ is the right choice" as an argued
decision, not a measured one.

This ADR does not reopen ADR-0007's decision. EasyDora's eight services
still talk to RabbitMQ exclusively, and nothing here changes that. It
answers a narrower, additive question: now that both brokers can be run
side by side on the same machine, what do they actually look like against
each other, doing the same work?

A second-broker adapter living inside a production service (e.g. a Kafka
mirror bolted onto `inventory-service`'s Outbox publisher) was considered
and rejected for this ADR. It would have made the running system's
topology genuinely two brokers for a decision this ADR's own data doesn't
support — RabbitMQ isn't found lacking here, so there is no specific
requirement to point to that would justify a second live broker (the kind
of requirement that *would* justify it: something Kafka does and RabbitMQ
structurally doesn't, like offset-based replay for an audit trail. No
such use case exists in EasyDora today). Introducing a broker to have data
to write into an ADR, then defending its presence retroactively, is
exactly the kind of hybrid state ADR-0007 (Migration Strategy) and
[architectural-principles.md](../architecture/architectural-principles.md)
principle #6 rule out. The benchmark harness in
`benchmarks/broker-comparison/` (see its own README for the full
methodology and raw JSON) is standalone instead: its own `docker-compose.yml`,
its own Go module, pointed at a Kafka and a RabbitMQ container that exist
only for the duration of a benchmark run and are never part of any
service's runtime dependency graph.

## Decision

RabbitMQ remains EasyDora's only live broker. This ADR's contribution is
replacing "probably fine" with measured numbers, run 2026-08-01 on a
single machine, single run per scenario (see
`benchmarks/broker-comparison/results.jsonl` for the raw output and the
harness's own README for full methodology and caveats):

**Throughput/latency** (one publisher, one consumer, synchronous
publish-then-wait-for-broker-ack per message — the same call pattern
`OutboxPublisher` already uses everywhere it exists, ADR-0037):

| Broker | Producer throughput | Publish p50 | Publish p99 |
|---|---|---|---|
| RabbitMQ | 1199 msg/s | 0.69 ms | 2.65 ms |
| Kafka | 84 msg/s | 11.6 ms | 15.9 ms |

**Broker-down behavior** (continuous publishing while the broker
container is stopped and restarted mid-run):

| Broker | Attempted | Confirmed | Reached consumer | Recovered before test ended |
|---|---|---|---|---|
| RabbitMQ | 207 | 206 | 205 | Yes, automatically |
| Kafka | 230 | 82 | 82 | No — zero successful publishes after the failure began |

Under EasyDora's actual call pattern (one confirm-wait per message, not
Kafka's batched producer mode), RabbitMQ is roughly 14x faster end to end
on this machine, and recovered from a hard container kill within the test
window while the Kafka client (`kafka-go`'s default `Writer`) did not.
Both results support ADR-0007's original conclusion, now with a number
behind it instead of only an argument.

One caveat this benchmark surfaced that ADR-0007 didn't have data on
either: RabbitMQ's automatic recovery is not lossless under a *hard*
container kill — 2 of 207 attempted messages received a positive broker
acknowledgment yet never reached the consumer. This is a narrow,
previously-undocumented gap in EasyDora's own outbox at-least-once
guarantee (ADR-0037) under a non-graceful broker failure, not a general
RabbitMQ limitation, and not something this ADR fixes — see Objective
criteria below.

## Consequences

**Positive**: ADR-0007 now has a measured answer, committed to the repo
(`benchmarks/broker-comparison/results.jsonl`), not just an argument;
future "have you actually operated/compared these" questions have real
numbers instead of a design rationale to recite; the harness itself is
reusable if a future ADR needs to re-measure after either broker's config
changes.

**Negative / residual**:
- Single machine, single run per scenario, single-node unreplicated Kafka
  (KRaft) against a single-node RabbitMQ — this rules out "RabbitMQ is
  probably fine," not "RabbitMQ wins under all conditions." A clustered,
  multi-broker comparison would very plausibly change the throughput gap;
  this ADR does not claim otherwise.
- The RabbitMQ hard-kill data-loss gap (2/207 acknowledged-but-undelivered
  messages) is new, real, and unresolved by this ADR.
- The benchmark harness duplicates a small amount of publish/consume
  logic that already exists, differently, in `inventory-service`'s real
  `RabbitMQPublisherAdapter`-equivalent code — acceptable here because the
  harness's whole point is to be free of any of that production code's
  own assumptions, but it means the two are not literally exercising the
  same code path.

## Objective criteria for revisiting this decision

Re-open this ADR (or ADR-0007) if any of the following becomes concretely
true, not hypothetically:

- A specific EasyDora use case needs offset-based event replay or
  ordered-partition consumption that a RabbitMQ queue genuinely cannot
  express reasonably (e.g., an audit/analytics consumer that must be able
  to re-read the last 30 days of a given event type on demand). At that
  point, the second-broker-adapter design sketched and rejected above
  becomes appropriate — for that one consumer, not as a blanket second
  broker.
- The RabbitMQ hard-kill data-loss gap this ADR found recurs in a real
  incident (not just this benchmark), at which point it deserves its own
  ADR investigating publisher-confirm semantics under non-graceful broker
  restarts.
- A clustered (not single-node) rerun of this same harness changes either
  result materially enough to change the throughput or recovery
  conclusion above.

## References

- [ADR-0007](0007-remove-kafka-broker.md) — the original, argued-not-measured
  decision this ADR adds real numbers to; this ADR does not change its
  Decision.
- [ADR-0037](0037-consolidated-outbox-pattern-specification.md) — the
  outbox delivery guarantee whose call pattern (publish, wait for ack,
  then mark published) this benchmark's throughput/latency test
  deliberately mirrors, and whose at-least-once guarantee the RabbitMQ
  hard-kill finding narrowly qualifies.
- [Architectural Principles](../architecture/architectural-principles.md)
  — principle #6 (no hybrid broker state) is why this benchmark's harness
  is standalone rather than a second adapter living inside a real
  service.
- `benchmarks/broker-comparison/` — the benchmark tool itself, its
  `README.md` (full methodology, reproduction steps, caveats), and
  `results.jsonl` (raw output this ADR's numbers are drawn from).
