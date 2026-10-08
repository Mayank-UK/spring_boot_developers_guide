import { defineConfig, devices } from '@playwright/test';

/**
 * Host-run Playwright suite. There is deliberately NO webServer block: the
 * dev stack (Spring Boot server on :8080, Vite client on :5176, plus PG /
 * Redis / Mongo) is managed externally via
 * `docker compose -f .docker/docker-compose.yml up -d` — see e2e/README.md.
 * Run the tests only once /api/health answers 200 and the client serves.
 */

/**
 * The published dev-stack ports — same defaults the compose file maps.
 * Override with E2E_CLIENT_URL / E2E_SERVER_URL when the host ports differ
 * (e.g. another stack already holds :8080 and compose is up with a port
 * override file). CI uses the defaults.
 */
declare const process: { env: Record<string, string | undefined> };

export const CLIENT_BASE_URL = process.env.E2E_CLIENT_URL ?? 'http://localhost:5176';
export const SERVER_BASE_URL = process.env.E2E_SERVER_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './tests',
  // Vite serves the topic view chunks lazily and the first cold load can be
  // slow on a busy machine — generous per-test budget, no hardcoded waits.
  timeout: 60_000,
  // The client is a Vite DEV server: first render of a route can lag while
  // modules transform on demand, so content assertions get a wide budget.
  expect: { timeout: 20_000 },
  fullyParallel: true,
  retries: 1,
  // The suite targets ONE dockerized dev stack — a small worker pool keeps
  // parallel first-loads from piling onto the Vite dev server (and the
  // host's other containers) on a busy machine.
  workers: 2,
  reporter: [['list']],
  outputDir: 'test-results',
  use: {
    baseURL: CLIENT_BASE_URL,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
