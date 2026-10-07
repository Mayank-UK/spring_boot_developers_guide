import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `jvm:threads` — topic 02's live answer to "threads vs
 * event loop":
 * - GET /api/demo/jvm/threads/compare?tasks=N — the same N blocking tasks on
 *   a fixed pool of 4 platform threads AND one virtual thread per task:
 *   wall time, distinct thread names, sample names, and the carrier thread
 *   visible inside each virtual thread's toString()
 * - GET /api/demo/jvm/threads?tasks=N&mode=platform|virtual — one model alone
 *
 * A teaching widget: it fetches directly (no dedicated repository), but
 * through the SAME guarded pipeline every data source uses — transport
 * failures and error envelopes arrive as typed Failures in Results.
 */

const TASK_CHOICES = [8, 24, 64] as const;
type TaskChoice = (typeof TASK_CHOICES)[number];
type Mode = 'platform' | 'virtual';

interface RunReport {
  mode: string;
  tasks: number;
  wallTimeMillis: number;
  distinctThreadNames: number;
  sampleThreadNames: string[];
  virtualThreadsObserved: boolean;
  carrierObserved: boolean;
  carrierSample: string | null;
}

interface ComparisonReport {
  tasks: number;
  platform: RunReport;
  virtual: RunReport;
  summary: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: ComparisonReport }
  | { kind: 'error'; failure: Failure };

type SoloState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; report: RunReport }
  | { kind: 'error'; failure: Failure };

/* ---- shared guarded fetch: direct call, pipeline rules unchanged ---- */

async function fetchJson<T>(
  config: AppConfig,
  path: string,
  parse: (body: unknown) => T,
): Promise<T> {
  const result = await safeRepositoryCall(async () => {
    const { body } = await guardedGetJson(config, path);
    return parseDto(body, 200, parse);
  });
  if (result.ok) return result.value;
  throw result.failure;
}

