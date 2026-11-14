# Operational Intelligence: a proposed architectural evolution

> **Status: Proposed, deferred.** This document describes a possible
> direction. It is not an approved implementation, it has no delivery date,
> and none of the capabilities described under "Proposed" exist today. It is
> deliberately not an ADR (ADRs in this repository record decisions that
> were made) and not a Roadmap item (nothing here is a backlog entry).
>
> Date: 2026-11-14

## 1. Purpose and framing

EasyDora already demonstrates event-driven microservices, the Outbox
pattern, contract testing, resilience patterns, and two observability
pillars (identifier-based tracing plus OpenTelemetry/Jaeger, and
Prometheus/Grafana metrics). This document explores one possible next
layer: **Operational Intelligence**, meaning components that analyze
operational signals, flag abnormal behavior, correlate events across
services, support incident investigation, and eventually recommend or
execute controlled corrective actions.

"AIOps" is the usual industry term for this area. This document prefers
"Operational Intelligence" because the intent here is narrower: a
complementary, explainable layer built on what EasyDora already has, as a
demonstration of backend engineering, distributed-systems reasoning, and
architectural judgment. It is not an attempt to reposition EasyDora as an
AIOps or SRE platform, to compete with commercial observability products,
or to claim production-grade SRE, cloud, or AIOps experience. EasyDora
remains an educational and demonstrative project (see
[CONTRIBUTING.md](../../CONTRIBUTING.md)).

How to read the labels used below:

- **Existing**: verified in this repository at the time of writing.
- **Proposed**: part of this proposal only; not implemented, not approved.
- **Assumption**: believed true or achievable, not validated.

## 2. What EasyDora already provides (Existing)

