import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import LabDemo from './lab_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * LabDemo spec — the caching lab over stubbed endpoints: running stats
 * renders the verdict (MISS then HIT on the repeat), the key namespace,
 * and the report summary; switching mode changes the requested URL;
 * invalidation POSTs and renders the named evicted keys; the HTTP specimen
 * captures the ETag from a 200 and sends it back as If-None-Match on the
 * re-GET, rendering the 304; and a degraded dependency (typed 503
 * envelope) renders the error panel with the envelope's message and code.
 */

interface Captured {
  url: string;
  method: string;
  headers?: Record<string, string>;
  body?: unknown;
}

type Responder = (
  url: string,
  init?: RequestInit,
) => { ok: boolean; status: number; headers: { get: (name: string) => string | null }; json: () => Promise<unknown> };

function statsBody(cache: string) {
  return {
    topicId: 'rest',
    mode: 'explicit',
    result: { postCount: 3, totalWords: 90, avgWords: 30, longestPostTitle: 'Nouns, not verbs' },
    cache,
    computeTimeMs: cache === 'MISS' ? 152 : 0,
    key: 'stats:rest',
    backendMode: 'fake',
  };
}

function specimenResponse(status: number, etag: string) {
  return {
    ok: true,
    status,
    headers: {
      get: (name: string) => {
        const lower = name.toLowerCase();
        if (lower === 'etag') return etag;
        if (lower === 'cache-control') return 'max-age=30';
        if (lower === 'last-modified') return 'Sat, 12 Sep 2026 09:00:00 GMT';
        return null;
      },
    },
    json: async () => ({ title: 'The uniform interface is the contract that scales' }),
  };
}

function stubEndpoints(captured: Captured[], ok: boolean): Responder {
  let statsCalls = 0;
  let specimenCalls = 0;
  const respond: Responder = (url, init) => {
    captured.push({
      url,
      method: init?.method ?? 'GET',
      headers:
        init?.headers && typeof init.headers === 'object' && !Array.isArray(init.headers)
          ? (init.headers as Record<string, string>)
          : undefined,
      body: init?.body ? (JSON.parse(String(init.body)) as unknown) : undefined,
    });
    if (!ok) {
      return {
        ok: false,
        status: 503,
        headers: { get: () => null },
        json: async () => ({
          error: { code: 'service_unavailable', message: 'Redis is unreachable', requestId: 'stub' },
        }),
      };
    }
    if (url.includes('/http-cache/')) {
      specimenCalls += 1;
      // first specimen GET: 200 + validators; any later one: the 304 round trip
      return specimenResponse(specimenCalls === 1 ? 200 : 304, 'W/"v1"');
    }
    if (url.includes('/invalidate/')) {
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => ({ topicId: 'rest', evictedKeys: ['stats:rest', 'topicStats:rest'] }),
      };
    }
    statsCalls += 1;
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => statsBody(statsCalls === 1 ? 'MISS' : 'HIT'),
    };
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      return respond(url, init);
    }),
  );
  return respond;
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LabDemo', () => {
  it('renders the idle prompt before any read', () => {
    stubEndpoints([], true);
    render(<LabDemo />);
    expect(screen.getByTestId('caching-idle')).toHaveTextContent('no read yet');
    expect(screen.queryByTestId('caching-stats-result')).toBeNull();
  });

  it('shows MISS then HIT on repeated reads, with the key and the report', async () => {
    stubEndpoints([], true);
    render(<LabDemo />);

    fireEvent.click(screen.getByTestId('caching-run-stats'));
    const first = await screen.findByTestId('caching-stats-result');
    expect(screen.getByTestId('caching-verdict')).toHaveTextContent('MISS');
    expect(screen.getByTestId('caching-key')).toHaveTextContent('key: stats:rest');
    expect(first).toHaveTextContent('mode: explicit');
    expect(screen.getByTestId('caching-report')).toHaveTextContent('3 posts');

    fireEvent.click(screen.getByTestId('caching-run-stats'));
    await screen.findByTestId('caching-verdict');
    expect(screen.getByTestId('caching-verdict')).toHaveTextContent('HIT');
    expect(screen.getByTestId('caching-time')).toHaveTextContent('computeTimeMs: 0');
  });

  it('switching mode asks the annotation path for the same topic', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);
    render(<LabDemo />);

    fireEvent.change(screen.getByTestId('caching-mode-select'), { target: { value: 'annotation' } });
    fireEvent.click(screen.getByTestId('caching-run-stats'));
    await screen.findByTestId('caching-stats-result');

    const statsCall = captured.find((call) => call.url.includes('/stats/'));
    expect(statsCall?.url).toBe('/api/demo/caching/stats/rest?mode=annotation');
  });

  it('invalidate POSTs and renders the named evicted keys', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);
    render(<LabDemo />);

    fireEvent.click(screen.getByTestId('caching-invalidate'));
    const keys = await screen.findByTestId('caching-invalidated-keys');
    expect(keys).toHaveTextContent('stats:rest');
    expect(keys).toHaveTextContent('topicStats:rest');
    const invalidateCall = captured.find((call) => call.method === 'POST');
    expect(invalidateCall?.url).toBe('/api/demo/caching/invalidate/rest');
  });

  it('captures the etag from the 200 and sends it back as If-None-Match for the 304', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);
    render(<LabDemo />);

    fireEvent.click(screen.getByTestId('caching-get'));
    const first = await screen.findByTestId('caching-specimen-result');
    expect(first).toHaveTextContent('status: 200');
    expect(screen.getByTestId('caching-specimen-etag')).toHaveTextContent('W/"v1"');
    expect(screen.getByTestId('caching-specimen-cache-control')).toHaveTextContent('max-age=30');

    fireEvent.click(screen.getByTestId('caching-revalidate'));
    const second = await screen.findByTestId('caching-specimen-result');
    expect(second).toHaveTextContent('status: 304');
    expect(second).toHaveTextContent('nothing shipped');
    const specimenCalls = captured.filter((call) => call.url.includes('/http-cache/'));
    expect(specimenCalls).toHaveLength(2);
    expect(specimenCalls[1]?.headers).toEqual({ 'if-none-match': 'W/"v1"' });
  });

  it('renders the degraded panel when the dependency is down', async () => {
    stubEndpoints([], false);
    render(<LabDemo />);

    fireEvent.click(screen.getByTestId('caching-run-stats'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('Redis is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
