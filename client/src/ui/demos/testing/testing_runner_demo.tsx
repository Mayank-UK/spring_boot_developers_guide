import { useCallback, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `testing:runner` — topic 34 (testing), the slice
 * section. The test battery as a live surface:
 *
 * - "run the battery" calls the runner endpoint and renders the report
 *   exactly as served: every check (name, layer, status, duration, the
 *   reason it holds or failed) plus the tally and total duration;
 * - the failure-injection switch PATCHes the shared scenario store and
 *   re-runs — with injection ON the deliberately wrong assertion shows
 *   what a red check looks like: named, timed, carrying its reason;
 * - absent machinery (a slice's web check) renders SKIPPED, not faked.
 */

interface CheckResult {
  name: string;
  layer: string;
  status: string;
  durationMs: number;
  detail: string;
}

interface BatteryReport {
  failureInjected: boolean;
  passed: number;
  failed: number;
  skipped: number;
  totalDurationMs: number;
  checks: CheckResult[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number') throw new Error(`field "${key}" must be a number`);
  return value;
}

function textField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function parseCheck(source: unknown): CheckResult {
  if (!isRecord(source)) throw new Error('a check must be an object');
  return {
    name: textField(source, 'name'),
    layer: textField(source, 'layer'),
    status: textField(source, 'status'),
    durationMs: numberField(source, 'durationMs'),
    detail: textField(source, 'detail'),
  };
}

function parseReport(source: unknown): BatteryReport {
  if (!isRecord(source)) throw new Error('the battery answer must be an object');
  const checks = source.checks;
  if (!Array.isArray(checks)) throw new Error('field "checks" must be an array');
  return {
    failureInjected: source.failureInjected === true,
    passed: numberField(source, 'passed'),
    failed: numberField(source, 'failed'),
    skipped: numberField(source, 'skipped'),
    totalDurationMs: numberField(source, 'totalDurationMs'),
    checks: checks.map(parseCheck),
  };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';
const CHIP = 'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

function statusClass(status: string): string {
  switch (status) {
    case 'PASSED':
      return 'text-primary';
    case 'FAILED':
      return 'text-error';
    default:
      return 'text-on-surface-variant';
  }
}

function ms(value: number): string {
  return `${value.toFixed(1)} ms`;
}

export default function TestingRunnerDemo() {
  const config = container.appConfig;
  const [report, setReport] = useState<BatteryReport | null>(null);
  const [inject, setInject] = useState(false);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const run = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseReport((await guardedGetJson(config, '/demo/testing/run')).body),
    ).then((result) => {
      if (result.ok) {
        setReport(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  /** Flip the scenario store the way every failure-injection demo does: one PATCH, shared state. */
  const toggleInjection = (next: boolean) => {
    setInject(next);
    void safeRepositoryCall(async () => {
      const response = await fetch(`${config.apiBaseUrl}/demo/scenario`, {
        method: 'PATCH',
        headers: { 'content-type': 'application/json', accept: 'application/json' },
        body: JSON.stringify({ injectFailure: next }),
      });
      if (!response.ok) {
        throw new Error(`the scenario patch failed with status ${String(response.status)}`);
      }
      return undefined;
    }).then((result) => {
      if (result.ok) {
        run();
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-sm">
        <button
          type="button"
          data-testid="testing-run"
          onClick={run}
          className={BUTTON}
        >
          run the battery
        </button>
        <label className="flex items-center gap-xxs font-mono text-caption text-on-surface-variant">
          <input
            type="checkbox"
            data-testid="testing-inject-toggle"
            checked={inject}
            onChange={(event) => toggleInjection(event.target.checked)}
          />
          inject a failing check
        </label>
        {report !== null && (
          <span data-testid="testing-summary" className={CHIP}>
            {report.failureInjected ? 'injection ON · ' : ''}
            {report.passed} passed · {report.failed} failed · {report.skipped} skipped —{' '}
            {ms(report.totalDurationMs)} total
          </span>
        )}
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="testing-loading" className="font-mono text-caption text-on-surface-variant">
          running the checks…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the test battery" failure={panel.failure} />}

      {report !== null && (
        <section
          data-testid="testing-checks"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>the report — every check, its layer, its verdict, its reason</p>
          {report.checks.map((check, index) => (
            <span
              key={`${check.name}-${String(index)}`}
              data-testid={`testing-check-${String(index)}`}
              className="break-all"
            >
              <span className={statusClass(check.status)}>{check.status}</span>{' '}
              <span className="text-on-surface-variant">[{check.layer}] {ms(check.durationMs)}</span>{' '}
              {check.name}
              {check.detail !== '' && (
                <span className="text-on-surface-variant"> — {check.detail}</span>
              )}
            </span>
          ))}
        </section>
      )}
    </div>
  );
}
