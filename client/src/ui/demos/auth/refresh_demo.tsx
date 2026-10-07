import { useCallback, useEffect, useState } from 'react';
import { authApiJson, isRecord, requireString } from './auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `auth:refresh` — topic 23 (authentication), the
 * refresh-rotation section. The theft story as buttons:
 * - issue: a pair is born (a token FAMILY — generation 1 ACTIVE)
 * - exchange: the presented refresh token is spent, generation 2 minted
 * - the theft: present the SPENT token again → the whole family dies,
 *   and even the CURRENT token answers 401
 * - the families view: generations and revoked flags, hashes never shown
 */

const REFRESH_URL = '/demo/auth/refresh';

interface Pair {
  action: string;
  accessToken: string;
  refreshToken: string;
  familyId: string;
}

interface FamilyRow {
  familyId: string;
  username: string;
  revoked: boolean;
  generations: { state: string; issuedAt: string }[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'error'; failure: import('../../../core/error-handling/failures').Failure };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

function parsePair(body: unknown): Pair {
  if (!isRecord(body)) throw new Error('pair response must be an object');
  return {
    action: requireString(body, 'action'),
    accessToken: requireString(body, 'accessToken'),
    refreshToken: requireString(body, 'refreshToken'),
    familyId: requireString(body, 'familyId'),
  };
}

function parseFamilies(body: unknown): FamilyRow[] {
  if (!isRecord(body) || !Array.isArray(body.families)) {
    throw new Error('families response needs a families array');
  }
  return body.families.map((row) => ({
    familyId: requireString(row, 'familyId'),
    username: requireString(row, 'username'),
    revoked: isRecord(row) && row.revoked === true,
    generations: Array.isArray(isRecord(row) && row.generations)
      ? (row.generations as unknown[]).map((gen) => ({
          state: requireString(gen, 'state'),
          issuedAt: requireString(gen, 'issuedAt'),
        }))
      : [],
  }));
}

export default function RefreshDemo() {
  const [username, setUsername] = useState('alice');
  const [password, setPassword] = useState('alice-demo-pass-01');
  const [current, setCurrent] = useState<Pair | null>(null);
  const [spent, setSpent] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [families, setFamilies] = useState<FamilyRow[]>([]);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const refresh = useCallback(async (): Promise<void> => {
    const result = await authApiJson<FamilyRow[]>(
      `${REFRESH_URL}/families`,
      undefined,
      parseFamilies,
    );
    if (result.ok) setFamilies(result.value);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const issue = async (): Promise<void> => {
    const result = await authApiJson<Pair>(
      `${REFRESH_URL}/issue`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      },
      parsePair,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setPanel({ kind: 'idle' });
    setCurrent(result.value);
    setSpent(null);
    setNote(`family ${result.value.familyId} born — generation 1 ACTIVE`);
    await refresh();
  };

  const exchange = async (): Promise<void> => {
    if (current === null) return;
    const presented = current.refreshToken;
    const result = await authApiJson<Pair>(
      `${REFRESH_URL}/exchange`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: presented }),
      },
      parsePair,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setPanel({ kind: 'idle' });
    setSpent(presented);
    setCurrent(result.value);
    setNote('rotated — the presented token is now spent, a new generation is ACTIVE');
    await refresh();
  };

  const replayTheft = async (): Promise<void> => {
    if (spent === null) return;
    const result = await authApiJson<Pair>(
      `${REFRESH_URL}/exchange`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: spent }),
      },
      parsePair,
    );
    if (!result.ok) {
      setPanel({ kind: 'idle' });
      setNote(`theft detected — ${result.failure.message}`);
    }
    await refresh();
  };

  const tryCurrentAfterDeath = async (): Promise<void> => {
    if (current === null) return;
    const result = await authApiJson<Pair>(
      `${REFRESH_URL}/exchange`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: current.refreshToken }),
      },
      parsePair,
    );
    if (!result.ok) {
      setNote(`even the CURRENT token: ${result.failure.message}`);
      return;
    }
    setCurrent(result.value);
    setSpent(null);
    setNote('family still alive — exchange worked');
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">user</span>
          <select
            data-testid="authn-r-user"
            value={username}
            onChange={(event) => {
              setUsername(event.target.value);
              if (event.target.value === 'bob') setPassword('bob-demo-pass-01');
              if (event.target.value === 'carol') setPassword('carol-demo-pass-01');
            }}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            <option value="alice">alice</option>
            <option value="bob">bob</option>
            <option value="carol">carol</option>
          </select>
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">password</span>
          <input
            data-testid="authn-r-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <button
          type="button"
          data-testid="authn-r-issue"
          onClick={() => void issue()}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          issue a pair
        </button>
        <button
          type="button"
          data-testid="authn-r-exchange"
          onClick={() => void exchange()}
          disabled={current === null}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-50"
        >
          exchange (rotate)
        </button>
        <button
          type="button"
          data-testid="authn-r-theft"
          onClick={() => void replayTheft()}
          disabled={spent === null}
          className="rounded-pill border border-error px-sm py-xxs font-mono text-caption text-error disabled:opacity-50"
        >
          replay the SPENT token (theft)
        </button>
        <button
          type="button"
          data-testid="authn-r-current-after-death"
          onClick={() => void tryCurrentAfterDeath()}
          disabled={current === null}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-50"
        >
          try the current token
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the refresh demo" failure={panel.failure} />}

      {note && (
        <div data-testid="authn-r-note" className={`${CARD} text-on-surface`}>
          {note}
        </div>
      )}

      {current && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>the current pair — family {current.familyId}</p>
          <div data-testid="authn-r-current" className={`${CARD} break-all`}>
            <div className="text-on-surface">refresh: {current.refreshToken}</div>
            {spent && <div className="text-error">spent: {spent}</div>}
          </div>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the families view — generations, spent vs active, revoked</p>
        <div data-testid="authn-r-families" className="flex flex-col gap-xxs">
          {families.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">no families yet — issue a pair</p>
          )}
          {families.map((family) => (
            <div
              key={family.familyId}
              data-testid="authn-r-family-row"
              className={`flex flex-col gap-xxs rounded-sm border px-xs py-xxs font-mono text-caption ${
                family.revoked ? 'border-error' : 'border-outline'
              }`}
            >
              <div className="flex flex-wrap items-baseline gap-xs">
                <span className={family.revoked ? 'text-error' : 'text-on-surface'}>
                  {family.familyId}
                </span>
                <span className="text-on-surface-variant">{family.username}</span>
                {family.revoked && (
                  <span data-testid="authn-r-revoked" className="text-error">revoked</span>
                )}
                <span className="text-on-surface-variant">
                  {family.generations.map((generation) => generation.state).join(' → ')}
                </span>
              </div>
            </div>
          ))}
        </div>
      </section>

      <p className={`${CARD} text-on-surface-variant`}>
        rotation spends each token on use; a spent token replayed means theft — and the response
        is to kill the whole lineage, honest party included.
      </p>
    </div>
  );
}
