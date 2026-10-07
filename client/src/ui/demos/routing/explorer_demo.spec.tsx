import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ExplorerDemo from './explorer_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ExplorerDemo spec — the routing explorer over the shared guarded pipeline:
 * the table renders the real registry rows (ANY for method-less mappings),
 * the prefix filter actually issues the filtered fetch, the match probe
 * renders verdict + winner + ranked candidates (and the typed no-match
 * verdict), and the server's error envelope surfaces through the error
 * panel. Fetch is stubbed; both endpoints are GETs with query strings.
 */

const TABLE_URL = '/api/demo/routing/table?prefix=%2Fapi%2Fdemo';
const HEALTH_URL = '/api/demo/routing/table?prefix=%2Fapi%2Fhealth';
const DEFAULT_PROBE_URL =
  '/api/demo/routing/match?path=%2Fapi%2Fcontent%2Fjava-language&method=GET';
const PROBE_URL =
  '/api/demo/routing/match?path=%2Fapi%2Fdemo%2Frouting%2Ftable&method=GET';
const NO_MATCH_URL = '/api/demo/routing/match?path=%2Fapi%2Fdefinitely%2Fmissing&method=GET';

const route = (pattern: string, methods: string[], controller: string, handler: string) => ({
  pattern,
  methods,
  controller,
  bean: `${controller}`,
  handler,
  produces: [] as string[],
  consumes: [] as string[],
});

const tableView = {
  prefix: '/api/demo',
  total: 3,
  routes: [
    route('/api/demo/routing/match', ['GET'], 'RoutingDemoController', 'match'),
    route('/api/demo/routing/table', ['GET'], 'RoutingDemoController', 'table'),
    { ...route('/api/demo/routing/sectioned', [], 'X', 'any'), produces: ['application/json'] },
  ],
};

const matchReport = {
  method: 'GET',
  path: '/api/demo/routing/table',
  verdict: 'matched',
  matched: true,
  winner: route('/api/demo/routing/table', ['GET'], 'RoutingDemoController', 'table'),
  captured: {},
  candidates: [
    {
      entry: route('/api/demo/routing/table', ['GET'], 'RoutingDemoController', 'table'),
      pathMatched: true,
      methodMatched: true,
      captured: {},
      score: 0,
      catchAll: false,
      explanation: 'rank 1 of 2 matching — specificity score 0, no captures — maximally specific',
    },
    {
      entry: route('/api/demo/routing/{section}', ['GET'], 'RoutingDemoController', 'sectionSpecimen'),
      pathMatched: true,
      methodMatched: true,
      captured: { section: 'table' },
      score: 1,
      catchAll: false,
      explanation: 'rank 2 of 2 matching — specificity score 1 (1 captured variable)',
    },
  ],
  considered: 42,
  nonMatched: 40,
};

/** The demo's mount-time probe: /api/content/java-language, the content route. */
const defaultProbeReport = {
  ...matchReport,
  path: '/api/content/java-language',
  winner: route('/api/content/{topicId}', ['GET'], 'ContentController', 'detail'),
  captured: { topicId: 'java-language' },
};

