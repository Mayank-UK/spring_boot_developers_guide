import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import LabDemo from './lab_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * LabDemo spec — the security lab over stubbed routes: the headers lab
 * loads on mount and renders the MEASURED headers side by side (values on
 * the hardened chain, «absent» on the insecure comparison); the CORS
 * preflights render the allowlisted origin's 200 verdict beside the
 * unlisted origin's 403; the rate limiter renders the 200 with remaining
 * tokens, then the 429 + Retry-After, then the reset seam's fresh bucket.
 */

const HEADERS_URL = '/api/demo/security/lab/headers';
const PREFLIGHT_URL = '/api/demo/security/lab/cors/preflight';
const RATE_URL = '/api/demo/security/lab/ratelimited';

const HEADERS_BODY = {
  allowlistNote: 'values below are the MEASURED response headers of this deployment',
  secure: [
    { header: 'X-Content-Type-Options', value: 'nosniff', blunts: 'MIME-type confusion', note: 'a' },
    { header: 'X-Frame-Options', value: 'DENY', blunts: 'clickjacking', note: 'b' },
    { header: 'Strict-Transport-Security', value: null, blunts: 'protocol downgrade', note: 'http probe' },
  ],
  insecureComparison: [
    { header: 'X-Content-Type-Options', value: null, blunts: 'MIME-type confusion', note: 'ABSENT here' },
  ],
};

const PREFLIGHT_BODY = {
  policy: 'allowlisted origin https://partner.demo.sdg.dev',
  allowlistedOrigin: {
    requestedOrigin: 'https://partner.demo.sdg.dev',
    status: 200,
    accessControlVerdicts: { 'Access-Control-Allow-Origin': 'https://partner.demo.sdg.dev' },
    verdict: 'allowed — the origin is on the allowlist',
  },
  unlistedOrigin: {
    requestedOrigin: 'https://evil.example',
    status: 403,
    accessControlVerdicts: {},
    verdict: 'rejected — 403 Invalid CORS request (origin not allowlisted)',
  },
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LabDemo', () => {
  it('renders the measured headers of both chains on mount', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(HEADERS_BODY)));

    render(<LabDemo />);

    const nosniff = await screen.findByTestId('secl-h-secure-X-Content-Type-Options');
    expect(nosniff).toHaveTextContent('nosniff');
    expect(nosniff).toHaveTextContent('MIME-type confusion');
    const frame = await screen.findByTestId('secl-h-secure-X-Frame-Options');
    expect(frame).toHaveTextContent('DENY');
    const hsts = screen.getByTestId('secl-h-secure-Strict-Transport-Security');
    expect(hsts).toHaveTextContent('«absent»');
    const insecure = screen.getByTestId('secl-h-insecure-X-Content-Type-Options');
    expect(insecure).toHaveTextContent('«absent»');
    expect(screen.getByText(/MEASURED response headers/)).toBeTruthy();
  });

  it('runs both preflights and renders the two verdicts', async () => {
    let preflightCalls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.includes(HEADERS_URL)) {
          return jsonResponse(HEADERS_BODY);
        }
        if (url.includes(PREFLIGHT_URL)) {
          preflightCalls += 1;
          return jsonResponse(PREFLIGHT_BODY);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<LabDemo />);
    await screen.findByTestId('secl-h-secure-X-Frame-Options');
    fireEvent.click(screen.getByTestId('secl-cors-run'));

    const result = await screen.findByTestId('secl-cors-result');
    expect(result).toHaveTextContent('https://partner.demo.sdg.dev → 200');
    expect(result).toHaveTextContent('allowed — the origin is on the allowlist');
    expect(result).toHaveTextContent('https://evil.example → 403');
    expect(result).toHaveTextContent('rejected');
    expect(preflightCalls).toBe(1);
  });

  it('spends the bucket down to the 429 and back through the reset seam', async () => {
    let consumeCalls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.includes(HEADERS_URL)) {
          return jsonResponse(HEADERS_BODY);
        }
        if (url.includes(`${RATE_URL}/reset`) && init?.method === 'POST') {
          return jsonResponse({ key: 'demo-client', reset: true });
        }
        if (url.includes(RATE_URL) && init?.method === 'POST') {
          consumeCalls += 1;
          return consumeCalls === 1
            ? jsonResponse({
                key: 'demo-client',
                limit: 2,
                windowSeconds: 60,
                allowed: true,
                remainingTokens: 1,
              })
            : jsonResponse(
                {
                  error: {
                    code: 'rate_limited',
                    message: 'rate budget exhausted for key "demo-client"',
                    requestId: 'req-1',
                    limit: 2,
                    windowSeconds: 60,
                    retryAfterSeconds: 60,
                  },
                },
                429,
              );
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<LabDemo />);
    await screen.findByTestId('secl-h-secure-X-Frame-Options');

    fireEvent.click(screen.getByTestId('secl-rl-call'));
    await waitFor(() =>
      expect(screen.getByTestId('secl-rl-result')).toHaveTextContent('200 · allowed'),
    );
    expect(screen.getByTestId('secl-rl-result')).toHaveTextContent('1 token(s) left');

    fireEvent.click(screen.getByTestId('secl-rl-call'));
    await waitFor(() =>
      expect(screen.getByTestId('secl-rl-result')).toHaveTextContent('429 · rate_limited'),
    );
    expect(screen.getByTestId('secl-rl-result')).toHaveTextContent('rate budget exhausted');

    fireEvent.click(screen.getByTestId('secl-rl-reset'));
    await waitFor(() =>
      expect(screen.getByTestId('secl-rl-result')).toHaveTextContent('bucket reset'),
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
