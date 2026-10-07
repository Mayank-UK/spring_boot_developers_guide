import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { HomeView } from './home_view';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch, stubNetworkFailure } from '../../../test/fetch_stub';

/**
 * HomeView spec — behavior, not implementation: loading → index render,
 * empty-registry degradation, error degradation, and the live health chip.
 * The view resolves use cases through the (real) container; fetch is stubbed.
 */

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const healthOk = { status: 'UP' };

/** Groups deliberately out of wire order to prove the client sorts by `order`. */
const contentIndex = {
  version: 1,
  groups: [
    { id: 'apis', title: 'APIs', order: 2 },
    { id: 'fundamentals', title: 'Fundamentals', order: 1 },
  ],
  topics: [
    { id: 'api-design', title: 'REST API Design', group: 'apis', order: 1, sectionCount: 0 },
    {
      id: 'spring-boot-fundamentals',
      title: 'Spring Boot Fundamentals',
      subtitle: 'From start.spring.io to a running app',
      group: 'fundamentals',
      order: 4,
      sectionCount: 8,
    },
    { id: 'spring-core', title: 'Spring Core', group: 'fundamentals', order: 5, sectionCount: 10 },
  ],
};

const emptyIndex = { version: 1, groups: [], topics: [] };

function renderHome() {
  return render(
    <MemoryRouter>
      <HomeView />
    </MemoryRouter>,
  );
}

describe('HomeView', () => {
  it('renders loading first, then the topic map: groups in order, topic links with subtitle + section count, live health chip', async () => {
    stubApiFetch([
      { url: '/api/health', body: healthOk },
      { url: '/api/content', body: contentIndex },
    ]);

    renderHome();

    // Loading state comes first.
    expect(screen.getByTestId('topic-map-loading')).toBeInTheDocument();
    expect(screen.getByTestId('health-chip')).toHaveTextContent('api: checking…');

    // The health chip flips to the served status.
    await waitFor(() =>
      expect(screen.getByTestId('health-chip')).toHaveTextContent('api: UP'),
    );

    // Topic cards arrive: populated ones are links carrying subtitle + count.
    const populated = await screen.findByTestId('topic-card-spring-boot-fundamentals');
    expect(populated).toHaveAttribute('href', '/topics/spring-boot-fundamentals');
    expect(populated).toHaveTextContent('Spring Boot Fundamentals');
    expect(populated).toHaveTextContent('From start.spring.io to a running app');
    expect(populated).toHaveTextContent('8 sections');

    // sectionCount === 0 topics degrade to disabled "soon" cards (no link).
    const soon = screen.getByTestId('topic-card-api-design');
    expect(soon.querySelector('a')).toBeNull();
    expect(soon).toHaveAttribute('aria-disabled', 'true');
    expect(soon).toHaveTextContent('soon');

    // Group display order follows `order`, not wire order.
    const fundamentals = screen.getByText('Fundamentals');
    const apis = screen.getByText('APIs');
    expect(
      fundamentals.compareDocumentPosition(apis) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('renders the friendly landing-soon state for an EMPTY registry (zero topics)', async () => {
    stubApiFetch([
      { url: '/api/health', body: healthOk },
      { url: '/api/content', body: emptyIndex },
    ]);

    renderHome();

    const landing = await screen.findByTestId('curriculum-landing-soon');
    expect(landing).toHaveTextContent('The curriculum is landing soon');
    // No crash, no topic cards, no error panel.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByTestId('topic-map-loading')).not.toBeInTheDocument();
  });

  it('shows the error panel with envelope diagnostics when the content index call fails', async () => {
    stubApiFetch([
      { url: '/api/health', body: healthOk },
      {
        url: '/api/content',
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-1' },
        },
      },
    ]);

    renderHome();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the topic map');
    expect(alert).toHaveTextContent('boom');
    expect(alert).toHaveTextContent('internal_error · status 500 · request req-1');
    // The health chip is independent and still fine.
    await waitFor(() =>
      expect(screen.getByTestId('health-chip')).toHaveTextContent('api: UP'),
    );
  });

  it('degrades the health chip inline when the server is unreachable (status 0)', async () => {
    stubNetworkFailure();

    renderHome();

    await waitFor(() =>
      expect(screen.getByTestId('health-chip')).toHaveTextContent('api: down'),
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not reach the server.');
    expect(alert).toHaveTextContent('network_error · status 0');
  });
});
