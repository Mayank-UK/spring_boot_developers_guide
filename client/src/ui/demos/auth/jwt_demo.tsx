import { useState } from 'react';
import { authApiJson, isRecord, requireString } from './auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `auth:jwt` — topic 23 (authentication), the
 * JWT-and-resource-server section. The issuer and verifier side by side:
 * - mint: credentials (+ a TTL hook down to 1s) → token + the decoded
 *   claims PREVIEW (what anyone can read — no trust, just base64)
 * - call the protected resource with the Bearer → TRUSTED claims (the
 *   resource server verified the signature before the controller ran)
 * - attack it: the same token with one flipped signature character →
 *   the resource server's 401 with WWW-Authenticate error detail
 */

const JWT_URL = '/demo/auth/jwt'; // apiBaseUrl already contributes /api

interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  decoded: Record<string, unknown>;
}

interface ProtectedView {
  status: number;
  sub?: string;
  roles?: string[];
  jti?: string;
  via?: string;
  message: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'error'; failure: import('../../../core/error-handling/failures').Failure };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

function parseToken(body: unknown): TokenResponse {
  if (!isRecord(body)) throw new Error('token response must be an object');
  const decoded = isRecord(body.decoded) ? body.decoded : {};
  return {
    accessToken: requireString(body, 'accessToken'),
    tokenType: requireString(body, 'tokenType'),
    expiresIn: typeof body.expiresIn === 'number' ? body.expiresIn : 0,
    decoded,
  };
}

function parseProtected(body: unknown): ProtectedView {
  if (!isRecord(body)) throw new Error('protected response must be an object');
  return {
    status: 200,
    sub: typeof body.sub === 'string' ? body.sub : undefined,
    roles: Array.isArray(body.roles) ? body.roles.map(String) : undefined,
    jti: typeof body.jti === 'string' ? body.jti : undefined,
    via: typeof body.via === 'string' ? body.via : undefined,
    message: requireString(body, 'message'),
  };
}

export default function JwtDemo() {
  const [username, setUsername] = useState('alice');
  const [password, setPassword] = useState('alice-demo-pass-01');
  const [ttl, setTtl] = useState(900);
  const [token, setToken] = useState<TokenResponse | null>(null);
  const [protectedView, setProtectedView] = useState<ProtectedView | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const mint = async (): Promise<void> => {
    const result = await authApiJson<TokenResponse>(
      `${JWT_URL}/token`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password, ttlSeconds: ttl }),
      },
      parseToken,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      setToken(null);
      return;
    }
    setPanel({ kind: 'idle' });
    setToken(result.value);
    setProtectedView(null);
  };

  const callProtected = async (tamper: boolean): Promise<void> => {
    if (token === null) return;
    let bearer = token.accessToken;
    if (tamper) {
      bearer = bearer.slice(0, -1) + (bearer.endsWith('A') ? 'B' : 'A');
    }
    const result = await authApiJson<ProtectedView>(
      `${JWT_URL}/protected`,
      { headers: { Authorization: `Bearer ${bearer}` } },
      parseProtected,
    );
    if (result.ok) {
      setProtectedView(result.value);
    } else {
      setProtectedView({
        status: 401,
        message:
          '401 — the resource server rejected the token (signature or expiry), WWW-Authenticate carries the error detail',
      });
    }
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">user</span>
          <select
            data-testid="authn-j-user"
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
            data-testid="authn-j-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">ttlSeconds</span>
          <select
            data-testid="authn-j-ttl"
            value={String(ttl)}
            onChange={(event) => setTtl(Number(event.target.value))}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            <option value="900">900 (the default 15m)</option>
            <option value="1">1 (watch it expire)</option>
          </select>
        </label>
        <button
          type="button"
          data-testid="authn-j-mint"
          onClick={() => void mint()}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          mint token
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the JWT demo" failure={panel.failure} />}

      {token && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>
            minted — {token.expiresIn}s TTL, and the decoded preview (anyone can read this)
          </p>
          <div data-testid="authn-j-token" className={`${CARD} break-all`}>
            <div className="text-on-surface">{token.accessToken}</div>
            <div className="text-on-surface-variant">
              sub={String(token.decoded.sub ?? '')} · jti={String(token.decoded.jti ?? '')} ·
              exp={String(token.decoded.exp ?? '')} · roles={String(token.decoded.roles ?? '')}
            </div>
          </div>
          <div className="flex flex-wrap gap-xs">
            <button
              type="button"
              data-testid="authn-j-call"
              onClick={() => void callProtected(false)}
              className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
            >
              call protected with Bearer
            </button>
            <button
              type="button"
              data-testid="authn-j-tamper"
              onClick={() => void callProtected(true)}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              call with a tampered token
            </button>
          </div>
        </section>
      )}

      {protectedView && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>the resource server's answer</p>
          <div
            data-testid="authn-j-protected"
            className={`${CARD} ${protectedView.status === 200 ? 'text-on-surface' : 'text-error'}`}
          >
            {protectedView.status === 200 ? (
              <>
                <div>
                  {protectedView.sub} · roles: {protectedView.roles?.join(', ')} · via{' '}
                  {protectedView.via} · jti {protectedView.jti}
                </div>
                <div className="text-on-surface-variant">{protectedView.message}</div>
              </>
            ) : (
              protectedView.message
            )}
          </div>
        </section>
      )}

      <p className={`${CARD} text-on-surface-variant`}>
        the mint panel is a PREVIEW (base64, no trust); the protected panel is VERIFIED — the
        signature check happened in the chain before any controller ran.
      </p>
    </div>
  );
}
