import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `thirdparty:hub` — topic 43 (third-party), the
 * integration hub. One report, three integrations (mail, payments,
 * storage), each card answering the on-call questions: POSTURE (fake or
 * sandbox — how the adapter is answering), the LAST operation's outcome
 * (sent / queued / declined / stored / rate-limited / failed), QUOTA
 * state (calls used of the limit), and KEY handling (a VERDICT — present
 * or not — never a value). Triggers run each integration through its
 * fake; the quota dial demonstrates blast radius: exhaust one
 * integration and its siblings keep answering.
 *
 * Every number and verdict painted here comes from the SERVER's answers
 * — the component narrates nothing it did not fetch.
 */

interface IntegrationView {
  id: string;
  title: string;
  what: string;
  posture: string;
  lastOperation: string | null;
  lastOutcome: string;
  lastDetail: string | null;
  callsUsed: number;
  quotaLimit: number;
  quotaState: string;
  keyEnvVar: string | null;
  keyPresentFromEnv: boolean;
  keyVerdict: string | null;
  notes: string[];
}

interface HubReport {
  integrations: IntegrationView[];
  reading: string[];
}

interface OperationAnswer {
  outcome: string;
  detail: string;
  after: IntegrationView;
}

interface DialAnswer {
  changed: string;
  after: IntegrationView;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

const DIAL_LIMITS = ['1', '3', '8'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseIntegration(source: unknown): IntegrationView {
  if (!isRecord(source)) throw new Error('an integration row must be an object');
  return {
    id: String(source.id ?? ''),
    title: String(source.title ?? ''),
    what: String(source.what ?? ''),
    posture: String(source.posture ?? 'fake'),
    lastOperation: source.lastOperation === null || source.lastOperation === undefined
      ? null
      : String(source.lastOperation),
    lastOutcome: String(source.lastOutcome ?? 'idle'),
    lastDetail: source.lastDetail === null || source.lastDetail === undefined
      ? null
      : String(source.lastDetail),
    callsUsed: Number(source.callsUsed ?? 0),
    quotaLimit: Number(source.quotaLimit ?? 0),
    quotaState: String(source.quotaState ?? 'healthy'),
    keyEnvVar: source.keyEnvVar === null || source.keyEnvVar === undefined
      ? null
      : String(source.keyEnvVar),
    keyPresentFromEnv: source.keyPresentFromEnv === true,
    keyVerdict: source.keyVerdict === null || source.keyVerdict === undefined
      ? null
      : String(source.keyVerdict),
    notes: Array.isArray(source.notes) ? source.notes.map((n) => String(n)) : [],
  };
}

function parseHub(source: unknown): HubReport {
  if (!isRecord(source)) throw new Error('the hub report must be an object');
  const { integrations, reading } = source;
  if (!Array.isArray(integrations) || !Array.isArray(reading)) {
    throw new Error('the hub report carries integrations and reading arrays');
  }
  return {
    integrations: integrations.map(parseIntegration),
    reading: reading.map((line) => String(line)),
  };
}

function parseOperation(source: unknown): OperationAnswer {
  if (!isRecord(source)) throw new Error('an operation answer must be an object');
  return {
    outcome: String(source.outcome ?? ''),
    detail: String(source.detail ?? ''),
    after: parseIntegration(source.after),
  };
}

function parseDial(source: unknown): DialAnswer {
  if (!isRecord(source)) throw new Error('a dial answer must be an object');
  return {
    changed: String(source.changed ?? ''),
    after: parseIntegration(source.after),
  };
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
    const code = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.code ?? 'unknown_error')
      : 'unknown_error';
    const message = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.message ?? `Request failed with status ${String(response.status)}.`)
      : `Request failed with status ${String(response.status)}.`;
    throw new ServerException(message, { code, status: response.status });
  }
  return parsed;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const ACTION_BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';
