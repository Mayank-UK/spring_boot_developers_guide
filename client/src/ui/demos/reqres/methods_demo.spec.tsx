import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import MethodsDemo from './methods_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * MethodsDemo spec — the verb matrix over the shared guarded pipeline: the
 * initial GET renders 200 + note + body; POST surfaces 201 + Location; PUT
 * surfaces 200 + ETag; PATCH renders 204 with the deliberate empty-body
 * note (a 204 has no JSON to parse — the response-shape reader must not
 * choke on it); DELETE renders 200 + confirmation. The stub mirrors what
 * the server actually answers, headers included.
 */

interface StubResponse {
  status: number;
  headers: Record<string, string>;
  body?: string;
}

/** Response-shape stub: answers every fetch with the scripted response (headers case-insensitive, like the real Headers). */
function stubMethodFetch(response: StubResponse): void {
  const headers: Record<string, string> = {};
  for (const [name, value] of Object.entries(response.headers)) {
    headers[name.toLowerCase()] = value;
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: response.status >= 200 && response.status < 300,
      status: response.status,
      headers: {
        get: (name: string) => headers[name.toLowerCase()] ?? null,
      },
      text: async () => response.body ?? '',
    })),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MethodsDemo', () => {
  it('renders the initial GET answer: 200, teaching note, representation body', async () => {
    stubMethodFetch({
      status: 200,
      headers: {
        'x-teaching-note': '200 OK — success with a representation',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        method: 'GET',
        semantics: 'safe · idempotent',
        facts: { resource: 'methods-demo', state: 'unchanged — a read' },
      }),
    });

    render(<MethodsDemo />);

    const response = await screen.findByTestId('reqres-response');
    expect(screen.getByTestId('reqres-status')).toHaveTextContent('200');
    expect(screen.getByTestId('reqres-note')).toHaveTextContent('200 OK — success');
    expect(response).toHaveTextContent('safe · idempotent');
    expect(screen.queryByTestId('reqres-empty-body')).not.toBeInTheDocument();
  });

  it('POST renders 201 with the Location header naming the created resource', async () => {
    stubMethodFetch({
      status: 200,
      headers: { 'x-teaching-note': '200 OK — success with a representation' },
      body: JSON.stringify({ method: 'GET', semantics: 'safe · idempotent', facts: {} }),
    });
    render(<MethodsDemo />);
    await screen.findByTestId('reqres-response');

    stubMethodFetch({
      status: 201,
      headers: {
        Location: '/api/demo/reqres/methods/resource-7',
        'x-teaching-note': '201 Created — Location names the new resource',
      },
      body: JSON.stringify({
        method: 'POST',
        semantics: 'not safe · not idempotent',
        facts: { created: 'resource-7', location: '/api/demo/reqres/methods/resource-7' },
      }),
    });

    fireEvent.click(screen.getByTestId('reqres-verb-post'));

    await waitFor(() => expect(screen.getByTestId('reqres-status')).toHaveTextContent('201'));
    expect(screen.getByTestId('reqres-location')).toHaveTextContent(
      '/api/demo/reqres/methods/resource-7',
    );
    expect(screen.getByTestId('reqres-note')).toHaveTextContent('201 Created');
    expect(screen.getByTestId('reqres-body')).toHaveTextContent('resource-7');
  });

  it('PUT renders 200 with the ETag header', async () => {
    stubMethodFetch({
      status: 200,
      headers: { ETag: '"v3"', 'x-teaching-note': '200 OK — replaced; ETag versions the new representation' },
      body: JSON.stringify({ method: 'PUT', semantics: 'not safe · idempotent', facts: { etag: '"v3"' } }),
    });

    render(<MethodsDemo />);
    fireEvent.click(screen.getByTestId('reqres-verb-put'));

    await waitFor(() => expect(screen.getByTestId('reqres-status')).toHaveTextContent('200'));
    expect(screen.getByTestId('reqres-etag')).toHaveTextContent('"v3"');
  });

  it('PATCH renders 204 with the deliberate empty body, without parsing JSON', async () => {
    stubMethodFetch({
      status: 204,
      headers: { 'x-teaching-note': '204 No Content — applied; deliberately nothing to say' },
      body: '',
    });

    render(<MethodsDemo />);
    fireEvent.click(screen.getByTestId('reqres-verb-patch'));

    await waitFor(() => expect(screen.getByTestId('reqres-status')).toHaveTextContent('204'));
    expect(screen.getByTestId('reqres-empty-body')).toHaveTextContent('empty');
    expect(screen.queryByTestId('reqres-body')).not.toBeInTheDocument();
  });

  it('DELETE renders 200 with the confirmation body', async () => {
    stubMethodFetch({
      status: 200,
      headers: { 'x-teaching-note': '200 OK — deleted, with a confirmation body' },
      body: JSON.stringify({
        method: 'DELETE',
        semantics: 'not safe · idempotent',
        facts: { deleted: 'true', secondCall: 'finds nothing to do' },
      }),
    });

    render(<MethodsDemo />);
    fireEvent.click(screen.getByTestId('reqres-verb-delete'));

    await waitFor(() => expect(screen.getByTestId('reqres-status')).toHaveTextContent('200'));
    expect(screen.getByTestId('reqres-body')).toHaveTextContent('deleted');
  });

  it('shows the error panel when the server answers with an error envelope', async () => {
    stubMethodFetch({
      status: 400,
      headers: {},
      body: JSON.stringify({
        error: { code: 'validation_failed', message: 'unsupported method: TRACE', requestId: 'req-1' },
      }),
    });

    render(<MethodsDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the verb matrix');
    expect(alert).toHaveTextContent('validation_failed · status 400 · request req-1');
    expect(screen.queryByTestId('reqres-response')).not.toBeInTheDocument();
  });
});
