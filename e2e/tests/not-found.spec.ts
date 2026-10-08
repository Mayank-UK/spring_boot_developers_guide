import { expect, test } from '@playwright/test';
import { SERVER_BASE_URL } from '../playwright.config';

/**
 * Unknown routes: both faces of "not found" render the app's documented
 * error envelope — an unknown CLIENT route gets the friendly catch-all, an
 * unknown TOPIC id flows through the server's 404 envelope into the topic
 * view's error panel (code · status · requestId), and the raw API keeps the
 * one envelope shape every failure leaves the app in.
 */

test('unknown routes render the app error envelope', async ({ page, request }) => {
  // Raw API first: the documented envelope shape on the wire.
  const apiResponse = await request.get(`${SERVER_BASE_URL}/api/content/no-such-topic`);
  expect(apiResponse.status()).toBe(404);
  const body = await apiResponse.json();
  expect(body.error.code).toBe('not_found');
  expect(typeof body.error.message).toBe('string');
  expect(typeof body.error.requestId).toBe('string');

  // Unknown client route → the friendly catch-all view.
  await page.goto('/definitely-not-a-page', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  await expect(page.getByRole('link', { name: /back to the guide/ })).toHaveAttribute(
    'href',
    '/',
  );

  // Unknown topic id → the server's 404 envelope, rendered by the topic
  // view's error panel with its traceable requestId.
  await page.goto('/topics/definitely-not-a-topic', {
    waitUntil: 'domcontentloaded',
  });
  const panel = page.getByTestId('error-panel');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('not_found');
  await expect(panel).toContainText('status 404');
  await expect(panel).toContainText(/request [a-zA-Z0-9-]+/);
});
