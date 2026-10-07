import { useCallback, useEffect, useState } from 'react';
import { authApiJson, isRecord, requireString } from './auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `auth:api-keys` — topic 23 (authentication), the
 * api-keys section. The key lifecycle as a workbench:
 * - issue: password proof → the raw key shown ONCE (never again)
 * - list: the redacted view (display prefixes, no key material)
 * - whoami: call it with the last-issued key attached (or none, or a
 *   mangled one) — the proof the key IS a principal
 * - revoke: the next whoami with the dead key is a 401
 */

const KEYS_URL = '/demo/auth/apikeys';

interface IssuedKey {
  id: string;
  apiKey: string;
  displayPrefix: string;
  username: string;
}

interface KeyRow {
  id: string;
  redacted: string;
  username: string;
  createdAt: string;
  lastUsedAt: string;
}

interface Whoami {
  username: string;
  roles: string[];
  via: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'error'; failure: import('../../../core/error-handling/failures').Failure }
  | { kind: 'ready' };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

function parseIssued(body: unknown): IssuedKey {
  if (!isRecord(body)) throw new Error('issue response must be an object');
  return {
    id: requireString(body, 'id'),
    apiKey: requireString(body, 'apiKey'),
    displayPrefix: requireString(body, 'displayPrefix'),
    username: requireString(body, 'username'),
  };
}

function parseKeys(body: unknown): KeyRow[] {
  if (!isRecord(body) || !Array.isArray(body.keys)) {
    throw new Error('keys response needs a keys array');
  }
  return body.keys.map((row) => ({
    id: requireString(row, 'id'),
    redacted: requireString(row, 'redacted'),
    username: requireString(row, 'username'),
    createdAt: requireString(row, 'createdAt'),
    lastUsedAt: requireString(row, 'lastUsedAt'),
  }));
}

function parseWhoami(body: unknown): Whoami {
  if (!isRecord(body) || !isRecord(body.user)) throw new Error('whoami needs a user');
  return {
    username: requireString(body.user, 'username'),
    roles: Array.isArray(body.user.roles) ? body.user.roles.map(String) : [],
    via: requireString(body, 'via'),
  };
}

export default function ApiKeysDemo() {
  const [username, setUsername] = useState('alice');
  const [password, setPassword] = useState('alice-demo-pass-01');
  const [issued, setIssued] = useState<IssuedKey | null>(null);
  const [keys, setKeys] = useState<KeyRow[]>([]);
  const [whoami, setWhoami] = useState<{ status: number; view: Whoami | null } | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });

  const refresh = useCallback(async (): Promise<void> => {
    const result = await authApiJson<KeyRow[]>(KEYS_URL, undefined, parseKeys);
    if (result.ok) setKeys(result.value);
  }, []);

  useEffect(() => {
    // the listing is chain-protected (authenticated) — anonymous here is a
    // 401 the demo renders as empty rather than an error
    void authApiJson<KeyRow[]>(KEYS_URL, undefined, parseKeys).then((result) => {
      if (result.ok) setKeys(result.value);
      setPanel({ kind: 'ready' });
    });
  }, []);

  const issue = async (): Promise<void> => {
    const result = await authApiJson<IssuedKey>(
      KEYS_URL,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      },
      parseIssued,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setPanel({ kind: 'ready' });
    setIssued(result.value);
    await refresh();
  };

  const callWhoami = async (mode: 'current' | 'mangled' | 'none'): Promise<void> => {
    const key = issued?.apiKey ?? '';
    const header =
      mode === 'none' || key === ''
        ? undefined
        : { 'X-API-Key': mode === 'mangled' ? key.slice(0, -1) + (key.endsWith('A') ? 'B' : 'A') : key };
    const result = await authApiJson<Whoami>(
      `${KEYS_URL}/whoami`,
      header === undefined ? undefined : { headers: header },
      parseWhoami,
    );
    if (result.ok) {
      setWhoami({ status: 200, view: result.value });
    } else {
      setWhoami({ status: 401, view: null });
    }
  };

  const revoke = async (id: string): Promise<void> => {
    await authApiJson(`${KEYS_URL}/${id}`, { method: 'DELETE' }, () => null);
    await refresh();
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">issue for</span>
          <input
            data-testid="authn-k-user"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">password</span>
          <input
            data-testid="authn-k-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <button
          type="button"
          data-testid="authn-k-issue"
          onClick={() => void issue()}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          issue key
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the API key demo" failure={panel.failure} />}

      {issued && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>issued — shown this once, stored as its SHA-256</p>
          <div data-testid="authn-k-issued" className={`${CARD} break-all`}>
            <div className="text-on-surface">{issued.apiKey}</div>
            <div className="text-on-surface-variant">
              {issued.username} · listings will show {issued.displayPrefix}… and nothing more
            </div>
          </div>
          <div className="flex flex-wrap gap-xs">
            <button
              type="button"
              data-testid="authn-k-whoami"
              onClick={() => void callWhoami('current')}
              className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
            >
              whoami with this key
            </button>
            <button
              type="button"
              data-testid="authn-k-whoami-mangled"
              onClick={() => void callWhoami('mangled')}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              whoami with a mangled key
            </button>
            <button
              type="button"
              data-testid="authn-k-whoami-none"
              onClick={() => void callWhoami('none')}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              whoami with no key
            </button>
          </div>
        </section>
      )}

      {whoami && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>the whoami verdict</p>
          <div
            data-testid="authn-k-whoami-result"
            className={`${CARD} ${whoami.status === 200 ? 'text-on-surface' : 'text-error'}`}
          >
            {whoami.view
              ? `${whoami.view.username} · ${whoami.view.roles.join(', ')} · via ${whoami.view.via}`
              : '401 — the key identified no one (wrong and absent look identical)'}
          </div>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the redacted list (authenticated view)</p>
        <div data-testid="authn-k-list" className="flex flex-col gap-xxs">
          {keys.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">
              no keys visible — issue one (the listing needs an authenticated request)
            </p>
          )}
          {keys.map((row) => (
            <div
              key={row.id}
              data-testid="authn-k-row"
              className="flex flex-wrap items-baseline gap-xs font-mono text-caption"
            >
              <span className="text-on-surface">{row.redacted}</span>
              <span className="text-on-surface-variant">{row.username}</span>
              <span className="text-on-surface-variant">{row.id}</span>
              <button
                type="button"
                data-testid="authn-k-revoke"
                onClick={() => void revoke(row.id)}
                className="rounded-pill border border-outline px-xs py-xxs font-mono text-caption text-on-surface-variant hover:border-error hover:text-error"
              >
                revoke
              </button>
            </div>
          ))}
        </div>
      </section>

      <p className={`${CARD} text-on-surface-variant`}>
        keys are born random (32 bytes), prefixed sdg_key_, shown once, stored hashed — and a
        revoked key is dead on its very next request.
      </p>
    </div>
  );
}
