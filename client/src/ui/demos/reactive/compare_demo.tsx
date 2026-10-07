import { useCallback, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `reactive:compare` — topic 41 (reactive-spring), the
 * backpressure section (the demo carries the topic's two measured halves).
 * Two buttons, two GETs, no client-side math:
 *
 * - "run comparison": the SAME workload measured two ways on the server —
 *   blocking on one virtual thread per item vs a Reactor pipeline with a
 *   flatMap concurrency cap. The panel paints both shapes (threads,
 *   items, wall time) plus the shared checksum that proves both did the
 *   same work.
 * - "run drop" / "run buffer": the deterministic backpressure exhibit —
 *   a hot source against a slow consumer, with exact counts of what was
 *   delivered, dropped or buffered, and what the late demand recovered.
 */

interface ModelRun {
  model: string;
  shape: string;
  itemsProcessed: number;
  distinctThreads: number;
  sampleThreads: string[];
  wallNanos: number;
}

interface CompareReport {
  items: number;
  concurrency: number;
  workChecksum: number;
  blockingPerItem: ModelRun;
  reactorPipeline: ModelRun;
  reading: string[];
}

interface BackpressureReport {
  strategy: string;
  emitted: number;
  initialDemand: number;
  lateDemand: number;
  deliveredAfterInitial: number;
  deliveredTotal: number;
  dropped: number;
  stillBuffered: number;
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

const ITEM_CHOICES = ['16', '48', '200'] as const;
const CONCURRENCY_CHOICES = ['4', '8', '16'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseRun(source: unknown): ModelRun {
  if (!isRecord(source)) throw new Error('a model run must be an object');
  return {
    model: String(source.model ?? ''),
    shape: String(source.shape ?? ''),
    itemsProcessed: Number(source.itemsProcessed ?? 0),
    distinctThreads: Number(source.distinctThreads ?? 0),
    sampleThreads: Array.isArray(source.sampleThreads)
      ? source.sampleThreads.map((t) => String(t))
      : [],
    wallNanos: Number(source.wallNanos ?? 0),
  };
}

function parseCompare(source: unknown): CompareReport {
  if (!isRecord(source)) throw new Error('the comparison report must be an object');
  const { blockingPerItem, reactorPipeline, reading } = source;
  if (!isRecord(blockingPerItem) || !isRecord(reactorPipeline) || !Array.isArray(reading)) {
    throw new Error('the report carries both runs and a reading array');
  }
  return {
    items: Number(source.items ?? 0),
    concurrency: Number(source.concurrency ?? 0),
    workChecksum: Number(source.workChecksum ?? 0),
    blockingPerItem: parseRun(blockingPerItem),
    reactorPipeline: parseRun(reactorPipeline),
    reading: reading.map((line) => String(line)),
  };
}

function parseBackpressure(source: unknown): BackpressureReport {
  if (!isRecord(source)) throw new Error('the backpressure report must be an object');
  const { reading } = source;
  if (!Array.isArray(reading)) throw new Error('the report carries a reading array');
  return {
    strategy: String(source.strategy ?? ''),
    emitted: Number(source.emitted ?? 0),
    initialDemand: Number(source.initialDemand ?? 0),
    lateDemand: Number(source.lateDemand ?? 0),
    deliveredAfterInitial: Number(source.deliveredAfterInitial ?? 0),
    deliveredTotal: Number(source.deliveredTotal ?? 0),
    dropped: Number(source.dropped ?? 0),
    stillBuffered: Number(source.stillBuffered ?? 0),
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

function millis(wallNanos: number): string {
  return `${(wallNanos / 1_000_000).toFixed(1)}ms`;
}

export default function ReactiveCompareDemo() {
  const config = container.appConfig;
  const [items, setItems] = useState<(typeof ITEM_CHOICES)[number]>('48');
  const [concurrency, setConcurrency] = useState<(typeof CONCURRENCY_CHOICES)[number]>('8');
  const [compare, setCompare] = useState<CompareReport | null>(null);
  const [backpressure, setBackpressure] = useState<BackpressureReport | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  /** The comparison half: items + concurrency ride the URL, one GET. */
  const runComparison = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseCompare(
        await requestJson(config, `/demo/reactive/compare?items=${items}&concurrency=${concurrency}`),
      ),
    ).then((result) => {
      if (result.ok) {
        setCompare(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config, concurrency, items]);

  /** The backpressure half: one strategy, deterministic counts. */
  const runBackpressure = useCallback(
    (strategy: 'drop' | 'buffer') => {
      setPanel({ kind: 'loading' });
      void safeRepositoryCall(async () =>
        parseBackpressure(
          await requestJson(
            config,
            `/demo/reactive/backpressure?strategy=${strategy}&items=24&initial=4&late=4`,
          ),
        ),
      ).then((result) => {
        if (result.ok) {
          setBackpressure(result.value);
          setPanel({ kind: 'idle' });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
    },
    [config],
  );

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>items:</span>
        {ITEM_CHOICES.map((choice) => (
          <button
            key={choice}
            type="button"
            data-testid="reactive-items"
            onClick={() => setItems(choice)}
            className={choice === items ? ACTIVE_BUTTON : BUTTON}
          >
            {choice}
          </button>
        ))}
        <span className={OVERLINE}>pipeline concurrency:</span>
        {CONCURRENCY_CHOICES.map((choice) => (
          <button
            key={choice}
            type="button"
            data-testid="reactive-concurrency"
            onClick={() => setConcurrency(choice)}
            className={choice === concurrency ? ACTIVE_BUTTON : BUTTON}
          >
            {choice}
          </button>
        ))}
        <button
          type="button"
          data-testid="reactive-run-compare"
          onClick={runComparison}
          className={ACTION_BUTTON}
        >
          run comparison
        </button>
        <button
          type="button"
          data-testid="reactive-run-drop"
          onClick={() => runBackpressure('drop')}
          className={ACTION_BUTTON}
        >
          run drop
        </button>
        <button
          type="button"
          data-testid="reactive-run-buffer"
          onClick={() => runBackpressure('buffer')}
          className={ACTION_BUTTON}
        >
          run buffer
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p
          data-testid="reactive-loading"
          className="font-mono text-caption text-on-surface-variant"
        >
          measuring both models…
        </p>
      )}
      {panel.kind === 'error' && (
        <ErrorPanel subject="the reactive comparison" failure={panel.failure} />
      )}

      {compare !== null && (
        <section
          data-testid="reactive-compare-report"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>
            same workload, {String(compare.items)} items — shared checksum{' '}
            {String(compare.workChecksum)}
          </p>
          {[compare.blockingPerItem, compare.reactorPipeline].map((run) => (
            <div
              key={run.model}
              data-testid="reactive-model"
              className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
            >
              <span>{run.model}</span>
              <span className="text-on-surface-variant">
                {String(run.itemsProcessed)} items on {String(run.distinctThreads)} thread(s)
              </span>
              <span>{millis(run.wallNanos)}</span>
              <span className="text-on-surface-variant">
                threads: {run.sampleThreads.join(', ')}
              </span>
              <span className="text-on-surface-variant">{run.shape}</span>
            </div>
          ))}
          {compare.reading.map((line, index) => (
            <span
              key={line}
              data-testid={`reactive-compare-reading-${String(index)}`}
              className="border-t border-outline pt-xxs text-on-surface-variant"
            >
              {line}
            </span>
          ))}
        </section>
      )}

      {backpressure !== null && (
        <section
          data-testid="reactive-backpressure-report"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>
            strategy {backpressure.strategy} — {String(backpressure.emitted)} emitted, initial
            demand {String(backpressure.initialDemand)}, late demand{' '}
            {String(backpressure.lateDemand)}
          </p>
          <div data-testid="reactive-backpressure-counts" className="border-t border-outline pt-xxs">
            delivered after initial demand: {String(backpressure.deliveredAfterInitial)} → after
            late demand: {String(backpressure.deliveredTotal)} · dropped:{' '}
            {String(backpressure.dropped)} · still buffered: {String(backpressure.stillBuffered)}
          </div>
          {backpressure.reading.map((line, index) => (
            <span
              key={line}
              data-testid={`reactive-bp-reading-${String(index)}`}
              className="border-t border-outline pt-xxs text-on-surface-variant"
            >
              {line}
            </span>
          ))}
        </section>
      )}
    </div>
  );
}
