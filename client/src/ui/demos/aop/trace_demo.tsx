import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `aop:trace` — topic 06 (aop), the live half of the
 * aspect-anatomy section: GET /api/demo/aop/trace runs one succeeding and
 * one deliberately failing traced step through the real aspect machinery,
 * then returns the evidence the InvocationTraceAspect filed for each —
 * method, capped args summary, outcome (ok vs exception), durationNanos,
 * and the request id the call rode in on. Press "run again": the durations
 * change, because this is measurement happening now, not a cached page.
 *
 * A teaching widget: it fetches directly through the SAME guarded pipeline
 * every data source uses.
 */

interface InvocationRecord {
  method: string;
  argsSummary: string;
  outcome: string;
  durationNanos: number;
  requestId: string | null;
}

interface TraceReport {
  stepResult: string;
  failureMessage: string;
  okRecord: InvocationRecord | null;
  exceptionRecord: InvocationRecord | null;
  logTail: InvocationRecord[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: TraceReport }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the wire shape ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`field "${key}" must be a number`);
  }
  return value;
}

function parseRecord(source: unknown): InvocationRecord {
  if (!isRecord(source)) throw new Error('each invocation must be an object');
  const requestId = source.requestId;
  if (requestId !== null && typeof requestId !== 'string') {
    throw new Error('field "requestId" must be a string or null');
  }
  return {
    method: stringField(source, 'method'),
    argsSummary: stringField(source, 'argsSummary'),
    outcome: stringField(source, 'outcome'),
    durationNanos: numberField(source, 'durationNanos'),
    requestId,
  };
}

function parseTraceReport(body: unknown): TraceReport {
  if (!isRecord(body)) throw new Error('trace report must be an object');
  const tail = body.logTail;
  if (!Array.isArray(tail)) throw new Error('field "logTail" must be an array');
  const ok = body.okRecord;
  const failure = body.exceptionRecord;
  return {
    stepResult: stringField(body, 'stepResult'),
    failureMessage: stringField(body, 'failureMessage'),
    okRecord: ok === null ? null : parseRecord(ok),
    exceptionRecord: failure === null ? null : parseRecord(failure),
    logTail: tail.map(parseRecord),
  };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CELL = 'border-b border-outline px-xs py-xs';

function shortMethod(method: string): string {
  const lastDot = method.lastIndexOf('.');
  return lastDot === -1 ? method : method.slice(lastDot + 1);
}

function RecordCard({
  label,
  testId,
  record,
}: {
  label: string;
  testId: string;
  record: InvocationRecord | null;
}) {
  return (
    <div
      data-testid={testId}
      className="flex flex-col gap-xxs rounded-md border border-outline bg-surface px-sm py-sm"
    >
      <p className={OVERLINE}>{label}</p>
      {record === null ? (
        <p className="text-body-sm text-on-surface-variant">(no record — was the proxy active?)</p>
      ) : (
        <dl className="grid grid-cols-1 gap-xxs text-body-sm sm:grid-cols-2">
          <div>
            <dt className={OVERLINE}>method</dt>
            <dd className="break-all font-mono text-on-surface">{shortMethod(record.method)}</dd>
          </div>
          <div>
            <dt className={OVERLINE}>args</dt>
            <dd className="break-all font-mono text-on-surface">{record.argsSummary}</dd>
          </div>
          <div>
            <dt className={OVERLINE}>outcome</dt>
            <dd
              className={
                record.outcome === 'ok' ? 'text-on-surface' : 'font-semibold text-on-surface'
              }
            >
              {record.outcome}
            </dd>
          </div>
          <div>
            <dt className={OVERLINE}>duration</dt>
            <dd className="font-mono text-on-surface">{String(record.durationNanos)} ns</dd>
          </div>
          <div>
            <dt className={OVERLINE}>request id</dt>
            <dd className="font-mono text-on-surface-variant">{record.requestId ?? '(off-HTTP)'}</dd>
          </div>
        </dl>
      )}
    </div>
  );
}

export default function TraceDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const { body, status } = await guardedGetJson(config, '/demo/aop/trace');
      return parseDto(body, status, parseTraceReport);
    });
    setState(
      result.ok ? { kind: 'ready', report: result.value } : { kind: 'error', failure: result.failure },
    );
  }, [config]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="flex flex-col gap-sm">
      <div className="flex flex-wrap items-center justify-between gap-xs">
        <p className={OVERLINE}>the log, after two real calls</p>
        <button
          type="button"
          data-testid="aop-trace-run-again"
          onClick={() => void load()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint"
        >
          run again
        </button>
      </div>

      {state.kind === 'loading' && (
        <p data-testid="aop-trace-loading" className="text-body-sm text-on-surface-variant">
          running the traced steps…
        </p>
      )}
      {state.kind === 'error' && <ErrorPanel subject="the trace evidence" failure={state.failure} />}
      {state.kind === 'ready' && (
        <div className="flex flex-col gap-sm" data-testid="aop-trace-report">
          <div className="grid grid-cols-1 gap-sm sm:grid-cols-2">
            <RecordCard label="outcome ok" testId="aop-trace-ok" record={state.report.okRecord} />
            <RecordCard
              label="outcome exception"
              testId="aop-trace-exception"
              record={state.report.exceptionRecord}
            />
          </div>
          <p data-testid="aop-trace-failure-message" className="text-body-sm text-on-surface-variant">
            the failing step threw: <span className="font-mono">{state.report.failureMessage}</span>{' '}
            — rethrown untouched, recorded in finally.
          </p>
          <div className="flex flex-col gap-xs">
            <p className={OVERLINE}>the raw log tail</p>
            <table
              data-testid="aop-trace-tail"
              className="w-full border-collapse text-body-sm"
            >
              <thead>
                <tr className="text-left text-on-surface-variant">
                  <th className={CELL}>method</th>
                  <th className={CELL}>args</th>
                  <th className={CELL}>outcome</th>
                  <th className={CELL}>durationNanos</th>
                </tr>
              </thead>
              <tbody className="text-on-surface">
                {state.report.logTail.map((record, index) => (
                  <tr key={`${record.method}-${String(index)}`}>
                    <td className={`${CELL} font-mono`}>{shortMethod(record.method)}</td>
                    <td className={`${CELL} font-mono`}>{record.argsSummary}</td>
                    <td className={CELL}>{record.outcome}</td>
                    <td className={`${CELL} font-mono`}>{String(record.durationNanos)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
