import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import GrpcConsoleDemo from './grpc_console_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * GrpcConsoleDemo spec — the console over the stubbed demo endpoints: the
 * descriptor table renders the four rpcs with their real interaction kinds;
 * invoking posts the chosen method/topic to the invoke endpoint and renders
 * the OK verdict with its results; the deadline path renders the
 * DEADLINE_EXCEEDED verdict (code + message + round trip, no results); and
 * the topic picker is fed by the real content list endpoint.
 */

const CONTENT_URL = '/api/content';
const SERVICE_URL = '/api/demo/grpc/service';
const INVOKE_URL = '/api/demo/grpc/invoke';

interface CapturedCall {
  url: string;
  init?: RequestInit;
}

function stubDemo(captured: CapturedCall[], invokeBody: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url, init });
      let body: unknown;
      if (url === CONTENT_URL) {
        body = {
          version: 1,
          groups: [],
          topics: [
            { id: 'grpc', title: 'gRPC', group: 'apis', order: 5, sectionCount: 8 },
            { id: 'validation', title: 'Validation', group: 'middleware-request', order: 4, sectionCount: 9 },
          ],
        };
      } else if (url === SERVICE_URL) {
        body = {
          serviceName: 'guide.demo.GuideDemo',
          protoPackage: 'guide.demo.GuideDemo',
          methods: [
            { name: 'GetTopicInfo', fullMethodName: '/guide.demo.GuideDemo/GetTopicInfo', kind: 'unary' },
            {
              name: 'StreamSectionTitles',
              fullMethodName: '/guide.demo.GuideDemo/StreamSectionTitles',
              kind: 'server-streaming',
            },
            { name: 'AckSections', fullMethodName: '/guide.demo.GuideDemo/AckSections', kind: 'client-streaming' },
            { name: 'EchoChat', fullMethodName: '/guide.demo.GuideDemo/EchoChat', kind: 'bidi-streaming' },
          ],
        };
      } else if (url === INVOKE_URL) {
        body = invokeBody;
      } else {
        return {
          ok: false,
          status: 404,
          json: async () => ({ error: { code: 'not_found', message: `no stub for ${url}`, requestId: 'stub' } }),
        };
      }
      return { ok: true, status: 200, json: async () => body };
    }),
  );
}

const okUnary = {
  methodKind: 'UNARY',
  roundTripMs: 6,
  results: [{ topicId: 'grpc', title: 'gRPC', group: 'apis', sectionCount: 8 }],
  statusCode: 'OK',
  statusMessage: null,
};

const deadlineExceeded = {
  methodKind: 'UNARY',
  roundTripMs: 301,
  results: [],
  statusCode: 'DEADLINE_EXCEEDED',
  statusMessage: 'deadline exceeded after 299.87ms',
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GrpcConsoleDemo', () => {
  it('renders the descriptor table with all four interaction kinds', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okUnary);

    render(<GrpcConsoleDemo />);

    const table = await screen.findByTestId('grpc-service-table');
    expect(table).toHaveTextContent('GetTopicInfo');
    expect(table).toHaveTextContent('unary');
    expect(table).toHaveTextContent('StreamSectionTitles');
    expect(table).toHaveTextContent('server-streaming');
    expect(table).toHaveTextContent('AckSections');
    expect(table).toHaveTextContent('client-streaming');
    expect(table).toHaveTextContent('EchoChat');
    expect(table).toHaveTextContent('bidi-streaming');
    expect(screen.getAllByTestId('grpc-service-row').length).toBe(4);
    // the topic picker is fed from the real content list
    const select = screen.getByTestId('grpc-topic-select') as HTMLSelectElement;
    expect(select.value).toBe('grpc');
  });

  it('invokes the chosen kind and renders the OK verdict with results', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okUnary);

    render(<GrpcConsoleDemo />);
    await screen.findByTestId('grpc-service-table');

    fireEvent.click(screen.getByTestId('grpc-invoke'));

    const code = await screen.findByTestId('grpc-verdict-code');
    expect(code).toHaveTextContent('OK');
    expect(screen.getByTestId('grpc-verdict-roundtrip')).toHaveTextContent('6ms');
    const results = await screen.findByTestId('grpc-results');
    expect(results).toHaveTextContent('sectionCount: 8');

    const sent = captured.find((call) => call.url === INVOKE_URL);
    if (sent === undefined || sent.init === undefined) throw new Error('invoke was not captured');
    expect(sent.init.method).toBe('POST');
    const payload = JSON.parse(String(sent.init.body)) as Record<string, unknown>;
    expect(payload.method).toBe('unary');
    expect(payload.topicId).toBe('grpc');
    expect(payload.delayMs).toBe(0);
    expect('deadlineMs' in payload).toBe(false);
  });

  it('switching kind and arming a deadline sends both on the wire', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okUnary);

    render(<GrpcConsoleDemo />);
    await screen.findByTestId('grpc-service-table');

    fireEvent.click(screen.getByTestId('grpc-method-bidi'));
    const delay = screen.getByTestId('grpc-delay');
    fireEvent.change(delay, { target: { value: '1200' } });
    fireEvent.click(screen.getByTestId('grpc-deadline-toggle'));
    fireEvent.change(screen.getByTestId('grpc-deadline'), { target: { value: '300' } });
    fireEvent.click(screen.getByTestId('grpc-invoke'));

    await waitFor(() => {
      const sent = captured.find((call) => call.url === INVOKE_URL);
      if (sent === undefined) throw new Error('invoke was not captured');
      const payload = JSON.parse(String(sent.init?.body)) as Record<string, unknown>;
      expect(payload.method).toBe('bidi');
      expect(payload.delayMs).toBe(1200);
      expect(payload.deadlineMs).toBe(300);
    });
  });

  it('renders the DEADLINE_EXCEEDED verdict — code, message, no results', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, deadlineExceeded);

    render(<GrpcConsoleDemo />);
    await screen.findByTestId('grpc-service-table');

    fireEvent.click(screen.getByTestId('grpc-invoke'));

    expect(await screen.findByTestId('grpc-verdict-code')).toHaveTextContent('DEADLINE_EXCEEDED');
    expect(screen.getByTestId('grpc-verdict-message')).toHaveTextContent('deadline exceeded');
    expect(screen.getByTestId('grpc-verdict-roundtrip')).toHaveTextContent('301ms');
    expect(screen.getByTestId('grpc-results-empty')).toBeInTheDocument();
  });
});
