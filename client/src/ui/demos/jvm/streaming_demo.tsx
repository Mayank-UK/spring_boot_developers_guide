import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `jvm:streaming` — topic 02's live answer to "streams,
 * readers, and the split-character bug":
 * - GET /api/demo/jvm/streaming?chunks=N&charset=utf-8|utf-16 — one payload
 *   consumed four ways (raw InputStream, NIO channel, Reader, naive
 *   per-chunk decode), with read boundaries forced inside multi-byte '→'
 *   characters. The Reader comes out clean; the naive decode returns
 *   replacement characters (U+FFFD), counted.
 *
 * A teaching widget: it fetches directly (no dedicated repository), but
 * through the SAME guarded pipeline every data source uses.
 */

const CHUNK_CHOICES = [16, 32, 64] as const;
type ChunkChoice = (typeof CHUNK_CHOICES)[number];
const CHARSET_CHOICES = ['utf-8', 'utf-16'] as const;
type CharsetChoice = (typeof CHARSET_CHOICES)[number];

interface StreamingReport {
  chunks: number;
  charset: string;
  bufferSize: number;
  payloadBytes: number;
  expectedCharCount: number;
  streamScan: { totalBytes: number; readCalls: number };
  readerScan: { totalChars: number; multibyteChars: number; replacementChars: number };
  naiveScan: { decodedSample: string; replacementChars: number };
  channelScan: { totalBytes: number; readCalls: number };
  readerClean: boolean;
  naiveCorrupted: boolean;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; report: StreamingReport }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the stable wire shapes ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number') throw new Error(`field "${key}" must be a number`);
  return value;
}

function scanFields<T extends readonly string[]>(
  body: unknown,
  keys: T,
): Record<T[number], number> {
  if (!isRecord(body)) throw new Error('scan must be an object');
  const out = {} as Record<T[number], number>;
  for (const key of keys) {
    const field = key as T[number];
    out[field] = numberField(body, key);
  }
  return out;
}

function parseStreamingReport(body: unknown): StreamingReport {
  if (!isRecord(body)) throw new Error('streaming report must be an object');
  if (!isRecord(body.naiveScan) || typeof body.naiveScan.decodedSample !== 'string') {
    throw new Error('naiveScan needs a string decodedSample');
  }
  return {
    chunks: numberField(body, 'chunks'),
    charset: typeof body.charset === 'string' ? body.charset : '',
    bufferSize: numberField(body, 'bufferSize'),
    payloadBytes: numberField(body, 'payloadBytes'),
    expectedCharCount: numberField(body, 'expectedCharCount'),
    streamScan: scanFields(body.streamScan, ['totalBytes', 'readCalls'] as const),
    readerScan: scanFields(body.readerScan, ['totalChars', 'multibyteChars', 'replacementChars'] as const),
    naiveScan: {
      decodedSample: body.naiveScan.decodedSample,
      replacementChars: numberField(body.naiveScan, 'replacementChars'),
    },
    channelScan: scanFields(body.channelScan, ['totalBytes', 'readCalls'] as const),
    readerClean: body.readerClean === true,
    naiveCorrupted: body.naiveCorrupted === true,
  };
}

