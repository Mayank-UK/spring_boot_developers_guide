import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import TraceDemo from './trace_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * TraceDemo spec — topic 06's aop:trace widget over the shared guarded
 * pipeline: both outcome cards render from real invocation records (method,
 * args, outcome, duration), the raw tail table shows them in order, run
 * again re-fetches with fresh durations, and the error envelope message
 * surfaces on failure.
 */

const TRACE_PATH = '/api/demo/aop/trace';

const payload = (okNanos: number, exceptionNanos: number, requestId: string) => ({
  stepResult: 'processed[demo-seed]',
  failureMessage: 'deliberate failure to demonstrate outcome=exception',
  okRecord: {
    method: 'guide.domain.aop.TracedSampleSteps.succeedingStep',
    argsSummary: '(demo-seed)',
    outcome: 'ok',
    durationNanos: okNanos,
    requestId,
  },
  exceptionRecord: {
    method: 'guide.domain.aop.TracedSampleSteps.failingStep',
    argsSummary: '()',
    outcome: 'exception:IllegalStateException',
    durationNanos: exceptionNanos,
    requestId,
  },
  logTail: [
    {
      method: 'guide.domain.aop.TracedSampleSteps.succeedingStep',
      argsSummary: '(demo-seed)',
      outcome: 'ok',
      durationNanos: okNanos,
      requestId,
    },
    {
      method: 'guide.domain.aop.TracedSampleSteps.failingStep',
      argsSummary: '()',
      outcome: 'exception:IllegalStateException',
      durationNanos: exceptionNanos,
      requestId,
    },
  ],
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('TraceDemo', () => {
  it('renders ok and exception records from a real run', async () => {
    stubApiFetch([{ url: TRACE_PATH, body: payload(250, 120, 'req-7') }]);

    render(<TraceDemo />);

    expect(await screen.findByTestId('aop-trace-report')).toBeInTheDocument();
    expect(screen.getByTestId('aop-trace-ok')).toHaveTextContent('succeedingStep');
    expect(screen.getByTestId('aop-trace-ok')).toHaveTextContent('(demo-seed)');
    expect(screen.getByTestId('aop-trace-ok')).toHaveTextContent('ok');
    expect(screen.getByTestId('aop-trace-ok')).toHaveTextContent('250 ns');
    expect(screen.getByTestId('aop-trace-ok')).toHaveTextContent('req-7');
    expect(screen.getByTestId('aop-trace-exception')).toHaveTextContent('failingStep');
    expect(screen.getByTestId('aop-trace-exception')).toHaveTextContent(
      'exception:IllegalStateException',
    );
    expect(screen.getByTestId('aop-trace-failure-message')).toHaveTextContent(
      'deliberate failure to demonstrate outcome=exception',
    );
    expect(screen.getByTestId('aop-trace-tail')).toHaveTextContent('succeedingStep');
    expect(screen.getByTestId('aop-trace-tail')).toHaveTextContent('failingStep');
  });

  it('run again re-fetches — the durations move because it measures', async () => {
    stubApiFetch([{ url: TRACE_PATH, body: payload(250, 120, 'req-7') }]);
    render(<TraceDemo />);
    expect(await screen.findByTestId('aop-trace-ok')).toHaveTextContent('250 ns');

    stubApiFetch([{ url: TRACE_PATH, body: payload(999, 431, 'req-8') }]);
    fireEvent.click(screen.getByTestId('aop-trace-run-again'));

    await waitFor(() => expect(screen.getByTestId('aop-trace-ok')).toHaveTextContent('999 ns'));
    expect(screen.getByTestId('aop-trace-exception')).toHaveTextContent('431 ns');
  });

  it('shows the error envelope message when the endpoint fails', async () => {
    stubApiFetch([
      {
        url: TRACE_PATH,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-5' },
        },
      },
    ]);

    render(<TraceDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the trace evidence');
    expect(alert).toHaveTextContent('boom');
    expect(screen.queryByTestId('aop-trace-report')).not.toBeInTheDocument();
  });
});
