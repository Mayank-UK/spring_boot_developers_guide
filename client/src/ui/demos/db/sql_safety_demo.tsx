import { useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:sql-safety` — topic 17 (jdbc-sql), the
 * parameterized-queries-or-else section. One payload, two doors: the server
 * runs it through a bind parameter (a value) and through REAL string
 * concatenation (structure) against a sandbox table with a fake secret
 * column, then answers with both outcomes side by side. Presets cover the
 * classic bypass, a UNION escape (which the restricted role stops at
 * permission denied), and a harmless username.
 */

interface SandboxRow {
  username: string;
  role: string;
  secret: string;
}

interface LookupOutcome {
  sqlShown: string;
  rows: SandboxRow[];
  note: string;
  containmentError: string | null;
}

interface Showdown {
  payload: string;
  parameterized: LookupOutcome;
  vulnerable: LookupOutcome;
  verdict: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'ready'; showdown: Showdown }
  | { kind: 'error'; failure: Failure };

const PRESETS: Array<{ id: string; label: string; payload: string }> = [
  { id: 'classic', label: "classic ' OR '1'='1", payload: "' OR '1'='1" },
  {
    id: 'union',
    label: 'UNION escape',
    payload: "' UNION SELECT id::text, title, body FROM posts --",
  },
  { id: 'harmless', label: 'a harmless username', payload: 'ada' },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseLookup(body: unknown, what: string): LookupOutcome {
  if (!isRecord(body)) throw new Error(`${what} must be an object`);
  if (typeof body.sqlShown !== 'string' || typeof body.note !== 'string') {
    throw new Error(`${what} needs sqlShown and note`);
  }
  if (!Array.isArray(body.rows)) throw new Error(`${what}.rows must be an array`);
  for (const row of body.rows) {
    if (!isRecord(row) || typeof row.username !== 'string' || typeof row.secret !== 'string') {
      throw new Error(`${what}.rows entries need username and secret`);
    }
  }
  return {
    sqlShown: body.sqlShown,
    note: body.note,
    rows: body.rows as unknown as SandboxRow[],
    containmentError: typeof body.containmentError === 'string' ? body.containmentError : null,
  };
}

function parseShowdown(body: unknown): Showdown {
  if (!isRecord(body)) throw new Error('showdown must be an object');
  if (typeof body.payload !== 'string' || typeof body.verdict !== 'string') {
    throw new Error('showdown needs payload and verdict');
  }
  return {
    payload: body.payload,
    parameterized: parseLookup(body.parameterized, 'parameterized'),
    vulnerable: parseLookup(body.vulnerable, 'vulnerable'),
    verdict: body.verdict,
  };
}

async function runShowdown(config: AppConfig, payload: string): Promise<PanelState> {
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}/demo/db/sql/safety`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ payload }),
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
    return parseShowdown(parsed);
  });
  if (result.ok) return { kind: 'ready', showdown: result.value };
  return { kind: 'error', failure: result.failure };
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-sm';
const CODE = 'rounded-sm bg-surface-tint px-xs py-xs font-mono text-caption text-on-surface';
const MONO_NOTE = 'font-mono text-caption text-on-surface-variant';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';

function OutcomeCard({
  title,
  outcome,
  tone,
  testId,
}: {
  title: string;
  outcome: LookupOutcome;
  tone: 'safe' | 'leaky';
  testId: string;
}): ReactNode {
  return (
    <div data-testid={testId} className={CARD}>
      <p className={OVERLINE}>{title}</p>
      <code data-testid={`${testId}-sql`} className={CODE}>
        {outcome.sqlShown}
      </code>
      {outcome.rows.length > 0 ? (
        <ul data-testid={`${testId}-rows`} className="flex flex-col gap-xxs">
          {outcome.rows.map((row) => (
            <li key={row.username} className="font-mono text-caption">
              <span className={tone === 'leaky' ? 'text-error' : 'text-on-surface-variant'}>
                {row.username}
              </span>{' '}
              — {row.role} — {row.secret}
            </li>
          ))}
        </ul>
      ) : (
        <p data-testid={`${testId}-empty`} className={MONO_NOTE}>
          0 rows returned
        </p>
      )}
      {outcome.containmentError !== null && (
        <p data-testid={`${testId}-contained`} className="font-mono text-caption text-error">
          contained: {outcome.containmentError}
        </p>
      )}
      <p className="text-caption text-on-surface-variant">{outcome.note}</p>
    </div>
  );
}

export default function SqlSafetyDemo(): ReactNode {
  const config = container.appConfig;
  const [payload, setPayload] = useState(PRESETS[0]?.payload ?? '');
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });
  const [running, setRunning] = useState(false);

  const run = async (nextPayload: string): Promise<void> => {
    setPayload(nextPayload);
    setRunning(true);
    setPanel({ kind: 'running' });
    setPanel(await runShowdown(config, nextPayload));
    setRunning(false);
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the payload</p>
        <div className="flex flex-wrap gap-xs">
          {PRESETS.map((preset) => (
            <button
              key={preset.id}
              type="button"
              data-testid={`safety-preset-${preset.id}`}
              className={BUTTON}
              disabled={running}
              onClick={() => void run(preset.payload)}
            >
              {preset.label}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-xs">
          <input
            data-testid="safety-payload-input"
            className="min-w-64 flex-1 rounded-sm border border-outline bg-surface px-sm py-xxs font-mono text-caption text-on-surface"
            value={payload}
            onChange={(event) => setPayload(event.target.value)}
            placeholder="type your own payload"
          />
          <button
            type="button"
            data-testid="safety-run"
            className={BUTTON}
            disabled={running}
            onClick={() => void run(payload)}
          >
            {running ? 'running…' : 'run both doors'}
          </button>
        </div>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the injection showdown" failure={panel.failure} />}

      {panel.kind === 'ready' && (
        <>
          <div className="grid gap-sm md:grid-cols-2">
            <OutcomeCard
              testId="safety-parameterized"
              title="bind parameter — the payload is a value"
              outcome={panel.showdown.parameterized}
              tone="safe"
            />
            <OutcomeCard
              testId="safety-vulnerable"
              title="concatenation — the payload became structure"
              outcome={panel.showdown.vulnerable}
              tone="leaky"
            />
          </div>
          <p data-testid="safety-verdict" className="rounded-sm border border-secondary bg-secondary/10 px-sm py-xs text-body-sm text-on-surface">
            {panel.showdown.verdict}
          </p>
        </>
      )}
    </div>
  );
}
