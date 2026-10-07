import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import WebhookSimulatorDemo from './webhook_simulator_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * WebhookSimulatorDemo spec — the console over stubbed routes: subscriptions
 * render masked; the subscribe POST carries secret + event types; the fire
 * POST carries the event, the payload bytes as typed, and the respond script
 * (failure injection is an explicit request, not a client-side accident);
 * the dead-letter timeline renders all five attempts with the DLQ badge and
 * no-response transport rows; receipts render the consumer's verdicts with
 * duplicate flags.
 */

const SUBS_URL = '/api/demo/webhooks/subscriptions';
const RECEIPTS_URL = '/api/demo/webhooks/receipts';
const FIRE_URL = '/api/demo/webhooks/subscriptions/sub_demo1/fire';
const DELIVERIES_URL = '/api/demo/webhooks/subscriptions/sub_demo1/deliveries';

const SUBSCRIPTION = {
  id: 'sub_demo1',
  url: 'http://localhost:8080/api/demo/webhooks/receiver',
  secretMasked: 'whsec_…01',
  eventTypes: ['invoice.paid'],
  active: true,
  createdAt: '2026-10-06T12:00:00Z',
};

const DEAD_LETTERED = {
  id: 'dlv_dead1',
  eventId: 'evt_dead1',
  eventType: 'invoice.paid',
  state: 'dead-lettered',
  payload: '{"id":"evt_dead1","type":"invoice.paid","occurredAt":"2026-10-06T12:00:00Z","data":{}}',
  attempts: [
    { n: 1, at: '2026-10-06T12:00:00Z', outcome: 'failed', httpStatus: 500, signatureHeader: 't=1,v1=aa' },
    { n: 2, at: '2026-10-06T12:00:01Z', outcome: 'failed', httpStatus: 500, signatureHeader: 't=2,v1=bb' },
    { n: 3, at: '2026-10-06T12:00:03Z', outcome: 'failed', httpStatus: 503, signatureHeader: 't=3,v1=cc' },
    { n: 4, at: '2026-10-06T12:00:07Z', outcome: 'failed', httpStatus: null, signatureHeader: 't=4,v1=dd' },
    { n: 5, at: '2026-10-06T12:00:15Z', outcome: 'dead-lettered', httpStatus: 500, signatureHeader: 't=5,v1=ee' },
  ],
  nextAttemptAt: null,
  attemptsLeft: 0,
};

