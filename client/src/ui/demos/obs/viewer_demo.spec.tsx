import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ObsViewerDemo from './viewer_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * ObsViewerDemo spec — the structured-logging viewer over stubbed
 * endpoints: the ring loads its events with every structured field
 * (requestId/traceId rendered when present, omitted when not); the level
 * filter and limit ride into the query string; emitting a level calls the
 * emitter and reloads the ring; an empty filtered ring says so instead of
 * lying; and a degraded server renders the error panel.
 */

interface Captured {
  url: string;
}

const INFO_EVENT = {
  timestampMs: 1760000000114,
  level: 'INFO',
  logger: 'guide.http',
  message: 'GET /api/demo/obs/logs 200 3ms',
  requestId: 'req-1',
  traceId: 'trace-1',
};

const WARN_EVENT = {
  timestampMs: 1760000000420,
  level: 'WARN',
  logger: 'guide.demo.obs.emitter',
  message: 'deliberate WARN emission from the observability tour',
  requestId: 'req-2',
  traceId: null,
};

function ringBody(events: unknown[]) {
  return { capacity: 500, size: events.length, events };
}

function stubEndpoints(captured: Captured[], ok: boolean): (level: string) => unknown[] {
  const current = ok ? [INFO_EVENT, WARN_EVENT] : [];
  let emitCount = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url });
      const respond = (status: number, body: unknown) => ({
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
      });
      if (!ok) {
        return respond(503, {
          error: { code: 'service_unavailable', message: 'ring unreachable', requestId: 'x' },
        });
      }
      if (url.includes('/demo/obs/logs/emit')) {
        emitCount += 1;
        return respond(200, {
          ...WARN_EVENT,
          timestampMs: 1760000001000 + emitCount,
        });
      }
      const levelMatch = /[?&]level=([^&]*)/.exec(url);
      const level = decodeURIComponent(levelMatch?.[1] ?? '');
      const events =
        level === ''
          ? current
          : current.filter(
              (e) => String((e as { level: string }).level).toUpperCase() === level.toUpperCase(),
            );
      return respond(200, ringBody(events));
    }),
  );
  return (level) =>
    current.filter((e) => String((e as { level: string }).level).toUpperCase() === level.toUpperCase());
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ObsViewerDemo', () => {
  it('loads the ring with every structured field, ids included', async () => {
    stubEndpoints([], true);

    render(<ObsViewerDemo />);

    const ring = await screen.findByTestId('obs-ring');
    expect(ring).toHaveTextContent('INFO');
    expect(ring).toHaveTextContent('GET /api/demo/obs/logs 200 3ms');
    expect(ring).toHaveTextContent('requestId=req-1');
    expect(ring).toHaveTextContent('traceId=trace-1');
    expect(ring).toHaveTextContent('WARN');
    expect(ring).toHaveTextContent('2 of 500 held');
  });

  it('rides the level filter and limit into the query string', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<ObsViewerDemo />);
    await screen.findByTestId('obs-event-0');
    fireEvent.change(screen.getByTestId('obs-level-select'), { target: { value: 'warn' } });
    fireEvent.change(screen.getByTestId('obs-limit-select'), { target: { value: '50' } });
    await waitFor(() => {
      const withParams = captured.filter((call) => call.url.includes('level=warn'));
      expect(withParams.length).toBeGreaterThan(0);
      expect(withParams[withParams.length - 1]?.url).toContain('limit=50');
    });
  });

  it('emits a level for real and reloads the ring', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<ObsViewerDemo />);
    await screen.findByTestId('obs-event-0');
    fireEvent.click(screen.getByTestId('obs-emit-warn'));

    await waitFor(() => {
      expect(captured.some((call) => call.url.includes('/demo/obs/logs/emit?level=warn'))).toBe(true);
    });
    await waitFor(() => {
      expect(captured.filter((call) => call.url.includes('/demo/obs/logs?')).length).toBeGreaterThan(1);
    });
  });

  it('says so when the filtered ring is empty instead of lying', async () => {
    stubEndpoints([], true);

    render(<ObsViewerDemo />);
    await screen.findByTestId('obs-event-0');
    fireEvent.change(screen.getByTestId('obs-level-select'), { target: { value: 'error' } });

    expect(await screen.findByTestId('obs-empty')).toBeDefined();
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], false);

    render(<ObsViewerDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('ring unreachable');
  });
});
