import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `env:inspector` — topic 36 (environments), the
 * @ConfigurationProperties section. The environment story as a live surface:
 *
 * - the active profiles, and what "default" actually means (no profile —
 *   not a name @Profile matches);
 * - the curated guide.env.* values from the typed AppConfig record, each
 *   classified safe (echoed) or secret — the secret's value NEVER travels,
 *   only the dev-default verdict, so the panel can teach redaction without
 *   ever rehearsing a leak;
 * - the runtime-switchable flags beside the config on purpose: same screen,
 *   different rails. "flip data-mode" PATCHes /api/demo/scenario and
 *   re-inspects — config is boot-shaped, flags are runtime-shaped, and the
 *   panel shows the difference in one click.
 */

interface PropertyLine {
  key: string;
  value: string;
  classification: string;
}

interface FlagLine {
  name: string;
  state: string;
  runtimeSwitchable: boolean;
  note: string;
}

interface EnvInspection {
  activeProfiles: string[];
  properties: PropertyLine[];
  demoSecretIsDevDefault: boolean;
  flags: FlagLine[];
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parsePropertyLine(source: unknown): PropertyLine {
  if (!isRecord(source)) throw new Error('a property line must be an object');
  return {
    key: String(source.key ?? ''),
    value: String(source.value ?? ''),
    classification: String(source.classification ?? ''),
  };
}

function parseFlagLine(source: unknown): FlagLine {
  if (!isRecord(source)) throw new Error('a flag line must be an object');
  return {
    name: String(source.name ?? ''),
    state: String(source.state ?? ''),
    runtimeSwitchable: source.runtimeSwitchable === true,
    note: String(source.note ?? ''),
  };
}

function parseInspection(source: unknown): EnvInspection {
  if (!isRecord(source)) throw new Error('the inspection must be an object');
  const { activeProfiles, properties, flags, reading } = source;
  if (
    !Array.isArray(activeProfiles) ||
    !Array.isArray(properties) ||
    !Array.isArray(flags) ||
    !Array.isArray(reading)
  ) {
    throw new Error('the inspection carries profiles, properties, flags and reading arrays');
  }
  return {
    activeProfiles: activeProfiles.map((name) => String(name)),
    properties: properties.map(parsePropertyLine),
    demoSecretIsDevDefault: source.demoSecretIsDevDefault === true,
    flags: flags.map(parseFlagLine),
    reading: reading.map((line) => String(line)),
  };
}

/** Fetch a path as JSON with method support, guarding transport + envelope like every demo. */
async function requestJson(config: AppConfig, path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'content-type': 'application/json' },
      ...init,
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
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const ACTION_BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';

function classificationClass(classification: string): string {
  return classification === 'secret' ? 'text-error' : 'text-on-surface';
}

export default function EnvInspectorDemo() {
  const config = container.appConfig;
  const [inspection, setInspection] = useState<EnvInspection | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const loadInspection = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseInspection(await requestJson(config, '/demo/env/inspector')),
    ).then((result) => {
      if (result.ok) {
        setInspection(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  useEffect(() => {
    loadInspection();
  }, [loadInspection]);

  /** Flip the runtime flag, then re-inspect: config is boot-shaped, flags are runtime-shaped. */
  const flipDataMode = () => {
    const current = inspection?.flags.find((flag) => flag.name === 'scenario.data-mode');
    const next = current?.state === 'live' ? 'fake' : 'live';
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      requestJson(config, '/demo/scenario', {
        method: 'PATCH',
        body: JSON.stringify({ dataMode: next }),
      }),
    ).then((result) => {
      if (result.ok) {
        loadInspection();
      } else {
        // the typed envelope (e.g. an unknown mode) stays visible
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>inspect:</span>
        <button type="button" data-testid="env-refresh" onClick={loadInspection} className={ACTION_BUTTON}>
          re-inspect
        </button>
        <button type="button" data-testid="env-flip-mode" onClick={flipDataMode} className={BUTTON}>
          flip data-mode
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="env-loading" className="font-mono text-caption text-on-surface-variant">
          reading the environment…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the env inspector" failure={panel.failure} />}

      <section
        data-testid="env-inspection"
        className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        <p className={OVERLINE}>
          the environment — profiles:
          {inspection === null ? ' …' : ` ${inspection.activeProfiles.join(', ')}`}
        </p>
        {inspection?.properties.map((property) => (
          <div
            key={property.key}
            data-testid="env-property"
            className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
          >
            <span className={classificationClass(property.classification)}>{property.key}</span>
            <span className="text-on-surface-variant">=</span>
            <span className={classificationClass(property.classification)}>{property.value}</span>
            <span className="text-on-surface-variant">({property.classification})</span>
          </div>
        ))}
        {inspection !== null && (
          <span data-testid="env-secret-verdict" className="border-t border-outline pt-xxs">
            {inspection.demoSecretIsDevDefault
              ? 'demo-api-key is the known DEV DEFAULT — demo material; production sets the env var'
              : 'demo-api-key is set to a real value — which this panel will never show'}
          </span>
        )}
        {inspection?.flags.map((flag) => (
          <div
            key={flag.name}
            data-testid="env-flag"
            className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
          >
            <span>{flag.name}</span>
            <span className="text-on-surface-variant">=</span>
            <span>{flag.state}</span>
            <span className="text-on-surface-variant">
              ({flag.runtimeSwitchable ? 'runtime-switchable' : 'boot-fixed'}) — {flag.note}
            </span>
          </div>
        ))}
        {inspection?.reading.map((line, index) => (
          <span
            key={line}
            data-testid={`env-reading-${String(index)}`}
            className="border-t border-outline pt-xxs text-on-surface-variant"
          >
            {line}
          </span>
        ))}
      </section>
    </div>
  );
}
