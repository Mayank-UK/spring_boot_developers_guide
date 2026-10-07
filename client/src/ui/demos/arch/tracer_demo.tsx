import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `arch:tracer` — topic 32 (architecture), the live half
 * of the request-flow-tracer section. Two panels, one rule:
 * - the TRACER fires a real internal request server-side (target = content
 *   or posts) and renders the layered story: the middleware ring's markers,
 *   the aspect-recorded use-case invocation, and measured timings — both
 *   requests' correlation ids visible, the inner one nested inside the
 *   outer one's frame.
 * - the MAP renders the computed layer map: class counts per layer, the
 *   port/adapter inventory with the @Bean bindings that wire them, and the
 *   exemption ledger the DependencyRuleTest enforces — the rule, and every
 *   documented place it bends, on one screen.
 *
 * A teaching widget (like the other demos): it fetches through the SAME
 * guarded pipeline every data source uses, no dedicated repository.
 */

interface StoryStep {
  stage: string;
  label: string;
  evidence: string;
}

interface ObservedInvocation {
  method: string;
  argsSummary: string;
  outcome: string;
  durationNanos: number;
  requestId: string | null;
}

interface TraceReport {
  target: string;
  path: string;
  controller: string;
  useCase: string;
  adapter: string;
  note: string;
  outer: { requestId: string; markers: string[] };
  inner: { requestId: string; status: number; matched: boolean; markers: string[] } | null;
  invocations: ObservedInvocation[];
  steps: StoryStep[];
  timings: {
    roundTripNanos: number;
    useCaseNanos: number;
    overheadNanos: number;
    status: number;
    bodyBytes: number;
  };
}

interface LayerCount {
  layer: string;
  packageRoot: string;
  classes: number;
  areas: { area: string; classes: number }[];
}

interface PortBinding {
  port: string;
  adapters: string[];
  bindings: string[];
}

interface MapReport {
  layers: LayerCount[];
  ports: PortBinding[];
  teachingExhibits: { file: string; importPrefix: string; reason: string }[];
  domainExemptions: { file: string; importPrefix: string; reason: string }[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; trace: TraceReport; map: MapReport }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the wire shape (nulls are dropped server-side) ---- */

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
  if (value === undefined) return null;
  if (typeof value === 'string') return value;
  if (value === null) return null;
  throw new Error(`field "${key}" must be a string when present`);
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`field "${key}" must be a number`);
  }
  return value;
}

function stringArray(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value)) throw new Error(`field "${key}" must be an array`);
  for (const item of value) {
    if (typeof item !== 'string') throw new Error(`each entry of "${key}" must be a string`);
  }
  return value;
}

function parseStep(source: unknown): StoryStep {
  if (!isRecord(source)) throw new Error('a story step must be an object');
  return {
    stage: stringField(source, 'stage'),
    label: stringField(source, 'label'),
    evidence: stringField(source, 'evidence'),
  };
}

function parseInvocation(source: unknown): ObservedInvocation {
  if (!isRecord(source)) throw new Error('an invocation must be an object');
  return {
    method: stringField(source, 'method'),
    argsSummary: stringField(source, 'argsSummary'),
    outcome: stringField(source, 'outcome'),
    durationNanos: numberField(source, 'durationNanos'),
    requestId: optionalString(source, 'requestId'),
  };
}

