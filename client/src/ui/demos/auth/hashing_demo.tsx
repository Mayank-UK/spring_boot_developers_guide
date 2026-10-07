import { useState } from 'react';
import { authApiJson, isRecord, requireString } from './auth-demo-api';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `auth:hashing` — topic 23 (authentication), the
 * password-hashing section. The PasswordEncoder lab:
 * - hash a password at a chosen bcrypt cost, watching the milliseconds
 * - hash the SAME password twice — different salts, different hashes
 * - verify a CORRECT and a WRONG password against one hash, timings
 *   side by side (roughly equal: no timing oracle — the point)
 * - dissect any hash into version / cost / salt / body
 */

const HASH_URL = '/demo/auth/hashing';

interface HashResult {
  hash: string;
  cost: number;
  elapsedMs: number;
}

interface VerifyResult {
  matches: boolean;
  elapsedMs: number;
}

interface Anatomy {
  format: string;
  version: string;
  cost: number;
  salt: string;
  hashBody: string;
  notes: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'error'; failure: import('../../../core/error-handling/failures').Failure };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD = 'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption';

function parseHash(body: unknown): HashResult {
  if (!isRecord(body)) throw new Error('hash response must be an object');
  return {
    hash: requireString(body, 'hash'),
    cost: typeof body.cost === 'number' ? body.cost : 0,
    elapsedMs: typeof body.elapsedMs === 'number' ? body.elapsedMs : 0,
  };
}

function parseVerify(body: unknown): VerifyResult {
  if (!isRecord(body)) throw new Error('verify response must be an object');
  return {
    matches: body.matches === true,
    elapsedMs: typeof body.elapsedMs === 'number' ? body.elapsedMs : 0,
  };
}

function parseAnatomy(body: unknown): Anatomy {
  if (!isRecord(body)) throw new Error('anatomy response must be an object');
  return {
    format: requireString(body, 'format'),
    version: requireString(body, 'version'),
    cost: typeof body.cost === 'number' ? body.cost : 0,
    salt: requireString(body, 'salt'),
    hashBody: requireString(body, 'hashBody'),
    notes: Array.isArray(body.notes) ? body.notes.map(String) : [],
  };
}

export default function HashingDemo() {
  const [password, setPassword] = useState('correct horse battery staple');
  const [cost, setCost] = useState(10);
  const [hash, setHash] = useState<HashResult | null>(null);
  const [secondHash, setSecondHash] = useState<HashResult | null>(null);
  const [correct, setCorrect] = useState<VerifyResult | null>(null);
  const [wrong, setWrong] = useState<VerifyResult | null>(null);
  const [anatomy, setAnatomy] = useState<Anatomy | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  const callHash = async (target: 'first' | 'second'): Promise<void> => {
    const result = await authApiJson<HashResult>(
      `${HASH_URL}/hash`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password, cost }),
      },
      parseHash,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setPanel({ kind: 'idle' });
    if (target === 'first') {
      setHash(result.value);
      setSecondHash(null);
      setCorrect(null);
      setWrong(null);
      setAnatomy(null);
    } else {
      setSecondHash(result.value);
    }
  };

  const callVerify = async (mutate: boolean): Promise<void> => {
    if (hash === null) return;
    const result = await authApiJson<VerifyResult>(
      `${HASH_URL}/verify`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          password: mutate ? `${password}x` : password,
          hash: hash.hash,
        }),
      },
      parseVerify,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setPanel({ kind: 'idle' });
    if (mutate) {
      setWrong(result.value);
    } else {
      setCorrect(result.value);
    }
  };

  const callAnatomy = async (): Promise<void> => {
    if (hash === null) return;
    const result = await authApiJson<Anatomy>(
      `${HASH_URL}/compare?hash=${encodeURIComponent(hash.hash)}`,
      undefined,
      parseAnatomy,
    );
    if (!result.ok) {
      setPanel({ kind: 'error', failure: result.failure });
      return;
    }
    setPanel({ kind: 'idle' });
    setAnatomy(result.value);
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">password</span>
          <input
            data-testid="authn-h-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">bcrypt cost</span>
          <select
            data-testid="authn-h-cost"
            value={String(cost)}
            onChange={(event) => setCost(Number(event.target.value))}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            {[4, 6, 8, 10, 12].map((value) => (
              <option key={value} value={value}>
                {value} {value === 10 ? '(default)' : ''}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          data-testid="authn-h-hash"
          onClick={() => void callHash('first')}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          hash
        </button>
        <button
          type="button"
          data-testid="authn-hash-again"
          onClick={() => void callHash('second')}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          hash the same one again
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the hashing lab" failure={panel.failure} />}

      {hash && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>
            hashed at cost {hash.cost} in {hash.elapsedMs}ms
          </p>
          <div data-testid="authn-h-hash-result" className={`${CARD} break-all`}>
            {hash.hash}
          </div>
          {secondHash && (
            <div data-testid="authn-h-second" className={`${CARD} break-all text-on-surface-variant`}>
              {secondHash.hash}
              <div className="mt-xxs">
                same password, different salt — the two hashes share nothing
                {secondHash.hash === hash.hash ? ' (IDENTICAL — that would be a bug)' : ''}
              </div>
            </div>
          )}
          <div className="flex flex-wrap gap-xs">
            <button
              type="button"
              data-testid="authn-h-verify-correct"
              onClick={() => void callVerify(false)}
              className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
            >
              verify the correct password
            </button>
            <button
              type="button"
              data-testid="authn-h-verify-wrong"
              onClick={() => void callVerify(true)}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              verify a wrong password
            </button>
            <button
              type="button"
              data-testid="authn-h-anatomy"
              onClick={() => void callAnatomy()}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
            >
              dissect the format
            </button>
          </div>
        </section>
      )}

      {(correct || wrong) && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>the timing evidence — same order of magnitude, on purpose</p>
          <div data-testid="authn-h-timings" className="flex flex-col gap-xxs font-mono text-caption">
            {correct && (
              <div className={CARD}>
                correct → matches={String(correct.matches)} in {correct.elapsedMs}ms
              </div>
            )}
            {wrong && (
              <div className={CARD}>
                wrong → matches={String(wrong.matches)} in {wrong.elapsedMs}ms
              </div>
            )}
          </div>
        </section>
      )}

      {anatomy && (
        <section className="flex flex-col gap-xs">
          <p className={OVERLINE}>anatomy — every one of the 60 characters named</p>
          <div data-testid="authn-h-anatomy-result" className="flex flex-col gap-xxs font-mono text-caption">
            <div className={CARD}>
              {'$' + anatomy.version + '$' + anatomy.cost + '$' + anatomy.salt + anatomy.hashBody}
            </div>
            <div className={`${CARD} flex flex-col gap-xxs text-on-surface-variant`}>
              {anatomy.notes.map((note) => (
                <div key={note}>{note}</div>
              ))}
            </div>
          </div>
        </section>
      )}

      <p className={`${CARD} text-on-surface-variant`}>
        slow on purpose (2^cost rounds — each +1 doubles the work), salted per hash (no rainbow
        tables, no clustering), and the wrong password costs the same as the right one.
      </p>
    </div>
  );
}
