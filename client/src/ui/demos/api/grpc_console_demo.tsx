import { useCallback, useEffect, useMemo, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `api:grpc-console` — topic 15 (grpc), the
 * calling-grpc-stubs-and-channels section. A console over the server's demo
 * endpoints:
 * - GET  /api/demo/grpc/service  → the generated descriptor's four rpcs and
 *   their interaction kinds (real introspection, rendered as the picker)
 * - POST /api/demo/grpc/invoke   → run one rpc of the chosen kind against the
 *   REAL in-app gRPC server, with an optional artificial delay and an
 *   optional per-call deadline. The verdict panel renders the gRPC Status
 *   code (OK / NOT_FOUND / DEADLINE_EXCEEDED) and the round trip — the
 *   deadline path proves the call gave up at the deadline, not the delay.
 *
 * The topic picker is fed by GET /api/content — the same registry the gRPC
 * service answers from, so unary and stream results are always real data.
 */

type MethodKind = 'unary' | 'server-stream' | 'client-stream' | 'bidi';

const METHOD_KINDS: ReadonlyArray<{ id: MethodKind; label: string }> = [
  { id: 'unary', label: 'unary' },
  { id: 'server-stream', label: 'server-stream' },
  { id: 'client-stream', label: 'client-stream' },
  { id: 'bidi', label: 'bidi' },
];

interface RpcView {
  name: string;
  fullMethodName: string;
  kind: string;
}

interface ServiceView {
  serviceName: string;
  protoPackage: string;
  methods: RpcView[];
}

interface TopicSummaryDto {
  id: string;
  title: string;
}

interface GrpcCallVerdict {
  methodKind: string;
  roundTripMs: number;
  results: Array<Record<string, unknown>>;
  statusCode: string;
  statusMessage: string | null;
}

type VerdictPanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; verdict: GrpcCallVerdict }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded calls (same pipeline as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchJson(config: AppConfig, path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, init);
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
  return parsed;
}

function parseServiceView(body: unknown): ServiceView {
  if (!isRecord(body) || typeof body.serviceName !== 'string') {
    throw new Error('service view needs serviceName');
  }
  if (!Array.isArray(body.methods)) throw new Error('service view needs methods');
  for (const method of body.methods) {
    if (
      !isRecord(method) ||
      typeof method.name !== 'string' ||
      typeof method.fullMethodName !== 'string' ||
      typeof method.kind !== 'string'
    ) {
      throw new Error('each rpc needs name, fullMethodName, kind');
    }
  }
  return body as unknown as ServiceView;
}

function parseTopics(body: unknown): TopicSummaryDto[] {
  if (!isRecord(body) || !Array.isArray(body.topics)) throw new Error('content needs topics');
  const topics: TopicSummaryDto[] = [];
  for (const topic of body.topics) {
    if (isRecord(topic) && typeof topic.id === 'string' && typeof topic.title === 'string') {
      topics.push({ id: topic.id, title: topic.title });
    }
  }
  if (topics.length === 0) throw new Error('content topics came back empty');
  return topics;
}

function parseVerdict(body: unknown): GrpcCallVerdict {
  if (
    !isRecord(body) ||
    typeof body.methodKind !== 'string' ||
    typeof body.roundTripMs !== 'number' ||
    typeof body.statusCode !== 'string' ||
    !Array.isArray(body.results)
  ) {
    throw new Error('verdict needs methodKind, roundTripMs, results, statusCode');
  }
  const results: Array<Record<string, unknown>> = [];
  for (const result of body.results) {
    if (!isRecord(result)) throw new Error('each result must be an object');
    results.push(result);
  }
  return {
    methodKind: body.methodKind,
    roundTripMs: body.roundTripMs,
    results,
    statusCode: body.statusCode,
    statusMessage:
      body.statusMessage === null || body.statusMessage === undefined
        ? null
        : String(body.statusMessage),
  };
}

