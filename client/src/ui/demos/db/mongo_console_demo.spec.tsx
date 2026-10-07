import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import MongoConsoleDemo from './mongo_console_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * MongoConsoleDemo spec — the analytics console over stubbed endpoints:
 * the seeded stats + recent feed render on mount (the fake answers
 * instantly), recording a visit POSTs the form and refreshes both, the
 * explain button renders the $group pipelines the server would run, a
 * failed live flip shows the honest hint instead of an error, and a hard
 * dependency failure renders the error panel with the envelope's message.
 */

interface Captured {
  url: string;
  method: string;
  body?: string;
}

function statsBody() {
  return {
    area: 'mongo',
    dataMode: 'fake',
    totalVisits: 24,
    topicVisits: 24,
    filteredByTopic: false,
    avgDurationMs: 185583,
    topTopics: [
      { topicId: 'jdbc-sql', visits: 10 },
      { topicId: 'spring-core', visits: 7 },
    ],
    localeHistogram: [
      { locale: 'de-DE', visits: 4 },
      { locale: 'en', visits: 14 },
    ],
  };
}

function recentBody() {
  return {
    area: 'mongo',
    dataMode: 'fake',
    visits: [
      {
        id: 'v24',
        topicId: 'jdbc-sql',
        path: '/topics/jdbc-sql',
        visitorHash: 'a'.repeat(64),
        durationMs: 137000,
        occurredAt: '2026-09-28T10:27:00Z',
        locale: 'en',
      },
    ],
  };
}

function visitBody() {
  return {
    area: 'mongo',
    dataMode: 'fake',
    visit: {
      id: 'visit-new',
      topicId: 'mongodb',
      path: '/topics/mongodb',
      visitorHash: 'b'.repeat(64),
      durationMs: 45000,
      occurredAt: '2026-10-06T12:00:00Z',
      locale: 'en',
    },
  };
}

function explainBody() {
  return {
    area: 'mongo',
    dataMode: 'fake',
    note: 'the aggregation pipelines the live adapter builds',
    pipelines: [
      {
        name: 'topTopics',
        stages: [{ $group: { _id: '$topicId', visits: { $sum: 1 } } }],
      },
    ],
  };
}

function stubEndpoints(captured: Captured[], mode: 'ok' | 'unavailable' | 'hint') {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined });
      if (mode === 'unavailable') {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: {
              code: 'service_unavailable',
              message: 'MongoDB is unreachable (Connection refused)',
              requestId: 'stub',
            },
          }),
        };
      }
      if (url.includes('/mode') && mode === 'hint') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            area: 'mongo',
            dataMode: 'fake',
            requested: 'live',
            database: null,
            hint:
              'live requested but the area stayed fake: the mongo area could not activate: MongoDB is unreachable',
          }),
        };
      }
      if (url.includes('/stats')) {
        return { ok: true, status: 200, json: async () => statsBody() };
      }
      if (url.includes('/visits/recent')) {
        return { ok: true, status: 200, json: async () => recentBody() };
      }
      if (url.includes('/explain')) {
        return { ok: true, status: 200, json: async () => explainBody() };
      }
      if (url.includes('/visits')) {
        return { ok: true, status: 200, json: async () => visitBody() };
      }
      return { ok: true, status: 200, json: async () => ({}) };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MongoConsoleDemo', () => {
  it('renders the seeded stats and recent feed on mount', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');

    render(<MongoConsoleDemo />);

    const stats = await screen.findByTestId('mongo-stats');
    expect(stats).toHaveTextContent('24 visits stored');
    expect(screen.getAllByTestId('mongo-topic-row')[0]).toHaveTextContent('jdbc-sql: 10');
    expect(screen.getAllByTestId('mongo-locale-row')[0]).toHaveTextContent('de-DE: 4');
    expect(screen.getByTestId('mongo-mode')).toHaveTextContent('fake');
    expect(screen.getByTestId('mongo-recent')).toHaveTextContent('jdbc-sql');

    expect(captured[0]?.url).toBe('/api/demo/mongo/stats');
  });

  it('records a visit through the form and refreshes the aggregates', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');

    render(<MongoConsoleDemo />);

    fireEvent.change(screen.getByTestId('mongo-topic-input'), { target: { value: 'mongodb' } });
    fireEvent.change(screen.getByTestId('mongo-duration-input'), { target: { value: '90000' } });
    fireEvent.click(screen.getByTestId('mongo-record'));

    const recorded = await screen.findByTestId('mongo-recorded');
    expect(recorded).toHaveTextContent('recorded mongodb');
    expect(recorded).toHaveTextContent('45s'); // the stub's stored duration

    const post = captured.find((call) => call.method === 'POST' && call.url.includes('/visits'));
    expect(post?.url).toBe('/api/demo/mongo/visits');
    expect(post?.body).toContain('"topicId":"mongodb"');
    expect(post?.body).toContain('"durationMs":90000');
    expect(post?.body).toContain('"visitorToken":"reader-console"');
  });

  it('renders the aggregation pipelines from the explain endpoint', async () => {
    stubEndpoints([], 'ok');

    render(<MongoConsoleDemo />);

    fireEvent.click(await screen.findByTestId('mongo-explain'));

    const view = await screen.findByTestId('mongo-explain-view');
    expect(view).toHaveTextContent('$group');
    expect(view).toHaveTextContent('topTopics');
  });

  it('shows the honest hint when a live flip cannot connect', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'hint');

    render(<MongoConsoleDemo />);
    fireEvent.click(await screen.findByTestId('mongo-flip-live'));

    const hint = await screen.findByTestId('mongo-mode-hint');
    expect(hint).toHaveTextContent('live requested but the area stayed fake');
    expect(hint).toHaveTextContent('MongoDB is unreachable');

    const flip = captured.find((call) => call.url.includes('/mode'));
    expect(flip?.body).toContain('"dataMode":"live"');
  });

  it('renders the degraded panel when the dependency is down', async () => {
    stubEndpoints([], 'unavailable');

    render(<MongoConsoleDemo />);
    fireEvent.click(await screen.findByTestId('mongo-record'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('MongoDB is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
