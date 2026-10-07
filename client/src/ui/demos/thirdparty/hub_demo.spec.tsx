import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import HubDemo from './hub_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * HubDemo spec — the third-party hub over stubbed endpoints: the report
 * paints one card per integration (posture, last outcome, quota, key
 * VERDICT), triggers POST their operation and patch the answered row,
 * the quota dial rides integration/limit on the URL, and a degraded
 * server renders the error panel. Every string asserted here comes from
 * the mocked SERVER answer — the component narrates nothing it did not
 * fetch, and no key VALUE ever appears in any answer.
 */

interface Captured {
  url: string;
  method: string;
}

function view(overrides?: {
  id?: string;
  posture?: string;
  lastOutcome?: string;
  quotaState?: string;
  callsUsed?: number;
  quotaLimit?: number;
}) {
  const id = overrides?.id ?? 'mail';
  const operation = id === 'payments' ? 'charge' : id === 'storage' ? 'put' : 'send';
  return {
    id,
    title: id === 'mail' ? 'Spring Mail' : id === 'payments' ? 'Payments (Stripe-shaped)' : 'Object storage (S3-shaped)',
    what: 'one integration behind a port',
    posture: overrides?.posture ?? 'fake',
    lastOperation: overrides?.lastOutcome === undefined ? null : operation,
    lastOutcome: overrides?.lastOutcome ?? 'idle',
    lastDetail: overrides?.lastOutcome === undefined ? null : 'in-process fake accepted mail',
    callsUsed: overrides?.callsUsed ?? 0,
    quotaLimit: overrides?.quotaLimit ?? 8,
    quotaState: overrides?.quotaState ?? 'healthy',
    keyEnvVar: `GUIDE_THIRDPARTY_${id.toUpperCase()}_KEY`,
    keyPresentFromEnv: false,
    keyVerdict: `GUIDE_THIRDPARTY_${id.toUpperCase()}_KEY is not set — nothing to leak: the in-process fake needs no credential`,
    notes: id === 'mail' ? ['a REAL JavaMailSender-backed adapter is constructed in this context'] : [],
  };
}

function hub() {
  return {
    integrations: [view(), view({ id: 'payments' }), view({ id: 'storage' })],
    reading: [
      'posture per integration: fake = in-process adapter',
      'quota is PER-INTEGRATION state: dial one down and its siblings keep answering',
      'key handling: verdicts only, never values',
    ],
  };
}

type Down = { current: boolean };

/** One stub for a whole test: GET answers the hub report, POST answers the operation/dial body (or 503). */
function stubEndpoint(
  captured: Captured[],
  down: Down,
  bodies: { get?: () => unknown; post?: () => unknown },
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
            error: { code: 'service_unavailable', message: 'hub unreachable', requestId: 'x' },
          }),
        };
      }
      const body = method === 'GET' ? bodies.get : bodies.post;
      return { ok: true, status: 200, json: async () => body?.() ?? hub() };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('HubDemo', () => {
  it('paints one card per integration: posture, outcome, quota, key verdict — never a value', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, { get: () => hub() });

    render(<HubDemo />);

    const cards = await screen.findAllByTestId('tp-card');
    expect(cards).toHaveLength(3);
    expect(screen.getByTestId('tp-posture-mail')).toHaveTextContent('fake');
    expect(screen.getByTestId('tp-outcome-mail')).toHaveTextContent('idle');
    expect(screen.getByTestId('tp-quota-payments')).toHaveTextContent('quota 0/8 (healthy)');
    expect(screen.getByTestId('tp-key-storage')).toHaveTextContent(
      'GUIDE_THIRDPARTY_STORAGE_KEY is not set — nothing to leak',
    );
    expect(screen.getByTestId('tp-reading-1')).toHaveTextContent(
      'dial one down and its siblings keep answering',
    );
    expect(captured[0]?.url).toContain('/demo/thirdparty/hub');
  });

  it('POSTs a trigger and patches the answered row plus the receipt flash', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, {
      get: () => hub(),
      post: () => ({
        outcome: 'sent',
        messageId: 'demo-mail-1',
        detail: 'in-process fake accepted mail to friend@example.com',
        after: view({ id: 'mail', lastOutcome: 'sent', callsUsed: 1 }),
      }),
    });

    render(<HubDemo />);
    await screen.findAllByTestId('tp-card');

    fireEvent.click(screen.getByTestId('tp-run-mail'));

    expect(await screen.findByTestId('tp-action')).toHaveTextContent('mail: send mail → sent');
    expect(screen.getByTestId('tp-outcome-mail')).toHaveTextContent('send → sent');
    expect(screen.getByTestId('tp-quota-mail')).toHaveTextContent('quota 1/8 (healthy)');
    const post = captured.find((call) => call.method === 'POST');
    expect(post?.url).toContain('/demo/thirdparty/mail');
    expect(post?.url).toContain('to=friend@example.com');
  });

  it('rides the quota dial on the URL and paints blast radius: one exhausted, siblings healthy', async () => {
    const captured: Captured[] = [];
    let step = 0;
    stubEndpoint(captured, { current: false }, {
      get: () => hub(),
      post: () => {
        step += 1;
        if (step === 1) {
          return {
            changed: 'quota for mail is now 1 call (used 1)',
            after: view({ id: 'mail', lastOutcome: 'rate_limited', quotaState: 'exhausted', callsUsed: 1, quotaLimit: 1 }),
          };
        }
        return {
          outcome: 'succeeded',
          detail: 'charged 1999 usd — in-process fake',
          after: view({ id: 'payments', lastOutcome: 'succeeded', callsUsed: 4, quotaState: 'healthy' }),
        };
      },
    });

    render(<HubDemo />);
    await screen.findAllByTestId('tp-card');

    fireEvent.click(screen.getByTestId('tp-dial-mail')); // dial target: mail
    fireEvent.click(screen.getByTestId('tp-limit-1')); // limit 1
    fireEvent.click(screen.getByTestId('tp-dial-apply'));

    expect(await screen.findByTestId('tp-action')).toHaveTextContent('quota for mail is now 1 call');
    expect(screen.getByTestId('tp-outcome-mail')).toHaveTextContent('rate_limited');
    expect(screen.getByTestId('tp-quota-mail')).toHaveTextContent('quota 1/1 (exhausted)');
    expect(captured[1]?.url).toContain('/demo/thirdparty/quota?integration=mail&limit=1');

    // the blast-radius proof: the sibling still answers while mail is degraded
    fireEvent.click(screen.getByTestId('tp-run-payments'));
    expect(await screen.findByTestId('tp-action')).toHaveTextContent('payments: charge → succeeded');
    expect(screen.getByTestId('tp-outcome-payments')).toHaveTextContent('charge → succeeded');
    expect(screen.getByTestId('tp-quota-payments')).toHaveTextContent('(healthy)');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoint([], { current: true }, {});

    render(<HubDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('hub unreachable');
  });
});