/* ---- small shared presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function statusTone(code: string): string {
  if (code === 'OK') return 'text-on-surface';
  if (code === 'DEADLINE_EXCEEDED') return 'text-warning';
  return 'text-error';
}

function ResultRows({ results }: { results: Array<Record<string, unknown>> }) {
  if (results.length === 0) {
    return (
      <p data-testid="grpc-results-empty" className="font-mono text-caption text-on-surface-variant">
        no messages came back — the call failed before any result
      </p>
    );
  }
  return (
    <div data-testid="grpc-results" className="flex flex-col gap-xxs">
      {results.map((result, index) => (
        <div
          key={index}
          data-testid="grpc-result-row"
          className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
        >
          {Object.entries(result).map(([key, value]) => (
            <span key={key} className="mr-sm">
              <span className="text-on-surface-variant">{key}:</span> {String(value)}
            </span>
          ))}
        </div>
      ))}
    </div>
  );
}

export default function GrpcConsoleDemo() {
  const config = container.appConfig;
  const [service, setService] = useState<ServiceView | null>(null);
  const [topics, setTopics] = useState<TopicSummaryDto[] | null>(null);
  const [method, setMethod] = useState<MethodKind>('unary');
  const [topicId, setTopicId] = useState('grpc');
  const [delayMs, setDelayMs] = useState(0);
  const [deadlineArmed, setDeadlineArmed] = useState(false);
  const [deadlineMs, setDeadlineMs] = useState(300);
  const [panel, setPanel] = useState<VerdictPanel>({ kind: 'idle' });

  useEffect(() => {
    void safeRepositoryCall(async () => parseServiceView(await fetchJson(config, '/demo/grpc/service')))
      .then((result) => {
        if (result.ok) setService(result.value);
      });
    void safeRepositoryCall(async () => parseTopics(await fetchJson(config, '/content')))
      .then((result) => {
        if (result.ok) setTopics(result.value);
      });
  }, [config]);

  const invoke = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseVerdict(
        await fetchJson(config, '/demo/grpc/invoke', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            method,
            topicId,
            delayMs,
            deadlineMs: deadlineArmed ? deadlineMs : undefined,
          }),
        }),
      ),
    ).then((result) => {
      setPanel(result.ok
        ? { kind: 'ready', verdict: result.value }
        : { kind: 'error', failure: result.failure });
    });
  }, [config, method, topicId, delayMs, deadlineArmed, deadlineMs]);

  const selectedTopicTitle = useMemo(
    () => topics?.find((topic) => topic.id === topicId)?.title ?? topicId,
    [topics, topicId],
  );

  return (
    <div className="flex flex-col gap-md">
      {/* The contract, as the generated descriptor sees it. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>the service the codegen produced</p>
        {service === null ? (
          <p data-testid="grpc-service-loading" className="font-mono text-caption text-on-surface-variant">
            reading the descriptor…
          </p>
        ) : (
          <table data-testid="grpc-service-table" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                <th className="border-b border-outline px-xs py-xs font-semibold">rpc</th>
                <th className="border-b border-outline px-xs py-xs font-semibold">kind</th>
                <th className="border-b border-outline px-xs py-xs font-semibold">wire name</th>
              </tr>
            </thead>
            <tbody className="text-on-surface">
              {service.methods.map((rpc) => (
                <tr key={rpc.name} data-testid="grpc-service-row">
                  <td className="border-b border-outline px-xs py-xs font-mono">{rpc.name}</td>
                  <td className="border-b border-outline px-xs py-xs font-mono">{rpc.kind}</td>
                  <td className="border-b border-outline px-xs py-xs font-mono text-caption text-on-surface-variant">
                    {rpc.fullMethodName}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* The console: pick a kind, a topic, order delay, arm a deadline. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>invoke — pick an interaction kind</p>
        <div className="flex flex-wrap gap-xs" role="group" aria-label="interaction kind">
          {METHOD_KINDS.map((kind) => (
            <button
              key={kind.id}
              type="button"
              data-testid={`grpc-method-${kind.id}`}
              aria-pressed={kind.id === method}
              onClick={() => setMethod(kind.id)}
              className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
                kind.id === method
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant hover:bg-surface-tint'
              }`}
            >
              {kind.label}
            </button>
          ))}
        </div>

        <div className="flex flex-col gap-xxs">
          <label htmlFor="grpc-topic-select" className="font-mono text-caption text-on-surface-variant">
            topic ({selectedTopicTitle} — answers come from the real registry)
          </label>
          <select
            id="grpc-topic-select"
            data-testid="grpc-topic-select"
            value={topicId}
            onChange={(event) => setTopicId(event.target.value)}
            className="self-start rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            <option value="grpc">grpc</option>
            {topics
              ?.filter((topic) => topic.id !== 'grpc')
              .map((topic) => (
                <option key={topic.id} value={topic.id}>
                  {topic.id}
                </option>
              ))}
          </select>
        </div>

        <div className="flex flex-wrap items-center gap-md">
          <label htmlFor="grpc-delay" className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            <span>
              artificial server delay: <span data-testid="grpc-delay-value">{delayMs}ms</span>
            </span>
            <input
              id="grpc-delay"
              data-testid="grpc-delay"
              type="range"
              min={0}
              max={2000}
              step={100}
              value={delayMs}
              onChange={(event) => setDelayMs(Number(event.target.value))}
            />
          </label>

          <label htmlFor="grpc-deadline" className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            <span>
              <input
                type="checkbox"
                data-testid="grpc-deadline-toggle"
                checked={deadlineArmed}
                onChange={(event) => setDeadlineArmed(event.target.checked)}
                className="mr-xxs"
              />
              withDeadlineAfter:{' '}
              <span data-testid="grpc-deadline-value">{deadlineArmed ? `${deadlineMs}ms` : 'off'}</span>
            </span>
            <input
              id="grpc-deadline"
              data-testid="grpc-deadline"
              type="range"
              min={100}
              max={2000}
              step={50}
              value={deadlineMs}
              disabled={!deadlineArmed}
              onChange={(event) => setDeadlineMs(Number(event.target.value))}
              className={deadlineArmed ? '' : 'opacity-40'}
            />
          </label>
        </div>

        <button
          type="button"
          data-testid="grpc-invoke"
          onClick={invoke}
          className="self-start rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          invoke {method} on {topicId}
        </button>
      </section>

      {/* The verdict. */}
      <section className="flex flex-col gap-sm">
        {panel.kind === 'idle' && (
          <p data-testid="grpc-idle" className="font-mono text-caption text-on-surface-variant">
            no call yet — invoke one
          </p>
        )}
        {panel.kind === 'loading' && (
          <p data-testid="grpc-loading" className="font-mono text-caption text-on-surface-variant">
            rpc in flight…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the rpc verdict" failure={panel.failure} />}
        {panel.kind === 'ready' && (
          <>
            <div data-testid="grpc-verdict" className="flex flex-wrap items-baseline gap-md">
              <span className={`font-mono text-body-sm ${statusTone(panel.verdict.statusCode)}`}>
                <span className="text-on-surface-variant">status:</span>{' '}
                <span data-testid="grpc-verdict-code">{panel.verdict.statusCode}</span>
              </span>
              <span className="font-mono text-body-sm text-on-surface">
                <span className="text-on-surface-variant">round trip:</span>{' '}
                <span data-testid="grpc-verdict-roundtrip">{panel.verdict.roundTripMs}ms</span>
              </span>
              <span className="font-mono text-body-sm text-on-surface">
                <span className="text-on-surface-variant">kind:</span> {panel.verdict.methodKind}
              </span>
            </div>
            {panel.verdict.statusMessage !== null && (
              <p data-testid="grpc-verdict-message" className="font-mono text-caption text-on-surface-variant">
                {panel.verdict.statusMessage}
              </p>
            )}
            <ResultRows results={panel.verdict.results} />
          </>
        )}
      </section>
    </div>
  );
}
