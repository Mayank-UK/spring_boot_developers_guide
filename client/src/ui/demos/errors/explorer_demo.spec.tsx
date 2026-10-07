import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ErrorsExplorerDemo from './explorer_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * ErrorsExplorerDemo spec — the failure explorer over stubbed endpoints:
 * the catalog loads with its ten rows (eight kinds plus the two framework
 * rows, 404 and 405, with their reproduce hints); inject POSTs the chosen
 * kind and the includeDetails toggle, then renders the OBSERVED wire
 * answer — status + content-type + the envelope's code/message/requestId
 * (the failure is data here, not a transport error); the problem+json
 * probe renders the SAME failure in the RFC 9457 flat shape (no error
 * wrapper) and the side-by-side chip appears once both contracts have
 * answered; and a degraded server renders the error panel.
 */

interface Captured {
  url: string;
  method: string;
  body: unknown;
}

type Responder = (
  url: string,
  init?: RequestInit,
) => { ok: boolean; status: number; headers?: Headers; json: () => Promise<unknown> };

const ENVELOPE_CODE = 'conflict';
const ENVELOPE_MESSAGE = 'a post with slug "first-post" already exists';

function catalogBody() {
  const kinds: Array<[string, number, string, boolean]> = [
    ['VALIDATION', 400, 'validation_failed', true],
    ['UNAUTHORIZED', 401, 'unauthorized', true],
    ['FORBIDDEN', 403, 'forbidden', true],
    ['NOT_FOUND', 404, 'not_found', true],
    ['CONFLICT', 409, ENVELOPE_CODE, true],
    ['RATE_LIMITED', 429, 'rate_limited', true],
    ['UNAVAILABLE', 503, 'service_unavailable', true],
    ['INTERNAL', 500, 'internal_error', true],
    ['NoResourceFoundException', 404, 'not_found', false],
    ['HttpRequestMethodNotSupportedException', 405, 'method_not_allowed', false],
  ];
  return kinds.map(([kind, httpStatus, envelopeCode, injectable]) => ({
    kind,
    raisedBy: injectable ? `${kind.toLowerCase()}Exception` : 'framework (ErrorResponse)',
    httpStatus,
    envelopeCode,
    whenItFires: `when ${kind} fires`,
    exampleMessage: `example for ${kind}`,
    injectable,
    reproduce: injectable ? null : 'curl -i localhost:8080/api/definitely/not/routed',
  }));
}

function stubEndpoints(captured: Captured[], ok: boolean): Responder {
  // the demo reads response.headers.get('content-type'), so stubs carry Headers
  const headersOf = (contentType: string): Headers =>
    new Headers({ 'content-type': contentType });
  const respond: Responder = (url, init) => {
    captured.push({
      url,
      method: init?.method ?? 'GET',
      body: init?.body ? (JSON.parse(String(init.body)) as unknown) : null,
    });
    if (url.endsWith('/demo/errors/catalog')) {
      if (!ok) {
        return {
          ok: false,
          status: 503,
          headers: headersOf('application/json'),
          json: async () => ({
            error: {
              code: 'service_unavailable',
              message: 'The failure catalog is unreachable',
              requestId: 'stub',
            },
          }),
        };
      }
      return {
        ok: true,
        status: 200,
        headers: headersOf('application/json'),
        json: async () => catalogBody(),
      };
    }
    if (url.endsWith('/demo/errors/inject')) {
      return {
        ok: false,
        status: 409,
        headers: headersOf('application/json'),
        json: async () => ({
          error: {
            code: ENVELOPE_CODE,
            message: ENVELOPE_MESSAGE,
            requestId: 'req-409',
            slug: 'first-post',
          },
        }),
      };
    }
    if (url.includes('/demo/errors/problem-detail')) {
      return {
        ok: false,
        status: 409,
        headers: headersOf('application/problem+json'),
        json: async () => ({
          type: 'https://errors.springboot-developers-guide.dev/conflict',
          title: 'Conflict',
          status: 409,
          detail: ENVELOPE_MESSAGE,
          code: ENVELOPE_CODE,
          requestId: 'req-409',
          slug: 'first-post',
        }),
      };
    }
    return {
      ok: true,
      status: 200,
      headers: headersOf('application/json'),
      json: async () => ({}),
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

describe('ErrorsExplorerDemo', () => {
  it('loads the ten-row catalog including the two framework rows', async () => {
    stubEndpoints([], true);

    render(<ErrorsExplorerDemo />);

    const catalog = await screen.findByTestId('errors-catalog');
    expect(catalog).toHaveTextContent('CONFLICT → 409 conflict');
    expect(catalog).toHaveTextContent('VALIDATION → 400 validation_failed');
    const unrouted = screen.getByTestId('errors-row-NoResourceFoundException');
    expect(unrouted).toHaveTextContent('404 not_found');
    expect(unrouted).toHaveTextContent('curl -i localhost:8080/api/definitely/not/routed');
    expect(screen.getByTestId('errors-row-HttpRequestMethodNotSupportedException')).toHaveTextContent(
      '405 method_not_allowed',
    );
  });

  it('injects the chosen kind and renders the observed envelope as data', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<ErrorsExplorerDemo />);
    await screen.findByTestId('errors-row-CONFLICT');
    fireEvent.change(screen.getByTestId('errors-kind-select'), { target: { value: 'CONFLICT' } });
    fireEvent.change(screen.getByTestId('errors-details-toggle'), {
      target: { checked: true },
    });
    fireEvent.click(screen.getByTestId('errors-inject'));

    const observed = await screen.findByTestId('errors-observed');
    expect(observed).toHaveTextContent('409 · application/json');
    expect(observed).toHaveTextContent(`code: ${ENVELOPE_CODE}`);
    expect(observed).toHaveTextContent(`message: ${ENVELOPE_MESSAGE}`);
    expect(observed).toHaveTextContent('requestId: req-409');
    expect(observed).toHaveTextContent('slug: first-post');
    const posts = captured.filter((call) => call.url.endsWith('/demo/errors/inject'));
    expect(posts[0]?.body).toEqual({ kind: 'CONFLICT', includeDetails: true });
  });

  it('renders the same failure through the problem+json contract beside the envelope', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<ErrorsExplorerDemo />);
    await screen.findByTestId('errors-row-CONFLICT');
    fireEvent.click(screen.getByTestId('errors-inject'));
    fireEvent.click(screen.getByTestId('errors-problem'));

    const problem = await screen.findByTestId('errors-problem-observed');
    expect(problem).toHaveTextContent('409 · application/problem+json');
    expect(problem).toHaveTextContent(`detail: ${ENVELOPE_MESSAGE}`);
    expect(problem).toHaveTextContent(`code: ${ENVELOPE_CODE}`);
    expect(problem).toHaveTextContent('title: Conflict');
    const probes = captured.filter((call) => call.url.includes('/demo/errors/problem-detail'));
    expect(probes[0]?.url).toContain('kind=CONFLICT');
    expect(screen.getByTestId('errors-same-failure')).toBeDefined();
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], false);

    render(<ErrorsExplorerDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('The failure catalog is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
