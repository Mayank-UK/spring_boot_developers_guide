import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import JwtDemo from './jwt_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * JwtDemo spec — mint, trust, tamper: the mint POST carries the
 * credentials + the ttlSeconds hook; the decoded PREVIEW renders (what
 * anyone can read); the protected call attaches Authorization: Bearer
 * and renders the TRUSTED claims; the tamper button flips one signature
 * character before sending and renders the resource server's 401 as
 * evidence, not an error panel.
 */

const TOKEN_URL = '/api/demo/auth/jwt/token';
const PROTECTED_URL = '/api/demo/auth/jwt/protected';
const TOKEN =
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSIsInJ0b2xzIjpbImFkbWluIl19.sgB7xQ4m2n8k1o5pR3tUv6wYyZ9cL1eK0aJ2dH4fG7i';

const TOKEN_OK = {
  accessToken: TOKEN,
  tokenType: 'Bearer',
  expiresIn: 900,
  decoded: {
    sub: 'alice',
    roles: ['admin', 'staff'],
    jti: '6f1c-uuid',
    iat: '2026-10-06T12:00:00Z',
    exp: '2026-10-06T12:15:00Z',
  },
};

const PROTECTED_OK = {
  message: 'the resource server verified this Bearer token — the claims below are TRUSTED',
  sub: 'alice',
  roles: ['admin', 'staff'],
  jti: '6f1c-uuid',
  issuedAt: '2026-10-06T12:00:00Z',
  expiresAt: '2026-10-06T12:15:00Z',
  via: 'jwt',
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('JwtDemo', () => {
  it('mints with credentials + the ttl hook and renders the decoded preview', async () => {
    let sent: { init?: RequestInit } | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(TOKEN_URL)) {
          sent = { init };
          return jsonResponse(TOKEN_OK);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<JwtDemo />);
    fireEvent.change(screen.getByTestId('authn-j-ttl'), { target: { value: '1' } });
    fireEvent.click(screen.getByTestId('authn-j-mint'));

    const card = await screen.findByTestId('authn-j-token');
    expect(card).toHaveTextContent(TOKEN);
    expect(card).toHaveTextContent('sub=alice');
    expect(card).toHaveTextContent('jti=6f1c-uuid');
    expect(String(sent?.init?.body)).toContain('"ttlSeconds":1');
    expect(String(sent?.init?.body)).toContain('"username":"alice"');
  });

  it('calls protected with the Bearer header and renders the trusted claims', async () => {
    let bearer: string | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(TOKEN_URL)) return jsonResponse(TOKEN_OK);
        if (url.endsWith(PROTECTED_URL)) {
          bearer = (init?.headers as Record<string, string>).Authorization;
          return jsonResponse(PROTECTED_OK);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<JwtDemo />);
    fireEvent.click(screen.getByTestId('authn-j-mint'));
    await screen.findByTestId('authn-j-token');
    fireEvent.click(screen.getByTestId('authn-j-call'));

    await waitFor(() => expect(bearer).toBe(`Bearer ${TOKEN}`));
    const verdict = await screen.findByTestId('authn-j-protected');
    expect(verdict).toHaveTextContent('alice');
    expect(verdict).toHaveTextContent('via jwt');
    expect(verdict).toHaveTextContent('TRUSTED');
  });

  it('the tampered token flips a signature character and renders the 401 evidence', async () => {
    let bearer: string | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(TOKEN_URL)) return jsonResponse(TOKEN_OK);
        if (url.endsWith(PROTECTED_URL)) {
          bearer = (init?.headers as Record<string, string>).Authorization;
          return jsonResponse(
            { error: { code: 'unauthorized', message: 'invalid token', requestId: 'r' } },
            401,
          );
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<JwtDemo />);
    fireEvent.click(screen.getByTestId('authn-j-mint'));
    await screen.findByTestId('authn-j-token');
    fireEvent.click(screen.getByTestId('authn-j-tamper'));

    await waitFor(() => expect(bearer).toBeDefined());
    expect(bearer).not.toBe(`Bearer ${TOKEN}`);
    expect(bearer?.startsWith('Bearer eyJ')).toBe(true);
    expect(bearer?.slice(-1)).not.toBe(TOKEN.slice(-1));
    const verdict = await screen.findByTestId('authn-j-protected');
    expect(verdict).toHaveTextContent('401');
    expect(verdict).toHaveTextContent('WWW-Authenticate');
  });

  it('bad credentials surface through the error panel', async () => {
    stubApiFetch([
      {
        url: TOKEN_URL,
        status: 401,
        body: { error: { code: 'unauthorized', message: 'invalid credentials', requestId: 'req-j' } },
      },
    ]);

    render(<JwtDemo />);
    fireEvent.click(screen.getByTestId('authn-j-mint'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('invalid credentials');
  });
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
