import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import { ServerFailure, type Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `http:headers` — topic 03 (http-web), the methods/
 * status/headers section. A request builder (method picker + header rows +
 * optional JSON body) pointed at the inspector endpoint:
 * - GET  /api/demo/http/inspect — echoes method, path, query, and every
 *   header, sorted and classified (caching/auth/content/session/
 *   negotiation/cors/other), secrets flagged
 * - POST /api/demo/http/inspect — the same echo plus what the server parsed
 *   out of the body's Content-Type
 *
 * A teaching widget (like the java demo): it fetches directly, but through
 * the SAME guarded pipeline every data source uses — transport failures and
 * error envelopes arrive as typed Failures in Results. The builder controls
 * method/headers/body explicitly, which is the whole point: you are looking
 * at the request, not just the response.
 */

type Method = 'GET' | 'POST';

interface HeaderRow {
  name: string;
  value: string;
}

interface HeaderView {
  name: string;
  value: string;
  category: string;
  known: boolean;
  sensitive: boolean;
}

interface BodyView {
  present: boolean;
  contentType: string | null;
  mediaType: string | null;
  parsedAs: string;
  topLevelEntries: number;
}

interface InspectReport {
  method: string;
  path: string;
  query: string | null;
  headers: HeaderView[];
  body: BodyView;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: InspectReport }
  | { kind: 'error'; failure: Failure };

const DEFAULT_QUERY = 'channel=headers-demo';
const DEFAULT_BODY = '{"hello":"wire","count":3}';

/* ---- the guarded send: this demo's own method+headers fetch, taxonomy unchanged ---- */

async function sendInspect(
  config: AppConfig,
  method: Method,
  query: string,
  rows: readonly HeaderRow[],
  bodyText: string,
): Promise<PanelState> {
  const headers: Record<string, string> = {};
  for (const row of rows) {
    if (row.name.trim() !== '' && row.value.trim() !== '') {
      headers[row.name.trim()] = row.value.trim();
    }
  }
  let body: string | undefined;
  if (method === 'POST') {
    try {
      JSON.parse(bodyText);
    } catch {
      return {
        kind: 'error',
        failure: new ServerFailure(
          'The request body is not valid JSON — the builder refuses to send it.',
          { code: 'invalid_json', status: 0 },
        ),
      };
    }
    headers['Content-Type'] = 'application/json';
    body = bodyText;
  }
  const path = `/demo/http/inspect${query.trim() === '' ? '' : `?${query.trim()}`}`;

  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}${path}`, { method, headers, body });
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
    return parseDto(parsed, response.status, parseInspectReport);
  });
  if (result.ok) return { kind: 'ready', report: result.value };
  return { kind: 'error', failure: result.failure };
}

/* ---- runtime validation of the stable wire shape ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseInspectReport(body: unknown): InspectReport {
  if (!isRecord(body)) throw new Error('inspect report must be an object');
  const headers = body.headers;
  if (!Array.isArray(headers)) throw new Error('headers must be an array');
  for (const header of headers) {
    if (
      !isRecord(header) ||
      typeof header.name !== 'string' ||
      typeof header.value !== 'string' ||
      typeof header.category !== 'string' ||
      typeof header.known !== 'boolean' ||
      typeof header.sensitive !== 'boolean'
    ) {
      throw new Error('each echoed header needs name, value, category, known, sensitive');
    }
  }
  const rawBody = body.body;
  if (
    !isRecord(rawBody) ||
    typeof rawBody.present !== 'boolean' ||
    typeof rawBody.parsedAs !== 'string' ||
    typeof rawBody.topLevelEntries !== 'number' ||
    (rawBody.contentType !== null && typeof rawBody.contentType !== 'string') ||
    (rawBody.mediaType !== null && typeof rawBody.mediaType !== 'string')
  ) {
    throw new Error('body report needs present, contentType, mediaType, parsedAs, entries');
  }
  return {
    method: stringField(body, 'method'),
    path: stringField(body, 'path'),
    query: body.query === null || body.query === undefined ? null : stringField(body, 'query'),
    headers: headers as HeaderView[],
    body: rawBody as unknown as BodyView,
  };
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

/* ---- small shared presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function MethodPicker({ active, onPick }: { active: Method; onPick: (method: Method) => void }) {
  const options: Method[] = ['GET', 'POST'];
  return (
    <div className="flex flex-wrap gap-xs" role="group" aria-label="request method">
      {options.map((option) => (
        <button
          key={option}
          type="button"
          data-testid={`http-method-${option.toLowerCase()}`}
          aria-pressed={option === active}
          onClick={() => onPick(option)}
          className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
            option === active
              ? 'border-secondary bg-secondary/15 text-on-surface'
              : 'border-outline text-on-surface-variant hover:bg-surface-tint'
          }`}
        >
          {option}
        </button>
      ))}
    </div>
  );
}

function HeaderRows({
  rows,
  onChange,
  onRemove,
  onAdd,
}: {
  rows: HeaderRow[];
  onChange: (index: number, patch: Partial<HeaderRow>) => void;
  onRemove: (index: number) => void;
  onAdd: () => void;
}) {
  return (
    <div className="flex flex-col gap-xs">
      {rows.map((row, index) => (
        <div key={index} className="flex flex-wrap items-center gap-xs">
          <input
            data-testid={`http-header-name-${index}`}
            aria-label={`header ${index + 1} name`}
            value={row.name}
            placeholder="header name"
            onChange={(event) => onChange(index, { name: event.target.value })}
            className="w-40 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <input
            data-testid={`http-header-value-${index}`}
            aria-label={`header ${index + 1} value`}
            value={row.value}
            placeholder="value"
            onChange={(event) => onChange(index, { value: event.target.value })}
            className="min-w-32 flex-1 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <button
            type="button"
            data-testid={`http-header-remove-${index}`}
            aria-label={`remove header ${index + 1}`}
            onClick={() => onRemove(index)}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant hover:bg-surface-tint"
          >
            remove
          </button>
        </div>
      ))}
      <button
        type="button"
        data-testid="http-add-header"
        onClick={onAdd}
        className="self-start rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant hover:bg-surface-tint"
      >
        + header row
      </button>
    </div>
  );
}

