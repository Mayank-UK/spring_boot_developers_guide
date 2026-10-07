import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `redis:playground` — topic 20 (redis), the
 * core-structures section. The console over POST /api/demo/redis/op:
 * pick a structure op (set/get/del, hset/hgetall, lpush/lrange,
 * sadd/smembers, zadd/zrange, ttl/expire, publish), fill its operands,
 * run it. Every answer carries {ok, result, namespacedKey, mode}: the
 * RESULT shows the structure's semantics (a hash as fields, a zrange as
 * scored members, ttl as the typed three-way insight), the NAMESPACED KEY
 * shows what the server actually stored under (guide:demo:… — the
 * adapter-owned namespace lesson), and the MODE shows which store
 * executed the op (fake in-memory or live Redis, per the scenario
 * toggle). The pub/sub panel reads the recent-messages ring; publishing
 * then refreshing shows the round trip.
 */

const OPS = [
  'set',
  'get',
  'del',
  'hset',
  'hgetall',
  'lpush',
  'lrange',
  'sadd',
  'smembers',
  'zadd',
  'zrange',
  'ttl',
  'expire',
  'publish',
] as const;
type Op = (typeof OPS)[number];

const OPS_NEEDING_VALUE: ReadonlySet<Op> = new Set(['set', 'hset', 'lpush', 'sadd', 'zadd', 'publish']);

interface OpAnswer {
  ok: boolean;
  result: unknown;
  namespacedKey: string;
  mode: string;
}

interface RecentMessage {
  channel: string;
  message: string;
  receivedAt: string;
}

interface Recent {
  mode: string;
  messages: RecentMessage[];
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; answer: OpAnswer }
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

function parseOpAnswer(body: unknown): OpAnswer {
  if (
    !isRecord(body) ||
    typeof body.ok !== 'boolean' ||
    typeof body.namespacedKey !== 'string' ||
    typeof body.mode !== 'string'
  ) {
    throw new Error('op answer needs ok, result, namespacedKey, mode');
  }
  return { ok: body.ok, result: body.result, namespacedKey: body.namespacedKey, mode: body.mode };
}

function parseRecent(body: unknown): Recent {
  if (!isRecord(body) || typeof body.mode !== 'string' || !Array.isArray(body.messages)) {
    throw new Error('recent needs mode, messages');
  }
  return {
    mode: body.mode,
    messages: body.messages.map((row) => {
      if (
        !isRecord(row) ||
        typeof row.channel !== 'string' ||
        typeof row.message !== 'string' ||
        typeof row.receivedAt !== 'string'
      ) {
        throw new Error('each recent message needs channel, message, receivedAt');
      }
      return { channel: row.channel, message: row.message, receivedAt: row.receivedAt };
    }),
  };
}

/* ---- presentation ---- */

