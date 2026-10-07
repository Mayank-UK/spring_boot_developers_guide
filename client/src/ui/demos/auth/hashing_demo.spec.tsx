import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import HashingDemo from './hashing_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * HashingDemo spec — the PasswordEncoder lab over stubbed routes: hash
 * carries password + cost; the SAME password hashed twice renders two
 * DIFFERENT hashes (salts); verify correct and wrong render side by
 * side with their timings; dissect renders the 59-char anatomy and its
 * notes.
 */

const HASH_URL = '/api/demo/auth/hashing/hash';
const VERIFY_URL = '/api/demo/auth/hashing/verify';
const COMPARE_URL = '/api/demo/auth/hashing/compare';

const HASH_ONE = { hash: '$2a$10$abcdefghijklmnopqrstuv123456789012345678901234567890123', cost: 10, elapsedMs: 62.1 };
const HASH_TWO = { hash: '$2a$10$uvwxyzabcdefghij12345678901234567890123456789012345678', cost: 10, elapsedMs: 63.4 };

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('HashingDemo', () => {
  it('hashes with the chosen cost and renders the anatomy dissection', async () => {
    let sent: { init?: RequestInit } | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(HASH_URL)) {
          sent = { init };
          return jsonResponse(HASH_ONE);
        }
        if (url.startsWith(COMPARE_URL)) {
          return jsonResponse({
            format: 'bcrypt',
            version: '2a',
            cost: 10,
            salt: 'abcdefghijklmnopqrstuv',
            hashBody: '123456789012345678901234567890123',
            notes: ['version 2a — the algorithm revision', 'cost 10 — 2^10 bcrypt rounds'],
          });
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<HashingDemo />);
    fireEvent.change(screen.getByTestId('authn-h-cost'), { target: { value: '12' } });
    fireEvent.click(screen.getByTestId('authn-h-hash'));

    const result = await screen.findByTestId('authn-h-hash-result');
    expect(result).toHaveTextContent('$2a$10$');
    expect(String(sent?.init?.body)).toContain('"cost":12');
    expect(String(sent?.init?.body)).toContain('"password":"correct horse battery staple"');

    fireEvent.click(screen.getByTestId('authn-h-anatomy'));
    const anatomy = await screen.findByTestId('authn-h-anatomy-result');
    expect(anatomy).toHaveTextContent('$2a$10$abcdefghijklmnopqrstuv123456789012345678901234567890123');
    expect(anatomy).toHaveTextContent('cost 10');
    expect(anatomy).toHaveTextContent('algorithm revision');
  });

  it('hashing the same password twice renders two different hashes', async () => {
    let count = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(HASH_URL)) {
          count += 1;
          return jsonResponse(count === 1 ? HASH_ONE : HASH_TWO);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<HashingDemo />);
    fireEvent.click(screen.getByTestId('authn-h-hash'));
    await screen.findByTestId('authn-h-hash-result');
    fireEvent.click(screen.getByTestId('authn-hash-again'));

    const second = await screen.findByTestId('authn-h-second');
    expect(second).toHaveTextContent(HASH_TWO.hash);
    expect(second.textContent).not.toContain('IDENTICAL');
  });

  it('verify correct and wrong render side by side with timings', async () => {
    const bodies: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(HASH_URL)) return jsonResponse(HASH_ONE);
        if (url.endsWith(VERIFY_URL)) {
          bodies.push(String(init?.body));
          const isWrong = String(init?.body).includes('battery staplex');
          return jsonResponse({ matches: !isWrong, elapsedMs: isWrong ? 61.8 : 62.1 });
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<HashingDemo />);
    fireEvent.click(screen.getByTestId('authn-h-hash'));
    await screen.findByTestId('authn-h-hash-result');
    fireEvent.click(screen.getByTestId('authn-h-verify-correct'));
    fireEvent.click(screen.getByTestId('authn-h-verify-wrong'));

    const timings = await screen.findByTestId('authn-h-timings');
    expect(timings).toHaveTextContent('matches=true in 62.1ms');
    expect(timings).toHaveTextContent('matches=false in 61.8ms');
    expect(bodies[0]).toContain(HASH_ONE.hash);
    expect(bodies[1]).toContain('battery staplex');
  });

  it('an out-of-range cost surfaces the 400 through the error panel', async () => {
    stubApiFetch([
      {
        url: HASH_URL,
        status: 400,
        body: { error: { code: 'validation_failed', message: 'bcrypt cost must be between 4 and 15', requestId: 'req-h' } },
      },
    ]);

    render(<HashingDemo />);
    fireEvent.click(screen.getByTestId('authn-h-hash'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('between 4 and 15');
  });
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
