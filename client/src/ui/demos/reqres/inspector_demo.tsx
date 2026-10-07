import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import { ServerFailure, type Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `reqres:inspector` — topic 09 (request-response), the
 * request-object section. A request builder pointed at the inspector:
 * - GET  /api/demo/reqres/inspect — every query param, three selected
 *   headers, and one cookie, BOUND by MVC annotations, reported beside the
 *   RAW servlet surface (URI, query string, auth type, content type,
 *   locales, attributes) the sugars never covered
 * - POST /api/demo/reqres/inspect — the same sugars plus @RequestBody:
 *   the whole JSON body, bound at once after converter negotiation
 *
 * A teaching widget like the http demo: it fetches directly but through the
 * SAME guarded pipeline every data source uses. The report's whole point is
 * the split — each bound value comes back stamped with the annotation that
 * carried it.
 */

type Method = 'GET' | 'POST';

interface BoundValue {
  name: string;
  value: string;
  via: string;
}

interface BoundBody {
  present: boolean;
  parsedAs: string;
  topLevelEntries: number;
}

interface RawSurface {
  requestURI: string;
  queryString: string | null;
  authType: string | null;
  contentType: string | null;
  locales: string[];
}

interface AttributeView {
  name: string;
  value: string;
  note: string;
}

interface InspectReport {
  method: string;
  bound: { params: BoundValue[]; headers: BoundValue[]; cookies: BoundValue[]; body: BoundBody | null };
  raw: RawSurface;
  attributes: AttributeView[];
  summary: { boundValues: number; rawReads: number; attributes: number; notes: string[] };
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: InspectReport }
  | { kind: 'error'; failure: Failure };

const DEFAULT_QUERY = 'channel=inspector-demo';
const DEFAULT_FLAG = 'hello-surface';
const DEFAULT_COOKIE = 'first-visit';
const DEFAULT_BODY = '{"hello":"wire","count":3}';

/* ---- the guarded send: this demo's own builder fetch, taxonomy unchanged ---- */

async function sendInspect(
  config: AppConfig,
  method: Method,
  query: string,
  accept: string,
  flag: string,
  cookie: string,
  bodyText: string,
): Promise<PanelState> {
  const headers: Record<string, string> = {};
  if (accept.trim() !== '') headers['Accept'] = accept.trim();
  if (flag.trim() !== '') headers['x-teaching-flag'] = flag.trim();
  if (cookie.trim() !== '') headers['Cookie'] = `reqres-visit=${cookie.trim()}`;
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
  const path = `/demo/reqres/inspect${query.trim() === '' ? '' : `?${query.trim()}`}`;

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

function parseBoundValues(source: unknown, what: string): BoundValue[] {
  if (!Array.isArray(source)) throw new Error(`${what} must be an array`);
  for (const entry of source) {
    if (
      !isRecord(entry) ||
      typeof entry.name !== 'string' ||
      typeof entry.value !== 'string' ||
      typeof entry.via !== 'string'
    ) {
      throw new Error(`each bound value in ${what} needs name, value, via`);
    }
  }
  return source as BoundValue[];
}

function parseInspectReport(body: unknown): InspectReport {
  if (!isRecord(body)) throw new Error('inspect report must be an object');
  const bound = body.bound;
  if (!isRecord(bound)) throw new Error('report.bound must be an object');
  const raw = body.raw;
  if (
    !isRecord(raw) ||
    typeof raw.requestURI !== 'string' ||
    !Array.isArray(raw.locales) ||
    raw.locales.some((locale) => typeof locale !== 'string')
  ) {
    throw new Error('report.raw needs requestURI and a locales array');
  }
  if (
    (raw.queryString !== null && typeof raw.queryString !== 'string') ||
    (raw.authType !== null && typeof raw.authType !== 'string') ||
    (raw.contentType !== null && typeof raw.contentType !== 'string')
  ) {
    throw new Error('report.raw fields must be strings or null');
  }
  let boundBody: BoundBody | null = null;
  if (bound.body !== null && bound.body !== undefined) {
    if (
      !isRecord(bound.body) ||
      typeof bound.body.present !== 'boolean' ||
      typeof bound.body.parsedAs !== 'string' ||
      typeof bound.body.topLevelEntries !== 'number'
    ) {
      throw new Error('report.bound.body needs present, parsedAs, topLevelEntries');
    }
    boundBody = bound.body as unknown as BoundBody;
  }
  if (!Array.isArray(body.attributes)) throw new Error('report.attributes must be an array');
  for (const attribute of body.attributes) {
    if (
      !isRecord(attribute) ||
      typeof attribute.name !== 'string' ||
      typeof attribute.value !== 'string' ||
      typeof attribute.note !== 'string'
    ) {
      throw new Error('each attribute needs name, value, note');
    }
  }
  const summary = body.summary;
  if (
    !isRecord(summary) ||
    typeof summary.boundValues !== 'number' ||
    typeof summary.rawReads !== 'number' ||
    typeof summary.attributes !== 'number' ||
    !Array.isArray(summary.notes) ||
    summary.notes.some((note) => typeof note !== 'string')
  ) {
    throw new Error('report.summary needs counters and a notes array');
  }
  return {
    method: stringField(body, 'method'),
    bound: {
      params: parseBoundValues(bound.params, 'bound.params'),
      headers: parseBoundValues(bound.headers, 'bound.headers'),
      cookies: parseBoundValues(bound.cookies, 'bound.cookies'),
      body: boundBody,
    },
    raw: raw as unknown as RawSurface,
    attributes: body.attributes as AttributeView[],
    summary: summary as unknown as InspectReport['summary'],
  };
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

/* ---- small shared presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs font-mono text-body-sm';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function MethodPicker({ active, onPick }: { active: Method; onPick: (method: Method) => void }) {
  const options: Method[] = ['GET', 'POST'];
  return (
    <div className="flex flex-wrap gap-xs" role="group" aria-label="request method">
      {options.map((option) => (
        <button
          key={option}
          type="button"
          data-testid={`reqres-method-${option.toLowerCase()}`}
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

function LabeledInput({
  id,
  label,
  value,
  placeholder,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-xs">
      <label htmlFor={id} className="w-32 font-mono text-caption text-on-surface-variant">
        {label}
      </label>
      <input
        id={id}
        data-testid={id}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className="min-w-40 flex-1 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
      />
    </div>
  );
}

function BoundTable({ title, values }: { title: string; values: BoundValue[] }) {
  return (
    <div className="flex flex-col gap-xxs">
      <p className={OVERLINE}>{title}</p>
      {values.length === 0 ? (
        <p className="font-mono text-caption text-on-surface-variant">none sent</p>
      ) : (
        <table className="w-full border-collapse text-body-sm">
          <tbody>
            {values.map((value) => (
              <tr key={`${value.name}-${value.via}`} data-testid="reqres-bound-row">
                <td className={CELL}>{value.name}</td>
                <td className={CELL}>{value.value}</td>
                <td className={`${CELL} text-on-surface-variant`}>{value.via}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ReportPanel({ report }: { report: InspectReport }) {
  return (
    <div data-testid="reqres-inspect-report" className="flex flex-col gap-sm">
      <p data-testid="reqres-request-line" className="font-mono text-body-sm text-on-surface">
        {report.method} {report.raw.requestURI}
        {report.raw.queryString === null ? '' : `?${report.raw.queryString}`}
      </p>

      <div className="flex flex-col gap-xs">
        <p className={OVERLINE}>bound — what MVC handed the handler, stamped with its sugar</p>
        <BoundTable title="params (@RequestParam)" values={report.bound.params} />
        <BoundTable title="headers (@RequestHeader)" values={report.bound.headers} />
        <BoundTable title="cookies (@CookieValue)" values={report.bound.cookies} />
        {report.bound.body !== null && report.bound.body.present && (
          <p data-testid="reqres-body-bound" className="font-mono text-caption text-on-surface">
            @RequestBody bound the whole body — parsed as {report.bound.body.parsedAs},{' '}
            {String(report.bound.body.topLevelEntries)} top-level entries
          </p>
        )}
      </div>

      <div className="flex flex-col gap-xxs">
        <p className={OVERLINE}>raw — read by hand from the request object</p>
        <table className="w-full border-collapse text-body-sm">
          <tbody>
            <tr data-testid="reqres-raw-uri">
              <td className={CELL}>requestURI</td>
              <td className={CELL}>{report.raw.requestURI}</td>
            </tr>
            <tr data-testid="reqres-raw-query">
              <td className={CELL}>queryString</td>
              <td className={CELL}>{report.raw.queryString ?? 'null'}</td>
            </tr>
            <tr>
              <td className={CELL}>authType</td>
              <td className={CELL}>{report.raw.authType ?? 'null'}</td>
            </tr>
            <tr>
              <td className={CELL}>contentType</td>
              <td className={CELL}>{report.raw.contentType ?? 'null'}</td>
            </tr>
            <tr>
              <td className={CELL}>locales</td>
              <td className={CELL}>{report.raw.locales.join(', ') || 'none'}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="flex flex-col gap-xxs">
        <p className={OVERLINE}>attributes — in-process cargo the sugars never cover</p>
        {report.attributes.length === 0 ? (
          <p className="font-mono text-caption text-on-surface-variant">
            none (the request-id filter has not run in this slice — live, it stamps requestId)
          </p>
        ) : (
          report.attributes.map((attribute) => (
            <p key={attribute.name} data-testid="reqres-attribute-row" className="font-mono text-caption text-on-surface">
              {attribute.name} = {attribute.value} — {attribute.note}
            </p>
          ))
        )}
      </div>

      <div data-testid="reqres-summary" className="flex flex-col gap-xxs">
        <p className={OVERLINE}>summary</p>
        <p className="font-mono text-caption text-on-surface">
          {String(report.summary.boundValues)} bound · {String(report.summary.rawReads)} raw reads ·{' '}
          {String(report.summary.attributes)} attribute(s)
        </p>
        {report.summary.notes.map((note) => (
          <p key={note} className="font-mono text-caption text-on-surface-variant">
            {note}
          </p>
        ))}
      </div>
    </div>
  );
}

export default function InspectorDemo() {
  const [method, setMethod] = useState<Method>('GET');
  const [query, setQuery] = useState(DEFAULT_QUERY);
  const [accept, setAccept] = useState('application/json');
  const [flag, setFlag] = useState(DEFAULT_FLAG);
  const [cookie, setCookie] = useState(DEFAULT_COOKIE);
  const [bodyText, setBodyText] = useState(DEFAULT_BODY);
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const send = useCallback(
    (
      currentMethod: Method,
      currentQuery: string,
      currentAccept: string,
      currentFlag: string,
      currentCookie: string,
      currentBody: string,
    ) => {
      setPanel({ kind: 'loading' });
      void sendInspect(config, currentMethod, currentQuery, currentAccept, currentFlag, currentCookie, currentBody)
        .then(setPanel);
    },
    [config],
  );

  useEffect(() => {
    send('GET', DEFAULT_QUERY, 'application/json', DEFAULT_FLAG, DEFAULT_COOKIE, DEFAULT_BODY);
  }, [send]);

  return (
    <div className="flex flex-col gap-md">
      {/* The builder: you control exactly what the server will bind. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>build a request, watch both surfaces of it</p>
        <MethodPicker
          active={method}
          onPick={(picked) => {
            setMethod(picked);
            send(picked, query, accept, flag, cookie, bodyText);
          }}
        />
        <LabeledInput
          id="reqres-query-input"
          label="query"
          value={query}
          placeholder="key=value&other=1"
          onChange={setQuery}
        />
        <LabeledInput
          id="reqres-accept-input"
          label="Accept"
          value={accept}
          placeholder="application/json"
          onChange={setAccept}
        />
        <LabeledInput
          id="reqres-flag-input"
          label="x-teaching-flag"
          value={flag}
          placeholder="custom header"
          onChange={setFlag}
        />
        <LabeledInput
          id="reqres-cookie-input"
          label="cookie value"
          value={cookie}
          placeholder="reqres-visit=… (empty for none)"
          onChange={setCookie}
        />
        {method === 'POST' && (
          <div className="flex flex-col gap-xxs">
            <label htmlFor="reqres-body-input" className="font-mono text-caption text-on-surface-variant">
              JSON body (sent with Content-Type: application/json)
            </label>
            <textarea
              id="reqres-body-input"
              data-testid="reqres-body-input"
              rows={3}
              value={bodyText}
              onChange={(event) => setBodyText(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </div>
        )}
        <button
          type="button"
          data-testid="reqres-send"
          onClick={() => send(method, query, accept, flag, cookie, bodyText)}
          className="self-start rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          send request
        </button>
      </section>

      {/* The two surfaces, side by side. */}
      <section className="flex flex-col gap-sm">
        {panel.kind === 'loading' && (
          <p data-testid="reqres-inspect-loading" className="text-body-sm text-on-surface-variant">
            asking the server…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the inspector report" failure={panel.failure} />}
        {panel.kind === 'ready' && <ReportPanel report={panel.report} />}
      </section>
    </div>
  );
}
