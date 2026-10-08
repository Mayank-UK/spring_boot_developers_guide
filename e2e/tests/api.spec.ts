import { expect, test } from '@playwright/test';
import { SERVER_BASE_URL } from '../playwright.config';

/**
 * The terminal-companion CONTRACT, checked the way the curl tours use it:
 * straight HTTP requests from the test to the server on :8080 (no browser).
 * Every topic's terminal companion tells the reader to curl these exact
 * endpoints — if a shape below changes, dozens of published curl tours lie.
 */

test('terminal-companion contract: /api/health and /api/content shapes', async ({
  request,
}) => {
  const health = await request.get(`${SERVER_BASE_URL}/api/health`);
  expect(health.status()).toBe(200);
  expect(await health.json()).toEqual({
    status: 'ok',
    service: 'springboot-developers-guide',
  });

  const content = await request.get(`${SERVER_BASE_URL}/api/content`);
  expect(content.status()).toBe(200);
  const index = await content.json();

  // The documented index shape: versioned, grouped, one row per topic.
  expect(index.version).toBe(1);
  for (const group of index.groups as Array<{ id: string; title: string; order: number }>) {
    expect(typeof group.id).toBe('string');
    expect(typeof group.title).toBe('string');
    expect(typeof group.order).toBe('number');
  }
  const topics = index.topics as Array<{
    id: string;
    title: string;
    group: string;
    sectionCount: number;
  }>;
  expect(topics.length).toBeGreaterThan(0);
  for (const topic of topics) {
    expect(typeof topic.id).toBe('string');
    expect(typeof topic.title).toBe('string');
    expect(typeof topic.group).toBe('string');
    expect(typeof topic.sectionCount).toBe('number');
  }
  expect(topics.map((topic) => topic.id)).toContain('spring-boot-fundamentals');
});

test('shared demo reset returns a report for every registered area', async ({
  request,
}) => {
  const response = await request.post(`${SERVER_BASE_URL}/api/demo/reset`);
  expect(response.status()).toBe(200);

  // The report shape: {reset: [{area, reset, note?}]} — one row per
  // registered area, sorted, ≥1 area, and messaging (the curriculum's most
  // stateful area) always among them. A row may legitimately carry
  // reset=false with the reason in its note (live store unreachable); the
  // sweep reports, it never aborts.
  const report = await response.json();
  expect(Array.isArray(report.reset)).toBe(true);
  expect(report.reset.length).toBeGreaterThanOrEqual(1);

  const rows = report.reset as Array<{ area: unknown; reset: unknown; note?: unknown }>;
  expect(rows.map((row) => row.area)).toContain('messaging');
  for (const row of rows) {
    expect(typeof row.area).toBe('string');
    expect(typeof row.reset).toBe('boolean');
    if (row.reset === false) {
      expect(typeof row.note).toBe('string');
    }
  }
});
