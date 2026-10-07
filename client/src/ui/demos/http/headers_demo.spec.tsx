import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import HeadersDemo from './headers_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * HeadersDemo spec — the http-web inspector over the shared guarded
 * pipeline: the GET echo renders with classifications and the sensitive
 * flag, the POST variant actually sends method/headers/body (asserted on a
 * capturing fetch stub) and renders the Content-Type report, an invalid
 * builder body is refused client-side, and the server's error envelope
 * surfaces through the error panel.
 */

const GET_URL = '/api/demo/http/inspect?channel=headers-demo';
const POST_URL = '/api/demo/http/inspect?channel=post-variant';

const getPayload = {
  method: 'GET',
  path: '/api/demo/http/inspect',
  query: 'channel=headers-demo',
  headers: [
    { name: 'accept', value: 'application/json', category: 'negotiation', known: true, sensitive: false },
    { name: 'authorization', value: 'Bearer demo-token', category: 'auth', known: true, sensitive: true },
    { name: 'x-teaching-flag', value: 'hello-wire', category: 'other', known: false, sensitive: false },
  ],
  body: { present: false, contentType: null, mediaType: null, parsedAs: 'absent', topLevelEntries: 0 },
};

const postPayload = {
  method: 'POST',
  path: '/api/demo/http/inspect',
  query: 'channel=post-variant',
  headers: [
    { name: 'content-type', value: 'application/json', category: 'content', known: true, sensitive: false },
  ],
  body: {
    present: true,
    contentType: 'application/json',
    mediaType: 'application/json',
    parsedAs: 'json object',
    topLevelEntries: 2,
  },
};

interface CapturedCall {
  url: string;
  init?: RequestInit;
}

/** Capturing fetch stub: records method/headers/body, answers one payload. */
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

describe('HeadersDemo', () => {
  it('renders the annotated GET echo: categories, sensitive flag, no body', async () => {
    stubApiFetch([{ url: GET_URL, body: getPayload }]);

    render(<HeadersDemo />);

    const report = await screen.findByTestId('http-inspect-report');
    expect(screen.getByTestId('http-request-line')).toHaveTextContent(
      'GET /api/demo/http/inspect?channel=headers-demo',
    );
    expect(report).toHaveTextContent('negotiation');
    expect(report).toHaveTextContent('auth');
    expect(report).toHaveTextContent('other');
    expect(report).toHaveTextContent('not a standard name');
    expect(screen.getByTestId('http-sensitive-flag')).toHaveTextContent('sensitive');
    expect(screen.getByTestId('http-body-report')).toHaveTextContent('no body');
  });

  it('POST sends method, headers, and the JSON body, then renders the content-type report', async () => {
    const calls = stubCapturingFetch(postPayload);

    render(<HeadersDemo />);
    await screen.findByTestId('http-inspect-report'); // initial GET answered too

    fireEvent.change(screen.getByTestId('http-query-input'), {
      target: { value: 'channel=post-variant' },
    });
    fireEvent.click(screen.getByTestId('http-method-post'));

    await waitFor(() =>
      expect(screen.getByTestId('http-request-line')).toHaveTextContent('POST'),
    );

    const sent = calls[calls.length - 1];
    if (sent === undefined) throw new Error('no fetch was captured after clicking POST');
    expect(sent.url).toBe(POST_URL);
    expect(sent.init?.method).toBe('POST');
    const headers = sent.init?.headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers['x-teaching-flag']).toBe('hello-wire');
    expect(sent.init?.body).toBe('{"hello":"wire","count":3}');

    const bodyReport = screen.getByTestId('http-body-report');
    expect(bodyReport).toHaveTextContent('application/json');
    expect(bodyReport).toHaveTextContent('json object');
    expect(bodyReport).toHaveTextContent('2 top-level entries');
  });

  it('refuses to POST a body that is not valid JSON, without fetching', async () => {
    const calls = stubCapturingFetch(getPayload);

    render(<HeadersDemo />);
    await screen.findByTestId('http-inspect-report');

    // Switch to POST first — the body textarea only exists in POST mode, and
    // the switch itself sends once with the valid default body.
    fireEvent.click(screen.getByTestId('http-method-post'));
    await screen.findByTestId('http-body-input');
    const callsAfterPost = calls.length;

    fireEvent.change(screen.getByTestId('http-body-input'), { target: { value: '{oops' } });
    fireEvent.click(screen.getByTestId('http-send'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('not valid JSON');
    expect(calls.length).toBe(callsAfterPost);
  });

  it('shows the error panel when the server answers with an error envelope', async () => {
    stubApiFetch([
      {
        url: GET_URL,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'Internal server error', requestId: 'req-9' },
        },
      },
    ]);

    render(<HeadersDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the inspector report');
    expect(alert).toHaveTextContent('internal_error · status 500 · request req-9');
    expect(screen.queryByTestId('http-inspect-report')).not.toBeInTheDocument();
  });
});
