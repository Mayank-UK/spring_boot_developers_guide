import { useCallback, useEffect, useState } from 'react';
import { authApiJson, isRecord, requireString } from '../auth/auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';
import type { Failure } from '../../../core/error-handling/failures';

/**
 * Demo for DemoBlock `authz:matrix` — topic 24 (authorization), the
 * roles-and-authorities section. One console over the authz island:
 * - the role→action matrix and per-user action summary, rendered from
 *   the REAL policies (GET /demo/authz/matrix — the island's open door)
 * - act-as switching: alice/bob/carol over the session machinery (no
 *   password — the tour's stand-in for login), and the visible-document
 *   list RE-FILTERING per caller (the query-shapes-authz lesson)
 * - guarded probes, each labeled with the enforcement layer that would
 *   fire: url rule (anonymous), @PreAuthorize (publish), @PostAuthorize
 *   (owner-view), in-use-case policy (guarded read) — 403s rendered as
 *   this guide's error envelope (code forbidden + requestId), evidence
 *   not accidents
 * - the self-invocation trap, run live as the current caller
 */

const BASE = '/demo/authz';

interface RoleSummary {
  role: string;
  allowedActions: string[];
}

interface UserRow {
  username: string;
  roles: string[];
  actionSummary: string[];
}

interface MatrixView {
  roleMatrix: RoleSummary[];
  users: UserRow[];
}

interface DocumentRow {
  id: string;
  title: string;
  owner: string;
  visibility: string;
  visibleBecause: string;
}

interface ProbeResult {
  label: string;
  layer: string;
  status: number;
  summary: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure }
  | { kind: 'ready' };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

function parseMatrix(body: unknown): MatrixView {
  if (!isRecord(body) || !Array.isArray(body.roleMatrix) || !Array.isArray(body.users)) {
    throw new Error('matrix response needs roleMatrix and users');
  }
  return {
    roleMatrix: body.roleMatrix.map((row) => ({
      role: requireString(row, 'role'),
      allowedActions: Array.isArray(row.allowedActions) ? row.allowedActions.map(String) : [],
    })),
    users: body.users.map((row) => ({
      username: requireString(row, 'username'),
      roles: Array.isArray(row.roles) ? row.roles.map(String) : [],
      actionSummary: Array.isArray(row.actionSummary)
        ? row.actionSummary.map(String)
        : [],
    })),
  };
}

function parseDocuments(body: unknown): DocumentRow[] {
  if (!isRecord(body) || !Array.isArray(body.documents)) {
    throw new Error('documents response needs a documents array');
  }
  return body.documents.map((row) => ({
    id: requireString(row, 'id'),
    title: requireString(row, 'title'),
    owner: requireString(row, 'owner'),
    visibility: requireString(row, 'visibility'),
    visibleBecause: requireString(row, 'reason'),
  }));
}

interface TrapView {
  caller: string;
  directAllowed: boolean;
  internalAllowed: boolean;
}

function parseTrap(body: unknown): TrapView {
  if (!isRecord(body) || !isRecord(body.directCall) || !isRecord(body.internalCall)) {
    throw new Error('self-invocation response needs directCall and internalCall');
  }
  return {
    caller: isRecord(body.caller) ? requireString(body.caller, 'username') : '?',
    directAllowed: body.directCall.allowed === true,
    internalAllowed: body.internalCall.allowed === true,
  };
}

