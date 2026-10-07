import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `spring:app-info` — topic 04 (spring-boot-fundamentals),
 * the live half of the anatomy-of-a-controller section:
 * GET /api/demo/spring/app-info — the RUNNING server describing itself:
 * application name + active profiles, Java version, startup time and live
 * uptime, bean-definition and @Configuration counts, embedded server,
 * exposed actuator endpoints, and the package layout of this repo's own
 * beans. Every number is read from the real context at request time — press
 * refresh and watch uptimeSeconds climb; that is what "live introspection,
 * not a cached page" means.
 *
 * A teaching widget (like the java demos): it fetches directly, no dedicated
 * repository — but through the SAME guarded pipeline every data source uses.
 */

interface AppInfoReport {
  applicationName: string;
  activeProfiles: string[];
  build: { artifact: string; version: string; builtAt: string };
  javaVersion: string;
  javaVendor: string;
  startedAt: string;
  uptimeSeconds: number;
  embeddedServer: string;
  serverPort: number;
  actuatorEndpoints: string[];
  beanDefinitionCount: number;
  configurationClasses: number;
  guideBeans: number;
  packages: Array<{ name: string; beans: number }>;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: AppInfoReport }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the wire shape (built per request, ranges vary) ---- */

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

function parseAppInfo(body: unknown): AppInfoReport {
  if (!isRecord(body)) throw new Error('app-info report must be an object');
  const build = body.build;
  const packages = body.packages;
  if (!isRecord(build)) throw new Error('field "build" must be an object');
  if (!Array.isArray(packages)) throw new Error('field "packages" must be an array');
  for (const pkg of packages) {
    if (!isRecord(pkg) || typeof pkg.name !== 'string' || typeof pkg.beans !== 'number') {
      throw new Error('each package needs string name and number beans');
    }
  }
  return {
    applicationName: stringField(body, 'applicationName'),
    activeProfiles: stringListField(body, 'activeProfiles'),
    build: {
      artifact: stringField(build, 'artifact'),
      version: stringField(build, 'version'),
      builtAt: stringField(build, 'builtAt'),
    },
    javaVersion: stringField(body, 'javaVersion'),
    javaVendor: stringField(body, 'javaVendor'),
    startedAt: stringField(body, 'startedAt'),
    uptimeSeconds: numberField(body, 'uptimeSeconds'),
    embeddedServer: stringField(body, 'embeddedServer'),
    serverPort: numberField(body, 'serverPort'),
    actuatorEndpoints: stringListField(body, 'actuatorEndpoints'),
    beanDefinitionCount: numberField(body, 'beanDefinitionCount'),
    configurationClasses: numberField(body, 'configurationClasses'),
    guideBeans: numberField(body, 'guideBeans'),
    packages: packages as AppInfoReport['packages'],
  };
}

/* ---- presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function Fact({ label, value, testId }: { label: string; value: string; testId: string }) {
  return (
    <div className="flex flex-col gap-xxs">
      <span className={OVERLINE}>{label}</span>
      <span data-testid={testId} className="font-mono text-body-sm text-on-surface break-words">
        {value}
      </span>
    </div>
  );
}

function PackageTable({ packages }: { packages: AppInfoReport['packages'] }) {
  return (
    <table data-testid="spring-app-info-packages" className="w-full border-collapse text-body-sm">
      <thead>
        <tr className="text-left text-on-surface-variant">
          <th className="border-b border-outline px-xs py-xs font-semibold">guide/ package</th>
          <th className="border-b border-outline px-xs py-xs font-semibold">context beans</th>
        </tr>
      </thead>
      <tbody className="text-on-surface">
        {packages.map((pkg) => (
          <tr key={pkg.name}>
            <td className={`${CELL} font-mono`}>{pkg.name}</td>
            <td className={CELL}>{String(pkg.beans)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ReportPanel({ report }: { report: AppInfoReport }) {
  const profiles =
    report.activeProfiles.length > 0 ? report.activeProfiles.join(', ') : '(none — defaults only)';
  return (
    <div data-testid="spring-app-info-report" className="flex flex-col gap-md">
      <div className="grid grid-cols-1 gap-sm sm:grid-cols-2 lg:grid-cols-3">
        <Fact label="application" value={report.applicationName} testId="spring-app-info-name" />
        <Fact label="active profiles" value={profiles} testId="spring-app-info-profiles" />
        <Fact
          label="build"
          value={`${report.build.artifact} · ${report.build.version} · ${report.build.builtAt}`}
          testId="spring-app-info-build"
        />
        <Fact
          label="java"
          value={`${report.javaVersion} (${report.javaVendor})`}
          testId="spring-app-info-java"
        />
        <Fact label="started at" value={report.startedAt} testId="spring-app-info-started" />
        <Fact
          label="uptime"
          value={`${String(report.uptimeSeconds)}s — refresh, watch it climb`}
          testId="spring-app-info-uptime"
        />
        <Fact
          label="embedded server"
          value={`${report.embeddedServer} : ${String(report.serverPort)}`}
          testId="spring-app-info-server"
        />
        <Fact
          label="actuator web exposure"
          value={report.actuatorEndpoints.join(', ')}
          testId="spring-app-info-actuator"
        />
        <Fact
          label="context"
          value={`${String(report.beanDefinitionCount)} bean definitions · ${String(
            report.configurationClasses,
          )} @Configuration classes`}
          testId="spring-app-info-context"
        />
      </div>

      <div className="flex flex-col gap-xs">
        <p className="text-body-sm text-on-surface-variant">
          this repo&rsquo;s own beans by top-level package ({String(report.guideBeans)} total — the
          rest of the context is framework machinery):
        </p>
        <PackageTable packages={report.packages} />
      </div>
    </div>
  );
}

export default function AppInfoDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const { body, status } = await guardedGetJson(config, '/demo/spring/app-info');
      return parseDto(body, status, parseAppInfo);
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
        <p className={OVERLINE}>the running context, describing itself</p>
        <button
          type="button"
          data-testid="spring-app-info-refresh"
          onClick={() => void load()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint"
        >
          refresh
        </button>
      </div>

      {state.kind === 'loading' && (
        <p data-testid="spring-app-info-loading" className="text-body-sm text-on-surface-variant">
          asking the server…
        </p>
      )}
      {state.kind === 'error' && <ErrorPanel subject="the app-info report" failure={state.failure} />}
      {state.kind === 'ready' && <ReportPanel report={state.report} />}
    </div>
  );
}
