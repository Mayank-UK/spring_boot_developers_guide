import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `errors:explorer` — topic 30 (exception handling), the
 * failure-injection section. The taxonomy as a live surface:
 *
 * - the catalog loads the ten rows (eight FailureKind kinds plus the two
 *   framework ErrorResponse rows — 404/405) exactly as the server serves
 *   them, when-it-fires and example messages included;
 * - inject POSTs {kind, includeDetails} to the demo endpoint that
 *   GENUINELY throws the typed exception through the real advice — so the
 *   panel observes the rendered envelope: status, content-type, code,
 *   message, requestId, merged details. No fabricated envelopes: what you
 *   see is what the wire sent;
 * - the problem+json probe fires the SAME failure at the RFC 9457 route
 *   and renders the standard shape beside the envelope — one failure, two
 *   contracts, chosen at the edge.
 */

interface CatalogRow {
  kind: string;
  raisedBy: string;
  httpStatus: number;
  envelopeCode: string;
  whenItFires: string;
  exampleMessage: string;
  injectable: boolean;
  reproduce: string | null;
}

/** What the wire actually answered — status + headers + parsed body, ok or not. */
interface Observed {
  status: number;
  contentType: string;
  body: unknown;
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * The one fetch that treats error envelopes as DATA: unlike the guarded
 * pipeline (which turns non-2xx into typed client Failures), this captures
 * whatever the advice rendered — status, content-type and body — because
 * observing the failure IS the demo.
 */
async function observeJson(
  config: AppConfig,
  path: string,
  init?: RequestInit,
): Promise<Observed> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'content-type': 'application/json', accept: '*/*' },
      ...init,
    });
  } catch (error) {
    throw new NetworkException('Could not reach the server.', { cause: error });
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch (error) {
    throw new ServerException(`Request failed with status ${String(response.status)}.`, {
      status: response.status,
      cause: error,
    });
  }
  return {
    status: response.status,
    contentType: response.headers.get('content-type') ?? '(none)',
    body,
  };
}

function parseCatalog(body: unknown): CatalogRow[] {
  if (!Array.isArray(body)) {
    throw new Error('catalog answer must be a list');
  }
  return body.map((row) => {
    if (
      !isRecord(row) ||
      typeof row.kind !== 'string' ||
      typeof row.httpStatus !== 'number' ||
      typeof row.envelopeCode !== 'string'
    ) {
      throw new Error('each catalog row needs kind, httpStatus and envelopeCode');
    }
    return {
      kind: row.kind,
      raisedBy: String(row.raisedBy ?? ''),
      httpStatus: row.httpStatus,
      envelopeCode: row.envelopeCode,
      whenItFires: String(row.whenItFires ?? ''),
      exampleMessage: String(row.exampleMessage ?? ''),
      injectable: row.injectable === true,
      reproduce: typeof row.reproduce === 'string' ? row.reproduce : null,
    };
  });
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const CHIP =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

function ObservedView({ observed, testId }: { observed: Observed; testId: string }) {
  const envelope = isRecord(observed.body) ? observed.body.error : null;
  const entries = envelope !== null && isRecord(envelope)
    ? Object.entries(envelope)
    : isRecord(observed.body)
      ? Object.entries(observed.body)
      : [];
  return (
    <div
      data-testid={testId}
      className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
    >
      <span className="text-on-surface-variant">
        {String(observed.status)} · {observed.contentType}
      </span>
      {entries.map(([key, value]) => (
        <span key={key} className="break-all">
          {key}: {typeof value === 'string' ? value : JSON.stringify(value)}
        </span>
      ))}
    </div>
  );
}

export default function ErrorsExplorerDemo() {
  const config = container.appConfig;
  const [catalog, setCatalog] = useState<CatalogRow[]>([]);
  const [kind, setKind] = useState('CONFLICT');
  const [includeDetails, setIncludeDetails] = useState(true);
  const [envelope, setEnvelope] = useState<Observed | null>(null);
  const [problem, setProblem] = useState<Observed | null>(null);
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });

  useEffect(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const observed = await observeJson(config, '/demo/errors/catalog');
      if (observed.status !== 200) {
        throw new ServerException('The failure catalog is unreachable.', {
          status: observed.status,
          code: 'service_unavailable',
        });
      }
      setCatalog(parseCatalog(observed.body));
    }).then((result) => {
      setPanel(result.ok ? { kind: 'idle' } : { kind: 'error', failure: result.failure });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const runObserved = (setter: (observed: Observed) => void, action: () => Promise<Observed>) => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(action).then((result) => {
      setPanel(result.ok ? { kind: 'idle' } : { kind: 'error', failure: result.failure });
      if (result.ok) {
        setter(result.value);
      }
    });
  };

  const doInject = () =>
    runObserved(setEnvelope, () =>
      observeJson(config, '/demo/errors/inject', {
        method: 'POST',
        body: JSON.stringify({ kind, includeDetails }),
      }),
    );

  const doProblem = () =>
    runObserved(setProblem, () =>
      observeJson(
        config,
        `/demo/errors/problem-detail?kind=${encodeURIComponent(kind)}&includeDetails=${String(includeDetails)}`,
      ),
    );

  return (
    <div className="flex flex-col gap-md">
      <section
        data-testid="errors-catalog"
        className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        <p className={OVERLINE}>the catalog — eight kinds, one envelope, plus the framework family</p>
        {catalog.map((row) => (
          <span key={row.kind} data-testid={`errors-row-${row.kind}`} className="break-all">
            {row.kind} → {String(row.httpStatus)} {row.envelopeCode} · {row.raisedBy} ·{' '}
            {row.whenItFires}
            {!row.injectable && row.reproduce !== null ? ` · ${row.reproduce}` : ''}
          </span>
        ))}
        {catalog.length === 0 && panel.kind !== 'loading' && (
          <span className="text-on-surface-variant">catalog not loaded yet</span>
        )}
      </section>

      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          kind
          <select
            data-testid="errors-kind-select"
            value={kind}
            onChange={(event) => setKind(event.target.value)}
            className={INPUT}
          >
            {catalog
              .filter((row) => row.injectable)
              .map((row) => (
                <option key={row.kind} value={row.kind}>
                  {row.kind} ({String(row.httpStatus)})
                </option>
              ))}
          </select>
        </label>
        <label className="flex items-center gap-xxs font-mono text-caption text-on-surface-variant">
          <input
            data-testid="errors-details-toggle"
            type="checkbox"
            checked={includeDetails}
            onChange={(event) => setIncludeDetails(event.target.checked)}
          />
          includeDetails
        </label>
        <button
          type="button"
          data-testid="errors-inject"
          onClick={doInject}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          inject (throw it for real)
        </button>
        <button
          type="button"
          data-testid="errors-problem"
          onClick={doProblem}
          className="rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface-variant"
        >
          same failure as problem+json
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="errors-loading" className="font-mono text-caption text-on-surface-variant">
          working…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the failure explorer" failure={panel.failure} />}

      <section className="flex flex-wrap gap-sm">
        {envelope !== null && <ObservedView observed={envelope} testId="errors-observed" />}
        {problem !== null && <ObservedView observed={problem} testId="errors-problem-observed" />}
        {envelope !== null && problem !== null && (
          <span data-testid="errors-same-failure" className={CHIP}>
            same failure, two contracts — envelope vs RFC 9457 problem+json
          </span>
        )}
      </section>
    </div>
  );
}
