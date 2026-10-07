import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `middleware:pipeline` — topic 08
 * (filters-interceptors), the live half of the-middleware-model section:
 * GET /api/demo/middleware/pipeline returns the middleware marker sequence
 * captured for THAT very request (filter before → interceptor preHandle →
 * you are here), the most recent COMPLETED request's full unwind from the
 * bounded ring, and the servlet filters the running app actually registers
 * with their real orders. Press "run again": the previous fetch becomes the
 * previousCompletedTrace — postHandle, afterCompletion and the filter
 * after-markers arrive only after a response is written.
 *
 * A teaching widget: it fetches directly through the SAME guarded pipeline
 * every data source uses.
 */

interface CompletedTraceDto {
  requestId: string | null;
  method: string;
  path: string;
  status: number;
  markers: string[];
}

interface RegisteredFilterDto {
  name: string;
  order: number;
  registration: string;
}

interface PipelineReport {
  requestId: string;
  currentRequestTrace: string[];
  previousCompletedTrace: CompletedTraceDto | null;
  registeredFilters: RegisteredFilterDto[];
  analysis: string[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: PipelineReport }
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

function stringArrayField(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== 'string')) {
    throw new Error(`field "${key}" must be an array of strings`);
  }
  return value as string[];
}

function parseCompletedTrace(source: unknown): CompletedTraceDto {
  if (!isRecord(source)) throw new Error('completed trace must be an object');
  const requestId = source.requestId;
  if (requestId !== null && typeof requestId !== 'string') {
    throw new Error('field "requestId" must be a string or null');
  }
  return {
    requestId,
    method: stringField(source, 'method'),
    path: stringField(source, 'path'),
    status: numberField(source, 'status'),
    markers: stringArrayField(source, 'markers'),
  };
}

function parseRegisteredFilter(source: unknown): RegisteredFilterDto {
  if (!isRecord(source)) throw new Error('each registered filter must be an object');
  return {
    name: stringField(source, 'name'),
    order: numberField(source, 'order'),
    registration: stringField(source, 'registration'),
  };
}

function parsePipelineReport(body: unknown): PipelineReport {
  if (!isRecord(body)) throw new Error('pipeline report must be an object');
  const previous = body.previousCompletedTrace;
  const filters = body.registeredFilters;
  if (!Array.isArray(filters)) throw new Error('field "registeredFilters" must be an array');
  return {
    requestId: stringField(body, 'requestId'),
    currentRequestTrace: stringArrayField(body, 'currentRequestTrace'),
    previousCompletedTrace: previous === null ? null : parseCompletedTrace(previous),
    registeredFilters: filters.map(parseRegisteredFilter),
    analysis: stringArrayField(body, 'analysis'),
  };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CELL = 'border-b border-outline px-xs py-xs';
const MARKER = 'font-mono text-body-sm';

function MarkerList({ testId, markers }: { testId: string; markers: string[] }) {
  return (
    <ol data-testid={testId} className="flex flex-col gap-xxs">
      {markers.map((marker, index) => (
        <li key={`${index}-${marker}`} className="flex items-baseline gap-xs">
          <span className="font-mono text-caption text-on-surface-variant">{String(index + 1)}</span>
          <span className={MARKER}>{marker}</span>
        </li>
      ))}
    </ol>
  );
}

export default function PipelineDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const { body, status } = await guardedGetJson(config, '/demo/middleware/pipeline');
      return parseDto(body, status, parsePipelineReport);
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
        <p className={OVERLINE}>the pipeline, captured live</p>
        <button
          type="button"
          data-testid="middleware-pipeline-run-again"
          onClick={() => void load()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint"
        >
          run again
        </button>
      </div>

      {state.kind === 'loading' && (
        <p data-testid="middleware-pipeline-loading" className="text-body-sm text-on-surface-variant">
          sending a request through the chain…
        </p>
      )}
      {state.kind === 'error' && <ErrorPanel subject="the pipeline trace" failure={state.failure} />}
      {state.kind === 'ready' && (
        <div className="flex flex-col gap-sm" data-testid="middleware-pipeline-report">
          <div className="grid grid-cols-1 gap-sm sm:grid-cols-2">
            <div
              data-testid="middleware-pipeline-current"
              className="flex flex-col gap-xxs rounded-md border border-outline bg-surface px-sm py-sm"
            >
              <p className={OVERLINE}>this very request — up to the handler</p>
              <p className="text-body-sm text-on-surface-variant">
                request id: <span className="font-mono">{state.report.requestId}</span>
              </p>
              <MarkerList testId="middleware-pipeline-current-markers" markers={state.report.currentRequestTrace} />
            </div>
            <div
              data-testid="middleware-pipeline-previous"
              className="flex flex-col gap-xxs rounded-md border border-outline bg-surface px-sm py-sm"
            >
              <p className={OVERLINE}>previous completed request — full unwind</p>
              {state.report.previousCompletedTrace === null ? (
                <p className="text-body-sm text-on-surface-variant">
                  (nothing has completed yet — press run again and this fetch lands here)
                </p>
              ) : (
                <>
                  <p className="text-body-sm text-on-surface-variant">
                    <span className="font-mono">{state.report.previousCompletedTrace.method}</span>{' '}
                    <span className="font-mono">{state.report.previousCompletedTrace.path}</span> →{' '}
                    {String(state.report.previousCompletedTrace.status)}
                    {state.report.previousCompletedTrace.requestId !== null && (
                      <>
                        {' '}
                        · id <span className="font-mono">{state.report.previousCompletedTrace.requestId}</span>
                      </>
                    )}
                  </p>
                  <MarkerList
                    testId="middleware-pipeline-previous-markers"
                    markers={state.report.previousCompletedTrace.markers}
                  />
                </>
              )}
            </div>
          </div>

          <div className="flex flex-col gap-xs">
            <p className={OVERLINE}>the registered filter chain (introspected live)</p>
            <table data-testid="middleware-pipeline-filters" className="w-full border-collapse text-body-sm">
              <thead>
                <tr className="text-left text-on-surface-variant">
                  <th className={CELL}>order</th>
                  <th className={CELL}>bean name</th>
                  <th className={CELL}>registered as</th>
                </tr>
              </thead>
              <tbody className="text-on-surface">
                {state.report.registeredFilters.map((filter) => (
                  <tr key={filter.name}>
                    <td className={`${CELL} font-mono`}>{String(filter.order)}</td>
                    <td className={`${CELL} font-mono`}>{filter.name}</td>
                    <td className={CELL}>{filter.registration}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex flex-col gap-xxs" data-testid="middleware-pipeline-analysis">
            <p className={OVERLINE}>what the trace proves</p>
            <ul className="flex list-disc flex-col gap-xxs pl-md text-body-sm text-on-surface">
              {state.report.analysis.map((finding) => (
                <li key={finding}>{finding}</li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}
