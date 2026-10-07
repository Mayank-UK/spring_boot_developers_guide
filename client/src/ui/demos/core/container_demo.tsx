import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `core:container` — topic 05 (spring-core), the live half
 * of the application-context section: the RUNNING ApplicationContext as data.
 * GET /api/demo/core/beans serves the overview — total bean definitions, this
 * repo's own beans grouped by top-level package, the use-case beans claimed
 * by the registered naming conventions, and a sample bean with its scope,
 * factory method, and dependencies. Typing any bean name fetches
 * GET /api/demo/core/beans/{name} — its detail, or the typed 404 envelope
 * when no such bean exists.
 *
 * A teaching widget (like the other demos): it fetches directly, no dedicated
 * repository — but through the SAME guarded pipeline every data source uses.
 */

interface BeanView {
  name: string;
  className: string;
  scope: string;
  factoryMethod: string | null;
  dependsOn: string[];
  proxied: boolean;
}

interface ContainerReport {
  totalBeans: number;
  byPackage: Array<{ name: string; beans: number }>;
  useCaseBeans: string[];
  useCaseConventions: string[];
  sampleBean: BeanView;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: ContainerReport }
  | { kind: 'error'; failure: Failure };

type DetailState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; bean: BeanView }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the wire shape (counts move per build) ---- */

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

function stringListField(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new Error(`field "${key}" must be an array of strings`);
  }
  return value as string[];
}

function parseBean(source: unknown): BeanView {
  if (!isRecord(source)) throw new Error('a bean view must be an object');
  const factoryMethod = source.factoryMethod;
  if (factoryMethod !== null && typeof factoryMethod !== 'string') {
    throw new Error('field "factoryMethod" must be a string or null');
  }
  if (typeof source.proxied !== 'boolean') {
    throw new Error('field "proxied" must be a boolean');
  }
  return {
    name: stringField(source, 'name'),
    className: stringField(source, 'className'),
    scope: stringField(source, 'scope'),
    factoryMethod,
    dependsOn: stringListField(source, 'dependsOn'),
    proxied: source.proxied,
  };
}

function parseContainer(body: unknown): ContainerReport {
  if (!isRecord(body)) throw new Error('container report must be an object');
  const byPackage = body.byPackage;
  if (!Array.isArray(byPackage)) throw new Error('field "byPackage" must be an array');
  for (const pkg of byPackage) {
    if (!isRecord(pkg) || typeof pkg.name !== 'string' || typeof pkg.beans !== 'number') {
      throw new Error('each package needs string name and number beans');
    }
  }
  if (!isRecord(body.sampleBean)) throw new Error('field "sampleBean" must be an object');
  return {
    totalBeans: numberField(body, 'totalBeans'),
    byPackage: byPackage as ContainerReport['byPackage'],
    useCaseBeans: stringListField(body, 'useCaseBeans'),
    useCaseConventions: stringListField(body, 'useCaseConventions'),
    sampleBean: parseBean(body.sampleBean),
  };
}

