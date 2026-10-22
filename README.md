# Spring Boot Developers Guide

A backend curriculum you can run. The **server is the book**: a Spring Boot 4.1.1 / Java 25 / Maven application that carries the entire teaching content — 44 topics in 10 groups — as a typed content API. The **client is a presentation-only shell** (React 19 + Vite + TypeScript + Tailwind v4) that renders what the server serves and never teaches React. Demos answer from real in-container PostgreSQL 17, Redis 7, and MongoDB 8, with runtime scenario toggles for fake/live data and failure injection.

One discipline shapes everything: **every code block in the guide is quoted from real, tested code in this repo.** A mechanical test (`SnippetSourcesTest`) checks each quoted block against the source file it names, in order, and fails the suite on anything unsourced or drifted. When you read a listing, you can open the file it came from, run it, and break it. That is the point.

## The curriculum

Each topic is concept-first sections, code quoted from this repo, its live demo mounted inline beside the text that teaches it, and a terminal companion — a curl tour against the running server. The served index at `GET /api/content` is the live table of contents; this table is the overview.

| # | Group | Topics | What it covers |
| - | ----- | -----: | -------------- |
| 1 | Fundamentals | 6 | the Java language, the JVM, and HTTP itself — the ground Spring stands on |
| 2 | Middleware & Request Handling | 4 | routing, the servlet filter chain and interceptors, request/response shapes, validation at the boundary |
| 3 | APIs | 5 | REST design, OpenAPI and Swagger UI, GraphQL, webhooks, gRPC |
| 4 | Data | 5 | PostgreSQL schema craft, SQL by hand via JDBC, Spring Data JPA, MongoDB, Redis |
| 5 | Caching & Messaging | 2 | HTTP caching and `@Cacheable`, Redis Streams, the transactional outbox |
| 6 | Auth & Security | 3 | sessions, API keys, JWT, OAuth2; RBAC and object-level ownership; headers, rate limiting, threat modeling |
| 7 | Files & Media | 1 | streamed uploads and downloads, range requests, image processing, path traversal |
| 8 | Internationalization | 1 | `Accept-Language`, `MessageSource`, formatting, locales vs timezones |
| 9 | Real-time & Jobs | 2 | SSE tickers, WebSocket chat, background jobs with retries and a DLQ |
| 10 | Platform | 15 | the long haul: exceptions, dependency management, architecture, observability, testing, debugging, environments, deployment (with real k8s manifests), performance, reliability, scaling, reactive Spring, best practices, third-party integrations, sagas / CQRS / event sourcing |

## Run it

**Devcontainer (the standard path).** Development happens entirely inside Docker: no host Maven, and the host's `client/node_modules` stays empty by design.

1. Open the repo in VS Code and **Reopen in Container** (Dev Containers extension + Docker Desktop required).
2. The first open builds the toolchain image and boots the full stack — server + client (hot-reload via bind mounts) + PostgreSQL + Redis + MongoDB. Then open <http://localhost:5176>.

The integrated terminal is the `dev` tooling container — for tests, git, and builds, not for running the app; the `server` and `client` containers run their own dev servers.

**Plain Docker (no VS Code):**

```bash
docker compose -f .docker/docker-compose.yml up
```

A warm boot takes ~15–30 s; the first cold boot downloads Maven/npm dependencies into named cache volumes and can take minutes. There is also a production stack (layered-jar server image + nginx-served client on port 8081):

```bash
docker compose -f .docker/docker-compose.prod.yml up --build
```

**Tear it down when you are done.** The dev stack idles badly — a multi-container stack left up saturates the machine. `docker compose -f .docker/docker-compose.yml stop` keeps the cache volumes, so the next `up` is warm again.

| Service | Address | Notes |
| ------- | ------- | ----- |
| Client (dev) | http://localhost:5176 | Vite dev server; `/api` proxied to the server |
| Server | http://localhost:8080 | try `curl -sf http://localhost:8080/api/health` |
| Swagger UI | http://localhost:8080/docs | springdoc OpenAPI for every endpoint |
| Actuator | http://localhost:8080/actuator/health | plus `/liveness` and `/readiness` probes |
| Client (prod) | http://localhost:8081 | nginx serving the built client |
| PostgreSQL / Redis / MongoDB | 127.0.0.1:5432 / :6379 / :27017 | `guide`/`guide`/`guide`; published by the dev stack |

