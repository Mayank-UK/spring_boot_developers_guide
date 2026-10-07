import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `msg:stream` — topic 22 (messaging), the
 * consumer-groups section. The console over the demo stream's consumer
 * group (group guide-demo, stream guide:demo:stream:events):
 *
 * - publish XADDs an event and answers the stream id (millis-sequence);
 * - consume XREADGROUPs into worker-1 and runs every delivery through the
 *   idempotent consumer — each row is flagged first-delivery or DUPLICATE,
 *   and the counters show the projection moving exactly once per event;
 *   ack=false stages the crash window (delivered, never acked);
 * - pending is XPENDING: who holds what, for how long (idle), delivered how
 *   many times — the crash-window ledger;
 * - claim is the visibility-timeout takeover: entries idle past minIdleMs
 *   move to worker-2 (XPENDING idle-filtered + XCLAIM); entries past the
 *   delivery threshold are dead-lettered;
 * - dlq lists the dead-letter stream.
 */

const EVENT_TYPES = ['demo.ping', 'post.published', 'post.title-touched'] as const;

interface PublishAnswer {
  streamId: string;
  stream: string;
  eventId: string;
  eventType: string;
}

interface DeliveryOutcome {
  id: string;
  duplicate: boolean;
  event: { eventId: string; eventType: string; aggregateId: string };
}

interface ConsumeAnswer {
  deliveries: DeliveryOutcome[];
  acked: number;
  counters: Record<string, number>;
  duplicatesSeen: number;
}

interface PendingEntry {
  id: string;
  consumer: string;
  idleMs: number;
  deliveryCount: number;
}

interface PendingAnswer {
  total: number;
  perConsumer: Record<string, number>;
  entries: PendingEntry[];
}

interface ClaimAnswer {
  claimedBy: string;
  minIdleMs: number;
  reprocessed: DeliveryOutcome[];
  deadLettered: { id: string; eventId: string; reason: string }[];
  acked: number;
}

interface DlqAnswer {
  entries: { id: string; eventId: string; eventType: string; reason: string | null }[];
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

async function publishEvent(
  config: AppConfig,
  eventType: string,
  payloadJson: string,
): Promise<PublishAnswer> {
  const payload = payloadJson.trim() === ''
    ? {}
    : (JSON.parse(payloadJson) as Record<string, unknown>);
  const body = await requestJson(config, '/demo/msg/stream/publish', {
    method: 'POST',
    body: JSON.stringify({ eventType, payload }),
  });
  if (!isRecord(body) || typeof body.streamId !== 'string' || typeof body.eventId !== 'string') {
    throw new Error('publish answer needs streamId and eventId');
  }
  return {
    streamId: body.streamId,
    stream: typeof body.stream === 'string' ? body.stream : '',
    eventId: body.eventId,
    eventType: typeof body.eventType === 'string' ? body.eventType : eventType,
  };
}

function parseConsume(body: unknown): ConsumeAnswer {
  if (!isRecord(body) || !Array.isArray(body.deliveries) || !isRecord(body.counters)) {
    throw new Error('consume answer needs deliveries and counters');
  }
  return {
    deliveries: body.deliveries.map((row) => {
      if (
        !isRecord(row) ||
        !isRecord(row.event) ||
        typeof row.id !== 'string' ||
        typeof row.duplicate !== 'boolean'
      ) {
        throw new Error('each delivery needs id, event, duplicate');
      }
      return {
        id: row.id,
        duplicate: row.duplicate,
        event: {
          eventId: String(row.event.eventId ?? ''),
          eventType: String(row.event.eventType ?? ''),
          aggregateId: String(row.event.aggregateId ?? ''),
        },
      };
    }),
    acked: typeof body.acked === 'number' ? body.acked : 0,
    counters: Object.fromEntries(
      Object.entries(body.counters).map(([key, value]) => [key, Number(value)]),
    ),
    duplicatesSeen: typeof body.duplicatesSeen === 'number' ? body.duplicatesSeen : 0,
  };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const CHIP =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

export default function StreamDemo() {
  const config = container.appConfig;
  const [eventType, setEventType] = useState<string>('demo.ping');
  const [payloadJson, setPayloadJson] = useState('{"note":"hello stream"}');
  const [lastPublished, setLastPublished] = useState<PublishAnswer | null>(null);
  const [consumeAck, setConsumeAck] = useState(true);
  const [replayPending, setReplayPending] = useState(false);
  const [consume, setConsume] = useState<ConsumeAnswer | null>(null);
  const [pending, setPending] = useState<PendingAnswer | null>(null);
  const [minIdleMs, setMinIdleMs] = useState('0');
  const [claimAck, setClaimAck] = useState(true);
  const [claim, setClaim] = useState<ClaimAnswer | null>(null);
  const [dlq, setDlq] = useState<DlqAnswer | null>(null);
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });

