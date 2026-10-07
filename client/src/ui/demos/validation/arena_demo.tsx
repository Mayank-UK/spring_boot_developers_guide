import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `validation:arena` — topic 10 (validation), the
 * choosing-between-them section. A payload editor pointed at the arena's two
 * endpoints over the SAME wire DTO:
 * - POST /api/demo/validation/arena        (analyst) — 200 verdict table +
 *   the 400 envelope preview the strict endpoint would emit for the payload
 * - POST /api/demo/validation/arena/strict (production) — @Valid: invalid
 *   payloads get the real 400 error envelope (rendered by the error panel)
 *
 * Unlike the http demo (which refuses to send malformed JSON), this editor
 * sends EXACTLY what you typed: the hostile-input premise, live. The server
 * judges — the client never pre-validates away the lesson.
 */

type Endpoint = 'analyst' | 'strict';

interface ArenaViolation {
  annotation: string;
  message: string;
}

interface FieldVerdict {
  field: string;
  valueClassification: string;
  violations: ArenaViolation[];
}

interface EnvelopePreview {
  error: {
    fields: Record<string, string>;
    code: string;
    message: string;
    requestId: string;
  };
}

interface ArenaVerdict {
  accepted: boolean;
  perField: FieldVerdict[];
  envelopeComparison: EnvelopePreview | null;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; verdict: ArenaVerdict }
  | { kind: 'error'; failure: Failure };

const DEFAULT_PAYLOAD = `{
  "name": "Ada Lovelace",
  "email": "ada@example.com",
  "score": 87,
  "eventDate": "2020-01-15",
  "password": "Sup3r$ecret!",
  "confirmStart": "2026-11-01",
  "confirmEnd": "2026-11-08"
}`;

interface Preset {
  id: string;
  label: string;
  payload: string;
}

const PRESETS: Preset[] = [
  { id: 'valid', label: 'valid', payload: DEFAULT_PAYLOAD },
  {
    id: 'kitchen-sink',
    label: 'every violation',
    payload: `{
  "name": "",
  "email": "not-an-email",
  "score": 101,
  "eventDate": "2030-01-01",
  "password": "",
  "confirmStart": "2026-11-08",
  "confirmEnd": "2026-11-01"
}`,
  },
  {
    id: 'optional-nulls',
    label: 'optional fields',
    payload: `{
  "name": "Grace",
  "email": "grace@example.com",
  "score": 99,
  "password": "Sup3r$ecret!"
}`,
  },
  {
    id: 'malformed',
    label: 'malformed JSON',
    payload: '{"name": ',
  },
];

/* ---- the guarded send: one fetch, whatever bytes you typed ---- */

async function sendArena(
  config: AppConfig,
  endpoint: Endpoint,
  bodyText: string,
): Promise<PanelState> {
  const path = endpoint === 'analyst'
    ? '/demo/validation/arena'
    : '/demo/validation/arena/strict';

  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: bodyText,
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
      if (isRecord(parsed) && isRecord(parsed.error)) {
        throw new ServerException(
          typeof parsed.error.message === 'string'
            ? parsed.error.message
            : `Request failed with status ${String(response.status)}.`,
          {
            status: response.status,
            code: typeof parsed.error.code === 'string' ? parsed.error.code : 'unknown_error',
            requestId:
              typeof parsed.error.requestId === 'string' ? parsed.error.requestId : undefined,
          },
        );
      }
      throw new ServerException(`Request failed with status ${String(response.status)}.`, {
        status: response.status,
      });
    }
    return parseDto(parsed, response.status, parseArenaVerdict);
  });
  if (result.ok) return { kind: 'ready', verdict: result.value };
  return { kind: 'error', failure: result.failure };
}