Where to click: the client home renders the served groups and topic cards; every topic page interleaves prose, quoted code, and its live demo (not queued at the page end), and closes with the terminal companion's curl tour. Demos with data answer from the real stores, switchable at runtime via `GET`/`PATCH /api/demo/scenario` (`dataMode: fake | live`, failure injection); `POST /api/demo/reset` returns shared demo state to a clean slate.

## Verify it

The host stays clean by design — no host Maven, empty host `node_modules` — so verification runs in throwaway Docker containers against the named cache volumes (`sbdg-m2`, `sbdg-npm`). Server tests need the Docker socket: persistence tests spawn a real `postgres:17` sibling via Testcontainers.

```bash
# Server: full test suite
docker run --rm -v "$PWD":/repo -w /repo/server -v sbdg-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-25 bash -lc 'flock /repo/server/.verify.lock mvn -q test'

# Server: gated build — adds the JaCoCo LINE-coverage floors (bundle 0.85 + per-package)
# …same command with `mvn -q verify`. Budget ~40 min with the coverage agent attached.

# Client: install, typecheck-build, and test
docker run --rm -v "$PWD":/repo -w /repo/client -v sbdg-npm:/root/.npm \
  node:22-bookworm bash -lc 'flock /repo/client/.verify.lock bash -c "npm ci && npm run build && npm test"'
```

The `flock` wrappers are deliberate: racing installs or builds on the shared bind mount corrupts `node_modules`/`target` and causes phantom failures. A first cold run downloads dependencies — if it times out, retry. At curriculum completion (October 2026): 1167 server tests, 322 client tests, all green.

The browser E2E suite (`e2e/`) runs on the host against the running dev stack — no Playwright `webServer`, the stack is managed by compose. The detailed recipe, port-conflict override, and teardown discipline live in [e2e/README.md](e2e/README.md).

## The repo

```
server/         Spring Boot 4.1 / Java 25, root package guide —
                  core/ (AppConfig, error taxonomy, Result, ScenarioStore) ·
                  content/ (the typed topic registry — the book itself) ·
                  domain/<area>/ (entities, ports, use cases) ·
                  data/<area>/ (adapters: jdbc, jpa, redis, mongo, messaging, …) ·
                  config/ (composition root: every port→adapter an explicit @Bean) ·
                  presentation/ (thin controllers, @ControllerAdvice, filters)
                Dependency rule: presentation → domain ← data. Controllers never
                import the data layer; DTOs never leak past adapters.
client/         React 19 shell — core/ (container, Result, config) · domain/ · data/ ·
                  ui/ (views, inline demos, routing)
e2e/            host-run Playwright suite against the dev stack
deploy/k8s/     the real manifests the deployment topic teaches
.docker/        dev & prod Dockerfiles, nginx.conf, dev & prod compose files
.devcontainer/  VS Code devcontainer, attaching to the compose `dev` service
```

## CI/CD

GitHub Actions, restored 2026-10-22 (hosted CI had been removed on 2026-10-10; the developer asked for it back). [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to master and every PR: the server suite as `mvn -B verify` (the same untrimmed gate as the local docker verify — Testcontainers, JaCoCo floors, enforcer; expect ~40+ min), the client suite + build, a gitleaks scan, and an image-smoke e2e job that builds both production images, boots them beside the platform datastores exactly as the deploy does, and drives the real UI with Playwright. Master pushes also publish attested (provenance + SBOM) images to GHCR for the VM's architecture. [`.github/workflows/deploy-production.yml`](.github/workflows/deploy-production.yml) blue/green-deploys a master commit (or a dispatched `sha-<7>` tag) to the VM: platform datastores first, the inactive color booted and probed privately, then a zero-downtime caddy flip with instant rollback on a failed public health gate.

The local docker verify above remains the first-line gate — CI runs the same contract on a clean runner. The `.docker/` platform/prod/ci compose trio that CI and the deploy consume (`docker-compose-platform.yml`, `docker-compose-prod.yml`, `docker-compose.ci.yml`) is separate from the legacy self-contained `docker-compose.prod.yml` teaching stack.