function parseTraceBody(source: unknown): TraceReport {
  if (!isRecord(source)) throw new Error('trace report must be an object');
  const outer = source.outer;
  const inner = source.inner ?? null;
  const invocations = source.invocations;
  const steps = source.steps;
  const timings = source.timings;
  if (!isRecord(outer)) throw new Error('field "outer" must be an object');
  if (!Array.isArray(invocations)) throw new Error('field "invocations" must be an array');
  if (!Array.isArray(steps)) throw new Error('field "steps" must be an array');
  if (!isRecord(timings)) throw new Error('field "timings" must be an object');
  return {
    target: stringField(source, 'target'),
    path: stringField(source, 'path'),
    controller: stringField(source, 'controller'),
    useCase: stringField(source, 'useCase'),
    adapter: stringField(source, 'adapter'),
    note: stringField(source, 'note'),
    outer: { requestId: stringField(outer, 'requestId'), markers: stringArray(outer, 'markers') },
    inner: isRecord(inner)
      ? {
          requestId: stringField(inner, 'requestId'),
          status: numberField(inner, 'status'),
          matched: inner.matched === true,
          markers: stringArray(inner, 'markers'),
        }
      : null,
    invocations: invocations.map(parseInvocation),
    steps: steps.map(parseStep),
    timings: {
      roundTripNanos: numberField(timings, 'roundTripNanos'),
      useCaseNanos: numberField(timings, 'useCaseNanos'),
      overheadNanos: numberField(timings, 'overheadNanos'),
      status: numberField(timings, 'status'),
      bodyBytes: numberField(timings, 'bodyBytes'),
    },
  };
}

function parsePort(source: unknown): PortBinding {
  if (!isRecord(source)) throw new Error('a port binding must be an object');
  return {
    port: stringField(source, 'port'),
    adapters: stringArray(source, 'adapters'),
    bindings: stringArray(source, 'bindings'),
  };
}

function parseLayer(source: unknown): LayerCount {
  if (!isRecord(source)) throw new Error('a layer count must be an object');
  const areas = source.areas;
  if (!Array.isArray(areas)) throw new Error('field "areas" must be an array');
  return {
    layer: stringField(source, 'layer'),
    packageRoot: stringField(source, 'packageRoot'),
    classes: numberField(source, 'classes'),
    areas: areas.map((area) => {
      if (!isRecord(area)) throw new Error('an area count must be an object');
      return { area: stringField(area, 'area'), classes: numberField(area, 'classes') };
    }),
  };
}

function parseExemptionRow(source: unknown): { file: string; importPrefix: string; reason: string } {
  if (!isRecord(source)) throw new Error('an exemption must be an object');
  return {
    file: stringField(source, 'file'),
    importPrefix: stringField(source, 'importPrefix'),
    reason: stringField(source, 'reason'),
  };
}

