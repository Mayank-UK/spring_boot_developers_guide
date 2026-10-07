import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import JpaProbeDemo from './jpa_probe_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * JpaProbeDemo spec — the four fetch shapes against a stubbed endpoint: the
 * initial n-plus-1 measurement renders (queries headline, mode, rows, wall
 * time, verdict); switching modes re-fetches with the new query parameter;
 * switching size re-fetches too; a failing fetch (the fake-mode 503)
 * renders the error panel with the envelope's message.
 */

interface Captured {
  path: string;
}

function reportFor(mode: string, queries: number): Record<string, unknown> {
  return {
    mode,
    size: 5,
    queriesIssued: queries,
    postsReturned: 5,
    entriesLoaded: 15,
    wallTimeNanos: 21_500_000,
    verdict: `${mode} verdict sentence`,
  };
}

function stubProbe(captured: Captured[], ok = true) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = typeof input === 'string' ? input : input.toString();
      captured.push({ path });
      if (!ok) {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: { code: 'service_unavailable', message: 'activate with PATCH /api/demo/scenario', requestId: 'x' },
          }),
        };
      }
      const mode = new URLSearchParams(path.split('?')[1] ?? '').get('mode') ?? 'n-plus-1';
      const queries = mode === 'n-plus-1' ? 6 : mode === 'batch' ? 2 : 1;
      return { ok: true, status: 200, json: async () => reportFor(mode, queries) };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('JpaProbeDemo', () => {
  it('renders the initial n-plus-1 measurement with the queries headline', async () => {
    const captured: Captured[] = [];
    stubProbe(captured);
    render(<JpaProbeDemo />);

    expect(await screen.findByTestId('jpa-probe-queries')).toHaveTextContent('6 queries');
    expect(screen.getByTestId('jpa-probe-mode')).toHaveTextContent('n-plus-1');
    expect(screen.getByTestId('jpa-probe-rows')).toHaveTextContent('5 posts · 15 entries');
    expect(screen.getByTestId('jpa-probe-verdict')).toHaveTextContent('n-plus-1 verdict sentence');
    expect(captured[0]?.path).toBe('/api/demo/jpa/probe?mode=n-plus-1&size=5');
  });

  it('re-measures when another fetch shape is picked', async () => {
    const captured: Captured[] = [];
    stubProbe(captured);
    render(<JpaProbeDemo />);

    await screen.findByTestId('jpa-probe-queries');
    fireEvent.click(screen.getByTestId('jpa-mode-join-fetch'));
    expect(await screen.findByTestId('jpa-probe-queries')).toHaveTextContent('1 queries');
    expect(screen.getByTestId('jpa-probe-mode')).toHaveTextContent('join-fetch');
    expect(captured.some((call) => call.path === '/api/demo/jpa/probe?mode=join-fetch&size=5')).toBe(true);
  });

  it('re-measures when the page size changes', async () => {
    const captured: Captured[] = [];
    stubProbe(captured);
    render(<JpaProbeDemo />);

    await screen.findByTestId('jpa-probe-queries');
    fireEvent.click(screen.getByTestId('jpa-size-8'));
    await waitFor(() => {
      expect(captured.some((call) => call.path === '/api/demo/jpa/probe?mode=n-plus-1&size=8')).toBe(true);
    });
  });

  it('renders the error panel when the area is not activated', async () => {
    stubProbe([], false);
    render(<JpaProbeDemo />);

    await waitFor(() => {
      expect(document.body.textContent).toContain('PATCH /api/demo/scenario');
    });
    expect(screen.queryByTestId('jpa-probe-report')).not.toBeInTheDocument();
  });
});
