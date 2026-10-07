import { useCallback, useEffect, useRef, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `api:webhook-simulator` — topic 14 (webhooks), the
 * delivering section. The console over the in-app loopback simulator:
 * - subscriptions list (create one; the secret is shown once at creation and
 *   masked everywhere after — the masked-listing discipline, live)
 * - fire form: event type, payload JSON, and the receiver behavior script
 *   (none | 200 | 500 | timeout) that makes the failure descent deterministic
 * - deliveries timeline: one row per event, every attempt with its outcome
 *   and HTTP status, a live backoff countdown while pending, and the DLQ
 *   badge when a delivery lands dead-lettered
 * - receipts: what the receiver actually saw — signature verdicts, duplicate
 *   flags, the status it answered with
 *
 * The console is a window, not a teacher: all policy lives server-side. It
 * polls the timeline once per second WHILE anything is pending and stops
 * when the queue drains.
 */

const POLL_INTERVAL_MS = 1000;

interface SubscriptionRow {
  id: string;
  url: string;
  secretMasked: string;
  eventTypes: string[];
  active: boolean;
  createdAt: string;
}

interface CreatedSubscription extends SubscriptionRow {
  secret: string;
  urlDefaulted: boolean;
}

interface AttemptRow {
  n: number;
  at: string;
  outcome: string;
  httpStatus: number | null;
  signatureHeader: string | null;
}

interface DeliveryRow {
  id: string;
  eventId: string;
  eventType: string;
  state: string;
  payload: string;
  attempts: AttemptRow[];
  nextAttemptAt: string | null;
  attemptsLeft: number;
}

interface ReceiptRow {
  eventId: string;
  eventType: string;
  signatureVerdict: string;
  signatureReason: string;
  duplicate: boolean;
  respondedStatus: number;
  note: string;
  receivedAt: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure }
  | { kind: 'ready' };

/* ---- the guarded pipeline (arena-demo shape, one helper for all routes) ---- */

async function apiJson<T>(
  config: AppConfig,
  path: string,
  init: RequestInit | undefined,
  parse: (body: unknown) => T,
): Promise<{ ok: true; value: T } | { ok: false; failure: Failure }> {
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}${path}`, init);
    } catch (error) {
      throw new NetworkException('Could not reach the server.', { cause: error });
    }
    let parsed: unknown;
    try {
      parsed = await response.json();
    } catch (error) {
      throw new ServerException(
        `Request failed with status ${String(response.status)}.`,
        { status: response.status, cause: error },
      );
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
  if (result.ok) return { ok: true, value: result.value };
  return { ok: false, failure: result.failure };
}

/* ---- runtime validation of the stable wire shape (the client's mirror) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function requireString(row: unknown, field: string): string {
  if (!isRecord(row) || typeof row[field] !== 'string') {
    throw new Error(`field ${field} must be a string`);
  }
  return row[field];
}

function parseSubscriptions(body: unknown): SubscriptionRow[] {
  if (!isRecord(body) || !Array.isArray(body.subscriptions)) {
    throw new Error('subscriptions response needs a subscriptions array');
  }
  return body.subscriptions.map((row) => ({
    id: requireString(row, 'id'),
    url: requireString(row, 'url'),
    secretMasked: requireString(row, 'secretMasked'),
    eventTypes:
      isRecord(row) && Array.isArray(row.eventTypes) ? row.eventTypes.map(String) : [],
    active: isRecord(row) ? row.active === true : false,
    createdAt: requireString(row, 'createdAt'),
  }));
}

function parseDeliveries(body: unknown): DeliveryRow[] {
  if (!isRecord(body) || !Array.isArray(body.deliveries)) {
    throw new Error('deliveries response needs a deliveries array');
  }
  return body.deliveries.map((row) => {
    if (!isRecord(row)) throw new Error('each delivery must be an object');
    return {
      id: requireString(row, 'id'),
      eventId: requireString(row, 'eventId'),
      eventType: requireString(row, 'eventType'),
      state: requireString(row, 'state'),
      payload: requireString(row, 'payload'),
      attempts: Array.isArray(row.attempts)
        ? row.attempts.map((attempt) => {
            if (!isRecord(attempt)) throw new Error('each attempt must be an object');
            return {
              n: typeof attempt.n === 'number' ? attempt.n : 0,
              at: requireString(attempt, 'at'),
              outcome: requireString(attempt, 'outcome'),
              httpStatus:
                typeof attempt.httpStatus === 'number' ? attempt.httpStatus : null,
              signatureHeader:
                typeof attempt.signatureHeader === 'string' ? attempt.signatureHeader : null,
            };
          })
        : [],
      nextAttemptAt: typeof row.nextAttemptAt === 'string' ? row.nextAttemptAt : null,
      attemptsLeft: typeof row.attemptsLeft === 'number' ? row.attemptsLeft : 0,
    };
  });
}

function parseReceipts(body: unknown): ReceiptRow[] {
  if (!isRecord(body) || !Array.isArray(body.receipts)) {
    throw new Error('receipts response needs a receipts array');
  }
  return body.receipts.map((row) => ({
    eventId: requireString(row, 'eventId'),
    eventType: requireString(row, 'eventType'),
    signatureVerdict: requireString(row, 'signatureVerdict'),
    signatureReason: requireString(row, 'signatureReason'),
    duplicate: isRecord(row) ? row.duplicate === true : false,
    respondedStatus: isRecord(row) && typeof row.respondedStatus === 'number'
      ? row.respondedStatus
      : 0,
    note: requireString(row, 'note'),
    receivedAt: requireString(row, 'receivedAt'),
  }));
}

/* ---- the poll: live while work is in flight, silent when drained ---- */

let pollTimer: ReturnType<typeof setTimeout> | null = null;

function cancelPoll(): void {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function schedulePoll(deliveries: DeliveryRow[], refresh: () => void): void {
  cancelPoll();
  const hasPending = deliveries.some(
    (delivery) => delivery.state === 'pending' || delivery.state === 'delivering',
  );
  if (hasPending) {
    pollTimer = setTimeout(refresh, POLL_INTERVAL_MS);
  }
}

/* ---- small presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

function StateBadge({ state }: { state: string }) {
  const dead = state === 'dead-lettered';
  const delivered = state === 'delivered';
  return (
    <span
      data-testid="wh-state-badge"
      className={`rounded-pill border px-xs py-xxs ${
        dead
          ? 'border-error text-error'
          : delivered
            ? 'border-outline text-on-surface-variant'
            : 'border-secondary text-on-surface'
      }`}
    >
      {state}
    </span>
  );
}

function secondsUntil(iso: string): number {
  return Math.max(0, Math.round((Date.parse(iso) - Date.now()) / 1000));
}

export default function WebhookSimulatorDemo() {
  const [subs, setSubs] = useState<SubscriptionRow[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [deliveries, setDeliveries] = useState<DeliveryRow[]>([]);
  const [receipts, setReceipts] = useState<ReceiptRow[]>([]);
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });

  const [secret, setSecret] = useState('whsec_demo_console_secret_01');
  const [events, setEvents] = useState('invoice.paid');
  const [eventType, setEventType] = useState('invoice.paid');
  const [payload, setPayload] = useState('{\n  "amount": 4200,\n  "currency": "usd"\n}');
  const [respond, setRespond] = useState('');
  const selectedIdRef = useRef<string | null>(null);
  const config = container.appConfig;

  const refresh = useCallback(async (): Promise<void> => {
    const target = selectedIdRef.current;
    const jobs: Promise<void>[] = [
      apiJson(config, '/demo/webhooks/subscriptions', undefined, parseSubscriptions).then(
        (result) => {
          if (!result.ok) return;
          setSubs(result.value);
          // auto-select the first subscription so the console is usable on arrival
          const first = result.value[0];
          if (selectedIdRef.current === null && first !== undefined) {
            selectedIdRef.current = first.id;
            setSelectedId(first.id);
            void apiJson(
              config,
              `/demo/webhooks/subscriptions/${first.id}/deliveries`,
              undefined,
              parseDeliveries,
            ).then((loaded) => {
              if (loaded.ok) {
                setDeliveries(loaded.value);
                schedulePoll(loaded.value, refresh);
              }
            });
          }
        },
      ),
      apiJson(config, '/demo/webhooks/receipts', undefined, parseReceipts).then((result) => {
        if (result.ok) setReceipts(result.value);
      }),
    ];
    if (target !== null) {
      jobs.push(
        apiJson(
          config,
          `/demo/webhooks/subscriptions/${target}/deliveries`,
          undefined,
          parseDeliveries,
        ).then((result) => {
          if (result.ok) {
            setDeliveries(result.value);
            schedulePoll(result.value, refresh);
          }
        }),
      );
    }
    await Promise.all(jobs);
  }, [config]);

  useEffect(() => {
    selectedIdRef.current = selectedId;
  }, [selectedId]);

  useEffect(() => {
    void refresh().then(() => setPanel({ kind: 'ready' }));
    return cancelPoll;
  }, [refresh]);

  const selectSubscription = (id: string) => {
    setSelectedId(id);
    selectedIdRef.current = id;
    setDeliveries([]);
    void apiJson(config, `/demo/webhooks/subscriptions/${id}/deliveries`, undefined, parseDeliveries).then(
      (result) => {
        if (result.ok) {
          setDeliveries(result.value);
          schedulePoll(result.value, refresh);
        }
      },
    );
  };

  const subscribe = () => {
    const body = JSON.stringify({
      secret,
      eventTypes: events
        .split(',')
        .map((type) => type.trim())
        .filter((type) => type.length > 0),
    });
    void apiJson(config, '/demo/webhooks/subscriptions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    }, (created) => {
      const row = created as CreatedSubscription;
      if (typeof row.id !== 'string') throw new Error('created subscription needs an id');
      return row;
    }).then((result) => {
      if (!result.ok) {
        setPanel({ kind: 'error', failure: result.failure });
        return;
      }
      setPanel({ kind: 'ready' });
      selectSubscription(result.value.id);
      void refresh();
    });
  };

  const fire = () => {
    const target = selectedIdRef.current;
    if (target === null) return;
    // the payload rides as typed: the sender's envelope is the server's job,
    // and a malformed payload is the server's 400 to raise (hostile-input premise)
    const payloadJson = payload.trim() === '' ? '{}' : payload;
    const body =
      `{"eventType":${JSON.stringify(eventType)},` +
      `"payload":${payloadJson}` +
      (respond === '' ? '' : `,"respond":${JSON.stringify(respond)}`) +
      '}';
    void apiJson(
      config,
      `/demo/webhooks/subscriptions/${target}/fire`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body },
      parseDelivery,
    ).then((result) => {
      if (!result.ok) {
        setPanel({ kind: 'error', failure: result.failure });
        return;
      }
      setPanel({ kind: 'ready' });
      void refresh();
      schedulePoll([result.value], refresh);
    });
  };

  const selected = subs.find((sub) => sub.id === selectedId) ?? null;

  return (
    <div className="flex flex-col gap-md">
      {/* Subscriptions: create + list. The secret shows once, then never. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>subscriptions — the consumer endpoints</p>
        <div className="flex flex-wrap items-end gap-xs">
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">secret</span>
            <input
              data-testid="wh-secret-input"
              value={secret}
              onChange={(event) => setSecret(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">
              event types (comma-separated)
            </span>
            <input
              data-testid="wh-events-input"
              value={events}
              onChange={(event) => setEvents(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
          <button
            type="button"
            data-testid="wh-subscribe"
            onClick={subscribe}
            className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
          >
            subscribe
          </button>
        </div>
        <div data-testid="wh-subs" className="flex flex-col gap-xxs">
          {subs.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">
              no subscriptions yet — subscribe to point the sender at the in-app receiver
            </p>
          )}
          {subs.map((sub) => (
            <button
              key={sub.id}
              type="button"
              data-testid="wh-sub-row"
              onClick={() => selectSubscription(sub.id)}
              className={`flex flex-wrap items-baseline gap-xs rounded-sm border px-xs py-xxs text-left font-mono text-caption transition-colors ${
                sub.id === selectedId
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant hover:bg-surface-tint'
              }`}
            >
              <span>{sub.id}</span>
              <span className="text-on-surface-variant">{sub.secretMasked}</span>
              <span>{sub.eventTypes.join(', ')}</span>
              <span className="text-on-surface-variant">{sub.url}</span>
            </button>
          ))}
        </div>
      </section>

      {/* Fire form: the event, and the receiver's scripted behavior. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>
          fire an event{selected ? ` at ${selected.id}` : ' — select a subscription first'}
        </p>
        <div className="flex flex-wrap items-end gap-xs">
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">eventType</span>
            <input
              data-testid="wh-event-input"
              value={eventType}
              onChange={(event) => setEventType(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">
              receiver behavior
            </span>
            <select
              data-testid="wh-respond-select"
              value={respond}
              onChange={(event) => setRespond(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            >
              <option value="">default (200)</option>
              <option value="200">200</option>
              <option value="500">500</option>
              <option value="timeout">timeout</option>
            </select>
          </label>
          <button
            type="button"
            data-testid="wh-fire"
            onClick={fire}
            disabled={selected === null}
            className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface disabled:opacity-50"
          >
            fire
          </button>
        </div>
        <textarea
          data-testid="wh-payload-input"
          rows={4}
          value={payload}
          onChange={(event) => setPayload(event.target.value)}
          className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
        />
        <p className="text-caption text-on-surface-variant">
          payload JSON — the sender wraps it in the event envelope (id, type, occurredAt) and
          signs those exact bytes
        </p>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the webhook console" failure={panel.failure} />}

      {/* Deliveries: the sender's timeline. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>deliveries — attempts, backoff, dead-letter</p>
        <div data-testid="wh-deliveries" className="flex flex-col gap-xs">
          {selectedId !== null && deliveries.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">nothing fired yet</p>
          )}
          {deliveries.map((delivery) => (
            <div
              key={delivery.id}
              data-testid="wh-delivery-row"
              className="flex flex-col gap-xxs rounded-sm border border-outline px-xs py-xs"
            >
              <div className="flex flex-wrap items-center gap-xs font-mono text-caption">
                <span className="text-on-surface">{delivery.eventType}</span>
                <span className="text-on-surface-variant">{delivery.eventId}</span>
                <StateBadge state={delivery.state} />
                {delivery.state === 'dead-lettered' && (
                  <span
                    data-testid="wh-dlq-badge"
                    className="rounded-pill border border-error bg-error/15 px-xs py-xxs font-semibold text-error"
                  >
                    DLQ
                  </span>
                )}
                {delivery.nextAttemptAt !== null && (
                  <span data-testid="wh-countdown" className="text-on-surface-variant">
                    next attempt in {secondsUntil(delivery.nextAttemptAt)}s
                  </span>
                )}
              </div>
              <div className="flex flex-col gap-xxs">
                {delivery.attempts.map((attempt) => (
                  <div
                    key={attempt.n}
                    data-testid="wh-attempt-row"
                    className="flex flex-wrap items-baseline gap-xs font-mono text-caption"
                  >
                    <span className="text-on-surface-variant">#{attempt.n}</span>
                    <span>{new Date(attempt.at).toLocaleTimeString()}</span>
                    <span
                      className={
                        attempt.outcome === 'delivered'
                          ? 'text-on-surface-variant'
                          : 'text-error'
                      }
                    >
                      {attempt.outcome}
                    </span>
                    <span className="text-on-surface-variant">
                      {attempt.httpStatus === null ? 'no response' : `HTTP ${attempt.httpStatus}`}
                    </span>
                  </div>
                ))}
              </div>
              <details className="font-mono text-caption text-on-surface-variant">
                <summary className="cursor-pointer">envelope bytes</summary>
                <pre className="overflow-x-auto rounded-sm border border-outline bg-surface px-xs py-xxs text-on-surface">
                  {delivery.payload}
                </pre>
              </details>
            </div>
          ))}
        </div>
      </section>

      {/* Receipts: the receiver's log, beside the sender's view. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>receipts — what the receiver saw</p>
        <div data-testid="wh-receipts" className="flex flex-col gap-xxs">
          {receipts.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">the receiver has seen nothing yet</p>
          )}
          {receipts.map((receipt, index) => (
            <div
              key={`${receipt.eventId}-${index}`}
              data-testid="wh-receipt-row"
              className="flex flex-wrap items-baseline gap-xs font-mono text-caption"
            >
              <span
                data-testid="wh-receipt-verdict"
                className={
                  receipt.signatureVerdict === 'verified'
                    ? 'text-on-surface-variant'
                    : 'text-error'
                }
              >
                {receipt.signatureVerdict}
              </span>
              <span>{receipt.eventId}</span>
              <span className="text-on-surface-variant">→ {receipt.respondedStatus}</span>
              {receipt.duplicate && (
                <span className="rounded-pill border border-outline px-xs py-xxs text-on-surface-variant">
                  duplicate
                </span>
              )}
              <span className="text-on-surface-variant">{receipt.signatureReason}</span>
            </div>
          ))}
        </div>
      </section>

      <p className={`${CARD} text-on-surface-variant`}>
        loopback receiver: the default subscription URL is this app's own
        /api/demo/webhooks/receiver — sender and consumer in one process, no tunnel.
      </p>
    </div>
  );
}

/** The fire endpoint answers with the single delivery it enqueued. */
function parseDelivery(body: unknown): DeliveryRow {
  const delivery = parseDeliveries({ deliveries: [body] })[0];
  if (delivery === undefined) throw new Error('fire response needs one delivery');
  return delivery;
}
