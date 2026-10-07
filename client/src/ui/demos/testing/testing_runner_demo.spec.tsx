import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import TestingRunnerDemo from './testing_runner_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * TestingRunnerDemo spec — the battery runner over stubbed endpoints: the
 * run button fetches the report and renders every check with its layer,
 * verdict and reason plus the tally; the injection toggle PATCHes the
 * shared scenario store and the next report shows the red check; and a
 * degraded server renders the error panel instead of a fake report.
 */

interface Captured {
  url: string;
  method: string;
  body: unknown;
}

function greenReport() {
  return {
    failureInjected: false,
    passed: 3,
    failed: 0,
    skipped: 2,
    totalDurationMs: 4.2,
    checks: [
      {
        name: 'unit: bounds — a hostile page is a validation failure',
        layer: 'unit',
        status: 'PASSED',
        durationMs: 0.3,
        detail: 'page=-1 rejected as a validation failure',
      },
      {
        name: 'data: a paged read serves titled posts within the page bound',
        layer: 'data',
        status: 'PASSED',
        durationMs: 0.8,
        detail: 'page 0 at size 10 served 10 titled posts',
      },
      {
        name: 'web: a self-call answers the scenario endpoint',
        layer: 'web',
        status: 'SKIPPED',
        durationMs: 0.0,
        detail: 'no live web server in this context (a slice)',
      },
    ],
  };
}

function redReport() {
  const report = greenReport();
  report.checks = [
    ...report.checks,
    {
      name: 'injected: a deliberately wrong assertion fails the check',
      layer: 'injected',
      status: 'FAILED',
      durationMs: 0.1,
      detail: 'expected 2 + 2 = 5 — the assertion is wrong on purpose',
    },
  ];
  report.failureInjected = true;
  report.failed = 1;
  return report;
}

function stubEndpoints(captured: Captured[], mode: 'green' | 'red' | 'down') {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      let body: unknown = undefined;
      if (typeof init?.body === 'string') {
        body = JSON.parse(init.body);
      }
      captured.push({ url, method, body });
      const respond = (status: number, payload: unknown) => ({
        ok: status >= 200 && status < 300,
        status,
        json: async () => payload,
      });
      if (mode === 'down') {
        return respond(503, {
          error: { code: 'service_unavailable', message: 'runner unreachable', requestId: 'x' },
        });
      }
      if (method === 'PATCH') {
        return respond(200, { dataMode: 'fake', injectFailure: (body as { injectFailure?: boolean })?.injectFailure });
      }
      return respond(200, mode === 'red' ? redReport() : greenReport());
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('TestingRunnerDemo', () => {
  it('runs the battery and renders every check with verdict and reason', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'green');

    render(<TestingRunnerDemo />);
    fireEvent.click(screen.getByTestId('testing-run'));

    const checks = await screen.findByTestId('testing-checks');
    expect(checks).toHaveTextContent('PASSED');
    expect(checks).toHaveTextContent('[unit]');
    expect(checks).toHaveTextContent('page=-1 rejected as a validation failure');
    expect(checks).toHaveTextContent('SKIPPED');
    expect(checks).toHaveTextContent('no live web server');
    expect(screen.getByTestId('testing-summary')).toHaveTextContent('3 passed · 0 failed · 2 skipped');
    expect(captured.some((call) => call.url.includes('/demo/testing/run'))).toBe(true);
  });

  it('patches the scenario store and shows the injected red check', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'red');

    render(<TestingRunnerDemo />);
    fireEvent.click(screen.getByTestId('testing-inject-toggle'));

    await waitFor(() => {
      const patch = captured.find((call) => call.method === 'PATCH');
      expect(patch?.url).toContain('/demo/scenario');
      expect(patch?.body).toEqual({ injectFailure: true });
    });
    const checks = await screen.findByTestId('testing-checks');
    expect(checks).toHaveTextContent('FAILED');
    expect(checks).toHaveTextContent('expected 2 + 2 = 5');
    expect(screen.getByTestId('testing-summary')).toHaveTextContent('injection ON ·');
    expect(screen.getByTestId('testing-summary')).toHaveTextContent('1 failed');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], 'down');

    render(<TestingRunnerDemo />);
    fireEvent.click(screen.getByTestId('testing-run'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('runner unreachable');
  });
});
