import { useEffect, useState } from 'react';
import { securityApiJson, isRecord, requireNumber, requireString } from './security-demo-api';
import { ErrorPanel } from '../../components/error-panel';
import type { Failure } from '../../../core/error-handling/failures';

/**
 * Demo for DemoBlock `security:lab` — topic 25 (security), the lab: three
 * live instruments over one guarded fetch helper.
 * - HEADERS: the server fetches its own probe routes through both chains
 *   and reports the MEASURED response headers — the helmet beside the
 *   deliberately-relaxed insecure-comparison chain.
 * - CORS: two live OPTIONS preflights (allowlisted origin vs unlisted),
 *   with the Access-Control-* verdicts the configured policy answers.
 * - RATE LIMIT: provision a per-key token bucket (limit/window), spend
 *   tokens one call at a time, watch the 429 + Retry-After arrive, and
 *   reset the bucket through the same seam the tests use.
 */

const HEADERS_URL = '/demo/security/lab/headers';
const PREFLIGHT_URL = '/demo/security/lab/cors/preflight';
const RATE_URL = '/demo/security/lab/ratelimited';

interface MeasuredHeader {
  header: string;
  value: string | null;
  blunts: string;
  note: string;
}

interface HeadersLab {
  allowlistNote: string;
  secure: MeasuredHeader[];
  insecureComparison: MeasuredHeader[];
}

interface Preflight {
  requestedOrigin: string;
  status: number;
  accessControlVerdicts: Record<string, string>;
  verdict: string;
}

interface CorsLab {
  policy: string;
  allowlistedOrigin: Preflight;
  unlistedOrigin: Preflight;
}

interface Consumption {
  key: string;
  limit: number;
  windowSeconds: number;
  allowed: boolean;
  remainingTokens: number;
}

type RatePanel =
  | { kind: 'idle' }
  | { kind: 'consumed'; report: Consumption }
  | { kind: 'refused'; failure: Failure; retryAfter: number }
  | { kind: 'reset' }
  | { kind: 'error'; failure: Failure };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD =
  'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption break-all';

function parseMeasured(body: unknown): MeasuredHeader {
  if (!isRecord(body)) throw new Error('measured header must be an object');
  return {
    header: requireString(body, 'header'),
    value: typeof body.value === 'string' ? body.value : null,
    blunts: requireString(body, 'blunts'),
    note: requireString(body, 'note'),
  };
}

function parseHeadersLab(body: unknown): HeadersLab {
  if (!isRecord(body)) throw new Error('headers lab must be an object');
  return {
    allowlistNote: requireString(body, 'allowlistNote'),
    secure: Array.isArray(body.secure) ? body.secure.map(parseMeasured) : [],
    insecureComparison: Array.isArray(body.insecureComparison)
      ? body.insecureComparison.map(parseMeasured)
      : [],
  };
}

function parsePreflight(body: unknown): Preflight {
  if (!isRecord(body)) throw new Error('preflight must be an object');
  const verdicts: Record<string, string> = {};
  if (isRecord(body.accessControlVerdicts)) {
    for (const [key, value] of Object.entries(body.accessControlVerdicts)) {
      if (typeof value === 'string') verdicts[key] = value;
    }
  }
  return {
    requestedOrigin: requireString(body, 'requestedOrigin'),
    status: requireNumber(body, 'status'),
    accessControlVerdicts: verdicts,
    verdict: requireString(body, 'verdict'),
  };
}

function parseCorsLab(body: unknown): CorsLab {
  if (!isRecord(body)) throw new Error('cors lab must be an object');
  return {
    policy: requireString(body, 'policy'),
    allowlistedOrigin: parsePreflight(body.allowlistedOrigin),
    unlistedOrigin: parsePreflight(body.unlistedOrigin),
  };
}

function parseConsumption(body: unknown): Consumption {
  if (!isRecord(body)) throw new Error('consumption must be an object');
  return {
    key: requireString(body, 'key'),
    limit: requireNumber(body, 'limit'),
    windowSeconds: requireNumber(body, 'windowSeconds'),
    allowed: body.allowed === true,
    remainingTokens: requireNumber(body, 'remainingTokens'),
  };
}

function HeaderRow({ measured, column }: { measured: MeasuredHeader; column: string }) {
  return (
    <div
      data-testid={`secl-h-${column}-${measured.header}`}
      className={`${CARD} flex flex-col gap-xxs`}
    >
      <div className="text-on-surface">
        {measured.header}: {measured.value ?? '«absent»'}
      </div>
      <div className="text-on-surface-variant">blunts: {measured.blunts}</div>
    </div>
  );
}

