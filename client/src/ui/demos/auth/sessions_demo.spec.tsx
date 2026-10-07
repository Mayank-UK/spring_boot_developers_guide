import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import SessionsDemo from './sessions_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * SessionsDemo spec — the login-flow console over stubbed routes: the
 * login POST carries the typed credentials (and credentials: 'include'
 * so the cookie jar plays along); a wrong password surfaces the 401 as
 * a flow event (one shape, no oracle); /me renders the principal from
 * the session; logout clears it and /me afterward records the chain's
 * 401. The cookie's configured attributes render beside the login.
 */

const LOGIN_URL = '/api/demo/auth/session/login';
const ME_URL = '/api/demo/auth/session/me';
const LOGOUT_URL = '/api/demo/auth/session/logout';

const LOGIN_OK = {
  user: { id: 'usr_alice', username: 'alice', roles: ['admin', 'staff'] },
  via: 'session',
  session: {
    cookie: { name: 'JSESSIONID', httpOnly: true, sameSite: 'lax', secure: false },
    fixationProtected: true,
  },
};

const ME_OK = { user: { id: 'usr_alice', username: 'alice', roles: ['admin', 'staff'] }, via: 'session' };

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SessionsDemo', () => {
  it('logs in and renders the user, cookie attributes, and the me probe', async () => {
    stubApiFetch([
      { url: LOGIN_URL, body: LOGIN_OK },
      { url: ME_URL, body: ME_OK },
    ]);

    render(<SessionsDemo />);
    fireEvent.click(screen.getByTestId('authn-s-login'));

    const card = await screen.findByTestId('authn-s-login-response');
    expect(card).toHaveTextContent('alice');
    expect(card).toHaveTextContent('admin, staff');
    expect(card).toHaveTextContent('httpOnly=true');
    expect(card).toHaveTextContent('sameSite=lax');
    expect(card).toHaveTextContent('fixationProtected=true');
    await screen.findByTestId('authn-s-me-result');
    expect(screen.getByTestId('authn-s-me-result')).toHaveTextContent('via session');
  });

  it('sends the typed credentials and requests cookie inclusion', async () => {
    let sent: { url: string; init?: RequestInit } | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(LOGIN_URL)) {
          sent = { url, init: _init };
          return jsonResponse(LOGIN_OK);
        }
        if (url.endsWith(ME_URL)) return jsonResponse(ME_OK, 401);
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<SessionsDemo />);
    fireEvent.change(screen.getByTestId('authn-s-password'), {
      target: { value: 'not-the-password' },
    });
    fireEvent.click(screen.getByTestId('authn-s-login'));

    await waitFor(() => expect(sent).toBeDefined());
    expect(sent?.init?.method).toBe('POST');
    const init = sent?.init as RequestInit & { credentials?: RequestCredentials };
    expect(init.credentials).toBe('include');
    expect(String(init.body)).toContain('"username":"alice"');
    expect(String(init.body)).toContain('"password":"not-the-password"');
  });

  it('renders the wrong-password 401 as a flow event — one shape, no oracle', async () => {
    stubApiFetch([
      {
        url: LOGIN_URL,
        status: 401,
        body: { error: { code: 'unauthorized', message: 'invalid credentials', requestId: 'req-1' } },
      },
    ]);

    render(<SessionsDemo />);
    fireEvent.click(screen.getByTestId('authn-s-login'));

    const event = await screen.findByTestId('authn-s-event');
    expect(event).toHaveTextContent('401');
    expect(event).toHaveTextContent('invalid credentials (one shape, no oracle)');
    expect(screen.queryByTestId('authn-s-login-response')).not.toBeInTheDocument();
  });

  it('logout clears the principal and the next me is the chain 401', async () => {
    let loggedIn = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(LOGIN_URL)) return jsonResponse(LOGIN_OK);
        if (url.endsWith(ME_URL)) return jsonResponse(loggedIn ? ME_OK : unauthorized(), loggedIn ? 200 : 401);
        if (url.endsWith(LOGOUT_URL)) {
          loggedIn = false;
          return jsonResponse({ loggedOut: true, sessionInvalidated: true });
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<SessionsDemo />);
    fireEvent.click(screen.getByTestId('authn-s-login'));
    await screen.findByTestId('authn-s-me-result');

    fireEvent.click(screen.getByTestId('authn-s-logout'));
    await waitFor(() =>
      expect(screen.queryByTestId('authn-s-login-response')).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId('authn-s-me'));
    await waitFor(() =>
      expect(
        screen
          .getAllByTestId('authn-s-event')
          .some((event) => event.textContent?.includes('refused the anonymous request')),
      ).toBe(true),
    );
  });
});

function unauthorized() {
  return { error: { code: 'unauthorized', message: 'anonymous', requestId: 'req-2' } };
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
