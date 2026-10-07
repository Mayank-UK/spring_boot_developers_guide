import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `arch:eventstore` — topic 44 (advanced architecture),
 * the live half of the event-sourcing section. Two views of one stream,
 * side by side on purpose: the RAW LOG (what actually happened — facts,
 * positions, payloads) and the PROJECTION (what those facts mean the state
 * IS — items, total, status, folded on every read with the replay log
 * narrating each step). Append through the event-type buttons; checkout
 * seals the stream and every later append is refused with the store's
 * typed answer.
 *
 * A teaching widget (like every demo): it fetches through the same guarded
 * pipeline all data sources use, no dedicated repository.
 */

interface StoredEvent {
  position: number;
  eventId: string;
  streamId: string;
  type: string;
  payload: Record<string, unknown>;
}

interface Projection {
  streamId: string;
  version: number;
  items: string[];
  totalCents: number;
  status: string;
  sealed: boolean;
  replayLog: string[];
}

interface StreamSummary {
  streamId: string;
  events: number;
  sealed: boolean;
}

interface AppendAnswer {
  event: StoredEvent;
  after: Projection;
}

interface StreamAnswer {
  streamId: string;
  events: StoredEvent[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'idle' }
  | { kind: 'error'; failure: Failure };

const TYPE_CHOICES = ['cart.item-added', 'cart.item-removed', 'cart.checked-out'] as const;
const TYPE_LABELS: Record<(typeof TYPE_CHOICES)[number], string> = {
  'cart.item-added': 'item-added',
  'cart.item-removed': 'item-removed',
  'cart.checked-out': 'checked-out',
};

/* ---- runtime validation of the wire shape (nulls are dropped server-side) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`field "${key}" must be a number`);
  }
  return value;
}

function stringArray(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value)) throw new Error(`field "${key}" must be an array`);
  return value.map((entry) => String(entry));
}

function parseEvent(source: unknown): StoredEvent {
  if (!isRecord(source)) throw new Error('a stored event must be an object');
  const payload = source.payload;
  if (!isRecord(payload)) throw new Error('field "payload" must be an object');
  return {
    position: numberField(source, 'position'),
    eventId: stringField(source, 'eventId'),
    streamId: stringField(source, 'streamId'),
    type: stringField(source, 'type'),
    payload,
  };
}

function parseProjection(source: unknown): Projection {
  if (!isRecord(source)) throw new Error('a projection must be an object');
  return {
    streamId: stringField(source, 'streamId'),
    version: numberField(source, 'version'),
    items: stringArray(source, 'items'),
    totalCents: numberField(source, 'totalCents'),
    status: stringField(source, 'status'),
    sealed: source.sealed === true,
    replayLog: stringArray(source, 'replayLog'),
  };
}

function parseAppend(source: unknown): AppendAnswer {
  if (!isRecord(source)) throw new Error('an append answer must be an object');
  return { event: parseEvent(source.event), after: parseProjection(source.after) };
}

function parseStream(source: unknown): StreamAnswer {
  if (!isRecord(source)) throw new Error('a stream answer must be an object');
  const events = source.events;
  if (!Array.isArray(events)) throw new Error('field "events" must be an array');
  return { streamId: stringField(source, 'streamId'), events: events.map(parseEvent) };
}

function parseSummaries(source: unknown): StreamSummary[] {
  const streams = source;
  if (!Array.isArray(streams)) throw new Error('the streams answer must be an array');
  return streams.map((entry) => {
    if (!isRecord(entry)) throw new Error('a stream summary must be an object');
    return {
      streamId: stringField(entry, 'streamId'),
      events: numberField(entry, 'events'),
      sealed: entry.sealed === true,
    };
  });
}

/** Fetch a path as JSON, guarding transport + envelope like every demo. */
async function requestJson(
  config: AppConfig,
  path: string,
  init?: RequestInit,
): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      ...init,
      headers: { 'content-type': 'application/json' },
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
    const envelope = isRecord(parsed) && isRecord(parsed.error) ? parsed.error : null;
    throw new ServerException(
      envelope && typeof envelope.message === 'string'
        ? envelope.message
        : `Request failed with status ${String(response.status)}.`,
      { status: response.status },
    );
  }
  return parsed;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const PILL =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const ACTIVE_PILL =
  'rounded-pill border border-outline bg-surface-tint px-sm py-xxs font-mono text-caption text-on-surface';
