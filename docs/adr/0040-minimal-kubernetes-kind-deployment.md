# ADR-0040: Minimal Kubernetes (kind) deployment as a parallel execution platform, alongside Docker Compose

## Status

Accepted - 2026-07-17

## Context

The project already demonstrates the distributed architecture correctly in
Docker Compose. However, some properties of a declarative deployment
platform remain implicit, because Docker Compose abstracts them away:
continuous reconciliation of desired state, the use of health checks to
govern availability and automatic recovery, the explicit separation
between configuration and application, and the declarative representation
of infrastructure resources. Introducing a minimal Kubernetes environment
makes these properties visible, while preserving the application's
architecture and its communication contracts.

Each of these four properties has a concrete, verifiable counterpart in
this system, not just an abstract claim:

- **Continuous reconciliation.** Docker Compose does not correct drift
  after `docker compose up` returns — nothing re-applies the desired state
  if a container is later modified or killed outside Compose's own
  commands. Kubernetes' controllers (the ReplicaSet controller behind every
  Deployment here) keep the declared state continuously, not once at
  startup.
- **Health checks governing availability.** Every service in this project
  already exposes a real `/health` endpoint (or `/<context>/health` for the
  four Spring services), used today by each Dockerfile's own `HEALTHCHECK`.
  In Docker Compose, that healthcheck only gates the *order* containers
  come up, once, via `depends_on: condition: service_healthy` — nothing
  in this project's `docker-compose.yml` sets a `restart:` policy, so a
  container whose `/health` starts failing after boot keeps receiving
  traffic indefinitely. In Kubernetes, the exact same endpoint — reused
  without any change — is polled continuously: `readinessProbe` decides
  whether the pod receives traffic through its Service's endpoints, and
  `livenessProbe` decides whether the pod gets restarted. The interface is
  identical; only the platform now acts on it continuously instead of once.
- **Separation between configuration and application.** In
  `docker-compose.yml`, environment variables are declared inline, in the
  same file and the same object as the container's runtime spec. In
  Kubernetes, `ConfigMap` and `Secret` are independent, addressable API
  objects with their own lifecycle, referenced by a Deployment rather than
  embedded in it.
- **Declarative representation of infrastructure.** A `docker-compose.yml`
  file only exists as a one-shot input to `docker compose up`; there is no
  persistent, independently queryable representation of "this is the
  desired state of the system" once the command returns. Kubernetes objects
  are stored and remain queryable via the API server independent of any CLI
  invocation.

A separate, related finding does **not** belong to the same list: every
backend service already resolves every other one exclusively by hostname
(`AUTH_SERVICE_URL=http://auth-service:8081`, etc., never a fixed IP),
because Docker Compose already provides DNS-based service discovery by
container name. Kubernetes resolves this the same way, via Service DNS —
**provided the Kubernetes Service is given the same name Compose already
uses as the hostname**, which this delivery does deliberately. This is
evidence that the architecture was already portable before this ADR, not
evidence of a property Compose was hiding — it is kept distinct from the
four properties above for that reason.

This decision does not change the application's architecture. It expands
the ways the same architecture can be executed. Docker Compose remains the
recommended environment for local development, while Kubernetes becomes an
alternative implementation of the same topology, allowing the
architectural decisions already made to be validated as independent of the
orchestration platform.

The adoption of Kubernetes does not introduce a new architecture, nor does
it replace the existing one; it demonstrates that the current architecture
is sufficiently decoupled from its execution platform to be reproduced
under a different orchestration mechanism, with changes confined to
declarative infrastructure.

## Decision

**Adopt kind as a local, single-node Kubernetes cluster, running the
backend and observability stack in parallel to Docker Compose, with zero
application code changes.**

### Tooling: kind, over Minikube and k3d

- **Minikube** is the most mature, most tutorialized local Kubernetes
  tool, but historically runs on a VM (or a single node via its Docker
  driver) and ships its own addons (dashboard, ingress, metrics-server)
  that add a layer of convenience above raw `kubectl` — the opposite of
  what this initiative is meant to exercise.
