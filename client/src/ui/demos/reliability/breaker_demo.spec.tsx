import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import BreakerDemo from './breaker_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * BreakerDemo spec — the reliability:breaker control room over stubbed
 * endpoints. The status panel paints the server's own numbers (state,
 * window metrics, config knobs, reading lines); a stubbed call paints the
 * attempt timeline from the report; the dial buttons PATCH the mode onto
 * the request URL; and a degraded server renders the error panel. Every
 * string asserted here comes from the mocked SERVER answer — the panel
 * narrates nothing it did not fetch.
 */

interface Captured {
  url: string;
  method: string;
}

function status(overrides?: {
  state?: string;
  mode?: string;
  successes?: number;
  failures?: number;
  notPermitted?: number;
  failureRate?: number | null;
  openedAtMs?: number | null;
  virtualNowMs?: number;
}) {
  return {
    state: overrides?.state ?? 'CLOSED',
    downstreamMode: overrides?.mode ?? 'healthy',
    injectFailureRail: false,
    virtualNowMs: overrides?.virtualNowMs ?? 0,
    config: {
      failureRateThreshold: 50.0,
      minimumNumberOfCalls: 4,
      waitDurationMs: 10000,
      slidingWindowSize: 10,
      permittedInHalfOpen: 1,
    },
    metrics: {
      successes: overrides?.successes ?? 0,
      failures: overrides?.failures ?? 0,
      notPermitted: overrides?.notPermitted ?? 0,
      failureRate: overrides?.failureRate === undefined ? null : overrides.failureRate,
    },
    openedAtMs: overrides?.openedAtMs ?? null,
    probeEligibleAtMs: overrides?.openedAtMs == null ? null : overrides.openedAtMs + 10000,
    recentCalls: [],
    reading: [
      overrides?.state === 'OPEN'
        ? 'OPEN since t=12360ms (virtual) — calls are rejected at the door.'
        : 'CLOSED — calls pass through; the window holds 0 success(es) and 4 failure(s).',
      'trip condition: failure rate >= 50.0% over the last 10 calls.',
    ],
  };
}

function callReport() {
  return {
    outcome: 'REJECTED_BY_BREAKER',
    breakerStateBefore: 'OPEN',
    breakerStateAfter: 'OPEN',
    attempts: [],
    totalElapsedMs: 0,
    downstreamAttempts: 0,
  };
}

type Down = { current: boolean };

/** One stub for a whole test: routes by URL to the mocked theater answers. */
function stubTheater(captured: Captured[], down: Down, answers: () => {
  status: () => unknown;
  call?: () => unknown;
  dial?: () => unknown;
  probe?: () => unknown;
  reset?: () => unknown;
}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      captured.push({ url, method });
      if (down.current) {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: { code: 'service_unavailable', message: 'breaker theater unreachable', requestId: 'x' },
          }),
        };
      }
      const body = url.includes('/demo/reliability/breaker')
        ? answers().status()
        : url.includes('/demo/reliability/call')
          ? (answers().call?.() ?? callReport())
          : url.includes('/demo/reliability/dial')
            ? (answers().dial?.() ?? 'down')
            : url.includes('/demo/reliability/probe')
              ? (answers().probe?.() ?? 'HALF_OPEN')
              : url.includes('/demo/reliability/reset')
                ? (answers().reset?.() ?? status())
                : { error: { code: 'not_found', message: 'unknown route', requestId: 'x' } };
      return { ok: true, status: 200, json: async () => body };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('BreakerDemo', () => {
  it('paints the CLOSED breaker status on mount: state, window, knobs, reading', async () => {
    const captured: Captured[] = [];
    stubTheater(captured, { current: false }, () => ({
      status: () => status({ failures: 4, virtualNowMs: 12360 }),
    }));

    render(<BreakerDemo />);

    const panel = await screen.findByTestId('r39-status');
    expect(screen.getByTestId('r39-state')).toHaveTextContent('CLOSED');
    expect(panel).toHaveTextContent('t=12360ms');
    expect(panel).toHaveTextContent('downstream healthy');
    expect(screen.getByTestId('r39-metrics')).toHaveTextContent('0 ok / 4 failed / 0 rejected');
    expect(screen.getByTestId('r39-metrics')).toHaveTextContent('not judged yet');
    expect(panel).toHaveTextContent('trips at 50% over the last 10 calls (min 4 judged)');
    expect(screen.getByTestId('r39-reading-0')).toHaveTextContent('CLOSED — calls pass through');
    expect(captured[0]?.url).toContain('/demo/reliability/breaker');
    expect(captured[0]?.method).toBe('GET');
  });

  it('paints the OPEN posture with the wait window and the rejected call report', async () => {
    const captured: Captured[] = [];
    let callCount = 0;
    stubTheater(captured, { current: false }, () => ({
      status: () => (callCount === 0
        ? status({ state: 'OPEN', failures: 4, failureRate: 100.0, openedAtMs: 12360, virtualNowMs: 12360 })
        : status({ state: 'OPEN', failures: 4, notPermitted: 1, failureRate: 100.0, openedAtMs: 12360, virtualNowMs: 12360 })),
      call: () => {
        callCount = 1;
        return callReport();
      },
    }));

    render(<BreakerDemo />);

    await screen.findByTestId('r39-state');
    expect(screen.getByTestId('r39-state')).toHaveTextContent('OPEN');
    expect(screen.getByTestId('r39-open')).toHaveTextContent('opened at t=12360ms');
    expect(screen.getByTestId('r39-open')).toHaveTextContent('probe eligible at t=22360ms');
    expect(screen.getByTestId('r39-reading-0')).toHaveTextContent('rejected at the door');

    fireEvent.click(screen.getByTestId('r39-call'));

    const report = await screen.findByTestId('r39-callreport');
    expect(report).toHaveTextContent('REJECTED_BY_BREAKER · OPEN → OPEN');
    expect(report).toHaveTextContent('0 downstream attempt(s)');
    await waitFor(() => {
      expect(screen.getByTestId('r39-metrics')).toHaveTextContent('0 ok / 4 failed / 1 rejected');
      expect(screen.getByTestId('r39-metrics')).toHaveTextContent('100.0%');
    });
    expect(captured.some((c) => c.url.includes('/demo/reliability/call') && c.method === 'POST')).toBe(true);
  });

  it('PATCHes the dial mode onto the request URL and refreshes from the answer', async () => {
    const captured: Captured[] = [];
    stubTheater(captured, { current: false }, () => ({
      status: () => status({ mode: 'down' }),
      dial: () => 'down',
    }));

    render(<BreakerDemo />);
    await screen.findByTestId('r39-state');

    fireEvent.click(screen.getByRole('button', { name: 'down' }));

    await waitFor(() => {
      expect(screen.getByTestId('r39-summary')).toHaveTextContent('downstream down');
    });
    const dial = captured.find((c) => c.url.includes('/demo/reliability/dial'));
    expect(dial?.url).toContain('mode=down');
    expect(dial?.method).toBe('PATCH');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubTheater([], { current: true }, () => ({ status: () => status() }));

    render(<BreakerDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('breaker theater unreachable');
  });
});