const CELL = 'border-b border-outline px-xs py-xs text-left';

export default function EventStoreDemo() {
  const config = container.appConfig;
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const [stream, setStream] = useState('cart-1');
  const [type, setType] = useState<(typeof TYPE_CHOICES)[number]>('cart.item-added');
  const [item, setItem] = useState('mug');
  const [priceCents, setPriceCents] = useState('1200');
  const [raw, setRaw] = useState<StreamAnswer | null>(null);
  const [projection, setProjection] = useState<Projection | null>(null);
  const [streams, setStreams] = useState<StreamSummary[]>([]);
  const [flash, setFlash] = useState<string | null>(null);

  /**
   * Load the whole demo view: the stream inventory first, then the raw log
   * and projection for the chosen stream when it EXISTS — a stream with no
   * history yet renders the empty placeholder, not an error (an empty log
   * folds to an empty state; that is the honest first view of a new cart).
   */
  const load = useCallback(async (streamId: string) => {
    setPanel({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const summaries = parseSummaries(
        await requestJson(config, '/demo/arch/eventstore/streams'),
      );
      const known = summaries.some((summary) => summary.streamId === streamId);
      return {
        summaries,
        raw: known
          ? parseStream(await requestJson(config, `/demo/arch/eventstore/stream/${streamId}`))
          : null,
        projection: known
          ? parseProjection(
              await requestJson(config, `/demo/arch/eventstore/state/${streamId}`),
            )
          : null,
      };
    });
    if (result.ok) {
      setStreams(result.value.summaries);
      setRaw(result.value.raw);
      setProjection(result.value.projection);
      setPanel({ kind: 'idle' });
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
  }, [config]);

  useEffect(() => {
    void load(stream);
  }, [load, stream]);

  /** Append one fact to the chosen stream, then reload the whole view. */
  const append = useCallback(() => {
    const path =
      type === 'cart.item-added'
        ? `/demo/arch/eventstore/append?stream=${stream}&type=${type}&item=${encodeURIComponent(item)}&priceCents=${priceCents}`
        : type === 'cart.item-removed'
          ? `/demo/arch/eventstore/append?stream=${stream}&type=${type}&item=${encodeURIComponent(item)}`
          : `/demo/arch/eventstore/append?stream=${stream}&type=${type}`;
    setPanel({ kind: 'loading' });
    setFlash(null);
    void safeRepositoryCall(async () =>
      parseAppend(await requestJson(config, path, { method: 'POST' })),
    ).then((result) => {
      if (result.ok) {
        setFlash(
          `${result.value.event.eventId} stored at position ${result.value.event.position} — the log grew by one fact`,
        );
        void load(stream);
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config, stream, type, item, priceCents, load]);

  const needsItem = type === 'cart.item-added' || type === 'cart.item-removed';

  return (
    <div className="flex flex-col gap-md">
      {/* ---- the append controls ---- */}
      <div className="flex flex-col gap-xs">
        <p className={OVERLINE}>one stream, two views: the raw log, and the state it folds to</p>
        <div className="flex flex-wrap items-center gap-xs" data-testid="eventstore-controls">
          <label className="text-caption text-on-surface-variant" htmlFor="eventstore-stream">
            stream
          </label>
          <input
            id="eventstore-stream"
            data-testid="eventstore-stream"
            value={stream}
            onChange={(event) => setStream(event.target.value)}
            className="w-28 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          {TYPE_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              onClick={() => setType(choice)}
              className={type === choice ? ACTIVE_PILL : PILL}
            >
              {TYPE_LABELS[choice]}
            </button>
          ))}
          {needsItem && (
            <input
              aria-label="item"
              data-testid="eventstore-item"
              value={item}
              onChange={(event) => setItem(event.target.value)}
              className="w-24 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          )}
          {type === 'cart.item-added' && (
            <input
              aria-label="price cents"
              data-testid="eventstore-price"
              value={priceCents}
              onChange={(event) => setPriceCents(event.target.value)}
              className="w-20 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          )}
          <button
            type="button"
            data-testid="eventstore-append"
            onClick={append}
            className="rounded-pill border border-outline bg-surface-tint px-md py-xxs text-body-sm text-on-surface transition-colors hover:bg-surface-tint/70"
          >
            append
          </button>
        </div>
        {flash && (
          <p data-testid="eventstore-flash" className="font-mono text-caption text-on-surface">
            {flash}
          </p>
        )}
      </div>

      {panel.kind === 'loading' && (
        <p data-testid="eventstore-loading" className="text-body-sm text-on-surface-variant">
          folding the log…
        </p>
      )}
      {panel.kind === 'error' && (
        <ErrorPanel subject="the event store" failure={panel.failure} />
      )}

      {panel.kind === 'idle' && !raw && !projection && (
        <p data-testid="eventstore-empty" className="text-body-sm text-on-surface-variant">
          this stream has no history yet — the log is the state, and the log is empty. Append the
          first fact.
        </p>
      )}

      {raw && projection && panel.kind === 'idle' && (
        <>
          {/* ---- the raw log ---- */}
          <div className="flex flex-col gap-xxs overflow-x-auto">
            <p className={OVERLINE}>the raw log — what actually happened, nothing derived</p>
            <table data-testid="eventstore-log" className="w-full border-collapse text-body-sm">
              <thead>
                <tr className="text-left text-on-surface-variant">
                  <th className={CELL}>pos</th>
                  <th className={CELL}>id</th>
                  <th className={CELL}>type</th>
                  <th className={CELL}>payload</th>
                </tr>
              </thead>
              <tbody>
                {raw.events.map((event) => (
                  <tr key={event.eventId} data-testid="eventstore-event-row">
                    <td className={`${CELL} font-mono`}>{event.position}</td>
                    <td className={`${CELL} font-mono`}>{event.eventId}</td>
                    <td className={`${CELL} font-mono`}>{event.type}</td>
                    <td className={`${CELL} font-mono text-caption`}>
                      {Object.entries(event.payload)
                        .map(([key, value]) => `${key}=${String(value)}`)
                        .join(' ')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* ---- the projection ---- */}
          <div className="flex flex-col gap-xxs">
            <p className={OVERLINE}>the projection — state, replayed from the log on every read</p>
            <p data-testid="eventstore-projection" className="font-mono text-body-sm text-on-surface">
              v{projection.version} · items [{projection.items.join(', ')}] · total{' '}
              {projection.totalCents}¢ · {projection.status}
              {projection.sealed ? ' (sealed)' : ''}
            </p>
            <ul className="flex flex-col gap-xxs">
              {projection.replayLog.map((line, index) => (
                <li
                  key={index}
                  data-testid={`eventstore-replay-${index}`}
                  className="font-mono text-caption text-on-surface-variant"
                >
                  {line}
                </li>
              ))}
            </ul>
          </div>

          {/* ---- the inventory ---- */}
          {streams.length > 0 && (
            <div className="flex flex-col gap-xxs">
              <p className={OVERLINE}>every stream the store owns</p>
              <ul data-testid="eventstore-streams" className="flex flex-wrap gap-xxs">
                {streams.map((summary) => (
                  <li
                    key={summary.streamId}
                    data-testid="eventstore-stream-chip"
                    className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
                  >
                    {summary.streamId} · {summary.events} events
                    {summary.sealed ? ' · sealed' : ''}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </div>
  );
}
