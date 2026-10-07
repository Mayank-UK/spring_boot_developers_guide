import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `reliability:breaker` — topic 39 (reliability), the
 * circuit-breaker section. The server owns every transition; this panel is
 * the control room:
 *
 * - read the breaker's observable surface (state, window metrics, config
 *   knobs, the server's reading lines);
 * - dial the downstream's personality (healthy / flaky / down / slow) and
 *   fire calls through the whole stack — each answer carries the exact
 *   virtual-time timeline of its attempts (latency, backoff, timeout);
 * - once four failing calls trip the breaker OPEN, watch calls get
 *   rejected AT THE DOOR (zero attempts), force the HALF_OPEN probe, and
 *   close it again with a healthy call; reset rewinds the whole theater.
 *
 * Every number rendered here comes from the server's report — the panel
 * narrates nothing it did not fetch.
 */

interface AttemptReport {
  attempt: number;
  outcome: string;
  startedAtMs: number;
  endedAtMs: number;
  detail: string;
}

interface CallReport {
  outcome: string;
  breakerStateBefore: string;
  breakerStateAfter: string;
  attempts: AttemptReport[];
  totalElapsedMs: number;
  downstreamAttempts: number;
}

interface BreakerStatus {
  state: string;
  downstreamMode: string;
  injectFailureRail: boolean;
  virtualNowMs: number;
  config: {
    failureRateThreshold: number;
    minimumNumberOfCalls: number;
    waitDurationMs: number;
    slidingWindowSize: number;
    permittedInHalfOpen: number;
  };
  metrics: {
    successes: number;
    failures: number;
    notPermitted: number;
    failureRate: number | null;
  };
  openedAtMs: number | null;
  probeEligibleAtMs: number | null;
  recentCalls: CallReport[];
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

const MODES = ['healthy', 'flaky', 'down', 'slow'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseAttempt(source: unknown): AttemptReport {
  if (!isRecord(source)) throw new Error('an attempt must be an object');
  return {
    attempt: Number(source.attempt ?? 0),
    outcome: String(source.outcome ?? ''),
    startedAtMs: Number(source.startedAtMs ?? 0),
    endedAtMs: Number(source.endedAtMs ?? 0),
    detail: String(source.detail ?? ''),
  };
}

function parseCallReport(source: unknown): CallReport {
  if (!isRecord(source)) throw new Error('a call report must be an object');
  const attempts = Array.isArray(source.attempts) ? source.attempts.map(parseAttempt) : [];
  return {
    outcome: String(source.outcome ?? ''),
    breakerStateBefore: String(source.breakerStateBefore ?? ''),
    breakerStateAfter: String(source.breakerStateAfter ?? ''),
    attempts,
    totalElapsedMs: Number(source.totalElapsedMs ?? 0),
    downstreamAttempts: Number(source.downstreamAttempts ?? 0),
  };
}

function parseStatus(source: unknown): BreakerStatus {
  if (!isRecord(source)) throw new Error('the breaker status must be an object');
  if (!isRecord(source.config) || !isRecord(source.metrics) || !Array.isArray(source.reading)) {
    throw new Error('the status carries config, metrics and reading');
  }
  const recent = Array.isArray(source.recentCalls) ? source.recentCalls.map(parseCallReport) : [];
  return {
    state: String(source.state ?? ''),
    downstreamMode: String(source.downstreamMode ?? ''),
    injectFailureRail: source.injectFailureRail === true,
    virtualNowMs: Number(source.virtualNowMs ?? 0),
    config: {
      failureRateThreshold: Number(source.config.failureRateThreshold ?? 0),
      minimumNumberOfCalls: Number(source.config.minimumNumberOfCalls ?? 0),
      waitDurationMs: Number(source.config.waitDurationMs ?? 0),
      slidingWindowSize: Number(source.config.slidingWindowSize ?? 0),
      permittedInHalfOpen: Number(source.config.permittedInHalfOpen ?? 0),
    },
    metrics: {
      successes: Number(source.metrics.successes ?? 0),
      failures: Number(source.metrics.failures ?? 0),
      notPermitted: Number(source.metrics.notPermitted ?? 0),
      failureRate:
        source.metrics.failureRate === null || source.metrics.failureRate === undefined
          ? null
          : Number(source.metrics.failureRate),
    },
    openedAtMs: source.openedAtMs == null ? null : Number(source.openedAtMs),
    probeEligibleAtMs: source.probeEligibleAtMs == null ? null : Number(source.probeEligibleAtMs),
    recentCalls: recent,
    reading: source.reading.map((line) => String(line)),
  };
}

/** Fetch a path as JSON, guarding transport + envelope like every demo. */
async function requestJson(config: AppConfig, path: string, method?: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      method: method ?? 'GET',
      headers: { 'content-type': 'application/json' },
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

const ACTIVE_BUTTON =
  'rounded-pill border border-outline bg-surface-tint px-sm py-xxs font-mono text-caption text-on-surface';

function stateBadge(state: string): string {
  if (state === 'OPEN') return 'rounded-pill border border-outline bg-error-container px-sm py-xxs font-mono text-caption text-on-error-container';
  if (state === 'HALF_OPEN') return 'rounded-pill border border-outline bg-tertiary-container px-sm py-xxs font-mono text-caption text-on-tertiary-container';
  return 'rounded-pill border border-outline bg-surface-tint px-sm py-xxs font-mono text-caption text-on-surface';
}

export default function BreakerDemo() {
  const config = container.appConfig;
  const [status, setStatus] = useState<BreakerStatus | null>(null);
  const [callReport, setCallReport] = useState<CallReport | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const refreshStatus = useCallback(() => {
    void safeRepositoryCall(async () =>
      parseStatus(await requestJson(config, '/demo/reliability/breaker')),
    ).then((result) => {
      if (result.ok) {
        setStatus(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  useEffect(() => {
    refreshStatus();
  }, [refreshStatus]);

  const run = useCallback(
    (path: string, method: string, onDone: () => void) => {
      setPanel({ kind: 'loading' });
      void safeRepositoryCall(async () => requestJson(config, path, method)).then((result) => {
        if (result.ok) {
          onDone();
          refreshStatus();
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
    },
    [config, refreshStatus],
  );

  const callDownstream = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseCallReport(await requestJson(config, '/demo/reliability/call', 'POST')),
    ).then((result) => {
      if (result.ok) {
        setCallReport(result.value);
        refreshStatus();
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config, refreshStatus]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>downstream:</span>
        {MODES.map((mode) => (
          <button
            key={mode}
            type="button"
            data-testid="r39-dial"
            onClick={() => run(`/demo/reliability/dial?mode=${mode}`, 'PATCH', () => undefined)}
            className={status?.downstreamMode === mode ? ACTIVE_BUTTON : BUTTON}
          >
            {mode}
          </button>
        ))}
        <button type="button" data-testid="r39-call" onClick={callDownstream} className={ACTION_BUTTON}>
          call downstream
        </button>
        <button type="button" data-testid="r39-probe" onClick={() => run('/demo/reliability/probe', 'POST', () => undefined)} className={ACTION_BUTTON}>
          force half-open probe
        </button>
        <button
          type="button"
          data-testid="r39-reset"
          onClick={() => {
            setCallReport(null);
            run('/demo/reliability/reset', 'POST', () => undefined);
          }}
          className={BUTTON}
        >
          reset
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="r39-loading" className="font-mono text-caption text-on-surface-variant">
          talking to the breaker…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the breaker theater" failure={panel.failure} />}

      <section
        data-testid="r39-status"
        className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        {status === null ? (
          <span>reading the breaker…</span>
        ) : (
          <>
            <span className={stateBadge(status.state)} data-testid="r39-state">
              {status.state}
            </span>
            <span data-testid="r39-summary">
              t={String(status.virtualNowMs)}ms · downstream {status.downstreamMode}
              {status.injectFailureRail ? ' · global injectFailure rail ON' : ''}
            </span>
            <span data-testid="r39-metrics">
              window: {String(status.metrics.successes)} ok / {String(status.metrics.failures)} failed /
              {' '}{String(status.metrics.notPermitted)} rejected · failure rate{' '}
              {status.metrics.failureRate === null ? 'not judged yet' : `${status.metrics.failureRate.toFixed(1)}%`}
            </span>
            <span className="text-on-surface-variant">
              trips at {String(status.config.failureRateThreshold)}% over the last{' '}
              {String(status.config.slidingWindowSize)} calls (min {String(status.config.minimumNumberOfCalls)} judged) ·
              wait {String(status.config.waitDurationMs)}ms · {String(status.config.permittedInHalfOpen)} probe
            </span>
            {status.openedAtMs !== null && (
              <span data-testid="r39-open" className="text-on-surface-variant">
                opened at t={String(status.openedAtMs)}ms · probe eligible at t=
                {String(status.probeEligibleAtMs)}ms — or force it now
              </span>
            )}
            {status.reading.map((line, index) => (
              <span
                key={line}
                data-testid={`r39-reading-${String(index)}`}
                className="border-t border-outline pt-xxs text-on-surface-variant"
              >
                {line}
              </span>
            ))}
          </>
        )}
      </section>

      {callReport !== null && (
        <section
          data-testid="r39-callreport"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span className={stateBadge(callReport.breakerStateAfter)}>
            {callReport.outcome} · {callReport.breakerStateBefore} → {callReport.breakerStateAfter}
          </span>
          <span className="text-on-surface-variant">
            {String(callReport.downstreamAttempts)} downstream attempt(s) · {String(callReport.totalElapsedMs)}ms virtual time
          </span>
          {callReport.attempts.map((attempt) => (
            <div
              key={attempt.attempt}
              data-testid="r39-attempt"
              className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
            >
              <span>attempt {String(attempt.attempt)}</span>
              <span>{attempt.outcome}</span>
              <span className="text-on-surface-variant">
                t={String(attempt.startedAtMs)}→{String(attempt.endedAtMs)}ms
              </span>
              <span className="text-on-surface-variant">{attempt.detail}</span>
            </div>
          ))}
        </section>
      )}
    </div>
  );
}