| Capability | Where it is documented | Relevance to this proposal |
|---|---|---|
| CorrelationId, RequestId, and MessageId propagated over HTTP headers and native AMQP properties; consistent logfmt log fields in every service | [observability.md](observability.md), [ADR-0024](../adr/0024-distributed-tracing-via-propagated-identifiers.md) | Join key between logs, traces, and messages for one business operation |
| OpenTelemetry spans exported to a Jaeger all-in-one container, across Go, Java, and Python services; `traceparent` carried through RabbitMQ and across the Outbox write-to-publish gap | ADR-0024 (2026-08-02 and 2026-08-03 Updates) | Per-hop latency and span trees as evidence |
| Prometheus and Grafana: request rate, latency, error rate, queue depth (via RabbitMQ's own Prometheus plugin), HikariCP pool metrics, a small set of business counters, including `inventory_idempotent_duplicate_detected_total` | [ADR-0036](../adr/0036-metrics-via-prometheus-grafana.md) | Primary source of time series for anomaly detection |
| Resilience behavior that produces observable failure signatures: circuit breakers (ADR-0006, ADR-0009), bounded retry plus dead-letter queues (ADR-0019), idempotent consumers, payment compensation saga (ADR-0034), Outbox pattern (ADR-0003, ADR-0007, ADR-0037) | Respective ADRs | Realistic, documented failure modes to detect and explain |
| Uniform health checks, Kustomize/kind deployment alongside Docker Compose | ADR-0010, [ADR-0040](../adr/0040-minimal-kubernetes-kind-deployment.md) | Possible execution surface for controlled remediation experiments |
| CI with real Postgres/RabbitMQ and cross-service end-to-end tests | ADR-0012, ADR-0013 | Possible home for repeatable scenario tests |
| A Python service (notification-service, FastAPI) in an already polyglot repository | README, CONTRIBUTING | Python is already part of the repository's toolbox |

## 3. Gaps and assumptions requiring validation

These are the main reasons the proposal is deferred rather than ready.

1. **No durable trace history (Existing limitation).** The Jaeger container
   uses in-memory storage that is not persisted across restarts
   (`docker-compose.yml`). Historical evidence for investigation would need
   either persistence or an evidence store of its own.
2. **No queryable log store (Existing limitation).** ADR-0036 explicitly
   declines Loki; logs are logfmt consumed with grep. Using logs as
   machine-readable evidence is an Assumption.
3. **No alerting layer (Existing limitation).** ADR-0036 declines
   Alertmanager and names its revisit condition ("a real need to page
   someone emerges"). Anomaly output would need a destination that does not
   exist today.
4. **No CausationId (Existing, deliberate).** [observability.md](observability.md)
   explains that only CorrelationId is propagated, which yields a linear
   chain per operation, and that CausationId would earn its place if a
   branching causality graph were needed. Event correlation across
   fan-out may be that need; that would be its own decision.
5. **Metric retention and resolution (Assumption).** Prometheus scrapes
   every 15 seconds. Whether the retention and resolution configured for
   the demo environment are enough to build baselines is not validated.
6. **Traffic volume (Assumption).** A demonstrative environment has sparse,
   bursty traffic. Whether statistical baselines are meaningful without
   synthetic load is not validated.
7. **No ground truth (Existing limitation).** I found no fault-injection or
   chaos tooling in the repository. Any precision/recall claim would depend
   on a scenario catalogue with known injected faults, which does not
   exist.
8. **Reproducible failures (Assumption).** Whether the deterministic payment
   provider ([ADR-0030](../adr/0030-deterministic-payment-provider.md)) and
   existing resilience paths can produce repeatable, labeled incidents has
   not been checked.
9. **Change signals (Assumption).** Version tags, the changelog, and
   container restarts could serve as "recent change" events, but no
   machine-readable change stream exists.

## 4. Capability areas (Proposed)

**4.1 Operational telemetry analysis.** Treat metrics, traces, logs, and
events as evidence records tied to a service, time window, and (when
available) CorrelationId. Interpret them with a service dependency map
(HTTP and RabbitMQ edges) rather than per-service in isolation. Reuse
OpenTelemetry, Jaeger, Prometheus, and Grafana where they suffice.

**4.2 Anomaly detection.** Detect abnormal latency, throughput, error rate,
retries, queue depth, dead-letter arrivals, and similar indicators with
explainable statistics first: moving averages, EWMA, Z-score or robust
variants (median/MAD), and comparison against naive static thresholds. Every
detection states the indicator, window, baseline, deviation, and method
parameters. More complex models are considered only if measurements show the
simple methods are insufficient.

**4.3 Event correlation.** Group anomalies and events into candidate
incidents using temporal proximity, dependency edges, correlation
identifiers, retries and redeliveries, and recent changes. Output means
"related", never "caused". Correlation and causation are kept distinct in
data model and wording.

**4.4 Incident investigation and root cause analysis.** Produce ranked
hypotheses, each with supporting evidence, contradicting or missing
evidence, alternative explanations, and a confidence level. A hypothesis is
never presented as an established fact.

**4.5 Controlled remediation.** Recommend actions first. Any later
execution starts with human approval, a closed allowlist of actions, and
the requirements in stage 5. Autonomous or unrestricted remediation is not
an assumption of this design.

## 5. Fit with the repository's architectural principles

The [architectural principles](architectural-principles.md) apply to this
proposal as much as to any other change:

- **A component must earn its place (principle 2).** No new component is
  justified by this document alone. Each stage must show what existing
  signals cannot answer before anything new is added. Candidate new elements
  below are options, not commitments.
- **Evidence over assumption (principle 8).** Hence section 3 and the
  measurement-first evaluation in section 7.
- **Incremental rollout (principle 7).** Stages are ordered so each is
  useful, reviewable, and removable without a rewrite.
- **Honest about what is unresolved (principle 10).** A future decision to
  adopt any stage would be recorded as an ADR then, with its own
  alternatives, including doing nothing.
- **Repository conventions.** CONTRIBUTING.md asks that no new cross-service
  shared library be introduced without discussion, and that new messages
  come with JSON Schemas and contract tests. Both would apply to any
  component that publishes or consumes events.

No technology is presumed. Kubernetes, AWS, Kafka (removed by
[ADR-0007](../adr/0007-remove-kafka-broker.md)), or any new infrastructure
are not requirements of this proposal.

## 6. Possible evolution stages (conceptual)

The stages are a conceptual sequence, not tasks. Each depends on evidence
from the previous one, and the sequence could stop at any point.

### Stage 1: Operational Intelligence Foundation

- **Purpose.** Make existing telemetry usable as evidence, without changing
  service behavior.
- **Would introduce.** Read-only access to metrics, traces, and logs; a
  normalized evidence record; a service dependency map; an inventory of
  which signals exist per service.
- **Possible reuse.** Prometheus and Jaeger query APIs; CorrelationId as the
  join key; ADR-0036 metrics.
- **Possible new elements.** One analysis component, likely outside the
  business services (Python is a natural fit for analytical code); a
  small evidence store if Prometheus/Jaeger retention proves insufficient.
- **Risks and considerations.** In-memory trace storage; unstructured logs;
  metric cardinality; adding infrastructure that fails principle 2; the
  component must not become a dependency of the business flow.
- **Evidence of effectiveness.** A past operation's timeline reconstructed
  from stored evidence alone, by CorrelationId; a documented coverage report
  of available versus missing signals.

### Stage 2: Anomaly Detection

- **Purpose.** Flag deviations in operational indicators with explanations a
  reader can verify.
- **Would introduce.** Per-indicator baselines and detectors (moving
  average, EWMA, Z-score, robust variants) over latency, throughput, errors,
  retries, queue depth, dead-letter arrivals, and existing business
  counters.
- **Possible reuse.** Prometheus series from ADR-0036; the idempotent
  duplicate counter as an example of an expected, benign signal.
- **Possible new elements.** A detector job and versioned detector
  configuration; a labeled scenario set (see section 7).
- **Risks and considerations.** Sparse demo traffic; counter resets on
  restarts; non-stationary series; alert fatigue; a Z-score assumes a
  reasonably stable distribution; no alert destination exists today.
- **Evidence of effectiveness.** Precision, recall, and false-positive rate
  on labeled scenarios, always compared with a naive static-threshold
  baseline. If the baseline is as good, the added complexity is not
  justified.

### Stage 3: Event Correlation

- **Purpose.** Group related anomalies and events across services into
  candidate incidents.
- **Would introduce.** Temporal windowing, dependency-aware grouping,
  correlation by CorrelationId and trace, treatment of retries and
  redeliveries, and use of change events where available.
- **Possible reuse.** CorrelationId, MessageId, `traceparent` across the
  Outbox gap, the dependency map from stage 1.
- **Possible new elements.** A correlation component; possibly a
  CausationId, which would require its own ADR and contract changes.
- **Risks and considerations.** Clock skew; false grouping caused by shared
  dependencies (RabbitMQ, Postgres); over- and under-grouping; presenting
  correlation as cause.
- **Evidence of effectiveness.** Grouping quality against labeled incidents:
  how many events from one injected fault land in one group, and how many
  unrelated events are kept out.

### Stage 4: Incident Investigation and RCA

- **Purpose.** Help a person investigate by assembling evidence-backed
  hypotheses.
- **Would introduce.** A hypothesis record containing statement, supporting
  evidence references (trace IDs, metric windows, log lines), contradicting
  or missing evidence, alternatives, and a qualitative confidence level
  with a documented rule. Deterministic rules and graph traversal come
  first; any language-model assistance would only summarize retrieved
  evidence, and its output would not count as evidence.
- **Possible reuse.** Stages 1 to 3; documented failure semantics of circuit
  breakers, retries, dead-letter queues, and the compensation saga.
- **Possible new elements.** An investigation view or report format.
- **Risks and considerations.** Confirmation bias; unsupported causal
  claims; miscalibrated confidence; evidence that expired before the
  investigation.
- **Evidence of effectiveness.** Every claim in a hypothesis resolves to
  retrievable evidence (checkable mechanically); the injected fault appears
  among the ranked hypotheses; investigation time compared with a manual
  investigation of the same scenario.

### Stage 5: Controlled Remediation

- **Purpose.** Move from recommending actions to executing a small set of
  them under strict control.
- **Would introduce.** First, recommendations linked to hypotheses. Later,
  human-approved execution from a closed allowlist of actions.
- **Architectural requirements.** Idempotent actions; authorization;
  audit log of request, approval, execution, and outcome; execution limits
  (rate and blast radius); rollback; defined failure handling; dry-run.
- **Possible reuse.** Health checks (ADR-0010); Kubernetes reconciliation
  (ADR-0040) for the cases it already covers; existing idempotency
  approaches.
- **Possible new elements.** An approval and audit mechanism; a restricted
  executor with narrowly scoped credentials.
- **Risks and considerations.** Acting on a wrong hypothesis; credential
  scope (see [ADR-0005](../adr/0005-secret-rotation.md) for why secrets
  handling matters here); duplicating what the platform already does;
  scope limited to the local demonstration environment.
- **Evidence of effectiveness.** Complete audit trail for every action;
  unauthorized and out-of-allowlist requests are rejected; repeating an
  action is safe; rollback is exercised in a test; failure handling is
  exercised by forcing the executor to fail.

## 7. Evaluation criteria

No target values are proposed, and no results are claimed. Any threshold
would be set only after a baseline is measured.

- **Anomaly detection precision and recall**, measured on labeled windows
  (injected fault versus normal operation).
- **False-positive rate**, including normal operation with restarts and
  benign signals such as idempotent duplicate detection.
- **Event correlation quality**, measured as grouping completeness and
  purity against labeled incidents.
- **RCA hypothesis quality and traceability**: evidence completeness, ranking
  of the known cause, and explicit alternatives.
- **Detection and investigation time**, compared with a manual baseline on
  the same scenarios.
- **Remediation safety and auditability**: audit completeness, rejection of
  unauthorized actions, idempotent re-execution, rollback behavior.

Caveat: scenarios would be synthetic and run in a demonstration
environment. Results would show the method works on those scenarios, not
that it would hold in production.

### Demonstrable scenarios (illustrative; flows to be validated)

- **A. Multi-service degradation.** Latency is injected into
  billing-service. Possible observable chain: higher latency in the
  gateway-to-billing path, the gateway's billing circuit breaker opening
  (ADR-0009), delayed order and payment events, compensation (ADR-0034).
  The system groups these signals into one candidate incident and produces
  a hypothesis for billing-service degradation with linked evidence, plus an
  alternative such as database contention that it could not rule out.
- **B. Consumer failure.** A consumer starts failing; retries are
  exhausted (ADR-0019); dead-letter arrivals and queue depth rise. The
  investigation distinguishes consumer-side errors from broker slowdown,
  and states which evidence is missing for each.
- **C. Benign signal.** A burst of idempotent duplicate detections in
  inventory-service. The expected outcome is a low-severity, correctly
  explained signal, not an incident. This tests false positives.
- **D. Control windows.** Normal traffic and routine restarts with no
  fault, used to measure the false-positive rate.

## 8. Non-goals

- A generic observability platform or a competitor to commercial products.
- New infrastructure introduced to look sophisticated.
- Autonomous agents or unrestricted automated remediation as an initial
  assumption.
- A large rewrite of the existing architecture.
- Claims of production-grade SRE, cloud, or AIOps experience.

## 9. Explicitly deferred decisions

- This is a proposal for architectural evolution, not an approved
  implementation.
- No implementation commitment or delivery date is established.
- Market demand and strategic value should be reassessed before any
  implementation.
- Technical feasibility and integration with the existing architecture must
  be reviewed before any implementation, starting with the gaps in
  section 3.
- The proposal may be refined, postponed, or discarded based on future
  evidence.
- Any decision to proceed would be recorded as an ADR at that time, and
  would not be inferred from this document.