const noMatchReport = {
  method: 'GET',
  path: '/api/definitely/missing',
  verdict: 'no_match',
  matched: false,
  winner: null,
  captured: {},
  candidates: [],
  considered: 42,
  nonMatched: 42,
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function stubDefault() {
  stubApiFetch([
    { url: TABLE_URL, body: tableView },
    { url: DEFAULT_PROBE_URL, body: defaultProbeReport },
  ]);
}

describe('ExplorerDemo', () => {
  it('renders the filtered table: rows, ANY for method-less mappings, produces column', async () => {
    stubDefault();

    render(<ExplorerDemo />);

    const table = await screen.findByTestId('routing-table');
    expect(table).toHaveTextContent('/api/demo/routing/table');
    expect(table).toHaveTextContent('RoutingDemoController#table');
    expect(table).toHaveTextContent('ANY');
    expect(table).toHaveTextContent('→ application/json');
    expect(screen.getByTestId('routing-table-count')).toHaveTextContent('3 routes under /api/demo');
  });

  it('issues a fresh fetch when the prefix changes and filter is clicked', async () => {
    stubApiFetch([
      { url: TABLE_URL, body: tableView },
      {
        url: HEALTH_URL,
        body: { prefix: '/api/health', total: 1, routes: [route('/api/health', ['GET'], 'HealthController', 'health')] },
      },
      { url: PROBE_URL, body: matchReport },
    ]);

    render(<ExplorerDemo />);
    await screen.findByTestId('routing-table');

    fireEvent.change(screen.getByTestId('routing-prefix-input'), { target: { value: '/api/health' } });
    fireEvent.click(screen.getByTestId('routing-table-load'));

    await waitFor(() =>
      expect(screen.getByTestId('routing-table-count')).toHaveTextContent('1 routes under /api/health'),
    );
  });

  it('renders the ranked match report: verdict, winner, and the WHY lines', async () => {
    stubApiFetch([
      { url: TABLE_URL, body: tableView },
      { url: DEFAULT_PROBE_URL, body: defaultProbeReport },
      { url: PROBE_URL, body: matchReport },
    ]);

    render(<ExplorerDemo />);
    await screen.findByTestId('routing-winner');
    // the mount-time probe answers with the content route and its capture:
    expect(screen.getByTestId('routing-winner')).toHaveTextContent(
      '/api/content/{topicId} → ContentController#detail',
    );
    expect(screen.getByTestId('routing-captured')).toHaveTextContent('{topicId} = "java-language"');

    // re-probe with the explorer's own table path — the exact-vs-variable story
    fireEvent.change(screen.getByTestId('routing-probe-path'), {
      target: { value: '/api/demo/routing/table' },
    });
    fireEvent.click(screen.getByTestId('routing-probe-run'));

    await waitFor(() =>
      expect(screen.getByTestId('routing-winner')).toHaveTextContent(
        '/api/demo/routing/table → RoutingDemoController#table',
      ),
    );
    expect(screen.getByTestId('routing-verdict')).toHaveTextContent('matched');
    const candidates = screen.getAllByTestId('routing-candidate');
    expect(candidates.length).toBe(2);
    expect(candidates[0]).toHaveTextContent('score 0');
    expect(candidates[0]).toHaveTextContent('maximally specific');
    expect(candidates[1]).toHaveTextContent('/api/demo/routing/{section}');
    expect(candidates[1]).toHaveTextContent('rank 2 of 2');
  });

  it('renders the typed no-match verdict with the honest counts', async () => {
    stubApiFetch([
      { url: TABLE_URL, body: tableView },
      { url: DEFAULT_PROBE_URL, body: defaultProbeReport },
      { url: NO_MATCH_URL, body: noMatchReport },
    ]);

    render(<ExplorerDemo />);
    await screen.findByTestId('routing-table');

    fireEvent.change(screen.getByTestId('routing-probe-path'), {
      target: { value: '/api/definitely/missing' },
    });
    fireEvent.click(screen.getByTestId('routing-probe-run'));

    await screen.findByTestId('routing-no-match');
    expect(screen.getByTestId('routing-verdict')).toHaveTextContent('no_match');
    expect(screen.getByTestId('routing-no-match')).toHaveTextContent('42 of 42');
  });

  it('shows the error panel when the table answers with an error envelope', async () => {
    stubApiFetch([
      {
        url: TABLE_URL,
        status: 500,
        body: { error: { code: 'internal_error', message: 'Internal server error', requestId: 'req-7' } },
      },
      { url: DEFAULT_PROBE_URL, body: defaultProbeReport },
    ]);

    render(<ExplorerDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the route table');
    expect(alert).toHaveTextContent('internal_error · status 500 · request req-7');
  });
});
