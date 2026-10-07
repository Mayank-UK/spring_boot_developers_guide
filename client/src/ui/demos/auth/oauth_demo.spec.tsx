import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import OAuthDemo from './oauth_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * OAuthDemo spec — the client app's half of the dance, stubbed: the
 * authorize POST carries the full request shape (response_type=code,
 * known client, redirect, state, S256 challenge, login simulation,
 * consent); the redirect's state echo is CHECKED (mismatch is called
 * out); the exchange POST carries code + verifier; the wrong-verifier
 * attack renders PKCE failure; consent=false renders access_denied.
 * Web Crypto's subtle.digest is stubbed so the challenge is
 * deterministic without depending on the jsdom environment.
 */

const AUTHORIZE_URL = '/api/demo/auth/oauth/authorize';
const TOKEN_URL = '/api/demo/auth/oauth/token';
const VERIFIER = 'demo-verifier-from-the-client-01';

const AUTHORIZED = {
  redirectTo: `http://localhost:5176/oauth/callback?code=sdg_ac_testcode&state=demo-state-xyz`,
  code: 'sdg_ac_testcode',
  state: 'demo-state-xyz',
  expiresAt: '2026-10-06T12:01:00Z',
};

const TOKENS_OK = {
  accessToken: 'eyJhbGciOiJIUzI1NiJ9.payload.sgB7xQ4m2n8k',
  refreshToken: 'sdg_rt_newgeneration',
  familyId: 'fam_test01',
  grant: 'authorization_code',
};

beforeAll(() => {
  container.initialize(createAppConfig());
  vi.stubGlobal('crypto', {
    subtle: {
      digest: async () => new Uint8Array([1, 2, 3, 4]).buffer,
    },
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.stubGlobal('crypto', {
    subtle: { digest: async () => new Uint8Array([1, 2, 3, 4]).buffer },
  });
});

describe('OAuthDemo', () => {
  it('runs the dance: authorize carries PKCE + state, redirect is checked, exchange succeeds', async () => {
    const calls: { url: string; body?: string }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        calls.push({ url, body: typeof init?.body === 'string' ? init.body : undefined });
        if (url.endsWith(AUTHORIZE_URL)) return jsonResponse(AUTHORIZED);
        if (url.endsWith(TOKEN_URL)) return jsonResponse(TOKENS_OK);
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<OAuthDemo />);
    fireEvent.click(screen.getByTestId('authn-o-authorize'));

    const redirect = await screen.findByTestId('authn-o-redirect');
    expect(redirect).toHaveTextContent('code=sdg_ac_testcode');
    expect(redirect).toHaveTextContent('state echoes');

    fireEvent.click(screen.getByTestId('authn-o-exchange'));
    const tokens = await screen.findByTestId('authn-o-tokens');
    expect(tokens).toHaveTextContent('fam_test01');
    expect(tokens).toHaveTextContent('authorization_code');

    const authorizeCall = calls.find((call) => call.url.endsWith(AUTHORIZE_URL));
    expect(String(authorizeCall?.body)).toContain('"responseType":"code"');
    expect(String(authorizeCall?.body)).toContain('"clientId":"sdg-console"');
    expect(String(authorizeCall?.body)).toContain('"codeChallengeMethod":"S256"');
    expect(String(authorizeCall?.body)).toContain('"state":"demo-state-xyz"');
    expect(String(authorizeCall?.body)).toContain('"consent":true');
    const exchangeCall = calls.find((call) => call.url.endsWith(TOKEN_URL));
    expect(String(exchangeCall?.body)).toContain('"code":"sdg_ac_testcode"');
    expect(String(exchangeCall?.body)).toContain(`"codeVerifier":"${VERIFIER}"`);
  });

  it('a state mismatch is called out as a redirect the client must refuse', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(AUTHORIZE_URL)) {
          return jsonResponse({ ...AUTHORIZED, state: 'attacker-state' });
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<OAuthDemo />);
    fireEvent.click(screen.getByTestId('authn-o-authorize'));

    const redirect = await screen.findByTestId('authn-o-redirect');
    expect(redirect).toHaveTextContent('STATE MISMATCH');
  });

  it('the wrong-verifier attack renders the PKCE failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(AUTHORIZE_URL)) return jsonResponse(AUTHORIZED);
        if (url.endsWith(TOKEN_URL)) {
          const wrongVerifier = String(init?.body).includes('attacker-guessed-this');
          return wrongVerifier
            ? jsonResponse(
                { error: { code: 'unauthorized', message: 'PKCE verification failed', requestId: 'r' } },
                401,
              )
            : jsonResponse(TOKENS_OK);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<OAuthDemo />);
    fireEvent.click(screen.getByTestId('authn-o-authorize'));
    await screen.findByTestId('authn-o-redirect');
    fireEvent.click(screen.getByTestId('authn-o-wrong-verifier'));

    await screen.findByTestId('authn-o-tokens', {}, { timeout: 100 }).catch(() => null);
    await waitFor(() =>
      expect(screen.queryByTestId('authn-o-tokens')).not.toBeInTheDocument(),
    );
    const redirect = screen.getByTestId('authn-o-redirect');
    expect(redirect).toHaveTextContent('PKCE failed');
  });

  it('declined consent surfaces access_denied through the error panel', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(AUTHORIZE_URL)) {
          return jsonResponse(
            { error: { code: 'forbidden', message: 'access_denied — the resource owner declined consent', requestId: 'r' } },
            403,
          );
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<OAuthDemo />);
    fireEvent.click(screen.getByTestId('authn-o-consent'));
    fireEvent.click(screen.getByTestId('authn-o-authorize'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('access_denied');
  });
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