/* ---- presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const PILL =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';

function BeanCard({ bean, testId }: { bean: BeanView; testId: string }) {
  return (
    <div
      data-testid={testId}
      className="flex flex-col gap-xs rounded-lg border border-outline bg-surface-tint p-sm"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-xs">
        <span className="font-mono text-body-sm text-on-surface">{bean.name}</span>
        <span className="font-mono text-caption text-on-surface-variant">
          {bean.scope}
          {bean.proxied ? ' · proxied' : ''}
        </span>
      </div>
      <span className="font-mono text-caption text-on-surface-variant break-words">
        {bean.className}
      </span>
      <span className="text-caption text-on-surface-variant">
        factory method:{' '}
        <span className="font-mono">{bean.factoryMethod ?? '(none — classpath-scanned)'}</span>
      </span>
      <div className="flex flex-wrap items-center gap-xxs">
        <span className="text-caption text-on-surface-variant">depends on:</span>
        {bean.dependsOn.length === 0 ? (
          <span className="font-mono text-caption text-on-surface-variant">(nothing — a leaf)</span>
        ) : (
          bean.dependsOn.map((dependency) => (
            <span key={dependency} className="font-mono text-caption text-on-surface-variant">
              {dependency}
            </span>
          ))
        )}
      </div>
    </div>
  );
}

function ReportPanel({
  report,
  onInspect,
}: {
  report: ContainerReport;
  onInspect: (name: string) => void;
}) {
  return (
    <div data-testid="core-container-report" className="flex flex-col gap-md">
      <div className="flex flex-wrap gap-sm">
        <div className="flex flex-col gap-xxs">
          <span className={OVERLINE}>bean definitions</span>
          <span data-testid="core-container-total" className="font-mono text-body-sm text-on-surface">
            {String(report.totalBeans)} (nearly all of it Boot machinery)
          </span>
        </div>
        <div className="flex flex-col gap-xxs">
          <span className={OVERLINE}>use-case conventions</span>
          <span data-testid="core-container-conventions" className="text-body-sm text-on-surface">
            {report.useCaseConventions.join(' + ')}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-xs">
        <p className="text-body-sm text-on-surface-variant">
          this repo&rsquo;s own beans by top-level package (the handful the totals hide):
        </p>
        <table data-testid="core-container-packages" className="w-full border-collapse text-body-sm">
          <thead>
            <tr className="text-left text-on-surface-variant">
              <th className="border-b border-outline px-xs py-xs font-semibold">guide/ package</th>
              <th className="border-b border-outline px-xs py-xs font-semibold">context beans</th>
            </tr>
          </thead>
          <tbody className="text-on-surface">
            {report.byPackage.map((pkg) => (
              <tr key={pkg.name}>
                <td className={`${CELL} font-mono`}>{pkg.name}</td>
                <td className={CELL}>{String(pkg.beans)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex flex-col gap-xs">
        <p className="text-body-sm text-on-surface-variant">
          use-case beans claimed by those conventions ({String(report.useCaseBeans.length)} — the
          conventions themselves are domain beans, so they appear in their own list):
        </p>
        <div data-testid="core-container-usecases" className="flex flex-wrap gap-xxs">
          {report.useCaseBeans.map((name) => (
            <button
              key={name}
              type="button"
              data-testid={`core-container-usecase-${name}`}
              onClick={() => onInspect(name)}
              className={PILL}
              title="inspect this bean below"
            >
              {name}
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-xxs">
        <span className={OVERLINE}>sample bean — real wiring, deterministically picked</span>
        <BeanCard bean={report.sampleBean} testId="core-container-sample" />
      </div>
    </div>
  );
}

function DetailPanel({ state }: { state: DetailState }) {
  if (state.kind === 'idle') {
    return (
      <p data-testid="core-container-detail-idle" className="text-caption text-on-surface-variant">
        type a bean name — every name above is a valid query; unknown names answer the typed 404
        envelope
      </p>
    );
  }
  if (state.kind === 'loading') {
    return (
      <p data-testid="core-container-detail-loading" className="text-caption text-on-surface-variant">
        asking the container…
      </p>
    );
  }
  if (state.kind === 'error') {
    return (
      <div
        role="status"
        data-testid="core-container-detail-error"
        className="flex flex-col gap-xxs rounded-lg border border-outline p-sm"
      >
        <span className="text-body-sm text-on-surface">{state.failure.message}</span>
        <span className="font-mono text-caption text-on-surface-variant">
          {state.failure.code} · status {String(state.failure.status)}
        </span>
      </div>
    );
  }
  return <BeanCard bean={state.bean} testId="core-container-detail" />;
}

export default function ContainerDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const [detail, setDetail] = useState<DetailState>({ kind: 'idle' });
  const [beanName, setBeanName] = useState('');
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const { body, status } = await guardedGetJson(config, '/demo/core/beans');
      return parseDto(body, status, parseContainer);
    });
    setState(
      result.ok ? { kind: 'ready', report: result.value } : { kind: 'error', failure: result.failure },
    );
  }, [config]);

  const lookup = useCallback(
    async (name: string) => {
      const trimmed = name.trim();
      if (trimmed === '') return;
      setDetail({ kind: 'loading' });
      const result = await safeRepositoryCall(async () => {
        const { body, status } = await guardedGetJson(
          config,
          `/demo/core/beans/${encodeURIComponent(trimmed)}`,
        );
        return parseDto(body, status, parseBean);
      });
      setDetail(
        result.ok
          ? { kind: 'ready', bean: result.value }
          : { kind: 'error', failure: result.failure },
      );
    },
    [config],
  );

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="flex flex-col gap-sm">
      <div className="flex flex-wrap items-center justify-between gap-xs">
        <p className={OVERLINE}>the running container, describing itself</p>
        <button
          type="button"
          data-testid="core-container-refresh"
          onClick={() => void load()}
          className={PILL}
        >
          refresh
        </button>
      </div>

      {state.kind === 'loading' && (
        <p data-testid="core-container-loading" className="text-body-sm text-on-surface-variant">
          asking the server…
        </p>
      )}
      {state.kind === 'error' && <ErrorPanel subject="the container overview" failure={state.failure} />}
      {state.kind === 'ready' && (
        <ReportPanel
          report={state.report}
          onInspect={(name) => {
            setBeanName(name);
            void lookup(name);
          }}
        />
      )}

      <div className="flex flex-col gap-xs">
        <span className={OVERLINE}>inspect one bean</span>
        <form
          className="flex flex-wrap gap-xs"
          onSubmit={(event) => {
            event.preventDefault();
            void lookup(beanName);
          }}
        >
          <input
            data-testid="core-container-lookup-input"
            value={beanName}
            onChange={(event) => setBeanName(event.target.value)}
            placeholder="bean name, e.g. showAppInfo"
            className="min-w-56 flex-1 rounded-lg border border-outline bg-surface px-sm py-xxs font-mono text-body-sm text-on-surface placeholder:text-on-surface-variant focus:outline-none"
          />
          <button type="submit" data-testid="core-container-lookup-button" className={PILL}>
            inspect
          </button>
        </form>
        <DetailPanel state={detail} />
      </div>
    </div>
  );
}