- **k3d** packages k3s (a distribution built for edge/IoT) running inside
  Docker containers. It is fast and light, sometimes lighter than kind, but
  k3s substitutes real control-plane components (an embedded SQLite store
  in place of etcd by default) and ships add-ons enabled by default
  (Traefik as an ingress controller). It is not an unopinionated
  distribution — decisions have already been made for you.
- **kind** is maintained by the Kubernetes project's own SIG-Testing. It
  runs the real upstream `kube-apiserver`/`kubelet`/`etcd` inside Docker
  containers, executing the upstream components without adding its own
  layer of abstractions or default-enabled components, and works directly
  with the platform's native mechanisms — no ingress controller
  pre-installed, no component swapped for a lighter substitute. It is the
  tool the Kubernetes project itself uses to test control-plane
  conformance, while remaining as fast and light as k3d for the single
  node this project needs.

kind is adopted for this reason, not familiarity or popularity.

### Scope

- One Namespace (`easydora`).
- One manifest set, Kustomize base only, **no overlays** — this project
  runs a single environment (local kind); a dev/prod split would be
  ceremony with nothing to differentiate.
- **One PersistentVolumeClaim, for Postgres only.** RabbitMQ redeclares its
  queues/bindings idempotently from each consuming service's own startup
  code (the same mechanism Docker Compose already relies on), so losing
  its state on a pod restart costs nothing real here. Prometheus loses only
  metric history, not a requirement for this environment. Grafana's own
  SQLite state (users, sessions) doesn't need to survive either, since its
  datasource and dashboards are both provisioned from files on every boot.
- **Deployment, not StatefulSet, for Postgres and RabbitMQ.** A
  StatefulSet's real value — stable per-replica network identity, a
  dedicated PVC per replica via `volumeClaimTemplates`, ordered scaling —
  has nothing to attach to at a single replica. Kubernetes' own official
  "Run a Single-Instance Stateful Application" tutorial uses a Deployment
  with one directly-referenced PersistentVolumeClaim for exactly this case
  (a single-instance stateful application), not a StatefulSet. RabbitMQ
  confirms the same logic from another angle: official and community
  charts/operators only recommend StatefulSet for a *clustered* RabbitMQ
  (multiple replicas with peer discovery via stable identity), which is
  not this project's case.
- `ConfigMap`s generated directly from the project's existing versioned
  files (`init-scripts/01-create-schemas.sql`, `observability/prometheus/prometheus.yml`,
  `observability/grafana/provisioning/**`, `observability/grafana/dashboards/*.json`)
  via Kustomize's `configMapGenerator` — one source of truth, nothing
  duplicated into `k8s/`.
- `Secret`s for credentials, following the same convention already used for
  the root `.env`/`.env.example` (a gitignored real file, a committed
  example with placeholder keys).
- Zero application code changes.
- The frontend is explicitly out of this delivery (see Non-Goals).

## Non-Goals

This initiative deliberately does not attempt to demonstrate:

- High availability.
- Autoscaling (HPA/VPA).
- Multiple environments or GitOps.
- A service mesh.
- Secrets management beyond the pattern this project already uses for
  `.env` (no Vault, Sealed Secrets, or External Secrets Operator).
- Real cloud provisioning (EKS/GKE/AKS).
- CI running against kind.
- The frontend, in this first delivery — it is a separate, later, optional
  delivery, since it adds no meaningful demonstration of platform
  properties in the backend and carries its own, unrelated complication
  (`VITE_GATEWAY_URL` baked into the Vite build at build time, not read at
  runtime).

### Alternatives rejected

