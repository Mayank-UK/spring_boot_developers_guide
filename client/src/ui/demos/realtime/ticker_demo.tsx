import { useEffect, useRef, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';

/**
 * Demo for DemoBlock `realtime:ticker` — topic 28 (realtime), the
 * server-sent-events section. One EventSource over
 * /api/demo/realtime/ticker:
 *
 * - NAMED EVENTS: the server sends `event: tick` frames with ids; the
 *   client listens with addEventListener('tick'), not onmessage — unnamed
 *   frames are a different code path on purpose;
 * - the live table: each tick updates its symbol's row (last price wins)
 *   and prepends to the recent-ticks list;
 * - AUTO-RECONNECT, shown: EventSource reconnects by itself after a
 *   dropped stream and honors the server's retry hint. Choose a limited
 *   stream (limit=20) and watch the connection badge go reconnecting →
 *   live as the browser re-subscribes — no client code runs to make that
 *   happen. On reconnect the browser sends Last-Event-ID and the server
 *   replays what the ring still holds;
 * - the state endpoint (/ticker/state) polls beside the stream:
 *   activeEmitters + ticksSent — the observability half of the demo.
 */

/** EventSource readyState values — spelled out because jsdom has no EventSource. */
const ES_CONNECTING = 0;
const ES_OPEN = 1;

type ConnectionState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'closed';

interface TickRow {
  seq: number;
  symbol: string;
  price: number;
  delta: number;
  at: string;
}

interface TickerStateAnswer {
  activeEmitters: number;
  ticksSent: number;
}

const LIMIT_CHOICES = ['endless', '20', '50'] as const;

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const CHIP =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchTickerState(config: AppConfig): Promise<TickerStateAnswer | null> {
  try {
    const response = await fetch(`${config.apiBaseUrl}/demo/realtime/ticker/state`);
    if (!response.ok) return null;
    const body: unknown = await response.json();
    if (!isRecord(body) || typeof body.activeEmitters !== 'number' || typeof body.ticksSent !== 'number') {
      return null;
    }
    return { activeEmitters: body.activeEmitters, ticksSent: body.ticksSent };
  } catch {
    return null; // transient — the next poll replaces this answer
  }
}

export default function TickerDemo() {
  const config = container.appConfig;
  const [connection, setConnection] = useState<ConnectionState>('idle');
  const [limit, setLimit] = useState<string>('endless');
  const [latest, setLatest] = useState<Record<string, TickRow>>({});
  const [recent, setRecent] = useState<TickRow[]>([]);
  const [state, setState] = useState<TickerStateAnswer | null>(null);
  const sourceRef = useRef<EventSource | null>(null);

  const stop = () => {
    sourceRef.current?.close();
    sourceRef.current = null;
    setConnection('closed');
  };

  const connect = () => {
    sourceRef.current?.close();
    const search = limit === 'endless' ? '' : `?limit=${limit}`;
    // auto-reconnect: the browser re-opens this socket itself after a drop,
    // honoring the server's retry: hint — and presents Last-Event-ID on the
    // reconnect so the missed ids replay. No client code makes that happen.
    const source = new EventSource(`${config.apiBaseUrl}/demo/realtime/ticker${search}`);
    sourceRef.current = source;
    setConnection('connecting');
    source.onopen = () => setConnection('live');
    source.addEventListener('tick', (event) => {
      const row = JSON.parse((event as MessageEvent<string>).data) as TickRow;
      setLatest((previous) => ({ ...previous, [row.symbol]: row }));
      setRecent((previous) => [row, ...previous].slice(0, 12));
    });
    source.onerror = () => {
      // CONNECTING means the browser is already re-connecting; only an
      // explicit close() (or a fatal error) leaves CLOSED
      setConnection(source.readyState === ES_CONNECTING || source.readyState === ES_OPEN
        ? 'reconnecting'
        : 'closed');
    };
    void fetchTickerState(config).then(setState);
  };

  const polling = connection !== 'idle' && connection !== 'closed';
  useEffect(() => {
    if (!polling) {
      return;
    }
    const poll = window.setInterval(() => {
      void fetchTickerState(config).then((answer) => {
        if (answer) setState(answer);
      });
    }, 2_000);
    return () => window.clearInterval(poll);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [polling]);

  useEffect(() => stop, []);

  const rows = Object.keys(latest).sort();

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          limit
          <select
            data-testid="realtime-ticker-limit"
            value={limit}
            onChange={(event) => setLimit(event.target.value)}
            className={INPUT}
          >
            {LIMIT_CHOICES.map((choice) => (
              <option key={choice} value={choice}>
                {choice}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          data-testid="realtime-ticker-connect"
          onClick={connect}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          subscribe (EventSource)
        </button>
        <button
          type="button"
          data-testid="realtime-ticker-disconnect"
          onClick={stop}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          close()
        </button>
        <span data-testid="realtime-ticker-connection" className={CHIP}>
          {connection}
        </span>
        {state && (
          <span data-testid="realtime-ticker-state" className={CHIP}>
            activeEmitters {String(state.activeEmitters)} · ticksSent {String(state.ticksSent)}
          </span>
        )}
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>live table (last price per symbol wins)</p>
        <div className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface">
          {rows.length === 0 && (
            <span className="text-on-surface-variant">no ticks yet — subscribe and wait one beat</span>
          )}
          {rows.map((symbol) => (
            <span key={symbol} data-testid={`realtime-ticker-row-${symbol}`} className="break-all">
              {symbol} · {String(latest[symbol]?.price ?? 0)} ·{' '}
              {(latest[symbol]?.delta ?? 0) >= 0 ? (
                <span className="text-secondary">+{String(latest[symbol]?.delta ?? 0)}</span>
              ) : (
                <span className="text-error">{String(latest[symbol]?.delta ?? 0)}</span>
              )}{' '}
              · id {String(latest[symbol]?.seq ?? 0)}
            </span>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>recent events (newest first — the ids are the resume contract)</p>
        <div
          data-testid="realtime-ticker-recent"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {recent.length === 0 && (
            <span className="text-on-surface-variant">empty — named 'tick' events land here</span>
          )}
          {recent.map((row) => (
            <span key={row.seq} className="break-all">
              id {String(row.seq)} · event:tick · {row.symbol} @ {String(row.price)}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}
