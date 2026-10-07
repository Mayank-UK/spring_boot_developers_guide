import { useCallback, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `perf:bench` — topic 38 (performance), the compression
 * benchmark section. One GET, measured live:
 *
 * - pick a payload profile (small/medium/large — a real-shaped few-KB JSON
 *   feed) and a pass count, run the benchmark;
 * - the report answers with identity bytes vs gzip bytes at two levels
 *   (bytes before/after, ratio, median compress + round-trip micros) — the
 *   ratio is deterministic, the micros move run to run, and the panel says
 *   so instead of rounding a claim it did not time;
 * - the process's EFFECTIVE server.compression posture rides along, so the
 *   screen never claims more than the running connector would actually do.
 */

interface LevelResult {
  level: number;
  rawBytes: number;
  compressedBytes: number;
  compressedToRawRatio: number;
  medianCompressMicros: number;
  medianRoundTripMicros: number;
}

interface ServerCompressionPosture {
  enabled: boolean;
  mimeTypes: string[];
  minResponseSize: string;
}

interface BenchReport {
  payloadProfile: string;
  payloadBytes: number;
  payloadItems: number;
  levels: LevelResult[];
  serverCompression: ServerCompressionPosture;
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

const PAYLOAD_PROFILES = ['small', 'medium', 'large'] as const;
const PASS_CHOICES = ['16', '48', '200'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseLevel(source: unknown): LevelResult {
  if (!isRecord(source)) throw new Error('a level result must be an object');
  return {
    level: Number(source.level ?? 0),
    rawBytes: Number(source.rawBytes ?? 0),
    compressedBytes: Number(source.compressedBytes ?? 0),
    compressedToRawRatio: Number(source.compressedToRawRatio ?? 0),
    medianCompressMicros: Number(source.medianCompressMicros ?? 0),
    medianRoundTripMicros: Number(source.medianRoundTripMicros ?? 0),
  };
}

function parseCompression(source: unknown): ServerCompressionPosture {
  if (!isRecord(source)) throw new Error('the compression posture must be an object');
  return {
    enabled: source.enabled === true,
    mimeTypes: Array.isArray(source.mimeTypes) ? source.mimeTypes.map((m) => String(m)) : [],
    minResponseSize: String(source.minResponseSize ?? ''),
  };
}

function parseReport(source: unknown): BenchReport {
  if (!isRecord(source)) throw new Error('the benchmark report must be an object');
  const { levels, reading } = source;
  if (!Array.isArray(levels) || !Array.isArray(reading)) {
    throw new Error('the report carries levels and reading arrays');
  }
  return {
    payloadProfile: String(source.payloadProfile ?? ''),
    payloadBytes: Number(source.payloadBytes ?? 0),
    payloadItems: Number(source.payloadItems ?? 0),
    levels: levels.map(parseLevel),
    serverCompression: parseCompression(source.serverCompression),
    reading: reading.map((line) => String(line)),
  };
}

/** Fetch a path as JSON, guarding transport + envelope like every demo. */
async function requestJson(config: AppConfig, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'content-type': 'application/json' },
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
    const code = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.code ?? 'unknown_error')
      : 'unknown_error';
    const message = isRecord(parsed) && isRecord(parsed.error)
      ? String(parsed.error.message ?? `Request failed with status ${String(response.status)}.`)
      : `Request failed with status ${String(response.status)}.`;
    throw new ServerException(message, { code, status: response.status });
  }
  return parsed;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const ACTION_BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';

const ACTIVE_BUTTON =
  'rounded-pill border border-outline bg-surface-tint px-sm py-xxs font-mono text-caption text-on-surface';

function pct(ratio: number): string {
  return `${(ratio * 100).toFixed(1)}%`;
}

export default function PerfBenchDemo() {
  const config = container.appConfig;
  const [payload, setPayload] = useState<(typeof PAYLOAD_PROFILES)[number]>('medium');
  const [passes, setPasses] = useState<(typeof PASS_CHOICES)[number]>('48');
  const [report, setReport] = useState<BenchReport | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  /** Run the benchmark: two query params, one GET, the report renders itself. */
  const runBenchmark = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseReport(await requestJson(config, `/demo/perf/bench?payload=${payload}&passes=${passes}`)),
    ).then((result) => {
      if (result.ok) {
        setReport(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config, payload, passes]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>payload:</span>
        {PAYLOAD_PROFILES.map((profile) => (
          <button
            key={profile}
            type="button"
            data-testid="perf-payload"
            onClick={() => setPayload(profile)}
            className={profile === payload ? ACTIVE_BUTTON : BUTTON}
          >
            {profile}
          </button>
        ))}
        <span className={OVERLINE}>passes:</span>
        {PASS_CHOICES.map((choice) => (
          <button
            key={choice}
            type="button"
            data-testid="perf-passes"
            onClick={() => setPasses(choice)}
            className={choice === passes ? ACTIVE_BUTTON : BUTTON}
          >
            {choice}
          </button>
        ))}
        <button type="button" data-testid="perf-run" onClick={runBenchmark} className={ACTION_BUTTON}>
          run benchmark
        </button>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="perf-loading" className="font-mono text-caption text-on-surface-variant">
          timing passes…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the compression benchmark" failure={panel.failure} />}

      <section
        data-testid="perf-report"
        className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
      >
        <p className={OVERLINE}>
          identity — {report === null ? 'not run yet' : `${String(report.payloadBytes)} bytes, ${String(report.payloadItems)} items (${report.payloadProfile})`}
        </p>
        {report?.levels.map((level) => (
          <div
            key={level.level}
            data-testid="perf-level"
            className="flex flex-wrap items-baseline gap-x-xs border-t border-outline pt-xxs"
          >
            <span>gzip level {String(level.level)}</span>
            <span className="text-on-surface-variant">
              {String(level.rawBytes)} → {String(level.compressedBytes)} bytes
            </span>
            <span>{pct(level.compressedToRawRatio)} of identity</span>
            <span className="text-on-surface-variant">
              median {String(level.medianCompressMicros)}µs compress / {String(level.medianRoundTripMicros)}µs round-trip
            </span>
          </div>
        ))}
        {report !== null && (
          <span data-testid="perf-connector" className="border-t border-outline pt-xxs">
            connector compression: {report.serverCompression.enabled ? 'ON' : 'OFF'}
            {report.serverCompression.enabled
              ? ` — ${report.serverCompression.mimeTypes.join(', ')} over ${report.serverCompression.minResponseSize} ride gzip on Accept-Encoding`
              : ' — every response leaves at identity'}
          </span>
        )}
        {report?.reading.map((line, index) => (
          <span
            key={line}
            data-testid={`perf-reading-${String(index)}`}
            className="border-t border-outline pt-xxs text-on-surface-variant"
          >
            {line}
          </span>
        ))}
      </section>
    </div>
  );
}
