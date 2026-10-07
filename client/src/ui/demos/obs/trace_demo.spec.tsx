import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ObsTraceDemo from './trace_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * ObsTraceDemo spec — the one-id proof over stubbed endpoints: running the
 * flow GETs /demo/obs/trace; the evidence renders the trace id, the
 * traceparent that was sent, the header the hop saw, and the hop's MDC
 * trace id; the waterfall and the MDC-joined log lines render; and the
 * one-id chip appears exactly when EVERY span and log carries the same id
 * the response reports. A degraded server renders the error panel instead.
 */

interface Captured {
  url: string;
}

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736';
const SENT = `00-${TRACE_ID}-00f067aa0ba902b7-01`;
const SEEN = `00-${TRACE_ID}-7c1f929d0e0e4736-01`;

function evidenceBody() {
  return {
    traceId: TRACE_ID,
    traceparentSent: SENT,
    hop: {
      traceparentSeen: SEEN,
      traceIdFromMdc: TRACE_ID,
      facts: {
        origin: 'traced-flow',
        postId: 'post-17',
        jobId: 'obs-42',
        outcome: 'succeeded',
        steps: [
          'post post-17 created through CreatePost (domain/posts)',
          'job obs-42 drained through JobWorker.runOnce — outcome succeeded (topic 29\'s vocabulary)',
        ],
      },
    },
    spans: [
      {
        name: 'GET /api/demo/obs/trace/hop',
        traceId: TRACE_ID,
        spanId: '7c1f929d0e0e4736',
        parentId: '00f067aa0ba902b7',
        startMs: 1760000000100,
        durationMs: 7.25,
      },
      {
        name: 'GET /api/demo/obs/trace',
        traceId: TRACE_ID,
        spanId: '00f067aa0ba902b7',
        parentId: null,
        startMs: 1760000000050,
        durationMs: 41.5,
      },
    ],
    logs: [
      {
        timestampMs: 1760000000120,
        level: 'INFO',
        logger: 'guide.domain.obs.RunTracedHop',
        message: 'traced hop from traced-flow: post post-17 job obs-42 outcome succeeded',
        requestId: 'obs-trace-hop',
        traceId: TRACE_ID,
      },
    ],
  };
}

function stubEndpoints(captured: Captured[], ok: boolean, broken = false) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url });
      if (!ok) {
        return {
          ok: false,
          status: 503,
          json: async () => ({
            error: { code: 'service_unavailable', message: 'no full runtime', requestId: 'x' },
          }),
        };
      }
      const body = broken ? { ...evidenceBody(), spans: [] } : evidenceBody();
      return { ok: true, status: 200, json: async () => body };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ObsTraceDemo', () => {
  it('runs the flow and renders the ids, the header pair, and the hop facts', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);

    render(<ObsTraceDemo />);
    fireEvent.click(screen.getByTestId('obs-trace-run'));

    const ids = await screen.findByTestId('obs-trace-ids');
    expect(ids).toHaveTextContent(`traceId: ${TRACE_ID}`);
    expect(ids).toHaveTextContent(`traceparentSent: ${SENT}`);
    expect(ids).toHaveTextContent(`hop received: ${SEEN}`);
    expect(ids).toHaveTextContent(`hop's MDC traceId: ${TRACE_ID}`);
    expect(captured[0]?.url).toContain('/demo/obs/trace');

    const hop = await screen.findByTestId('obs-trace-hop');
    expect(hop).toHaveTextContent('post post-17 created through CreatePost (domain/posts)');
    expect(hop).toHaveTextContent('outcome succeeded');
  });

  it('renders the waterfall and the MDC-joined log lines', async () => {
    stubEndpoints([], true);

    render(<ObsTraceDemo />);
    fireEvent.click(screen.getByTestId('obs-trace-run'));

    const spans = await screen.findByTestId('obs-trace-spans');
    expect(spans).toHaveTextContent('GET /api/demo/obs/trace/hop');
    expect(spans).toHaveTextContent('(root)');
    expect(spans).toHaveTextContent('7.25');
    expect(spans).toHaveTextContent('41.50');

    const logs = await screen.findByTestId('obs-trace-logs');
    expect(logs).toHaveTextContent('traced hop from traced-flow');
    expect(logs).toHaveTextContent('requestId=obs-trace-hop');
  });

  it('proves one id only when every span and log carries it', async () => {
    stubEndpoints([], true);

    render(<ObsTraceDemo />);
    fireEvent.click(screen.getByTestId('obs-trace-run'));

    const chip = await screen.findByTestId('obs-trace-proof');
    expect(chip).toHaveTextContent('one id');
    expect(chip).toHaveTextContent('2 spans');
  });

  it('withholds the proof chip when the waterfall is empty', async () => {
    stubEndpoints([], true, true);

    render(<ObsTraceDemo />);
    fireEvent.click(screen.getByTestId('obs-trace-run'));

    await screen.findByTestId('obs-trace-ids');
    expect(screen.queryByTestId('obs-trace-proof')).toBeNull();
  });

  it('renders the degraded panel when the full runtime is absent', async () => {
    stubEndpoints([], false);

    render(<ObsTraceDemo />);
    fireEvent.click(screen.getByTestId('obs-trace-run'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('no full runtime');
  });
});
