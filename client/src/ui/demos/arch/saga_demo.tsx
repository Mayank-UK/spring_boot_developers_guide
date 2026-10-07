import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import type { AppConfig } from '../../../core/config/app-config';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `arch:saga` — topic 44 (advanced architecture), the
 * live half of the sagas section. One panel, one story: an ORDER steps
 * through reserve-stock → charge-payment → ship; the dial picks which step
 * fails (or none), and the report shows the exact trail — forward steps
 * with their outcomes, compensations in REVERSE completion order, the
 * shared world's final state, and the orchestrator's own reading lines.
 * History below: every saga this context has run, newest first.
 *
 * A teaching widget (like every demo): it fetches through the same guarded
 * pipeline all data sources use, no dedicated repository.
 */

interface StepEntry {
  name: string;
  outcome: string;
  detail: string;
}

interface WorldView {
  stockOnHand: number;
  balanceCents: number;
  shipments: string[];
}

interface SagaReport {
  sagaId: string;
  state: string;
  items: number;
  amountCents: number;
  failAtStep: number;
  steps: StepEntry[];
  compensations: StepEntry[];
  world: WorldView;
  reading: string[];
}

interface SagaOverview {
  sagas: SagaReport[];
  world: WorldView;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'idle' }
  | { kind: 'ready'; report: SagaReport }
  | { kind: 'error'; failure: Failure };

const ITEM_CHOICES = ['1', '2', '3', '5'] as const;
const AMOUNT_CHOICES = ['999', '1999', '5000'] as const;
const FAIL_CHOICES = ['0', '1', '2', '3'] as const;
const FAIL_LABELS: Record<(typeof FAIL_CHOICES)[number], string> = {
  '0': 'never (happy path)',
  '1': 'at step 1 (reserve)',
  '2': 'at step 2 (charge)',
  '3': 'at step 3 (ship)',
};

/* ---- runtime validation of the wire shape (nulls are dropped server-side) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`field "${key}" must be a number`);
  }
  return value;
}

function parseStep(source: unknown): StepEntry {
  if (!isRecord(source)) throw new Error('a trail entry must be an object');
  return {
    name: stringField(source, 'name'),
    outcome: stringField(source, 'outcome'),
    detail: stringField(source, 'detail'),
  };
}

function stringArray(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value)) throw new Error(`field "${key}" must be an array`);
  return value.map((entry) => String(entry));
}

function parseWorld(source: unknown): WorldView {
  if (!isRecord(source)) throw new Error('the world view must be an object');
  const shipments = source.shipments;
  if (!Array.isArray(shipments)) throw new Error('field "shipments" must be an array');
  return {
    stockOnHand: numberField(source, 'stockOnHand'),
    balanceCents: numberField(source, 'balanceCents'),
    shipments: shipments.map((entry) => String(entry)),
  };
}

function parseReport(source: unknown): SagaReport {
  if (!isRecord(source)) throw new Error('a saga report must be an object');
  const steps = source.steps;
  const compensations = source.compensations;
  if (!Array.isArray(steps) || !Array.isArray(compensations)) {
    throw new Error('the report carries steps and compensations arrays');
  }
  return {
    sagaId: stringField(source, 'sagaId'),
    state: stringField(source, 'state'),
    items: numberField(source, 'items'),
    amountCents: numberField(source, 'amountCents'),
    failAtStep: numberField(source, 'failAtStep'),
    steps: steps.map(parseStep),
    compensations: compensations.map(parseStep),
    world: parseWorld(source.world),
    reading: stringArray(source, 'reading'),
  };
}

function parseOverview(source: unknown): SagaOverview {
  if (!isRecord(source)) throw new Error('the saga overview must be an object');
  const sagas = source.sagas;
  if (!Array.isArray(sagas)) throw new Error('field "sagas" must be an array');
  return { sagas: sagas.map(parseReport), world: parseWorld(source.world) };
}

/** Fetch a path as JSON, guarding transport + envelope like every demo. */
async function requestJson(
  config: AppConfig,
  path: string,
  init?: RequestInit,
): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      ...init,
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
    const envelope = isRecord(parsed) && isRecord(parsed.error) ? parsed.error : null;
    throw new ServerException(
      envelope && typeof envelope.message === 'string'
        ? envelope.message
        : `Request failed with status ${String(response.status)}.`,
      { status: response.status },
    );
  }
  return parsed;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const PILL =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint';
