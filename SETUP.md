# Setup

Local environment setup steps after cloning this repository.

## Prerequisites

- Docker Desktop (Windows/Mac) or Docker Engine (Linux) + Docker Compose
- Java 17 and Maven (for auth-service, products-service, orders-service, billing-service)
- Go 1.21+ (for api-gateway, inventory-service)
- Git

## Bring up infrastructure and services

```bash
git clone <repo-url>
cd easydora
docker-compose up -d
docker-compose ps
```

This starts PostgreSQL (5432), RabbitMQ (5672, management UI on 15672), Prometheus (9090), Grafana (3001), and the implemented application services. See the root `README.md` for the current per-service status.

To build/test an individual Spring Boot service locally:

```bash
cd <service-dir>
mvn test
```

Note: `mvn test` for services whose tests boot a Spring context against a real datasource (e.g. `@SpringBootTest`) requires the Postgres/RabbitMQ containers above to already be running.

To build/test an individual Go service locally:

```bash
cd <service-dir>
go test ./...
```

## Editor/tooling artifacts

Standard ignore rules for local editor state, build output, and dependency directories are already in the project's `.gitignore`. If your own tooling generates additional local-only files or directories in this repo, add them to your global git ignore rather than committing project-specific exceptions, unless the exclusion is genuinely relevant to every contributor.

## Commit hygiene

If you use any local development tooling (assistants, generators, IDE plugins, etc.) that can auto-insert co-authorship trailers or similar metadata into commit messages, it is each contributor's responsibility to configure their own tooling so that this repository's commits stay free of such trailers. Configure this once in your tool's settings before committing here.

Git hooks (`.git/hooks/`) are local to each clone and are not tracked by git. If you rely on a `commit-msg` or other hook for local commit message validation, you'll need to recreate it yourself after cloning — nothing here does that automatically.
