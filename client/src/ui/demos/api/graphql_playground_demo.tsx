import { useEffect, useMemo, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `api:graphql-playground` — topic 13 (graphql), the
 * introspection-and-tooling section. A query playground over the server's
 * demo endpoints:
 * - GET  /api/demo/graphql/queries → the preset query catalog (with its
 *   teaching commentary), rendered as the picker
 * - POST /api/demo/graphql/run     → execute {query, variables} against the
 *   same engine /graphql serves; the viewer renders GraphQL's own envelope:
 *   data as pretty JSON, errors[] as rows with extensions.code +
 *   extensions.requestId — the taxonomy as data, exactly what the terminal
 *   tour curls.
 *
 * The GraphiQL link points at the dev-only IDE the server mounts when the
 * dev profile is active (404 elsewhere — the topic's test asserts it).
 */

interface PresetQuery {
  id: string;
  title: string;
  description: string;
  query: string;
  defaultVariables: string;
}

interface GraphqlErrorView {
  message: string | null;
  path: unknown;
  extensions: Record<string, unknown> | null;
}

interface GraphqlOutcome {
  data: Record<string, unknown> | null;
  errors: GraphqlErrorView[];
}

type RunPanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; outcome: GraphqlOutcome }
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

function parsePresets(body: unknown): PresetQuery[] {
  if (!Array.isArray(body) || body.length === 0) throw new Error('the preset catalog came back empty');
  const presets: PresetQuery[] = [];
  for (const preset of body) {
    if (
      !isRecord(preset) ||
      typeof preset.id !== 'string' ||
      typeof preset.title !== 'string' ||
      typeof preset.description !== 'string' ||
      typeof preset.query !== 'string' ||
      typeof preset.defaultVariables !== 'string'
    ) {
      throw new Error('each preset needs id, title, description, query, defaultVariables');
    }
    presets.push(preset as unknown as PresetQuery);
  }
  return presets;
}

function parseOutcome(body: unknown): GraphqlOutcome {
  if (!isRecord(body) || !Array.isArray(body.errors)) throw new Error('outcome needs errors[]');
  const errors: GraphqlErrorView[] = [];
  for (const error of body.errors) {
    if (!isRecord(error)) throw new Error('each error must be an object');
    errors.push({
      message: typeof error.message === 'string' ? error.message : null,
      path: error.path ?? null,
      extensions: isRecord(error.extensions) ? error.extensions : null,
    });
  }
  return { data: isRecord(body.data) ? body.data : null, errors };
}

/* ---- small presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function extensionText(error: GraphqlErrorView, key: string): string | null {
  const value = error.extensions?.[key];
  return typeof value === 'string' ? value : null;
}

function JsonBlock({ testId, value }: { testId: string; value: unknown }) {
  return (
    <pre
      data-testid={testId}
      className="max-h-80 overflow-auto rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
    >
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

export default function GraphqlPlaygroundDemo() {
  const config = container.appConfig;
  const [presets, setPresets] = useState<PresetQuery[] | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [query, setQuery] = useState('');
  const [variables, setVariables] = useState('{}');
  const [variablesFault, setVariablesFault] = useState<string | null>(null);
  const [panel, setPanel] = useState<RunPanel>({ kind: 'idle' });

  useEffect(() => {
    void safeRepositoryCall(async () => parsePresets(await fetchJson(config, '/demo/graphql/queries')))
      .then((result) => {
        if (!result.ok) return;
        setPresets(result.value);
        const first = result.value[0];
        if (first) {
          setSelectedId(first.id);
          setQuery(first.query);
          setVariables(first.defaultVariables);
        }
      });
  }, [config]);

  const selectedPreset = useMemo(
    () => presets?.find((preset) => preset.id === selectedId) ?? null,
    [presets, selectedId],
  );

  const pickPreset = (id: string) => {
    setSelectedId(id);
    const preset = presets?.find((candidate) => candidate.id === id);
    if (preset) {
      setQuery(preset.query);
      setVariables(preset.defaultVariables);
      setVariablesFault(null);
    }
  };

  const run = () => {
    let parsedVariables: Record<string, unknown> = {};
    const text = variables.trim();
    if (text.length > 0) {
      try {
        const parsed: unknown = JSON.parse(text);
        if (!isRecord(parsed)) throw new Error('not an object');
        parsedVariables = parsed;
      } catch {
        setVariablesFault('variables must be a JSON object, e.g. {"id": "…"}');
        return;
      }
    }
    setVariablesFault(null);
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseOutcome(
        await fetchJson(config, '/demo/graphql/run', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query, variables: parsedVariables }),
        }),
      ),
    ).then((result) => {
      setPanel(result.ok
        ? { kind: 'ready', outcome: result.value }
        : { kind: 'error', failure: result.failure });
    });
  };

  return (
    <div className="flex flex-col gap-md">
      {/* The catalog picker. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>preset queries — the topic, executable</p>
        {presets === null ? (
          <p data-testid="graphql-presets-loading" className="font-mono text-caption text-on-surface-variant">
            loading the catalog…
          </p>
        ) : (
          <select
            data-testid="graphql-preset-select"
            value={selectedId}
            onChange={(event) => pickPreset(event.target.value)}
            className="self-start rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            {presets.map((preset) => (
              <option key={preset.id} value={preset.id}>
                {preset.title}
              </option>
            ))}
          </select>
        )}
        {selectedPreset !== null && (
          <p data-testid="graphql-preset-description" className="max-w-prose text-caption text-on-surface-variant">
            {selectedPreset.description}
          </p>
        )}
      </section>

      {/* The editors. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>the document (edit freely)</p>
        <textarea
          data-testid="graphql-query-input"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          rows={10}
          spellCheck={false}
          className="rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        />
        <label htmlFor="graphql-variables" className="font-mono text-caption text-on-surface-variant">
          variables (JSON object)
        </label>
        <textarea
          id="graphql-variables"
          data-testid="graphql-variables-input"
          value={variables}
          onChange={(event) => setVariables(event.target.value)}
          rows={4}
          spellCheck={false}
          className="rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        />
        {variablesFault !== null && (
          <p data-testid="graphql-variables-invalid" className="font-mono text-caption text-error">
            {variablesFault}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-md">
          <button
            type="button"
            data-testid="graphql-run"
            onClick={run}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            run query
          </button>
          <a
            data-testid="graphiql-link"
            href="/graphiql"
            target="_blank"
            rel="noreferrer"
            className="font-mono text-caption text-secondary underline"
          >
            open GraphiQL (dev only)
          </a>
        </div>
      </section>

      {/* The envelope: data + errors, as the terminal sees it. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>the envelope — {'{ data, errors }'} over HTTP 200</p>
        {panel.kind === 'idle' && (
          <p data-testid="graphql-idle" className="font-mono text-caption text-on-surface-variant">
            no run yet — pick a preset or write a document, then run
          </p>
        )}
        {panel.kind === 'loading' && (
          <p data-testid="graphql-loading" className="font-mono text-caption text-on-surface-variant">
            executing against the real schema…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the run" failure={panel.failure} />}
        {panel.kind === 'ready' && (
          <>
            <p className="font-mono text-caption text-on-surface-variant">data</p>
            {panel.outcome.data === null ? (
              <p data-testid="graphql-data-null" className="font-mono text-caption text-on-surface-variant">
                data is null — every field failed
              </p>
            ) : (
              <JsonBlock testId="graphql-data" value={panel.outcome.data} />
            )}
            <p className="font-mono text-caption text-on-surface-variant">
              errors ({panel.outcome.errors.length})
            </p>
            {panel.outcome.errors.length === 0 ? (
              <p data-testid="graphql-errors-empty" className="font-mono text-caption text-on-surface-variant">
                none — a clean run
              </p>
            ) : (
              <div data-testid="graphql-errors" className="flex flex-col gap-xxs">
                {panel.outcome.errors.map((error, index) => {
                  const code = extensionText(error, 'code');
                  const requestId = extensionText(error, 'requestId');
                  return (
                    <div
                      key={index}
                      data-testid="graphql-error-row"
                      className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs"
                    >
                      <span data-testid="graphql-error-code" className={`font-mono text-caption ${code === null ? 'text-on-surface-variant' : 'text-error'}`}>
                        {code ?? 'no code in extensions'}
                        {error.path !== null && error.path !== undefined && (
                          <span className="text-on-surface-variant"> at {JSON.stringify(error.path)}</span>
                        )}
                      </span>
                      {error.message !== null && (
                        <span className="font-mono text-caption text-on-surface">{error.message}</span>
                      )}
                      <span data-testid="graphql-error-request-id" className="font-mono text-caption text-on-surface-variant">
                        requestId: {requestId ?? 'absent'}
                      </span>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
