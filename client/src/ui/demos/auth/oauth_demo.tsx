import { useState } from 'react';
import { authApiJson, isRecord, requireString } from './auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `auth:oauth` — topic 23 (authentication), the
 * authorization-code flow section. The CLIENT APP's side of the dance
 * (the server plays the authorization server):
 * - the demo invents the PKCE verifier + S256 challenge and a state
 * - 'send the owner' → the authorize endpoint (login simulation +
 *   consent, attackable: decline consent)
 * - the redirect arrives — the demo checks the state echo (CSRF)
 * - exchange at /token with the verifier (attackable: wrong verifier,
 *   replayed code)
 */

const OAUTH_URL = '/demo/auth/oauth';
const REDIRECT_URI = 'http://localhost:5176/oauth/callback';
const CLIENT_ID = 'sdg-console';

interface AuthorizeResponse {
  redirectTo: string;
  code: string;
  state: string;
  expiresAt: string;
}

interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  familyId: string;
  grant: string;
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'error'; failure: import('../../../core/error-handling/failures').Failure };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

/** The client's PKCE pair: verifier invented here, challenge = S256(verifier). */
async function s256(verifier: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(verifier),
  );
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function parseAuthorize(body: unknown): AuthorizeResponse {
  if (!isRecord(body)) throw new Error('authorize response must be an object');
  return {
    redirectTo: requireString(body, 'redirectTo'),
    code: requireString(body, 'code'),
    state: requireString(body, 'state'),
    expiresAt: requireString(body, 'expiresAt'),
  };
}

function parseToken(body: unknown): TokenResponse {
  if (!isRecord(body)) throw new Error('token response must be an object');
  return {
    accessToken: requireString(body, 'accessToken'),
    refreshToken: requireString(body, 'refreshToken'),
    familyId: requireString(body, 'familyId'),
    grant: requireString(body, 'grant'),
  };
}

export default function OAuthDemo() {
  const [username, setUsername] = useState('alice');
  const [password, setPassword] = useState('alice-demo-pass-01');
  const [consent, setConsent] = useState(true);
  const [verifier, setVerifier] = useState('demo-verifier-from-the-client-01');
  const [state, setState] = useState('demo-state-xyz');
  const [authorization, setAuthorization] = useState<AuthorizeResponse | null>(null);
  const [tokens, setTokens] = useState<TokenResponse | null>(null);
  const [flowNote, setFlowNote] = useState<string | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const runAuthorize = async (): Promise<void> => {
    setTokens(null);
    setFlowNote(null);
    const challenge = await s256(verifier);
    const result = await authApiJson<AuthorizeResponse>(
      `${OAUTH_URL}/authorize`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          responseType: 'code',
          clientId: CLIENT_ID,
          redirectUri: REDIRECT_URI,
          state,
          codeChallenge: challenge,
          codeChallengeMethod: 'S256',
          username,
          password,
          consent,
        }),
      },
      parseAuthorize,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      setAuthorization(null);
      return;
    }
    setPanel({ kind: 'idle' });
    setAuthorization(result.value);
    const stateEchoes = result.value.state === state;
    setFlowNote(
      stateEchoes
        ? 'the redirect arrived — state echoes (this client initiated it)'
        : 'STATE MISMATCH — a real client must refuse this redirect',
    );
  };

  const runExchange = async (attack: 'none' | 'wrong-verifier' | 'replay'): Promise<void> => {
    if (authorization === null) return;
    const result = await authApiJson<TokenResponse>(
      `${OAUTH_URL}/token`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          grantType: 'authorization_code',
          code: authorization.code,
          redirectUri: REDIRECT_URI,
          clientId: CLIENT_ID,
          codeVerifier: attack === 'wrong-verifier' ? 'attacker-guessed-this' : verifier,
        }),
      },
      parseToken,
    );
    if (result.ok) {
      setPanel({ kind: 'idle' });
      setTokens(result.value);
      setFlowNote('exchanged — the code is now spent (single-use)');
    } else {
      setTokens(null);
      setFlowNote(
        attack === 'replay'
          ? '401 — the code was already used: codes are single-use'
          : '401 — PKCE failed: the verifier does not hash to the challenge',
      );
    }
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">resource owner</span>
          <select
            data-testid="authn-o-user"
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
            data-testid="authn-o-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">PKCE verifier</span>
          <input
            data-testid="authn-o-verifier"
            value={verifier}
            onChange={(event) => setVerifier(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">state</span>
          <input
            data-testid="authn-o-state"
            value={state}
            onChange={(event) => setState(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex items-center gap-xxs">
          <input
            data-testid="authn-o-consent"
            type="checkbox"
            checked={consent}
            onChange={(event) => setConsent(event.target.checked)}
          />
          <span className="font-mono text-caption text-on-surface-variant">consent</span>
        </label>
        <button
          type="button"
          data-testid="authn-o-authorize"
          onClick={() => void runAuthorize()}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          run the dance
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the OAuth demo" failure={panel.failure} />}

      {authorization && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>the redirect the client received</p>
          <div data-testid="authn-o-redirect" className={`${CARD} break-all`}>
            <div className="text-on-surface">{authorization.redirectTo}</div>
            {flowNote && <div className="text-on-surface-variant">{flowNote}</div>}
          </div>
          <div className="flex flex-wrap gap-xs">
            <button
              type="button"
              data-testid="authn-o-exchange"
              onClick={() => void runExchange('none')}
              className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
            >
              exchange at /token
            </button>
            <button
              type="button"
              data-testid="authn-o-wrong-verifier"
              onClick={() => void runExchange('wrong-verifier')}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              exchange with the wrong verifier
            </button>
            <button
              type="button"
              data-testid="authn-o-replay"
              onClick={() => void runExchange('replay')}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              replay the code
            </button>
          </div>
        </section>
      )}

      {tokens && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>tokens (grant {tokens.grant})</p>
          <div data-testid="authn-o-tokens" className={`${CARD} break-all`}>
            <div className="text-on-surface">access: {tokens.accessToken.slice(0, 48)}…</div>
            <div className="text-on-surface-variant">
              refresh: {tokens.refreshToken.slice(0, 16)}… · family {tokens.familyId} · grant{' '}
              {tokens.grant}
            </div>
          </div>
        </section>
      )}

      <p className={`${CARD} text-on-surface-variant`}>
        this component IS the client app: it invents the PKCE pair, checks the state echo, and
        exchanges code+verifier server-to-server. The authorization server half lives at
        /api/demo/auth/oauth.
      </p>
    </div>
  );
}
