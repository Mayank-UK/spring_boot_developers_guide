import { useState } from 'react';
import type { ReactNode } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:audit` — topic 17 (jdbc-sql), the
 * audit-logging-in-transaction section. A post title change and its audit
 * row, committed together or rolled back together: the failAfter switch
 * crashes BETWEEN the two writes and the proof reads committed truth after
 * the rollback — title unchanged, trail row never existed. The trail view
 * reads the ledger back oldest-first.
 */

interface TransferProof {
  committed: boolean;
  postId: string;
  titleBefore: string;
  titleAfter: string;
  changedBy: string;
  trailRowsBefore: number;
  trailRowsAfter: number;
  note: string;
}

interface TrailEntry {
  id: number;
  actor: string;
  payload: string;
  changedAt: string;
}

interface TrailView {
  entityId: string;
  entries: TrailEntry[];
}

type ProofState =
  | { kind: 'idle' }
  | { kind: 'ready'; proof: TransferProof }
  | { kind: 'error'; failure: Failure };

type TrailState =
  | { kind: 'loading' }
  | { kind: 'ready'; trail: TrailView }
  | { kind: 'error'; failure: Failure };

const DEFAULT_POST_ID = '9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function postJson(config: AppConfig, path: string, body: unknown): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
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
    const message = isRecord(parsed) && isRecord(parsed.error)
        && typeof parsed.error.message === 'string'
      ? parsed.error.message
      : `Request failed with status ${String(response.status)}.`;
    throw new ServerException(message, { status: response.status });
  }
  return parsed;
}

function parseProof(body: unknown): TransferProof {
  if (!isRecord(body)) throw new Error('proof must be an object');
  if (
    typeof body.committed !== 'boolean'
    || typeof body.postId !== 'string'
    || typeof body.titleBefore !== 'string'
    || typeof body.titleAfter !== 'string'
    || typeof body.changedBy !== 'string'
    || typeof body.trailRowsBefore !== 'number'
    || typeof body.trailRowsAfter !== 'number'
    || typeof body.note !== 'string'
  ) {
    throw new Error('proof needs committed, titles, counts, note');
  }
  return {
    committed: body.committed,
    postId: body.postId,
    titleBefore: body.titleBefore,
    titleAfter: body.titleAfter,
    changedBy: body.changedBy,
    trailRowsBefore: body.trailRowsBefore,
    trailRowsAfter: body.trailRowsAfter,
    note: body.note,
  };
}

function parseTrail(body: unknown): TrailView {
  if (!isRecord(body) || typeof body.entityId !== 'string' || !Array.isArray(body.entries)) {
    throw new Error('trail needs entityId and entries');
  }
  for (const entry of body.entries) {
    if (
      !isRecord(entry)
      || typeof entry.id !== 'number'
      || typeof entry.actor !== 'string'
      || typeof entry.payload !== 'string'
      || typeof entry.changedAt !== 'string'
    ) {
      throw new Error('each trail entry needs id, actor, payload, changedAt');
    }
  }
  return { entityId: body.entityId, entries: body.entries as unknown as TrailEntry[] };
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'w-full rounded-sm border border-outline bg-surface px-sm py-xxs font-mono text-caption text-on-surface';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint disabled:opacity-50';

export default function AuditDemo(): ReactNode {
  const config = container.appConfig;
  const [postId, setPostId] = useState(DEFAULT_POST_ID);
  const [title, setTitle] = useState('Renamed by the audit demo');
  const [changedBy, setChangedBy] = useState('demo-operator');
  const [failAfter, setFailAfter] = useState(false);
  const [proof, setProof] = useState<ProofState>({ kind: 'idle' });
  const [trail, setTrail] = useState<TrailState>({ kind: 'loading' });
  const [running, setRunning] = useState(false);

  const loadTrail = async (entityId: string): Promise<void> => {
    const result = await safeRepositoryCall(async () => {
      let response: Response;
      try {
        response = await fetch(
          `${config.apiBaseUrl}/demo/db/audit/trail?entityId=${encodeURIComponent(entityId)}`,
        );
      } catch (error) {
        throw new NetworkException('Could not reach the server.', { cause: error });
      }
      const parsed: unknown = await response.json();
      if (!response.ok) {
        throw new ServerException(`Request failed with status ${String(response.status)}.`, {
          status: response.status,
        });
      }
      return parseTrail(parsed);
    });
    setTrail(result.ok ? { kind: 'ready', trail: result.value } : { kind: 'error', failure: result.failure });
  };

  const transfer = async (): Promise<void> => {
    setRunning(true);
    const result = await safeRepositoryCall(async () =>
      parseProof(
        await postJson(config, '/demo/db/audit/transfer', {
          postId,
          title,
          changedBy,
          failAfter,
        }),
      ));
    setProof(result.ok ? { kind: 'ready', proof: result.value } : { kind: 'error', failure: result.failure });
    await loadTrail(postId);
    setRunning(false);
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the audited transfer</p>
        <div className="grid gap-xs sm:grid-cols-3">
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">postId</span>
            <input
              data-testid="audit-post-id"
              className={INPUT}
              value={postId}
              onChange={(event) => setPostId(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">new title</span>
            <input
              data-testid="audit-title"
              className={INPUT}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-xxs">
            <span className="font-mono text-caption text-on-surface-variant">changedBy</span>
            <input
              data-testid="audit-actor"
              className={INPUT}
              value={changedBy}
              onChange={(event) => setChangedBy(event.target.value)}
            />
          </label>
        </div>
        <div className="flex flex-wrap items-center gap-sm">
          <label className="flex items-center gap-xs text-caption text-on-surface">
            <input
              data-testid="audit-fail-after"
              type="checkbox"
              checked={failAfter}
              onChange={(event) => setFailAfter(event.target.checked)}
            />
            crash between the two writes (proves rollback)
          </label>
          <button
            type="button"
            data-testid="audit-run"
            className={BUTTON}
            disabled={running}
            onClick={() => void transfer()}
          >
            {running ? 'transferring…' : 'run the transfer'}
          </button>
        </div>
      </section>

      {proof.kind === 'error' && <ErrorPanel subject="the transfer proof" failure={proof.failure} />}

      {proof.kind === 'ready' && (
        <section
          data-testid="audit-proof"
          className={`flex flex-col gap-xs rounded-sm border px-sm py-sm ${
            proof.proof.committed
              ? 'border-outline bg-surface'
              : 'border-error bg-error/10'
          }`}
        >
          <p className="flex items-center gap-xs">
            <span
              data-testid="audit-committed"
              className={`rounded-pill px-sm py-xxs font-mono text-caption ${
                proof.proof.committed
                  ? 'bg-secondary/20 text-on-surface'
                  : 'bg-error/20 text-error'
              }`}
            >
              {proof.proof.committed ? 'committed' : 'rolled back'}
            </span>
            <span className="font-mono text-caption text-on-surface-variant">
              by {proof.proof.changedBy}
            </span>
          </p>
          <p data-testid="audit-titles" className="font-mono text-caption text-on-surface">
            “{proof.proof.titleBefore}” → “{proof.proof.titleAfter}”
          </p>
          <p data-testid="audit-counts" className="font-mono text-caption text-on-surface-variant">
            trail rows: {String(proof.proof.trailRowsBefore)} → {String(proof.proof.trailRowsAfter)}
          </p>
          <p className="text-caption text-on-surface-variant">{proof.proof.note}</p>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <div className="flex items-center justify-between gap-sm">
          <p className={OVERLINE}>the trail, oldest first</p>
          <button
            type="button"
            data-testid="audit-refresh"
            className={BUTTON}
            onClick={() => void loadTrail(postId)}
          >
            refresh
          </button>
        </div>
        {trail.kind === 'loading' && (
          <p data-testid="audit-trail-loading" className="font-mono text-caption text-on-surface-variant">
            fetching the trail…
          </p>
        )}
        {trail.kind === 'error' && <ErrorPanel subject="the trail" failure={trail.failure} />}
        {trail.kind === 'ready' && trail.trail.entries.length === 0 && (
          <p data-testid="audit-trail-empty" className="font-mono text-caption text-on-surface-variant">
            no trail rows for this entity — nothing was ever committed about it
          </p>
        )}
        {trail.kind === 'ready' && trail.trail.entries.length > 0 && (
          <ol className="flex flex-col gap-xs">
            {trail.trail.entries.map((entry) => (
              <li
                key={entry.id}
                data-testid="audit-trail-entry"
                className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs"
              >
                <span className="flex items-baseline justify-between gap-sm">
                  <span className="font-mono text-caption text-on-surface">
                    #{String(entry.id)} · {entry.actor}
                  </span>
                  <span className="font-mono text-caption text-on-surface-variant">
                    {entry.changedAt}
                  </span>
                </span>
                <code className="font-mono text-caption text-on-surface-variant">
                  {entry.payload}
                </code>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
