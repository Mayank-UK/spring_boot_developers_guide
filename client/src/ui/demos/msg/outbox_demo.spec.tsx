import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import OutboxDemo from './outbox_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * OutboxDemo spec — the proof console over the stubbed endpoints: the
 * transactional call POSTs {postId, publisher, failBeforeCommit,
 * failAfterPublish} and renders the outcome note (outbox = one tx, direct
 * = two systems); a staged crash (500 envelope) renders the CRASH CARD
 * with the what-to-watch-next hint instead of the error panel; relay
 * renders the batch ledger; and state renders the pending/delivered/dead
 * counts with the poller verdict.
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

function stateBody() {
  return {
    stats: { pending: 1, delivered: 2, dead: 0 },
    recent: [
      { id: 3, attempts: 0, event: { eventId: 'evt-3', eventType: 'post.published' }, processedAt: null },
      { id: 2, attempts: 0, event: { eventId: 'evt-2', eventType: 'post.published' }, processedAt: '2026-10-06T12:00:02Z' },
    ],
    pollerRunning: true,
  };
}

function stubEndpoints(
  captured: Captured[],
  mode: 'ok' | 'crash',
): Responder {
  const respond: Responder = (url, init) => {
    captured.push({
      url,
      method: init?.method ?? 'GET',
      body: init?.body ? (JSON.parse(String(init.body)) as unknown) : null,
    });
    if (url.endsWith('/state')) {
      return { ok: true, status: 200, json: async () => stateBody() };
    }
    if (url.endsWith('/relay')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          claimed: 1,
          published: 1,
          deadLettered: 0,
          skippedInFlight: false,
          streamIds: ['1761-0'],
        }),
      };
    }
    if (mode === 'crash') {
      return {
        ok: false,
        status: 500,
        json: async () => ({
          error: { code: 'internal_error', message: 'simulated crash', requestId: 'stub' },
        }),
      };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({
        publisher: 'outbox',
        postId: '9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01',
        title: 'The uniform interface is the contract that scales',
        newVersion: 2,
        eventId: 'evt-ok',
        note: 'one transaction: the post update and the outbox row committed together',
      }),
    };
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

describe('OutboxDemo', () => {
  it('loads the state readout on mount', async () => {
    stubEndpoints([], 'ok');

    render(<OutboxDemo />);

    const stats = await screen.findByTestId('msg-outbox-stats');
    expect(stats).toHaveTextContent('pending 1');
    expect(stats).toHaveTextContent('delivered 2');
    expect(stats).toHaveTextContent('poller live');
    const rows = screen.getByTestId('msg-outbox-rows');
    expect(rows).toHaveTextContent('#3');
    expect(rows).toHaveTextContent('pending');
  });

  it('sends the transactional request with both crash flags and renders the outcome', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');

    render(<OutboxDemo />);
    fireEvent.change(screen.getByTestId('msg-outbox-publisher'), {
      target: { value: 'outbox' },
    });
    fireEvent.click(screen.getByTestId('msg-outbox-fail-before'));
    fireEvent.click(screen.getByTestId('msg-outbox-fail-after'));
    fireEvent.click(screen.getByTestId('msg-outbox-transact'));

    const outcome = await screen.findByTestId('msg-outbox-outcome');
    expect(outcome).toHaveTextContent('one transaction');
    const posts = captured.filter((call) => call.url.endsWith('/transactional'));
    expect(posts[0]?.body).toEqual({
      postId: '9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01',
      publisher: 'outbox',
      failBeforeCommit: true,
      failAfterPublish: true,
    });
  });

  it('renders the crash card (not the error panel) when the staged crash answers 500', async () => {
    stubEndpoints([], 'crash');

    render(<OutboxDemo />);
    fireEvent.click(screen.getByTestId('msg-outbox-fail-after'));
    fireEvent.click(screen.getByTestId('msg-outbox-transact'));

    const crash = await screen.findByTestId('msg-outbox-crash');
    expect(crash).toHaveTextContent('the 500 is the proof starting');
    expect(crash).toHaveTextContent('the row survived the commit');
    expect(screen.queryByTestId('error-panel')).toBeNull();
  });

  it('runs a relay batch and renders the ledger', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');

    render(<OutboxDemo />);
    fireEvent.click(screen.getByTestId('msg-outbox-relay'));

    const result = await screen.findByTestId('msg-outbox-relay-result');
    expect(result).toHaveTextContent('claimed 1');
    expect(result).toHaveTextContent('published 1');
    expect(result).not.toHaveTextContent('single-flight');
    const relays = captured.filter((call) => call.url.endsWith('/relay'));
    expect(relays[0]?.method).toBe('POST');
  });
});
