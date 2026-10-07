import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import PerfBenchDemo from './perf_bench_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * PerfBenchDemo spec — the benchmark over stubbed endpoints: the report
 * paints identity bytes/items, per-level gzip rows (bytes before/after,
 * ratio, medians), the connector posture and the server's reading lines;
 * payload/pass selections ride the request URL; and a degraded server
 * renders the error panel. Every number asserted here comes from the
 * mocked SERVER answer — the component narrates nothing it did not fetch.
 */

interface Captured {
  url: string;
  method: string;
}

function report(overrides?: {
  payloadProfile?: string;
  payloadBytes?: number;
  payloadItems?: number;
  enabled?: boolean;
}) {
  const payloadBytes = overrides?.payloadBytes ?? 4385;
  return {
    payloadProfile: overrides?.payloadProfile ?? 'medium',
    payloadBytes,
    payloadItems: overrides?.payloadItems ?? 14,
    levels: [
      {
        level: 1,
        rawBytes: payloadBytes,
        compressedBytes: 831,
        compressedToRawRatio: 0.189,
        medianCompressMicros: 61,
        medianRoundTripMicros: 88,
      },
      {
        level: 6,
        rawBytes: payloadBytes,
        compressedBytes: 807,
        compressedToRawRatio: 0.184,
        medianCompressMicros: 74,
        medianRoundTripMicros: 103,
      },
    ],
    serverCompression: {
      enabled: overrides?.enabled ?? true,
      mimeTypes: ['application/json', 'text/html'],
      minResponseSize: '1KB',
    },
    reading: [
      `identity: ${String(payloadBytes)} bytes leave uncompressed — the floor every level is measured against.`,
      'gzip level 1: 4385 bytes become 831 (18.9% of identity) at a median 61µs per compress.',
      'gzip level 6: 4385 bytes become 807 (18.4% of identity) at a median 74µs per compress.',
      'timing is medians over 48 timed passes after 5 warm-ups; micros move between runs, the bytes never do.',
      overrides?.enabled === false
        ? 'this process\'s connector compression is OFF: every response leaves at identity regardless of level.'
        : "this process's connector compression is ON: application/json over 1KB ride gzip when the client sends Accept-Encoding.",
      "measured in-process with the JDK's java.util.zip — the deflate family the connector calls.",
    ],
  };
}

type Down = { current: boolean };

/** One stub for a whole test: the GET answers the mocked report (or the 503 envelope). */
function stubEndpoint(captured: Captured[], down: Down, body: () => unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url, method: init?.method ?? 'GET' });
      if (down.current) {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: { code: 'service_unavailable', message: 'benchmark unreachable', requestId: 'x' },
          }),
        };
      }
      return { ok: true, status: 200, json: async () => body() };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PerfBenchDemo', () => {
  it('paints the benchmark answer: identity floor, both gzip levels, connector posture, reading', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, () => report());

    render(<PerfBenchDemo />);

    // idle before the first run — the benchmark is opt-in, unlike demos that auto-load
    expect(screen.getByTestId('perf-report')).toHaveTextContent('not run yet');

    fireEvent.click(screen.getByTestId('perf-run'));

    const panel = await screen.findByTestId('perf-report');
    expect(panel).toHaveTextContent('4385 bytes, 14 items (medium)');
    const levels = screen.getAllByTestId('perf-level');
    expect(levels).toHaveLength(2);
    expect(levels[0]).toHaveTextContent('gzip level 1');
    expect(levels[0]).toHaveTextContent('4385 → 831 bytes');
    expect(levels[0]).toHaveTextContent('18.9% of identity');
    expect(levels[0]).toHaveTextContent('median 61');
    expect(levels[1]).toHaveTextContent('gzip level 6');
    expect(levels[1]).toHaveTextContent('4385 → 807 bytes');
    expect(screen.getByTestId('perf-connector')).toHaveTextContent('connector compression: ON');
    expect(screen.getByTestId('perf-connector')).toHaveTextContent('application/json');
    expect(screen.getByTestId('perf-reading-0')).toHaveTextContent(
      'identity: 4385 bytes leave uncompressed',
    );
    // the default request is medium at the default pass count
    expect(captured[0]?.url).toContain('/demo/perf/bench?payload=medium&passes=48');
  });

  it('rides the selected payload profile and pass count on the request URL', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, () =>
      report({ payloadProfile: 'large', payloadBytes: 16652, payloadItems: 54 }),
    );

    render(<PerfBenchDemo />);
    fireEvent.click(screen.getByRole('button', { name: 'large' }));
    fireEvent.click(screen.getByRole('button', { name: '200' }));
    fireEvent.click(screen.getByTestId('perf-run'));

    const panel = await screen.findByTestId('perf-report');
    expect(panel).toHaveTextContent('16652 bytes, 54 items (large)');
    expect(captured[0]?.url).toContain('/demo/perf/bench?payload=large&passes=200');
  });

  it('renders the OFF connector posture when the process answers with compression disabled', async () => {
    stubEndpoint([], { current: false }, () => report({ enabled: false }));

    render(<PerfBenchDemo />);
    fireEvent.click(screen.getByTestId('perf-run'));

    await screen.findByTestId('perf-report');
    expect(screen.getByTestId('perf-connector')).toHaveTextContent('connector compression: OFF');
    expect(screen.getByTestId('perf-reading-4')).toHaveTextContent(
      'connector compression is OFF',
    );
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoint([], { current: true }, () => report());

    render(<PerfBenchDemo />);
    fireEvent.click(screen.getByTestId('perf-run'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('benchmark unreachable');
  });
});
