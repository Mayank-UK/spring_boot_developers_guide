import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ArchTracerDemo from './tracer_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ArchTracerDemo spec — topic 32's arch:tracer widget over the shared
 * guarded pipeline: the layered story renders each step with its evidence
 * citation and the aspect-recorded use-case invocation with real timings;
 * both correlation ids show (outer frame, nested inner request); switching
 * the target to posts re-fetches the posts trace; the map panel renders
 * computed layer counts, the port/adapter inventory with its composition-
 * root binding, and the exemption ledger; a broken trace shows the error
 * panel.
 */

const TRACE_PATH_CONTENT = '/api/demo/arch/trace?target=content';
const TRACE_PATH_POSTS = '/api/demo/arch/trace?target=posts';
const MAP_PATH = '/api/demo/arch/map';

const step = (stage: string, label: string, evidence: string) => ({ stage, label, evidence });

const trace = (overrides: Record<string, unknown> = {}) => ({
  target: 'content',
  path: '/api/content/architecture',
  controller: 'ContentController.detail',
  useCase: 'GetTopicById',
  adapter: 'guide.data.content.ContentRepositoryAdapter',
  note: 'the guide serving this very page',
  outer: { requestId: 'outer-1', markers: ['filter:ordering:before', 'handler:arch-tracer'] },
  inner: {
    requestId: 'inner-1',
    status: 200,
    matched: true,
    markers: ['filter:ordering:before', 'interceptor:preHandle', 'interceptor:postHandle'],
  },
  invocations: [
    {
      method: 'guide.domain.arch.ProbeInternalApi.execute',
      argsSummary: '(CONTENT)',
      outcome: 'ok',
      durationNanos: 1234567,
      requestId: 'outer-1',
    },
  ],
  steps: [
    step('filter', 'outermost filter (order 20) entered', 'topic 08 ring: filter:ordering:before'),
    step('controller', 'ContentController.detail — thin', 'controller source'),
    step('use-case', 'guide.domain.arch.ProbeInternalApi.execute → ok', 'topic 06 aspect record'),
    step('done', 'response left the server — status 200, 4567 body bytes', 'probe measurement'),
  ],
  timings: { roundTripNanos: 1100000, useCaseNanos: 1234567, overheadNanos: 134567, status: 200, bodyBytes: 4567 },
  ...overrides,
});

const map = () => ({
  layers: [
    { layer: 'domain', packageRoot: 'guide.domain', classes: 260, areas: [{ area: 'posts', classes: 12 }] },
    { layer: 'presentation', packageRoot: 'guide.presentation', classes: 53, areas: [] },
  ],
  ports: [
    {
      port: 'guide.domain.posts.PostRepository',
      adapters: [
        'guide.data.posts.InMemoryPostRepository',
        'guide.data.posts.PgPostRepository',
        'guide.data.posts.ScenarioRoutingPostRepository',
      ],
      bindings: ['PostsDemoConfig.postRepository'],
    },
  ],
  teachingExhibits: [
    {
      file: 'guide/presentation/graphql/PostsGraphqlController.java',
      importPrefix: 'guide.data.comments.',
      reason: "reads the fake repository's batchCallCount",
    },
  ],
  domainExemptions: [
    {
      file: 'guide/domain/posts/PostStatus.java',
      importPrefix: 'com.fasterxml.jackson.annotation.',
      reason: 'enum serialized by name',
    },
  ],
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ArchTracerDemo', () => {
  it('renders the layered story with evidence citations and real timings', async () => {
    stubApiFetch([
      { url: TRACE_PATH_CONTENT, body: trace() },
      { url: MAP_PATH, body: map() },
    ]);

    render(<ArchTracerDemo />);

    await screen.findByTestId('arch-step-0');
    expect(screen.getByTestId('arch-step-1')).toHaveTextContent('ContentController.detail');
    expect(screen.getByTestId('arch-step-2')).toHaveTextContent('topic 06 aspect record');
    expect(screen.getByTestId('arch-step-2')).toHaveTextContent('ProbeInternalApi.execute');
    expect(screen.getByTestId('arch-outer-id')).toHaveTextContent('outer-1');
    expect(screen.getByTestId('arch-inner-id')).toHaveTextContent('inner-1');
    expect(screen.getByTestId('arch-timings')).toHaveTextContent('1100.0');
    expect(screen.getByTestId('arch-invocation-0')).toHaveTextContent('ok');
  });

  it('switching to posts re-runs the trace against the posts path', async () => {
    stubApiFetch([
      { url: TRACE_PATH_CONTENT, body: trace() },
      { url: MAP_PATH, body: map() },
      { url: TRACE_PATH_POSTS, body: trace({ target: 'posts', path: '/api/demo/api/posts?page=0&size=3' }) },
      { url: MAP_PATH, body: map() },
    ]);

    render(<ArchTracerDemo />);
    await screen.findByTestId('arch-step-0');

    fireEvent.click(screen.getByTestId('arch-target-posts'));
    await waitFor(() =>
      expect(screen.getByTestId('arch-target-note')).toHaveTextContent('/api/demo/api/posts'),
    );
  });

  it('renders the computed map: layers, port inventory, exemption ledger', async () => {
    stubApiFetch([
      { url: TRACE_PATH_CONTENT, body: trace() },
      { url: MAP_PATH, body: map() },
    ]);

    render(<ArchTracerDemo />);

    await screen.findByTestId('arch-layer-domain');
    expect(screen.getByTestId('arch-layer-domain')).toHaveTextContent('260');
    expect(screen.getByTestId('arch-layer-presentation')).toHaveTextContent('53');
    expect(screen.getByTestId('arch-port-PostRepository')).toHaveTextContent('PgPostRepository');
    expect(screen.getByTestId('arch-port-PostRepository')).toHaveTextContent('PostsDemoConfig.postRepository');
    expect(screen.getAllByTestId('arch-exhibit').length).toBe(1);
    expect(screen.getByTestId('arch-exhibit')).toHaveTextContent('batchCallCount');
    expect(screen.getByTestId('arch-exemptions')).toHaveTextContent('PostStatus.java');
  });

  it('renders the error panel when the trace cannot be read', async () => {
    stubApiFetch([{ url: TRACE_PATH_CONTENT, brokenJson: true, status: 200 }]);

    render(<ArchTracerDemo />);

    const panel = await screen.findByRole('alert');
    expect(panel).toBeInTheDocument();
  });
});