export default function LabDemo() {
  const [lab, setLab] = useState<HeadersLab | null>(null);
  const [cors, setCors] = useState<CorsLab | null>(null);
  const [panel, setPanel] = useState<{ kind: 'idle' } | { kind: 'error'; failure: Failure }>({
    kind: 'idle',
  });
  const [rateKey, setRateKey] = useState('demo-client');
  const [limit, setLimit] = useState(3);
  const [windowSeconds, setWindowSeconds] = useState(60);
  const [ratePanel, setRatePanel] = useState<RatePanel>({ kind: 'idle' });

  useEffect(() => {
    let cancelled = false;
    const load = async (): Promise<void> => {
      const result = await securityApiJson(HEADERS_URL, undefined, parseHeadersLab);
      if (cancelled) return;
      if (result.ok) {
        setLab(result.value);
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  const runPreflights = async (): Promise<void> => {
    const result = await securityApiJson(PREFLIGHT_URL, undefined, parseCorsLab);
    if (result.ok) {
      setCors(result.value);
      setPanel({ kind: 'idle' });
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
  };

  const callRateLimited = async (): Promise<void> => {
    const url = `${RATE_URL}?key=${encodeURIComponent(rateKey)}&limit=${limit}&window=${windowSeconds}`;
    const result = await securityApiJson(url, { method: 'POST' }, parseConsumption);
    if (result.ok) {
      setRatePanel({ kind: 'consumed', report: result.value });
    } else if (result.failure.status === 429) {
      setRatePanel({ kind: 'refused', failure: result.failure, retryAfter: windowSeconds });
    } else {
      setRatePanel({ kind: 'error', failure: result.failure });
    }
  };

  const resetBucket = async (): Promise<void> => {
    const url = `${RATE_URL}/reset?key=${encodeURIComponent(rateKey)}&limit=${limit}&window=${windowSeconds}`;
    const result = await securityApiJson(url, { method: 'POST' }, (body) => {
      if (!isRecord(body)) throw new Error('reset must be an object');
      return requireString(body, 'key');
    });
    if (result.ok) {
      setRatePanel({ kind: 'reset' });
    } else {
      setRatePanel({ kind: 'error', failure: result.failure });
    }
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>response headers — measured live from both chains</p>
        {panel.kind === 'error' && <ErrorPanel subject="the headers lab" failure={panel.failure} />}
        {lab === null && panel.kind !== 'error' && (
          <p className="font-mono text-caption text-on-surface-variant">fetching the probes…</p>
        )}
        {lab !== null && (
          <>
            <div className="grid gap-xs md:grid-cols-2">
              <div className="flex flex-col gap-xxs">
                <p className="font-mono text-caption text-secondary">main chain (hardened)</p>
                {lab.secure.map((measured) => (
                  <HeaderRow key={measured.header} measured={measured} column="secure" />
                ))}
              </div>
              <div className="flex flex-col gap-xxs">
                <p className="font-mono text-caption text-error">
                  insecure-comparison (deliberately relaxed)
                </p>
                {lab.insecureComparison.map((measured) => (
                  <HeaderRow key={measured.header} measured={measured} column="insecure" />
                ))}
              </div>
            </div>
            <p className="font-mono text-caption text-on-surface-variant">{lab.allowlistNote}</p>
          </>
        )}
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>CORS — two live preflights, one policy answering</p>
        <button
          type="button"
          data-testid="secl-cors-run"
          onClick={() => void runPreflights()}
          className="w-fit rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          run both preflights
        </button>
        {cors !== null && (
          <div data-testid="secl-cors-result" className="flex flex-col gap-xxs">
            <p className="font-mono text-caption text-on-surface-variant">{cors.policy}</p>
            {[cors.allowlistedOrigin, cors.unlistedOrigin].map((preflight) => (
              <div key={preflight.requestedOrigin} className={`${CARD} flex flex-col gap-xxs`}>
                <div className={preflight.status === 200 ? 'text-secondary' : 'text-error'}>
                  {preflight.requestedOrigin} → {preflight.status} · {preflight.verdict}
                </div>
                {Object.entries(preflight.accessControlVerdicts).map(([name, value]) => (
                  <div key={name} className="text-on-surface-variant">
                    {name}: {value}
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>rate limit — spend the bucket down to the 429</p>
        <div className="flex flex-wrap items-end gap-xs">
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">key</span>
            <input
              data-testid="secl-rl-key"
              value={rateKey}
              onChange={(event) => setRateKey(event.target.value)}
              className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">limit</span>
            <input
              data-testid="secl-rl-limit"
              value={String(limit)}
              onChange={(event) => setLimit(Number(event.target.value) || 1)}
              className="w-16 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">window (s)</span>
            <input
              data-testid="secl-rl-window"
              value={String(windowSeconds)}
              onChange={(event) => setWindowSeconds(Number(event.target.value) || 1)}
              className="w-20 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
            />
          </label>
          <button
            type="button"
            data-testid="secl-rl-call"
            onClick={() => void callRateLimited()}
            className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
          >
            make a call
          </button>
          <button
            type="button"
            data-testid="secl-rl-reset"
            onClick={() => void resetBucket()}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
          >
            reset (the test seam)
          </button>
        </div>
        {ratePanel.kind === 'consumed' && (
          <div data-testid="secl-rl-result" className={CARD}>
            200 · allowed · {ratePanel.report.remainingTokens} token(s) left of {ratePanel.report.limit}{' '}
            per {ratePanel.report.windowSeconds}s (key {ratePanel.report.key})
          </div>
        )}
        {ratePanel.kind === 'refused' && (
          <div data-testid="secl-rl-result" className={`${CARD} text-error`}>
            429 · rate_limited · Retry-After ~{ratePanel.retryAfter}s — {ratePanel.failure.message}
          </div>
        )}
        {ratePanel.kind === 'reset' && (
          <div data-testid="secl-rl-result" className={CARD}>
            bucket reset — the next call starts full
          </div>
        )}
        {ratePanel.kind === 'error' && (
          <ErrorPanel subject="the rate limiter" failure={ratePanel.failure} />
        )}
      </section>
    </div>
  );
}