function parseMapBody(source: unknown): MapReport {
  if (!isRecord(source)) throw new Error('map report must be an object');
  const layers = source.layers;
  const ports = source.ports;
  const teachingExhibits = source.teachingExhibits;
  const domainExemptions = source.domainExemptions;
  if (!Array.isArray(layers)) throw new Error('field "layers" must be an array');
  if (!Array.isArray(ports)) throw new Error('field "ports" must be an array');
  if (!Array.isArray(teachingExhibits)) throw new Error('field "teachingExhibits" must be an array');
  if (!Array.isArray(domainExemptions)) throw new Error('field "domainExemptions" must be an array');
  return {
    layers: layers.map(parseLayer),
    ports: ports.map(parsePort),
    teachingExhibits: teachingExhibits.map(parseExemptionRow),
    domainExemptions: domainExemptions.map(parseExemptionRow),
  };
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const PILL =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';
const CELL = 'border-b border-outline px-xs py-xs text-left';
const STAGE_COLORS: Record<string, string> = {
  filter: 'bg-surface-tint',
  interceptor: 'bg-surface-tint',
  controller: 'bg-primary/15',
  'use-case': 'bg-primary/15',
  adapter: 'bg-primary/15',
  result: 'bg-primary/15',
  note: 'bg-outline/20',
  done: 'bg-surface-tint',
};

function shortName(fullyQualified: string): string {
  // paths keep their extension (split on '/'), FQCNs take the class name (split on '.')
  const segments = fullyQualified.split(fullyQualified.includes('/') ? '/' : '.');
  return segments[segments.length - 1] ?? fullyQualified;
}

function micros(nanos: number): string {
  if (nanos < 0) return '—';
  return `${(nanos / 1_000).toFixed(1)} µs`;
}

export default function ArchTracerDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const [target, setTarget] = useState<'content' | 'posts'>('content');
  const config = container.appConfig;

  const load = useCallback(
    async (chosen: 'content' | 'posts') => {
      setState({ kind: 'loading' });
      const result = await safeRepositoryCall(async () => {
        const trace = await guardedGetJson(config, `/demo/arch/trace?target=${chosen}`);
        const map = await guardedGetJson(config, '/demo/arch/map');
        return {
          trace: parseDto(trace.body, trace.status, parseTraceBody),
          map: parseDto(map.body, map.status, parseMapBody),
        };
      });
      setState(
        result.ok
          ? { kind: 'ready', trace: result.value.trace, map: result.value.map }
          : { kind: 'error', failure: result.failure },
      );
    },
    [config],
  );

  useEffect(() => {
    void load(target);
  }, [load, target]);

  if (state.kind === 'loading') {
    return (
      <p data-testid="arch-loading" className="text-body-sm text-on-surface-variant">
        firing one real internal request…
      </p>
    );
  }
  if (state.kind === 'error') {
    return <ErrorPanel subject="the request-flow tracer" failure={state.failure} />;
  }

  const { trace, map } = state;

  return (
    <div className="flex flex-col gap-md">
      {/* ---- the tracer panel ---- */}
      <div className="flex flex-wrap items-center justify-between gap-xs">
        <p className={OVERLINE}>one real request, every layer, measured — not drawn</p>
        <div className="flex flex-wrap gap-xxs">
          <button
            type="button"
            data-testid="arch-target-content"
            onClick={() => setTarget('content')}
            className={PILL}
          >
            {target === 'content' ? '● content' : 'content'}
          </button>
          <button
            type="button"
            data-testid="arch-target-posts"
            onClick={() => setTarget('posts')}
            className={PILL}
          >
            {target === 'posts' ? '● posts' : 'posts'}
          </button>
          <button
            type="button"
            data-testid="arch-refresh"
            onClick={() => void load(target)}
            className={PILL}
          >
            re-run trace
          </button>
        </div>
      </div>

      <p data-testid="arch-target-note" className="text-caption text-on-surface-variant">
        GET {trace.path} — {trace.note}. Driver: {trace.controller} → {trace.useCase} →{' '}
        {shortName(trace.adapter)}.
      </p>

      <div data-testid="arch-story" className="flex flex-col gap-xxs">
        {trace.steps.map((step, index) => (
          <div
            key={`${step.stage}-${index}`}
            data-testid={`arch-step-${index}`}
            className={`flex flex-wrap items-baseline gap-xs rounded-sm px-xs py-xxs ${
              STAGE_COLORS[step.stage] ?? ''
            }`}
          >
            <span className="w-20 shrink-0 font-mono text-caption uppercase text-on-surface-variant">
              {step.stage}
            </span>
            <span className="text-body-sm text-on-surface">{step.label}</span>
            <span className="font-mono text-caption text-on-surface-variant">[{step.evidence}]</span>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap gap-md text-caption text-on-surface-variant">
        <span data-testid="arch-outer-id">
          outer request-id <span className="font-mono text-on-surface">{trace.outer.requestId}</span>
        </span>
        {trace.inner ? (
          <span data-testid="arch-inner-id">
            inner request-id <span className="font-mono text-on-surface">{trace.inner.requestId}</span>{' '}
            (status {trace.inner.status}
            {trace.inner.matched ? ', correlation matched' : ', unmatched'})
          </span>
        ) : (
          <span data-testid="arch-inner-id">inner trace not captured this run</span>
        )}
        <span data-testid="arch-timings">
          round trip <span className="font-mono text-on-surface">{micros(trace.timings.roundTripNanos)}</span>{' '}
          · use case{' '}
          <span className="font-mono text-on-surface">{micros(trace.timings.useCaseNanos)}</span> · body{' '}
          {trace.timings.bodyBytes} B
        </span>
      </div>

      {trace.invocations.length > 0 && (
        <div className="flex flex-col gap-xxs overflow-x-auto">
          <p className={OVERLINE}>aspect records (topic 06's InvocationLog)</p>
          <table data-testid="arch-invocations" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                <th className={CELL}>method</th>
                <th className={CELL}>args</th>
                <th className={CELL}>outcome</th>
                <th className={CELL}>duration</th>
                <th className={CELL}>request-id</th>
              </tr>
            </thead>
            <tbody>
              {trace.invocations.map((invocation, index) => (
                <tr key={index} data-testid={`arch-invocation-${index}`}>
                  <td className={`${CELL} font-mono`}>{shortName(invocation.method)}</td>
                  <td className={`${CELL} font-mono text-caption`}>{invocation.argsSummary}</td>
                  <td className={CELL}>{invocation.outcome}</td>
                  <td className={`${CELL} font-mono`}>{micros(invocation.durationNanos)}</td>
                  <td className={`${CELL} font-mono text-caption`}>
                    {invocation.requestId ?? '— (off the HTTP path)'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* ---- the map panel ---- */}
      <p className={OVERLINE}>the layer map — computed from the running app, never hand-maintained</p>

      <div className="flex flex-col gap-xxs overflow-x-auto">
        <table data-testid="arch-layers" className="w-full border-collapse text-body-sm">
          <thead>
            <tr className="text-left text-on-surface-variant">
              <th className={CELL}>layer</th>
              <th className={CELL}>package</th>
              <th className={CELL}>classes</th>
              <th className={CELL}>areas (biggest first)</th>
            </tr>
          </thead>
          <tbody>
            {map.layers.map((layer) => (
              <tr key={layer.layer} data-testid={`arch-layer-${layer.layer}`}>
                <td className={`${CELL} font-mono`}>{layer.layer}</td>
                <td className={`${CELL} font-mono text-caption`}>{layer.packageRoot}</td>
                <td className={`${CELL} font-mono`}>{layer.classes}</td>
                <td className={`${CELL} font-mono text-caption`}>
                  {[...layer.areas]
                    .sort((a, b) => b.classes - a.classes)
                    .slice(0, 6)
                    .map((area) => `${area.area}:${area.classes}`)
                    .join(' · ')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex flex-col gap-xxs overflow-x-auto">
        <p className="text-caption text-on-surface-variant">
          ports (domain interfaces) → adapters (data implementations) → composition-root @Bean bindings
        </p>
        <table data-testid="arch-ports" className="w-full border-collapse text-body-sm">
          <thead>
            <tr className="text-left text-on-surface-variant">
              <th className={CELL}>port</th>
              <th className={CELL}>adapters</th>
              <th className={CELL}>wired by</th>
            </tr>
          </thead>
          <tbody>
            {map.ports.map((binding) => (
              <tr key={binding.port} data-testid={`arch-port-${shortName(binding.port)}`}>
                <td className={`${CELL} font-mono`}>{shortName(binding.port)}</td>
                <td className={`${CELL} font-mono text-caption`}>
                  {binding.adapters.map(shortName).join(', ')}
                </td>
                <td className={`${CELL} font-mono text-caption`}>
                  {binding.bindings.length > 0 ? binding.bindings.join(', ') : '(not @Bean-bound)'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex flex-col gap-xs">
        <p className="text-caption text-on-surface-variant">
          the exemption ledger — {map.teachingExhibits.length} presentation teaching exhibits and{' '}
          {map.domainExemptions.length} domain outside-import entries, each with its reason; the
          DependencyRuleTest fails if reality and this list ever diverge
        </p>
        <ul data-testid="arch-exemptions" className="flex flex-col gap-xxs">
          {map.teachingExhibits.map((exhibit) => (
            <li
              key={exhibit.file}
              data-testid="arch-exhibit"
              className="rounded-sm bg-surface-tint px-xs py-xxs text-caption"
            >
              <span className="font-mono text-on-surface">{shortName(exhibit.file)}</span> imports{' '}
              <span className="font-mono">{exhibit.importPrefix}*</span> — {exhibit.reason}
            </li>
          ))}
          {map.domainExemptions.slice(0, 6).map((entry) => (
            <li key={entry.file + entry.importPrefix} className="px-xs py-xxs text-caption">
              <span className="font-mono text-on-surface">{shortName(entry.file)}</span> ←{' '}
              <span className="font-mono">{entry.importPrefix}*</span> — {entry.reason}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