const RECEIPTS = {
  receipts: [
    {
      eventId: 'evt_dead1',
      eventType: 'invoice.paid',
      signatureVerdict: 'invalid',
      signatureReason: 'hmac mismatch — wrong secret or altered body',
      duplicate: false,
      respondedStatus: 401,
      note: 'refused before parsing',
      receivedAt: '2026-10-06T12:00:16Z',
    },
    {
      eventId: 'evt_ok1',
      eventType: 'invoice.paid',
      signatureVerdict: 'verified',
      signatureReason: 'hmac-sha256 match, constant-time compare',
      duplicate: true,
      respondedStatus: 200,
      note: 'at-least-once: duplicate of an earlier receipt',
      receivedAt: '2026-10-06T12:00:10Z',
    },
  ],
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('WebhookSimulatorDemo', () => {
  it('renders subscriptions with masked secrets and auto-selects the first', async () => {
    stubApiFetch([
      { url: SUBS_URL, body: { subscriptions: [SUBSCRIPTION] } },
      { url: DELIVERIES_URL, body: { subscriptionId: 'sub_demo1', deliveries: [] } },
      { url: RECEIPTS_URL, body: { receipts: [] } },
    ]);

    render(<WebhookSimulatorDemo />);

    const row = await screen.findByTestId('wh-sub-row');
    expect(row).toHaveTextContent('sub_demo1');
    expect(row).toHaveTextContent('whsec_…01');
    expect(row.textContent).not.toContain('whsec_demo_console_secret_01');
    expect(row).toHaveTextContent('invoice.paid');
    // auto-selection: the timeline switches to the selected subscription's view
    await waitFor(() =>
      expect(screen.getByText('nothing fired yet', { exact: false })).toBeInTheDocument(),
    );
  });

  it('subscribes with the typed secret and event types, then selects the new sub', async () => {
    let subscribed: { url: string; init?: RequestInit } | undefined;
    let created = false; // stateful: the listing sees the new sub only after the POST
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url === SUBS_URL && (init?.method ?? 'GET') === 'POST') {
          subscribed = { url, init };
          created = true;
          return jsonResponse({ ...SUBSCRIPTION, secret: 'whsec_demo_console_secret_01', urlDefaulted: true });
        }
        if (url === SUBS_URL) {
          return jsonResponse({ subscriptions: created ? [SUBSCRIPTION] : [] });
        }
        if (url === DELIVERIES_URL) return jsonResponse({ subscriptionId: 'sub_demo1', deliveries: [] });
        if (url === RECEIPTS_URL) return jsonResponse({ receipts: [] });
        return jsonResponse({ error: { code: 'not_found', message: `no stub for ${url}`, requestId: 'stub' } }, 404);
      }),
    );

    render(<WebhookSimulatorDemo />);
    await waitFor(() => expect(screen.getByTestId('wh-subscribe')).toBeInTheDocument());

    fireEvent.change(screen.getByTestId('wh-secret-input'), {
      target: { value: 'whsec_typed_by_a_human_456' },
    });
    fireEvent.change(screen.getByTestId('wh-events-input'), {
      target: { value: 'invoice.paid, invoice.voided' },
    });
    fireEvent.click(screen.getByTestId('wh-subscribe'));

    await waitFor(() => expect(subscribed).toBeDefined());
    expect(String(subscribed?.init?.body)).toContain('whsec_typed_by_a_human_456');
    expect(String(subscribed?.init?.body)).toContain('invoice.voided');
    await screen.findByTestId('wh-sub-row');
  });

  it('fires with the respond script and payload bytes as typed', async () => {
    let fired: { url: string; init?: RequestInit } | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url === FIRE_URL) {
          fired = { url, init };
          return jsonResponse({
            ...DEAD_LETTERED,
            state: 'pending',
            attempts: [],
            attemptsLeft: 5,
            nextAttemptAt: '2026-10-06T12:00:01Z',
          });
        }
        if (url === SUBS_URL) return jsonResponse({ subscriptions: [SUBSCRIPTION] });
        if (url === DELIVERIES_URL) return jsonResponse({ subscriptionId: 'sub_demo1', deliveries: [DEAD_LETTERED] });
        if (url === RECEIPTS_URL) return jsonResponse({ receipts: [] });
        return jsonResponse({ error: { code: 'not_found', message: `no stub for ${url}`, requestId: 'stub' } }, 404);
      }),
    );

    render(<WebhookSimulatorDemo />);
    await screen.findByTestId('wh-sub-row');

    fireEvent.change(screen.getByTestId('wh-event-input'), {
      target: { value: 'invoice.voided' },
    });
    fireEvent.change(screen.getByTestId('wh-payload-input'), {
      target: { value: '{"reason":"fraud"}' },
    });
    fireEvent.change(screen.getByTestId('wh-respond-select'), {
      target: { value: '500' },
    });
    fireEvent.click(screen.getByTestId('wh-fire'));

    await waitFor(() => expect(fired).toBeDefined());
    expect(fired?.url).toBe(FIRE_URL);
    expect(fired?.init?.method).toBe('POST');
    const body = String(fired?.init?.body);
    expect(body).toContain('"eventType":"invoice.voided"');
    expect(body).toContain('"payload":{"reason":"fraud"}');
    expect(body).toContain('"respond":"500"');
  });

  it('renders the dead-letter timeline: five attempts, transport failure, DLQ badge', async () => {
    stubApiFetch([
      { url: SUBS_URL, body: { subscriptions: [SUBSCRIPTION] } },
      { url: DELIVERIES_URL, body: { subscriptionId: 'sub_demo1', deliveries: [DEAD_LETTERED] } },
      { url: RECEIPTS_URL, body: RECEIPTS },
    ]);

    render(<WebhookSimulatorDemo />);

    expect(await screen.findByTestId('wh-dlq-badge')).toBeInTheDocument();
    const attempts = screen.getAllByTestId('wh-attempt-row');
    expect(attempts.length).toBe(5);
    expect(attempts[0]).toHaveTextContent('failed');
    expect(attempts[0]).toHaveTextContent('HTTP 500');
    expect(attempts[3]).toHaveTextContent('no response');
    expect(attempts[4]).toHaveTextContent('dead-lettered');
    expect(screen.getByTestId('wh-state-badge')).toHaveTextContent('dead-lettered');
    expect(screen.queryByTestId('wh-countdown')).not.toBeInTheDocument();
  });

  it('renders receipts with signature verdicts and the duplicate flag', async () => {
    stubApiFetch([
      { url: SUBS_URL, body: { subscriptions: [SUBSCRIPTION] } },
      { url: DELIVERIES_URL, body: { subscriptionId: 'sub_demo1', deliveries: [] } },
      { url: RECEIPTS_URL, body: RECEIPTS },
    ]);

    render(<WebhookSimulatorDemo />);

    const rows = await screen.findAllByTestId('wh-receipt-row');
    expect(rows.length).toBe(2);
    const verdicts = screen.getAllByTestId('wh-receipt-verdict');
    expect(verdicts[0]).toHaveTextContent('invalid');
    expect(verdicts[1]).toHaveTextContent('verified');
    expect(rows[0]).toHaveTextContent('401');
    expect(rows[1]).toHaveTextContent('duplicate');
    expect(rows[1]).toHaveTextContent('hmac-sha256 match');
  });

  it('surfaces the server 400 through the error panel when the event type is unsubscribed', async () => {
    stubApiFetch([
      { url: SUBS_URL, body: { subscriptions: [SUBSCRIPTION] } },
      {
        url: FIRE_URL,
        status: 400,
        body: {
          error: {
            code: 'validation_failed',
            message: 'subscription sub_demo1 listens to [invoice.paid], not customer.deleted',
            requestId: 'req-wh-7',
          },
        },
      },
      { url: DELIVERIES_URL, body: { subscriptionId: 'sub_demo1', deliveries: [] } },
      { url: RECEIPTS_URL, body: { receipts: [] } },
    ]);

    render(<WebhookSimulatorDemo />);
    await screen.findByTestId('wh-sub-row');

    fireEvent.change(screen.getByTestId('wh-event-input'), {
      target: { value: 'customer.deleted' },
    });
    fireEvent.click(screen.getByTestId('wh-fire'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('listens to');
    expect(alert).toHaveTextContent('validation_failed · status 400 · request req-wh-7');
  });
});

/** Minimal JSON response stand-in, same contract the shared stub provides. */
function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
