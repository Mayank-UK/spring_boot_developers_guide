import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import PipelineDemo from './pipeline_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * PipelineDemo spec — topic 08's middleware:pipeline widget over the shared
 * guarded pipeline: the current request's markers render in order (filter
 * before → interceptor preHandle → handler), a null previous trace renders
 * its hint, the registered-filters table shows the introspected chain, and
 * "run again" lands the FIRST fetch in the previousCompletedTrace slot with
 * the full unwind — postHandle, afterCompletion, both filter after-markers.
 */

const PIPELINE_PATH = '/api/demo/middleware/pipeline';

const CURRENT_MARKERS = [
  'filter:ordering:before',
  'filter:trace:before',
  'interceptor:preHandle',
  'handler:middleware-pipeline',
];

const FULL_UNWIND = [
  ...CURRENT_MARKERS,
  'interceptor:postHandle',
  'interceptor:afterCompletion',
  'filter:trace:after',
  'filter:ordering:after',
];

const FILTERS = [
  { name: 'characterEncodingFilter', order: -2147483648, registration: '@Bean filter (auto-registered)' },
  { name: 'middlewareOrderingFilter', order: 20, registration: '@Bean filter (auto-registered)' },
  { name: 'pipelineTraceFilterRegistration', order: 30, registration: 'FilterRegistrationBean' },
  { name: 'requestIdFilter', order: 2147483647, registration: '@Bean filter (auto-registered)' },
];

const firstPayload = {
  requestId: 'req-first',
  currentRequestTrace: CURRENT_MARKERS,
  previousCompletedTrace: null,
  registeredFilters: FILTERS,
  analysis: [
    'filter:ordering:before precedes filter:trace:before — the LOWER order value enters the chain earlier.',
    'this trace stops at the handler on purpose: postHandle, afterCompletion and both filter after-markers run AFTER the response is serialized.',
  ],
};

const secondPayload = {
  requestId: 'req-second',
  currentRequestTrace: CURRENT_MARKERS,
  previousCompletedTrace: {
    requestId: 'req-first',
    method: 'GET',
    path: PIPELINE_PATH,
    status: 200,
    markers: FULL_UNWIND,
  },
  registeredFilters: FILTERS,
  analysis: firstPayload.analysis,
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PipelineDemo', () => {
  it('renders the live marker sequence, the chain table, and the analysis', async () => {
    stubApiFetch([{ url: PIPELINE_PATH, body: firstPayload }]);

    render(<PipelineDemo />);

    expect(await screen.findByTestId('middleware-pipeline-report')).toBeInTheDocument();
    const current = screen.getByTestId('middleware-pipeline-current-markers');
    expect(current).toHaveTextContent('filter:ordering:before');
    expect(current).toHaveTextContent('filter:trace:before');
    expect(current).toHaveTextContent('interceptor:preHandle');
    expect(current).toHaveTextContent('handler:middleware-pipeline');
    expect(current).not.toHaveTextContent('afterCompletion');

    // markers render in order — the first marker is the ordering filter's before
    expect(current.querySelector('li')).toHaveTextContent('filter:ordering:before');

    // nothing completed yet — the hint says so
    expect(screen.getByTestId('middleware-pipeline-previous')).toHaveTextContent(
      'nothing has completed yet',
    );

    const filters = screen.getByTestId('middleware-pipeline-filters');
    expect(filters).toHaveTextContent('middlewareOrderingFilter');
    expect(filters).toHaveTextContent('20');
    expect(filters).toHaveTextContent('pipelineTraceFilterRegistration');
    expect(filters).toHaveTextContent('FilterRegistrationBean');
    expect(filters).toHaveTextContent('requestIdFilter');

    const analysis = screen.getByTestId('middleware-pipeline-analysis');
    expect(analysis).toHaveTextContent('LOWER order value');
    expect(analysis).toHaveTextContent('stops at the handler');
  });

  it('run again lands the previous fetch in the completed-trace slot with the full unwind', async () => {
    stubApiFetch([{ url: PIPELINE_PATH, body: firstPayload }]);
    render(<PipelineDemo />);
    expect(await screen.findByTestId('middleware-pipeline-report')).toBeInTheDocument();

    stubApiFetch([{ url: PIPELINE_PATH, body: secondPayload }]);
    fireEvent.click(screen.getByTestId('middleware-pipeline-run-again'));

    await waitFor(() =>
      expect(screen.getByTestId('middleware-pipeline-previous-markers')).toBeInTheDocument(),
    );
    const previous = screen.getByTestId('middleware-pipeline-previous-markers');
    expect(screen.getByTestId('middleware-pipeline-previous')).toHaveTextContent('req-first');
    expect(previous).toHaveTextContent('interceptor:postHandle');
    expect(previous).toHaveTextContent('interceptor:afterCompletion');
    expect(previous).toHaveTextContent('filter:trace:after');
    expect(previous).toHaveTextContent('filter:ordering:after');

    // the marker list is ordered: the unwind's last marker is the outermost filter's after
    const items = previous.querySelectorAll('li');
    expect(items[items.length - 1]).toHaveTextContent('filter:ordering:after');

    // the current trace still stops at the handler
    expect(screen.getByTestId('middleware-pipeline-current-markers')).not.toHaveTextContent('postHandle');
  });

  it('shows the error envelope message when the endpoint fails', async () => {
    stubApiFetch([
      {
        url: PIPELINE_PATH,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-5' },
        },
      },
    ]);

    render(<PipelineDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the pipeline trace');
    expect(alert).toHaveTextContent('boom');
    expect(screen.queryByTestId('middleware-pipeline-report')).not.toBeInTheDocument();
  });
});
