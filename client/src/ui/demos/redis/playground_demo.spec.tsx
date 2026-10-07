import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import PlaygroundDemo from './playground_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * PlaygroundDemo spec — the redis structures console over the stubbed
 * endpoints: running an op POSTs the chosen shape and renders the
 * namespaced key (the adapter-owned guide:demo: prefix, surfaced on every
 * answer), the op's result, and the executing mode; the mode chip loads
 * from /mode; publishing refreshes the recent pub/sub ring; and a degraded
 * dependency (typed 503 envelope) renders the error panel with the
 * envelope's message and code.
 */

interface Captured {
  url: string;
  method: string;
  body: unknown;
}

type Responder = (url: string, init?: RequestInit) => { ok: boolean; status: number; json: () => Promise<unknown> };

function opAnswer() {
  return {
    ok: true,
    result: [
      { member: 'ada', score: 10 },
      { member: 'grace', score: 9 },
    ],
    namespacedKey: 'guide:demo:league',
    mode: 'fake',
  };
}

function recentBody() {
  return {
    area: 'redis',
    mode: 'live',
    messages: [
      { channel: 'guide:demo:chan:playground', message: 'hello live', receivedAt: '2026-10-06T12:00:00Z' },
    ],
  };
}

function stubEndpoints(captured: Captured[], ok: boolean, opResult?: unknown): Responder {
  const respond: Responder = (url, init) => {
    captured.push({
      url,
      method: init?.method ?? 'GET',
      body: init?.body ? (JSON.parse(String(init.body)) as unknown) : null,
    });
    if (!ok) {
      return {
        ok: false,
        status: 503,
        json: async () => ({
          error: {
            code: 'service_unavailable',
            message: 'Redis is unreachable',
            requestId: 'stub',
          },
        }),
      };
    }
    if (url.endsWith('/mode')) {
      return { ok: true, status: 200, json: async () => ({ area: 'redis', dataMode: 'fake' }) };
    }
    if (url.endsWith('/recent')) {
      return { ok: true, status: 200, json: async () => recentBody() };
    }
    void opResult;
    return { ok: true, status: 200, json: async () => opAnswer() };
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

describe('PlaygroundDemo', () => {
  it('renders the idle prompt and loads the mode chip before any op', async () => {
    stubEndpoints([], true);

    render(<PlaygroundDemo />);

    expect(screen.getByTestId('redis-idle')).toHaveTextContent('no op yet');
    const chip = await screen.findByTestId('redis-mode-chip');
    expect(chip).toHaveTextContent('area mode: fake');
    expect(screen.queryByTestId('redis-result')).toBeNull();
  });

  it('runs the selected op and renders the namespaced key, result, and mode', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<PlaygroundDemo />);
    fireEvent.change(screen.getByTestId('redis-op-select'), { target: { value: 'zadd' } });
    fireEvent.change(screen.getByTestId('redis-key-input'), { target: { value: 'league' } });
    fireEvent.change(screen.getByTestId('redis-value-input'), { target: { value: 'grace' } });
    fireEvent.change(screen.getByTestId('redis-score-input'), { target: { value: '9' } });
    fireEvent.click(screen.getByTestId('redis-run'));

    const result = await screen.findByTestId('redis-result');
    expect(screen.getByTestId('redis-namespaced')).toHaveTextContent('guide:demo:league');
    expect(screen.getByTestId('redis-result-value')).toHaveTextContent('ada');
    expect(screen.getByTestId('redis-result-value')).toHaveTextContent('grace');
    expect(result).toHaveTextContent('mode: fake');

    const posts = captured.filter((call) => call.method === 'POST');
    expect(posts[0]?.url).toBe('/api/demo/redis/op');
    expect(posts[0]?.body).toEqual({ op: 'zadd', key: 'league', value: 'grace', score: 9 });
  });

  it('hides operands their ops do not use and sends ttlSeconds with set', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<PlaygroundDemo />);
    expect(screen.queryByTestId('redis-field-input')).toBeNull();
    expect(screen.queryByTestId('redis-score-input')).toBeNull();
    fireEvent.change(screen.getByTestId('redis-key-input'), { target: { value: 'session:42' } });
    fireEvent.change(screen.getByTestId('redis-value-input'), { target: { value: 'ada' } });
    fireEvent.click(screen.getByTestId('redis-run'));

    await screen.findByTestId('redis-result');
    const posts = captured.filter((call) => call.method === 'POST');
    expect(posts[0]?.body).toEqual({
      op: 'set',
      key: 'session:42',
      value: 'ada',
      ttlSeconds: 60,
    });
  });

  it('refreshes the recent pub/sub ring after publishing', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<PlaygroundDemo />);
    fireEvent.change(screen.getByTestId('redis-op-select'), { target: { value: 'publish' } });
    fireEvent.change(screen.getByTestId('redis-key-input'), {
      target: { value: 'chan:playground' },
    });
    fireEvent.change(screen.getByTestId('redis-value-input'), { target: { value: 'hello live' } });
    fireEvent.click(screen.getByTestId('redis-run'));

    const item = await screen.findByTestId('redis-recent-item');
    expect(item).toHaveTextContent('guide:demo:chan:playground → hello live');
    const bodies = captured.filter((call) => call.method === 'POST').map((call) => call.body);
    expect(bodies[0]).toEqual({ op: 'publish', key: 'chan:playground', value: 'hello live' });
  });

  it('renders the degraded panel when the dependency is down', async () => {
    stubEndpoints([], false);

    render(<PlaygroundDemo />);
    fireEvent.click(screen.getByTestId('redis-run'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('Redis is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
