import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ReactiveCompareDemo from './compare_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * ReactiveCompareDemo spec — both halves over stubbed endpoints: the
 * comparison panel paints BOTH model runs (items, thread counts, sampled
 * thread names, wall time) plus the shared checksum and the server's
 * reading lines; the backpressure panel paints the exact drop and buffer
 * counts (delivered/dropped vs delivered/still-buffered) as the server
 * reported them; item/concurrency selections ride the request URL; and a
 * degraded server renders the error panel. Every number asserted here
 * comes from the mocked SERVER answer — the component narrates nothing it
 * did not fetch.
 */

interface Captured {
  url: string;
  method: string;
}

function compareReport() {
  return {
    items: 48,
    concurrency: 8,
    workChecksum: -6415601794888525841,
    blockingPerItem: {
      model: 'blocking-virtual-threads',
      shape: 'one virtual thread per item',
      itemsProcessed: 48,
      distinctThreads: 48,
      sampleThreads: ['mvc-item-1', 'mvc-item-2', 'mvc-item-3'],
      wallNanos: 9_100_000,
      reading: ['blocking run'],
    },
    reactorPipeline: {
      model: 'reactor-pipeline',
      shape: 'boundedElastic pool, flatMap concurrency 8',
      itemsProcessed: 48,
      distinctThreads: 6,
      sampleThreads: ['boundedElastic-1', 'boundedElastic-2'],
      wallNanos: 14_800_000,
      reading: ['reactor run'],
    },
    reading: [
      'both models processed 48 item(s) and produced checksum -6415601794888525841 — identical work, identical answer, only the SHAPE differs.',
      'wall times are measured, not asserted — on pure blocking I/O the virtual-thread model is routinely FASTER at demo scale.',
    ],
  };
}

function backpressureReport(strategy: 'drop' | 'buffer') {
  return strategy === 'drop'
    ? {
        strategy: 'drop',
        emitted: 24,
        initialDemand: 4,
        lateDemand: 4,
        deliveredAfterInitial: 4,
        deliveredTotal: 4,
        dropped: 20,
        stillBuffered: 0,
        reading: [
          'hot source emitted 24; the subscriber demanded 4 up front — delivered 4, DROPPED 20.',
          'a late request(4) delivered 0 more: dropped values never return — drop is lossy BY DESIGN.',
        ],
      }
    : {
        strategy: 'buffer',
        emitted: 24,
        initialDemand: 4,
        lateDemand: 4,
        deliveredAfterInitial: 4,
        deliveredTotal: 8,
        dropped: 0,
        stillBuffered: 16,
        reading: [
          'hot source emitted 24; the subscriber demanded 4 up front — delivered 4, BUFFERED 20 in the sink.',
          'a late request(4) drained 4 buffered value(s): nothing lost, demand gates the PACE.',
        ],
      };
}

type Down = { current: boolean };

/** One stub for a whole test: the GET answers the mocked report matching its URL (or the 503 envelope). */
function stubEndpoint(captured: Captured[], down: Down): void {
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
            error: { code: 'service_unavailable', message: 'reactive demo unreachable', requestId: 'x' },
          }),
        };
      }
      if (url.includes('/demo/reactive/backpressure')) {
        return { ok: true, status: 200, json: async () => backpressureReport(url.includes('buffer') ? 'buffer' : 'drop') };
      }
      return { ok: true, status: 200, json: async () => compareReport() };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ReactiveCompareDemo', () => {
  it('paints both model runs: thread shapes, wall time, shared checksum, reading lines', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false });

    render(<ReactiveCompareDemo />);

    // idle before the first run — the comparison is opt-in
    expect(screen.queryByTestId('reactive-compare-report')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('reactive-run-compare'));

    const panel = await screen.findByTestId('reactive-compare-report');
    expect(panel).toHaveTextContent('same workload, 48 items');
    expect(panel).toHaveTextContent('checksum -6415601794888525841');
    const models = screen.getAllByTestId('reactive-model');
    expect(models).toHaveLength(2);
    expect(models[0]).toHaveTextContent('blocking-virtual-threads');
    expect(models[0]).toHaveTextContent('48 items on 48 thread(s)');
    expect(models[0]).toHaveTextContent('mvc-item-1');
    expect(models[1]).toHaveTextContent('reactor-pipeline');
    expect(models[1]).toHaveTextContent('48 items on 6 thread(s)');
    expect(models[1]).toHaveTextContent('boundedElastic-1');
    expect(models[1]).toHaveTextContent('flatMap concurrency 8');
    expect(screen.getByTestId('reactive-compare-reading-1')).toHaveTextContent(
      'virtual-thread model is routinely FASTER',
    );
    // the default request is items=48, concurrency=8
    expect(captured[0]?.url).toContain('/demo/reactive/compare?items=48&concurrency=8');
  });

  it('rides the selected item count and pipeline concurrency on the request URL', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false });

    render(<ReactiveCompareDemo />);
    fireEvent.click(screen.getByRole('button', { name: '200' }));
    fireEvent.click(screen.getByRole('button', { name: '4' }));
    fireEvent.click(screen.getByTestId('reactive-run-compare'));

    await screen.findByTestId('reactive-compare-report');
    expect(captured[0]?.url).toContain('/demo/reactive/compare?items=200&concurrency=4');
  });

  it('paints the exact drop and buffer counts beside their strategies', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false });

    render(<ReactiveCompareDemo />);

    fireEvent.click(screen.getByTestId('reactive-run-drop'));
    const dropPanel = await screen.findByTestId('reactive-backpressure-report');
    expect(dropPanel).toHaveTextContent('strategy drop — 24 emitted, initial demand 4, late demand 4');
    expect(screen.getByTestId('reactive-backpressure-counts')).toHaveTextContent(
      'delivered after initial demand: 4 → after late demand: 4 · dropped: 20 · still buffered: 0',
    );
    expect(screen.getByTestId('reactive-bp-reading-1')).toHaveTextContent('dropped values never return');

    fireEvent.click(screen.getByTestId('reactive-run-buffer'));
    const bufferPanel = await screen.findByTestId('reactive-backpressure-report');
    expect(bufferPanel).toHaveTextContent('strategy buffer');
    expect(screen.getByTestId('reactive-backpressure-counts')).toHaveTextContent(
      'delivered after initial demand: 4 → after late demand: 8 · dropped: 0 · still buffered: 16',
    );
    expect(screen.getByTestId('reactive-bp-reading-0')).toHaveTextContent('BUFFERED 20 in the sink');
    // both strategy runs ride the deterministic parameters
    expect(captured[0]?.url).toContain('/demo/reactive/backpressure?strategy=drop&items=24&initial=4&late=4');
    expect(captured[1]?.url).toContain('/demo/reactive/backpressure?strategy=buffer&items=24&initial=4&late=4');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoint([], { current: true });

    render(<ReactiveCompareDemo />);
    fireEvent.click(screen.getByTestId('reactive-run-compare'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('reactive demo unreachable');
  });
});
