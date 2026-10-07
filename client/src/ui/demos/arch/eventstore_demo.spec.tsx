import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import EventStoreDemo from './eventstore_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * EventStoreDemo spec — the store over stubbed endpoints: an empty store
 * renders the honest first view (no history yet, not an error); an append
 * rides stream/type/item/price on the request URL and paints both views —
 * the raw log rows and the projection with its replay log; a sealed
 * (checked-out) stream renders the seal in the projection and the inventory
 * chips; a degraded server renders the error panel. Every fact asserted
 * comes from the mocked SERVER answer — the component narrates nothing it
 * did not fetch.
 */

interface Captured {
  url: string;
  method: string;
}

function event(overrides?: { position?: number; eventId?: string; type?: string; payload?: object }) {
  return {
    position: overrides?.position ?? 1,
    eventId: overrides?.eventId ?? 'evt-1',
    streamId: 'cart-1',
    type: overrides?.type ?? 'cart.item-added',
    payload: overrides?.payload ?? { item: 'mug', priceCents: 1200 },
  };
}

function projection(overrides?: {
  version?: number;
  items?: string[];
  totalCents?: number;
  status?: string;
  sealed?: boolean;
  replayLog?: string[];
}) {
  return {
    streamId: 'cart-1',
    version: overrides?.version ?? 1,
    items: overrides?.items ?? ['mug'],
    totalCents: overrides?.totalCents ?? 1200,
    status: overrides?.status ?? 'open',
    sealed: overrides?.sealed ?? false,
    replayLog:
      overrides?.replayLog ?? [
        'evt-1 cart.item-added mug 1200¢ → items [mug], total 1200¢',
      ],
  };
}

type Down = { current: boolean };

/**
 * One stub for a whole test: /streams answers the inventory (plus the raw
 * log and projection for known streams on GET), /append answers the stored
 * fact plus the projection after — or the 503 envelope when down.
 */
function stubStore(
  captured: Captured[],
  down: Down,
  bodies: {
    streams?: () => unknown;
    stream?: () => unknown;
    state?: () => unknown;
    append?: () => unknown;
    streamKnown?: boolean;
  },
): void {
  const streamsBody = bodies.streams ?? (() => []);
  const streamBody = bodies.stream ?? (() => ({ streamId: 'cart-1', events: [event()] }));
  const stateBody = bodies.state ?? (() => projection());
  const appendBody = bodies.append ?? (() => ({ event: event(), after: projection() }));
  const streamKnown = bodies.streamKnown ?? true;

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
            error: { code: 'service_unavailable', message: 'event store unreachable', requestId: 'x' },
          }),
        };
      }
      if (url.includes('/append')) {
        return { ok: true, status: 200, json: async () => appendBody() };
      }
      if (url.includes('/streams')) {
        return { ok: true, status: 200, json: async () => streamsBody() };
      }
      if (url.includes('/stream/')) {
        return { ok: true, status: 200, json: async () => (streamKnown ? streamBody() : null) };
      }
      if (url.includes('/state/')) {
        return { ok: true, status: 200, json: async () => stateBody() };
      }
      return { ok: true, status: 200, json: async () => ({}) };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('EventStoreDemo', () => {
  it('renders the honest empty view when the store owns no streams yet', async () => {
    const captured: Captured[] = [];
    stubStore(captured, { current: false }, { streams: () => [], streamKnown: false });

    render(<EventStoreDemo />);

    expect(await screen.findByTestId('eventstore-empty')).toHaveTextContent('no history yet');
    expect(screen.queryByTestId('eventstore-log')).not.toBeInTheDocument();
    // the mount fetch asks only for the inventory — no 404 fishing for a
    // stream the store never saw
    expect(captured).toHaveLength(1);
    expect(captured[0]?.url).toContain('/demo/arch/eventstore/streams');
  });

  it('appends a fact and paints both views: the raw log and the folded projection', async () => {
    const captured: Captured[] = [];
    stubStore(captured, { current: false }, {
      streams: () => [{ streamId: 'cart-1', events: 1, sealed: false }],
      stream: () => ({ streamId: 'cart-1', events: [event()] }),
      state: () => projection(),
      append: () => ({ event: event(), after: projection() }),
    });

    render(<EventStoreDemo />);
    await screen.findByTestId('eventstore-log');

    fireEvent.click(screen.getByTestId('eventstore-append'));

    expect(await screen.findByTestId('eventstore-flash')).toHaveTextContent(
      'evt-1 stored at position 1',
    );
    const rows = screen.getAllByTestId('eventstore-event-row');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('cart.item-added');
    expect(rows[0]).toHaveTextContent('item=mug');
    expect(rows[0]).toHaveTextContent('priceCents=1200');
    expect(screen.getByTestId('eventstore-projection')).toHaveTextContent('v1');
    expect(screen.getByTestId('eventstore-projection')).toHaveTextContent('items [mug]');
    expect(screen.getByTestId('eventstore-projection')).toHaveTextContent('total 1200¢');
    expect(screen.getByTestId('eventstore-replay-0')).toHaveTextContent(
      'evt-1 cart.item-added mug 1200¢',
    );
    // the append rides stream/type/item/price on the request URL
    const append = captured.find((call) => call.method === 'POST');
    expect(append?.url).toContain(
      '/demo/arch/eventstore/append?stream=cart-1&type=cart.item-added&item=mug&priceCents=1200',
    );
  });

  it('renders a sealed stream: checkout in the projection line and the inventory chips', async () => {
    stubStore([], { current: false }, {
      streams: () => [{ streamId: 'cart-1', events: 2, sealed: true }],
      stream: () => ({
        streamId: 'cart-1',
        events: [
          event({ position: 1, eventId: 'evt-1' }),
          event({ position: 2, eventId: 'evt-2', type: 'cart.checked-out', payload: {} }),
        ],
      }),
      state: () =>
        projection({
          version: 2,
          items: ['book'],
          totalCents: 2500,
          status: 'checked-out',
          sealed: true,
          replayLog: [
            'evt-1 cart.item-added book 2500¢ → items [book], total 2500¢',
            'evt-2 cart.checked-out → history sealed: every later read of this stream folds the same state',
          ],
        }),
    });

    render(<EventStoreDemo />);

    expect(await screen.findByTestId('eventstore-projection')).toHaveTextContent('checked-out');
    expect(screen.getByTestId('eventstore-projection')).toHaveTextContent('(sealed)');
    const chips = screen.getAllByTestId('eventstore-stream-chip');
    expect(chips[0]).toHaveTextContent('cart-1 · 2 events · sealed');
    expect(screen.getByTestId('eventstore-replay-1')).toHaveTextContent('history sealed');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubStore([], { current: true }, {});

    render(<EventStoreDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('event store unreachable');
  });
});
