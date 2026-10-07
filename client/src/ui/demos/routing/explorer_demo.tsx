import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `routing:explorer` — topic 07 (routing), the opening
 * section. Two probes over the server's real route registry:
 * - GET /api/demo/routing/table?prefix=… — every registered annotation
 *   mapping (pattern, methods, controller, handler, produces/consumes),
 *   optionally narrowed by prefix
 * - GET /api/demo/routing/match?path=…&method=… — which mapping wins for a
 *   probe, which others also matched, and the WHY: ranked candidates with
 *   specificity scores, captures, and typed verdicts (matched ·
 *   method_not_allowed · no_match)
 *
 * Like every teaching widget here it fetches directly, but through the SAME
 * guarded pipeline all data sources use — transport failures and error
 * envelopes arrive as typed Failures.
 */

interface RouteRow {
  pattern: string;
  methods: string[];
  controller: string;
  bean: string;
  handler: string;
  produces: string[];
  consumes: string[];
}

interface TableView {
  prefix: string | null;
  total: number;
  routes: RouteRow[];
}

interface Candidate {
  entry: RouteRow;
  pathMatched: boolean;
  methodMatched: boolean;
  captured: Record<string, string>;
  score: number;
  catchAll: boolean;
  explanation: string;
}

interface MatchReport {
  method: string;
  path: string;
  verdict: 'matched' | 'method_not_allowed' | 'no_match';
  matched: boolean;
  winner: RouteRow | null;
  captured: Record<string, string>;
  candidates: Candidate[];
  considered: number;
  nonMatched: number;
}

type PanelState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; report: T }
  | { kind: 'error'; failure: Failure };

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const DEFAULT_PROBE_PATH = '/api/content/java-language';
const DEFAULT_PREFIX = '/api/demo';

/* ---- the guarded fetch shared by both probes ---- */

