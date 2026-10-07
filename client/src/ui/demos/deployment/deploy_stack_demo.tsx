import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `deploy:stack` — topic 37 (deployment), the compose
 * section. The deployment story as a live surface:
 *
 * - the prod container the layered-jar image runs (image, base, launcher,
 *   port) — what actually ships;
 * - the six-service dev compose stack as a typed mirror of
 *   .docker/docker-compose.yml: images, published ports, each service's role;
 * - the twelve-factor env rails with dev and prod values side by side — the
 *   one-build-many-environments proof: parity rails identical, deliberate
 *   differences named;
 * - the actuator probe pair the k8s Deployment polls (liveness vs readiness)
 *   and the graceful-shutdown posture read from the real property sources.
 */

interface ContainerLine {
  image: string;
  base: string;
  entrypoint: string;
  port: number;
}

interface ServiceLine {
  name: string;
  image: string;
  ports: string[];
  role: string;
}

interface EnvRail {
  name: string;
  devValue: string;
  prodValue: string;
  note: string;
}

interface ProbeLine {
  livenessPath: string;
  readinessPath: string;
  port: number;
}

interface ShutdownLine {
  mode: string;
  timeout: string;
  contract: string;
}

interface DeploymentInspection {
  container: ContainerLine;
  composeServices: ServiceLine[];
  envRails: EnvRail[];
  probes: ProbeLine;
  shutdown: ShutdownLine;
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseContainer(source: unknown): ContainerLine {
  if (!isRecord(source)) throw new Error('the container line must be an object');
  return {
    image: String(source.image ?? ''),
    base: String(source.base ?? ''),
    entrypoint: String(source.entrypoint ?? ''),
    port: Number(source.port ?? 0),
  };
}

function parseServiceLine(source: unknown): ServiceLine {
  if (!isRecord(source)) throw new Error('a service line must be an object');
  const ports = source.ports;
  return {
    name: String(source.name ?? ''),
    image: String(source.image ?? ''),
    ports: Array.isArray(ports) ? ports.map((port) => String(port)) : [],
    role: String(source.role ?? ''),
  };
}

function parseEnvRail(source: unknown): EnvRail {
  if (!isRecord(source)) throw new Error('an env rail must be an object');
  return {
    name: String(source.name ?? ''),
    devValue: String(source.devValue ?? ''),
    prodValue: String(source.prodValue ?? ''),
    note: String(source.note ?? ''),
  };
}

function parseStack(source: unknown): DeploymentInspection {
  if (!isRecord(source)) throw new Error('the stack must be an object');
  const { composeServices, envRails, reading } = source;
  if (
    !Array.isArray(composeServices) ||
    !Array.isArray(envRails) ||
    !Array.isArray(reading)
  ) {
    throw new Error('the stack carries services, env rails and reading arrays');
  }
  return {
    container: parseContainer(source.container),
    composeServices: composeServices.map(parseServiceLine),
    envRails: envRails.map(parseEnvRail),
    probes: {
      livenessPath: String(isRecord(source.probes) ? source.probes.livenessPath ?? '' : ''),
      readinessPath: String(isRecord(source.probes) ? source.probes.readinessPath ?? '' : ''),
      port: Number(isRecord(source.probes) ? source.probes.port ?? 0 : 0),
    },
    shutdown: {
      mode: String(isRecord(source.shutdown) ? source.shutdown.mode ?? '' : ''),
      timeout: String(isRecord(source.shutdown) ? source.shutdown.timeout ?? '' : ''),
      contract: String(isRecord(source.shutdown) ? source.shutdown.contract ?? '' : ''),
    },
    reading: reading.map((line) => String(line)),
  };
}

/** Fetch a path as JSON, guarding transport + envelope like every demo. */
async function requestJson(config: AppConfig, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'content-type': 'application/json' },
    });
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
    const code = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.code ?? 'unknown_error')
      : 'unknown_error';
    const message = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.message ?? `Request failed with status ${String(response.status)}.`)
      : `Request failed with status ${String(response.status)}.`;
    throw new ServerException(message, { code, status: response.status });
  }
  return parsed;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const PARITY_IDENTICAL = 'text-on-surface';
