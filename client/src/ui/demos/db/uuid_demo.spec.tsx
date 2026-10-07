import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import UuidDemo from './uuid_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * UuidDemo spec — the key-strategy console over the stubbed experiment
 * endpoint: running the experiment POSTs the chosen row count and renders
 * both measurement cards (v4 scattered, v7 time-ordered), the index-size
 * delta, and the server's measured verdict; a degraded dependency renders
 * the error panel with the envelope's message.
 */

interface Captured {
  url: string;
}

function report() {
  return {
    rows: 5000,
    uuidV4: {
      strategy: 'uuid v4 (random)',
      primaryKeyIndexBytes: 327680,
      primaryKeyIndexSize: '320 KiB',
      insertDurationMs: 214,
      idsInKeyOrder: [
        '0181c61a-9f2e-7c1a-9d0d-91b4db5b0f31',
        '1048ea92-6b41-4b5f-8a0f-3f5b9a6c9d1e',
        '27d3f4a1-1b0e-43d5-9d1f-31a0e78fbb6a',
      ],
      comparedPairs: 4999,
      outOfOrderPairs: 2487,
      keyOrderTracksInsertion: false,
    },
    uuidV7: {
      strategy: 'uuid v7 (time-ordered)',
      primaryKeyIndexBytes: 262144,
      primaryKeyIndexSize: '256 KiB',
      insertDurationMs: 163,
      idsInKeyOrder: [
        '0192b3f4-8f00-7000-8000-000000000001',
        '0192b3f4-8f00-7000-8000-000000000002',
        '0192b3f4-8f00-7000-8000-000000000003',
      ],
      comparedPairs: 4999,
      outOfOrderPairs: 0,
      keyOrderTracksInsertion: true,
    },
    indexByteDelta: 65536,
    verdict:
      'At 5000 rows the random v4 index is 64 KiB larger than the time-ordered v7 index: random keys split pages half-full, sequential keys append.',
  };
}

function stubCompare(captured: Captured[], ok: boolean) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url });
      if (!ok) {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: {
              code: 'service_unavailable',
              message: 'PostgreSQL is unreachable',
              requestId: 'stub',
            },
          }),
        };
      }
      void init;
      return { ok: true, status: 200, json: async () => report() };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('UuidDemo', () => {
  it('renders the idle prompt before any run', () => {
    stubCompare([], true);

    render(<UuidDemo />);

    expect(screen.getByTestId('uuid-idle')).toHaveTextContent('no run yet');
    expect(screen.queryByTestId('uuid-report')).toBeNull();
  });

  it('runs the experiment and renders both strategies with the verdict', async () => {
    const captured: Captured[] = [];
    stubCompare(captured, true);

    render(<UuidDemo />);
    fireEvent.click(screen.getByTestId('uuid-run'));

    const reportPanel = await screen.findByTestId('uuid-report');
    expect(reportPanel).toHaveTextContent('5000 rows per side');
    expect(screen.getByTestId('uuid-delta')).toHaveTextContent('65536 bytes');

    expect(screen.getByTestId('uuid-v4-card-locality')).toHaveTextContent(
      'no — 2487/4999 pairs out of order',
    );
    expect(screen.getByTestId('uuid-v7-card-locality')).toHaveTextContent(
      'yes — 0/4999 pairs out of order',
    );
    expect(screen.getByTestId('uuid-v4-card-index')).toHaveTextContent('320 KiB');
    expect(screen.getByTestId('uuid-v7-card-index')).toHaveTextContent('256 KiB');
    expect(screen.getByTestId('uuid-verdict')).toHaveTextContent('random keys split pages half-full');

    expect(captured[0]?.url).toBe('/api/demo/db/uuid/compare?rows=5000');
  });

  it('sends the edited row count on the wire', async () => {
    const captured: Captured[] = [];
    stubCompare(captured, true);

    render(<UuidDemo />);
    fireEvent.change(screen.getByTestId('uuid-rows-input'), { target: { value: '1200' } });
    fireEvent.click(screen.getByTestId('uuid-run'));

    await waitFor(() => {
      expect(captured[0]?.url).toBe('/api/demo/db/uuid/compare?rows=1200');
    });
  });

  it('renders the degraded panel when the dependency is down', async () => {
    stubCompare([], false);

    render(<UuidDemo />);
    fireEvent.click(screen.getByTestId('uuid-run'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('PostgreSQL is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
