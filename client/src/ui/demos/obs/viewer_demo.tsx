import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `obs:viewer` — topic 33 (observability), the
 * structured-logging section. The app's log ring as a live surface:
 *
 * - the ring loads the recent structured events exactly as the server
 *   serves them — every field the JSON encoder carries (timestamp, level,
 *   logger, message, requestId, traceId when present);
 * - the level filter and limit ride as query params, server-capped;
 * - the emit row deliberately fires each of the five levels through the
 *   real logging pipeline and the ring reload shows what landed — including
 *   the per-logger TRACE that overrides the root INFO level.
 */

interface LoggedEvent {
  timestampMs: number;
  level: string;
  logger: string;
  message: string;
  requestId: string | null;
  traceId: string | null;
}

interface RingBody {
  capacity: number;
  size: number;
  events: LoggedEvent[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

const LEVELS = ['trace', 'debug', 'info', 'warn', 'error'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function optionalString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === undefined || value === null) return null;
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string when present`);
  return value;
}

function parseEvent(source: unknown): LoggedEvent {
  if (!isRecord(source)) throw new Error('a log event must be an object');
  const timestampMs = source.timestampMs;
  if (typeof timestampMs !== 'number') throw new Error('field "timestampMs" must be a number');
  return {
    timestampMs,
    level: String(source.level ?? ''),
    logger: String(source.logger ?? ''),
    message: String(source.message ?? ''),
    requestId: optionalString(source, 'requestId'),
    traceId: optionalString(source, 'traceId'),
  };
}

function parseRingBody(source: unknown): RingBody {
  if (!isRecord(source)) throw new Error('the ring answer must be an object');
  const events = source.events;
  const capacity = source.capacity;
  const size = source.size;
  if (!Array.isArray(events)) throw new Error('field "events" must be an array');
  if (typeof capacity !== 'number' || typeof size !== 'number') {
    throw new Error('the ring answer carries capacity and size as numbers');
  }
  return { capacity, size, events: events.map(parseEvent) };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CHIP = 'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const LEVEL_BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';

function levelClass(level: string): string {
  switch (level.toUpperCase()) {
    case 'ERROR':
      return 'text-error';
    case 'WARN':
      return 'text-on-surface-variant';
    default:
      return 'text-on-surface';
  }
}

function clockOf(timestampMs: number): string {
  return new Date(timestampMs).toISOString().slice(11, 23);
}

export default function ObsViewerDemo() {
  const config = container.appConfig;
  const [level, setLevel] = useState('');
  const [limit, setLimit] = useState(20);
  const [ring, setRing] = useState<RingBody | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const loadRing = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const response = await guardedGetJson(
        config,
        `/demo/obs/logs?level=${encodeURIComponent(level)}&limit=${String(limit)}`,
      );
      return parseRingBody(response.body);
    }).then((result) => {
      if (result.ok) {
        setRing(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config, level, limit]);

  useEffect(() => {
    loadRing();
  }, [loadRing]);

  const emit = (nextLevel: string) =>
    void safeRepositoryCall(async () =>
      parseEvent((await guardedGetJson(config, `/demo/obs/logs/emit?level=${nextLevel}`)).body),
    ).then((result) => {
      if (result.ok) {
        loadRing();
      } else if (result.failure.code === 'validation_failed') {
        // the emitter's own door: an unknown level is a typed 400, kept visible
        setPanel({ kind: 'error', failure: result.failure });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          level filter
          <select
            data-testid="obs-level-select"
            value={level}
            onChange={(event) => setLevel(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            <option value="">(all levels)</option>
            {LEVELS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          limit
          <select
            data-testid="obs-limit-select"
            value={String(limit)}
            onChange={(event) => setLimit(Number(event.target.value))}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            {[10, 20, 50].map((option) => (
              <option key={option} value={String(option)}>
                {String(option)}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          data-testid="obs-refresh"
          onClick={loadRing}
          className={BUTTON}
        >
          refresh ring
        </button>
      </section>

      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>emit for real:</span>
        {LEVELS.map((option) => (
          <button
            key={option}
            type="button"
            data-testid={`obs-emit-${option}`}
            onClick={() => emit(option)}
            className={LEVEL_BUTTON}
          >
            {option}
          </button>
        ))}
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="obs-loading" className="font-mono text-caption text-on-surface-variant">
          reading the ring…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the log ring" failure={panel.failure} />}

      <section
        data-testid="obs-ring"
        className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        <p className={OVERLINE}>
          the ring — {ring === null ? '…' : `${String(ring.size)} of ${String(ring.capacity)} held`}
        </p>
        {ring?.events.map((event, index) => (
          <span
            key={`${String(event.timestampMs)}-${String(index)}`}
            data-testid={`obs-event-${String(index)}`}
            className="break-all"
          >
            <span className={levelClass(event.level)}>{clockOf(event.timestampMs)} {event.level}</span>{' '}
            <span className="text-on-surface-variant">[{event.logger}]</span> {event.message}
            {event.requestId !== null && (
              <span className="text-on-surface-variant"> requestId={event.requestId}</span>
            )}
            {event.traceId !== null && (
              <span className="text-on-surface-variant"> traceId={event.traceId}</span>
            )}
          </span>
        ))}
        {ring !== null && ring.events.length === 0 && (
          <span data-testid="obs-empty" className={CHIP}>
            no events at this level yet — emit one above
          </span>
        )}
      </section>
    </div>
  );
}
