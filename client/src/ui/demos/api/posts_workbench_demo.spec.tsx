import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import PostsWorkbenchDemo from './posts_workbench_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * PostsWorkbenchDemo spec — the posts workbench over stubbed fetch:
 * the seeded listing renders with honest paging metadata; the next-page
 * control drives the page query param and the status filter lands in the
 * URL; creating POSTs the judged payload and refreshes the listing; a 409
 * from a stale-version PUT renders the readable conflict panel (both
 * versions); an empty title is stopped client-side; and the ScenarioStore
 * toggle PATCHes /demo/scenario, after which the listing meets the injected
 * 503 envelope.
 */

const LIST_URL = '/api/demo/api/posts?page=0&size=5';
const LIST_URL_DRAFT = '/api/demo/api/posts?page=0&size=5&status=draft';
const LIST_URL_PAGE1 = '/api/demo/api/posts?page=1&size=5';
const CREATE_URL = '/api/demo/api/posts';
const SCENARIO_URL = '/api/demo/scenario';

interface CapturedCall {
  url: string;
  method: string;
  body?: string;
}

function post(id: string, title: string, version = 1): Record<string, unknown> {
  return {
    id,
    title,
    body: `body of ${title}`,
    tags: ['api-design'],
    status: 'published',
    version,
    createdAt: '2026-09-12T09:00:00Z',
    updatedAt: '2026-09-12T09:00:00Z',
  };
}

function paged(items: Record<string, unknown>[], page: number, totalPages: number) {
  return { items, page, size: 5, totalElements: 12, totalPages };
}

/** Capturing stub: records calls, answers by URL with an ordered answer queue per URL. */
function stubCapturingFetch(answersByRoute: Record<string, unknown[]>): CapturedCall[] {
  const calls: CapturedCall[] = [];
  const served: Record<string, number> = {};
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body === undefined ? undefined : String(init?.body) });
      const queue = answersByRoute[url];
      if (queue === undefined || queue.length === 0) {
        return {
          ok: false,
          status: 404,
          json: async () => ({
            error: { code: 'not_found', message: `no stub for ${method} ${url}`, requestId: 'stub' },
          }),
        };
      }
      const index = Math.min(served[url] ?? 0, queue.length - 1);
      served[url] = index + 1;
      const body = queue[index] as { __status?: number } & Record<string, unknown>;
      const status = typeof body.__status === 'number' ? body.__status : 200;
      const payload = body.__status === undefined ? body : (body['payload'] as unknown);
      return { ok: status >= 200 && status < 300, status, json: async () => payload };
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

