import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import SelfInvocationDemo from './self_invocation_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * SelfInvocationDemo spec — topic 06's aop:self-invocation widget over the
 * shared guarded pipeline: the trap renders honestly (outer recorded, inner
 * not, the order summary proving the inner step ran, and the log table
 * holding only the outer record), run again re-fetches, and the error
 * envelope message surfaces on failure.
 */

const TRAP_PATH = '/api/demo/aop/self-invocation';

const payload = (outerRecorded: boolean, innerRecorded: boolean) => ({
  orderSummary: 'order[book] ← reserved:book',
  outerMethod: 'placeOrder',
  innerMethod: 'reserveStock',
  outerRecorded,
  innerRecorded,
  exhibitLog: outerRecorded
    ? [
        {
          method: 'guide.domain.teaching.TracedOrderWorkflow.placeOrder',
          argsSummary: '(book)',
          outcome: 'ok',
          durationNanos: 340,
          requestId: 'req-9',
        },
      ]
    : [],
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SelfInvocationDemo', () => {
  it('renders the trap: outer recorded, inner absent, work proven', async () => {
    stubApiFetch([{ url: TRAP_PATH, body: payload(true, false) }]);

    render(<SelfInvocationDemo />);

    expect(await screen.findByTestId('aop-trap-report')).toBeInTheDocument();
    expect(screen.getByTestId('aop-trap-order-summary')).toHaveTextContent(
      'order[book] ← reserved:book',
    );
    expect(screen.getByTestId('aop-trap-outer-verdict')).toHaveTextContent('placeOrder');
    expect(screen.getByTestId('aop-trap-outer-value')).toHaveTextContent(
      'recorded — the call passed through the proxy',
    );
    expect(screen.getByTestId('aop-trap-inner-verdict')).toHaveTextContent('reserveStock');
    expect(screen.getByTestId('aop-trap-inner-value')).toHaveTextContent(
      'not recorded — the internal call bypassed the proxy',
    );
    const log = screen.getByTestId('aop-trap-log');
    expect(log).toHaveTextContent('placeOrder');
    expect(log).not.toHaveTextContent('reserveStock');
  });

  it('run again re-fetches the live verdict', async () => {
    stubApiFetch([{ url: TRAP_PATH, body: payload(true, false) }]);
    render(<SelfInvocationDemo />);
    expect(await screen.findByTestId('aop-trap-log')).toHaveTextContent('placeOrder');

    stubApiFetch([{ url: TRAP_PATH, body: payload(false, false) }]);
    fireEvent.click(screen.getByTestId('aop-trap-run-again'));

    await waitFor(() =>
      expect(screen.getByTestId('aop-trap-log')).toHaveTextContent('(empty — was the proxy active?)'),
    );
  });

  it('shows the error envelope message when the endpoint fails', async () => {
    stubApiFetch([
      {
        url: TRAP_PATH,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-6' },
        },
      },
    ]);

    render(<SelfInvocationDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the self-invocation report');
    expect(alert).toHaveTextContent('boom');
    expect(screen.queryByTestId('aop-trap-report')).not.toBeInTheDocument();
  });
});