const ACTIVE_PILL =
  'rounded-pill border border-outline bg-surface-tint px-sm py-xxs font-mono text-caption text-on-surface';
const CELL = 'border-b border-outline px-xs py-xs text-left';

function outcomeTone(outcome: string): string {
  if (outcome === 'completed' || outcome === 'compensated') return 'text-on-surface';
  if (outcome === 'failed') return 'text-error';
  return 'text-on-surface-variant';
}

export default function SagaDemo() {
  const config = container.appConfig;
  const [overview, setOverview] = useState<SagaOverview | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const [items, setItems] = useState<(typeof ITEM_CHOICES)[number]>('2');
  const [amountCents, setAmountCents] = useState<(typeof AMOUNT_CHOICES)[number]>('1999');
  const [failAtStep, setFailAtStep] = useState<(typeof FAIL_CHOICES)[number]>('0');

  /**
   * Load the overview (history + shared world). QUIET mode refreshes the
   * overview silently after a run — the report the run answered with stays
   * on screen; only the initial mount drives the panel state.
   */
  const loadOverview = useCallback(async (quiet = false) => {
    if (!quiet) {
      setPanel({ kind: 'loading' });
    }
    const result = await safeRepositoryCall(async () =>
      parseOverview(await requestJson(config, '/demo/arch/saga/recent')),
    );
    if (result.ok) {
      setOverview(result.value);
      if (!quiet) {
        setPanel({ kind: 'idle' });
      }
    } else if (!quiet) {
      setPanel({ kind: 'error', failure: result.failure });
    }
  }, [config]);

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);

  /** One saga: POST the parameters, show the trail it answers with. */
  const runSaga = useCallback(() => {
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () =>
      parseReport(
        await requestJson(
          config,
          `/demo/arch/saga/start?items=${items}&amountCents=${amountCents}&failAtStep=${failAtStep}`,
          { method: 'POST' },
        ),
      ),
    ).then((result) => {
      if (result.ok) {
        setPanel({ kind: 'ready', report: result.value });
        void loadOverview(true);
      } else {
        setPanel({ kind: 'error', failure: result.failure });
      }
    });
  }, [config, items, amountCents, failAtStep, loadOverview]);

  return (
    <div className="flex flex-col gap-md">
      {/* ---- the dials ---- */}
      <div className="flex flex-col gap-xs">
        <p className={OVERLINE}>an order saga: three steps, one dial, the world watching</p>
        <div className="flex flex-wrap items-center gap-xs" data-testid="saga-dials">
          <span className="text-caption text-on-surface-variant">items</span>
          {ITEM_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              onClick={() => setItems(choice)}
              className={items === choice ? ACTIVE_PILL : PILL}
            >
              {choice}
            </button>
          ))}
          <span className="text-caption text-on-surface-variant">amount</span>
          {AMOUNT_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              onClick={() => setAmountCents(choice)}
              className={amountCents === choice ? ACTIVE_PILL : PILL}
            >
              {choice}¢
            </button>
          ))}
          <span className="text-caption text-on-surface-variant">fail…</span>
          {FAIL_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              onClick={() => setFailAtStep(choice)}
              className={failAtStep === choice ? ACTIVE_PILL : PILL}
            >
              {FAIL_LABELS[choice]}
            </button>
          ))}
        </div>
        <button
          type="button"
          data-testid="saga-run"
          onClick={runSaga}
          className="w-fit rounded-pill border border-outline bg-surface-tint px-md py-xxs text-body-sm text-on-surface transition-colors hover:bg-surface-tint/70"
        >
          start saga
        </button>
      </div>

      {panel.kind === 'loading' && (
        <p data-testid="saga-loading" className="text-body-sm text-on-surface-variant">
          the orchestrator is stepping…
        </p>
      )}
      {panel.kind === 'idle' && (
        <p data-testid="saga-idle" className="text-body-sm text-on-surface-variant">
          no saga run in this view yet — dial a failure (or leave the happy path alone) and start one.
        </p>
      )}
      {panel.kind === 'error' && (
        <ErrorPanel subject="the saga engine" failure={panel.failure} />
      )}

      {panel.kind === 'ready' && (
        <div className="flex flex-col gap-xs" data-testid="saga-report">
          <p data-testid="saga-verdict" className="text-body-sm">
            <span className="font-mono text-on-surface">{panel.report.sagaId}</span> —{' '}
            <span
              className={
                panel.report.state === 'completed' ? 'text-on-surface' : 'text-error'
              }
            >
              {panel.report.state}
            </span>
            {' '}({panel.report.items} items, {panel.report.amountCents}¢, failAtStep{' '}
            {panel.report.failAtStep})
          </p>
          <table data-testid="saga-steps" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                <th className={CELL}>#</th>
                <th className={CELL}>forward step</th>
                <th className={CELL}>outcome</th>
                <th className={CELL}>detail</th>
              </tr>
            </thead>
            <tbody>
              {panel.report.steps.map((step, index) => (
                <tr key={step.name} data-testid={`saga-step-${index}`}>
                  <td className={`${CELL} font-mono`}>{index + 1}</td>
                  <td className={`${CELL} font-mono`}>{step.name}</td>
                  <td className={`${CELL} ${outcomeTone(step.outcome)}`}>{step.outcome}</td>
                  <td className={`${CELL} font-mono text-caption`}>{step.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {panel.report.compensations.length > 0 && (
            <table data-testid="saga-compensations" className="w-full border-collapse text-body-sm">
              <thead>
                <tr className="text-left text-on-surface-variant">
                  <th className={CELL}>#</th>
                  <th className={CELL}>compensation (reverse order)</th>
                  <th className={CELL}>detail</th>
                </tr>
              </thead>
              <tbody>
                {panel.report.compensations.map((entry, index) => (
                  <tr key={entry.name} data-testid={`saga-compensation-${index}`}>
                    <td className={`${CELL} font-mono`}>{index + 1}</td>
                    <td className={`${CELL} font-mono`}>{entry.name}</td>
                    <td className={`${CELL} font-mono text-caption`}>{entry.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p data-testid="saga-world" className="font-mono text-caption text-on-surface-variant">
            world now: stock {panel.report.world.stockOnHand} · balance{' '}
            {panel.report.world.balanceCents}¢ · shipments {panel.report.world.shipments.length}
          </p>
          <ul className="flex flex-col gap-xxs">
            {panel.report.reading.map((line, index) => (
              <li
                key={index}
                data-testid={`saga-reading-${index}`}
                className="text-caption text-on-surface-variant"
              >
                {line}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- the shared world + history ---- */}
      {overview && (
        <div className="flex flex-col gap-xxs">
          <p className={OVERLINE}>the shared world — the state every future saga starts from</p>
          <p data-testid="saga-world-now" className="font-mono text-caption text-on-surface">
            stock {overview.world.stockOnHand} · balance {overview.world.balanceCents}¢ · shipments{' '}
            {overview.world.shipments.length}
          </p>
          {overview.sagas.length > 0 && (
            <table data-testid="saga-history" className="w-full border-collapse text-body-sm">
              <thead>
                <tr className="text-left text-on-surface-variant">
                  <th className={CELL}>saga</th>
                  <th className={CELL}>state</th>
                  <th className={CELL}>parameters</th>
                </tr>
              </thead>
              <tbody>
                {overview.sagas.map((saga) => (
                  <tr key={saga.sagaId} data-testid="saga-history-row">
                    <td className={`${CELL} font-mono`}>{saga.sagaId}</td>
                    <td className={`${CELL} ${outcomeTone(saga.state === 'completed' ? 'completed' : 'failed')}`}>
                      {saga.state}
                    </td>
                    <td className={`${CELL} font-mono text-caption`}>
                      {saga.items} items · {saga.amountCents}¢ · failAtStep {saga.failAtStep}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