/* ---- runtime validation of the stable wire shape (the client's mirror) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseArenaVerdict(body: unknown): ArenaVerdict {
  if (!isRecord(body)) throw new Error('arena verdict must be an object');
  if (typeof body.accepted !== 'boolean') throw new Error('accepted must be a boolean');
  if (!Array.isArray(body.perField)) throw new Error('perField must be an array');
  for (const row of body.perField) {
    if (
      !isRecord(row) ||
      typeof row.field !== 'string' ||
      typeof row.valueClassification !== 'string' ||
      !Array.isArray(row.violations)
    ) {
      throw new Error('each field verdict needs field, valueClassification, violations');
    }
    for (const violation of row.violations) {
      if (
        !isRecord(violation) ||
        typeof violation.annotation !== 'string' ||
        typeof violation.message !== 'string'
      ) {
        throw new Error('each violation needs annotation and message');
      }
    }
  }
  let envelopeComparison: EnvelopePreview | null = null;
  if (body.envelopeComparison !== null && body.envelopeComparison !== undefined) {
    const error = isRecord(body.envelopeComparison) ? body.envelopeComparison.error : undefined;
    if (!isRecord(error) || typeof error.code !== 'string' || typeof error.message !== 'string') {
      throw new Error('envelopeComparison needs error.code and error.message');
    }
    envelopeComparison = body.envelopeComparison as unknown as EnvelopePreview;
  }
  return {
    accepted: body.accepted,
    perField: body.perField as unknown as FieldVerdict[],
    envelopeComparison,
  };
}

/* ---- small shared presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function VerdictTable({ verdict }: { verdict: ArenaVerdict }) {
  return (
    <table data-testid="arena-verdict-table" className="w-full border-collapse text-body-sm">
      <thead>
        <tr className="text-left text-on-surface-variant">
          <th className="border-b border-outline px-xs py-xs font-semibold">field</th>
          <th className="border-b border-outline px-xs py-xs font-semibold">value</th>
          <th className="border-b border-outline px-xs py-xs font-semibold">violations</th>
        </tr>
      </thead>
      <tbody className="text-on-surface">
        {verdict.perField.map((row) => (
          <tr key={row.field} data-testid="arena-field-row">
            <td className={`${CELL} font-mono`}>{row.field}</td>
            <td className={CELL}>
              <span
                className={
                  row.violations.length === 0
                    ? 'font-mono text-caption text-on-surface-variant'
                    : 'font-mono text-caption text-error'
                }
              >
                {row.valueClassification}
              </span>
            </td>
            <td className={CELL}>
              {row.violations.length === 0 ? (
                <span className="font-mono text-caption text-on-surface-variant">—</span>
              ) : (
                <span className="flex flex-col gap-xxs">
                  {row.violations.map((violation) => (
                    <span key={violation.annotation} className="font-mono text-caption">
                      <span className="rounded-sm border border-outline px-xxs py-xxs text-on-surface">
                        @{violation.annotation}
                      </span>{' '}
                      {violation.message}
                    </span>
                  ))}
                </span>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default function ArenaDemo() {
  const [payload, setPayload] = useState(DEFAULT_PAYLOAD);
  const [endpoint, setEndpoint] = useState<Endpoint>('analyst');
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const send = useCallback(
    (currentEndpoint: Endpoint, currentPayload: string) => {
      setPanel({ kind: 'loading' });
      void sendArena(config, currentEndpoint, currentPayload).then(setPanel);
    },
    [config],
  );

  useEffect(() => {
    send('analyst', DEFAULT_PAYLOAD);
  }, [send]);

  return (
    <div className="flex flex-col gap-md">
      {/* The editor: you control the bytes, including the broken ones. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>edit the submission — break it, then submit it</p>
        <div className="flex flex-wrap gap-xs" role="group" aria-label="target endpoint">
          {(['analyst', 'strict'] as Endpoint[]).map((option) => (
            <button
              key={option}
              type="button"
              data-testid={`arena-endpoint-${option}`}
              aria-pressed={option === endpoint}
              onClick={() => {
                setEndpoint(option);
                send(option, payload);
              }}
              className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
                option === endpoint
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant hover:bg-surface-tint'
              }`}
            >
              {option === 'analyst' ? 'arena (analyst)' : 'arena/strict (@Valid)'}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap gap-xs">
          {PRESETS.map((preset) => (
            <button
              key={preset.id}
              type="button"
              data-testid={`arena-preset-${preset.id}`}
              onClick={() => {
                setPayload(preset.payload);
                send(endpoint, preset.payload);
              }}
              className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant hover:bg-surface-tint"
            >
              {preset.label}
            </button>
          ))}
        </div>
        <div className="flex flex-col gap-xxs">
          <label htmlFor="arena-payload" className="font-mono text-caption text-on-surface-variant">
            ArenaSubmission JSON (sent as-is — the server judges it)
          </label>
          <textarea
            id="arena-payload"
            data-testid="arena-payload-input"
            rows={9}
            value={payload}
            onChange={(event) => setPayload(event.target.value)}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
        </div>
        <button
          type="button"
          data-testid="arena-submit"
          onClick={() => send(endpoint, payload)}
          className="self-start rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          submit payload
        </button>
      </section>

      {/* The verdict. */}
      <section className="flex flex-col gap-sm">
        {panel.kind === 'loading' && (
          <p data-testid="arena-loading" className="text-body-sm text-on-surface-variant">
            asking the arena…
          </p>
        )}
        {panel.kind === 'error' && (
          <ErrorPanel subject="the arena verdict" failure={panel.failure} />
        )}
        {panel.kind === 'ready' && (
          <>
            <p data-testid="arena-accepted" className="font-mono text-body-sm text-on-surface">
              {panel.verdict.accepted
                ? 'accepted: true — the payload passed every constraint'
                : 'accepted: false — the verdict, per field:'}
            </p>
            <VerdictTable verdict={panel.verdict} />
            {panel.verdict.envelopeComparison !== null && (
              <div data-testid="arena-envelope" className="flex flex-col gap-xxs">
                <p className={OVERLINE}>the same input via the error envelope (strict endpoint)</p>
                <pre className="overflow-x-auto rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface">
                  {JSON.stringify(panel.verdict.envelopeComparison, null, 2)}
                </pre>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
