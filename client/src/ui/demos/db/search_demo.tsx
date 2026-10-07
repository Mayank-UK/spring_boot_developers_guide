import { useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:search` — topic 17 (jdbc-sql), the full-text-search
 * section. websearch_to_tsquery over the V12 tsvector column, ranked by
 * ts_rank with <b>marked</b> headline excerpts, the ILIKE count beside it,
 * and the three EXPLAIN readouts proving the structural difference. Try
 * q=errors — stemming finds the "error envelope" post; the substring scan
 * finds nothing.
 */

interface SearchHit {
  id: string;
  title: string;
  rank: number;
  excerpt: string;
}

interface SearchReport {
  q: string;
  limit: number;
  hits: SearchHit[];
  likeCount: number;
  ftsPlan: string[];
  ftsPlanIndexForced: string[];
  likePlan: string[];
  comparisonNote: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'ready'; report: SearchReport }
  | { kind: 'error'; failure: Failure };

const SUGGESTIONS = ['errors', 'pagination', '"uniform interface"', 'paging'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseReport(body: unknown): SearchReport {
  if (!isRecord(body)) throw new Error('report must be an object');
  if (typeof body.q !== 'string' || typeof body.likeCount !== 'number') {
    throw new Error('report needs q and likeCount');
  }
  if (!Array.isArray(body.hits) || !Array.isArray(body.ftsPlan) || !Array.isArray(body.likePlan)) {
    throw new Error('report needs hits, ftsPlan, likePlan arrays');
  }
  for (const hit of body.hits) {
    if (!isRecord(hit) || typeof hit.id !== 'string' || typeof hit.title !== 'string') {
      throw new Error('each hit needs id and title');
    }
  }
  return {
    q: body.q,
    limit: typeof body.limit === 'number' ? body.limit : 5,
    hits: body.hits as unknown as SearchHit[],
    likeCount: body.likeCount,
    ftsPlan: body.ftsPlan.map((line) => String(line)),
    ftsPlanIndexForced: (Array.isArray(body.ftsPlanIndexForced)
      ? body.ftsPlanIndexForced
      : []
    ).map((line) => String(line)),
    likePlan: body.likePlan.map((line) => String(line)),
    comparisonNote: typeof body.comparisonNote === 'string' ? body.comparisonNote : '',
  };
}

async function runSearch(config: AppConfig, q: string): Promise<PanelState> {
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(
        `${config.apiBaseUrl}/demo/db/search?q=${encodeURIComponent(q)}&limit=5`,
      );
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
      const message = isRecord(parsed) && isRecord(parsed.error)
          && typeof parsed.error.message === 'string'
        ? parsed.error.message
        : `Request failed with status ${String(response.status)}.`;
      throw new ServerException(message, { status: response.status });
    }
    return parseReport(parsed);
  });
  if (result.ok) return { kind: 'ready', report: result.value };
  return { kind: 'error', failure: result.failure };
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CODE = 'rounded-sm bg-surface-tint px-xs py-xs font-mono text-caption text-on-surface whitespace-pre-wrap';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';

/** The excerpt carries <b>…</b> markers from ts_headline — render them as emphasis. */
function Excerpt({ excerpt }: { excerpt: string }): ReactNode {
  const parts = excerpt.split(/<\/?b>/);
  return (
    <span data-testid="search-excerpt" className="font-mono text-caption text-on-surface-variant">
      {parts.map((part, index) =>
        index % 2 === 1 ? (
          <mark key={index} className="rounded-xxs bg-secondary/30 px-xxs text-on-surface">
            {part}
          </mark>
        ) : (
          <span key={index}>{part}</span>
        ),
      )}
    </span>
  );
}

function Plan({ title, lines, testId }: { title: string; lines: string[]; testId: string }): ReactNode {
  return (
    <div data-testid={testId} className="flex flex-col gap-xxs">
      <p className={OVERLINE}>{title}</p>
      <code className={CODE}>{lines.join('\n')}</code>
    </div>
  );
}

export default function SearchDemo(): ReactNode {
  const config = container.appConfig;
  const [q, setQ] = useState('errors');
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });
  const [running, setRunning] = useState(false);

  const run = async (term: string): Promise<void> => {
    setQ(term);
    setRunning(true);
    setPanel(await runSearch(config, term));
    setRunning(false);
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <input
          data-testid="search-input"
          className="min-w-56 flex-1 rounded-sm border border-outline bg-surface px-sm py-xxs font-mono text-caption text-on-surface"
          value={q}
          onChange={(event) => setQ(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !running) void run(q);
          }}
          placeholder='websearch syntax: words, "phrases", OR, -excluded'
        />
        <button
          type="button"
          data-testid="search-run"
          className={BUTTON}
          disabled={running}
          onClick={() => void run(q)}
        >
          {running ? 'searching…' : 'search'}
        </button>
        {SUGGESTIONS.map((suggestion) => (
          <button
            key={suggestion}
            type="button"
            data-testid={`search-suggest-${suggestion.replace(/[^a-z]/g, '')}`}
            className={BUTTON}
            disabled={running}
            onClick={() => void run(suggestion)}
          >
            {suggestion}
          </button>
        ))}
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the search report" failure={panel.failure} />}

      {panel.kind === 'ready' && (
        <>
          <section className="flex flex-col gap-xs">
            <p className={OVERLINE}>ranked hits — ts_rank over the generated tsvector</p>
            {panel.report.hits.length === 0 ? (
              <p data-testid="search-no-hits" className="font-mono text-caption text-on-surface-variant">
                no post matches “{panel.report.q}” — the term and its stems appear nowhere
              </p>
            ) : (
              <ol className="flex flex-col gap-xs">
                {panel.report.hits.map((hit) => (
                  <li
                    key={hit.id}
                    data-testid="search-hit"
                    className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs"
                  >
                    <span className="flex items-baseline justify-between gap-sm">
                      <span className="text-body-sm text-on-surface">{hit.title}</span>
                      <span data-testid="search-rank" className="font-mono text-caption text-on-surface-variant">
                        rank {hit.rank.toFixed(4)}
                      </span>
                    </span>
                    <Excerpt excerpt={hit.excerpt} />
                  </li>
                ))}
              </ol>
            )}
            <p data-testid="search-like-count" className="font-mono text-caption text-on-surface-variant">
              the same term as a substring (ILIKE %…%): {String(panel.report.likeCount)} row(s)
            </p>
          </section>

          <section className="grid gap-sm md:grid-cols-3">
            <Plan title="FTS plan (default)" lines={panel.report.ftsPlan} testId="search-plan-fts" />
            <Plan
              title="FTS plan (seq scan forced off)"
              lines={panel.report.ftsPlanIndexForced}
              testId="search-plan-forced"
            />
            <Plan title="LIKE plan" lines={panel.report.likePlan} testId="search-plan-like" />
          </section>

          <p data-testid="search-note" className="rounded-sm border border-secondary bg-secondary/10 px-sm py-xs text-caption text-on-surface">
            {panel.report.comparisonNote}
          </p>
        </>
      )}
    </div>
  );
}
