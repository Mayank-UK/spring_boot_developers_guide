# Spring Boot Developers Guide

A runnable full-stack reference app that **is** the guide: the server (Spring Boot 4.1 + Java 25 + Maven) carries all the teaching content as a typed content API, and the client (React 19 + Vite + TypeScript + Tailwind v4) is a presentation-only shell that renders it — never teaches React. Demos answer from real in-container PostgreSQL, Redis, and MongoDB, with runtime scenario toggles for fake/live data and failure injection.

> Stub README — docs expand as the guide grows.

## Run it

**VS Code devcontainer (the standard way)** — development happens entirely inside Docker: no host Maven, no host `node_modules`.

1. Open the repo in VS Code → **Reopen in Container** (Dev Containers extension + Docker Desktop required).
2. The first open builds the toolchain image and boots the full stack — server + client (hot-reload via bind mounts) + PostgreSQL + Redis + MongoDB. Open **http://localhost:5176**.

The integrated terminal is the `dev` tooling container — for tests (`cd server && mvn test` · `cd client && npm test`), git, and builds, not for running the app.

**Full dev stack via plain Docker** (no VS Code):

```bash
docker compose -f .docker/docker-compose.yml up
```

Production stack (layered-jar server image + nginx-served client):

```bash
docker compose -f .docker/docker-compose.prod.yml up --build
```

## Ports

| Service | URL / address | Notes |
| ------- | ------------- | ----- |
| Client (dev) | http://localhost:5176 | Vite dev server; `/api` proxied to the server |
| Server | http://localhost:8080 | Spring Boot API; try `GET /api/health` |
| Client (prod) | http://localhost:8081 | nginx serving the built client; `/api` proxied to the server service |
| PostgreSQL | 127.0.0.1:5432 | `guide`/`guide`/`guide`, published by the dev compose stack |
| Redis | 127.0.0.1:6379 | published by the dev compose stack |
| MongoDB | 127.0.0.1:27017 | published by the dev compose stack |

## Layout

```
server/         Spring Boot 4.1 + Java 25 — core / content / domain / data / config / presentation
client/         React 19 + Vite + TS + Tailwind v4 — presentation shell + demo components
e2e/            Playwright browser E2E — host-run against the published dev ports (Phase 12)
.docker/        dev & prod Dockerfiles, nginx.conf, dev & prod compose files
.devcontainer/  VS Code devcontainer — attaches to the compose `dev` service
deploy/         k8s manifests for the deployment curriculum (Phase 10)
.github/        CI workflows
```

Docs expand as the guide grows — the full curriculum and build phases live in `agentic_development.md`.
