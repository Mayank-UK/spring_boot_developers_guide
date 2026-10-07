import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import RefreshDemo from './refresh_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * RefreshDemo spec — the theft story over stubbed routes: issue mints
 * the pair; exchange (rotate) sends the CURRENT token and renders the
 * next generation; replaying the SPENT token renders the family-revoked
 * 401; the families view renders generations and the revoked flag; and
 * after the theft even the current token's exchange is refused.
 */

const ISSUE_URL = '/api/demo/auth/refresh/issue';
const EXCHANGE_URL = '/api/demo/auth/refresh/exchange';
const FAMILIES_URL = '/api/demo/auth/refresh/families';

const FIRST = {
  action: 'issued',
  accessToken: 'eyJhbGciOiJIUzI1NiJ9.first.sig',
  refreshToken: 'sdg_rt_generation1',
  familyId: 'fam_test01',
};
const SECOND = {
  action: 'rotated',
  accessToken: 'eyJhbGciOiJIUzI1NiJ9.second.sig',
  refreshToken: 'sdg_rt_generation2',
  familyId: 'fam_test01',
};

function familiesView(revoked: boolean) {
  return {
    families: [
      {
        familyId: 'fam_test01',
        username: 'alice',
        revoked,
        generations: [
          { state: 'rotated', issuedAt: '2026-10-06T12:00:00Z' },
          { state: 'active', issuedAt: '2026-10-06T13:00:00Z' },
        ],
      },
    ],
  };
}

const REVOKED_401 = {
  error: {
    code: 'unauthorized',
    message: 'refresh token reuse detected — family revoked (possible theft)',
    requestId: 'req-9',
  },
};
const DEAD_FAMILY_401 = {
  error: { code: 'unauthorized', message: 'refresh family is revoked — log in again', requestId: 'req-10' },
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('RefreshDemo', () => {
  it('issues, rotates, and renders the generation timeline', async () => {
    const bodies: string[] = [];
    let revoked = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (typeof init?.body === 'string') bodies.push(init.body);
        if (url.endsWith(ISSUE_URL)) {
          revoked = false;
          return jsonResponse(FIRST);
        }
        if (url.endsWith(EXCHANGE_URL)) {
          return String(init?.body).includes('sdg_rt_generation1')
            ? jsonResponse(SECOND)
            : jsonResponse(DEAD_FAMILY_401, 401);
        }
        if (url.endsWith(FAMILIES_URL)) return jsonResponse(familiesView(revoked));
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<RefreshDemo />);
    fireEvent.click(screen.getByTestId('authn-r-issue'));
    const current = await screen.findByTestId('authn-r-current');
    expect(current).toHaveTextContent('sdg_rt_generation1');
    expect(screen.getByTestId('authn-r-note')).toHaveTextContent('family fam_test01 born');

    fireEvent.click(screen.getByTestId('authn-r-exchange'));
    await waitFor(() =>
      expect(screen.getByTestId('authn-r-current')).toHaveTextContent('sdg_rt_generation2'),
    );
    const row = await screen.findByTestId('authn-r-family-row');
    expect(row).toHaveTextContent('rotated → active');
    expect(bodies[0]).toContain('"username":"alice"');
    expect(bodies[1]).toContain('"refreshToken":"sdg_rt_generation1"');
  });

  it('replaying the spent token revokes the family — and the current token dies with it', async () => {
    let stage: 'first' | 'second' | 'dead' = 'first';
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(ISSUE_URL)) {
          stage = 'first';
          return jsonResponse(FIRST);
        }
        if (url.endsWith(EXCHANGE_URL)) {
          const presented = String(init?.body);
          if (stage === 'first') {
            stage = 'second';
            return jsonResponse(SECOND);
          }
          if (presented.includes('sdg_rt_generation1')) {
            stage = 'dead';
            return jsonResponse(REVOKED_401, 401);
          }
          return jsonResponse(DEAD_FAMILY_401, 401);
        }
        if (url.endsWith(FAMILIES_URL)) {
          return jsonResponse(familiesView(stage === 'dead'));
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<RefreshDemo />);
    fireEvent.click(screen.getByTestId('authn-r-issue'));
    await screen.findByTestId('authn-r-current');
    fireEvent.click(screen.getByTestId('authn-r-exchange'));
    await waitFor(() =>
      expect(screen.getByTestId('authn-r-current')).toHaveTextContent('sdg_rt_generation2'),
    );

    fireEvent.click(screen.getByTestId('authn-r-theft'));
    const note = await screen.findByTestId('authn-r-note');
    expect(note).toHaveTextContent('reuse detected');
    expect(note).toHaveTextContent('family revoked');
    const revoked = await screen.findByTestId('authn-r-revoked');
    expect(revoked).toHaveTextContent('revoked');

    fireEvent.click(screen.getByTestId('authn-r-current-after-death'));
    await waitFor(() =>
      expect(screen.getByTestId('authn-r-note')).toHaveTextContent('family is revoked'),
    );
  });

  it('bad credentials on issue surface through the error panel', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(ISSUE_URL)) {
          return jsonResponse(
            { error: { code: 'unauthorized', message: 'invalid credentials', requestId: 'req-r' } },
            401,
          );
        }
        if (url.endsWith(FAMILIES_URL)) return jsonResponse({ families: [] });
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<RefreshDemo />);
    fireEvent.click(screen.getByTestId('authn-r-issue'));

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