export default function MatrixDemo() {
  const [matrix, setMatrix] = useState<MatrixView | null>(null);
  const [caller, setCaller] = useState<UserRow | null>(null);
  const [documents, setDocuments] = useState<DocumentRow[] | null>(null);
  const [probe, setProbe] = useState<ProbeResult | null>(null);
  const [trap, setTrap] = useState<TrapView | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });

  useEffect(() => {
    void authApiJson<MatrixView>(`${BASE}/matrix`, undefined, parseMatrix).then((result) => {
      if (result.ok) {
        setMatrix(result.value);
        setPanel({ kind: 'ready' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, []);

  const loadDocuments = useCallback(async (): Promise<void> => {
    setDocuments(null);
    const result = await authApiJson<DocumentRow[]>(`${BASE}/documents`, undefined, parseDocuments);
    if (result.ok) {
      setDocuments(result.value);
    } else if (result.failure.status === 401) {
      setDocuments([]); // anonymous — the URL-rule layer answered before any code ran
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
  }, []);

  const actAs = async (username: string): Promise<void> => {
    const result = await authApiJson<{ user: UserRow }>(
      `${BASE}/act-as`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username }),
      },
      (body) => {
        if (!isRecord(body) || !isRecord(body.user)) throw new Error('act-as needs a user');
        return {
          user: {
            username: requireString(body.user, 'username'),
            roles: Array.isArray(body.user.roles) ? body.user.roles.map(String) : [],
            actionSummary: [],
          },
        };
      },
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setCaller(result.value.user);
    setProbe(null);
    setTrap(null);
    await loadDocuments();
  };

  /** One guarded call, one labeled verdict — 200s summarized, 403s as the envelope. */
  const runProbe = async (
    label: string,
    layer: string,
    path: string,
    init: RequestInit | undefined,
  ): Promise<void> => {
    const result = await authApiJson<Record<string, unknown>>(path, init, (body) =>
      isRecord(body) ? body : {},
    );
    if (result.ok) {
      const effect = typeof result.value['effect'] === 'string'
        ? ` — ${String(result.value['effect'])}`
        : '';
      setProbe({ label, layer, status: 200, summary: `200 allowed${effect}` });
      return;
    }
    const failure = result.failure;
    setProbe({
      label,
      layer,
      status: failure.status,
      summary: `${String(failure.status)} ${failure.code} — ${failure.message} (requestId ${failure.requestId ?? 'n/a'})`,
    });
  };

  const runTrap = async (): Promise<void> => {
    const result = await authApiJson<TrapView>(`${BASE}/specimens/self-invocation`, undefined, parseTrap);
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setTrap(result.value);
  };

  return (
    <div className="flex flex-col gap-md">
      {panel.kind === 'error' && <ErrorPanel subject="the authorization demo" failure={panel.failure} />}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the role→action matrix (rendered from the real RolePolicy)</p>
        <div data-testid="authz-role-matrix" className="flex flex-wrap gap-xs">
          {matrix?.roleMatrix.map((row) => (
            <div key={row.role} data-testid="authz-role-row" className={`${CARD} text-on-surface`}>
              <span className="text-on-surface-variant">{row.role}</span>{' → '}
              {row.allowedActions.join(', ')}
            </div>
          ))}
          {!matrix && <span className="text-body-sm text-on-surface-variant">loading…</span>}
        </div>
        <div data-testid="authz-user-summary" className="flex flex-wrap gap-xs">
          {matrix?.users.map((user) => (
            <span
              key={user.username}
              data-testid="authz-user-row"
              className={`${CARD} ${caller?.username === user.username ? 'border-secondary' : ''} text-on-surface`}
            >
              {user.username} ({user.roles.join('+')}): {user.actionSummary.join(', ')}
            </span>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>act as — the session switch (no password, tour-only)</p>
        <div className="flex flex-wrap gap-xs">
          {['alice', 'bob', 'carol'].map((username) => (
            <button
              key={username}
              type="button"
              data-testid={`authz-act-${username}`}
              onClick={() => void actAs(username)}
              className={`rounded-pill border px-sm py-xxs font-mono text-caption ${
                caller?.username === username
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant'
              }`}
            >
              act as {username}
            </button>
          ))}
        </div>
        <div data-testid="authz-documents" className="flex flex-col gap-xxs">
          {documents === null && (
            <p className="text-body-sm text-on-surface-variant">
              pick an identity — the document list re-filters per caller
            </p>
          )}
          {documents?.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">
              anonymous: the URL-rule layer answered 401 before any code ran
            </p>
          )}
          {documents?.map((doc) => (
            <div
              key={doc.id}
              data-testid="authz-doc-row"
              className="flex flex-wrap items-baseline gap-xs font-mono text-caption"
            >
              <span className="text-on-surface">{doc.id}</span>
              <span className="text-on-surface-variant">{doc.visibility}</span>
              <span className="text-on-surface-variant">owner {doc.owner}</span>
              <span className="text-on-surface-variant">— {doc.visibleBecause}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>guarded probes — each a different enforcement layer</p>
        <div className="flex flex-wrap gap-xs">
          <button
            type="button"
            data-testid="authz-probe-read"
            disabled={!caller}
            onClick={() =>
              void runProbe(
                'read alice-private',
                'in-use-case policy',
                `${BASE}/documents/doc-alice-private`,
                undefined,
              )
            }
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-50"
          >
            read alice's private doc
          </button>
          <button
            type="button"
            data-testid="authz-probe-publish"
            disabled={!caller}
            onClick={() =>
              void runProbe(
                'publish bob-private',
                '@PreAuthorize (role SpEL + @authz bean)',
                `${BASE}/documents/doc-bob-private/publish`,
                { method: 'POST' },
              )
            }
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-50"
          >
            publish bob's private doc
          </button>
          <button
            type="button"
            data-testid="authz-probe-owner"
            disabled={!caller}
            onClick={() =>
              void runProbe(
                'owner-view bob-team',
                '@PostAuthorize (returnObject)',
                `${BASE}/documents/doc-bob-team/owner-view`,
                undefined,
              )
            }
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-50"
          >
            owner-view bob's team doc
          </button>
          <button
            type="button"
            data-testid="authz-probe-trap"
            disabled={!caller}
            onClick={() => void runTrap()}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-50"
          >
            run the self-invocation trap
          </button>
        </div>
        {probe && (
          <div
            data-testid="authz-probe-result"
            className={`${CARD} ${probe.status === 200 ? 'text-on-surface' : 'text-error'}`}
          >
            {probe.label} · layer {probe.layer} · {probe.summary}
          </div>
        )}
        {trap && (
          <div data-testid="authz-trap-result" className={`${CARD} text-error`}>
            self-invocation as {trap.caller}: direct call checked →{' '}
            {trap.directAllowed ? 'allowed' : 'denied'}; internal call unchecked →{' '}
            {trap.internalAllowed ? 'ALLOWED (the check never ran)' : 'denied'} — the trap
          </div>
        )}
      </section>

      <p className={`${CARD} text-on-surface-variant`}>
        every 403 above is this guide's error envelope — code forbidden, a requestId, the reason —
        whichever of the three layers fired: the URL rule, the method annotation, or the
        in-use-case policy.
      </p>
    </div>
  );
}
