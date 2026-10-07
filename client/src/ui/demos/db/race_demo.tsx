import { useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:race` — topic 17 (jdbc-sql), the
 * isolation-locking-and-the-race section. N genuinely-concurrent reservation
 * attempts at one inventory row, under one of three strategies; the
 * scoreboard's honest number is reserved — after a lost update the SHELF
 * reads a plausible value while reserved counts what actually shipped.
 * The concurrency cap (8) is HikariCP's pool size: your concurrency is your pool.
 */

interface RaceOutcome {
  strategy: string;
  concurrency: number;
  initialStock: number;
  attempted: number;
  succeeded: number;
  declined: number;
  oversold: boolean;
  finalStock: number;
  finalReserved: number;
  story: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'ready'; outcome: RaceOutcome }
  | { kind: 'error'; failure: Failure };

const STRATEGIES: Array<{ id: string; label: string; hint: string }> = [
  { id: 'naive', label: 'naive', hint: 'check, decide in Java, write back — the bug' },
  { id: 'guarded', label: 'guarded', hint: 'SELECT FOR UPDATE in a transaction' },
  { id: 'atomic', label: 'atomic', hint: 'one UPDATE … WHERE stock >= 1' },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseOutcome(body: unknown): RaceOutcome {
  if (!isRecord(body)) throw new Error('race outcome must be an object');
  const numbers = ['concurrency', 'initialStock', 'attempted', 'succeeded', 'declined', 'finalStock', 'finalReserved'];
  for (const key of numbers) {
    if (typeof body[key] !== 'number') throw new Error(`race outcome needs numeric ${key}`);
  }
  if (typeof body.strategy !== 'string' || typeof body.story !== 'string' || typeof body.oversold !== 'boolean') {
    throw new Error('race outcome needs strategy, oversold, story');
  }
  return {
    strategy: body.strategy,
    concurrency: body.concurrency as number,
    initialStock: body.initialStock as number,
    attempted: body.attempted as number,
    succeeded: body.succeeded as number,
    declined: body.declined as number,
    oversold: body.oversold,
    finalStock: body.finalStock as number,
    finalReserved: body.finalReserved as number,
    story: body.story,
  };
}

async function runRace(
  config: AppConfig,
  strategy: string,
  concurrency: number,
  initialStock: number,
): Promise<PanelState> {
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}/demo/db/race/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ strategy, concurrency, initialStock }),
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
      const message = isRecord(parsed) && isRecord(parsed.error)
          && typeof parsed.error.message === 'string'
        ? parsed.error.message
        : `Request failed with status ${String(response.status)}.`;
      throw new ServerException(message, { status: response.status });
    }
    return parseOutcome(parsed);
  });
  if (result.ok) return { kind: 'ready', outcome: result.value };
  return { kind: 'error', failure: result.failure };
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const STAT =
  'flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs text-center';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';

function Stat({ label, value, testId }: { label: string; value: string; testId: string }): ReactNode {
  return (
    <div className={STAT}>
      <span data-testid={testId} className="font-mono text-body-lg text-on-surface">
        {value}
      </span>
      <span className="font-mono text-caption text-on-surface-variant">{label}</span>
    </div>
  );
}

export default function RaceDemo(): ReactNode {
  const config = container.appConfig;
  const [concurrency, setConcurrency] = useState(4);
  const [initialStock, setInitialStock] = useState(1);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });
  const [running, setRunning] = useState<string | null>(null);

  const run = async (strategy: string): Promise<void> => {
    setRunning(strategy);
    setPanel(await runRace(config, strategy, concurrency, initialStock));
    setRunning(null);
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the knobs</p>
        <div className="flex flex-wrap items-center gap-sm text-caption text-on-surface-variant">
          <label className="flex items-center gap-xs">
            <span className="font-mono">concurrency</span>
            <input
              data-testid="race-concurrency"
              type="number"
              min={2}
              max={8}
              value={concurrency}
              onChange={(event) =>
                setConcurrency(Math.max(2, Math.min(8, Number(event.target.value) || 2)))
              }
              className="w-16 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
            <span>(pool-capped at 8)</span>
          </label>
          <label className="flex items-center gap-xs">
            <span className="font-mono">initialStock</span>
            <input
              data-testid="race-stock"
              type="number"
              min={1}
              max={99}
              value={initialStock}
              onChange={(event) =>
                setInitialStock(Math.max(1, Math.min(99, Number(event.target.value) || 1)))
              }
              className="w-16 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the strategies — run all three, compare the scoreboards</p>
        <div className="flex flex-wrap gap-xs">
          {STRATEGIES.map((strategy) => (
            <button
              key={strategy.id}
              type="button"
              data-testid={`race-run-${strategy.id}`}
              className={BUTTON}
              disabled={running !== null}
              title={strategy.hint}
              onClick={() => void run(strategy.id)}
            >
              {running === strategy.id ? 'racing…' : `run ${strategy.label}`}
            </button>
          ))}
        </div>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the race" failure={panel.failure} />}

      {panel.kind === 'ready' && (
        <section className="flex flex-col gap-sm">
          <div className="grid grid-cols-2 gap-xs sm:grid-cols-3 lg:grid-cols-6">
            <Stat label="attempted" value={String(panel.outcome.attempted)} testId="race-attempted" />
            <Stat label="succeeded" value={String(panel.outcome.succeeded)} testId="race-succeeded" />
            <Stat label="declined" value={String(panel.outcome.declined)} testId="race-declined" />
            <Stat
              label="final stock"
              value={String(panel.outcome.finalStock)}
              testId="race-final-stock"
            />
            <Stat
              label="final reserved"
              value={String(panel.outcome.finalReserved)}
              testId="race-final-reserved"
            />
            <div
              data-testid="race-oversold"
              className={`flex items-center justify-center rounded-sm border px-sm py-xs font-mono text-body-lg ${
                panel.outcome.oversold
                  ? 'border-error bg-error/15 text-error'
                  : 'border-outline bg-surface text-on-surface'
              }`}
            >
              {panel.outcome.oversold ? 'oversold' : 'held'}
            </div>
          </div>
          <p
            data-testid="race-story"
            className="rounded-sm border border-outline bg-surface px-sm py-xs text-caption text-on-surface"
          >
            <span className="font-mono text-on-surface-variant">{panel.outcome.strategy}:</span>{' '}
            {panel.outcome.story}
          </p>
        </section>
      )}
    </div>
  );
}
