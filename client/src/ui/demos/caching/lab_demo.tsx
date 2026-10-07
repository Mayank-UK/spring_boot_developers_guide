import { useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `caching:lab` — topic 21 (caching), the
 * spring-cache-abstraction section. Three panels over /api/demo/caching:
 *
 * - STATS: the same expensive read through either caching path
 *   (mode=explicit = the cache-aside wrapper over the CacheBackend port;
 *   mode=annotation = the @Cacheable proxy over the same backend). Run it
 *   twice: MISS becomes HIT and computeTimeMs collapses — the proof the
 *   page keeps repeating is the COMPUTE COUNT, surfaced as the verdict.
 * - INVALIDATE: the deliberate eviction, answering with every key dropped.
 * - HTTP SPECIMEN: GET the post (200 + ETag/Last-Modified/Cache-Control),
 *   then re-GET with If-None-Match — the 304 with no body IS the demo.
 */

interface StatsView {
  topicId: string;
  mode: string;
  result: {
    postCount: number;
    totalWords: number;
    avgWords: number;
    longestPostTitle: string;
  };
  cache: string;
  computeTimeMs: number;
  key: string;
  backendMode: string;
}

interface InvalidateView {
  topicId: string;
  evictedKeys: string[];
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; view: StatsView }
  | { kind: 'error'; failure: Failure };

type InvalidatePanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; view: InvalidateView }
  | { kind: 'error'; failure: Failure };

type SpecimenPanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; status: number; etag: string; lastModified: string; cacheControl: string; bodyPreview: string }
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

/**
 * The HTTP-caching specimen's raw fetch: revalidation is JUST a request
 * header — If-None-Match carries the ETag the last response handed out,
 * and the server answers 304 with no body when nothing changed.
 */
async function fetchSpecimen(
  config: AppConfig,
  postId: string,
  etag: string | null,
  useValidator: boolean,
): Promise<Response> {
  const conditional: HeadersInit = useValidator && etag !== null ? { 'if-none-match': etag } : {};
  try {
    return await fetch(`${config.apiBaseUrl}/demo/caching/http-cache/${postId}`, {
      headers: conditional,
    });
  } catch (error) {
    throw new NetworkException('Could not reach the server.', { cause: error });
  }
}

function parseStatsView(body: unknown): StatsView {
  if (
    !isRecord(body) ||
    !isRecord(body.result) ||
    typeof body.topicId !== 'string' ||
    typeof body.mode !== 'string' ||
    typeof body.cache !== 'string' ||
    typeof body.computeTimeMs !== 'number' ||
    typeof body.key !== 'string' ||
    typeof body.backendMode !== 'string' ||
    typeof body.result.postCount !== 'number' ||
    typeof body.result.totalWords !== 'number' ||
    typeof body.result.avgWords !== 'number' ||
    typeof body.result.longestPostTitle !== 'string'
  ) {
    throw new Error('stats answer needs result, cache, computeTimeMs, key, mode');
  }
  return {
    topicId: body.topicId,
    mode: body.mode,
    result: {
      postCount: body.result.postCount,
      totalWords: body.result.totalWords,
      avgWords: body.result.avgWords,
      longestPostTitle: body.result.longestPostTitle,
    },
    cache: body.cache,
    computeTimeMs: body.computeTimeMs,
    key: body.key,
    backendMode: body.backendMode,
  };
}

