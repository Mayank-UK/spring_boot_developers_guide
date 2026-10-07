import { useCallback, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `scaling:stateless` — topic 40 (scaling), the
 * statelessness section. One GET per click, measured live on the server:
 *
 * - route each request like a load balancer would — round-robin (no
 *   affinity) or pinned to server-a/server-b (what a sticky session
 *   forces);
 * - choose where the durable value lives: the shared external store (the
 *   redis/PostgreSQL arrangement) or the serving replica's own memory
 *   (the session-in-heap arrangement the demo exists to break);
 * - the panel narrates only what the probe answered: each replica's
 *   private counter, the shared store's value, what THIS caller was
 *   told, and how much a local-memory world would have forgotten. Pin to
 *   a, switch store to local, then route to b — the number goes
 *   backwards, and that is the whole lesson.
 */

interface InstanceView {
  id: string;
  localState: number;
}

interface Probe {
  processInstanceId: string;
  processStartedAt: string;
  totalRequests: number;
  routedTo: string | null;
  routing: string;
  stateHome: string;
  instances: InstanceView[];
  externalState: number;
  reportedValue: number;
  lostIfLocal: number;
  reading: string[];
}

interface LogEntry {
  routedTo: string;
  reportedValue: number;
  stateHome: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

const STORE_MODES = ['external', 'local'] as const;
const ROUTING_MODES = ['round-robin', 'a', 'b'] as const;
const LOG_LIMIT = 12;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseInstances(source: unknown): InstanceView[] {
  if (!Array.isArray(source)) throw new Error('the probe carries an instances array');
  return source.map((entry) => {
    if (!isRecord(entry)) throw new Error('each instance view must be an object');
    return { id: String(entry.id ?? ''), localState: Number(entry.localState ?? 0) };
  });
}

function parseProbe(source: unknown): Probe {
  if (!isRecord(source)) throw new Error('the statelessness probe must be an object');
  const { instances, reading } = source;
  if (!Array.isArray(reading)) throw new Error('the probe carries a reading array');
  return {
    processInstanceId: String(source.processInstanceId ?? ''),
    processStartedAt: String(source.processStartedAt ?? ''),
    totalRequests: Number(source.totalRequests ?? 0),
    routedTo: source.routedTo === null || source.routedTo === undefined ? null : String(source.routedTo),
    routing: String(source.routing ?? ''),
    stateHome: String(source.stateHome ?? ''),
    instances: parseInstances(instances),
    externalState: Number(source.externalState ?? 0),
    reportedValue: Number(source.reportedValue ?? 0),
    lostIfLocal: Number(source.lostIfLocal ?? 0),
    reading: reading.map((line) => String(line)),
  };
}

/** Fetch a path as JSON, guarding transport + envelope like every demo. */
async function requestJson(config: AppConfig, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
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

export default function StatelessDemo() {
  const config = container.appConfig;
  const [storeMode, setStoreMode] = useState<(typeof STORE_MODES)[number]>('external');
  const [routingMode, setRoutingMode] = useState<(typeof ROUTING_MODES)[number]>('round-robin');
  const [probe, setProbe] = useState<Probe | null>(null);
  const [log, setLog] = useState<LogEntry[]>([]);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const applyProbe = useCallback((next: Probe) => {
    setProbe(next);
    if (next.routedTo !== null) {
      setLog((entries) => [
        { routedTo: next.routedTo ?? '', reportedValue: next.reportedValue, stateHome: next.stateHome },
        ...entries,
      ].slice(0, LOG_LIMIT));
    }
  }, []);

  /** Send one request through the simulated fleet: store + routing ride the URL. */
  const sendRequest = useCallback(() => {
    setPanel({ kind: 'loading' });
    const instanceParam = routingMode === 'round-robin' ? '' : `&instance=${routingMode}`;
    void safeRepositoryCall(async () =>
      parseProbe(await requestJson(config, `/demo/scaling/stateless?store=${storeMode}${instanceParam}`)),
    ).then((result) => {
      if (result.ok) {
        applyProbe(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [applyProbe, config, routingMode, storeMode]);

  /** Fresh fleet: personas, shared store, routing cursor — all zero. */
  const resetFleet = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseProbe(await requestJson(config, '/demo/scaling/stateless/reset')),
    ).then((result) => {
      if (result.ok) {
        setProbe(result.value);
        setLog([]);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>value lives:</span>
        {STORE_MODES.map((mode) => (
          <button
            key={mode}
            type="button"
            data-testid="scaling-store"
            onClick={() => setStoreMode(mode)}
            className={mode === storeMode ? ACTIVE_BUTTON : BUTTON}
          >
            {mode}
          </button>
        ))}
        <span className={OVERLINE}>routing:</span>
        {ROUTING_MODES.map((mode) => (
          <button
            key={mode}
            type="button"
            data-testid="scaling-routing"
            onClick={() => setRoutingMode(mode)}
            className={mode === routingMode ? ACTIVE_BUTTON : BUTTON}
          >
            {mode}
          </button>
        ))}
        <button type="button" data-testid="scaling-send" onClick={sendRequest} className={ACTION_BUTTON}>
          send request
        </button>
        <button type="button" data-testid="scaling-reset" onClick={resetFleet} className={ACTION_BUTTON}>
          reset fleet
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="scaling-loading" className="font-mono text-caption text-on-surface-variant">
          routing request…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the statelessness probe" failure={panel.failure} />}

      <section
        data-testid="scaling-report"
        className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        <p className={OVERLINE}>
          instance identity — {probe === null ? 'not run yet' : `${probe.processInstanceId} (up since ${probe.processStartedAt})`}
        </p>
        {probe?.instances.map((instance) => (
          <div
            key={instance.id}
            data-testid="scaling-instance"
            className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
          >
            <span>{instance.id}</span>
            <span className="text-on-surface-variant">own memory: {String(instance.localState)} request(s)</span>
          </div>
        ))}
        {probe !== null && (
          <span data-testid="scaling-external" className="border-t border-outline pt-xxs">
            shared store: {String(probe.externalState)} — the value every replica agrees on
          </span>
        )}
        {probe !== null && probe.routedTo !== null && (
          <span data-testid="scaling-last" className="border-t border-outline pt-xxs">
            this request: routed to {probe.routedTo} ({probe.routing}), caller told {String(probe.reportedValue)}
            {probe.stateHome === 'local'
              ? ` — a local-memory world would have forgotten ${String(probe.lostIfLocal)}`
              : ' — any replica, same answer'}
          </span>
        )}
        {probe?.reading.map((line, index) => (
          <span
            key={line}
            data-testid={`scaling-reading-${String(index)}`}
            className="border-t border-outline pt-xxs text-on-surface-variant"
          >
            {line}
          </span>
        ))}
        {log.length > 0 && (
          <span className={`${OVERLINE} border-t border-outline pt-xxs`}>what the caller was told (newest first)</span>
        )}
        {log.map((entry, index) => (
          <span key={`${entry.routedTo}-${String(index)}`} data-testid="scaling-log" className="text-on-surface-variant">
            {'#'}{String(log.length - index)} → {entry.routedTo}: told {String(entry.reportedValue)}
            {entry.stateHome === 'local' ? ' (local)' : ' (external)'}
          </span>
        ))}
      </section>
    </div>
  );
}
