# EasyDora on Kubernetes (kind)

A parallel, optional way to run EasyDora's backend and observability stack,
alongside Docker Compose -- not a replacement for it. See
[ADR-0040](../docs/adr/0040-minimal-kubernetes-kind-deployment.md) for why
this exists and what it deliberately does not attempt.

**Docker Compose remains the recommended path for day-to-day local
development.** Use this only when you specifically want to exercise the
Kubernetes object model.

This covers the backend + observability delivery only. The frontend is a
separate, later, optional delivery (see ADR-0040's Non-Goals) and is not
part of what's built here.

## Prerequisites

- Docker
- [`kind`](https://kind.sigs.k8s.io/)
- `kubectl`

## 1. Create the cluster

```bash
kind create cluster --config k8s/kind-config.yaml
```

> **cgroup v1 hosts (check with `docker info | grep "Cgroup Version"`,
> common on Docker Desktop/WSL2):** kind's default (newest) node image can
> fail to bootstrap the control plane on cgroup v1 (`kubeadm` times out:
> `client rate limiter Wait returned an error`). `k8s/kind-config.yaml`
> already pins `kindest/node:v1.31.2`, confirmed working on cgroup v1, for
> this reason — no extra step needed, but if you change or drop that pin
> on a cgroup v1 host, expect this failure mode.

## 2. Build the same images Docker Compose already builds

Same Dockerfiles, same build contexts as `docker-compose.yml` -- just
tagged for local use instead of Compose's own naming:

```bash
docker build -f api-gateway/Dockerfile          -t easydora-api-gateway:local          .
docker build -f auth-service/Dockerfile         -t easydora-auth-service:local         .
docker build -f products-service/Dockerfile     -t easydora-products-service:local     .
docker build -f orders-service/Dockerfile       -t easydora-orders-service:local       .
docker build -f billing-service/Dockerfile      -t easydora-billing-service:local      .
docker build -f inventory-service/Dockerfile    -t easydora-inventory-service:local    .
docker build -t easydora-notification-service:local notification-service
```

## 3. Load the images into the kind cluster

`kind` nodes don't share the host's image cache -- a locally built image
has to be loaded explicitly, or the pod stays `ImagePullBackOff` (no
registry is used at this scale):

```bash
kind load docker-image easydora-api-gateway:local          --name easydora
kind load docker-image easydora-auth-service:local         --name easydora
kind load docker-image easydora-products-service:local     --name easydora
kind load docker-image easydora-orders-service:local       --name easydora
kind load docker-image easydora-billing-service:local      --name easydora
kind load docker-image easydora-inventory-service:local    --name easydora
kind load docker-image easydora-notification-service:local --name easydora
```

## 4. Create the Secret

```bash
cp k8s/base/secrets.example.yaml k8s/base/secrets.yaml
# edit k8s/base/secrets.yaml with real values (same ones as the root .env)
kubectl apply -f k8s/base/secrets.yaml
```

`k8s/base/secrets.yaml` is gitignored, same convention as the root
`.env`/`.env.example`.

## 5. Apply everything else

```bash
kubectl kustomize k8s/base --load-restrictor LoadRestrictionsNone | kubectl apply -f -
```

`--load-restrictor LoadRestrictionsNone` is required: by default `kubectl`'s
built-in Kustomize refuses to read files from outside the kustomization's
own root, and this base's `configMapGenerator` deliberately reads the
project's existing `init-scripts/`/`observability/` files directly (one
source of truth, nothing duplicated into `k8s/`) -- see
[ADR-0040](../docs/adr/0040-minimal-kubernetes-kind-deployment.md). Plain
`kubectl apply -k k8s/base` fails with a `security` error for this reason.

## 6. Verify

```bash
kubectl get pods -n easydora -w
```

All eleven components (seven application services + postgres + rabbitmq +
prometheus + grafana) should reach `Running`/`1/1 Ready`.

Then replay `docs/walkthrough.md`'s curl sequence against the Gateway,
which is reachable at `http://localhost:8080` (the same port Docker Compose
already publishes it on, via `k8s/kind-config.yaml`'s `extraPortMappings`
onto the Gateway's NodePort):

```bash
curl http://localhost:8080/health
```

Grafana (same seven dashboards as Docker Compose, unmodified):

```bash
kubectl port-forward svc/grafana 3001:3000 -n easydora
# open http://localhost:3001
```

Prometheus:

```bash
kubectl port-forward svc/prometheus 9090:9090 -n easydora
```

## Incremental validation (matches the order things actually depend on each other)

If bringing this up step by step rather than all at once:

1. The apply command in step 5 creates everything at once, but you can
   watch `postgres`/`rabbitmq` reach `Ready` first, independent of any
   application service, before worrying about the rest.
2. `auth-service` first among the application services -- everything else
   depends on its `jwt.created` broadcast.
3. The remaining backend services, then `api-gateway` last (it depends on
   every other service already being resolvable by Service DNS).
4. `prometheus`/`grafana` at the end.

## Teardown

```bash
kind delete cluster --name easydora
```

Doesn't touch Docker Compose, which keeps working independently throughout
and after.
