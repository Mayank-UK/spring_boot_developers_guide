import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ApiKeysDemo from './api_keys_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ApiKeysDemo spec — the key lifecycle over stubbed routes: issuance
 * shows the raw key ONCE with its prefix; the listing renders redacted
 * prefixes and never the key material; whoami attaches X-API-Key and
 * renders the principal (a mangled or absent key renders the 401 —
 * wrong and absent look identical); revoke calls DELETE and refreshes.
 */

const KEYS_URL = '/api/demo/auth/apikeys';
const WHOAMI_URL = '/api/demo/auth/apikeys/whoami';
const RAW_KEY = 'sdg_key_abc123def456ghi789jkl012mno345pqr678stu';
const ISSUED = {
  id: 'key_test01',
  apiKey: RAW_KEY,
  displayPrefix: 'sdg_key_abcd',
  username: 'alice',
};
const LISTING = {
  keys: [
    { id: 'key_test01', redacted: 'sdg_key_abcd…', username: 'alice', createdAt: '2026-10-06T12:00:00Z', lastUsedAt: '' },
  ],
};
const WHOAMI_OK = { user: { id: 'usr_alice', username: 'alice', roles: ['admin', 'staff'] }, via: 'api-key' };

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ApiKeysDemo', () => {
  it('issues with the typed credentials and shows the raw key exactly once', async () => {
    let sent: { url: string; init?: RequestInit } | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(KEYS_URL) && (init?.method ?? 'GET') === 'POST') {
          sent = { url, init };
          return jsonResponse(ISSUED);
        }
        if (url.endsWith(KEYS_URL)) return jsonResponse(LISTING);
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<ApiKeysDemo />);
    await screen.findByTestId('authn-k-issue');
    fireEvent.click(screen.getByTestId('authn-k-issue'));

    const issued = await screen.findByTestId('authn-k-issued');
    expect(issued).toHaveTextContent(RAW_KEY);
    expect(issued).toHaveTextContent('sdg_key_abcd…');
    await waitFor(() => expect(sent).toBeDefined());
    expect(String(sent?.init?.body)).toContain('"username":"alice"');
    expect(String(sent?.init?.body)).toContain('"password":"alice-demo-pass-01"');
  });

  it('the redacted list renders prefixes, never key material', async () => {
    stubApiFetch([
      {
        url: KEYS_URL,
        status: 401,
        body: { error: { code: 'unauthorized', message: 'anonymous', requestId: 'r' } },
      },
    ]);

    const { container: mounted } = render(<ApiKeysDemo />);
    await waitFor(() =>
      expect(mounted.textContent).not.toContain(RAW_KEY),
    );
  });

  it('whoami attaches the X-API-Key header and renders the principal', async () => {
    let header: string | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(WHOAMI_URL)) {
          header = (init?.headers as Record<string, string>)['X-API-Key'];
          return jsonResponse(WHOAMI_OK);
        }
        if (url.endsWith(KEYS_URL) && (init?.method ?? 'GET') === 'POST') return jsonResponse(ISSUED);
        if (url.endsWith(KEYS_URL)) return jsonResponse(LISTING);
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<ApiKeysDemo />);
    // issue first so the demo holds a key to attach
    fireEvent.click(screen.getByTestId('authn-k-issue'));
    await screen.findByTestId('authn-k-issued');
    fireEvent.click(screen.getByTestId('authn-k-whoami'));

    await waitFor(() => expect(header).toBe(RAW_KEY));
    const verdict = await screen.findByTestId('authn-k-whoami-result');
    expect(verdict).toHaveTextContent('alice');
    expect(verdict).toHaveTextContent('via api-key');
  });

  it('a mangled key and no key render the identical 401', async () => {
    const headers: (string | undefined)[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(WHOAMI_URL)) {
          headers.push((init?.headers as Record<string, string> | undefined)?.['X-API-Key']);
          return jsonResponse({ error: { code: 'unauthorized', message: 'no key', requestId: 'r' } }, 401);
        }
        if (url.endsWith(KEYS_URL) && (init?.method ?? 'GET') === 'POST') return jsonResponse(ISSUED);
        if (url.endsWith(KEYS_URL)) return jsonResponse(LISTING);
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<ApiKeysDemo />);
    fireEvent.click(screen.getByTestId('authn-k-issue'));
    await screen.findByTestId('authn-k-issued');
    fireEvent.click(screen.getByTestId('authn-k-whoami-mangled'));
    let verdict = await screen.findByTestId('authn-k-whoami-result');
    expect(verdict).toHaveTextContent('401');
    expect(verdict).toHaveTextContent('identical');

    fireEvent.click(screen.getByTestId('authn-k-whoami-none'));
    verdict = await screen.findByTestId('authn-k-whoami-result');
    expect(verdict).toHaveTextContent('401');
    expect(headers[0]).not.toBe(RAW_KEY);
    expect(headers[1]).toBeUndefined();
  });

  it('revoke issues the DELETE and refreshes the listing', async () => {
    let deleted = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith('/api/demo/auth/apikeys/key_test01') && init?.method === 'DELETE') {
          deleted = true;
          return jsonResponse({ revoked: true, id: 'key_test01' });
        }
        if (url.endsWith(KEYS_URL) && (init?.method ?? 'GET') === 'POST') return jsonResponse(ISSUED);
        if (url.endsWith(KEYS_URL)) {
          return jsonResponse(deleted ? { keys: [] } : LISTING);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<ApiKeysDemo />);
    fireEvent.click(screen.getByTestId('authn-k-issue'));
    const row = await screen.findByTestId('authn-k-row');
    expect(row).toHaveTextContent('sdg_key_abcd…');

    fireEvent.click(screen.getByTestId('authn-k-revoke'));
    await waitFor(() => expect(deleted).toBe(true));
    await waitFor(() =>
      expect(screen.queryByTestId('authn-k-row')).not.toBeInTheDocument(),
    );
  });
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
