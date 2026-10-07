import { useState } from 'react';
import { securityApiJson, isRecord, requireNumber, requireString } from './security-demo-api';
import { ErrorPanel } from '../../components/error-panel';
import type { Failure } from '../../../core/error-handling/failures';

/**
 * Demo for DemoBlock `security:pii` — topic 25 (security), the PII
 * crypto-shred vault. The before/after IS the demo:
 * - STORE a subject's fields → ciphertext previews + key id come back
 * - READ while the key exists → plaintext, decrypted on request
 * - ERASE → the per-subject key is destroyed and the erasure is logged
 * - READ again → the SAME route now answers the typed undecryptable
 *   failure (erased: true) — the rows are still there; only the key died
 * - LOG → the append-only audit trail of every erasure
 */

const PII_URL = '/demo/security/pii';

interface Stored {
  subject: string;
  keyId: string;
  ciphertextPreview: Record<string, string>;
  fieldCount: number;
}

interface ReadView {
  subject: string;
  keyId: string;
  fields: Record<string, string>;
}

interface Erased {
  subject: string;
  keyId: string | null;
  ciphertextFieldsStillStored: number;
  dataUnreadableForever: boolean;
  logEntryId: string;
}

interface LogEntry {
  id: string;
  subjectId: string;
  keyId: string;
  action: string;
  at: string;
}

type ReadPanel =
  | { kind: 'idle' }
  | { kind: 'read'; view: ReadView }
  | { kind: 'undecryptable'; failure: Failure }
  | { kind: 'error'; failure: Failure };

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const CARD =
  'rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption break-all';

function parseStored(body: unknown): Stored {
  if (!isRecord(body)) throw new Error('store response must be an object');
  const preview: Record<string, string> = {};
  if (isRecord(body.ciphertextPreview)) {
    for (const [key, value] of Object.entries(body.ciphertextPreview)) {
      if (typeof value === 'string') preview[key] = value;
    }
  }
  return {
    subject: requireString(body, 'subject'),
    keyId: requireString(body, 'keyId'),
    ciphertextPreview: preview,
    fieldCount: requireNumber(body, 'fieldCount'),
  };
}

function parseReadView(body: unknown): ReadView {
  if (!isRecord(body)) throw new Error('read response must be an object');
  const fields: Record<string, string> = {};
  if (isRecord(body.fields)) {
    for (const [key, value] of Object.entries(body.fields)) {
      if (typeof value === 'string') fields[key] = value;
    }
  }
  return { subject: requireString(body, 'subject'), keyId: requireString(body, 'keyId'), fields };
}

function parseErased(body: unknown): Erased {
  if (!isRecord(body)) throw new Error('erase response must be an object');
  return {
    subject: requireString(body, 'subject'),
    keyId: typeof body.keyId === 'string' ? body.keyId : null,
    ciphertextFieldsStillStored: requireNumber(body, 'ciphertextFieldsStillStored'),
    dataUnreadableForever: body.dataUnreadableForever === true,
    logEntryId: requireString(body, 'logEntryId'),
  };
}

function parseLog(body: unknown): LogEntry[] {
  if (!Array.isArray(body)) throw new Error('log response must be an array');
  return body.map((entry) => {
    if (!isRecord(entry)) throw new Error('log entry must be an object');
    return {
      id: requireString(entry, 'id'),
      subjectId: requireString(entry, 'subjectId'),
      keyId: requireString(entry, 'keyId'),
      action: requireString(entry, 'action'),
      at: requireString(entry, 'at'),
    };
  });
}

