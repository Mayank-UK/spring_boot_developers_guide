import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `best:checklist` — topic 42 (best-practices), the
 * anti-checklist section. The guide grades ITSELF, live:
 *
 * - one GET serves every practice as a row (category + id + title) with
 *   either a LIVE verdict the server self-reported from in-process state
 *   (the registered failure taxonomy, the validated content registry, the
 *   effective configuration) or a VERIFY pointer whose curl output IS the
 *   verdict;
 * - the panel renders exactly what the server graded — grouped by category,
 *   verdict badges for live rows, the curl command in monospace for verify
 *   rows. No client-side math: the server's verdicts are the lesson, and a
 *   checklist that claims a pass it cannot see is the anti-pattern the
 *   section teaches.
 */

interface CheckItem {
  category: string;
  id: string;
  title: string;
  verdict: string;
  evidence: string;
  verify: string | null;
}

interface ChecklistReport {
  items: CheckItem[];
  counts: { liveChecks: number; livePasses: number; verifyPointers: number };
  reading: string[];
}

type PanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; failure: Failure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseCheckItem(source: unknown): CheckItem {
  if (!isRecord(source)) throw new Error('a checklist item must be an object');
  return {
    category: String(source.category ?? ''),
    id: String(source.id ?? ''),
    title: String(source.title ?? ''),
    verdict: String(source.verdict ?? ''),
    evidence: String(source.evidence ?? ''),
    verify: source.verify == null ? null : String(source.verify),
  };
}

function parseChecklist(source: unknown): ChecklistReport {
  if (!isRecord(source)) throw new Error('the checklist report must be an object');
  const { items, counts, reading } = source;
  if (!Array.isArray(items) || !Array.isArray(reading) || !isRecord(counts)) {
    throw new Error('the report carries items, counts and reading');
  }
  return {
    items: items.map(parseCheckItem),
    counts: {
      liveChecks: Number(counts.liveChecks ?? 0),
      livePasses: Number(counts.livePasses ?? 0),
      verifyPointers: Number(counts.verifyPointers ?? 0),
    },
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
const ACTION_BUTTON =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';
const PASS_BADGE = 'rounded-pill border border-outline px-xxs py-xxs font-mono text-caption text-secondary';
const VERIFY_BADGE = 'rounded-pill border border-outline px-xxs py-xxs font-mono text-caption text-warning';

/** Consecutive rows sharing a category render under one category heading. */
interface CategoryGroup {
  category: string;
  items: CheckItem[];
}

function groupByCategory(items: CheckItem[]): CategoryGroup[] {
  const groups: CategoryGroup[] = [];
  for (const item of items) {
    const last = groups.at(-1);
    if (last !== undefined && last.category === item.category) {
      last.items.push(item);
    } else {
      groups.push({ category: item.category, items: [item] });
    }
  }
  return groups;
}

function verdictBadge(verdict: string): string {
  return verdict === 'pass' ? PASS_BADGE : VERIFY_BADGE;
}

export default function ChecklistDemo() {
  const config = container.appConfig;
  const [report, setReport] = useState<ChecklistReport | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'idle' });

  /** Load the checklist: one GET, the server's verdicts rendered as-is. */
  const loadChecklist = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseChecklist(await requestJson(config, '/demo/best/checklist')),
    ).then((result) => {
      if (result.ok) {
        setReport(result.value);
        setPanel({ kind: 'idle' });
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config]);

  useEffect(() => {
    loadChecklist();
  }, [loadChecklist]);

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-center gap-xs">
        <span className={OVERLINE}>the guide grades itself:</span>
        <button type="button" data-testid="check-refresh" onClick={loadChecklist} className={ACTION_BUTTON}>
          re-run checklist
        </button>
        {report !== null && (
          <span data-testid="check-counts" className="font-mono text-caption text-on-surface">
            {String(report.counts.livePasses)}/{String(report.counts.liveChecks)} live pass ·{' '}
            {String(report.counts.verifyPointers)} to verify
          </span>
        )}
      </section>

      {panel.kind === 'loading' && (
        <p data-testid="check-loading" className="font-mono text-caption text-on-surface-variant">
          grading the running app…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the best-practices checklist" failure={panel.failure} />}

      {report !== null && groupByCategory(report.items).map((group) => (
        <section
          key={group.category}
          data-testid="check-category"
          className="flex flex-col gap-xs rounded-sm border border-outline bg-surface px-sm py-xs"
        >
          <p className={OVERLINE}>{group.category}</p>
          {group.items.map((item) => (
            <div
              key={item.id}
              data-testid="check-item"
              className="flex flex-col gap-xxs border-t border-outline pt-xxs"
            >
              <div className="flex flex-wrap items-baseline gap-x-xs">
                <span data-testid="check-verdict" className={verdictBadge(item.verdict)}>
                  {item.verdict}
                </span>
                <span className="font-mono text-caption text-on-surface">{item.id}</span>
                <span className="text-caption text-on-surface">{item.title}</span>
              </div>
              <p data-testid="check-evidence" className="text-caption text-on-surface-variant">
                {item.evidence}
              </p>
              {item.verify !== null && (
                <code data-testid="check-verify" className="font-mono text-caption text-on-surface">
                  $ {item.verify}
                </code>
              )}
            </div>
          ))}
        </section>
      ))}

      {report?.reading.map((line, index) => (
        <span
          key={line}
          data-testid={`check-reading-${String(index)}`}
          className="font-mono text-caption text-on-surface-variant"
        >
          {line}
        </span>
      ))}
    </div>
  );
}
