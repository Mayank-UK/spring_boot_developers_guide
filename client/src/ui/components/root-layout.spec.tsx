import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { ThemeProvider } from '../design-system/theme-provider';
import { appRoutes } from '../routing/router';
import { RoutePaths } from '../routing/routes';
import { container } from '../../core/container';
import { createAppConfig } from '../../core/config/app-config';
import { stubApiFetch } from '../../test/fetch_stub';

/**
 * RootLayout header spec — drives the REAL route tree through a memory router
 * (same harness as router.spec.tsx) to pin the back-arrow contract: hidden
 * exactly on home, shown on any other route (the eager not-found catch-all
 * doubles as the non-home route without needing content stubs), and a click
 * walks the history stack back to the previous entry.
 */

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAt(entries: string[], initialIndex = entries.length - 1) {
  const router = createMemoryRouter(appRoutes(), {
    initialEntries: entries,
    initialIndex,
  });
  render(
    <ThemeProvider>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
  return router;
}

describe('root layout header back button', () => {
  it('is absent on home', async () => {
    stubApiFetch([
      { url: '/api/health', body: { status: 'UP' } },
      { url: '/api/content', body: { version: 1, groups: [], topics: [] } },
    ]);
    renderAt([RoutePaths.Home], 0);

    // Home resolves past the Suspense fallback; the header has no back arrow.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Spring Boot Developers Guide' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Go back' })).not.toBeInTheDocument();
  });

  it('is present on a non-home route (unknown catch-all)', async () => {
    renderAt(['/nowhere/at/all']);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Go back' })).toBeInTheDocument();
  });

  it('navigates back through history on click', async () => {
    stubApiFetch([
      { url: '/api/health', body: { status: 'UP' } },
      { url: '/api/content', body: { version: 1, groups: [], topics: [] } },
    ]);
    // Home first, then an unknown route on top of it.
    renderAt([RoutePaths.Home, '/nowhere/at/all']);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Go back' }));

    // navigate(-1) unwinds to the previous entry: home renders again.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Spring Boot Developers Guide' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Go back' })).not.toBeInTheDocument();
  });
});