async function fetchJson<T>(config: AppConfig, path: string, parse: (body: unknown) => T): Promise<T> {
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}${path}`, {
        headers: { accept: 'application/json' },
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
      if (isRecord(parsed) && isRecord(parsed.error)) {
        throw new ServerException(
          typeof parsed.error.message === 'string'
            ? parsed.error.message
            : `Request failed with status ${String(response.status)}.`,
          {
            status: response.status,
            code: typeof parsed.error.code === 'string' ? parsed.error.code : 'unknown_error',
            requestId:
              typeof parsed.error.requestId === 'string' ? parsed.error.requestId : undefined,
          },
        );
      }
      throw new ServerException(`Request failed with status ${String(response.status)}.`, {
        status: response.status,
      });
    }
    return parseDto(parsed, response.status, parse);
  });
  if (result.ok) return result.value;
  throw result.failure;
}

/* ---- runtime validation of the stable wire shape ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringArray(value: unknown): string[] {
  if (!Array.isArray(value)) throw new Error('expected a string array');
  return value.map((entry) => {
    if (typeof entry !== 'string') throw new Error('expected a string array');
    return entry;
  });
}

function parseRouteRow(body: unknown): RouteRow {
  if (
    !isRecord(body) ||
    typeof body.pattern !== 'string' ||
    typeof body.controller !== 'string' ||
    typeof body.bean !== 'string' ||
    typeof body.handler !== 'string'
  ) {
    throw new Error('each route needs pattern, controller, bean, handler');
  }
  return {
    pattern: body.pattern,
    methods: stringArray(body.methods),
    controller: body.controller,
    bean: body.bean,
    handler: body.handler,
    produces: stringArray(body.produces),
    consumes: stringArray(body.consumes),
  };
}

function parseTableView(body: unknown): TableView {
  if (!isRecord(body) || typeof body.total !== 'number' || !Array.isArray(body.routes)) {
    throw new Error('table view needs total and routes');
  }
  return {
    prefix: typeof body.prefix === 'string' ? body.prefix : null,
    total: body.total,
    routes: body.routes.map(parseRouteRow),
  };
}

function parseMatchReport(body: unknown): MatchReport {
  if (
    !isRecord(body) ||
    typeof body.method !== 'string' ||
    typeof body.path !== 'string' ||
    typeof body.verdict !== 'string' ||
    typeof body.matched !== 'boolean' ||
    !Array.isArray(body.candidates)
  ) {
    throw new Error('match report needs method, path, verdict, matched, candidates');
  }
  const verdict = body.verdict;
  if (verdict !== 'matched' && verdict !== 'method_not_allowed' && verdict !== 'no_match') {
    throw new Error(`unknown verdict "${verdict}"`);
  }
  const captured = isRecord(body.captured)
    ? Object.fromEntries(
        Object.entries(body.captured).map(([key, value]) => [key, String(value)]),
      )
    : {};
  return {
    method: body.method,
    path: body.path,
    verdict,
    matched: body.matched,
    winner: body.winner === null || body.winner === undefined ? null : parseRouteRow(body.winner),
    captured,
    candidates: body.candidates.map((raw) => {
      if (!isRecord(raw)) throw new Error('each candidate must be an object');
      return {
        entry: parseRouteRow(raw.entry),
        pathMatched: raw.pathMatched === true,
        methodMatched: raw.methodMatched === true,
        captured: isRecord(raw.captured)
          ? Object.fromEntries(Object.entries(raw.captured).map(([k, v]) => [k, String(v)]))
          : {},
        score: typeof raw.score === 'number' ? raw.score : 0,
        catchAll: raw.catchAll === true,
        explanation: typeof raw.explanation === 'string' ? raw.explanation : '',
      };
    }),
    considered: typeof body.considered === 'number' ? body.considered : 0,
    nonMatched: typeof body.nonMatched === 'number' ? body.nonMatched : 0,
  };
}

/* ---- presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs font-mono text-caption';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function methodsLabel(methods: readonly string[]): string {
  return methods.length === 0 ? 'ANY' : methods.join(', ');
}

function VerdictBadge({ verdict }: { verdict: MatchReport['verdict'] }) {
  const tone =
    verdict === 'matched'
      ? 'border-secondary text-on-surface'
      : verdict === 'method_not_allowed'
        ? 'border-error text-error'
        : 'border-outline text-on-surface-variant';
  return (
    <span
      data-testid="routing-verdict"
      className={`rounded-pill border px-sm py-xxs font-mono text-caption ${tone}`}
    >
      {verdict}
    </span>
  );
}

function RouteTable({ view }: { view: TableView }) {
  return (
    <div data-testid="routing-table" className="flex flex-col gap-xxs">
      <p data-testid="routing-table-count" className="font-mono text-caption text-on-surface-variant">
        {String(view.total)} routes{view.prefix === null ? '' : ` under ${view.prefix}`}
      </p>
      <table className="w-full border-collapse text-body-sm">
        <thead>
          <tr className="text-left text-on-surface-variant">
            <th className="border-b border-outline px-xs py-xs font-semibold">pattern</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">methods</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">handler</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">negotiates</th>
          </tr>
        </thead>
        <tbody className="text-on-surface">
          {view.routes.map((route) => (
            <tr key={`${route.pattern} ${route.controller}.${route.handler}`} data-testid="routing-row">
              <td className={CELL}>{route.pattern}</td>
              <td className={CELL}>{methodsLabel(route.methods)}</td>
              <td className={CELL}>
                {route.controller}#{route.handler}
              </td>
              <td className={CELL}>
                {route.consumes.length === 0 && route.produces.length === 0
                  ? '—'
                  : `${route.consumes.join(', ')} → ${route.produces.join(', ')}`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function MatchPanel({ report }: { report: MatchReport }) {
  return (
    <div data-testid="routing-match" className="flex flex-col gap-sm">
      <p className="font-mono text-body-sm text-on-surface">
        {report.method} {report.path}
      </p>
      <VerdictBadge verdict={report.verdict} />
      {report.winner !== null && (
        <div data-testid="routing-winner" className="flex flex-col gap-xxs">
          <p className={OVERLINE}>winner</p>
          <p className="font-mono text-body-sm text-on-surface">
            {report.winner.pattern} → {report.winner.controller}#{report.winner.handler}
          </p>
          {Object.keys(report.captured).length > 0 && (
            <p data-testid="routing-captured" className="font-mono text-caption text-on-surface-variant">
              captured{' '}
              {Object.entries(report.captured)
                .map(([name, value]) => `{${name}} = "${value}"`)
                .join('  ')}
            </p>
          )}
        </div>
      )}
      {report.candidates.length > 0 && (
        <ol className="flex flex-col gap-xs">
          <p className={OVERLINE}>ranked candidates — why the winner won</p>
          {report.candidates.map((candidate, index) => (
            <li
              key={`${candidate.entry.pattern}-${String(index)}`}
              data-testid="routing-candidate"
              className="flex flex-col gap-xxs border-b border-outline pb-xs"
            >
              <span className="font-mono text-caption text-on-surface">
                {candidate.entry.pattern}
                {candidate.catchAll ? ' (catch-all)' : ''} · score {String(candidate.score)}
                {candidate.pathMatched && candidate.methodMatched ? '' : ' · method not allowed'}
              </span>
              <span className="font-mono text-caption text-on-surface-variant">
                {candidate.explanation}
              </span>
            </li>
          ))}
        </ol>
      )}
      {report.verdict === 'no_match' && (
        <p data-testid="routing-no-match" className="font-mono text-caption text-on-surface-variant">
          nothing matches — {String(report.nonMatched)} of {String(report.considered)} registered
          patterns refused this path
        </p>
      )}
    </div>
  );
}

export default function RoutingExplorerDemo() {
  const [prefix, setPrefix] = useState(DEFAULT_PREFIX);
  const [table, setTable] = useState<PanelState<TableView>>({ kind: 'loading' });
  const [probePath, setProbePath] = useState(DEFAULT_PROBE_PATH);
  const [probeMethod, setProbeMethod] = useState<string>('GET');
  const [match, setMatch] = useState<PanelState<MatchReport>>({ kind: 'loading' });
  const config = container.appConfig;

  const loadTable = useCallback(
    (nextPrefix: string) => {
      setTable({ kind: 'loading' });
      const query = nextPrefix.trim() === '' ? '' : `?prefix=${encodeURIComponent(nextPrefix.trim())}`;
      fetchJson(config, `/demo/routing/table${query}`, parseTableView)
        .then((view) => setTable({ kind: 'ready', report: view }))
        .catch((failure: Failure) => setTable({ kind: 'error', failure }));
    },
    [config],
  );

  const runProbe = useCallback(
    (method: string, path: string) => {
      setMatch({ kind: 'loading' });
      const query = `?path=${encodeURIComponent(path)}&method=${encodeURIComponent(method)}`;
      fetchJson(config, `/demo/routing/match${query}`, parseMatchReport)
        .then((report) => setMatch({ kind: 'ready', report }))
        .catch((failure: Failure) => setMatch({ kind: 'error', failure }));
    },
    [config],
  );

  useEffect(() => {
    loadTable(DEFAULT_PREFIX);
    runProbe('GET', DEFAULT_PROBE_PATH);
  }, [loadTable, runProbe]);

  return (
    <div className="flex flex-col gap-md">
      {/* Probe 1 — the registry, live. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>the route table — every registered annotation mapping</p>
        <div className="flex flex-wrap items-center gap-xs">
          <label htmlFor="routing-prefix" className="font-mono text-caption text-on-surface-variant">
            prefix
          </label>
          <input
            id="routing-prefix"
            data-testid="routing-prefix-input"
            value={prefix}
            placeholder="/api/demo (empty for everything)"
            onChange={(event) => setPrefix(event.target.value)}
            className="min-w-48 flex-1 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <button
            type="button"
            data-testid="routing-table-load"
            onClick={() => loadTable(prefix)}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            filter
          </button>
        </div>
        {table.kind === 'loading' && (
          <p data-testid="routing-table-loading" className="text-body-sm text-on-surface-variant">
            reading the registry…
          </p>
        )}
        {table.kind === 'error' && <ErrorPanel subject="the route table" failure={table.failure} />}
        {table.kind === 'ready' && <RouteTable view={table.report} />}
      </section>

      {/* Probe 2 — the match explainer. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>the match probe — who would handle this request, and why</p>
        <div className="flex flex-wrap items-center gap-xs">
          <select
            data-testid="routing-probe-method"
            aria-label="probe method"
            value={probeMethod}
            onChange={(event) => setProbeMethod(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            {METHODS.map((method) => (
              <option key={method} value={method}>
                {method}
              </option>
            ))}
          </select>
          <input
            data-testid="routing-probe-path"
            aria-label="probe path"
            value={probePath}
            placeholder="/api/content/java-language"
            onChange={(event) => setProbePath(event.target.value)}
            className="min-w-56 flex-1 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <button
            type="button"
            data-testid="routing-probe-run"
            onClick={() => runProbe(probeMethod, probePath)}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            probe
          </button>
        </div>
        {match.kind === 'loading' && (
          <p data-testid="routing-match-loading" className="text-body-sm text-on-surface-variant">
            asking the registry…
          </p>
        )}
        {match.kind === 'error' && <ErrorPanel subject="the match report" failure={match.failure} />}
        {match.kind === 'ready' && <MatchPanel report={match.report} />}
      </section>
    </div>
  );
}