export default function PiiDemo() {
  const [subject, setSubject] = useState('usr_demo_1');
  const [name, setName] = useState('Ada Lovelace');
  const [email, setEmail] = useState('ada@example.com');
  const [stored, setStored] = useState<Stored | null>(null);
  const [readPanel, setReadPanel] = useState<ReadPanel>({ kind: 'idle' });
  const [erased, setErased] = useState<Erased | null>(null);
  const [log, setLog] = useState<LogEntry[] | null>(null);
  const [panel, setPanel] = useState<{ kind: 'idle' } | { kind: 'error'; failure: Failure }>({
    kind: 'idle',
  });

  const readSubject = async (): Promise<void> => {
    const result = await securityApiJson(
      `${PII_URL}/read/${encodeURIComponent(subject)}`,
      undefined,
      parseReadView,
    );
    if (result.ok) {
      setReadPanel({ kind: 'read', view: result.value });
    } else if (result.failure.status === 404 && result.failure.code === 'not_found') {
      setReadPanel({ kind: 'undecryptable', failure: result.failure });
    } else {
      setReadPanel({ kind: 'error', failure: result.failure });
    }
  };

  const store = async (): Promise<void> => {
    const result = await securityApiJson(
      `${PII_URL}/store`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subject, fields: { name, email } }),
      },
      parseStored,
    );
    if (result.ok) {
      setPanel({ kind: 'idle' });
      setStored(result.value);
      setReadPanel({ kind: 'idle' });
      setErased(null);
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
  };

  const erase = async (): Promise<void> => {
    const result = await securityApiJson(
      `${PII_URL}/erase/${encodeURIComponent(subject)}`,
      { method: 'POST' },
      parseErased,
    );
    if (result.ok) {
      setPanel({ kind: 'idle' });
      setErased(result.value);
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
  };

  const loadLog = async (): Promise<void> => {
    const result = await securityApiJson(`${PII_URL}/log`, undefined, parseLog);
    if (result.ok) {
      setPanel({ kind: 'idle' });
      setLog(result.value);
    } else {
      setPanel({ kind: 'error', failure: result.failure });
    }
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-xs">
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">subject</span>
          <input
            data-testid="secpii-subject"
            value={subject}
            onChange={(event) => setSubject(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">name</span>
          <input
            data-testid="secpii-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <label className="flex flex-col gap-xxs">
          <span className="font-mono text-caption text-on-surface-variant">email</span>
          <input
            data-testid="secpii-email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </label>
        <button
          type="button"
          data-testid="secpii-store"
          onClick={() => void store()}
          className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
        >
          store (seal under a per-subject key)
        </button>
      </section>

      {panel.kind === 'error' && <ErrorPanel subject="the PII vault" failure={panel.failure} />}

      {stored !== null && (
        <section className="flex flex-col gap-xxs">
          <p className={OVERLINE}>
            stored — {stored.fieldCount} field(s) sealed under {stored.keyId} (ciphertext previews)
          </p>
          {Object.entries(stored.ciphertextPreview).map(([field, preview]) => (
            <div key={field} data-testid="secpii-stored" className={CARD}>
              {field}: {preview}
            </div>
          ))}
        </section>
      )}

      <section className="flex flex-col gap-xxs">
        <div className="flex flex-wrap gap-xs">
          <button
            type="button"
            data-testid="secpii-read"
            onClick={() => void readSubject()}
            className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
          >
            read
          </button>
          <button
            type="button"
            data-testid="secpii-erase"
            onClick={() => void erase()}
            className="rounded-pill border border-error bg-error/15 px-sm py-xxs font-mono text-caption text-on-surface"
          >
            erase (destroy the key)
          </button>
          <button
            type="button"
            data-testid="secpii-log"
            onClick={() => void loadLog()}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
          >
            erasure log
          </button>
        </div>
        {readPanel.kind === 'read' && (
          <div data-testid="secpii-read-result" className={`${CARD} text-secondary`}>
            {Object.entries(readPanel.view.fields)
              .map(([field, value]) => `${field}: ${value}`)
              .join(' · ')}
          </div>
        )}
        {readPanel.kind === 'undecryptable' && (
          <div data-testid="secpii-read-result" className={`${CARD} text-error`}>
            undecryptable — {readPanel.failure.message}
          </div>
        )}
        {readPanel.kind === 'error' && <ErrorPanel subject="the read" failure={readPanel.failure} />}
      </section>

      {erased !== null && (
        <section className="flex flex-col gap-xxs">
          <p className={OVERLINE}>erased — the receipt</p>
          <div data-testid="secpii-erased" className={CARD}>
            key {erased.keyId ?? '(already gone)'} destroyed ·{' '}
            {erased.ciphertextFieldsStillStored} ciphertext field(s) retained, unreadable forever ·
            receipt {erased.logEntryId}
          </div>
        </section>
      )}

      {log !== null && (
        <section className="flex flex-col gap-xxs">
          <p className={OVERLINE}>the append-only erasure log</p>
          {log.length === 0 && (
            <p className="font-mono text-caption text-on-surface-variant">no erasures yet</p>
          )}
          {log.map((entry) => (
            <div key={entry.id} data-testid="secpii-log-entry" className={CARD}>
              {entry.at} · {entry.subjectId} · {entry.keyId} · {entry.action}
            </div>
          ))}
        </section>
      )}
    </div>
  );
}
