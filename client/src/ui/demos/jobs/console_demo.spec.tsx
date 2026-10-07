import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import JobsConsoleDemo from './console_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * JobsConsoleDemo spec — the queue console over stubbed endpoints: the
 * overview loads on mount with the two observability numbers (depth,
 * oldest age) and the state chips; enqueue POSTs the chosen type, parsed
 * payload and failure injection, then follows the returned job's trace;
 * the dead-letter panel lists exhausted records and replay (fix) POSTs
 * the cleared injection; the async panel fires the specimen and renders
 * the ticket's thread (caller-runs called out); and a degraded server
 * (typed envelope) renders the error panel.
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

function overviewBody() {
  return {
    countsByState: { queued: 1, running: 0, succeeded: 2, failed: 1, dead: 1 },
    depth: 2,
    oldestPendingId: 'job_a',
    oldestPendingAgeMs: 4200,
    deadLetters: 1,
  };
}

function deadBody() {
  return {
    deadLetters: [
      {
        id: 'job_dead',
        type: 'send-welcome-email',
        payload: '{}',
        state: 'dead',
        maxAttempts: 3,
        attemptsLeft: 0,
        failFirstAttempts: 9,
        attempts: [
          { n: 1, at: '2026-10-06T12:00:00Z', outcome: 'failed', error: 'SMTP 421' },
          { n: 2, at: '2026-10-06T12:00:01Z', outcome: 'failed', error: 'SMTP 421' },
          { n: 3, at: '2026-10-06T12:00:03Z', outcome: 'dead', error: 'SMTP 421' },
        ],
        lastError: 'injected failure #3 of 9 — SMTP 421, try again later',
        queuedAt: '2026-10-06T12:00:00Z',
        startedAt: '2026-10-06T12:00:00Z',
        finishedAt: '2026-10-06T12:00:03Z',
        nextAttemptAt: null,
        replays: 0,
      },
    ],
  };
}

function heartbeatBody() {
  return { ticks: ['2026-10-06T12:00:09Z'], totalTicks: 42 };
}

function ticketsBody() {
  return [
    {
      id: 'work_1',
      delayMs: 1500,
      requestedAt: '2026-10-06T12:00:00Z',
      startedAt: '2026-10-06T12:00:00Z',
      threadName: 'jobs-demo-1',
      finishedAt: '2026-10-06T12:00:01Z',
      state: 'done',
    },
    {
      id: 'work_2',
      delayMs: 1500,
      requestedAt: '2026-10-06T12:00:00Z',
      startedAt: '2026-10-06T12:00:00Z',
      threadName: 'Test worker',
      finishedAt: null,
      state: 'running',
    },
  ];
}

function emailsBody() {
  return [
    {
      to: 'reader@example.com',
      subject: 'Welcome to the guide, Reader',
      body: 'Hi Reader — your account is ready.',
      at: '2026-10-06T12:00:01Z',
    },
  ];
}

function enqueueBody() {
  return {
    id: 'job_new',
    type: 'send-welcome-email',
    payload: '{"to":"reader@example.com","name":"Reader"}',
    state: 'queued',
    maxAttempts: 3,
    attemptsLeft: 3,
    failFirstAttempts: 2,
    attempts: [],
    lastError: null,
    queuedAt: '2026-10-06T12:00:00Z',
    startedAt: null,
    finishedAt: null,
    nextAttemptAt: '2026-10-06T12:00:00Z',
    replays: 0,
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
            message: 'The queue console is unreachable',
            requestId: 'stub',
          },
        }),
      };
    }
    if (url.endsWith('/heartbeat')) {
      return { ok: true, status: 200, json: async () => heartbeatBody() };
    }
    if (url.endsWith('/emails')) {
      return { ok: true, status: 200, json: async () => emailsBody() };
    }
    if (url.endsWith('/dead')) {
      return { ok: true, status: 200, json: async () => deadBody() };
    }
    if (url.endsWith('/enqueue')) {
      return { ok: true, status: 200, json: async () => enqueueBody() };
    }
    if (url.includes('/replay')) {
      return { ok: true, status: 200, json: async () => enqueueBody() };
    }
    if (url.endsWith('/async') && (init?.method ?? 'GET') === 'POST') {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          id: 'work_9',
          delayMs: 1500,
          requestedAt: '2026-10-06T12:00:00Z',
          startedAt: null,
          threadName: null,
          finishedAt: null,
          state: 'pending',
        }),
      };
    }
    if (url.endsWith('/async')) {
      return { ok: true, status: 200, json: async () => ticketsBody() };
    }
    if (url.includes('/jobs/')) {
      return { ok: true, status: 200, json: async () => enqueueBody() };
    }
    return { ok: true, status: 200, json: async () => overviewBody() };
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

