import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import MatrixDemo from './matrix_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * MatrixDemo spec — the authz island over stubbed routes: the role→action
 * matrix renders from the server's real policy data; act-as switches the
 * session identity (username in the POST body, credentials: 'include' so
 * the cookie jar carries the session); the document list RE-FILTERS per
 * caller (bob never sees alice's private rows); a guarded read that is
 * denied renders as the error envelope — status 403, code forbidden, a
 * requestId — labeled with the enforcement layer that fired.
 */

const MATRIX_URL = '/api/demo/authz/matrix';
const ACT_AS_URL = '/api/demo/authz/act-as';
const DOCUMENTS_URL = '/api/demo/authz/documents';

const MATRIX_OK = {
  roleMatrix: [
    { role: 'reader', allowedActions: ['read', 'list'] },
    { role: 'staff', allowedActions: ['read', 'list', 'publish'] },
    { role: 'admin', allowedActions: ['read', 'list', 'publish'] },
  ],
  users: [
    { username: 'alice', roles: ['admin', 'staff'], actionSummary: ['read', 'list', 'publish'] },
    { username: 'bob', roles: ['staff'], actionSummary: ['read', 'list', 'publish'] },
    { username: 'carol', roles: ['reader'], actionSummary: ['read', 'list'] },
  ],
};

function documentsBody(ids: string[]): { documents: unknown[] } {
  return {
    documents: ids.map((id) => ({
      id,
      title: `title ${id}`,
      owner: id.includes('alice') ? 'alice' : id.includes('bob') ? 'bob' : 'carol',
      visibility: 'private',
      reason: 'stub reason',
    })),
  };
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MatrixDemo', () => {
  it('renders the role matrix and user summaries from the server data', async () => {
    stubApiFetch([{ url: MATRIX_URL, body: MATRIX_OK }]);

    render(<MatrixDemo />);

    const rows = await screen.findAllByTestId('authz-role-row');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('reader');
    expect(rows[0]).toHaveTextContent('read, list');
    expect(rows[1]).toHaveTextContent('staff');
    expect(rows[1]).toHaveTextContent('read, list, publish');
    const users = screen.getAllByTestId('authz-user-row');
    expect(users[2]).toHaveTextContent('carol (reader): read, list');
  });

  it('acts as bob: username in the body, session cookie inclusion, filtered list', async () => {
    let actAsBody = '';
    let actAsInit: RequestInit | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(ACT_AS_URL)) {
          actAsBody = String(init?.body);
          actAsInit = init;
          return jsonResponse({ user: { username: 'bob', roles: ['staff'] } });
        }
        if (url.endsWith(DOCUMENTS_URL)) {
          return jsonResponse(
            documentsBody(['doc-alice-team', 'doc-bob-private', 'doc-roadmap']),
          );
        }
        if (url.endsWith(MATRIX_URL)) return jsonResponse(MATRIX_OK);
        return jsonResponse(
          { error: { code: 'not_found', message: 'no stub', requestId: 'x' } },
          404,
        );
      }),
    );

    render(<MatrixDemo />);
    await screen.findAllByTestId('authz-user-row');
    fireEvent.click(screen.getByTestId('authz-act-bob'));

    await screen.findAllByTestId('authz-doc-row');
    expect(actAsBody).toContain('"username":"bob"');
    expect(actAsInit?.method).toBe('POST');
    const init = actAsInit as RequestInit & { credentials?: RequestCredentials };
    expect(init.credentials).toBe('include');
    const docs = screen.getAllByTestId('authz-doc-row');
    expect(docs).toHaveLength(3);
    for (const row of docs) expect(row).toHaveTextContent('doc-');
    expect(screen.queryByText(/doc-alice-private/)).not.toBeInTheDocument();
  });

  it('renders a denied guarded read as the 403 envelope with code, requestId, and layer label', async () => {
    stubApiFetch([
      { url: MATRIX_URL, body: MATRIX_OK },
      { url: ACT_AS_URL, body: { user: { username: 'bob', roles: ['staff'] } } },
      { url: DOCUMENTS_URL, body: documentsBody(['doc-bob-private']) },
      {
        url: '/api/demo/authz/documents/doc-alice-private',
        status: 403,
        body: {
          error: {
            code: 'forbidden',
            message: 'private to alice (and admins) — caller is neither',
            requestId: 'req-403',
            checkedBy: 'ownership-policy',
          },
        },
      },
    ]);

    render(<MatrixDemo />);
    await screen.findAllByTestId('authz-user-row');
    fireEvent.click(screen.getByTestId('authz-act-bob'));
    await screen.findByTestId('authz-doc-row');
    fireEvent.click(screen.getByTestId('authz-probe-read'));

    const result = await screen.findByTestId('authz-probe-result');
    expect(result).toHaveTextContent('403 forbidden');
    expect(result).toHaveTextContent('private to alice');
    expect(result).toHaveTextContent('requestId req-403');
    expect(result).toHaveTextContent('in-use-case policy');
  });

  it('records the anonymous case: the URL-rule layer answered before any code ran', async () => {
    stubApiFetch([
      { url: MATRIX_URL, body: MATRIX_OK },
      { url: ACT_AS_URL, body: { user: { username: 'carol', roles: ['reader'] } } },
      {
        url: DOCUMENTS_URL,
        status: 401,
        body: { error: { code: 'unauthorized', message: 'anonymous', requestId: 'req-401' } },
      },
    ]);

    render(<MatrixDemo />);
    await screen.findAllByTestId('authz-user-row');
    fireEvent.click(screen.getByTestId('authz-act-carol'));

    await screen.findByText(/URL-rule layer answered 401 before any code ran/);
  });

  it('renders the self-invocation trap verdict: direct checked and denied, internal unchecked', async () => {
    stubApiFetch([
      { url: MATRIX_URL, body: MATRIX_OK },
      { url: ACT_AS_URL, body: { user: { username: 'bob', roles: ['staff'] } } },
      { url: DOCUMENTS_URL, body: documentsBody(['doc-carol-private']) },
      {
        url: '/api/demo/authz/specimens/self-invocation',
        body: {
          caller: { username: 'bob' },
          directCall: { checked: true, allowed: false },
          internalCall: { checked: false, allowed: true, returned: 'note' },
        },
      },
    ]);

    render(<MatrixDemo />);
    await screen.findAllByTestId('authz-user-row');
    fireEvent.click(screen.getByTestId('authz-act-bob'));
    await screen.findByTestId('authz-doc-row');
    fireEvent.click(screen.getByTestId('authz-probe-trap'));

    const trap = await screen.findByTestId('authz-trap-result');
    expect(trap).toHaveTextContent('direct call checked → denied');
    expect(trap).toHaveTextContent('internal call unchecked → ALLOWED (the check never ran)');
  });
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
