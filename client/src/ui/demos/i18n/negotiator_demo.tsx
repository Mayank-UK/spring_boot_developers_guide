import { useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `i18n:negotiator` — topic 27 (internationalization),
 * the accept-language-negotiation section. One Accept-Language builder
 * (locale rows with q-values, hostile-header presets) drives three panels
 * over /api/demo/i18n:
 *
 * - NEGOTIATE: the full trace — parsed candidates, skipped malformed
 *   segments, the two-pass score matrix, our winner and the resolver's
 *   actual choice side by side.
 * - GREETING: localized content for the negotiated locale — the builder's
 *   header rides the request, the server answers in the locale it chose,
 *   and the fallback chain shows which bundle level spoke.
 * - FORMAT: the locale format matrix (dates, currency, compact numbers)
 *   plus the two-zone contrast that separates locale from time zone.
 */

interface Candidate {
  tag: string;
  language: string;
  region: string;
  quality: number;
  wildcard: boolean;
  rank: number;
}

interface Score {
  supported: string;
  score: number;
  matched: string | null;
  kind: string;
}

interface NegotiateView {
  header: string | null;
  fromRequest: boolean;
  candidates: Candidate[];
  skipped: string[];
  supported: string[];
  scores: Score[];
  winner: string;
  kind: string;
  reason: string;
  resolverChose: string;
  resolverAgrees: boolean;
}

interface ChainStep {
  bundle: string;
  hit: boolean;
  template: string | null;
}

interface GreetingView {
  useCase: string;
  locale: string;
  localeDisplay: string;
  key: string;
  message: string | null;
  answeringBundle: string | null;
  template: string | null;
  chain: ChainStep[];
  note: string;
}

interface FormatRow {
  tag: string;
  display: string;
  date: string;
  time: string;
  dateTime: string;
  currencyLocaleTag: string;
  currency: string;
  currencyCode: string;
  decimal: string;
  percent: string;
  compact: string;
}

interface ZonedMoment {
  zoneId: string;
  offset: string;
  dateTime: string;
}

interface FormatView {
  instant: string;
  number: number;
  locales: FormatRow[];
  zones: ZonedMoment[];
  note: string;
}

interface Row {
  id: number;
  tag: string;
  q: string;
}

type Panel<T> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; view: T }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded fetch pipeline (same shape as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function requestJson(
  config: AppConfig,
  path: string,
  init?: RequestInit,
): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      ...init,
      headers: { ...(init?.headers as Record<string, string> | undefined) },
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
  return parsed;
}

function parseNegotiate(body: unknown): NegotiateView {
  if (
    !isRecord(body) ||
    !Array.isArray(body.candidates) ||
    !Array.isArray(body.skipped) ||
    !Array.isArray(body.scores) ||
    !Array.isArray(body.supported) ||
    typeof body.winner !== 'string' ||
    typeof body.kind !== 'string' ||
    typeof body.reason !== 'string' ||
    typeof body.resolverChose !== 'string' ||
    typeof body.resolverAgrees !== 'boolean'
  ) {
    throw new Error('negotiate answer needs candidates, scores, winner, resolverChose');
  }
  return {
    header: typeof body.header === 'string' ? body.header : null,
    fromRequest: body.fromRequest === true,
    candidates: body.candidates as Candidate[],
    skipped: body.skipped as string[],
    supported: body.supported as string[],
    scores: body.scores as Score[],
    winner: body.winner,
    kind: body.kind,
    reason: body.reason,
    resolverChose: body.resolverChose,
    resolverAgrees: body.resolverAgrees,
  };
}

function parseGreeting(body: unknown): GreetingView {
  if (
    !isRecord(body) ||
    typeof body.useCase !== 'string' ||
    typeof body.locale !== 'string' ||
    typeof body.note !== 'string' ||
    !Array.isArray(body.chain)
  ) {
    throw new Error('greeting answer needs useCase, locale, chain, note');
  }
  return {
    useCase: body.useCase,
    locale: body.locale,
    localeDisplay: typeof body.localeDisplay === 'string' ? body.localeDisplay : '',
    key: typeof body.key === 'string' ? body.key : '',
    message: typeof body.message === 'string' ? body.message : null,
    answeringBundle: typeof body.answeringBundle === 'string' ? body.answeringBundle : null,
    template: typeof body.template === 'string' ? body.template : null,
    chain: body.chain as ChainStep[],
    note: body.note,
  };
}