describe('PostsWorkbenchDemo', () => {
  it('renders the listing with honest paging metadata', async () => {
    stubApiFetch([
      {
        url: LIST_URL,
        body: paged([post('p1', 'The uniform interface'), post('p2', 'Nouns, not verbs')], 0, 3),
      },
    ]);

    render(<PostsWorkbenchDemo />);

    await screen.findByTestId('posts-table');
    const rows = screen.getAllByTestId('posts-row');
    expect(rows.length).toBe(2);
    expect(rows[0]).toHaveTextContent('The uniform interface');
    expect(screen.getByTestId('posts-page-indicator')).toHaveTextContent('page 1 / 3');
  });

  it('drives the page and status query params through the controls', async () => {
    stubApiFetch([
      { url: LIST_URL, body: paged([post('p1', 'Page one post')], 0, 2) },
      { url: LIST_URL_PAGE1, body: paged([post('p2', 'Page two post')], 1, 2) },
      { url: LIST_URL_DRAFT, body: paged([post('p3', 'A draft post')], 0, 1) },
    ]);

    render(<PostsWorkbenchDemo />);
    await screen.findByTestId('posts-table');

    fireEvent.click(screen.getByTestId('posts-next-page'));
    await waitFor(() =>
      expect(screen.getAllByTestId('posts-row')[0]).toHaveTextContent('Page two post'),
    );

    fireEvent.click(screen.getByTestId('posts-prev-page'));
    await waitFor(() =>
      expect(screen.getAllByTestId('posts-row')[0]).toHaveTextContent('Page one post'),
    );

    fireEvent.change(screen.getByTestId('posts-status-filter'), { target: { value: 'draft' } });
    await waitFor(() =>
      expect(screen.getAllByTestId('posts-row')[0]).toHaveTextContent('A draft post'),
    );
  });

  it('creates through POST and refreshes the listing', async () => {
    const calls = stubCapturingFetch({
      [LIST_URL]: [
        paged([post('p1', 'Before the create')], 0, 2),
        paged([post('p1', 'Before the create'), post('p2', 'Freshly created')], 0, 2),
      ],
      [CREATE_URL]: [{ __status: 201, payload: post('p2', 'Freshly created') }],
    });

    render(<PostsWorkbenchDemo />);
    await screen.findByTestId('posts-table');
    const callsBeforeCreate = calls.length;

    fireEvent.click(screen.getByTestId('posts-new'));
    fireEvent.change(screen.getByTestId('posts-title-input'), {
      target: { value: 'Freshly created' },
    });
    fireEvent.click(screen.getByTestId('posts-save'));

    await waitFor(() => {
      expect(screen.queryByTestId('posts-editor')).not.toBeInTheDocument();
      expect(screen.getAllByTestId('posts-row').length).toBe(2);
    });

    const created = calls.find((call) => call.url === CREATE_URL && call.method === 'POST');
    expect(created).toBeDefined();
    const sent = JSON.parse(created?.body ?? '{}') as { title?: string; status?: string };
    expect(sent.title).toBe('Freshly created');
    expect(sent.status).toBe('draft');
    // one POST, then one list refresh
    expect(calls.length).toBe(callsBeforeCreate + 2);
  });

  it('surfaces a stale-version 409 as the readable conflict panel', async () => {
    stubCapturingFetch({
      [LIST_URL]: [paged([post('p-contested', 'The contested post', 3)], 0, 1)],
      ['/api/demo/api/posts/p-contested']: [
        {
          __status: 409,
          payload: {
            error: {
              code: 'conflict',
              message:
                'post "p-contested" changed since you read it: expected version 3, current version 5',
              requestId: 'req-conflict-1',
              expectedVersion: 3,
              currentVersion: 5,
            },
          },
        },
      ],
    });

    render(<PostsWorkbenchDemo />);
    await screen.findByTestId('posts-table');

    fireEvent.click(screen.getByTestId('posts-edit'));
    fireEvent.change(screen.getByTestId('posts-title-input'), {
      target: { value: 'The contested post, my edit' },
    });
    fireEvent.click(screen.getByTestId('posts-save'));

    const conflict = await screen.findByTestId('posts-conflict');
    expect(conflict).toHaveTextContent('someone updated it first');
    expect(conflict).toHaveTextContent('version 3');
    expect(conflict).toHaveTextContent('version 5');
    expect(screen.getByTestId('posts-editor')).toBeInTheDocument();
  });

  it('rejects an empty title client-side — the server never sees it', async () => {
    const calls = stubCapturingFetch({ [LIST_URL]: [paged([post('p1', 'Any post')], 0, 1)] });

    render(<PostsWorkbenchDemo />);
    await screen.findByTestId('posts-table');
    const callsBeforeSave = calls.length;

    fireEvent.click(screen.getByTestId('posts-new'));
    fireEvent.click(screen.getByTestId('posts-save'));

    const notice = await screen.findByTestId('posts-notice');
    expect(notice).toHaveTextContent('a post needs a title');
    // no POST left the client for a titleless draft
    expect(calls.length).toBe(callsBeforeSave);
  });

  it('toggles scenario failure injection and meets the injected 503', async () => {
    let failureInjected = false;
    const calls: CapturedCall[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        const method = init?.method ?? 'GET';
        calls.push({ url, method, body: init?.body === undefined ? undefined : String(init?.body) });
        if (url === SCENARIO_URL) {
          failureInjected = JSON.parse(String(init?.body)).injectFailure === true;
          return {
            ok: true,
            status: 200,
            json: async () => ({ dataMode: 'fake', injectFailure: failureInjected }),
          };
        }
        if (url === LIST_URL) {
          if (failureInjected) {
            return {
              ok: false,
              status: 503,
              json: async () => ({
                error: {
                  code: 'service_unavailable',
                  message: 'posts API is unavailable by scenario (injectFailure=true)',
                  requestId: 'req-503',
                  area: 'api',
                },
              }),
            };
          }
          return { ok: true, status: 200, json: async () => paged([post('p1', 'Healthy listing')], 0, 1) };
        }
        return {
          ok: false,
          status: 404,
          json: async () => ({
            error: { code: 'not_found', message: `no stub for ${method} ${url}`, requestId: 'stub' },
          }),
        };
      }),
    );

    render(<PostsWorkbenchDemo />);
    await screen.findByTestId('posts-table');

    fireEvent.click(screen.getByTestId('posts-inject-failure'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('posts API is unavailable by scenario');
    expect(alert).toHaveTextContent('service_unavailable · status 503 · request req-503');
    expect(screen.getByTestId('posts-inject-failure')).toHaveAttribute('aria-pressed', 'true');

    const patch = calls.find((call) => call.url === SCENARIO_URL);
    expect(patch?.method).toBe('PATCH');
    expect(String(patch?.body)).toContain('"injectFailure":true');
  });
});
