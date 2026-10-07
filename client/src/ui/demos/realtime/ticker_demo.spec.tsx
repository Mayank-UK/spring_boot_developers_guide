import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import TickerDemo from './ticker_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * TickerDemo spec — jsdom has no EventSource, so the class is FAKED and
 * driven deterministically: subscribing constructs it with the right URL
 * (endless vs limit=N), named 'tick' events update the live table and the
 * recent list, the state endpoint is polled beside the stream, and the
 * connection badge walks the EventSource lifecycle — connecting → live →
 * reconnecting (the browser's own auto-reconnect, visible) → closed.
 */

interface TickRow {
  seq: number;
  symbol: string;
  price: number;
  delta: number;
  at: string;
}

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  readyState = 0;
  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private readonly listeners = new Map<string, ((event: { data: string }) => void)[]>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, callback: (event: { data: string }) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), callback]);
  }

  close(): void {
    this.readyState = 2;
  }

  // ---- spec helpers: drive the fake the way a server would ----

  open(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  emitTick(row: TickRow): void {
    for (const callback of this.listeners.get('tick') ?? []) {
      callback({ data: JSON.stringify(row) });
    }
  }

  dropped(): void {
    this.readyState = 0; // still CONNECTING — the browser is re-connecting
    this.onerror?.();
  }
}

function lastStream(): FakeEventSource {
  const instance = FakeEventSource.instances.at(-1);
  if (!instance) throw new Error('no EventSource constructed');
  return instance;
}

function stubStateFetch(activeEmitters: number, ticksSent: number): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ activeEmitters, ticksSent }),
    })),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
  FakeEventSource.instances = [];
});

describe('TickerDemo', () => {
  it('subscribes with the right URL and walks connecting → live', async () => {
    stubStateFetch(1, 42);
    vi.stubGlobal('EventSource', FakeEventSource);

    render(<TickerDemo />);
    fireEvent.click(screen.getByTestId('realtime-ticker-connect'));

    const stream = lastStream();
    expect(stream.url).toBe('/api/demo/realtime/ticker');
    expect(screen.getByTestId('realtime-ticker-connection')).toHaveTextContent('connecting');
    act(() => { stream.open(); });
    expect(screen.getByTestId('realtime-ticker-connection')).toHaveTextContent('live');
    const state = await screen.findByTestId('realtime-ticker-state');
    expect(state).toHaveTextContent('activeEmitters 1');
    expect(state).toHaveTextContent('ticksSent 42');
  });

  it('carries the limit when a bounded stream is chosen', () => {
    stubStateFetch(1, 7);
    vi.stubGlobal('EventSource', FakeEventSource);

    render(<TickerDemo />);
    fireEvent.change(screen.getByTestId('realtime-ticker-limit'), { target: { value: '20' } });
    fireEvent.click(screen.getByTestId('realtime-ticker-connect'));

    expect(lastStream().url).toBe('/api/demo/realtime/ticker?limit=20');
  });

  it('renders named tick events into the live table and the recent list', () => {
    stubStateFetch(1, 3);
    vi.stubGlobal('EventSource', FakeEventSource);

    render(<TickerDemo />);
    fireEvent.click(screen.getByTestId('realtime-ticker-connect'));
    const stream = lastStream();
    act(() => { stream.open(); });

    act(() => { stream.emitTick({ seq: 1, symbol: 'SDG', price: 100.06, delta: 0.06, at: 't' }); });
    act(() => { stream.emitTick({ seq: 2, symbol: 'JDK', price: 99.9, delta: -0.1, at: 't' }); });
    act(() => { stream.emitTick({ seq: 3, symbol: 'SDG', price: 100.12, delta: 0.06, at: 't' }); });

    const sdg = screen.getByTestId('realtime-ticker-row-SDG');
    expect(sdg).toHaveTextContent('100.12');
    expect(sdg).toHaveTextContent('id 3');
    expect(screen.getByTestId('realtime-ticker-row-JDK')).toHaveTextContent('99.9');
    const recent = screen.getByTestId('realtime-ticker-recent');
    expect(recent).toHaveTextContent('id 3');
    expect(recent).toHaveTextContent('event:tick');
    expect(recent).toHaveTextContent('id 1');
  });

  it('shows the browser auto-reconnect on drop and closed after close()', () => {
    stubStateFetch(0, 0);
    vi.stubGlobal('EventSource', FakeEventSource);

    render(<TickerDemo />);
    fireEvent.click(screen.getByTestId('realtime-ticker-connect'));
    const stream = lastStream();
    act(() => { stream.open(); });

    act(() => { stream.dropped(); });
    expect(screen.getByTestId('realtime-ticker-connection')).toHaveTextContent('reconnecting');

    fireEvent.click(screen.getByTestId('realtime-ticker-disconnect'));
    expect(screen.getByTestId('realtime-ticker-connection')).toHaveTextContent('closed');
    expect(stream.readyState).toBe(2);
  });
});
