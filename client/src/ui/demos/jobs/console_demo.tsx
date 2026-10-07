import { useEffect, useRef, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `jobs:console` — topic 29 (background jobs), the
 * retries-and-backoff section. The console over the observable queue:
 *
 * - enqueue puts work on the queue with an optional failure injection
 *   (failAttempts = succeed only after N failures), so a full retry
 *   episode — fail, backoff, requeue, succeed or dead-letter — is
 *   reproducible from the UI;
 * - the overview shows the two observability numbers (depth, oldest
 *   pending age) plus counts by state;
 * - the trace follows ONE job's lifecycle, attempt history included;
 * - the dead-letter list replays: requeue with the injection cleared
 *   (the operator's "fix") or kept (poison stays poison);
 * - heartbeat shows the @Scheduled cadence evidence (tick ring count);
 * - the async panel fires the @Async contrast specimen and shows which
 *   THREAD ran it — executor saturation (caller-runs) is visible here;
 * - emails lists what the recording sender actually sent.
 *
 * Live-ish by polling: while any work is pending (depth, a non-terminal
 * traced job, an unfinished async ticket) refresh every second — the
 * worker's own tick cadence — and stop when the queue goes idle.
 */

const JOB_TYPES = ['send-welcome-email', 'generate-report', 'cleanup-pii'] as const;
const TERMINAL_STATES: readonly string[] = ['succeeded', 'dead'];
const POLL_INTERVAL_MS = 1000;

interface Overview {
  countsByState: Record<string, number>;
  depth: number;
  oldestPendingId: string | null;
  oldestPendingAgeMs: number | null;
  deadLetters: number;
}

interface AttemptView {
  n: number;
  at: string;
  outcome: string;
  error: string | null;
}

interface JobView {
  id: string;
  type: string;
  payload: string;
  state: string;
  maxAttempts: number;
  attemptsLeft: number;
  failFirstAttempts: number;
  attempts: AttemptView[];
  lastError: string | null;
  nextAttemptAt: string | null;
  replays: number;
}

interface DeadView {
  deadLetters: JobView[];
}

interface HeartbeatView {
  ticks: string[];
  totalTicks: number;
}

interface AsyncTicket {
  id: string;
  delayMs: number;
  requestedAt: string;
  startedAt: string | null;
  threadName: string | null;
  finishedAt: string | null;
  state: string;
}

interface SentEmail {
  to: string;
  subject: string;
  body: string;
  at: string;
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded fetch pipeline (same shape as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function requestJson(
  config: AppConfig,
  path: string,
  init?: RequestInit,
): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { 'content-type': 'application/json' },
      ...init,
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
  return parsed;
}

function parseOverview(body: unknown): Overview {
  if (!isRecord(body) || !isRecord(body.countsByState) || typeof body.depth !== 'number') {
    throw new Error('overview needs countsByState and depth');
  }
  return {
    countsByState: Object.fromEntries(
      Object.entries(body.countsByState).map(([key, value]) => [key, Number(value)]),
    ),
    depth: body.depth,
    oldestPendingId:
      typeof body.oldestPendingId === 'string' ? body.oldestPendingId : null,
    oldestPendingAgeMs:
      typeof body.oldestPendingAgeMs === 'number' ? body.oldestPendingAgeMs : null,
    deadLetters: typeof body.deadLetters === 'number' ? body.deadLetters : 0,
  };
}

function parseJob(body: unknown): JobView {
  if (!isRecord(body) || typeof body.id !== 'string' || typeof body.state !== 'string') {
    throw new Error('job view needs id and state');
  }
  return {
    id: body.id,
    type: String(body.type ?? ''),
    payload: String(body.payload ?? '{}'),
    state: body.state,
    maxAttempts: typeof body.maxAttempts === 'number' ? body.maxAttempts : 3,
    attemptsLeft: typeof body.attemptsLeft === 'number' ? body.attemptsLeft : 0,
    failFirstAttempts:
      typeof body.failFirstAttempts === 'number' ? body.failFirstAttempts : 0,
    attempts: Array.isArray(body.attempts)
      ? body.attempts.map((row) => {
          if (!isRecord(row) || typeof row.n !== 'number' || typeof row.outcome !== 'string') {
            throw new Error('each attempt needs n and outcome');
          }
          return {
            n: row.n,
            at: String(row.at ?? ''),
            outcome: row.outcome,
            error: typeof row.error === 'string' ? row.error : null,
          };
        })
      : [],
    lastError: typeof body.lastError === 'string' ? body.lastError : null,
    nextAttemptAt: typeof body.nextAttemptAt === 'string' ? body.nextAttemptAt : null,
    replays: typeof body.replays === 'number' ? body.replays : 0,
  };
}

function parseTickets(body: unknown): AsyncTicket[] {
  if (!Array.isArray(body)) {
    throw new Error('async tickets answer must be a list');
  }
  return body.map((row) => {
    if (!isRecord(row) || typeof row.id !== 'string' || typeof row.state !== 'string') {
      throw new Error('each ticket needs id and state');
    }
    return {
      id: row.id,
      delayMs: typeof row.delayMs === 'number' ? row.delayMs : 0,
      requestedAt: String(row.requestedAt ?? ''),
      startedAt: typeof row.startedAt === 'string' ? row.startedAt : null,
      threadName: typeof row.threadName === 'string' ? row.threadName : null,
      finishedAt: typeof row.finishedAt === 'string' ? row.finishedAt : null,
      state: row.state,
    };
  });
}

function parseEmails(body: unknown): SentEmail[] {
  if (!Array.isArray(body)) {
    throw new Error('emails answer must be a list');
  }
  return body.map((row) => {
    if (!isRecord(row) || typeof row.to !== 'string' || typeof row.subject !== 'string') {
      throw new Error('each email needs to and subject');
    }
    return {
      to: row.to,
      subject: row.subject,
      body: String(row.body ?? ''),
      at: String(row.at ?? ''),
    };
  });
}

function oldestAgeLabel(overview: Overview | null): string {
  if (overview === null || overview.oldestPendingAgeMs === null) {
    return 'oldest — (idle)';
  }
  const seconds = Math.floor(overview.oldestPendingAgeMs / 1000);
  return `oldest ${String(seconds)}s`;
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const CHIP =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

export default function JobsConsoleDemo() {
  const config = container.appConfig;
  const [type, setType] = useState<string>('send-welcome-email');
  const [payloadJson, setPayloadJson] = useState('{"to":"reader@example.com","name":"Reader"}');
  const [failAttempts, setFailAttempts] = useState('2');
  const [delayMs, setDelayMs] = useState('1500');
  const [overview, setOverview] = useState<Overview | null>(null);
  const [lastJob, setLastJob] = useState<JobView | null>(null);
  const [dead, setDead] = useState<DeadView | null>(null);
  const [heartbeat, setHeartbeat] = useState<HeartbeatView | null>(null);
  const [tickets, setTickets] = useState<AsyncTicket[]>([]);
  const [emails, setEmails] = useState<SentEmail[]>([]);
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });
  const pollTimer = useRef<number | null>(null);

  const run = (action: () => Promise<void>) => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(action).then((result) => {
      setPanel(result.ok ? { kind: 'idle' } : { kind: 'error', failure: result.failure });
    });
  };

  const refresh = (focusJobId: string | null) => {
    run(async () => {
      const [overviewBody, deadBody, heartbeatBody, ticketsBody, emailsBody] =
        await Promise.all([
          requestJson(config, '/demo/jobs'),
          requestJson(config, '/demo/jobs/dead'),
          requestJson(config, '/demo/jobs/heartbeat'),
          requestJson(config, '/demo/jobs/async'),
          requestJson(config, '/demo/jobs/emails'),
        ]);
      const nextOverview = parseOverview(overviewBody);
      setOverview(nextOverview);
      setDead({
        deadLetters:
          isRecord(deadBody) && Array.isArray(deadBody.deadLetters)
            ? deadBody.deadLetters.map((row) => parseJob(row))
            : [],
      });
      if (isRecord(heartbeatBody) && Array.isArray(heartbeatBody.ticks)) {
        setHeartbeat({
          ticks: heartbeatBody.ticks.map((tick) => String(tick)),
          totalTicks: typeof heartbeatBody.totalTicks === 'number'
            ? heartbeatBody.totalTicks
            : 0,
        });
      }
      const nextTickets = parseTickets(ticketsBody);
      setTickets(nextTickets);
      setEmails(parseEmails(emailsBody));
      const traceId = focusJobId ?? lastJob?.id ?? null;
      let freshJob: JobView | null = lastJob;
      if (traceId !== null) {
        freshJob = parseJob(await requestJson(config, `/demo/jobs/jobs/${traceId}`));
        setLastJob(freshJob);
      }

      // the live-ish cadence: keep polling while work is pending — the
      // worker ticks every second, so we watch at the same beat
      const hasPendingWork =
        nextOverview.depth > 0 ||
        (freshJob !== null && !TERMINAL_STATES.includes(freshJob.state)) ||
        nextTickets.some((ticket) => ticket.state !== 'done');
      if (pollTimer.current !== null) {
        window.clearTimeout(pollTimer.current);
        pollTimer.current = null;
      }
      if (hasPendingWork) {
        pollTimer.current = window.setTimeout(() => refresh(focusJobId), POLL_INTERVAL_MS);
      }
    });
  };

  useEffect(() => {
    refresh(null);
    return () => {
      if (pollTimer.current !== null) {
        window.clearTimeout(pollTimer.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const doEnqueue = () =>
    run(async () => {
      const payload = payloadJson.trim() === ''
        ? {}
        : (JSON.parse(payloadJson) as Record<string, unknown>);
      const body = await requestJson(config, '/demo/jobs/enqueue', {
        method: 'POST',
        body: JSON.stringify({
          type,
          payload,
          failAttempts: Number(failAttempts),
        }),
      });
      const job = parseJob(body);
      setLastJob(job);
      refresh(job.id);
    });

  const doReplay = (jobId: string, fix: boolean) =>
    run(async () => {
      const body = await requestJson(config, `/demo/jobs/dead/${jobId}/replay`, {
        method: 'POST',
        body: JSON.stringify({ failAttempts: fix ? 0 : 9 }),
      });
      setLastJob(parseJob(body));
      refresh(null);
    });

  const doAsync = () =>
    run(async () => {
      await requestJson(config, '/demo/jobs/async', {
        method: 'POST',
        body: JSON.stringify({ delayMs: Number(delayMs) }),
      });
      refresh(null);
    });

  const stateChip = (state: string) => {
    const count = overview?.countsByState[state] ?? 0;
    return (
      <span key={state} className={CHIP}>
        {state}: {String(count)}
      </span>
    );
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          type
          <select
            data-testid="jobs-type"
            value={type}
            onChange={(event) => setType(event.target.value)}
            className={INPUT}
          >
            {JOB_TYPES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          payload (json)
          <input
            data-testid="jobs-payload"
            value={payloadJson}
            onChange={(event) => setPayloadJson(event.target.value)}
            className={`${INPUT} w-64`}
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          failAttempts
          <input
            data-testid="jobs-failattempts"
            type="number"
            min={0}
            max={50}
            value={failAttempts}
            onChange={(event) => setFailAttempts(event.target.value)}
            className={`${INPUT} w-20`}
          />
        </label>
        <button
          type="button"
          data-testid="jobs-enqueue"
          onClick={doEnqueue}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          enqueue
        </button>
      </section>

      <section className="flex flex-wrap items-center gap-sm">
        <p className={OVERLINE}>the two numbers</p>
        <span data-testid="jobs-depth" className={CHIP}>
          depth {String(overview?.depth ?? 0)}
        </span>
        <span data-testid="jobs-oldest-age" className={CHIP}>
          {oldestAgeLabel(overview)}
        </span>
        {['queued', 'running', 'succeeded', 'failed', 'dead'].map(stateChip)}
        <span data-testid="jobs-heartbeat" className={CHIP}>
          heartbeat {String(heartbeat?.totalTicks ?? 0)} ticks
        </span>
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="jobs-loading" className="font-mono text-caption text-on-surface-variant">
          working…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the jobs console" failure={panel.failure} />}

      {lastJob !== null && (
        <section
          data-testid="jobs-trace"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span className="break-all">
            {lastJob.id} · {lastJob.type} · {lastJob.state} · attempts left{' '}
            {String(lastJob.attemptsLeft)}/{String(lastJob.maxAttempts)}
            {lastJob.replays > 0 ? ` · replays ${String(lastJob.replays)}` : ''}
          </span>
          {lastJob.attempts.map((attempt) => (
            <span key={attempt.n} className="break-all text-on-surface-variant">
              #{String(attempt.n)} {attempt.outcome} at {attempt.at}
              {attempt.error !== null ? ` — ${attempt.error}` : ''}
            </span>
          ))}
          {lastJob.nextAttemptAt !== null && (
            <span className="text-on-surface-variant">next attempt {lastJob.nextAttemptAt}</span>
          )}
        </section>
      )}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>dead letters (the confession booth)</p>
        <div
          data-testid="jobs-dead"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {(dead?.deadLetters ?? []).length === 0 && (
            <span className="text-on-surface-variant">empty — nothing exhausted its budget</span>
          )}
          {(dead?.deadLetters ?? []).map((job) => (
            <span key={job.id} className="flex flex-wrap items-center gap-xs">
              <span className="break-all">
                {job.type} · {job.id} · {String(job.attempts.length)} attempts ·{' '}
                {job.lastError ?? 'no error'}
              </span>
              <button
                type="button"
                data-testid="jobs-replay-fixed"
                onClick={() => doReplay(job.id, true)}
                className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
              >
                replay (fix)
              </button>
              <button
                type="button"
                data-testid="jobs-replay-poison"
                onClick={() => doReplay(job.id, false)}
                className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
              >
                replay (poison)
              </button>
            </span>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <div className="flex flex-wrap items-end gap-sm">
          <p className={OVERLINE}>the @Async contrast</p>
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            delayMs
            <input
              data-testid="jobs-delay"
              type="number"
              min={1}
              max={10000}
              value={delayMs}
              onChange={(event) => setDelayMs(event.target.value)}
              className={`${INPUT} w-24`}
            />
          </label>
          <button
            type="button"
            data-testid="jobs-async-fire"
            onClick={doAsync}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            fire (returns instantly)
          </button>
        </div>
        <div
          data-testid="jobs-async-tickets"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {tickets.length === 0 && (
            <span className="text-on-surface-variant">no tickets yet — fire one</span>
          )}
          {tickets.map((ticket) => (
            <span key={ticket.id} className="break-all">
              {ticket.id} · {ticket.state} ·{' '}
              {ticket.threadName ?? '(not started)'}
              {!ticket.threadName?.startsWith('jobs-demo-') && ticket.threadName !== null
                ? ' [caller-runs — saturation!]'
                : ''}
            </span>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>what actually left (the recording sender)</p>
        <div
          data-testid="jobs-emails"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {emails.length === 0 && (
            <span className="text-on-surface-variant">no mail yet — enqueue a welcome</span>
          )}
          {emails.map((email) => (
            <span key={`${email.at}-${email.to}`} className="break-all">
              to {email.to} · {email.subject}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}
