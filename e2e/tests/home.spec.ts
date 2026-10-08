import { expect, test } from '@playwright/test';
import { SERVER_BASE_URL } from '../playwright.config';

/**
 * The guide's front door: the home view renders whatever the content API
 * serves (groups + topic cards) and a topic card navigates to the generic
 * topic renderer with its numbered sections. Assertions are behavior-level
 * against SETTLED content — the health chip waits for "api: ok", never the
 * "checking" state, and the topic map waits for real cards, never the
 * skeleton.
 */

const FLAGSHIP_TOPIC = 'spring-boot-fundamentals';

test('home renders the curriculum index from the content API', async ({
  page,
  request,
}) => {
  // The contract side: what the server actually serves right now.
  const indexResponse = await request.get(`${SERVER_BASE_URL}/api/content`);
  expect(indexResponse.status()).toBe(200);
  const index = await indexResponse.json();
  expect(index.version).toBe(1);
  expect(index.groups.length).toBeGreaterThan(0);
  expect(index.topics.length).toBeGreaterThan(0);

  // domcontentloaded: SPA route — assertions below wait for settled
  // content, never the full "load" event (lazy chunks land after DCL).
  await page.goto('/', { waitUntil: 'domcontentloaded' });

  // Settled health: the chip reads "api: ok" only after the health probe
  // succeeded — asserting on it also proves the client reaches the server.
  await expect(page.getByTestId('health-chip')).toHaveText(/api: ok/);

  await expect(page.getByRole('heading', { name: 'Topic map' })).toBeVisible();

  // The first curriculum group renders as a section heading, exactly as
  // served (the client owns no content of its own).
  await expect(
    page.getByRole('heading', { name: index.groups[0].title, exact: true }),
  ).toBeVisible();

  // One link per served topic (all shipped topics have sections → links).
  await expect(page.locator('a[data-testid^="topic-card-"]')).toHaveCount(
    index.topics.length,
  );

  // The flagship topic card is present and points at its topic route.
  await expect(page.getByTestId(`topic-card-${FLAGSHIP_TOPIC}`)).toHaveAttribute(
    'href',
    `/topics/${FLAGSHIP_TOPIC}`,
  );
});

test('a topic card navigates to the topic view with numbered sections', async ({
  page,
}) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.getByTestId(`topic-card-${FLAGSHIP_TOPIC}`).click();

  await expect(page).toHaveURL(new RegExp(`/topics/${FLAGSHIP_TOPIC}$`));
  await expect(
    page.getByRole('heading', { level: 1, name: 'Spring Boot Fundamentals' }),
  ).toBeVisible();

  // Sections are numbered 01, 02, … in the generic topic renderer.
  const sectionHeadings = page.locator('article > section h2');
  const count = await sectionHeadings.count();
  expect(count).toBeGreaterThanOrEqual(5);
  await expect(sectionHeadings.nth(0)).toHaveText(/^01/);
  await expect(sectionHeadings.nth(1)).toHaveText(/^02/);
});
