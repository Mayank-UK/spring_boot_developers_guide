import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `debug:stall` — topic 35 (debugging), the
 * thread-dumps section. The stall theater as a live surface:
 *
 * - "lock contention" parks the classic pair — an owner asleep while
 *   HOLDING a monitor, a victim BLOCKED at it — and "busy spin" parks a
 *   RUNNABLE-forever thread;
 * - the snapshot panel renders exactly what a thread dump would print:
 *   each thread's name, state, the monitor it waits for WITH the holder's
 *   name, and the stack frames top-down, plus the reading lines that
 *   narrate what the states mean;
 * - stop wakes and joins everything, so the demo is repeatable and leaves
 *   nothing behind — the same discipline you want from your own tooling.
 */

interface StallThread {
  name: string;
  state: string;
  lock: string | null;
  lockOwner: string | null;
  stack: string[];
}

interface StallReport {
  running: boolean;
  kind: string;
  startedAtMs: number;
  lastParkedMs: number;
  jvmThreadCount: number;
  threads: StallThread[];
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function optionalString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === undefined || value === null) return null;
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string when present`);
  return value;
}

function parseThread(source: unknown): StallThread {
  if (!isRecord(source)) throw new Error('a thread view must be an object');
  const stack = source.stack;
  if (!Array.isArray(stack)) throw new Error('field "stack" must be an array');
  return {
    name: String(source.name ?? ''),
    state: String(source.state ?? ''),
    lock: optionalString(source, 'lock'),
    lockOwner: optionalString(source, 'lockOwner'),
    stack: stack.map((frame) => String(frame)),
  };
}

function parseReport(source: unknown): StallReport {
  if (!isRecord(source)) throw new Error('the stall report must be an object');
  const threads = source.threads;
  const reading = source.reading;
  if (!Array.isArray(threads) || !Array.isArray(reading)) {
    throw new Error('the stall report carries threads and reading arrays');
  }
  const lastParkedMs = source.lastParkedMs;
  const jvmThreadCount = source.jvmThreadCount;
  const startedAtMs = source.startedAtMs;
  if (
    typeof lastParkedMs !== 'number' ||
    typeof jvmThreadCount !== 'number' ||
    typeof startedAtMs !== 'number'
  ) {
    throw new Error('the stall report carries its timing and count fields as numbers');
  }
  return {
    running: source.running === true,
    kind: String(source.kind ?? ''),
    startedAtMs,
    lastParkedMs,
    jvmThreadCount,
    threads: threads.map(parseThread),
    reading: reading.map((line) => String(line)),
  };
}

/** Fetch a path as JSON with method support, guarding transport + envelope like every demo. */
async function requestJson(config: AppConfig, path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'content-type': 'application/json' },
      ...init,
    });
  } catch (error) {
    throw new NetworkException('Could not reach the server.', { cause: error });
  }
  let parsed: unknown;
  try {
    parsed = await response.json();
  } catch (error) {
    throw new ServerException(`Request failed with status ${String(response.status)}.`, {
      status: response.status,
      cause: error,
    });
  }
  if (!response.ok) {
    const code = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.code ?? 'unknown_error')
      : 'unknown_error';
    const message = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.message ?? `Request failed with status ${String(response.status)}.`)
      : `Request failed with status ${String(response.status)}.`;
    throw new ServerException(message, { code, status: response.status });
  }
  return parsed;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const ACTION_BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';

function stateClass(state: string): string {
  switch (state) {
    case 'BLOCKED':
      return 'text-error';
    case 'RUNNABLE':
      return 'text-on-surface';
    default:
      return 'text-on-surface-variant';
  }
}

export default function DebugStallDemo() {
  const config = container.appConfig;
  const [report, setReport] = useState<StallReport | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const loadReport = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseReport(await requestJson(config, '/demo/debug/stall/threads')),
    ).then((result) => {
      if (result.ok) {
        setReport(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  useEffect(() => {
    loadReport();
  }, [loadReport]);

  const start = (kind: 'lock' | 'spin') =>
    void safeRepositoryCall(async () =>
      parseReport(await requestJson(config, `/demo/debug/stall/start?kind=${kind}`, { method: 'POST' })),
    ).then((result) => {
      if (result.ok) {
        setReport(result.value);
        setPanel({ kind: 'idle' });
      } else {
        // a typed 409 (already parked) or 400 (unknown kind) stays visible
        setPanel({ kind: 'error', failure: result.failure });
      }
    });

  const stop = () =>
    void safeRepositoryCall(async () =>
      parseReport(await requestJson(config, '/demo/debug/stall/stop', { method: 'POST' })),
    ).then((result) => {
      if (result.ok) {
        setReport(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>park a stall:</span>
        <button type="button" data-testid="debug-start-lock" onClick={() => start('lock')} className={ACTION_BUTTON}>
          lock contention
        </button>
        <button type="button" data-testid="debug-start-spin" onClick={() => start('spin')} className={ACTION_BUTTON}>
          busy spin
        </button>
        <button type="button" data-testid="debug-stop" onClick={stop} className={BUTTON}>
          stop
        </button>
        <button type="button" data-testid="debug-refresh" onClick={loadReport} className={BUTTON}>
          snapshot
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="debug-loading" className="font-mono text-caption text-on-surface-variant">
          reading the theater…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the stall theater" failure={panel.failure} />}

      <section
        data-testid="debug-report"
        className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        <p className={OVERLINE}>
          the theater — {report === null ? '…' : report.running
            ? `parked (${report.kind})`
            : report.lastParkedMs > 0
              ? `idle — previous stall ran ${String(report.lastParkedMs)}ms`
              : 'idle'}
          {report !== null && (
            <span className="text-on-surface-variant"> · {String(report.jvmThreadCount)} JVM threads live</span>
          )}
        </p>
        {report?.reading.map((line, index) => (
          <span key={line} data-testid={`debug-reading-${String(index)}`} className="text-on-surface-variant">
            {line}
          </span>
        ))}
        {report?.threads.map((thread, index) => (
          <div
            key={thread.name}
            data-testid={`debug-thread-${String(index)}`}
            className="flex flex-col gap-xxs border-t border-outline pt-xxs"
          >
            <span>
              <span className="font-semibold">"{thread.name}"</span>{' '}
              <span className={stateClass(thread.state)}>{thread.state}</span>
              {thread.lock !== null && (
                <span className="text-on-surface-variant"> waiting to lock {thread.lock}</span>
              )}
              {thread.lockOwner !== null && (
                <span className="text-on-surface-variant"> held by "{thread.lockOwner}"</span>
              )}
            </span>
            {thread.stack.map((frame, frameIndex) => (
              <span key={`${thread.name}-${String(frameIndex)}`} className="break-all pl-sm text-on-surface-variant">
                {frame}
              </span>
            ))}
          </div>
        ))}
        {report !== null && report.threads.length === 0 && panel.kind === 'idle' && (
          <span data-testid="debug-idle" className="text-on-surface-variant">
            nothing parked — the next dump would show only the JVM's own threads
          </span>
        )}
      </section>
    </div>
  );
}
