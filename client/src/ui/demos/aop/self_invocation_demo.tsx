import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `aop:self-invocation` — topic 06 (aop), the live half
 * of the-self-invocation-trap section: GET /api/demo/aop/self-invocation
 * runs the ExhibitSelfInvocation workflow through the live proxy and
 * reports what the InvocationLog actually saw. The outer placeOrder call is
 * recorded; the inner reserveStock — which demonstrably RAN, its result sits
 * inside the order summary — left no record at all. The report derives from
 * the real log, so what renders is what the machinery did.
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

interface SelfInvocationReport {
  orderSummary: string;
  outerMethod: string;
  innerMethod: string;
  outerRecorded: boolean;
  innerRecorded: boolean;
  exhibitLog: InvocationRecord[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: SelfInvocationReport }
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

function booleanField(source: Record<string, unknown>, key: string): boolean {
  const value = source[key];
  if (typeof value !== 'boolean') throw new Error(`field "${key}" must be a boolean`);
  return value;
}

function parseRecord(source: unknown): InvocationRecord {
  if (!isRecord(source)) throw new Error('each invocation must be an object');
  const requestId = source.requestId;
  if (requestId !== null && typeof requestId !== 'string') {
    throw new Error('field "requestId" must be a string or null');
  }
  const duration = source.durationNanos;
  if (typeof duration !== 'number' || !Number.isFinite(duration)) {
    throw new Error('field "durationNanos" must be a number');
  }
  return {
    method: stringField(source, 'method'),
    argsSummary: stringField(source, 'argsSummary'),
    outcome: stringField(source, 'outcome'),
    durationNanos: duration,
    requestId,
  };
}

function parseReport(body: unknown): SelfInvocationReport {
  if (!isRecord(body)) throw new Error('self-invocation report must be an object');
  const log = body.exhibitLog;
  if (!Array.isArray(log)) throw new Error('field "exhibitLog" must be an array');
  return {
    orderSummary: stringField(body, 'orderSummary'),
    outerMethod: stringField(body, 'outerMethod'),
    innerMethod: stringField(body, 'innerMethod'),
    outerRecorded: booleanField(body, 'outerRecorded'),
    innerRecorded: booleanField(body, 'innerRecorded'),
    exhibitLog: log.map(parseRecord),
  };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CELL = 'border-b border-outline px-xs py-xs';
const VERDICT = 'rounded-md border px-sm py-sm text-body-sm';

function shortMethod(method: string): string {
  const lastDot = method.lastIndexOf('.');
  return lastDot === -1 ? method : method.slice(lastDot + 1);
}

function Verdict({ report }: { report: SelfInvocationReport }) {
  return (
    <div className="grid grid-cols-1 gap-sm sm:grid-cols-2">
      <div
        data-testid="aop-trap-outer-verdict"
        className={`${VERDICT} border-outline bg-surface-tint text-on-surface`}
      >
        <p className="font-mono">{report.outerMethod}</p>
        <p data-testid="aop-trap-outer-value" className="font-semibold">
          {report.outerRecorded ? 'recorded — the call passed through the proxy' : 'not recorded'}
        </p>
      </div>
      <div
        data-testid="aop-trap-inner-verdict"
        className={`${VERDICT} border-outline text-on-surface-variant`}
      >
        <p className="font-mono">{report.innerMethod}</p>
        <p data-testid="aop-trap-inner-value" className="font-semibold">
          {report.innerRecorded
            ? 'recorded'
            : 'not recorded — the internal call bypassed the proxy'}
        </p>
      </div>
    </div>
  );
}

export default function SelfInvocationDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const { body, status } = await guardedGetJson(config, '/demo/aop/self-invocation');
      return parseDto(body, status, parseReport);
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
        <p className={OVERLINE}>outer recorded. inner: nowhere.</p>
        <button
          type="button"
          data-testid="aop-trap-run-again"
          onClick={() => void load()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint"
        >
          run again
        </button>
      </div>

      {state.kind === 'loading' && (
        <p data-testid="aop-trap-loading" className="text-body-sm text-on-surface-variant">
          running the order workflow…
        </p>
      )}
      {state.kind === 'error' && (
        <ErrorPanel subject="the self-invocation report" failure={state.failure} />
      )}
      {state.kind === 'ready' && (
        <div className="flex flex-col gap-sm" data-testid="aop-trap-report">
          <p data-testid="aop-trap-order-summary" className="text-body-sm text-on-surface">
            the workflow returned <span className="font-mono">{state.report.orderSummary}</span> —
            the inner step demonstrably ran; the aspect just never heard about it.
          </p>
          <Verdict report={state.report} />
          <div className="flex flex-col gap-xs">
            <p className={OVERLINE}>what the invocation log holds for this exhibit</p>
            <table data-testid="aop-trap-log" className="w-full border-collapse text-body-sm">
              <thead>
                <tr className="text-left text-on-surface-variant">
                  <th className={CELL}>method</th>
                  <th className={CELL}>args</th>
                  <th className={CELL}>outcome</th>
                  <th className={CELL}>durationNanos</th>
                </tr>
              </thead>
              <tbody className="text-on-surface">
                {state.report.exhibitLog.length === 0 ? (
                  <tr>
                    <td className={CELL} colSpan={4}>
                      (empty — was the proxy active?)
                    </td>
                  </tr>
                ) : (
                  state.report.exhibitLog.map((record, index) => (
                    <tr key={`${record.method}-${String(index)}`}>
                      <td className={`${CELL} font-mono`}>{shortMethod(record.method)}</td>
                      <td className={`${CELL} font-mono`}>{record.argsSummary}</td>
                      <td className={CELL}>{record.outcome}</td>
                      <td className={`${CELL} font-mono`}>{String(record.durationNanos)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