| Alternative | Why it was rejected |
|---|---|
| Helm | No environment variation to templatize. |
| ArgoCD / FluxCD | No multi-environment promotion flow to automate. |
| Service Mesh | No mTLS/traffic-shaping requirement; service coordination is already handled by RabbitMQ. |
| Ingress Controller | NodePort/port-forward already solve access for a single-developer local cluster. |
| Cert-Manager | No local TLS termination need. |
| HPA / VPA | Fixed-replica topology, no real load variation to scale against. |
| StatefulSet (Postgres/RabbitMQ) | Its real value doesn't apply at a single replica — see Decision above. |
| Minikube / k3d | See Decision above — kind runs upstream Kubernetes without component substitutions or default-enabled add-ons. |

## Objective criteria for revisiting this decision

In the same spirit as [ADR-0039](0039-jwt-broadcast-cache-restart-and-ttl.md)'s
own criteria:

- **Any service in this project ever needs more than one replica** (the
  same trigger ADR-0039 already uses). At that point, Deployment+PVC no
  longer suffices for Postgres, and StatefulSet earns its place — not
  before.
- **A real need for multiple environments emerges.** At that point,
  Kustomize overlays (or Helm) earn their place — not before.

## Consequences

**Positive**: platform properties this project never exercised before
(continuous reconciliation, active health-driven traffic/restart,
declarative configuration as first-class objects) become real and
verifiable, not just a design claim; zero application code change was
required, thanks to architectural choices already in place before this
initiative existed (DNS-based service discovery by hostname, a real
`/health` endpoint on every service).

**Negative / residual, not fixed here**:
- One more place to keep manually in sync with `docker-compose.yml`
  (environment variables, ports, image names) — there is no templating
  layer here that would catch drift automatically between the two.
  Accepted, given the scope defined above.
- `k8s/base/secrets.yaml` duplicates a few values already present in the
  root `.env` (and, for `notification-service`'s `RABBITMQ_URL`, the
  password appears twice within the same Secret file, since a raw
  Kubernetes Secret manifest cannot interpolate one field into another the
  way `docker-compose.yml`'s `${RABBITMQ_PASSWORD}` substitution does).
  Manual to keep in sync when rotating a password; accepted at this scale.

## Update — 2026-07-17: frontend added

The frontend, deliberately excluded from the first delivery above, is now
added as its own Deployment/Service (`k8s/base/frontend/`). It reuses the
exact same image already built for Docker Compose, unmodified —
`VITE_GATEWAY_URL=http://localhost:8080` is baked in at Vite build time,
and `k8s/kind-config.yaml`'s `extraPortMappings` already expose the
Gateway's NodePort at that same host address, so the frontend's build-time
assumption holds without a rebuild. A second `extraPortMappings` entry
(NodePort 30081 → host 3000) does the same for the frontend itself,
matching Docker Compose's own `3000:3000` publish. No PersistentVolumeClaim
(a static SvelteKit build, no state of its own); `tcpSocket` readiness/
liveness probes, since the frontend has no `/health` endpoint of its own
(SSR is disabled — see ADR-0026 — so there's no server-side route to
probe, only the static file server).

This closes the one open Non-Goal from the original scope. Every other
Non-Goal listed above remains unchanged and out of scope.

## References

- [ADR-0007](0007-remove-kafka-broker.md) — an earlier infrastructure
  decision driven by an objective operational trigger, contrasted with this
  one's different kind of motivation (making implicit platform properties
  explicit, not resolving an operational pain point).
- [ADR-0018](0018-persistence-strategy.md) — the single-Postgres-instance
  decision this ADR reproduces unchanged under a different orchestrator.
- [ADR-0039](0039-jwt-broadcast-cache-restart-and-ttl.md) — the
  replica-count criterion this ADR's own "objective criteria for
  revisiting" reuses.
- `docs/architecture/architectural-principles.md` — Principle #2 ("a
  component must earn its place"), Principle #3 ("remove complexity that
  doesn't add architectural value"), and Principle #4 ("behavior over
  technology"), all directly exercised by this decision.
- `k8s/README.md` — how to build, load, and apply this delivery.
- README Roadmap — the item this ADR closes.