function parseFormat(body: unknown): FormatView {
  if (
    !isRecord(body) ||
    typeof body.instant !== 'string' ||
    typeof body.number !== 'number' ||
    !Array.isArray(body.locales) ||
    !Array.isArray(body.zones) ||
    typeof body.note !== 'string'
  ) {
    throw new Error('format answer needs instant, locales, zones, note');
  }
  return {
    instant: body.instant,
    number: body.number,
    locales: body.locales as FormatRow[],
    zones: body.zones as ZonedMoment[],
    note: body.note,
  };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const PILL =
  'rounded-pill border px-sm py-xxs font-mono text-caption';

const PRESETS: Array<{ label: string; rows: Array<[string, string]> }> = [
  { label: 'browser-like', rows: [['de-DE', '1'], ['fr', '0.8'], ['en', '0.5']] },
  { label: 'region-only', rows: [['ja', '1']] },
  { label: 'wildcard', rows: [['*', '0.1']] },
  { label: 'not-acceptable', rows: [['de', '0'], ['en', '0.5']] },
  {
    label: 'hostile',
    rows: [['EN-gb', '1'], ['zz-ZZ;q=OOPS', '1'], ['', '1'], ['fr;q=1.5', '1']],
  },
];

/** The Accept-Language builder's product: one header string from the rows. */
function buildHeader(rows: Row[]): string {
  return rows
    .map((row) => {
      const tag = row.tag.trim();
      if (tag === '') return '';
      return row.q.trim() !== '' && row.q.trim() !== '1' ? `${tag};q=${row.q.trim()}` : tag;
    })
    .filter((segment) => segment !== '')
    .join(', ');
}

export default function NegotiatorDemo() {
  const config = container.appConfig;
  const [rows, setRows] = useState<Row[]>(
    PRESETS[0]!.rows.map(([tag, q], index) => ({ id: index + 1, tag, q })),
  );
  const [nextId, setNextId] = useState(PRESETS[0]!.rows.length + 1);
  const [negotiatePanel, setNegotiatePanel] = useState<Panel<NegotiateView>>({ kind: 'idle' });
  const [useCase, setUseCase] = useState('welcome');
  const [name, setName] = useState('Mak');
  const [greetingPanel, setGreetingPanel] = useState<Panel<GreetingView>>({ kind: 'idle' });
  const [formatPanel, setFormatPanel] = useState<Panel<FormatView>>({ kind: 'idle' });

  const applyPreset = (label: string) => {
    const preset = PRESETS.find((entry) => entry.label === label);
    if (!preset) return;
    const applied = preset.rows.map(([tag, q], index) => ({ id: index + 1, tag, q }));
    setRows(applied);
    setNextId(preset.rows.length + 1);
  };

  const patchRow = (id: number, field: 'tag' | 'q', value: string) => {
    setRows((current) =>
      current.map((row) => (row.id === id ? { ...row, [field]: value } : row)),
    );
  };

  const header = buildHeader(rows);

  const runNegotiate = () => {
    setNegotiatePanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseNegotiate(
        await requestJson(config, `/demo/i18n/negotiate?header=${encodeURIComponent(header)}`),
      ),
    ).then((result) => {
      setNegotiatePanel(
        result.ok
          ? { kind: 'ready', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const runGreeting = () => {
    setGreetingPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseGreeting(
        await requestJson(
          config,
          `/demo/i18n/greeting?useCase=${useCase}&name=${encodeURIComponent(name)}`,
          { headers: { 'Accept-Language': header } },
        ),
      ),
    ).then((result) => {
      setGreetingPanel(
        result.ok
          ? { kind: 'ready', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const runFormat = () => {
    setFormatPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseFormat(await requestJson(config, '/demo/i18n/format')),
    ).then((result) => {
      setFormatPanel(
        result.ok
          ? { kind: 'ready', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the accept-language builder</p>
        <div data-testid="i18n-header-rows" className="flex flex-col gap-xxs">
          {rows.map((row, index) => (
            <div key={row.id} className="flex flex-wrap items-center gap-xxs">
              <span className="w-4 font-mono text-caption text-on-surface-variant">
                {String(index + 1)}
              </span>
              <input
                data-testid={`i18n-row-${row.id}-tag`}
                value={row.tag}
                onChange={(event) => patchRow(row.id, 'tag', event.target.value)}
                placeholder="tag (fr, en-GB, *)"
                className={`${INPUT} w-40`}
              />
              <label className="flex items-center gap-xxs font-mono text-caption text-on-surface-variant">
                q=
                <input
                  data-testid={`i18n-row-${row.id}-q`}
                  value={row.q}
                  onChange={(event) => patchRow(row.id, 'q', event.target.value)}
                  placeholder="1"
                  className={`${INPUT} w-14`}
                />
              </label>
              <button
                type="button"
                data-testid={`i18n-row-${row.id}-remove`}
                onClick={() => setRows((current) => current.filter((entry) => entry.id !== row.id))}
                className="rounded-pill border border-outline px-xs py-xxs font-mono text-caption text-on-surface-variant"
              >
                x
              </button>
            </div>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-sm">
          <button
            type="button"
            data-testid="i18n-add-row"
            onClick={() => {
              setRows((current) => [...current, { id: nextId, tag: '', q: '1' }]);
              setNextId((current) => current + 1);
            }}
            className={`${PILL} border-outline text-on-surface`}
          >
            + add locale
          </button>
          <label className="flex items-center gap-xxs font-mono text-caption text-on-surface-variant">
            preset
            <select
              data-testid="i18n-preset"
              value=""
              onChange={(event) => applyPreset(event.target.value)}
              className={INPUT}
            >
              <option value="">choose…</option>
              {PRESETS.map((preset) => (
                <option key={preset.label} value={preset.label}>
                  {preset.label}
                </option>
              ))}
            </select>
          </label>
          <span data-testid="i18n-header-value" className="break-all font-mono text-caption text-on-surface">
            Accept-Language: {header === '' ? '(empty)' : header}
          </span>
        </div>
        <button
          type="button"
          data-testid="i18n-negotiate-run"
          onClick={runNegotiate}
          className={`${PILL} self-start border-secondary bg-secondary/15 text-on-surface`}
        >
          negotiate
        </button>
      </section>

      {negotiatePanel.kind === 'idle' && (
        <p data-testid="i18n-idle" className="font-mono text-caption text-on-surface-variant">
          build a header and negotiate — the trace shows candidates, junk skipped, scores, the winner
        </p>
      )}
      {negotiatePanel.kind === 'loading' && (
        <p className="font-mono text-caption text-on-surface-variant">negotiating…</p>
      )}
      {negotiatePanel.kind === 'error' && (
        <ErrorPanel subject="the negotiation" failure={negotiatePanel.failure} />
      )}
      {negotiatePanel.kind === 'ready' && (
        <section
          data-testid="i18n-negotiate-result"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <div data-testid="i18n-candidates" className="flex flex-wrap gap-x-md gap-y-xxs">
            {negotiatePanel.view.candidates.map((candidate) => (
              <span key={candidate.tag} className={PILL}>
                #{String(candidate.rank)} {candidate.tag} q={String(candidate.quality)}
                {candidate.wildcard ? ' (wildcard)' : ''}
              </span>
            ))}
            {negotiatePanel.view.candidates.length === 0 && (
              <span className="text-on-surface-variant">no candidates survived parsing</span>
            )}
          </div>
          {negotiatePanel.view.skipped.length > 0 && (
            <div data-testid="i18n-skipped" className="flex flex-col text-on-surface-variant">
              {negotiatePanel.view.skipped.map((reason) => (
                <span key={reason}>skipped: {reason}</span>
              ))}
            </div>
          )}
          <div data-testid="i18n-scores" className="flex flex-col">
            {negotiatePanel.view.scores.map((score) => (
              <span key={score.supported}>
                {score.supported}: {score.kind === 'NONE' ? '—' : `${String(score.score)} via ${score.matched} (${score.kind.toLowerCase()})`}
              </span>
            ))}
          </div>
          <span data-testid="i18n-winner" className={PILL}>
            winner: {negotiatePanel.view.winner} · {negotiatePanel.view.kind.toLowerCase()} —{' '}
            {negotiatePanel.view.reason}
          </span>
          <span data-testid="i18n-resolver" className="text-on-surface-variant">
            resolver chose: {negotiatePanel.view.resolverChose}
            {negotiatePanel.view.resolverAgrees ? ' (agrees)' : ' (differs — see reason)'}
          </span>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>localized content for the negotiated locale</p>
        <div className="flex flex-wrap items-end gap-sm">
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            use case
            <select
              data-testid="i18n-usecase-select"
              value={useCase}
              onChange={(event) => setUseCase(event.target.value)}
              className={INPUT}
            >
              <option value="welcome">welcome ({'{0}'} parameter)</option>
              <option value="returning">returning (date parameter)</option>
              <option value="plural">plural (choice pattern)</option>
              <option value="fallback">fallback (base bundle only)</option>
              <option value="missing">missing (nowhere)</option>
              <option value="pseudo">pseudo (layout copy)</option>
            </select>
          </label>
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            name
            <input
              data-testid="i18n-name-input"
              value={name}
              onChange={(event) => setName(event.target.value)}
              className={`${INPUT} w-32`}
            />
          </label>
          <button
            type="button"
            data-testid="i18n-greeting-run"
            onClick={runGreeting}
            className={`${PILL} border-secondary bg-secondary/15 text-on-surface`}
          >
            greet (header rides the request)
          </button>
        </div>
        {greetingPanel.kind === 'loading' && (
          <p className="font-mono text-caption text-on-surface-variant">greeting…</p>
        )}
        {greetingPanel.kind === 'error' && (
          <ErrorPanel subject="the greeting" failure={greetingPanel.failure} />
        )}
        {greetingPanel.kind === 'ready' && (
          <section
            data-testid="i18n-greeting-result"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <span data-testid="i18n-greeting-message">
              {greetingPanel.view.message ?? '(no message — the key exists nowhere)'}
            </span>
            <span className="text-on-surface-variant">
              locale {greetingPanel.view.locale} ({greetingPanel.view.localeDisplay}) · key{' '}
              {greetingPanel.view.key} · answered by{' '}
              {greetingPanel.view.answeringBundle ?? 'no bundle'}
            </span>
            <span data-testid="i18n-chain" className="text-on-surface-variant">
              chain:{' '}
              {greetingPanel.view.chain
                .map((step) => `${step.bundle} ${step.hit ? 'HIT' : 'miss'}`)
                .join(' → ')}
              {greetingPanel.view.chain.length === 0 ? '(empty)' : ''}
            </span>
            {greetingPanel.view.template !== null && (
              <span className="break-all text-on-surface-variant">
                template: {greetingPanel.view.template}
              </span>
            )}
          </section>
        )}
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the locale format matrix</p>
        <button
          type="button"
          data-testid="i18n-format-run"
          onClick={runFormat}
          className={`${PILL} self-start border-secondary bg-secondary/15 text-on-surface`}
        >
          render the matrix
        </button>
        {formatPanel.kind === 'loading' && (
          <p className="font-mono text-caption text-on-surface-variant">formatting…</p>
        )}
        {formatPanel.kind === 'error' && (
          <ErrorPanel subject="the format matrix" failure={formatPanel.failure} />
        )}
        {formatPanel.kind === 'ready' && (
          <section
            data-testid="i18n-format-result"
            className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <span className="text-on-surface-variant">
              instant {formatPanel.view.instant} · number {String(formatPanel.view.number)}
            </span>
            <table data-testid="i18n-format-table" className="text-left">
              <thead>
                <tr className="text-on-surface-variant">
                  <th className="pr-sm">tag</th>
                  <th className="pr-sm">date</th>
                  <th className="pr-sm">time</th>
                  <th className="pr-sm">currency</th>
                  <th className="pr-sm">decimal</th>
                  <th className="pr-sm">percent</th>
                  <th>compact</th>
                </tr>
              </thead>
              <tbody>
                {formatPanel.view.locales.map((row) => (
                  <tr key={row.tag}>
                    <td className="pr-sm">
                      {row.tag} ({row.display})
                    </td>
                    <td className="pr-sm">{row.date}</td>
                    <td className="pr-sm">{row.time}</td>
                    <td className="pr-sm">
                      {row.currency} <span className="text-on-surface-variant">{row.currencyCode}</span>
                    </td>
                    <td className="pr-sm">{row.decimal}</td>
                    <td className="pr-sm">{row.percent}</td>
                    <td>{row.compact}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div data-testid="i18n-zones" className="flex flex-col text-on-surface-variant">
              {formatPanel.view.zones.map((zone) => (
                <span key={zone.zoneId}>
                  {zone.zoneId} ({zone.offset}): {zone.dateTime}
                </span>
              ))}
            </div>
          </section>
        )}
      </section>
    </div>
  );
}