function renderResult(result: unknown): string {
  if (result === null) return '(nil)';
  if (typeof result === 'string') return result;
  return JSON.stringify(result, null, 0);
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';

export default function PlaygroundDemo() {
  const config = container.appConfig;
  const [op, setOp] = useState<Op>('set');
  const [key, setKey] = useState('league');
  const [value, setValue] = useState('ada');
  const [field, setField] = useState('lang');
  const [score, setScore] = useState('10');
  const [ttlSeconds, setTtlSeconds] = useState('60');
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });
  const [mode, setMode] = useState<string>('…');
  const [recent, setRecent] = useState<RecentMessage[]>([]);

  const refreshMode = () => {
    void safeRepositoryCall(async () => {
      const body = await requestJson(config, '/demo/redis/mode');
      if (!isRecord(body) || typeof body.dataMode !== 'string') {
        throw new Error('mode needs area, dataMode');
      }
      return body.dataMode;
    }).then((result) => {
      if (result.ok) setMode(result.value);
    });
  };

  const refreshRecent = () => {
    void safeRepositoryCall(async () => parseRecent(await requestJson(config, '/demo/redis/recent')))
      .then((result) => {
        if (result.ok) setRecent(result.value.messages);
      });
  };

  useEffect(() => {
    refreshMode();
    refreshRecent();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = () => {
    setPanel({ kind: 'loading' });
    const body: Record<string, unknown> = { op, key };
    if (OPS_NEEDING_VALUE.has(op)) body.value = value;
    if (op === 'hset') body.field = field;
    if (op === 'zadd') body.score = Number(score);
    if (op === 'set' || op === 'expire') body.ttlSeconds = Number(ttlSeconds);
    void safeRepositoryCall(async () =>
      parseOpAnswer(
        await requestJson(config, '/demo/redis/op', {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      ),
    ).then((result) => {
      setPanel(
        result.ok
          ? { kind: 'ready', answer: result.value }
          : { kind: 'error', failure: result.failure },
      );
      refreshMode();
      if (op === 'publish') refreshRecent();
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          op
          <select
            data-testid="redis-op-select"
            value={op}
            onChange={(event) => setOp(event.target.value as Op)}
            className={INPUT}
          >
            {OPS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          key / channel
          <input
            data-testid="redis-key-input"
            value={key}
            onChange={(event) => setKey(event.target.value)}
            className={`${INPUT} w-40`}
          />
        </label>
        {OPS_NEEDING_VALUE.has(op) && (
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            value / member / message
            <input
              data-testid="redis-value-input"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              className={`${INPUT} w-40`}
            />
          </label>
        )}
        {op === 'hset' && (
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            field
            <input
              data-testid="redis-field-input"
              value={field}
              onChange={(event) => setField(event.target.value)}
              className={`${INPUT} w-24`}
            />
          </label>
        )}
        {op === 'zadd' && (
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            score
            <input
              data-testid="redis-score-input"
              type="number"
              step="0.5"
              value={score}
              onChange={(event) => setScore(event.target.value)}
              className={`${INPUT} w-20`}
            />
          </label>
        )}
        {(op === 'set' || op === 'expire') && (
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            ttl seconds
            <input
              data-testid="redis-ttl-input"
              type="number"
              min={1}
              value={ttlSeconds}
              onChange={(event) => setTtlSeconds(event.target.value)}
              className={`${INPUT} w-20`}
            />
          </label>
        )}
        <button
          type="button"
          data-testid="redis-run"
          onClick={run}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          run op
        </button>
        <span
          data-testid="redis-mode-chip"
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          area mode: {mode}
        </span>
      </section>

      {panel.kind === 'idle' && (
        <p data-testid="redis-idle" className="font-mono text-caption text-on-surface-variant">
          no op yet — every answer reports the namespaced key the store actually saw
        </p>
      )}
      {panel.kind === 'loading' && (
        <p data-testid="redis-loading" className="font-mono text-caption text-on-surface-variant">
          executing {op}…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the redis op" failure={panel.failure} />}
      {panel.kind === 'ready' && (
        <section
          data-testid="redis-result"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span data-testid="redis-namespaced" className="break-all text-on-surface">
            {panel.answer.namespacedKey}
          </span>
          <span data-testid="redis-result-value" className="break-all">
            {renderResult(panel.answer.result)}
          </span>
          <span className="text-on-surface-variant">mode: {panel.answer.mode}</span>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <div className="flex items-center gap-sm">
          <p className={OVERLINE}>recent pub/sub deliveries</p>
          <button
            type="button"
            data-testid="redis-refresh-recent"
            onClick={refreshRecent}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
          >
            refresh
          </button>
        </div>
        <div data-testid="redis-recent-list" className="flex flex-col gap-xxs">
          {recent.length === 0 && (
            <p data-testid="redis-recent-empty" className="font-mono text-caption text-on-surface-variant">
              nothing delivered yet — run the publish op, then refresh
            </p>
          )}
          {recent.map((row, index) => (
            <span
              key={`${row.receivedAt}-${String(index)}`}
              data-testid="redis-recent-item"
              className="break-all font-mono text-caption text-on-surface"
            >
              {row.channel} → {row.message}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}
