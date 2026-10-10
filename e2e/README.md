# E2E — host-run Playwright suite

A small, real browser suite that runs **on the host** against the running dev
stack. It exercises the guide the way a reader experiences it: the home index
rendered from the content API, a flagship topic with its live inline demo and
verbatim code blocks, the terminal-companion curl-tour contract against the
server directly, the shared demo reset, and the error envelope on unknown
routes.

There is **no `webServer` in the Playwright config by design** — the stack
(server `:8080`, client `:5176`, PostgreSQL, Redis, MongoDB) is externally
managed via docker compose.

## Run it

Prerequisites: Docker Desktop (or a docker engine + compose), and Node on the
host (any maintained 20+/22 LTS; the suite installs nothing globally).

```bash
# 1. Start the dev stack from the repo root (boots in ~15-30s warm; the
#    first cold boot downloads Maven/npm deps into the named cache volumes
#    and can take several minutes).
docker compose -f .docker/docker-compose.yml up -d

# 2. Wait until the server answers health and the client serves:
curl -sf http://localhost:8080/api/health   # → {"status":"ok",...}
curl -sf http://localhost:5176 >/dev/null   # → Vite dev server

# 3. Install and run (first time also downloads the Chromium browser):
cd e2e
npm ci
npx playwright install chromium
npm test            # or: npm run test:headed
```

Typecheck only: `npm run typecheck` (`tsc --noEmit`).

If another stack already holds a host port (e.g. `:8080` is busy), bring the
same compose file up with a small override file that republishes the port and
point the suite at it:

```bash
printf 'services:\n  server:\n    ports:\n      - "18080:8080"\n' > /tmp/sbdg-ports.yml
docker compose -f .docker/docker-compose.yml -f /tmp/sbdg-ports.yml up -d
E2E_SERVER_URL=http://localhost:18080 npm test   # client URL default still applies
```

Defaults: client `http://localhost:5176` (`E2E_CLIENT_URL`), server
`http://localhost:8080` (`E2E_SERVER_URL`).

## Teardown discipline — stop the stack when done

The dev stack idles badly (a multi-container stack left up saturates the
machine; see the OPS note in `agentic_development.md`). When you are done:

```bash
docker compose -f .docker/docker-compose.yml stop
```

Restart later with the same `up -d` command — named volumes keep the Maven /
npm caches, so warm restarts are quick. Treat the teardown as part of every
session, not an optional extra.

## What the suite covers

| Spec | Asserts |
| ---- | ------- |
| `tests/home.spec.ts` | Home renders the served groups + one card per served topic; a card click reaches the topic view with numbered (`01`, `02`, …) sections |
| `tests/topic.spec.ts` | spring-boot-fundamentals renders its live `spring:app-info` demo panel (settled report, not a spinner) and its first code listing VERBATIM (exact text match against what `/api/content/...` served) |
| `tests/api.spec.ts` | The terminal-companion contract straight against `:8080`: `/api/health` and `/api/content` documented shapes; `POST /api/demo/reset` returns the report with the registered areas (≥1, messaging among them) |
| `tests/not-found.spec.ts` | Unknown client route → friendly 404; unknown topic id → the server's error envelope rendered (code · status · requestId) and its raw shape on the wire |

Each test stands alone (no ordering, no shared demo state) and asserts on
settled content — loading skeletons and spinners are never treated as
passing states.
