import { useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:uuid` — topic 16 (sql-schema), the key-strategy
 * section. The experiment console:
 * - POST /api/demo/db/uuid/compare?rows=N inserts N random uuid v4 rows and
 *   N time-ordered uuid v7 rows into the two lab tables, then reports what
 *   the database actually did: primary-key index bytes per side (random keys
 *   split b-tree pages half-full; sequential keys append), and the locality
 *   verdict — walking rows in key order, do adjacent pairs match insertion
 *   order (v7: yes; v4: a shuffle). The server truncates the lab tables
 *   when it finishes, so the run is idempotent.
 * - The ids listed for each side are the FIRST ids in key order: v7's
 *   cluster tightly by mint time; v4's look like noise. That visible
 *   difference is the b-tree page-locality story in one glance.
 */

interface StrategyMeasurement {
  strategy: string;
  primaryKeyIndexBytes: number;
  primaryKeyIndexSize: string;
  insertDurationMs: number;
  idsInKeyOrder: string[];
  comparedPairs: number;
  outOfOrderPairs: number;
  keyOrderTracksInsertion: boolean;
}

interface KeyStrategyReport {
  rows: number;
  uuidV4: StrategyMeasurement;
  uuidV7: StrategyMeasurement;
  indexByteDelta: number;
  verdict: string;
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; report: KeyStrategyReport }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded fetch pipeline (same shape as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchJson(config: AppConfig, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, { method: 'POST' });
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
    if (isRecord(parsed) && isRecord(parsed.error)) {
      throw new ServerException(
        typeof parsed.error.message === 'string'
          ? parsed.error.message
          : `Request failed with status ${String(response.status)}.`,
        {
          status: response.status,
          code: typeof parsed.error.code === 'string' ? parsed.error.code : 'unknown_error',
          requestId:
            typeof parsed.error.requestId === 'string' ? parsed.error.requestId : undefined,
        },
      );
    }
    throw new ServerException(`Request failed with status ${String(response.status)}.`, {
      status: response.status,
    });
  }
  return parsed;
}

function parseMeasurement(body: unknown): StrategyMeasurement {
  if (
    !isRecord(body) ||
    typeof body.strategy !== 'string' ||
    typeof body.primaryKeyIndexBytes !== 'number' ||
    typeof body.primaryKeyIndexSize !== 'string' ||
    typeof body.insertDurationMs !== 'number' ||
    !Array.isArray(body.idsInKeyOrder) ||
    typeof body.comparedPairs !== 'number' ||
    typeof body.outOfOrderPairs !== 'number' ||
    typeof body.keyOrderTracksInsertion !== 'boolean'
  ) {
    throw new Error('measurement fields missing');
  }
  return {
    strategy: body.strategy,
    primaryKeyIndexBytes: body.primaryKeyIndexBytes,
    primaryKeyIndexSize: body.primaryKeyIndexSize,
    insertDurationMs: body.insertDurationMs,
    idsInKeyOrder: body.idsInKeyOrder.map((id) => String(id)),
    comparedPairs: body.comparedPairs,
    outOfOrderPairs: body.outOfOrderPairs,
    keyOrderTracksInsertion: body.keyOrderTracksInsertion,
  };
}

function parseReport(body: unknown): KeyStrategyReport {
  if (
    !isRecord(body) ||
    typeof body.rows !== 'number' ||
    !isRecord(body.uuidV4) ||
    !isRecord(body.uuidV7) ||
    typeof body.indexByteDelta !== 'number' ||
    typeof body.verdict !== 'string'
  ) {
    throw new Error('report needs rows, uuidV4, uuidV7, indexByteDelta, verdict');
  }
  return {
    rows: body.rows,
    uuidV4: parseMeasurement(body.uuidV4),
    uuidV7: parseMeasurement(body.uuidV7),
    indexByteDelta: body.indexByteDelta,
    verdict: body.verdict,
  };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function StrategyCard({ testId, measurement }: { testId: string; measurement: StrategyMeasurement }) {
  return (
    <div
      data-testid={testId}
      className={`flex flex-col gap-xxs rounded-sm border px-sm py-xs font-mono text-caption ${
        measurement.keyOrderTracksInsertion
          ? 'border-secondary bg-secondary/10 text-on-surface'
          : 'border-outline bg-surface text-on-surface'
      }`}
    >
      <span className="font-semibold">{measurement.strategy}</span>
      <span data-testid={`${testId}-index`}>
        primary-key index: {measurement.primaryKeyIndexSize} ({String(measurement.primaryKeyIndexBytes)}{' '}
        bytes) · insert {String(measurement.insertDurationMs)}ms
      </span>
      <span data-testid={`${testId}-locality`}>
        key order tracks insertion order:{' '}
        {measurement.keyOrderTracksInsertion
          ? `yes — ${String(measurement.outOfOrderPairs)}/${String(measurement.comparedPairs)} pairs out of order`
          : `no — ${String(measurement.outOfOrderPairs)}/${String(measurement.comparedPairs)} pairs out of order`}
      </span>
      <span className="flex flex-col text-on-surface-variant">
        {measurement.idsInKeyOrder.slice(0, 4).map((id) => (
          <span key={id} data-testid={`${testId}-id`}>
            {id}
          </span>
        ))}
      </span>
    </div>
  );
}

export default function UuidDemo() {
  const config = container.appConfig;
  const [rows, setRows] = useState(5000);
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });

  const run = () => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseReport(await fetchJson(config, `/demo/db/uuid/compare?rows=${String(rows)}`)),
    ).then((result) => {
      setPanel(
        result.ok
          ? { kind: 'ready', report: result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label
          htmlFor="uuid-rows"
          className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant"
        >
          rows per strategy
          <input
            id="uuid-rows"
            data-testid="uuid-rows-input"
            type="number"
            min={1}
            max={50000}
            value={rows}
            onChange={(event) => setRows(Number(event.target.value))}
            className="w-28 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <button
          type="button"
          data-testid="uuid-run"
          onClick={run}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          run the experiment
        </button>
      </section>

      {panel.kind === 'idle' && (
        <p data-testid="uuid-idle" className="font-mono text-caption text-on-surface-variant">
          no run yet — insert v4 and v7 populations and measure what the indexes did
        </p>
      )}
      {panel.kind === 'loading' && (
        <p data-testid="uuid-loading" className="font-mono text-caption text-on-surface-variant">
          inserting {String(rows)} + {String(rows)} rows…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the key-strategy report" failure={panel.failure} />}
      {panel.kind === 'ready' && (
        <section data-testid="uuid-report" className="flex flex-col gap-sm">
          <p className={OVERLINE}>
            {String(panel.report.rows)} rows per side · index-size delta{' '}
            <span data-testid="uuid-delta">{String(panel.report.indexByteDelta)} bytes</span>
          </p>
          <div className="grid gap-sm md:grid-cols-2">
            <StrategyCard testId="uuid-v4-card" measurement={panel.report.uuidV4} />
            <StrategyCard testId="uuid-v7-card" measurement={panel.report.uuidV7} />
          </div>
          <p data-testid="uuid-verdict" className="font-mono text-caption text-on-surface">
            {panel.report.verdict}
          </p>
        </section>
      )}
    </div>
  );
}
