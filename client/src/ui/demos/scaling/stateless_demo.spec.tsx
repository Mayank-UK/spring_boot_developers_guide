import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import StatelessDemo from './stateless_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * StatelessDemo spec — the two-replica experiment over stubbed endpoints:
 * the panel paints the process identity, each persona's private counter,
 * the shared store's value, and what THIS caller was told; store/routing
 * selections ride the request URL; the local-memory failure mode shows
 * up in the call log (the number going backwards); reset rewinds the
 * fleet; and a degraded server renders the error panel. Every number
 * asserted here comes from the mocked SERVER answer — the component
 * narrates nothing it did not fetch.
 */

interface Captured {
  url: string;
  method: string;
}

function probe(overrides?: {
  routedTo?: string;
  routing?: string;
  stateHome?: string;
  reportedValue?: number;
  lostIfLocal?: number;
  externalState?: number;
  localA?: number;
  localB?: number;
  totalRequests?: number;
}) {
  const reported = overrides?.reportedValue ?? 3;
  return {
    processInstanceId: '41782@macbook.local',
    processStartedAt: '2026-10-07T14:02:11Z',
    totalRequests: overrides?.totalRequests ?? 3,
    routedTo: overrides?.routedTo ?? 'server-a',
    routing: overrides?.routing ?? 'round-robin',
    stateHome: overrides?.stateHome ?? 'external',
    instances: [
      { id: 'server-a', localState: overrides?.localA ?? 2 },
      { id: 'server-b', localState: overrides?.localB ?? 1 },
    ],
    externalState: overrides?.externalState ?? 3,
    reportedValue: reported,
    lostIfLocal: overrides?.lostIfLocal ?? 1,
    reading: [
      `request #${String(overrides?.totalRequests ?? 3)} routed to ${overrides?.routedTo ?? 'server-a'} (round-robin) — server-a's own memory holds ${String(overrides?.localA ?? 2)} request(s), server-b's holds ${String(overrides?.localB ?? 1)}.`,
      overrides?.stateHome === 'local'
        ? `the value lives INSIDE ${overrides?.routedTo ?? 'server-a'} only: this answer says ${String(reported)}, but ${String(overrides?.totalRequests ?? 3)} request(s) have been made — routing just forgot ${String(overrides?.lostIfLocal ?? 1)} of your history.`
        : 'the value that matters lives OUTSIDE both replicas: the shared store reports 3 — any instance, any routing order, the same monotonic answer.',
      "server-local memories have diverged (2 vs 1) — harmless, because they are ephemeral; statelessness never claimed instances share a heap.",
      'both replicas are simulated inside this one JVM (process 41782@macbook.local); in production each is a pod and the shared store is redis or PostgreSQL over the network.',
    ],
  };
}

type Down = { current: boolean };

/** One stub for a whole test: the GET answers the mocked probe (or the 503 envelope). */
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
            error: { code: 'service_unavailable', message: 'probe unreachable', requestId: 'x' },
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

