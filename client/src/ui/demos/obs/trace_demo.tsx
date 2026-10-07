import { useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `obs:trace` — topic 33 (observability), the tracing
 * section. One button, one proof: the server self-calls its hop endpoint
 * over real HTTP with a hand-built W3C traceparent, then answers the
 * evidence — the trace id, the traceparent it sent, the hop's facts (what
 * the cross-layer work did), the span waterfall with timings, and the log
 * lines whose MDC carried the SAME trace id. One id across every hop,
 * rendered from the response rather than asserted by prose.
 */

interface TraceSpan {
  name: string;
  traceId: string;
  spanId: string;
  parentId: string | null;
  startMs: number;
  durationMs: number;
}

interface LoggedEvent {
  timestampMs: number;
  level: string;
  logger: string;
  message: string;
  requestId: string | null;
  traceId: string | null;
}

interface HopFacts {
  origin: string | null;
  postId: string | null;
  jobId: string | null;
  outcome: string | null;
  steps: string[];
}

interface TraceEvidence {
  traceId: string;
  traceparentSent: string;
  hop: {
    traceparentSeen: string | null;
    traceIdFromMdc: string | null;
    facts: HopFacts;
  };
  spans: TraceSpan[];
  logs: LoggedEvent[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function optionalString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === undefined || value === null) return null;
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string when present`);
  return value;
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`field "${key}" must be a number`);
  }
  return value;
}

function parseSteps(source: Record<string, unknown>): string[] {
  const steps = source.steps;
  if (!Array.isArray(steps)) throw new Error('field "steps" must be an array');
  return steps.map((step) => {
    if (typeof step !== 'string') throw new Error('each step must be a string');
    return step;
  });
}

function parseFacts(source: unknown): HopFacts {
  if (!isRecord(source)) throw new Error('hop facts must be an object');
  return {
    origin: optionalString(source, 'origin'),
    postId: optionalString(source, 'postId'),
    jobId: optionalString(source, 'jobId'),
    outcome: optionalString(source, 'outcome'),
    steps: parseSteps(source),
  };
}

function parseTraceEvidence(source: unknown): TraceEvidence {
  if (!isRecord(source)) throw new Error('trace evidence must be an object');
  const spans = source.spans;
  const logs = source.logs;
  const hop = source.hop;
  if (!Array.isArray(spans)) throw new Error('field "spans" must be an array');
  if (!Array.isArray(logs)) throw new Error('field "logs" must be an array');
  if (!isRecord(hop)) throw new Error('field "hop" must be an object');
  return {
    traceId: stringField(source, 'traceId'),
    traceparentSent: stringField(source, 'traceparentSent'),
    hop: {
      traceparentSeen: optionalString(hop, 'traceparentSeen'),
      traceIdFromMdc: optionalString(hop, 'traceIdFromMdc'),
      facts: parseFacts(hop.facts),
    },
    spans: spans.map((span) => {
      if (!isRecord(span)) throw new Error('each span must be an object');
      return {
        name: stringField(span, 'name'),
        traceId: stringField(span, 'traceId'),
        spanId: stringField(span, 'spanId'),
        parentId: optionalString(span, 'parentId'),
        startMs: numberField(span, 'startMs'),
        durationMs: numberField(span, 'durationMs'),
      };
    }),
    logs: logs.map((log) => {
      if (!isRecord(log)) throw new Error('each log event must be an object');
      return {
        timestampMs: numberField(log, 'timestampMs'),
        level: String(log.level ?? ''),
        logger: String(log.logger ?? ''),
        message: String(log.message ?? ''),
        requestId: optionalString(log, 'requestId'),
        traceId: optionalString(log, 'traceId'),
      };
    }),
  };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CHIP = 'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';
const CELL = 'border-b border-outline px-xs py-xs text-left';

function clockOf(timestampMs: number): string {
  return new Date(timestampMs).toISOString().slice(11, 23);
}

function short(id: string | null, head: number, tail: number): string {
  if (id === null) return '—';
  return id.length <= head + tail ? id : `${id.slice(0, head)}…${id.slice(-tail)}`;
}

export default function ObsTraceDemo() {
  const config = container.appConfig;
  const [evidence, setEvidence] = useState<TraceEvidence | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const runFlow = () => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const response = await guardedGetJson(config, '/demo/obs/trace');
      return parseTraceEvidence(response.body);
    }).then((result) => {
      if (result.ok) {
        setEvidence(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  };

  const oneIdProven =
    evidence !== null &&
    evidence.spans.every((span) => span.traceId === evidence.traceId) &&
    evidence.logs.every((log) => log.traceId === evidence.traceId) &&
    evidence.hop.traceIdFromMdc === evidence.traceId &&
    evidence.spans.length > 0;

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-sm">
        <button
          type="button"
          data-testid="obs-trace-run"
          onClick={runFlow}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          run the traced flow (real HTTP self-call)
        </button>
        {oneIdProven && (
          <span data-testid="obs-trace-proof" className={CHIP}>
            one id {short(evidence?.traceId ?? null, 8, 6)} across {String(evidence?.spans.length ?? 0)} spans and{' '}
            {String(evidence?.logs.length ?? 0)} log lines
          </span>
        )}
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="obs-trace-loading" className="font-mono text-caption text-on-surface-variant">
          crossing the hop…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the traced flow" failure={panel.failure} />}

      {evidence !== null && (
        <>
          <section
            data-testid="obs-trace-ids"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <p className={OVERLINE}>the id and the header that carried it</p>
            <span className="break-all">traceId: {evidence.traceId}</span>
            <span className="break-all">traceparentSent: {evidence.traceparentSent}</span>
            <span className="break-all">
              hop received: {evidence.hop.traceparentSeen ?? '(none)'}
            </span>
            <span className="break-all text-on-surface-variant">
              hop's MDC traceId: {evidence.hop.traceIdFromMdc ?? '(none)'} — same id, other thread
            </span>
          </section>

          <section
            data-testid="obs-trace-hop"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <p className={OVERLINE}>what the hop actually did (cross-layer, real events)</p>
            {evidence.hop.facts.steps.map((step) => (
              <span key={step} className="break-all">
                · {step}
              </span>
            ))}
          </section>

          <section data-testid="obs-trace-spans" className="overflow-x-auto">
            <p className={OVERLINE}>the span waterfall</p>
            <table className="w-full border-collapse font-mono text-caption">
              <thead>
                <tr className="text-on-surface-variant">
                  <th className={CELL}>span</th>
                  <th className={CELL}>spanId</th>
                  <th className={CELL}>parent</th>
                  <th className={CELL}>start</th>
                  <th className={CELL}>durationMs</th>
                </tr>
              </thead>
              <tbody>
                {evidence.spans.map((span) => (
                  <tr key={span.spanId} data-testid="obs-trace-span-row">
                    <td className={CELL}>{span.name}</td>
                    <td className={CELL}>{short(span.spanId, 8, 6)}</td>
                    <td className={CELL}>{span.parentId === null ? '(root)' : short(span.parentId, 8, 6)}</td>
                    <td className={CELL}>{clockOf(span.startMs)}</td>
                    <td className={CELL}>{span.durationMs.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section
            data-testid="obs-trace-logs"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <p className={OVERLINE}>log lines carrying the same id (MDC-joined)</p>
            {evidence.logs.map((log, index) => (
              <span key={`${String(log.timestampMs)}-${String(index)}`} className="break-all">
                {clockOf(log.timestampMs)} {log.level} [{log.logger}] {log.message}
                {log.requestId !== null && (
                  <span className="text-on-surface-variant"> requestId={log.requestId}</span>
                )}
              </span>
            ))}
          </section>
        </>
      )}
    </div>
  );
}