const ACTIVE_BUTTON =
  'rounded-pill border border-outline bg-surface-tint px-sm py-xxs font-mono text-caption text-on-surface';

function outcomeTone(outcome: string): string {
  if (outcome === 'succeeded' || outcome === 'sent' || outcome === 'stored') {
    return 'text-on-surface';
  }
  if (outcome === 'rate_limited' || outcome === 'failed') {
    return 'text-error';
  }
  return 'text-on-surface-variant';
}

export default function HubDemo() {
  const config = container.appConfig;
  const [report, setReport] = useState<HubReport | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });
  const [flash, setFlash] = useState<string | null>(null);
  const [dialTarget, setDialTarget] = useState<string>('mail');
  const [dialLimit, setDialLimit] = useState<(typeof DIAL_LIMITS)[number]>('1');
  const [payToken, setPayToken] = useState<string>('tok_visa');

  /** Patch one integration row into the loaded report (triggers/dials answer with `after`). */
  const patchRow = (next: IntegrationView) => {
    setReport((current) =>
      current === null
        ? current
        : { ...current, integrations: current.integrations.map((row) => (row.id === next.id ? next : row)) },
    );
  };

  const loadHub = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => parseHub(await requestJson(config, '/demo/thirdparty/hub')))
      .then((result) => {
        if (result.ok) {
          setReport(result.value);
          setFlash(null);
          setPanel({ kind: 'idle' });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
  }, [config]);

  useEffect(() => {
    loadHub();
  }, [loadHub]);

  /** One trigger: POST the operation, patch the row it answers with, flash the receipt. */
  const runTrigger = useCallback((path: string, note: string) => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseOperation(await requestJson(config, path, { method: 'POST' })))
      .then((result) => {
        if (result.ok) {
          patchRow(result.value.after);
          setFlash(`${note} → ${result.value.outcome}: ${result.value.detail}`);
          setPanel({ kind: 'idle' });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
  }, [config]);

  /** The quota dial — blast radius in one click: exhaust one integration, watch the others. */
  const runDial = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseDial(await requestJson(
        config,
        `/demo/thirdparty/quota?integration=${dialTarget}&limit=${dialLimit}`,
        { method: 'POST' },
      )))
      .then((result) => {
        if (result.ok) {
          patchRow(result.value.after);
          setFlash(result.value.changed);
          setPanel({ kind: 'idle' });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
  }, [config, dialTarget, dialLimit]);

  /** The posture dial — fake ↔ sandbox per integration (the adapter refuses live at runtime). */
  const runPosture = useCallback((id: string, next: string) => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseDial(await requestJson(
        config,
        `/demo/thirdparty/posture?integration=${id}&posture=${next}`,
        { method: 'POST' },
      )))
      .then((result) => {
        if (result.ok) {
          patchRow(result.value.after);
          setFlash(result.value.changed);
          setPanel({ kind: 'idle' });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
  }, [config]);

  const runReset = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseHub(await requestJson(config, '/demo/thirdparty/reset', { method: 'POST' })))
      .then((result) => {
        if (result.ok) {
          setReport(result.value);
          setFlash('hub reset — quotas, counters and postures back to constructed state');
          setPanel({ kind: 'idle' });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      });
  }, [config]);

  const triggerFor = (id: string): { label: string; path: string } => {
    switch (id) {
      case 'mail':
        return {
          label: 'send mail',
          path: '/demo/thirdparty/mail?to=friend@example.com&subject=Welcome',
        };
      case 'payments':
        return {
          label: 'charge',
          path: `/demo/thirdparty/payments?token=${payToken}&amountCents=1999&currency=usd`,
        };
      default:
        return {
          label: 'put object',
          path: '/demo/thirdparty/storage?key=guide/hello.txt&sizeBytes=2048',
        };
    }
  };

  return (
    <div className="flex flex-col gap-md" data-testid="tp-hub">
      {panel.kind === 'error' && <ErrorPanel subject="the third-party hub" failure={panel.failure} />}

      {report?.integrations.map((row) => {
        const trigger = triggerFor(row.id);
        return (
          <section
            key={row.id}
            data-testid="tp-card"
            className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <p className={OVERLINE}>
              {row.title} — posture <span data-testid={`tp-posture-${row.id}`}>{row.posture}</span>
            </p>
            <p className="text-on-surface-variant">{row.what}</p>
            <div className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs">
              <span>last:</span>
              <span data-testid={`tp-outcome-${row.id}`} className={outcomeTone(row.lastOutcome)}>
                {row.lastOperation === null ? 'idle' : `${row.lastOperation} → ${row.lastOutcome}`}
              </span>
              {row.lastDetail !== null && (
                <span className="text-on-surface-variant">{row.lastDetail}</span>
              )}
            </div>
            <div className="flex flex-wrap items-baseline gap-x-xs">
              <span data-testid={`tp-quota-${row.id}`}>
                quota {String(row.callsUsed)}/{String(row.quotaLimit)} ({row.quotaState})
              </span>
            </div>
            <div className="flex flex-wrap items-baseline gap-x-xs">
              <span data-testid={`tp-key-${row.id}`} className="text-on-surface-variant">
                key: {row.keyVerdict}
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-xs border-t border-outline pt-xxs">
              <button
                type="button"
                data-testid={`tp-run-${row.id}`}
                onClick={() => runTrigger(trigger.path, `${row.id}: ${trigger.label}`)}
                className={ACTION_BUTTON}
              >
                {trigger.label}
              </button>
              {row.id === 'payments' && (
                <>
                  {['tok_visa', 'tok_chargeDeclined'].map((token) => (
                    <button
                      key={token}
                      type="button"
                      data-testid="tp-token"
                      onClick={() => setPayToken(token)}
                      className={token === payToken ? ACTIVE_BUTTON : BUTTON}
                    >
                      {token}
                    </button>
                  ))}
                </>
              )}
              <button
                type="button"
                data-testid={`tp-flip-${row.id}`}
                onClick={() => runPosture(row.id, row.posture === 'fake' ? 'sandbox' : 'fake')}
                className={BUTTON}
              >
                → {row.posture === 'fake' ? 'sandbox' : 'fake'}
              </button>
            </div>
            {row.notes.map((note) => (
              <span key={note} className="border-t border-outline pt-xxs text-on-surface-variant">
                {note}
              </span>
            ))}
          </section>
        );
      })}

      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>quota dial:</span>
        {report?.integrations.map((row) => (
          <button
            key={row.id}
            type="button"
            data-testid={`tp-dial-${row.id}`}
            onClick={() => setDialTarget(row.id)}
            className={row.id === dialTarget ? ACTIVE_BUTTON : BUTTON}
          >
            {row.id}
          </button>
        ))}
        {DIAL_LIMITS.map((limit) => (
          <button
            key={limit}
            type="button"
            data-testid={`tp-limit-${limit}`}
            onClick={() => setDialLimit(limit)}
            className={limit === dialLimit ? ACTIVE_BUTTON : BUTTON}
          >
            {limit}
          </button>
        ))}
        <button type="button" data-testid="tp-dial-apply" onClick={runDial} className={ACTION_BUTTON}>
          set quota
        </button>
        <button type="button" data-testid="tp-reset" onClick={runReset} className={BUTTON}>
          reset hub
        </button>
      </section>

      {flash !== null && (
        <p data-testid="tp-action" className="font-mono text-caption text-on-surface">
          {flash}
        </p>
      )}

      {report?.reading.map((line, index) => (
        <span
          key={line}
          data-testid={`tp-reading-${String(index)}`}
          className="border-t border-outline pt-xxs font-mono text-caption text-on-surface-variant"
        >
          {line}
        </span>
      ))}
    </div>
  );
}