/* ---- runtime validation of the stable wire shapes ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseRunReport(body: unknown): RunReport {
  if (!isRecord(body)) throw new Error('run report must be an object');
  const samples = body.sampleThreadNames;
  if (!Array.isArray(samples) || samples.some((item) => typeof item !== 'string')) {
    throw new Error('sampleThreadNames must be an array of strings');
  }
  if (
    typeof body.mode !== 'string' ||
    typeof body.tasks !== 'number' ||
    typeof body.wallTimeMillis !== 'number' ||
    typeof body.distinctThreadNames !== 'number' ||
    typeof body.virtualThreadsObserved !== 'boolean' ||
    typeof body.carrierObserved !== 'boolean'
  ) {
    throw new Error('run report fields have the wrong shape');
  }
  return {
    mode: body.mode,
    tasks: body.tasks,
    wallTimeMillis: body.wallTimeMillis,
    distinctThreadNames: body.distinctThreadNames,
    sampleThreadNames: samples as string[],
    virtualThreadsObserved: body.virtualThreadsObserved,
    carrierObserved: body.carrierObserved,
    carrierSample: typeof body.carrierSample === 'string' ? body.carrierSample : null,
  };
}

function parseComparison(body: unknown): ComparisonReport {
  if (!isRecord(body)) throw new Error('comparison must be an object');
  if (typeof body.tasks !== 'number' || typeof body.summary !== 'string') {
    throw new Error('comparison needs numeric tasks and string summary');
  }
  return {
    tasks: body.tasks,
    platform: parseRunReport(body.platform),
    virtual: parseRunReport(body.virtual),
    summary: body.summary,
  };
}

/* ---- small shared presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs font-mono';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function RunCard({ report, title, testId }: { report: RunReport; title: string; testId: string }) {
  return (
    <div
      data-testid={testId}
      className="flex flex-col gap-xs rounded-lg border border-outline bg-surface-tint p-sm"
    >
      <div className="flex items-baseline justify-between gap-xs">
        <span className="font-display text-title-sm text-on-surface">{title}</span>
        <span className="font-mono text-caption text-on-surface-variant">
          {report.wallTimeMillis} ms wall
        </span>
      </div>
      <p className="text-body-sm text-on-surface">
        {report.tasks} blocking tasks on{' '}
        <span className="font-mono" data-testid={`${testId}-distinct`}>
          {report.distinctThreadNames}
        </span>{' '}
        distinct threads
      </p>
      <ul className="flex flex-col gap-xxs">
        {report.sampleThreadNames.slice(0, 3).map((name) => (
          <li key={name} className={`${CELL} text-caption text-on-surface-variant`}>
            {name}
          </li>
        ))}
      </ul>
      {report.carrierObserved && report.carrierSample !== null ? (
        <p className="font-mono text-caption text-secondary" data-testid={`${testId}-carrier`}>
          carrier visible: {report.carrierSample}
        </p>
      ) : (
        <p className="font-mono text-caption text-on-surface-variant">1:1 OS threads — no carrier to see</p>
      )}
    </div>
  );
}

export default function ThreadsDemo() {
  const [tasks, setTasks] = useState<TaskChoice>(24);
  const [comparison, setComparison] = useState<PanelState>({ kind: 'loading' });
  const [solo, setSolo] = useState<SoloState>({ kind: 'idle' });
  const config = container.appConfig;

  const loadComparison = useCallback(
    (taskCount: TaskChoice) => {
      setComparison({ kind: 'loading' });
      fetchJson(config, `/demo/jvm/threads/compare?tasks=${String(taskCount)}`, parseComparison)
        .then((report) => setComparison({ kind: 'ready', report }))
        .catch((failure: Failure) => setComparison({ kind: 'error', failure }));
    },
    [config],
  );

  useEffect(() => {
    loadComparison(24);
  }, [loadComparison]);

  const runSolo = (mode: Mode) => {
    setSolo({ kind: 'loading' });
    fetchJson(config, `/demo/jvm/threads?tasks=${String(tasks)}&mode=${mode}`, parseRunReport)
      .then((report) => setSolo({ kind: 'ready', report }))
      .catch((failure: Failure) => setSolo({ kind: 'error', failure }));
  };

  return (
    <div className="flex flex-col gap-md">
      {/* Panel (a): the head-to-head — same tasks, two threading models. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>platform pool vs virtual threads — same tasks, two runtimes</p>

        <div className="flex flex-wrap items-center gap-xs" role="group" aria-label="task count">
          {TASK_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              data-testid={`jvm-threads-tasks-${choice}`}
              aria-pressed={choice === tasks}
              onClick={() => {
                setTasks(choice);
                loadComparison(choice);
              }}
              className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
                choice === tasks
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant hover:bg-surface-tint'
              }`}
            >
              {choice} tasks
            </button>
          ))}
        </div>

        {comparison.kind === 'loading' && (
          <p data-testid="jvm-threads-loading" className="text-body-sm text-on-surface-variant">
            running both models…
          </p>
        )}
        {comparison.kind === 'error' && (
          <ErrorPanel subject="the thread comparison" failure={comparison.failure} />
        )}
        {comparison.kind === 'ready' && (
          <div className="grid gap-sm md:grid-cols-2">
            <RunCard
              report={comparison.report.platform}
              title="platform: pool of 4"
              testId="jvm-threads-compare-platform"
            />
            <RunCard
              report={comparison.report.virtual}
              title="virtual: 1 per task"
              testId="jvm-threads-compare-virtual"
            />
            <p
              data-testid="jvm-threads-summary"
              className="text-body-sm text-on-surface md:col-span-2"
            >
              {comparison.report.summary}
            </p>
          </div>
        )}
      </section>

      {/* Panel (b): one model alone, on demand. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>run one model alone</p>
        <div className="flex flex-wrap gap-xs">
          <button
            type="button"
            data-testid="jvm-threads-run-platform"
            onClick={() => runSolo('platform')}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface hover:bg-surface-tint"
          >
            platform × {tasks}
          </button>
          <button
            type="button"
            data-testid="jvm-threads-run-virtual"
            onClick={() => runSolo('virtual')}
            className="rounded-pill border border-secondary px-sm py-xxs font-mono text-caption text-on-surface hover:bg-surface-tint"
          >
            virtual × {tasks}
          </button>
        </div>

        {solo.kind === 'loading' && (
          <p className="text-body-sm text-on-surface-variant">running {tasks} tasks…</p>
        )}
        {solo.kind === 'error' && <ErrorPanel subject="the single-model run" failure={solo.failure} />}
        {solo.kind === 'ready' && (
          <RunCard
            report={solo.report}
            title={`${solo.report.mode} run`}
            testId={`jvm-threads-solo-${solo.report.mode}`}
          />
        )}
      </section>
    </div>
  );
}