const PARITY_DIFFERS = 'text-secondary';

function railClass(rail: EnvRail): string {
  return rail.devValue === rail.prodValue ? PARITY_IDENTICAL : PARITY_DIFFERS;
}

export default function DeployStackDemo() {
  const config = container.appConfig;
  const [stack, setStack] = useState<DeploymentInspection | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  /** The whole deployment story in one GET — deterministic process state. */
  const loadStack = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseStack(await requestJson(config, '/demo/deploy/stack')),
    ).then((result) => {
      if (result.ok) {
        setStack(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  useEffect(() => {
    loadStack();
  }, [loadStack]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>inspect the stack:</span>
        <button
          type="button"
          data-testid="deploy-refresh"
          onClick={loadStack}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint"
        >
          re-read the stack
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="deploy-loading" className="font-mono text-caption text-on-surface-variant">
          reading the deployment story…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the deployment stack" failure={panel.failure} />}

      {stack !== null && (
        <section
          data-testid="deploy-container"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>what ships</p>
          <div data-testid="deploy-image" className="flex flex-wrap items-baseline gap-x-xs">
            <span>{stack.container.image}</span>
            <span className="text-on-surface-variant">on {stack.container.base}</span>
          </div>
          <span className="text-on-surface-variant">
            entrypoint {stack.container.entrypoint} — port {String(stack.container.port)}
          </span>
        </section>
      )}

      {stack !== null && (
        <section
          data-testid="deploy-services"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>the dev compose stack — six services, one file</p>
          {stack.composeServices.map((service) => (
            <div
              key={service.name}
              data-testid="deploy-service"
              className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
            >
              <span>{service.name}</span>
              <span className="text-on-surface-variant">{service.image}</span>
              {service.ports.length > 0 && (
                <span className="text-on-surface-variant">[{service.ports.join(', ')}]</span>
              )}
              <span className="text-on-surface-variant">— {service.role}</span>
            </div>
          ))}
        </section>
      )}

      {stack !== null && (
        <section
          data-testid="deploy-rails"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>env rails — dev vs prod (identical values are parity)</p>
          {stack.envRails.map((rail) => (
            <div
              key={rail.name}
              data-testid="deploy-rail"
              className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
            >
              <span className={railClass(rail)}>{rail.name}</span>
              <span className="text-on-surface-variant">dev:</span>
              <span className={railClass(rail)}>{rail.devValue}</span>
              <span className="text-on-surface-variant">prod:</span>
              <span className={railClass(rail)}>{rail.prodValue}</span>
              <span className="text-on-surface-variant">— {rail.note}</span>
            </div>
          ))}
        </section>
      )}

      {stack !== null && (
        <section
          data-testid="deploy-ops"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <p className={OVERLINE}>the ops posture</p>
          <div data-testid="deploy-probes">
            <span>liveness {stack.probes.livenessPath}</span>
            <span className="text-on-surface-variant"> · readiness {stack.probes.readinessPath}</span>
            <span className="text-on-surface-variant"> · port {String(stack.probes.port)}</span>
          </div>
          <div data-testid="deploy-shutdown" className="border-t border-outline pt-xxs">
            <span>shutdown: {stack.shutdown.mode}</span>
            <span className="text-on-surface-variant"> (drain budget {stack.shutdown.timeout})</span>
            <span className="text-on-surface-variant"> — {stack.shutdown.contract}</span>
          </div>
          {stack.reading.map((line, index) => (
            <span
              key={line}
              data-testid={`deploy-reading-${String(index)}`}
              className="border-t border-outline pt-xxs text-on-surface-variant"
            >
              {line}
            </span>
          ))}
        </section>
      )}
    </div>
  );
}
