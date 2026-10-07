import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:sql-lab` — topic 17 (jdbc-sql), the sql-by-hand
 * section. Four WHITELISTED named queries over the V11 lab dataset — there
 * is no raw-SQL box in this UI on purpose: the client picks a name, the SQL
 * lives in the repo as reviewed constants (the injection rule, applied to
 * reporting endpoints). Each result renders its own SQL beside its rows —
 * the query IS the exhibit.
 */

interface LabResult {
  name: string;
  title: string;
  description: string;
  sql: string;
  rows: Array<Record<string, unknown>>;
}

interface CatalogEntry {
  name: string;
  title: string;
  description: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'catalog'; available: CatalogEntry[] }
  | { kind: 'ready'; result: LabResult }
  | { kind: 'error'; failure: Failure };

const QUERIES: Array<{ id: string; label: string }> = [
  { id: 'cte', label: 'recursive CTE' },
  { id: 'window', label: 'window functions' },
  { id: 'lag', label: 'LAG cadence' },
  { id: 'aggregate', label: 'dept rollup' },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchLab(config: AppConfig, query: string | null): Promise<unknown> {
  const path = query === null ? '/demo/db/sql/lab' : `/demo/db/sql/lab?query=${encodeURIComponent(query)}`;
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`);
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
  return parsed;
}

function parseLabResult(body: unknown): LabResult {
  if (!isRecord(body)) throw new Error('lab result must be an object');
  if (
    typeof body.name !== 'string'
    || typeof body.title !== 'string'
    || typeof body.description !== 'string'
    || typeof body.sql !== 'string'
    || !Array.isArray(body.rows)
  ) {
    throw new Error('lab result needs name, title, description, sql, rows');
  }
  for (const row of body.rows) {
    if (!isRecord(row)) throw new Error('each lab row must be an object');
  }
  return {
    name: body.name,
    title: body.title,
    description: body.description,
    sql: body.sql,
    rows: body.rows,
  };
}

function parseCatalog(body: unknown): CatalogEntry[] {
  if (!isRecord(body) || !Array.isArray(body.available)) {
    throw new Error('catalog needs an available array');
  }
  return body.available.map((entry) => {
    if (
      !isRecord(entry)
      || typeof entry.name !== 'string'
      || typeof entry.title !== 'string'
      || typeof entry.description !== 'string'
    ) {
      throw new Error('each catalog entry needs name, title, description');
    }
    return { name: entry.name, title: entry.title, description: entry.description };
  });
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CODE = 'rounded-sm bg-surface-tint px-sm py-xs font-mono text-caption text-on-surface whitespace-pre-wrap';
const CELL = 'border-b border-outline px-xs py-xxs';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';

function renderCell(value: unknown): ReactNode {
  if (value === null) {
    return <span className="font-mono text-caption text-on-surface-variant">null</span>;
  }
  if (typeof value === 'number') {
    return <span className="font-mono text-caption">{value.toLocaleString('en-US')}</span>;
  }
  return <span className="font-mono text-caption">{String(value)}</span>;
}

export default function SqlLabDemo(): ReactNode {
  const config = container.appConfig;
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const [running, setRunning] = useState<string | null>(null);

  const run = async (query: string): Promise<void> => {
    setRunning(query);
    const result = await safeRepositoryCall(async () => parseLabResult(await fetchLab(config, query)));
    setPanel(result.ok ? { kind: 'ready', result: result.value } : { kind: 'error', failure: result.failure });
    setRunning(null);
  };

  useEffect(() => {
    const load = async (): Promise<void> => {
      const result = await safeRepositoryCall(async () => parseCatalog(await fetchLab(config, null)));
      setPanel(
        result.ok ? { kind: 'catalog', available: result.value } : { kind: 'error', failure: result.failure },
      );
    };
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the whitelist — pick a name, never a statement</p>
        <div className="flex flex-wrap gap-xs">
          {QUERIES.map((query) => (
            <button
              key={query.id}
              type="button"
              data-testid={`lab-run-${query.id}`}
              className={BUTTON}
              disabled={running !== null}
              onClick={() => void run(query.id)}
            >
              {running === query.id ? 'running…' : query.label}
            </button>
          ))}
        </div>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="lab-loading" className="font-mono text-caption text-on-surface-variant">
          fetching the catalog…
        </p>
      )}
      {panel.kind === 'catalog' && (
        <ul data-testid="lab-catalog" className="flex flex-col gap-xxs">
          {panel.available.map((entry) => (
            <li key={entry.name} className="text-caption text-on-surface-variant">
              <span className="font-mono text-on-surface">{entry.name}</span> — {entry.title}
            </li>
          ))}
        </ul>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the lab query" failure={panel.failure} />}

      {panel.kind === 'ready' && (
        <section className="flex flex-col gap-sm">
          <p data-testid="lab-title" className="text-body-sm font-semibold text-on-surface">
            {panel.result.title}
          </p>
          <code data-testid="lab-sql" className={CODE}>
            {panel.result.sql}
          </code>
          <p className="text-caption text-on-surface-variant">{panel.result.description}</p>
          <table data-testid="lab-rows" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                {Object.keys(panel.result.rows[0] ?? {}).map((column) => (
                  <th key={column} className={`${CELL} font-semibold`}>
                    {column}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="text-on-surface">
              {panel.result.rows.map((row, index) => (
                <tr key={index} data-testid="lab-row">
                  {Object.values(row).map((value, columnIndex) => (
                    <td key={columnIndex} className={CELL}>
                      {renderCell(value)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <p data-testid="lab-rowcount" className="font-mono text-caption text-on-surface-variant">
            {String(panel.result.rows.length)} rows
          </p>
        </section>
      )}
    </div>
  );
}
