import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `msg:outbox` — topic 22 (messaging), the
 * transactional-outbox section. The proof console:
 *
 * - transactional publishes a post (mutation + event in ONE tx on the
 *   outbox path; mutation-then-XADD on the direct path) and can crash on
 *   purpose at both windows. failAfterPublish + outbox → the 500 envelope
 *   below IS THE PROOF STARTING: the post and the outbox row committed
 *   together, so /relay still delivers the event. failAfterPublish +
 *   direct → THE LOST EVENT: the post committed, the event is nowhere.
 *   failBeforeCommit → one transaction, both writes roll back.
 * - relay runs one batch: claim (SKIP LOCKED, single-flight) → XADD → mark
 *   processed; poison rows (attempts past the threshold) dead-letter.
 * - state is the evidence readout: pending / delivered / dead counts, the
 *   newest rows with attempts, and whether the ticker is live.
 */

const SEED_POST = '9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01';

interface Outcome {
  publisher: string;
  postId: string;
  title: string;
  newVersion: number;
  eventId: string;
  streamId?: string;
  note: string;
}

interface RelayAnswer {
  claimed: number;
  published: number;
  deadLettered: number;
  skippedInFlight: boolean;
  streamIds: string[];
}

interface OutboxRow {
  id: number;
  attempts: number;
  event: { eventId: string; eventType: string };
  processedAt: string | null;
}

interface StateAnswer {
  stats: { pending: number; delivered: number; dead: number };
  recent: OutboxRow[];
  pollerRunning: boolean;
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
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

/** The proof request: which publisher, and where (whether) to crash. */
async function transact(
  config: AppConfig,
  postId: string,
  publisher: string,
  failBeforeCommit: boolean,
  failAfterPublish: boolean,
): Promise<Outcome> {
  const body = await requestJson(config, '/demo/msg/outbox/transactional', {
    method: 'POST',
    body: JSON.stringify({ postId, publisher, failBeforeCommit, failAfterPublish }),
  });
  if (!isRecord(body) || typeof body.eventId !== 'string' || typeof body.note !== 'string') {
    throw new Error('transactional answer needs eventId and note');
  }
  return {
    publisher: String(body.publisher ?? publisher),
    postId: String(body.postId ?? postId),
    title: String(body.title ?? ''),
    newVersion: typeof body.newVersion === 'number' ? body.newVersion : 0,
    eventId: body.eventId,
    streamId: typeof body.streamId === 'string' ? body.streamId : undefined,
    note: body.note,
  };
}

function parseState(body: unknown): StateAnswer {
  if (!isRecord(body) || !isRecord(body.stats) || !Array.isArray(body.recent)) {
    throw new Error('state needs stats and recent');
  }
  return {
    stats: {
      pending: Number(body.stats.pending ?? 0),
      delivered: Number(body.stats.delivered ?? 0),
      dead: Number(body.stats.dead ?? 0),
    },
    recent: body.recent.map((row) => {
      if (!isRecord(row) || typeof row.id !== 'number' || !isRecord(row.event)) {
        throw new Error('each outbox row needs id and event');
      }
      return {
        id: row.id,
        attempts: typeof row.attempts === 'number' ? row.attempts : 0,
        event: {
          eventId: String(row.event.eventId ?? ''),
          eventType: String(row.event.eventType ?? ''),
        },
        processedAt: typeof row.processedAt === 'string' ? row.processedAt : null,
      };
    }),
    pollerRunning: body.pollerRunning === true,
  };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const CHIP =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

export default function OutboxDemo() {
  const config = container.appConfig;
  const [postId, setPostId] = useState(SEED_POST);
  const [publisher, setPublisher] = useState('outbox');
  const [failBeforeCommit, setFailBeforeCommit] = useState(false);
  const [failAfterPublish, setFailAfterPublish] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [crash, setCrash] = useState<Failure | null>(null);
  const [relay, setRelay] = useState<RelayAnswer | null>(null);
  const [state, setState] = useState<StateAnswer | null>(null);
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });

  const run = (action: () => Promise<void>) => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(action).then((result) => {
      setPanel(result.ok ? { kind: 'idle' } : { kind: 'error', failure: result.failure });
    });
  };

  const refreshState = () => {
    run(async () => {
      setState(parseState(await requestJson(config, '/demo/msg/outbox/state')));
    });
  };

  useEffect(() => {
    refreshState();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const doTransactional = () => {
    setCrash(null);
    setOutcome(null);
    // NOT via safeRepositoryCall's error panel: a staged crash is the
    // PROOF, not a degraded dependency — capture the envelope and show it
    // as the crash card, then read /state for the evidence.
    void safeRepositoryCall(async () =>
      transact(config, postId, publisher, failBeforeCommit, failAfterPublish),
    ).then((result) => {
      if (result.ok) {
        setOutcome(result.value);
      } else {
        setCrash(result.failure);
      }
      refreshState();
    });
  };

  const doRelay = () =>
    run(async () => {
      const body = await requestJson(config, '/demo/msg/outbox/relay', { method: 'POST' });
      if (!isRecord(body) || typeof body.claimed !== 'number' || typeof body.published !== 'number'
        || typeof body.deadLettered !== 'number'
        || typeof body.skippedInFlight !== 'boolean' || !Array.isArray(body.streamIds)) {
        throw new Error('relay answer needs claimed, published, deadLettered, skippedInFlight');
      }
      setRelay({
        claimed: body.claimed,
        published: body.published,
        deadLettered: body.deadLettered,
        skippedInFlight: body.skippedInFlight,
        streamIds: body.streamIds.map(String),
      });
      refreshState();
    });

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          postId (seed)
          <input
            data-testid="msg-outbox-postid"
            value={postId}
            onChange={(event) => setPostId(event.target.value)}
            className={`${INPUT} w-64`}
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          publisher
          <select
            data-testid="msg-outbox-publisher"
            value={publisher}
            onChange={(event) => setPublisher(event.target.value)}
            className={INPUT}
          >
            <option value="outbox">outbox (one tx)</option>
            <option value="direct">direct (XADD after commit)</option>
          </select>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          <span className="flex items-center gap-xs">
            crash BEFORE commit
            <input
              data-testid="msg-outbox-fail-before"
              type="checkbox"
              checked={failBeforeCommit}
              onChange={(event) => setFailBeforeCommit(event.target.checked)}
            />
          </span>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          <span className="flex items-center gap-xs">
            crash AFTER publish point
            <input
              data-testid="msg-outbox-fail-after"
              type="checkbox"
              checked={failAfterPublish}
              onChange={(event) => setFailAfterPublish(event.target.checked)}
            />
          </span>
        </label>
        <button
          type="button"
          data-testid="msg-outbox-transact"
          onClick={doTransactional}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          run transactional
        </button>
      </section>

      {outcome && (
        <section
          data-testid="msg-outbox-outcome"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span className="break-all">
            {outcome.publisher} · event {outcome.eventId}
            {outcome.streamId ? ` · stream ${outcome.streamId}` : ' · queued in the outbox'}
          </span>
          <span className="break-all text-on-surface-variant">{outcome.note}</span>
        </section>
      )}
      {crash && (
        <section
          data-testid="msg-outbox-crash"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span className="text-secondary">staged crash — the 500 is the proof starting</span>
          <span className="break-all text-on-surface-variant">
            now read state: {publisher === 'outbox'
              ? 'the row survived the commit (pending +1) — relay delivers it'
              : 'the event is nowhere (the dual-write window) — relay has nothing'}
          </span>
        </section>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the outbox demo" failure={panel.failure} />}
      {panel.kind === 'loading' && (
        <p data-testid="msg-outbox-loading" className="font-mono text-caption text-on-surface-variant">
          working…
        </p>
      )}

      <section className="flex flex-wrap items-center gap-sm">
        <button
          type="button"
          data-testid="msg-outbox-relay"
          onClick={doRelay}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          run relay batch
        </button>
        <button
          type="button"
          data-testid="msg-outbox-refresh"
          onClick={refreshState}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          refresh state
        </button>
        {relay && (
          <span data-testid="msg-outbox-relay-result" className={CHIP}>
            claimed {String(relay.claimed)} · published {String(relay.published)} · dead{' '}
            {String(relay.deadLettered)}
            {relay.skippedInFlight ? ' · single-flight: another batch was running' : ''}
          </span>
        )}
      </section>

      <section className="flex flex-col gap-xs">
        <div className="flex flex-wrap items-center gap-sm">
          <p className={OVERLINE}>outbox state</p>
          {state && (
            <span data-testid="msg-outbox-stats" className={CHIP}>
              pending {String(state.stats.pending)} · delivered {String(state.stats.delivered)} ·
              dead {String(state.stats.dead)} · poller{' '}
              {state.pollerRunning ? 'live' : 'idle'}
            </span>
          )}
        </div>
        <div
          data-testid="msg-outbox-rows"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {(state?.recent ?? []).length === 0 && (
            <span className="text-on-surface-variant">no rows yet — run a transaction above</span>
          )}
          {(state?.recent ?? []).map((row) => (
            <span key={String(row.id)} className="break-all">
              #{String(row.id)} · {row.event.eventType} · attempts {String(row.attempts)} ·{' '}
              {row.processedAt ? 'processed' : 'pending'}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}