describe('JobsConsoleDemo', () => {
  it('loads the overview with the two numbers, chips, heartbeat and emails', async () => {
    stubEndpoints([], true);

    render(<JobsConsoleDemo />);

    expect(await screen.findByTestId('jobs-depth')).toHaveTextContent('depth 2');
    expect(screen.getByTestId('jobs-oldest-age')).toHaveTextContent('oldest 4s');
    const counts = screen.getByText('failed: 1');
    expect(counts).toBeDefined();
    expect(screen.getByTestId('jobs-heartbeat')).toHaveTextContent('heartbeat 42 ticks');
    const emails = screen.getByTestId('jobs-emails');
    expect(emails).toHaveTextContent('to reader@example.com');
    expect(emails).toHaveTextContent('Welcome to the guide, Reader');
  });

  it('lists dead letters with their error trail and replay buttons', async () => {
    stubEndpoints([], true);

    render(<JobsConsoleDemo />);

    const dead = await screen.findByTestId('jobs-dead');
    expect(dead).toHaveTextContent('job_dead');
    expect(dead).toHaveTextContent('3 attempts');
    expect(dead).toHaveTextContent('SMTP 421, try again later');
    expect(screen.getByTestId('jobs-replay-fixed')).toBeDefined();
    expect(screen.getByTestId('jobs-replay-poison')).toBeDefined();
  });

  it('enqueues the chosen job with the failure injection and traces it', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<JobsConsoleDemo />);
    await screen.findByTestId('jobs-depth');
    fireEvent.change(screen.getByTestId('jobs-payload'), {
      target: { value: '{"to":"maker@example.com","name":"Maker"}' },
    });
    fireEvent.change(screen.getByTestId('jobs-failattempts'), { target: { value: '2' } });
    fireEvent.click(screen.getByTestId('jobs-enqueue'));

    const trace = await screen.findByTestId('jobs-trace');
    expect(trace).toHaveTextContent('job_new');
    expect(trace).toHaveTextContent('queued');
    expect(trace).toHaveTextContent('attempts left 3/3');
    const posts = captured.filter((call) => call.url.endsWith('/enqueue'));
    expect(posts[0]?.body).toEqual({
      type: 'send-welcome-email',
      payload: { to: 'maker@example.com', name: 'Maker' },
      failAttempts: 2,
    });
  });

  it('replays a dead letter with the injection cleared (the fix)', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<JobsConsoleDemo />);
    await screen.findByTestId('jobs-dead');
    fireEvent.click(screen.getByTestId('jobs-replay-fixed'));

    const trace = await screen.findByTestId('jobs-trace');
    expect(trace).toHaveTextContent('job_new');
    const replays = captured.filter((call) => call.url.includes('/replay'));
    expect(replays[0]?.url).toBe('/api/demo/jobs/dead/job_dead/replay');
    expect(replays[0]?.body).toEqual({ failAttempts: 0 });
  });

  it('fires the async specimen and renders the thread evidence', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<JobsConsoleDemo />);
    await screen.findByTestId('jobs-async-tickets');
    fireEvent.change(screen.getByTestId('jobs-delay'), { target: { value: '800' } });
    fireEvent.click(screen.getByTestId('jobs-async-fire'));

    const posts = captured.filter(
      (call) => call.url.endsWith('/async') && call.method === 'POST',
    );
    expect(posts[0]?.body).toEqual({ delayMs: 800 });
    const tickets = screen.getByTestId('jobs-async-tickets');
    expect(tickets).toHaveTextContent('jobs-demo-1');
    expect(tickets).toHaveTextContent('[caller-runs — saturation!]');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], false);

    render(<JobsConsoleDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('The queue console is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
