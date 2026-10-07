import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `api:docs-link` — topic 12 (api-documentation), the
 * serving-swagger-ui section. Two halves of one story:
 *
 * - The LINK: the real Swagger UI the server mounts at /docs (and the raw
 *   contract at /v3/api-docs) — honest links out to the living document.
 * - The COVERAGE: the server's own report, fetched live from
 *   GET /api/demo/api-docs/coverage — path/operation/schema counts, sample
 *   paths grouped by surface, and the spec version. The server computes that
 *   report by fetching its OWN /v3/api-docs and counting, so these numbers
 *   are arithmetic over the real contract, never a hand-maintained list.
 */

interface CoverageReport {
  paths: number;
  operations: number;
  schemas: number;
  samplePaths: Record<string, string[]>;
  specVersion: string;
  swaggerUiUrl: string;
}

type CoveragePanel =
  | { kind: 'loading' }
  | { kind: 'ready'; report: CoverageReport }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded call (same pipeline as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchJson(config: AppConfig, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`);
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
    if (isRecord(parsed) && isRecord(parsed.error)) {
      throw new ServerException(
        typeof parsed.error.message === 'string'
          ? parsed.error.message
          : `Request failed with status ${String(response.status)}.`,
        { status: response.status },
      );
    }
    throw new ServerException(`Request failed with status ${String(response.status)}.`, {
      status: response.status,
    });
  }
  return parsed;
}

function parseCoverage(body: unknown): CoverageReport {
  if (!isRecord(body)) throw new Error('coverage report must be an object');
  const numbers: Array<keyof Pick<CoverageReport, 'paths' | 'operations' | 'schemas'>> = [
    'paths',
    'operations',
    'schemas',
  ];
  for (const key of numbers) {
    if (typeof body[key] !== 'number') throw new Error(`coverage needs a numeric ${key}`);
  }
  if (typeof body.specVersion !== 'string' || typeof body.swaggerUiUrl !== 'string') {
    throw new Error('coverage needs specVersion and swaggerUiUrl');
  }
  if (!isRecord(body.samplePaths)) throw new Error('coverage needs grouped samplePaths');
  const samplePaths: Record<string, string[]> = {};
  for (const [group, paths] of Object.entries(body.samplePaths)) {
    if (!Array.isArray(paths)) throw new Error(`samplePaths group ${group} must be a list`);
    samplePaths[group] = paths.map((path) => String(path));
  }
  return {
    paths: body.paths as number,
    operations: body.operations as number,
    schemas: body.schemas as number,
    samplePaths,
    specVersion: body.specVersion,
    swaggerUiUrl: body.swaggerUiUrl,
  };
}

/* ---- where the docs live, resolved per browser ---- */

/**
 * The server mounts Swagger UI at /docs. Same-origin when the guide is served
 * beside the API (Docker/prod fronts both); the server origin (port 8080)
 * when this page runs on the Vite dev server, which proxies /api only.
 * Visiting the server directly (localhost:8080) always works.
 */
function docsOrigin(): string {
  if (typeof window === 'undefined') return '';
  const { origin, port } = window.location;
  return port === '5176' ? 'http://localhost:8080' : origin;
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const COUNT =
  'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-body-sm text-on-surface';
const LINK_BUTTON =
  'inline-flex w-fit items-center gap-xs rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-secondary/25';
const LINK_SECONDARY =
  'inline-flex w-fit items-center gap-xs rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant hover:bg-surface-tint hover:text-on-surface';

export default function DocsLinkDemo(): ReactNode {
  const config = container.appConfig;
  const [panel, setPanel] = useState<CoveragePanel>({ kind: 'loading' });

  useEffect(() => {
    void safeRepositoryCall(async () => parseCoverage(await fetchJson(config, '/demo/api-docs/coverage')))
      .then((result) => {
        setPanel(result.ok
          ? { kind: 'ready', report: result.value }
          : { kind: 'error', failure: result.failure });
      });
  }, [config]);

  const origin = docsOrigin();

  return (
    <div className="flex flex-col gap-md">
      {/* The links: the living document and its interactive front door. */}
      <section className="flex flex-col gap-sm">
        <a
          data-testid="docs-link-open"
          href={`${origin}/docs`}
          target="_blank"
          rel="noopener noreferrer"
          className={LINK_BUTTON}
        >
          Open the API docs — Swagger UI at /docs
        </a>
        <a
          data-testid="docs-link-spec"
          href={`${origin}/v3/api-docs`}
          target="_blank"
          rel="noopener noreferrer"
          className={LINK_SECONDARY}
        >
          /v3/api-docs — the raw contract as JSON
        </a>
        {origin === 'http://localhost:8080' && (
          <p data-testid="docs-link-devnote" className="text-caption text-on-surface-variant">
            You are on the Vite dev server, which proxies /api only — these links point straight at
            the server origin (localhost:8080), where the docs are mounted.
          </p>
        )}
      </section>

      {/* The coverage: the server counting its own contract, live. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>the contract, counted live</p>
        {panel.kind === 'loading' && (
          <p data-testid="docs-coverage-loading" className="font-mono text-caption text-on-surface-variant">
            fetching /api/demo/api-docs/coverage…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the coverage report" failure={panel.failure} />}
        {panel.kind === 'ready' && (
          <>
            <div data-testid="docs-counts" className="flex flex-wrap items-center gap-xs">
              <span className={COUNT}>
                <span className="text-on-surface-variant">paths:</span>{' '}
                <span data-testid="docs-paths-count">{panel.report.paths}</span>
              </span>
              <span className={COUNT}>
                <span className="text-on-surface-variant">operations:</span>{' '}
                <span data-testid="docs-operations-count">{panel.report.operations}</span>
              </span>
              <span className={COUNT}>
                <span className="text-on-surface-variant">schemas:</span>{' '}
                <span data-testid="docs-schemas-count">{panel.report.schemas}</span>
              </span>
              <span className={COUNT}>
                <span className="text-on-surface-variant">spec:</span>{' '}
                <span data-testid="docs-spec-version">{panel.report.specVersion}</span>
              </span>
            </div>

            <div data-testid="docs-sample" className="flex flex-col gap-xxs">
              {Object.entries(panel.report.samplePaths)
                .slice(0, 6)
                .map(([group, paths]) => (
                  <div
                    key={group}
                    data-testid={`docs-sample-group-${group}`}
                    className="rounded-sm border border-outline bg-surface px-xs py-xxs"
                  >
                    <span className="font-mono text-caption text-on-surface-variant">{group}:</span>{' '}
                    <span className="font-mono text-caption text-on-surface">
                      {paths.join('  ')}
                    </span>
                  </div>
                ))}
            </div>

            <p data-testid="docs-link-notes" className="text-body-sm text-on-surface">
              These numbers come from the server fetching its own /v3/api-docs and counting — the
              same bytes the drift test pins, so the report can never be hand-maintained fiction.
              What you will find behind the links: every path this app really serves, grouped by
              tag, each with its parameters, request bodies, and response schemas — and{' '}
              <span className="font-mono">try it out</span> executes against the running server,
              so the documented envelope and the real one are always one click apart.
            </p>
          </>
        )}
      </section>
    </div>
  );
}
