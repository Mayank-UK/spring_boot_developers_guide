import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `reqres:methods` — topic 09 (request-response), the
 * ResponseEntity section. One path, five verbs:
 * - GET    → 200 + a representation
 * - POST   → 201 + Location naming the created resource
 * - PUT    → 200 + an ETag that moves with each replacement
 * - PATCH  → 204, deliberately no body
 * - DELETE → 200 + a confirmation record
 *
 * The demo is a response INSPECTOR: it fetches the same path with the
 * chosen verb and renders exactly what the protocol delivered — status,
 * the signature headers (Location, ETag, x-teaching-note), and the body or
 * its deliberate absence. The status/headers/body decision is made in the
 * server's domain exhibit; nothing client-side knows what a verb answers
 * until the wire says so.
 */

type Verb = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

interface ObservedResponse {
  verb: Verb;
  status: number;
  location: string | null;
  etag: string | null;
  teachingNote: string | null;
  body: unknown;
  bodyEmpty: boolean;
}

type PanelState =
  | { kind: 'loading'; verb: Verb }
  | { kind: 'ready'; observed: ObservedResponse }
  | { kind: 'error'; failure: Failure };

/** Reads the raw exchange this demo exists to show — through the guarded pipeline. */
async function callMethod(config: AppConfig, verb: Verb): Promise<PanelState> {
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}/demo/reqres/methods`, {
        method: verb,
        headers: { accept: 'application/json' },
      });
    } catch (error) {
      throw new NetworkException('Could not reach the server.', { cause: error });
    }
    // Read as text first: a 204 legitimately has no body to parse as JSON.
    let text: string;
    try {
      text = await response.text();
    } catch (error) {
      throw new ServerException(`Request failed with status ${String(response.status)}.`, {
        status: response.status,
        cause: error,
      });
    }
    if (!response.ok) {
      let message = `Request failed with status ${String(response.status)}.`;
      let code: string | undefined;
      let requestId: string | undefined;
      try {
        const parsed: unknown = JSON.parse(text);
        if (isRecord(parsed) && isRecord(parsed.error)) {
          if (typeof parsed.error.message === 'string') message = parsed.error.message;
          if (typeof parsed.error.code === 'string') code = parsed.error.code;
          if (typeof parsed.error.requestId === 'string') requestId = parsed.error.requestId;
        }
      } catch {
        // no JSON envelope — keep the status-based message
      }
      throw new ServerException(message, {
        status: response.status,
        code: code ?? 'unknown_error',
        requestId,
      });
    }
    let body: unknown = null;
    let bodyEmpty = true;
    if (text.trim() !== '') {
      bodyEmpty = false;
      try {
        body = JSON.parse(text);
      } catch {
        body = text;
      }
    }
    return {
      verb,
      status: response.status,
      location: response.headers.get('location'),
      etag: response.headers.get('etag'),
      teachingNote: response.headers.get('x-teaching-note'),
      body,
      bodyEmpty,
    } satisfies ObservedResponse;
  });
  if (result.ok) return { kind: 'ready', observed: result.value };
  return { kind: 'error', failure: result.failure };
}

/* ---- presentation ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function VerbPicker({ active, onPick }: { active: Verb; onPick: (verb: Verb) => void }) {
  const verbs: Verb[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
  return (
    <div className="flex flex-wrap gap-xs" role="group" aria-label="HTTP verb">
      {verbs.map((verb) => (
        <button
          key={verb}
          type="button"
          data-testid={`reqres-verb-${verb.toLowerCase()}`}
          aria-pressed={verb === active}
          onClick={() => onPick(verb)}
          className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
            verb === active
              ? 'border-secondary bg-secondary/15 text-on-surface'
              : 'border-outline text-on-surface-variant hover:bg-surface-tint'
          }`}
        >
          {verb}
        </button>
      ))}
    </div>
  );
}

function StatusChip({ status }: { status: number }) {
  const tone =
    status >= 200 && status < 300
      ? 'border-secondary text-on-surface'
      : 'border-error text-error';
  return (
    <span
      data-testid="reqres-status"
      className={`rounded-sm border px-sm py-xxs font-mono text-body-sm ${tone}`}
    >
      {String(status)}
    </span>
  );
}

function ResponsePanel({ observed }: { observed: ObservedResponse }) {
  return (
    <div data-testid="reqres-response" className="flex flex-col gap-sm">
      <div className="flex flex-wrap items-center gap-sm">
        <StatusChip status={observed.status} />
        <span className="font-mono text-body-sm text-on-surface-variant">
          {observed.verb} /api/demo/reqres/methods
        </span>
      </div>

      {observed.teachingNote !== null && (
        <p data-testid="reqres-note" className="font-mono text-caption text-on-surface">
          x-teaching-note: {observed.teachingNote}
        </p>
      )}

      <div className="flex flex-col gap-xxs">
        <p className={OVERLINE}>signature headers</p>
        {observed.location === null && observed.etag === null ? (
          <p className="font-mono text-caption text-on-surface-variant">none for this verb</p>
        ) : (
          <>
            {observed.location !== null && (
              <p data-testid="reqres-location" className="font-mono text-caption text-on-surface">
                Location: {observed.location}
              </p>
            )}
            {observed.etag !== null && (
              <p data-testid="reqres-etag" className="font-mono text-caption text-on-surface">
                ETag: {observed.etag}
              </p>
            )}
          </>
        )}
      </div>

      <div className="flex flex-col gap-xxs">
        <p className={OVERLINE}>body</p>
        {observed.bodyEmpty ? (
          <p data-testid="reqres-empty-body" className="font-mono text-caption text-on-surface-variant">
            empty — a 204 answer, deliberately nothing to say
          </p>
        ) : (
          <pre
            data-testid="reqres-body"
            className="overflow-x-auto rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            {JSON.stringify(observed.body, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}

export default function MethodsDemo() {
  const [activeVerb, setActiveVerb] = useState<Verb>('GET');
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading', verb: 'GET' });
  const config = container.appConfig;

  const call = useCallback(
    (verb: Verb) => {
      setPanel({ kind: 'loading', verb });
      void callMethod(config, verb).then(setPanel);
    },
    [config],
  );

  useEffect(() => {
    call('GET');
  }, [call]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>one path, five verbs — fire each and read the full answer</p>
        <VerbPicker
          active={activeVerb}
          onPick={(verb) => {
            setActiveVerb(verb);
            call(verb);
          }}
        />
      </section>

      <section className="flex flex-col gap-sm">
        {panel.kind === 'loading' && (
          <p data-testid="reqres-loading" className="text-body-sm text-on-surface-variant">
            firing {panel.verb}…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the verb matrix" failure={panel.failure} />}
        {panel.kind === 'ready' && <ResponsePanel observed={panel.observed} />}
      </section>
    </div>
  );
}
