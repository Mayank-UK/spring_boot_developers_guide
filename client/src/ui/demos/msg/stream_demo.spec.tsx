import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import StreamDemo from './stream_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * StreamDemo spec — the consumer-group console over the stubbed
 * endpoints: publishing POSTs the chosen vocabulary event and renders the
 * broker's stream id; consuming renders per-delivery duplicate flags (the
 * idempotency receipt) plus the projection counters; the pending ledger
 * loads on mount and lists holders with idle and delivery counts; claiming
 * POSTs minIdleMs + ack and renders the takeover result; and a degraded
 * dependency (typed 503 envelope) renders the error panel.
 */

interface Captured {
  url: string;
  method: string;
  body: unknown;
}

type Responder = (
  url: string,
  init?: RequestInit,
) => { ok: boolean; status: number; json: () => Promise<unknown> };

function publishBody() {
  return {
    streamId: '1760-0',
    stream: 'guide:demo:stream:events',
    eventId: 'evt-1',
    eventType: 'demo.ping',
    aggregateId: 'console',
  };
}

function consumeBody() {
  return {
    deliveries: [
      {
        id: '1760-0',
        duplicate: false,
        event: { eventId: 'evt-1', eventType: 'demo.ping', aggregateId: 'console' },
      },
    ],
    acked: 1,
    counters: { 'demo.ping': 1, 'post.published': 0, 'post.title-touched': 0 },
    duplicatesSeen: 0,
  };
}

function pendingBody() {
  return {
    total: 1,
    minId: '1760-0',
    maxId: '1760-0',
    perConsumer: { 'worker-1': 1 },
    entries: [{ id: '1760-0', consumer: 'worker-1', idleMs: 4200, deliveryCount: 2 }],
  };
}

function dlqBody() {
  return {
    entries: [
      {
        id: '1769-0',
        eventId: 'evt-9',
        eventType: 'demo.ping',
        reason: 'delivered 4 times without an ack — poison',
      },
    ],
  };
}

function stubEndpoints(captured: Captured[], ok: boolean): Responder {
  const respond: Responder = (url, init) => {
    captured.push({
      url,
      method: init?.method ?? 'GET',
      body: init?.body ? (JSON.parse(String(init.body)) as unknown) : null,
    });
    if (!ok) {
      return {
        ok: false,
        status: 503,
        json: async () => ({
          error: {
            code: 'service_unavailable',
            message: 'Redis Streams are unreachable',
            requestId: 'stub',
          },
        }),
      };
    }
    if (url.endsWith('/pending')) {
      return { ok: true, status: 200, json: async () => pendingBody() };
    }
    if (url.endsWith('/dlq')) {
      return { ok: true, status: 200, json: async () => dlqBody() };
    }
    if (url.includes('/consume')) {
      return { ok: true, status: 200, json: async () => consumeBody() };
    }
    if (url.includes('/claim')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          claimedBy: 'worker-2',
          minIdleMs: 0,
          reprocessed: [{ id: '1760-0', duplicate: true }],
          deadLettered: [],
          acked: 1,
          counters: {},
        }),
      };
    }
    return { ok: true, status: 200, json: async () => publishBody() };
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      return respond(url, init);
    }),
  );
  return respond;
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('StreamDemo', () => {
  it('loads the pending ledger and dlq on mount', async () => {
    stubEndpoints([], true);

    render(<StreamDemo />);

    expect(await screen.findByTestId('msg-stream-pending-total')).toHaveTextContent('total 1');
    const entry = await screen.findByTestId('msg-stream-pending-entries');
    expect(entry).toHaveTextContent('held by worker-1');
    expect(entry).toHaveTextContent('delivered 2×');
    const dlq = screen.getByTestId('msg-stream-dlq');
    expect(dlq).toHaveTextContent('delivered 4 times without an ack');
  });

  it('publishes the chosen event and renders the stream id', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<StreamDemo />);
    fireEvent.change(screen.getByTestId('msg-stream-payload'), {
      target: { value: '{"note":"hi"}' },
    });
    fireEvent.click(screen.getByTestId('msg-stream-publish'));

    const chip = await screen.findByTestId('msg-stream-published');
    expect(chip).toHaveTextContent('demo.ping → 1760-0');
    const posts = captured.filter((call) => call.method === 'POST');
    expect(posts[0]?.url).toBe('/api/demo/msg/stream/publish');
    expect(posts[0]?.body).toEqual({ eventType: 'demo.ping', payload: { note: 'hi' } });
  });

  it('consumes with the ack flag and renders duplicate flags plus counters', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<StreamDemo />);
    fireEvent.click(screen.getByTestId('msg-stream-consume'));

    const deliveries = await screen.findByTestId('msg-stream-deliveries');
    expect(deliveries).toHaveTextContent('1760-0');
    expect(deliveries).toHaveTextContent('[applied]');
    expect(screen.getByTestId('msg-stream-consumed')).toHaveTextContent('1 delivered');
    expect(screen.getByTestId('msg-stream-consumed')).toHaveTextContent('1 acked');
    const consumeCalls = captured.filter((call) => call.url.includes('/consume'));
    expect(consumeCalls[0]?.url).toBe('/api/demo/msg/stream/consume?max=10&ack=true&replayPending=false');
  });

  it('claims with minIdleMs and renders the takeover result', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<StreamDemo />);
    fireEvent.change(screen.getByTestId('msg-stream-minidle'), { target: { value: '250' } });
    fireEvent.click(screen.getByTestId('msg-stream-claim'));

    const result = await screen.findByTestId('msg-stream-claim-result');
    expect(result).toHaveTextContent('worker-2');
    expect(result).toHaveTextContent('1 reprocessed');
    const claims = captured.filter((call) => call.url.includes('/claim'));
    expect(claims[0]?.url).toBe('/api/demo/msg/stream/claim?minIdleMs=250&ack=true');
  });

  it('renders the degraded panel when the dependency is down', async () => {
    stubEndpoints([], false);

    render(<StreamDemo />);
    fireEvent.click(screen.getByTestId('msg-stream-publish'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('Redis Streams are unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
