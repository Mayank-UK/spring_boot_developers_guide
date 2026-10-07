import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import InspectorDemo from './inspector_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * InspectorDemo spec — the reqres request inspector over the shared guarded
 * pipeline: the GET report renders both surfaces (bound values stamped with
 * their sugars, raw fields, attributes with notes, the summary), the POST
 * variant actually sends method/headers/cookie/body (asserted on a
 * capturing fetch stub) and renders the @RequestBody report, an invalid
 * builder body is refused client-side, and the server's error envelope
 * surfaces through the error panel.
 */

const GET_URL = '/api/demo/reqres/inspect?channel=inspector-demo';
const POST_URL = '/api/demo/reqres/inspect?channel=post-variant';

const getPayload = {
  method: 'GET',
  bound: {
    params: [
      { name: 'channel', value: 'inspector-demo', via: '@RequestParam' },
      { name: 'verbose', value: 'yes', via: '@RequestParam' },
    ],
    headers: [
      { name: 'Accept', value: 'application/json', via: '@RequestHeader' },
      { name: 'x-teaching-flag', value: 'hello-surface', via: '@RequestHeader' },
    ],
    cookies: [{ name: 'reqres-visit', value: 'first-visit', via: '@CookieValue' }],
    body: null,
  },
  raw: {
    requestURI: '/api/demo/reqres/inspect',
    queryString: 'channel=inspector-demo',
    authType: null,
    contentType: null,
    locales: ['en-US'],
  },
  attributes: [
    {
      name: 'requestId',
      value: 'req-42',
      note: "stamped by topic 08's request-id filter — middleware ran before any controller",
    },
  ],
  summary: {
    boundValues: 4,
    rawReads: 3,
    attributes: 1,
    notes: [
      'the query string was never parsed by hand — @RequestParam had already bound it',
      'a middleware attribute (requestId) rode along — the raw surface\u2019s exclusive cargo',
    ],
  },
};

const postPayload = {
  method: 'POST',
  bound: {
    params: [{ name: 'channel', value: 'post-variant', via: '@RequestParam' }],
    headers: [{ name: 'Content-Type', value: 'application/json', via: '@RequestHeader' }],
    cookies: [],
    body: { present: true, parsedAs: 'object', topLevelEntries: 2 },
  },
  raw: {
    requestURI: '/api/demo/reqres/inspect',
    queryString: 'channel=post-variant',
    authType: null,
    contentType: 'application/json',
    locales: ['en-US'],
  },
  attributes: [],
  summary: {
    boundValues: 3,
    rawReads: 4,
    attributes: 0,
    notes: ['@RequestBody bound the whole body after HttpMessageConverter negotiation'],
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

describe('InspectorDemo', () => {
  it('renders the GET report: sugars, raw surface, middleware attribute, summary', async () => {
    stubApiFetch([{ url: GET_URL, body: getPayload }]);

    render(<InspectorDemo />);

    const report = await screen.findByTestId('reqres-inspect-report');
    expect(screen.getByTestId('reqres-request-line')).toHaveTextContent(
      'GET /api/demo/reqres/inspect?channel=inspector-demo',
    );
    // Every bound value shows the annotation that carried it:
    expect(report).toHaveTextContent('@RequestParam');
    expect(report).toHaveTextContent('@RequestHeader');
    expect(report).toHaveTextContent('@CookieValue');
    // The raw half:
    expect(screen.getByTestId('reqres-raw-query')).toHaveTextContent('channel=inspector-demo');
    // The middleware trace + the summary verdict:
    expect(screen.getByTestId('reqres-attribute-row')).toHaveTextContent('requestId = req-42');
    expect(screen.getByTestId('reqres-attribute-row')).toHaveTextContent('request-id filter');
    expect(screen.getByTestId('reqres-summary')).toHaveTextContent('4 bound');
    expect(screen.getByTestId('reqres-summary')).toHaveTextContent('never parsed by hand');
  });

  it('POST sends method, headers, cookie, and body, then renders the @RequestBody report', async () => {
    const calls = stubCapturingFetch(postPayload);

    render(<InspectorDemo />);
    await screen.findByTestId('reqres-inspect-report'); // initial GET answered too

    fireEvent.change(screen.getByTestId('reqres-query-input'), {
      target: { value: 'channel=post-variant' },
    });
    fireEvent.click(screen.getByTestId('reqres-method-post'));

    await waitFor(() => expect(screen.getByTestId('reqres-request-line')).toHaveTextContent('POST'));

    const sent = calls[calls.length - 1];
    if (sent === undefined) throw new Error('no fetch was captured after clicking POST');
    expect(sent.url).toBe(POST_URL);
    expect(sent.init?.method).toBe('POST');
    const headers = sent.init?.headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers['x-teaching-flag']).toBe('hello-surface');
    expect(headers['Cookie']).toBe('reqres-visit=first-visit');
    expect(sent.init?.body).toBe('{"hello":"wire","count":3}');

    expect(screen.getByTestId('reqres-body-bound')).toHaveTextContent('object');
    expect(screen.getByTestId('reqres-body-bound')).toHaveTextContent('2 top-level entries');
  });

  it('refuses to POST a body that is not valid JSON, without fetching', async () => {
    const calls = stubCapturingFetch(getPayload);

    render(<InspectorDemo />);
    await screen.findByTestId('reqres-inspect-report');

    fireEvent.click(screen.getByTestId('reqres-method-post'));
    await screen.findByTestId('reqres-body-input');
    const callsAfterPost = calls.length;

    fireEvent.change(screen.getByTestId('reqres-body-input'), { target: { value: '{oops' } });
    fireEvent.click(screen.getByTestId('reqres-send'));

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

    render(<InspectorDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the inspector report');
    expect(alert).toHaveTextContent('internal_error · status 500 · request req-9');
    expect(screen.queryByTestId('reqres-inspect-report')).not.toBeInTheDocument();
  });
});
