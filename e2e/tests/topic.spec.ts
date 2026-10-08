import { expect, test } from '@playwright/test';
import { SERVER_BASE_URL } from '../playwright.config';

/**
 * Topic-detail rendering: the flagship spring-boot-fundamentals topic shows
 * its live inline demo (spring:app-info — the running context describing
 * itself) and its code blocks render VERBATIM: the bytes the content API
 * serves are the bytes on the page (collapsible listing, expanded for the
 * check; text content compared exactly, not normalized).
 */

const TOPIC = 'spring-boot-fundamentals';

interface ServedBlock {
  kind: 'text' | 'code' | 'demo';
  id: string;
  code?: string;
}

test('spring-boot-fundamentals renders verbatim code and its live app-info demo', async ({
  page,
  request,
}) => {
  // Contract side: the topic detail as served right now.
  const detailResponse = await request.get(`${SERVER_BASE_URL}/api/content/${TOPIC}`);
  expect(detailResponse.status()).toBe(200);
  const detail = await detailResponse.json();
  expect(detail.id).toBe(TOPIC);

  const codeBlocks = (detail.sections as Array<{ blocks: ServedBlock[] }>)
    .flatMap((section) => section.blocks)
    .filter(
      (block): block is ServedBlock & { code: string } =>
        block.kind === 'code' && typeof block.code === 'string',
    );
  expect(codeBlocks.length).toBeGreaterThan(0);

  await page.goto(`/topics/${TOPIC}`, { waitUntil: 'domcontentloaded' });
  await expect(
    page.getByRole('heading', { level: 1, name: 'Spring Boot Fundamentals' }),
  ).toBeVisible();

  // The inline demo mounts and settles: the report panel replaces the
  // "asking the server…" state with real rendered facts.
  await expect(page.getByTestId('spring-app-info-report')).toBeVisible();
  await expect(page.getByTestId('spring-app-info-java')).not.toBeEmpty();
  await expect(page.getByTestId('spring-app-info-server')).toContainText('8080');
  await expect(page.getByTestId('spring-app-info-packages')).toBeVisible();

  // The first code listing renders verbatim: expand it (collapsed by
  // default), then compare the exact text content with the served code.
  const firstListing = page.locator('article figure').first();
  await firstListing.getByRole('button').first().click();
  const rendered = await page.evaluate(
    () => document.querySelector('figure pre code')?.textContent ?? '',
  );
  expect(rendered).not.toBe('');
  expect(rendered).toBe(codeBlocks[0].code);
});