  const run = (action: () => Promise<void>) => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(action).then((result) => {
      setPanel(result.ok ? { kind: 'idle' } : { kind: 'error', failure: result.failure });
    });
  };

  const refreshLedgers = () => {
    run(async () => {
      const [pendingBody, dlqBody] = await Promise.all([
        requestJson(config, '/demo/msg/stream/pending'),
        requestJson(config, '/demo/msg/stream/dlq'),
      ]);
      if (!isRecord(pendingBody) || typeof pendingBody.total !== 'number'
        || !Array.isArray(pendingBody.entries) || !isRecord(pendingBody.perConsumer)) {
        throw new Error('pending needs total, entries, perConsumer');
      }
      setPending({
        total: pendingBody.total,
        perConsumer: Object.fromEntries(
          Object.entries(pendingBody.perConsumer).map(([key, value]) => [key, Number(value)]),
        ),
        entries: pendingBody.entries.map((row) => {
          if (!isRecord(row) || typeof row.id !== 'string' || typeof row.consumer !== 'string'
            || typeof row.idleMs !== 'number' || typeof row.deliveryCount !== 'number') {
            throw new Error('each pending entry needs id, consumer, idleMs, deliveryCount');
          }
          return {
            id: row.id,
            consumer: row.consumer,
            idleMs: row.idleMs,
            deliveryCount: row.deliveryCount,
          };
        }),
      });
      if (!isRecord(dlqBody) || !Array.isArray(dlqBody.entries)) {
        throw new Error('dlq needs entries');
      }
      setDlq({
        entries: dlqBody.entries.map((row) => {
          if (!isRecord(row) || typeof row.id !== 'string') {
            throw new Error('each dlq entry needs id');
          }
          return {
            id: row.id,
            eventId: String(row.eventId ?? ''),
            eventType: String(row.eventType ?? ''),
            reason: typeof row.reason === 'string' ? row.reason : null,
          };
        }),
      });
    });
  };

  useEffect(() => {
    refreshLedgers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const doPublish = () =>
    run(async () => {
      setLastPublished(await publishEvent(config, eventType, payloadJson));
      refreshLedgers();
    });

  const doConsume = () =>
    run(async () => {
      const search = `?max=10&ack=${String(consumeAck)}&replayPending=${String(replayPending)}`;
      setConsume(parseConsume(await requestJson(config, `/demo/msg/stream/consume${search}`, {
        method: 'POST',
      })));
      refreshLedgers();
    });

  const doClaim = () =>
    run(async () => {
      const search = `?minIdleMs=${encodeURIComponent(minIdleMs)}&ack=${String(claimAck)}`;
      const body = await requestJson(config, `/demo/msg/stream/claim${search}`, { method: 'POST' });
      if (!isRecord(body) || !Array.isArray(body.reprocessed) || !Array.isArray(body.deadLettered)
        || typeof body.claimedBy !== 'string' || typeof body.acked !== 'number') {
        throw new Error('claim needs claimedBy, reprocessed, deadLettered, acked');
      }
      setClaim({
        claimedBy: body.claimedBy,
        minIdleMs: typeof body.minIdleMs === 'number' ? body.minIdleMs : Number(minIdleMs),
        reprocessed: body.reprocessed.map((row) => {
          if (!isRecord(row) || typeof row.id !== 'string' || typeof row.duplicate !== 'boolean') {
            throw new Error('each reprocessed row needs id, duplicate');
          }
          return {
            id: row.id,
            duplicate: row.duplicate,
            event: { eventId: '', eventType: '', aggregateId: '' },
          };
        }),
        deadLettered: body.deadLettered.map((row) => {
          if (!isRecord(row) || typeof row.id !== 'string') {
            throw new Error('each dead-lettered row needs id');
          }
          return {
            id: row.id,
            eventId: String(row.eventId ?? ''),
            reason: typeof row.reason === 'string' ? row.reason : '',
          };
        }),
        acked: body.acked,
      });
      refreshLedgers();
    });

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          eventType
          <select
            data-testid="msg-stream-event-type"
            value={eventType}
            onChange={(event) => setEventType(event.target.value)}
            className={INPUT}
          >
            {EVENT_TYPES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          payload (json)
          <input
            data-testid="msg-stream-payload"
            value={payloadJson}
            onChange={(event) => setPayloadJson(event.target.value)}
            className={`${INPUT} w-56`}
          />
        </label>
        <button
          type="button"
          data-testid="msg-stream-publish"
          onClick={doPublish}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          publish (XADD)
        </button>
        {lastPublished && (
          <span data-testid="msg-stream-published" className={CHIP}>
            {lastPublished.eventType} → {lastPublished.streamId}
          </span>
        )}
      </section>

      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          <span className="flex items-center gap-xs">
            ack after processing
            <input
              data-testid="msg-stream-ack"
              type="checkbox"
              checked={consumeAck}
              onChange={(event) => setConsumeAck(event.target.checked)}
            />
          </span>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          <span className="flex items-center gap-xs">
            replay my pending (offset 0)
            <input
              data-testid="msg-stream-replay"
              type="checkbox"
              checked={replayPending}
              onChange={(event) => setReplayPending(event.target.checked)}
            />
          </span>
        </label>
        <button
          type="button"
          data-testid="msg-stream-consume"
          onClick={doConsume}
          className="rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface"
        >
          consume (XREADGROUP)
        </button>
        {consume && (
          <span data-testid="msg-stream-consumed" className={CHIP}>
            {String(consume.deliveries.length)} delivered · {String(consume.acked)} acked ·
            {' '}dupes seen {String(consume.duplicatesSeen)}
          </span>
        )}
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="msg-stream-loading" className="font-mono text-caption text-on-surface-variant">
          working…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the stream console" failure={panel.failure} />}

      {consume && (
        <section
          data-testid="msg-stream-deliveries"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {consume.deliveries.map((row) => (
            <span key={row.id} className="break-all">
              {row.id} · {row.event.eventType}{' '}
              {row.duplicate ? (
                <span className="text-on-surface-variant">[DUPLICATE — skipped]</span>
              ) : (
                <span className="text-secondary">[applied]</span>
              )}
            </span>
          ))}
          <span data-testid="msg-stream-counters" className="text-on-surface-variant">
            counters: {Object.entries(consume.counters)
              .map(([type, count]) => `${type}=${String(count)}`)
              .join(' · ')}
          </span>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <div className="flex flex-wrap items-center gap-sm">
          <p className={OVERLINE}>pending (XPENDING)</p>
          <span data-testid="msg-stream-pending-total" className={CHIP}>
            total {String(pending?.total ?? 0)}
          </span>
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            min idle ms
            <input
              data-testid="msg-stream-minidle"
              type="number"
              min={0}
              value={minIdleMs}
              onChange={(event) => setMinIdleMs(event.target.value)}
              className={`${INPUT} w-24`}
            />
          </label>
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            <span className="flex items-center gap-xs">
              ack after claim
              <input
                data-testid="msg-stream-claim-ack"
                type="checkbox"
                checked={claimAck}
                onChange={(event) => setClaimAck(event.target.checked)}
              />
            </span>
          </label>
          <button
            type="button"
            data-testid="msg-stream-claim"
            onClick={doClaim}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            claim (XCLAIM)
          </button>
          <button
            type="button"
            data-testid="msg-stream-refresh"
            onClick={refreshLedgers}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
          >
            refresh
          </button>
        </div>
        <div
          data-testid="msg-stream-pending-entries"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {(pending?.entries ?? []).length === 0 && (
            <span className="text-on-surface-variant">nothing pending — every delivery acked</span>
          )}
          {(pending?.entries ?? []).map((entry) => (
            <span key={entry.id} className="break-all">
              {entry.id} · held by {entry.consumer} · idle {String(entry.idleMs)}ms · delivered{' '}
              {String(entry.deliveryCount)}×
            </span>
          ))}
          {claim && (
            <span data-testid="msg-stream-claim-result" className="break-all text-on-surface-variant">
              claim → {claim.claimedBy}: {String(claim.reprocessed.length)} reprocessed,{' '}
              {String(claim.deadLettered.length)} dead-lettered, {String(claim.acked)} acked
            </span>
          )}
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>dead letters (the DLQ stream)</p>
        <div
          data-testid="msg-stream-dlq"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {(dlq?.entries ?? []).length === 0 && (
            <span className="text-on-surface-variant">empty — poison only, we promise</span>
          )}
          {(dlq?.entries ?? []).map((entry) => (
            <span key={entry.id} className="break-all">
              {entry.eventType} · {entry.eventId} · {entry.reason}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}
