import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ArenaDemo from './arena_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ArenaDemo spec — the validation arena over the shared guarded pipeline:
 * the accepted verdict renders per-field rows; the kitchen-sink preset
 * actually POSTs its payload (asserted on a capturing fetch stub) and the
 * verdict table shows every violation class with its annotation; the
 * envelope preview renders the strict endpoint's 400 shape; the strict
 * endpoint's real error envelope surfaces through the error panel; and a
 * malformed payload is sent as-is (the server judges, not the client).
 */

const ANALYST_URL = '/api/demo/validation/arena';
const STRICT_URL = '/api/demo/validation/arena/strict';

const acceptedPayload = {
  accepted: true,
  perField: [
    { field: 'name', valueClassification: 'accepted', violations: [] },
    { field: 'email', valueClassification: 'accepted', violations: [] },
    { field: 'score', valueClassification: 'accepted', violations: [] },
    { field: 'eventDate', valueClassification: 'accepted', violations: [] },
    { field: 'password', valueClassification: 'accepted', violations: [] },
    { field: 'confirmStart', valueClassification: 'accepted', violations: [] },
    { field: 'confirmEnd', valueClassification: 'accepted', violations: [] },
  ],
};

const rejectedPayload = {
  accepted: false,
  perField: [
    {
      field: 'name',
      valueClassification: 'missing or blank',
      violations: [{ annotation: 'NotBlank', message: 'name is required' }],
    },
    {
      field: 'score',
      valueClassification: 'outside numeric range',
      violations: [{ annotation: 'Max', message: 'score must be at most 100' }],
    },
    {
      field: 'password',
      valueClassification: 'policy violations',
      violations: [
        { annotation: 'StrongPassword', message: 'password needs an uppercase letter, a symbol' },
      ],
    },
    {
      field: 'confirm window (confirmStart, confirmEnd)',
      valueClassification: 'cross-field: confirmStart ≤ confirmEnd',
      violations: [
        {
          annotation: 'DateRange',
          message:
            'confirmation window needs both confirmStart and confirmEnd, confirmStart on or before confirmEnd',
        },
      ],
    },
  ],
  envelopeComparison: {
    error: {
      fields: { name: 'name is required', score: 'score must be at most 100' },
      code: 'validation_failed',
      message: 'request body failed validation',
      requestId: 'req-arena-1',
    },
  },
};

interface CapturedCall {
  url: string;
  init?: RequestInit;
}

/** Capturing fetch stub: records method/body, answers one payload. */
function stubCapturingFetch(payload: unknown): CapturedCall[] {
  const calls: CapturedCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: input.toString(), init });
      return {
        ok: true,
        status: 200,
        json: async () => payload,
      };
    }),
  );
  return calls;
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ArenaDemo', () => {
  it('renders the accepted verdict: every field row says accepted, no envelope', async () => {
    stubApiFetch([{ url: ANALYST_URL, body: acceptedPayload }]);

    render(<ArenaDemo />);

    await screen.findByTestId('arena-verdict-table');
    expect(screen.getByTestId('arena-accepted')).toHaveTextContent('accepted: true');
    const rows = screen.getAllByTestId('arena-field-row');
    expect(rows.length).toBe(7);
    expect(rows[0]).toHaveTextContent('accepted');
    expect(screen.queryByTestId('arena-envelope')).not.toBeInTheDocument();
  });

  it('the kitchen-sink preset POSTs its payload and renders every violation class', async () => {
    const calls = stubCapturingFetch(rejectedPayload);

    render(<ArenaDemo />);
    await screen.findByTestId('arena-verdict-table'); // initial load answered too
    const callsAfterLoad = calls.length;

    fireEvent.click(screen.getByTestId('arena-preset-kitchen-sink'));
    await waitFor(() =>
      expect(screen.getByTestId('arena-accepted')).toHaveTextContent('accepted: false'),
    );

    const sent = calls[calls.length - 1];
    if (sent === undefined) throw new Error('no fetch was captured after the preset click');
    expect(sent.url).toBe(ANALYST_URL);
    expect(sent.init?.method).toBe('POST');
    expect(String(sent.init?.body)).toContain('"not-an-email"');

    const table = screen.getByTestId('arena-verdict-table');
    expect(table).toHaveTextContent('@NotBlank');
    expect(table).toHaveTextContent('name is required');
    expect(table).toHaveTextContent('@Max');
    expect(table).toHaveTextContent('@StrongPassword');
    expect(table).toHaveTextContent('policy violations');
    expect(table).toHaveTextContent('@DateRange');
    expect(table).toHaveTextContent('confirm window (confirmStart, confirmEnd)');
    expect(calls.length).toBe(callsAfterLoad + 1);
  });

  it('renders the envelope preview beside a rejected verdict', async () => {
    stubApiFetch([{ url: ANALYST_URL, body: rejectedPayload }]);

    render(<ArenaDemo />);

    const envelope = await screen.findByTestId('arena-envelope');
    expect(envelope).toHaveTextContent('validation_failed');
    expect(envelope).toHaveTextContent('name is required');
    expect(envelope).toHaveTextContent('req-arena-1');
  });

  it('strict mode surfaces the real 400 envelope through the error panel', async () => {
    stubApiFetch([
      { url: ANALYST_URL, body: acceptedPayload },
      {
        url: STRICT_URL,
        status: 400,
        body: {
          error: {
            fields: { name: 'name is required' },
            code: 'validation_failed',
            message: 'request body failed validation',
            requestId: 'req-strict-9',
          },
        },
      },
    ]);

    render(<ArenaDemo />);
    await screen.findByTestId('arena-verdict-table');

    fireEvent.click(screen.getByTestId('arena-endpoint-strict'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('request body failed validation');
    expect(alert).toHaveTextContent('validation_failed · status 400 · request req-strict-9');
  });

  it('sends a malformed payload as-is — the server judges, not the client', async () => {
    const calls = stubCapturingFetch(acceptedPayload);

    render(<ArenaDemo />);
    await screen.findByTestId('arena-verdict-table');
    const callsAfterLoad = calls.length;

    fireEvent.click(screen.getByTestId('arena-preset-malformed'));

    await waitFor(() => expect(calls.length).toBe(callsAfterLoad + 1));
    const sent = calls[calls.length - 1];
    if (sent === undefined) throw new Error('no fetch was captured after the preset click');
    expect(String(sent.init?.body)).toBe('{"name": ');
  });
});
