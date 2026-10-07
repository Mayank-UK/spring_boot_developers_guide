import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:mongo-console` — topic 19 (mongodb), the
 * queries-and-the-criteria-api section. The page-visit analytics console:
 * - POST /api/demo/mongo/visits records one visit into the CURRENT mode's
 *   store (the seeded in-memory fake, or the live guide_visits collection
 *   once the area has flipped); the server stores only a sha-256 of the
 *   visitor token, which the recent feed shows as the hash.
 * - GET /api/demo/mongo/stats renders the aggregates the $group pipelines
 *   answer: totals, top topics, average duration, the locale histogram.
 * - GET /api/demo/mongo/explain shows the pipelines themselves — the BSON
 *   stages the server would run, the transparency artifact.
 * - POST /api/demo/mongo/mode flips the area fake/live; a live request
 *   while MongoDB is unreachable answers an honest hint (the area stays
 *   fake), which this console displays instead of an error.
 */

interface VisitView {
  id: string;
  topicId: string;
  path: string;
  visitorHash: string;
  durationMs: number;
  occurredAt: string;
  locale: string;
}

interface TopicTally {
  topicId: string;
  visits: number;
}

interface LocaleTally {
  locale: string;
  visits: number;
}

interface StatsView {
  area: string;
  dataMode: string;
  totalVisits: number;
  topicVisits: number;
  filteredByTopic: boolean;
  avgDurationMs: number;
  topTopics: TopicTally[];
  localeHistogram: LocaleTally[];
}

interface ExplainPipeline {
  name: string;
  stages: Record<string, unknown>[];
}

interface ExplainView {
  dataMode: string;
  note: string;
  pipelines: ExplainPipeline[];
}

interface ModeView {
  dataMode: string;
  requested: string;
  database: string | null;
  hint: string | null;
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; visit: VisitView }
  | { kind: 'error'; failure: Failure };

type ModePanel =
  | { kind: 'idle' }
  | { kind: 'flipping' }
  | { kind: 'done'; view: ModeView }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded fetch pipeline (same shape as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function exchange(config: AppConfig, path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'Content-Type': 'application/json' },
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

function parseVisit(body: unknown): VisitView {
  if (
    !isRecord(body) ||
    typeof body.id !== 'string' ||
    typeof body.topicId !== 'string' ||
    typeof body.path !== 'string' ||
    typeof body.visitorHash !== 'string' ||
    typeof body.durationMs !== 'number' ||
    typeof body.occurredAt !== 'string' ||
    typeof body.locale !== 'string'
  ) {
    throw new Error('visit fields missing');
  }
  return {
    id: body.id,
    topicId: body.topicId,
    path: body.path,
    visitorHash: body.visitorHash,
    durationMs: body.durationMs,
    occurredAt: body.occurredAt,
    locale: body.locale,
  };
}

function parseStats(body: unknown): StatsView {
  if (
    !isRecord(body) ||
    typeof body.totalVisits !== 'number' ||
    typeof body.topicVisits !== 'number' ||
    typeof body.avgDurationMs !== 'number' ||
    !Array.isArray(body.topTopics) ||
    !Array.isArray(body.localeHistogram)
  ) {
    throw new Error('stats fields missing');
  }
  return {
    area: typeof body.area === 'string' ? body.area : 'mongo',
    dataMode: typeof body.dataMode === 'string' ? body.dataMode : 'fake',
    totalVisits: body.totalVisits,
    topicVisits: body.topicVisits,
    filteredByTopic: body.filteredByTopic === true,
    avgDurationMs: body.avgDurationMs,
    topTopics: body.topTopics
      .filter((t): t is Record<string, unknown> => isRecord(t))
      .map((t) => ({
        topicId: String(t.topicId),
        visits: Number(t.visits),
      })),
    localeHistogram: body.localeHistogram
      .filter((l): l is Record<string, unknown> => isRecord(l))
      .map((l) => ({
        locale: String(l.locale),
        visits: Number(l.visits),
      })),
  };
}

function parseExplain(body: unknown): ExplainView {
  if (!isRecord(body) || !Array.isArray(body.pipelines)) {
    throw new Error('explain fields missing');
  }
  return {
    dataMode: typeof body.dataMode === 'string' ? body.dataMode : 'fake',
    note: typeof body.note === 'string' ? body.note : '',
    pipelines: body.pipelines
      .filter((p): p is Record<string, unknown> => isRecord(p))
      .map((p) => ({
        name: String(p.name),
        stages: Array.isArray(p.stages)
          ? p.stages.filter((s): s is Record<string, unknown> => isRecord(s))
          : [],
      })),
  };
}

function parseMode(body: unknown): ModeView {
  if (!isRecord(body) || typeof body.dataMode !== 'string') {
    throw new Error('mode fields missing');
  }
  return {
    dataMode: body.dataMode,
    requested: typeof body.requested === 'string' ? body.requested : body.dataMode,
    database: typeof body.database === 'string' ? body.database : null,
    hint: typeof body.hint === 'string' ? body.hint : null,
  };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function formatDuration(ms: number): string {
  if (ms < 1000) return `${String(ms)}ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${String(seconds)}s`;
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60)}s`;
}

