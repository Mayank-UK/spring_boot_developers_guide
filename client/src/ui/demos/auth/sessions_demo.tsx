import { useState } from 'react';
import { authApiJson, isRecord, requireString } from './auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `auth:sessions` — topic 23 (authentication), the
 * login-flow section. The session island as a console:
 * - login: pick a seeded user (alice/bob/carol) + password; wrong
 *   password shows the ONE 401 shape (no oracle)
 * - the login response's user + the session cookie's attributes
 * - /me: what the request's SecurityContext says with the cookie
 *   attached (or the chain's 401 without one)
 * - logout: kills the session; /me afterwards refuses it
 *
 * The component holds no session state of its own — the browser cookie
 * jar (credentials: 'include') is the whole mechanism, mirrored live.
 */

const SESSION_URL = '/demo/auth/session';

interface UserView {
  id: string;
  username: string;
  roles: string[];
}

interface LoginResponse {
  user: UserView;
  via: string;
  session: { cookie: Record<string, unknown>; fixationProtected: boolean };
}

interface MeResponse {
  user: UserView;
  via: string;
}

interface FlowEvent {
  action: string;
  status: number | 'net';
  detail: string;
  at: string;
}

type PanelState = { kind: 'idle' } | { kind: 'error'; failure: import('../../../core/error-handling/failures').Failure };

function parseUser(row: unknown): UserView {
  if (!isRecord(row)) throw new Error('user must be an object');
  return {
    id: requireString(row, 'id'),
    username: requireString(row, 'username'),
    roles: Array.isArray(row.roles) ? row.roles.map(String) : [],
  };
}

function parseLoginResponse(body: unknown): LoginResponse {
  if (!isRecord(body) || !isRecord(body.user) || !isRecord(body.session)) {
    throw new Error('login response needs user and session objects');
  }
  const cookie = isRecord(body.session.cookie) ? body.session.cookie : {};
  return {
    user: parseUser(body.user),
    via: requireString(body, 'via'),
    session: {
      cookie,
      fixationProtected: body.session.fixationProtected === true,
    },
  };
}

function parseMeResponse(body: unknown): MeResponse {
  if (!isRecord(body) || !isRecord(body.user)) throw new Error('me response needs a user');
  return { user: parseUser(body.user), via: requireString(body, 'via') };
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

const USERS = [
  { username: 'alice', password: 'alice-demo-pass-01' },
  { username: 'bob', password: 'bob-demo-pass-01' },
  { username: 'carol', password: 'carol-demo-pass-01' },
];

export default function SessionsDemo() {
  const [username, setUsername] = useState('alice');
  const [password, setPassword] = useState('alice-demo-pass-01');
  const [loginResponse, setLoginResponse] = useState<LoginResponse | null>(null);
  const [me, setMe] = useState<MeResponse | null>(null);
  const [events, setEvents] = useState<FlowEvent[]>([]);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const record = (action: string, ok: boolean, detail: string) => {
    setEvents((prior) => [
      { action, status: ok ? 200 : 401, detail, at: new Date().toLocaleTimeString() },
      ...prior.slice(0, 7),
    ]);
  };

  const callMe = async (): Promise<void> => {
    const result = await authApiJson<MeResponse>(`${SESSION_URL}/me`, undefined, parseMeResponse);
    if (result.ok) {
      setMe(result.value);
      record('GET /me', true, `${result.value.user.username} via ${result.value.via}`);
    } else {
      setMe(null);
      record('GET /me', false, '401 — the chain refused the anonymous request');
    }
  };

  const callLogin = async (username: string, password: string): Promise<void> => {
    const result = await authApiJson<LoginResponse>(
      SESSION_URL + '/login',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
        credentials: 'include',
      },
      parseLoginResponse,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      setLoginResponse(null);
      record('POST /login', false, '401 — invalid credentials (one shape, no oracle)');
      return;
    }
    setPanel({ kind: 'idle' });
    setLoginResponse(result.value);
    record('POST /login', true, `${result.value.user.username} logged in (session saved)`);
    void callMe();
  };

  const callLogout = async (): Promise<void> => {
    await authApiJson(`${SESSION_URL}/logout`, { method: 'POST' }, () => null);
    setLoginResponse(null);
    setMe(null);
    record('POST /logout', true, 'session invalidated — /me will refuse the dead cookie');
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">user</span>
          <select
            data-testid="authn-s-user"
            value={username}
            onChange={(event) => {
              setUsername(event.target.value);
              const seeded = USERS.find((candidate) => candidate.username === event.target.value);
              if (seeded) setPassword(seeded.password);
            }}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            {USERS.map((candidate) => (
              <option key={candidate.username} value={candidate.username}>
                {candidate.username}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">password</span>
          <input
            data-testid="authn-s-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <button
          type="button"
          data-testid="authn-s-login"
          onClick={() => void callLogin(username, password)}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          login
        </button>
        <button
          type="button"
          data-testid="authn-s-me"
          onClick={() => void callMe()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          GET /me
        </button>
        <button
          type="button"
          data-testid="authn-s-logout"
          onClick={() => void callLogout()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          logout
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the session demo" failure={panel.failure} />}

      {loginResponse && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>logged in — the cookie the browser now holds</p>
          <div data-testid="authn-s-login-response" className={CARD}>
            <div className="text-on-surface">
              {loginResponse.user.username} · roles: {loginResponse.user.roles.join(', ')}
            </div>
            <div className="text-on-surface-variant">
              cookie {String(loginResponse.session.cookie.name ?? 'JSESSIONID')} · httpOnly=
              {String(loginResponse.session.cookie.httpOnly)} · sameSite=
              {String(loginResponse.session.cookie.sameSite)} · secure=
              {String(loginResponse.session.cookie.secure)}
            </div>
            <div className="text-on-surface-variant">
              fixationProtected={String(loginResponse.session.fixationProtected)} — the session id
              rotated at login
            </div>
          </div>
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the /me probe — what the SecurityContext says</p>
        {me ? (
          <div data-testid="authn-s-me-result" className={CARD}>
            {me.user.username} · {me.user.roles.join(', ')} · via {me.via}
          </div>
        ) : (
          <p className="text-body-sm text-on-surface-variant">
            not authenticated (yet, or anymore) — call /me with and without the cookie
          </p>
        )}
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>flow events</p>
        <div data-testid="authn-s-events" className="flex flex-col gap-xxs">
          {events.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">nothing yet — log in</p>
          )}
          {events.map((event) => (
            <div
              key={`${event.at}-${event.action}`}
              data-testid="authn-s-event"
              className="flex flex-wrap items-baseline gap-xs font-mono text-caption"
            >
              <span className="text-on-surface-variant">{event.at}</span>
              <span className={event.status === 200 ? 'text-on-surface' : 'text-error'}>
                {event.status}
              </span>
              <span>{event.action}</span>
              <span className="text-on-surface-variant">{event.detail}</span>
            </div>
          ))}
        </div>
      </section>

      <p className={`${CARD} text-on-surface-variant`}>
        the browser's cookie jar carries the session (credentials: 'include'); this component
        never sees the cookie itself — only its effects.
      </p>
    </div>
  );
}