function parseInvalidateView(body: unknown): InvalidateView {
  if (
    !isRecord(body) ||
    typeof body.topicId !== 'string' ||
    !Array.isArray(body.evictedKeys) ||
    !body.evictedKeys.every((key) => typeof key === 'string')
  ) {
    throw new Error('invalidate answer needs topicId and evictedKeys');
  }
  return { topicId: body.topicId, evictedKeys: body.evictedKeys as string[] };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';

function verdictClass(cache: string): string {
  if (cache === 'HIT') return 'rounded-pill border border-secondary bg-secondary/20 px-sm py-xxs font-mono text-caption text-on-surface';
  return 'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';
}

export default function LabDemo() {
  const config = container.appConfig;
  const [topicId, setTopicId] = useState('rest');
  const [mode, setMode] = useState<'explicit' | 'annotation'>('explicit');
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });
  const [invalidatePanel, setInvalidatePanel] = useState<InvalidatePanel>({ kind: 'idle' });
  const [postId, setPostId] = useState('9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01');
  const [etag, setEtag] = useState<string | null>(null);
  const [specimenPanel, setSpecimenPanel] = useState<SpecimenPanel>({ kind: 'idle' });

  const runStats = () => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseStatsView(await requestJson(config, `/demo/caching/stats/${topicId}?mode=${mode}`)),
    ).then((result) => {
      setPanel(
        result.ok
          ? { kind: 'ready', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const invalidate = () => {
    setInvalidatePanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseInvalidateView(
        await requestJson(config, `/demo/caching/invalidate/${topicId}`, { method: 'POST' }),
      ),
    ).then((result) => {
      setInvalidatePanel(
        result.ok
          ? { kind: 'ready', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const runSpecimen = (useValidator: boolean) => {
    setSpecimenPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const response = await fetchSpecimen(config, postId, etag, useValidator);
      let bodyPreview = '(no body — that is the point of a 304)';
      if (response.status !== 304) {
        const post = (await response.json()) as Record<string, unknown>;
        bodyPreview = typeof post.title === 'string' ? post.title : JSON.stringify(post).slice(0, 80);
      }
      return {
        status: response.status,
        etag: response.headers.get('etag') ?? '(none)',
        lastModified: response.headers.get('last-modified') ?? '(none)',
        cacheControl: response.headers.get('cache-control') ?? '(none)',
        bodyPreview,
      };
    }).then((result) => {
      if (result.ok) {
        setSpecimenPanel({ kind: 'ready', ...result.value });
        if (result.value.status === 200 && result.value.etag !== '(none)') {
          setEtag(result.value.etag);
        }
      } else {
        setSpecimenPanel({ kind: 'error', failure: result.failure });
      }
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          topic (tag)
          <input
            data-testid="caching-topic-input"
            value={topicId}
            onChange={(event) => setTopicId(event.target.value)}
            className={`${INPUT} w-32`}
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          mode
          <select
            data-testid="caching-mode-select"
            value={mode}
            onChange={(event) => setMode(event.target.value as 'explicit' | 'annotation')}
            className={INPUT}
          >
            <option value="explicit">explicit</option>
            <option value="annotation">annotation</option>
          </select>
        </label>
        <button
          type="button"
          data-testid="caching-run-stats"
          onClick={runStats}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          run stats
        </button>
        <button
          type="button"
          data-testid="caching-invalidate"
          onClick={invalidate}
          className="rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface"
        >
          invalidate
        </button>
      </section>

      {panel.kind === 'idle' && (
        <p data-testid="caching-idle" className="font-mono text-caption text-on-surface-variant">
          no read yet — run it twice and watch MISS become HIT
        </p>
      )}
      {panel.kind === 'loading' && (
        <p data-testid="caching-loading" className="font-mono text-caption text-on-surface-variant">
          reading {mode}…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the stats read" failure={panel.failure} />}
      {panel.kind === 'ready' && (
        <section
          data-testid="caching-stats-result"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span className="flex items-center gap-sm">
            <span data-testid="caching-verdict" className={verdictClass(panel.view.cache)}>
              {panel.view.cache}
            </span>
            <span data-testid="caching-time">computeTimeMs: {String(panel.view.computeTimeMs)}</span>
            <span className="text-on-surface-variant">
              mode: {panel.view.mode} · backend: {panel.view.backendMode}
            </span>
          </span>
          <span data-testid="caching-key" className="break-all text-on-surface-variant">
            key: {panel.view.key}
          </span>
          <span data-testid="caching-report">
            {String(panel.view.result.postCount)} posts · {String(panel.view.result.totalWords)}{' '}
            words · avg {String(panel.view.result.avgWords)} · longest “
            {panel.view.result.longestPostTitle}”
          </span>
        </section>
      )}

      {invalidatePanel.kind === 'ready' && (
        <p data-testid="caching-invalidated-keys" className="font-mono text-caption text-on-surface">
          evicted: {invalidatePanel.view.evictedKeys.join(', ')}
        </p>
      )}
      {invalidatePanel.kind === 'error' && (
        <ErrorPanel subject="the invalidation" failure={invalidatePanel.failure} />
      )}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the http-cache specimen</p>
        <div className="flex flex-wrap items-end gap-sm">
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            post id
            <input
              data-testid="caching-post-input"
              value={postId}
              onChange={(event) => setPostId(event.target.value)}
              className={`${INPUT} w-64`}
            />
          </label>
          <button
            type="button"
            data-testid="caching-get"
            onClick={() => runSpecimen(false)}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            GET post
          </button>
          <button
            type="button"
            data-testid="caching-revalidate"
            onClick={() => runSpecimen(true)}
            className="rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface"
          >
            re-GET with If-None-Match
          </button>
        </div>
        {specimenPanel.kind === 'idle' && (
          <p data-testid="caching-specimen-idle" className="font-mono text-caption text-on-surface-variant">
            GET once to capture the validators, then re-GET to watch the 304
          </p>
        )}
        {specimenPanel.kind === 'loading' && (
          <p data-testid="caching-specimen-loading" className="font-mono text-caption text-on-surface-variant">
            fetching…
          </p>
        )}
        {specimenPanel.kind === 'error' && (
          <ErrorPanel subject="the specimen read" failure={specimenPanel.failure} />
        )}
        {specimenPanel.kind === 'ready' && (
          <section
            data-testid="caching-specimen-result"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <span data-testid="caching-specimen-status">
              status: {String(specimenPanel.status)}
              {specimenPanel.status === 304 ? ' — not modified, nothing shipped' : ''}
            </span>
            <span data-testid="caching-specimen-etag" className="break-all">
              etag: {specimenPanel.etag}
            </span>
            <span className="break-all text-on-surface-variant">
              last-modified: {specimenPanel.lastModified}
            </span>
            <span data-testid="caching-specimen-cache-control" className="break-all">
              cache-control: {specimenPanel.cacheControl}
            </span>
            <span className="break-all text-on-surface-variant">{specimenPanel.bodyPreview}</span>
          </section>
        )}
      </section>
    </div>
  );
}