function ReportPanel({ report }: { report: InspectReport }) {
  const target = `${report.method} ${report.path}${report.query === null ? '' : `?${report.query}`}`;
  return (
    <div data-testid="http-inspect-report" className="flex flex-col gap-sm">
      <p data-testid="http-request-line" className="font-mono text-body-sm text-on-surface">
        {target}
      </p>

      <table className="w-full border-collapse text-body-sm">
        <thead>
          <tr className="text-left text-on-surface-variant">
            <th className="border-b border-outline px-xs py-xs font-semibold">header (sorted)</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">value</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">classification</th>
          </tr>
        </thead>
        <tbody className="text-on-surface">
          {report.headers.map((header) => (
            <tr key={header.name} data-testid="http-header-row">
              <td className={`${CELL} font-mono`}>{header.name}</td>
              <td className={`${CELL} font-mono`}>{header.value}</td>
              <td className={CELL}>
                <span className="font-mono text-caption text-on-surface-variant">
                  {header.category}
                  {header.known ? '' : ' (not a standard name)'}
                </span>
                {header.sensitive && (
                  <span
                    data-testid="http-sensitive-flag"
                    className="ml-xs rounded-sm border border-error px-xs py-xxs font-mono text-caption text-error"
                  >
                    sensitive
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div data-testid="http-body-report" className="flex flex-col gap-xxs">
        <p className={OVERLINE}>what the server parsed out of the body</p>
        {report.body.present ? (
          <p className="font-mono text-caption text-on-surface">
            Content-Type: {report.body.contentType ?? 'none declared'} → media type{' '}
            {report.body.mediaType ?? 'unknown'} · parsed as {report.body.parsedAs} ·{' '}
            {String(report.body.topLevelEntries)} top-level entries
          </p>
        ) : (
          <p className="font-mono text-caption text-on-surface-variant">
            no body — this request was a GET
          </p>
        )}
      </div>
    </div>
  );
}

export default function HeadersDemo() {
  const [method, setMethod] = useState<Method>('GET');
  const [query, setQuery] = useState(DEFAULT_QUERY);
  const [rows, setRows] = useState<HeaderRow[]>([
    { name: 'x-teaching-flag', value: 'hello-wire' },
  ]);
  const [bodyText, setBodyText] = useState(DEFAULT_BODY);
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const send = useCallback(
    (currentMethod: Method, currentQuery: string, currentRows: HeaderRow[], currentBody: string) => {
      setPanel({ kind: 'loading' });
      void sendInspect(config, currentMethod, currentQuery, currentRows, currentBody).then(setPanel);
    },
    [config],
  );

  useEffect(() => {
    send('GET', DEFAULT_QUERY, [{ name: 'x-teaching-flag', value: 'hello-wire' }], DEFAULT_BODY);
  }, [send]);

  const changeRow = (index: number, patch: Partial<HeaderRow>) => {
    setRows((current) =>
      current.map((row, position) => (position === index ? { ...row, ...patch } : row)),
    );
  };

  return (
    <div className="flex flex-col gap-md">
      {/* The builder: you control the request, not just the response. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>build a request, watch the server read it back</p>
        <MethodPicker
          active={method}
          onPick={(picked) => {
            setMethod(picked);
            send(picked, query, rows, bodyText);
          }}
        />
        <div className="flex flex-wrap items-center gap-xs">
          <label htmlFor="http-query" className="font-mono text-caption text-on-surface-variant">
            query
          </label>
          <input
            id="http-query"
            data-testid="http-query-input"
            value={query}
            placeholder="key=value&other=1 (empty for none)"
            onChange={(event) => setQuery(event.target.value)}
            className="min-w-48 flex-1 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </div>
        <HeaderRows
          rows={rows}
          onChange={changeRow}
          onRemove={(index) => {
            const next = rows.filter((_, position) => position !== index);
            setRows(next);
            send(method, query, next, bodyText);
          }}
          onAdd={() => setRows((current) => [...current, { name: '', value: '' }])}
        />
        {method === 'POST' && (
          <div className="flex flex-col gap-xxs">
            <label htmlFor="http-body" className="font-mono text-caption text-on-surface-variant">
              JSON body (sent with Content-Type: application/json)
            </label>
            <textarea
              id="http-body"
              data-testid="http-body-input"
              rows={3}
              value={bodyText}
              onChange={(event) => setBodyText(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </div>
        )}
        <button
          type="button"
          data-testid="http-send"
          onClick={() => send(method, query, rows, bodyText)}
          className="self-start rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          send request
        </button>
      </section>

      {/* The annotated echo. */}
      <section className="flex flex-col gap-sm">
        {panel.kind === 'loading' && (
          <p data-testid="http-inspect-loading" className="text-body-sm text-on-surface-variant">
            asking the server…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the inspector report" failure={panel.failure} />}
        {panel.kind === 'ready' && <ReportPanel report={panel.report} />}
      </section>
    </div>
  );
}
