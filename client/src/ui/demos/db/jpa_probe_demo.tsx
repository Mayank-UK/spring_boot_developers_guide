import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:jpa-probe` — topic 18 (spring-data-jpa), the
 * n-plus-one section. The four fetch shapes (n-plus-1, join-fetch,
 * entity-graph, batch) run the same page of specimen posts against live
 * PostgreSQL, and the measurement — queriesIssued above all — is the
 * lesson. A 503 before activation is part of the teaching: the error panel
 * carries the typed envelope that names the PATCH which flips the area
 * live.
 */

interface ProbeReport {
  mode: string;
  size: number;
  queriesIssued: number;
  postsReturned: number;
  entriesLoaded: number;
  wallTimeNanos: number;
  verdict: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading'; mode: string }
  | { kind: 'ready'; report: ProbeReport }
  | { kind: 'error'; failure: Failure };

const MODES: ReadonlyArray<{ id: string; label: string; hint: string }> = [
  { id: 'n-plus-1', label: 'n-plus-1', hint: 'the disease: 1 + N queries' },
  { id: 'join-fetch', label: 'join-fetch', hint: 'one query, widest rows' },
  { id: 'entity-graph', label: 'entity-graph', hint: 'one query, per-query plan' },
  { id: 'batch', label: 'batch', hint: 'lazy kept, round trips collapsed' },
];

const SIZES = [3, 5, 8, 12] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseReport(body: unknown): ProbeReport {
  if (!isRecord(body)) throw new Error('report must be an object');
  if (
    typeof body.mode !== 'string'
    || typeof body.size !== 'number'
    || typeof body.queriesIssued !== 'number'
    || typeof body.postsReturned !== 'number'
    || typeof body.entriesLoaded !== 'number'
    || typeof body.wallTimeNanos !== 'number'
    || typeof body.verdict !== 'string'
  ) {
    throw new Error('report needs mode, size, queriesIssued, postsReturned, entriesLoaded, wallTimeNanos, verdict');
  }
  return {
    mode: body.mode,
    size: body.size,
    queriesIssued: body.queriesIssued,
    postsReturned: body.postsReturned,
    entriesLoaded: body.entriesLoaded,
    wallTimeNanos: body.wallTimeNanos,
    verdict: body.verdict,
  };
}

async function fetchJson(config: AppConfig, path: string): Promise<unknown> {
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

const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';
const MODE_BUTTON_ACTIVE = `${BUTTON} bg-primary border-primary text-on-primary`;

export default function JpaProbeDemo(): ReactNode {
  const config = container.appConfig;
  const [mode, setMode] = useState<string>('n-plus-1');
  const [size, setSize] = useState<number>(5);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const run = useCallback(
    async (nextMode: string, nextSize: number): Promise<void> => {
      setPanel({ kind: 'loading', mode: nextMode });
      const result = await safeRepositoryCall(async () =>
        parseReport(await fetchJson(config, `/demo/jpa/probe?mode=${nextMode}&size=${String(nextSize)}`)));
      setPanel(result.ok ? { kind: 'ready', report: result.value } : { kind: 'error', failure: result.failure });
    },
    [config],
  );

  useEffect(() => {
    void run('n-plus-1', 5);
  }, [run]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-sm">
        {MODES.map((entry) => (
          <button
            key={entry.id}
            type="button"
            data-testid={`jpa-mode-${entry.id}`}
            className={mode === entry.id ? MODE_BUTTON_ACTIVE : BUTTON}
            onClick={() => {
              setMode(entry.id);
              void run(entry.id, size);
            }}
          >
            {entry.label}
          </button>
        ))}
      </section>

      <section className="flex flex-wrap items-center gap-sm">
        <span className="font-mono text-caption text-on-surface-variant">size</span>
        {SIZES.map((option) => (
          <button
            key={option}
            type="button"
            data-testid={`jpa-size-${String(option)}`}
            className={size === option ? MODE_BUTTON_ACTIVE : BUTTON}
            onClick={() => {
              setSize(option);
              void run(mode, option);
            }}
          >
            {String(option)}
          </button>
        ))}
      </section>

      {panel.kind === 'idle' && (
        <p className="font-mono text-caption text-on-surface-variant">pick a fetch shape to measure.</p>
      )}
      {panel.kind === 'loading' && (
        <p data-testid="jpa-probe-loading" className="font-mono text-caption text-on-surface-variant">
          measuring {panel.mode}…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the JPA probe" failure={panel.failure} />}

      {panel.kind === 'ready' && (
        <>
          <p data-testid="jpa-probe-queries" className="font-mono text-body text-primary">
            {String(panel.report.queriesIssued)} queries
          </p>
          <table data-testid="jpa-probe-report" className="w-full border-collapse text-body-sm">
            <tbody className="text-on-surface">
              <tr>
                <td className="border-b border-outline px-xs py-xxs text-on-surface-variant">mode</td>
                <td data-testid="jpa-probe-mode" className="border-b border-outline px-xs py-xxs font-mono">
                  {panel.report.mode} · size {String(panel.report.size)}
                </td>
              </tr>
              <tr>
                <td className="border-b border-outline px-xs py-xxs text-on-surface-variant">rows</td>
                <td data-testid="jpa-probe-rows" className="border-b border-outline px-xs py-xxs font-mono">
                  {String(panel.report.postsReturned)} posts · {String(panel.report.entriesLoaded)} entries
                </td>
              </tr>
              <tr>
                <td className="border-b border-outline px-xs py-xxs text-on-surface-variant">wall time</td>
                <td data-testid="jpa-probe-wall" className="border-b border-outline px-xs py-xxs font-mono">
                  {String(Math.round(panel.report.wallTimeNanos / 1_000) / 1_000)} ms
                </td>
              </tr>
              <tr>
                <td className="border-b border-outline px-xs py-xxs text-on-surface-variant">verdict</td>
                <td data-testid="jpa-probe-verdict" className="border-b border-outline px-xs py-xxs">
                  {panel.report.verdict}
                </td>
              </tr>
            </tbody>
          </table>
          <p className="text-caption text-on-surface-variant">
            {MODES.find((entry) => entry.id === panel.report.mode)?.hint ?? ''} — specimens are seeded and
            rolled back inside one transaction; only the read phase is counted.
          </p>
        </>
      )}
    </div>
  );
}