/* ---- small shared presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function Verdict({ report }: { report: StreamingReport }) {
  return (
    <p data-testid="jvm-streaming-verdict" className="text-body-sm text-on-surface">
      {report.readerClean && report.naiveCorrupted ? (
        <>
          The Reader decoded all {report.chunks} chunks with{' '}
          <span className="font-mono">0</span> replacement characters; the naive per-chunk decode
          returned <span className="font-mono text-error">{report.naiveScan.replacementChars}</span>.
          Decode once, on a Reader, with a named charset.
        </>
      ) : (
        'Unexpected: the naive decode was not corrupted or the Reader was not clean — inspect the raw numbers.'
      )}
    </p>
  );
}

function ReportTable({ report }: { report: StreamingReport }) {
  return (
    <div data-testid="jvm-streaming-report" className="flex flex-col gap-sm">
      <p className="font-mono text-caption text-on-surface-variant">
        payload: {report.payloadBytes} bytes · {report.expectedCharCount} expected chars · buffer{' '}
        {report.bufferSize} B, chosen to split '→' mid-character
      </p>
      <table className="w-full border-collapse text-body-sm">
        <thead>
          <tr className="text-left text-on-surface-variant">
            <th className="border-b border-outline px-xs py-xs font-semibold">consumer</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">result</th>
          </tr>
        </thead>
        <tbody className="text-on-surface">
          <tr>
            <td className={`${CELL} font-mono`}>InputStream (bytes)</td>
            <td className={CELL}>
              {report.streamScan.totalBytes} bytes in {report.streamScan.readCalls} reads — exact,
              no decoding
            </td>
          </tr>
          <tr>
            <td className={`${CELL} font-mono`}>channel (ByteBuffer)</td>
            <td className={CELL}>
              {report.channelScan.totalBytes} bytes in {report.channelScan.readCalls} reads — same
              bytes, NIO surface
            </td>
          </tr>
          <tr>
            <td className={`${CELL} font-mono`}>Reader (decode across)</td>
            <td className={CELL}>
              {report.readerScan.totalChars} chars, {report.readerScan.multibyteChars} × '→',{' '}
              <span data-testid="jvm-streaming-reader-replacements" className="font-mono">
                {report.readerScan.replacementChars}
              </span>{' '}
              replacements
            </td>
          </tr>
          <tr>
            <td className={`${CELL} font-mono`}>naive per-chunk String</td>
            <td className={CELL}>
              <span data-testid="jvm-streaming-naive-sample" className="font-mono text-error">
                {report.naiveScan.decodedSample}
              </span>{' '}
              — {report.naiveScan.replacementChars} replacement chars
            </td>
          </tr>
        </tbody>
      </table>
      <Verdict report={report} />
    </div>
  );
}

export default function StreamingDemo() {
  const [chunks, setChunks] = useState<ChunkChoice>(16);
  const [charset, setCharset] = useState<CharsetChoice>('utf-8');
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const load = useCallback(
    (chunkCount: ChunkChoice, charsetChoice: CharsetChoice) => {
      setState({ kind: 'loading' });
      const outcome = safeRepositoryCall(async () => {
        const { body } = await guardedGetJson(
          config,
          `/demo/jvm/streaming?chunks=${String(chunkCount)}&charset=${charsetChoice}`,
        );
        return parseDto(body, 200, parseStreamingReport);
      });
      void outcome.then((result) => {
        if (result.ok) setState({ kind: 'ready', report: result.value });
        else setState({ kind: 'error', failure: result.failure });
      });
    },
    [config],
  );

  useEffect(() => {
    load(16, 'utf-8');
  }, [load]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>one payload, four consumers — decode in one place</p>

        <div className="flex flex-wrap items-center gap-xs" role="group" aria-label="payload shape">
          {CHUNK_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              data-testid={`jvm-streaming-chunks-${choice}`}
              aria-pressed={choice === chunks}
              onClick={() => {
                setChunks(choice);
                load(choice, charset);
              }}
              className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
                choice === chunks
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant hover:bg-surface-tint'
              }`}
            >
              {choice} chunks
            </button>
          ))}
          {CHARSET_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              data-testid={`jvm-streaming-charset-${choice}`}
              aria-pressed={choice === charset}
              onClick={() => {
                setCharset(choice);
                load(chunks, choice);
              }}
              className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
                choice === charset
                  ? 'border-secondary bg-secondary/15 text-on-surface'
                  : 'border-outline text-on-surface-variant hover:bg-surface-tint'
              }`}
            >
              {choice}
            </button>
          ))}
        </div>

        {state.kind === 'loading' && (
          <p data-testid="jvm-streaming-loading" className="text-body-sm text-on-surface-variant">
            generating the payload…
          </p>
        )}
        {state.kind === 'error' && <ErrorPanel subject="the streaming report" failure={state.failure} />}
        {state.kind === 'ready' && <ReportTable report={state.report} />}
      </section>
    </div>
  );
}
