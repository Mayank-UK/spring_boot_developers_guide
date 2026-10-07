import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import SagaDemo from './saga_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * SagaDemo spec — the saga engine over stubbed endpoints: the world panel
 * paints the shared state (stock, balance, shipments) on mount; the happy
 * path renders all three forward steps completed with no compensations and
 * the world moved; the fail-at-step dial rides the request URL and a
 * compensated saga renders the failed step, the skipped tail and the
 * compensation table in reverse completion order; a degraded server renders
 * the error panel. Every fact asserted comes from the mocked SERVER answer —
 * the component narrates nothing it did not fetch.
 */

interface Captured {
  url: string;
  method: string;
}

function world(overrides?: { stockOnHand?: number; balanceCents?: number; shipments?: string[] }) {
  return {
    stockOnHand: overrides?.stockOnHand ?? 6,
    balanceCents: overrides?.balanceCents ?? 6000,
    shipments: overrides?.shipments ?? [],
  };
}

function happyReport() {
  return {
    sagaId: 'saga-1',
    state: 'completed',
    items: 2,
    amountCents: 1999,
    failAtStep: 0,
    steps: [
      { name: 'reserve-stock', outcome: 'completed', detail: 'stock 6 → 4' },
      { name: 'charge-payment', outcome: 'completed', detail: 'balance 6000¢ → 4001¢' },
      { name: 'ship-order', outcome: 'completed', detail: 'shipment saga-1 dispatched' },
    ],
    compensations: [],
    world: world({ stockOnHand: 4, balanceCents: 4001, shipments: ['saga-1'] }),
    reading: [
      'saga-1: all three steps completed in order — the happy path leaves the world changed with nothing to unwind.',
      'one saga, one world change per step: stock 4, balance 4001¢, shipments 1 — a second saga would see this one\u2019s effects.',
    ],
  };
}

function compensatedReport() {
  return {
    sagaId: 'saga-2',
    state: 'compensated',
    items: 3,
    amountCents: 2500,
    failAtStep: 2,
    steps: [
      { name: 'reserve-stock', outcome: 'completed', detail: 'stock 6 → 3' },
      { name: 'charge-payment', outcome: 'failed', detail: 'injected failure at step 2 — the dial, not the world' },
      { name: 'ship-order', outcome: 'skipped', detail: 'never attempted — forward progress stopped at step 2' },
    ],
    compensations: [
      {
        name: 'release-stock',
        outcome: 'compensated',
        detail: 'stock restored to 6',
      },
    ],
    world: world(),
    reading: [
      'saga-2: step 2 (charge-payment) failed — the orchestrator stops forward progress (the dial made it fail) and unwinds what already succeeded.',
      'compensations run in REVERSE completion order: [release-stock] — last completed, first undone.',
      'world after unwind: stock 6, balance 6000¢, shipments 0 — identical to before the saga started; no partial state survives.',
    ],
  };
}

type Down = { current: boolean };

/**
 * One stub for a whole test: GET recent answers the mocked overview, POST
 * start answers the mocked report (or the 503 envelope when down).
 */
function stubEndpoints(
  captured: Captured[],
  down: Down,
  report: () => unknown,
  overview: () => unknown,
): void {
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
            error: { code: 'service_unavailable', message: 'saga engine unreachable', requestId: 'x' },
          }),
        };
      }
      const body = method === 'POST' ? report() : overview();
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

describe('SagaDemo', () => {
  it('paints the shared world on mount and waits for the operator', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, { current: false }, happyReport, () => ({
      sagas: [],
      world: world(),
    }));

    render(<SagaDemo />);

    expect(await screen.findByTestId('saga-world-now')).toHaveTextContent(
      'stock 6 · balance 6000¢ · shipments 0',
    );
    expect(screen.getByTestId('saga-idle')).toHaveTextContent('no saga run in this view yet');
    expect(captured[0]?.url).toContain('/demo/arch/saga/recent');
    expect(captured[0]?.method).toBe('GET');
  });

  it('runs the happy path: three completed steps, no compensations, world moved', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, { current: false }, happyReport, () => ({
      sagas: [happyReport()],
      world: world({ stockOnHand: 4, balanceCents: 4001, shipments: ['saga-1'] }),
    }));

    render(<SagaDemo />);
    await screen.findByTestId('saga-world-now');

    fireEvent.click(screen.getByTestId('saga-run'));

    const report = await screen.findByTestId('saga-report');
    expect(report).toHaveTextContent('saga-1');
    expect(report).toHaveTextContent('completed');
    const steps = screen.getAllByTestId(/^saga-step-\d$/);
    expect(steps).toHaveLength(3);
    expect(steps[0]).toHaveTextContent('reserve-stock');
    expect(steps[0]).toHaveTextContent('completed');
    expect(steps[2]).toHaveTextContent('ship-order');
    expect(screen.queryByTestId('saga-compensations')).not.toBeInTheDocument();
    expect(screen.getByTestId('saga-world')).toHaveTextContent('stock 4');
    expect(screen.getByTestId('saga-world')).toHaveTextContent('shipments 1');
    expect(screen.getByTestId('saga-reading-0')).toHaveTextContent('happy path');
    // the default dials ride the request URL
    const start = captured.find((call) => call.method === 'POST');
    expect(start?.url).toContain('/demo/arch/saga/start?items=2&amountCents=1999&failAtStep=0');
  });

  it('rides the fail-at-step dial and renders the compensation trail', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, { current: false }, compensatedReport, () => ({
      sagas: [compensatedReport()],
      world: world(),
    }));

    render(<SagaDemo />);
    await screen.findByTestId('saga-world-now');

    fireEvent.click(screen.getByRole('button', { name: 'at step 2 (charge)' }));
    fireEvent.click(screen.getByTestId('saga-run'));

    const report = await screen.findByTestId('saga-report');
    expect(report).toHaveTextContent('compensated');
    expect(screen.getByTestId('saga-step-1')).toHaveTextContent('failed');
    expect(screen.getByTestId('saga-step-2')).toHaveTextContent('skipped');
    const compensations = screen.getByTestId('saga-compensations');
    expect(compensations).toHaveTextContent('release-stock');
    expect(compensations).toHaveTextContent('stock restored to 6');
    expect(screen.getByTestId('saga-reading-1')).toHaveTextContent('REVERSE');
    expect(screen.getByTestId('saga-reading-2')).toHaveTextContent('no partial state survives');
    const start = captured.find((call) => call.method === 'POST');
    expect(start?.url).toContain('failAtStep=2');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], { current: true }, happyReport, () => ({ sagas: [], world: world() }));

    render(<SagaDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('saga engine unreachable');
  });
});