export default function MongoConsoleDemo() {
  const config = container.appConfig;
  const [topicId, setTopicId] = useState('mongodb');
  const [path, setPath] = useState('/topics/mongodb');
  const [durationMs, setDurationMs] = useState(45000);
  const [locale, setLocale] = useState('en');
  const [visitorToken, setVisitorToken] = useState('reader-console');
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });
  const [stats, setStats] = useState<StatsView | null>(null);
  const [recent, setRecent] = useState<VisitView[]>([]);
  const [explain, setExplain] = useState<ExplainView | null>(null);
  const [modePanel, setModePanel] = useState<ModePanel>({ kind: 'idle' });

  const refresh = () => {
    void safeRepositoryCall(async () =>
      parseStats(await exchange(config, '/demo/mongo/stats')),
    ).then((result) => {
      setStats(result.ok ? result.value : null);
    });
    void safeRepositoryCall(async () =>
      parseRecent(await exchange(config, '/demo/mongo/visits/recent?limit=6')),
    ).then((result) => {
      setRecent(result.ok ? result.value : []);
    });
  };

  function parseRecent(body: unknown): VisitView[] {
    if (!isRecord(body) || !Array.isArray(body.visits)) {
      throw new Error('recent fields missing');
    }
    return body.visits.map(parseVisit);
  }

  // Open on the populated console: the seeded fake answers immediately.
  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- mount-only load
  }, []);

  const record = () => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const body = await exchange(config, '/demo/mongo/visits', {
        method: 'POST',
        body: JSON.stringify({ topicId, path, durationMs, locale, visitorToken }),
      });
      // The endpoint answers {area, dataMode, visit} — the stored document is nested.
      return parseVisit(isRecord(body) && isRecord(body.visit) ? body.visit : body);
    }).then((result) => {
      setPanel(
        result.ok
          ? { kind: 'ready', visit: result.value }
          : { kind: 'error', failure: result.failure },
      );
      refresh();
    });
  };

  const loadExplain = () => {
    void safeRepositoryCall(async () =>
      parseExplain(await exchange(config, '/demo/mongo/explain')),
    ).then((result) => {
      setExplain(result.ok ? result.value : null);
    });
  };

  const flip = (dataMode: 'fake' | 'live') => {
    setModePanel({ kind: 'flipping' });
    void safeRepositoryCall(async () =>
      parseMode(
        await exchange(config, '/demo/mongo/mode', {
          method: 'POST',
          body: JSON.stringify({ dataMode }),
        }),
      ),
    ).then((result) => {
      setModePanel(
        result.ok
          ? { kind: 'done', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
      refresh();
    });
  };

  const currentMode = stats?.dataMode ?? 'fake';

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-sm">
        <span className={OVERLINE}>
          area: mongo · mode:{' '}
          <span data-testid="mongo-mode">{currentMode}</span>
        </span>
        <button
          type="button"
          data-testid="mongo-flip-fake"
          onClick={() => flip('fake')}
          className="rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface"
        >
          use fake
        </button>
        <button
          type="button"
          data-testid="mongo-flip-live"
          onClick={() => flip('live')}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          flip live
        </button>
      </section>

      {modePanel.kind === 'flipping' && (
        <p data-testid="mongo-mode-flipping" className="font-mono text-caption text-on-surface-variant">
          activating…
        </p>
      )}
      {modePanel.kind === 'done' && modePanel.view.hint !== null && (
        <p
          data-testid="mongo-mode-hint"
          className="rounded-sm border border-outline px-sm py-xs font-mono text-caption text-on-surface"
        >
          {modePanel.view.hint}
        </p>
      )}
      {modePanel.kind === 'done' && modePanel.view.database !== null && (
        <p data-testid="mongo-mode-live" className="font-mono text-caption text-on-surface">
          live on database {modePanel.view.database}
        </p>
      )}
      {modePanel.kind === 'error' && (
        <ErrorPanel subject="the mode flip" failure={modePanel.failure} />
      )}

      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          topicId
          <input
            data-testid="mongo-topic-input"
            type="text"
            value={topicId}
            onChange={(event) => setTopicId(event.target.value)}
            className="w-36 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          path
          <input
            data-testid="mongo-path-input"
            type="text"
            value={path}
            onChange={(event) => setPath(event.target.value)}
            className="w-52 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          durationMs
          <input
            data-testid="mongo-duration-input"
            type="number"
            min={0}
            value={durationMs}
            onChange={(event) => setDurationMs(Number(event.target.value))}
            className="w-28 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          locale
          <input
            data-testid="mongo-locale-input"
            type="text"
            value={locale}
            onChange={(event) => setLocale(event.target.value)}
            className="w-20 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          visitorToken
          <input
            data-testid="mongo-token-input"
            type="text"
            value={visitorToken}
            onChange={(event) => setVisitorToken(event.target.value)}
            className="w-36 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <button
          type="button"
          data-testid="mongo-record"
          onClick={record}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          record a visit
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="mongo-recording" className="font-mono text-caption text-on-surface-variant">
          recording…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the visit" failure={panel.failure} />}
      {panel.kind === 'ready' && (
        <p data-testid="mongo-recorded" className="font-mono text-caption text-on-surface">
          recorded {panel.visit.topicId} · {formatDuration(panel.visit.durationMs)} · visitor{' '}
          {panel.visit.visitorHash.slice(0, 12)}…
        </p>
      )}

      {stats !== null && (
        <section data-testid="mongo-stats" className="flex flex-col gap-xs">
          <p className={OVERLINE}>
            {String(stats.totalVisits)} visits stored
            {stats.filteredByTopic
              ? ` · ${String(stats.topicVisits)} in the filtered topic`
              : ''}{' '}
            · avg read {formatDuration(stats.avgDurationMs)}
          </p>
          <div className="grid gap-xs md:grid-cols-2">
            <div
              data-testid="mongo-topics"
              className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
            >
              {stats.topTopics.map((tally) => (
                <span key={tally.topicId} data-testid="mongo-topic-row">
                  {tally.topicId}: {String(tally.visits)}
                </span>
              ))}
            </div>
            <div
              data-testid="mongo-histogram"
              className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
            >
              {stats.localeHistogram.map((tally) => (
                <span key={tally.locale} data-testid="mongo-locale-row">
                  {tally.locale}: {String(tally.visits)}
                </span>
              ))}
            </div>
          </div>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <button
          type="button"
          data-testid="mongo-explain"
          onClick={loadExplain}
          className="self-start rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface"
        >
          explain the pipelines
        </button>
        {recent.length > 0 && (
          <div
            data-testid="mongo-recent"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface-variant"
          >
            {recent.map((visit) => (
              <span key={visit.id} data-testid="mongo-recent-row">
                {visit.occurredAt} · {visit.topicId} · {visit.locale} ·{' '}
                {formatDuration(visit.durationMs)}
              </span>
            ))}
          </div>
        )}
        {explain !== null && (
          <div
            data-testid="mongo-explain-view"
            className="flex flex-col gap-xxs overflow-x-auto rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <p className={OVERLINE}>{explain.note}</p>
            {explain.pipelines.map((pipeline) => (
              <pre key={pipeline.name} className="whitespace-pre text-caption text-on-surface">
                {`db.guide_visits.aggregate([
${pipeline.stages.map((stage) => `  ${JSON.stringify(stage)}`).join(',\n')}
])  // ${pipeline.name}`}
              </pre>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
