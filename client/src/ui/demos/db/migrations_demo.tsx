import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:migrations` — topic 17 (jdbc-sql), the
 * migrations-with-flyway section. The applied ledger read from
 * flyway_schema_history (versions, descriptions, install times, the state
 * sentence) plus an Apply button that runs SchemaMigrator.migrate() again —
 * and the idempotence is the lesson: zero scripts, one history query.
 */

interface AppliedMigration {
  version: string;
  description: string;
  installedOn: string;
  success: boolean;
}

interface History {
  applied: AppliedMigration[];
  summary: string;
  pending: number;
  mode: string;
}

interface Application {
  migrationsApplied: number;
  appliedVersions: string[];
  summary: string;
  note: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; history: History }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchJson(config: AppConfig, path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, init);
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

function parseHistory(body: unknown): History {
  if (!isRecord(body)) throw new Error('history must be an object');
  if (
    typeof body.summary !== 'string'
    || typeof body.pending !== 'number'
    || typeof body.mode !== 'string'
    || !Array.isArray(body.applied)
  ) {
    throw new Error('history needs applied, summary, pending, mode');
  }
  for (const row of body.applied) {
    if (
      !isRecord(row)
      || typeof row.version !== 'string'
      || typeof row.description !== 'string'
      || typeof row.installedOn !== 'string'
      || typeof row.success !== 'boolean'
    ) {
      throw new Error('each applied row needs version, description, installedOn, success');
    }
  }
  return {
    applied: body.applied as unknown as AppliedMigration[],
    summary: body.summary,
    pending: body.pending,
    mode: body.mode,
  };
}

function parseApplication(body: unknown): Application {
  if (!isRecord(body)) throw new Error('application must be an object');
  if (
    typeof body.migrationsApplied !== 'number'
    || typeof body.summary !== 'string'
    || typeof body.note !== 'string'
    || !Array.isArray(body.appliedVersions)
  ) {
    throw new Error('application needs migrationsApplied, appliedVersions, summary, note');
  }
  return {
    migrationsApplied: body.migrationsApplied,
    appliedVersions: body.appliedVersions.map((version) => String(version)),
    summary: body.summary,
    note: body.note,
  };
}

const CELL = 'border-b border-outline px-xs py-xs';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';

export default function MigrationsDemo(): ReactNode {
  const config = container.appConfig;
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const [application, setApplication] = useState<Application | null>(null);
  const [applying, setApplying] = useState(false);

  const load = async (): Promise<void> => {
    const result = await safeRepositoryCall(async () =>
      parseHistory(await fetchJson(config, '/demo/db/migrations')));
    setPanel(result.ok ? { kind: 'ready', history: result.value } : { kind: 'error', failure: result.failure });
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const apply = async (): Promise<void> => {
    setApplying(true);
    const result = await safeRepositoryCall(async () =>
      parseApplication(await fetchJson(config, '/demo/db/migrations/apply', { method: 'POST' })));
    if (result.ok) {
      setApplication(result.value);
      await load();
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
    setApplying(false);
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-sm">
        <button
          type="button"
          data-testid="migrations-apply"
          className={BUTTON}
          disabled={applying}
          onClick={() => void apply()}
        >
          {applying ? 'applying…' : 'apply pending migrations'}
        </button>
        <span className="font-mono text-caption text-on-surface-variant">— expect zero</span>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="migrations-loading" className="font-mono text-caption text-on-surface-variant">
          fetching /api/demo/db/migrations…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the migration ledger" failure={panel.failure} />}

      {panel.kind === 'ready' && (
        <>
          <p data-testid="migrations-summary" className="font-mono text-caption text-on-surface">
            {panel.history.summary}
          </p>
          <p data-testid="migrations-mode" className="font-mono text-caption text-on-surface-variant">
            mode: {panel.history.mode}
          </p>
          <table data-testid="migrations-ledger" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                <th className={CELL}>version</th>
                <th className={CELL}>description</th>
                <th className={CELL}>installed on</th>
                <th className={CELL}>success</th>
              </tr>
            </thead>
            <tbody className="text-on-surface">
              {panel.history.applied.map((row) => (
                <tr key={row.version} data-testid={`migrations-row-${row.version}`}>
                  <td className={`${CELL} font-mono`}>{row.version}</td>
                  <td className={CELL}>{row.description}</td>
                  <td className={`${CELL} font-mono text-caption`}>{row.installedOn}</td>
                  <td className={`${CELL} font-mono text-caption`}>
                    {row.success ? 'true' : 'false'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {application !== null && (
        <div data-testid="migrations-application" className="flex flex-col gap-xxs rounded-sm border border-secondary bg-secondary/10 px-sm py-sm">
          <p className="font-mono text-caption text-on-surface">
            migrationsApplied: {String(application.migrationsApplied)}
          </p>
          <p className="text-caption text-on-surface-variant">{application.note}</p>
        </div>
      )}
    </div>
  );
}