describe('StatelessDemo', () => {
  it('paints the probe: identity, both private counters, the shared truth, and what this caller was told', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, () => probe());

    render(<StatelessDemo />);

    // idle before the first request — the experiment is opt-in
    expect(screen.getByTestId('scaling-report')).toHaveTextContent('not run yet');

    fireEvent.click(screen.getByTestId('scaling-send'));

    // settled state first: the report panel testid exists from the idle
    // paint ("not run yet"), so waiting on the element alone can resolve
    // before the fetch paints under load — wait for the fetched identity.
    await waitFor(() => {
      expect(screen.getByTestId('scaling-report')).toHaveTextContent('41782@macbook.local');
    });

    const panel = screen.getByTestId('scaling-report');
    expect(panel).toHaveTextContent('41782@macbook.local');
    const instances = screen.getAllByTestId('scaling-instance');
    expect(instances).toHaveLength(2);
    expect(instances[0]).toHaveTextContent('server-a');
    expect(instances[0]).toHaveTextContent('own memory: 2 request(s)');
    expect(instances[1]).toHaveTextContent('server-b');
    expect(instances[1]).toHaveTextContent('own memory: 1 request(s)');
    expect(screen.getByTestId('scaling-external')).toHaveTextContent('shared store: 3');
    expect(screen.getByTestId('scaling-last')).toHaveTextContent('routed to server-a (round-robin)');
    expect(screen.getByTestId('scaling-last')).toHaveTextContent('caller told 3');
    expect(screen.getByTestId('scaling-last')).toHaveTextContent('any replica, same answer');
    expect(screen.getByTestId('scaling-reading-1')).toHaveTextContent('same monotonic answer');
    // the default request is round-robin external: store rides the URL, no instance pin
    expect(captured[0]?.url).toContain('/demo/scaling/stateless?store=external');
    expect(captured[0]?.url).not.toContain('instance=');
  });

  it('rides the selected store and routing on the request URL, local mode and all', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: false }, () =>
      probe({
        routedTo: 'server-b',
        routing: 'sticky',
        stateHome: 'local',
        reportedValue: 1,
        lostIfLocal: 3,
        localA: 3,
        localB: 1,
        totalRequests: 4,
      }),
    );

    render(<StatelessDemo />);
    fireEvent.click(screen.getByRole('button', { name: 'local' }));
    fireEvent.click(screen.getByRole('button', { name: 'b' }));
    fireEvent.click(screen.getByTestId('scaling-send'));

    // settled state first: the report panel testid exists from the idle
    // paint ("not run yet"), so waiting on the element alone can beat the
    // fetch under load — wait for the fetched numbers before asserting.
    await waitFor(() => {
      expect(screen.getByTestId('scaling-report')).toHaveTextContent('own memory: 3 request(s)');
    });

    const panel = screen.getByTestId('scaling-report');
    expect(panel).toHaveTextContent('own memory: 3 request(s)');
    expect(screen.getByTestId('scaling-last')).toHaveTextContent('routed to server-b (sticky)');
    expect(screen.getByTestId('scaling-last')).toHaveTextContent(
      'a local-memory world would have forgotten 3',
    );
    expect(captured[0]?.url).toContain('/demo/scaling/stateless?store=local&instance=b');
  });

  it('shows the local-memory failure in the call log — the number goes backwards', async () => {
    const answers = [
      probe({ routedTo: 'server-a', routing: 'sticky', stateHome: 'local', reportedValue: 3, lostIfLocal: 0, localA: 3, localB: 0, totalRequests: 3 }),
      probe({ routedTo: 'server-b', routing: 'sticky', stateHome: 'local', reportedValue: 1, lostIfLocal: 3, localA: 3, localB: 1, totalRequests: 4 }),
    ];
    let call = 0;
    stubEndpoint([], { current: false }, () => answers[Math.min(call, answers.length - 1)] ?? answers[0]);

    render(<StatelessDemo />);
    fireEvent.click(screen.getByTestId('scaling-send'));
    await screen.findByTestId('scaling-log');
    call += 1;
    fireEvent.click(screen.getByTestId('scaling-send'));

    const log = await waitFor(() => {
      const entries = screen.getAllByTestId('scaling-log');
      expect(entries).toHaveLength(2);
      return entries;
    });
    // newest first: the backwards answer on top, the history it forgot below
    expect(log[0]).toHaveTextContent('server-b: told 1 (local)');
    expect(log[1]).toHaveTextContent('server-a: told 3 (local)');
  });

  it('reset rewinds the fleet and clears the call log', async () => {
    const captured: Captured[] = [];
    const answers = [
      probe(),
      {
        processInstanceId: '41782@macbook.local',
        processStartedAt: '2026-10-07T14:02:11Z',
        totalRequests: 0,
        routedTo: null,
        routing: 'round-robin',
        stateHome: 'external',
        instances: [
          { id: 'server-a', localState: 0 },
          { id: 'server-b', localState: 0 },
        ],
        externalState: 0,
        reportedValue: 0,
        lostIfLocal: 0,
        reading: [
          "all counters reset — both personas' private memories, the shared store, and the routing cursor.",
          'the next round-robin request lands on server-a.',
        ],
      },
    ];
    let call = 0;
    stubEndpoint(captured, { current: false }, () => answers[call] ?? answers[0] ?? {});

    render(<StatelessDemo />);
    fireEvent.click(screen.getByTestId('scaling-send'));
    await screen.findByTestId('scaling-log');
    call = 1;
    fireEvent.click(screen.getByTestId('scaling-reset'));

    // settled state first: the pre-reset panel still shows the first
    // answer ("shared store: 3") while the reset request is in flight —
    // wait for the rewound answer to paint before asserting.
    await waitFor(() => {
      expect(screen.getByTestId('scaling-report')).toHaveTextContent('shared store: 0');
    });

    const panel = screen.getByTestId('scaling-report');
    expect(panel).toHaveTextContent('shared store: 0');
    expect(panel).toHaveTextContent('own memory: 0 request(s)');
    // routedTo null — the "this request" line is absent after a reset
    expect(screen.queryByTestId('scaling-last')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scaling-log')).not.toBeInTheDocument();
    expect(screen.getByTestId('scaling-reading-0')).toHaveTextContent('all counters reset');
    expect(captured[1]?.url).toContain('/demo/scaling/stateless/reset');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoint([], { current: true }, () => probe());

    render(<StatelessDemo />);
    fireEvent.click(screen.getByTestId('scaling-send'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('probe unreachable');
  });
});
