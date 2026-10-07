import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import ChecklistDemo from './checklist_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * ChecklistDemo spec — the guide grading itself over a stubbed endpoint:
 * the counts line, the category groups with live PASS verdicts and
 * evidence, VERIFY rows carrying the curl (and PASS rows carrying none),
 * and the degraded panel when the server is down. Everything asserted here
 * comes from the mocked SERVER answer — the component grades nothing
 * client-side, which is the demo's whole point.
 */

interface Captured {
  url: string;
  method: string;
}

function item(overrides?: {
  category?: string;
  id?: string;
  title?: string;
  verdict?: string;
  evidence?: string;
  verify?: string | null;
}) {
  return {
    category: overrides?.category ?? 'setup',
    id: overrides?.id ?? 'typed-config',
    title: overrides?.title ?? 'Configuration is one typed, boot-validated record',
    verdict: overrides?.verdict ?? 'pass',
    evidence: overrides?.evidence ?? 'guide.env.* binds into the AppConfig record',
    verify: overrides?.verify === undefined ? null : overrides.verify,
  };
}

function report() {
  return {
    items: [
      item(),
      item({
        id: 'content-registry',
        title: 'Content ids are unique, validated at startup',
        evidence: '42 topics constructed into a ContentRegistry that validates',
      }),
      item({
        category: 'api',
        id: 'thin-controllers',
        title: 'Controllers parse, call one use case, map the answer',
        verdict: 'verify',
        evidence: 'an unrouted path still answers the envelope (404, not a stack trace)',
        verify: 'curl -si localhost:8080/api/content/nope | head -4',
      }),
    ],
    counts: { liveChecks: 6, livePasses: 6, verifyPointers: 10 },
    reading: [
      '6 of 6 live checks pass: those verdicts were read from THIS process.',
      '10 items are VERIFY by design: their truth is environmental.',
      'the categories mirror the topic\u2019s sections.',
    ],
  };
}

type Down = { current: boolean };

/** One stub for a whole test: the GET answers the mocked report (or the 503 envelope). */
function stubEndpoint(captured: Captured[], down: Down, body: () => unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url, method: init?.method ?? 'GET' });
      if (down.current) {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: { code: 'service_unavailable', message: 'checklist unreachable', requestId: 'x' },
          }),
        };
      }
      return { ok: true, status: 200, json: async () => body() };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ChecklistDemo', () => {
  it('auto-loads the checklist and paints the counts line', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, () => report());

    render(<ChecklistDemo />);

    const counts = await screen.findByTestId('check-counts');
    expect(counts).toHaveTextContent('6/6 live pass');
    expect(counts).toHaveTextContent('10 to verify');
    expect(captured[0]?.url).toContain('/demo/best/checklist');
    expect(captured[0]?.method).toBe('GET');
  });

  it('groups rows by category and renders live verdicts with their evidence', async () => {
    stubEndpoint([], { current: false }, () => report());

    render(<ChecklistDemo />);

    const categories = await screen.findAllByTestId('check-category');
    expect(categories).toHaveLength(2);
    expect(categories[0]).toHaveTextContent('setup');
    expect(categories[0]).toHaveTextContent('typed-config');
    expect(categories[0]).toHaveTextContent('content-registry');
    expect(categories[1]).toHaveTextContent('api');

    const items = await screen.findAllByTestId('check-item');
    expect(items).toHaveLength(3);
    const evidences = await screen.findAllByTestId('check-evidence');
    expect(evidences[0]).toHaveTextContent('AppConfig record');
    expect(evidences[1]).toHaveTextContent('42 topics constructed into a ContentRegistry');
  });

  it('verify rows carry the curl command, pass rows carry none', async () => {
    stubEndpoint([], { current: false }, () => report());

    render(<ChecklistDemo />);

    const verifies = await screen.findAllByTestId('check-verify');
    expect(verifies).toHaveLength(1);
    expect(verifies[0]).toHaveTextContent('curl -si localhost:8080/api/content/nope');
    // exactly one row is a verify badge (thin-controllers), two are passes
    const verdicts = await screen.findAllByTestId('check-verdict');
    const labels = verdicts.map((badge) => badge.textContent ?? '');
    expect(labels.filter((label) => label === 'pass')).toHaveLength(2);
    expect(labels.filter((label) => label === 'verify')).toHaveLength(1);
    // the reading lines close the panel
    expect(screen.getByTestId('check-reading-0')).toHaveTextContent('6 of 6 live checks pass');
    expect(screen.getByTestId('check-reading-1')).toHaveTextContent('VERIFY by design');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoint([], { current: true }, () => report());

    render(<ChecklistDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('checklist unreachable');
  });
});
