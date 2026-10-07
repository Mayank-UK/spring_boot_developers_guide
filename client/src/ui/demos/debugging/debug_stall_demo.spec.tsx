import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import DebugStallDemo from './debug_stall_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * DebugStallDemo spec — the thread-dump theater over stubbed endpoints:
 * the idle report paints first with its reading line; parking the lock
 * stall POSTs kind=lock and renders the dump-shaped facts (victim BLOCKED
 * naming the holder, owner TIMED_WAITING, stack frames top-down); the spin
 * stall POSTs kind=spin and renders RUNNABLE; stop POSTs and returns to the
 * idle report with the previous duration; and a degraded server renders
 * the error panel. The reading lines must come from the SERVER's answer —
 * the component narrates nothing the snapshot did not say.
 */

interface Captured {
  url: string;
  method: string;
}

type Mode = 'idle' | 'lock' | 'spin' | 'down';

const LOCK_REPORT = {
  running: true,
  kind: 'lock',
  startedAtMs: 1760000000000,
  lastParkedMs: 0,
  jvmThreadCount: 42,
  threads: [
    {
      name: 'debug-stall-owner',
      state: 'TIMED_WAITING',
      lock: null,
      lockOwner: null,
      stack: ['at java.lang.Thread.sleep(Thread.java:540)'],
    },
    {
      name: 'debug-stall-victim',
      state: 'BLOCKED',
      lock: 'java.lang.Object@1a2b3c4d',
      lockOwner: 'debug-stall-owner',
      stack: ['at guide.data.debug.InProcessStallTheater.lambda$startLockStall$1(InProcessStallTheater.java:127)'],
    },
  ],
  reading: [
    'debug-stall-owner is TIMED_WAITING — asleep in a timed park, and if it holds a monitor, sleeping does NOT release it',
    'debug-stall-victim is BLOCKED, waiting to lock java.lang.Object@1a2b3c4d held by debug-stall-owner — the dump NAMES the holder, which is the whole trick',
    '2 of 42 JVM threads shown — a real dump lists every line; you learn to read the ones that matter',
  ],
};

const SPIN_REPORT = {
  running: true,
  kind: 'spin',
  startedAtMs: 1760000000000,
  lastParkedMs: 0,
  jvmThreadCount: 41,
  threads: [
    {
      name: 'debug-stall-spinner',
      state: 'RUNNABLE',
      lock: null,
      lockOwner: null,
      stack: ['at guide.data.debug.InProcessStallTheater.lambda$startSpinStall$2(InProcessStallTheater.java:141)'],
    },
  ],
  reading: [
    "debug-stall-spinner is RUNNABLE — runnable-forever is its own smell: sample the stack twice and it is still here, the poor man's profiler",
  ],
};

const IDLE_REPORT = {
  running: false,
  kind: '',
  startedAtMs: 1760000000000,
  lastParkedMs: 4213,
  jvmThreadCount: 40,
  threads: [],
  reading: [
    'no stall is parked — the previous one ran for 4213ms; start another and snapshot while it runs',
  ],
};

/**
 * One stub for a whole test: start POSTs answer by their kind param, the
 * stop POST answers idle, and the plain GET answers whatever `mode` says —
 * so a test flips the theater by mutating `mode` after clicking.
 */
function stubEndpoints(captured: Captured[], mode: { current: Mode }): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      captured.push({ url, method });
      const respond = (status: number, body: unknown) => ({
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
      });
      if (mode.current === 'down') {
        return respond(503, {
          error: { code: 'service_unavailable', message: 'theater unreachable', requestId: 'x' },
        });
      }
      if (url.includes('/stall/start')) {
        return respond(200, url.includes('kind=lock') ? LOCK_REPORT : SPIN_REPORT);
      }
      if (url.includes('/stall/stop')) {
        return respond(200, IDLE_REPORT);
      }
      return respond(200, mode.current === 'lock' ? LOCK_REPORT : mode.current === 'spin' ? SPIN_REPORT : IDLE_REPORT);
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DebugStallDemo', () => {
  it('paints the idle report first, with the server reading line', async () => {
    stubEndpoints([], { current: 'idle' });

    render(<DebugStallDemo />);

    const report = await screen.findByTestId('debug-report');
    expect(report).toHaveTextContent('idle — previous stall ran 4213ms');
    expect(report).toHaveTextContent('40 JVM threads live');
    expect(screen.getByTestId('debug-reading-0')).toHaveTextContent('no stall is parked');
    expect(screen.getByTestId('debug-idle')).toBeDefined();
  });

  it('parks the lock stall and renders the dump-shaped facts', async () => {
    const captured: Captured[] = [];
    const mode = { current: 'idle' as Mode };
    stubEndpoints(captured, mode);

    render(<DebugStallDemo />);
    await screen.findByTestId('debug-report');

    fireEvent.click(screen.getByTestId('debug-start-lock'));

    const report = await waitFor(() => {
      const found = screen.getByTestId('debug-report');
      expect(found).toHaveTextContent('parked (lock)');
      return found;
    });
    expect(report).toHaveTextContent('"debug-stall-owner"');
    expect(report).toHaveTextContent('TIMED_WAITING');
    expect(report).toHaveTextContent('"debug-stall-victim"');
    expect(report).toHaveTextContent('BLOCKED');
    expect(report).toHaveTextContent('held by "debug-stall-owner"');
    expect(report).toHaveTextContent('at java.lang.Thread.sleep(Thread.java:540)');
    expect(report).toHaveTextContent('2 of 42 JVM threads shown');

    const startCall = captured.find((call) => call.url.includes('/stall/start'));
    expect(startCall?.method).toBe('POST');
    expect(startCall?.url).toContain('kind=lock');
  });

  it('parks the spin stall and renders RUNNABLE with its reading', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, { current: 'spin' });

    render(<DebugStallDemo />);

    const thread = await screen.findByTestId('debug-thread-0');
    expect(thread).toHaveTextContent('"debug-stall-spinner"');
    expect(thread).toHaveTextContent('RUNNABLE');
    expect(screen.getByTestId('debug-reading-0')).toHaveTextContent("poor man's profiler");

    const startCall = captured.find((call) => call.url.includes('/stall/start'));
    expect(startCall).toBeUndefined(); // this paint came from the initial GET
  });

  it('stops the stall and shows the idle report with the duration', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, { current: 'lock' });

    render(<DebugStallDemo />);
    await screen.findByTestId('debug-thread-1');

    fireEvent.click(screen.getByTestId('debug-stop'));

    const report = await waitFor(() => {
      const found = screen.getByTestId('debug-report');
      expect(found).toHaveTextContent('idle — previous stall ran 4213ms');
      return found;
    });
    expect(report).toHaveTextContent('nothing parked');
    const stopCall = captured.find((call) => call.url.includes('/stall/stop'));
    expect(stopCall?.method).toBe('POST');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], { current: 'down' });

    render(<DebugStallDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('theater unreachable');
  });
});
