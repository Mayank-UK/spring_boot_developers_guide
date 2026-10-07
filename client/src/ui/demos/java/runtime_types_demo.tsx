import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `java:runtime-types` — two panels over two real
 * endpoints of topic 01 (java-language):
 * - GET /api/demo/java/reflection?class=result|text-block|topic — what
 *   reflection sees on one of this repo's own classes: record components,
 *   sealed permits, annotations
 * - GET /api/demo/java/erasure — erasure side by side: declared generic
 *   field types survive in the class file, while two differently-
 *   parameterized Box instances share one raw Class at runtime
 *
 * A teaching widget: it fetches directly (no dedicated repository), but
 * through the SAME guarded pipeline every data source uses — transport
 * failures and error envelopes arrive as typed Failures in Results.
 */

const CLASS_CHOICES = ['result', 'text-block', 'topic'] as const;
type ClassChoice = (typeof CLASS_CHOICES)[number];

interface ComponentView {
  name: string;
  type: string;
}

interface ClassReport {
  requestedName: string;
  className: string;
  kind: string;
  recordComponents: ComponentView[];
  sealed: boolean;
  permittedSubclasses: string[];
  annotations: string[];
}

interface FieldView {
  name: string;
  genericType: string;
  rawType: string;
}

interface BoxInstanceView {
  declaredAs: string;
  rawClass: string;
  items: string[];
}

interface ErasureReport {
  declaredFields: FieldView[];
  instances: BoxInstanceView[];
  shareOneRawClass: boolean;
}

type PanelState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; report: T }
  | { kind: 'error'; failure: Failure };

/* ---- shared guarded fetch: direct call, pipeline rules unchanged ---- */

async function fetchPanelData<T>(
  config: AppConfig,
  path: string,
  parse: (body: unknown) => T,
): Promise<PanelState<T>> {
  const result = await safeRepositoryCall(async () => {
    const { body, status } = await guardedGetJson(config, path);
    return parseDto(body, status, parse);
  });
  if (result.ok) return { kind: 'ready', report: result.value };
  return { kind: 'error', failure: result.failure };
}

/* ---- runtime validation of the stable wire shapes ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function stringListField(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new Error(`field "${key}" must be an array of strings`);
  }
  return value as string[];
}

function parseClassReport(body: unknown): ClassReport {
  if (!isRecord(body)) throw new Error('reflection report must be an object');
  const components = body.recordComponents;
  if (!Array.isArray(components)) throw new Error('recordComponents must be an array');
  for (const component of components) {
    if (!isRecord(component) || typeof component.name !== 'string' || typeof component.type !== 'string') {
      throw new Error('each record component needs string name and type');
    }
  }
  return {
    requestedName: stringField(body, 'requestedName'),
    className: stringField(body, 'className'),
    kind: stringField(body, 'kind'),
    recordComponents: components as ComponentView[],
    sealed: body.sealed === true,
    permittedSubclasses: stringListField(body, 'permittedSubclasses'),
    annotations: stringListField(body, 'annotations'),
  };
}

function parseErasureReport(body: unknown): ErasureReport {
  if (!isRecord(body)) throw new Error('erasure report must be an object');
  const fields = body.declaredFields;
  const instances = body.instances;
  if (!Array.isArray(fields) || !Array.isArray(instances)) {
    throw new Error('declaredFields and instances must be arrays');
  }
  for (const field of fields) {
    if (!isRecord(field) || typeof field.name !== 'string' || typeof field.genericType !== 'string' || typeof field.rawType !== 'string') {
      throw new Error('each declared field needs name, genericType, rawType');
    }
  }
  for (const instance of instances) {
    if (!isRecord(instance) || typeof instance.declaredAs !== 'string' || typeof instance.rawClass !== 'string' || !Array.isArray(instance.items)) {
      throw new Error('each instance needs declaredAs, rawClass, items');
    }
  }
  return {
    declaredFields: fields as FieldView[],
    instances: instances as BoxInstanceView[],
    shareOneRawClass: body.shareOneRawClass === true,
  };
}

/* ---- small shared presentation helpers ---- */

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

function ClassPicker({ active, onPick }: { active: ClassChoice; onPick: (choice: ClassChoice) => void }) {
  return (
    <div className="flex flex-wrap gap-xs" role="group" aria-label="class to inspect">
      {CLASS_CHOICES.map((choice) => (
        <button
          key={choice}
          type="button"
          data-testid={`java-class-${choice}`}
          aria-pressed={choice === active}
          onClick={() => onPick(choice)}
          className={`rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
            choice === active
              ? 'border-secondary bg-secondary/15 text-on-surface'
              : 'border-outline text-on-surface-variant hover:bg-surface-tint'
          }`}
        >
          {choice}
        </button>
      ))}
    </div>
  );
}

function ReflectionPanel({ report }: { report: ClassReport }) {
  return (
    <div data-testid="java-reflection-report" className="flex flex-col gap-sm">
      <div className="flex flex-wrap items-baseline gap-sm">
        <span className="font-mono text-body-sm text-on-surface">{report.className}</span>
        <span className="rounded-sm border border-outline px-xs py-xxs font-mono text-caption text-on-surface-variant">
          {report.kind}
        </span>
        {report.sealed && (
          <span
            data-testid="java-reflection-sealed"
            className="rounded-sm border border-secondary px-xs py-xxs font-mono text-caption text-secondary"
          >
            sealed · {report.permittedSubclasses.length} permits
          </span>
        )}
      </div>

      <table className="w-full border-collapse text-body-sm">
        <thead>
          <tr className="text-left text-on-surface-variant">
            <th className="border-b border-outline px-xs py-xs font-semibold">record component</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">declared type (kept)</th>
          </tr>
        </thead>
        <tbody className="text-on-surface">
          {report.recordComponents.length === 0 ? (
            <tr>
              <td className={CELL} colSpan={2}>
                <span data-testid="java-reflection-empty" className="text-on-surface-variant">
                  no record components — {report.requestedName} is not a record
                </span>
              </td>
            </tr>
          ) : (
            report.recordComponents.map((component) => (
              <tr key={component.name}>
                <td className={`${CELL} font-mono`}>{component.name}</td>
                <td className={`${CELL} font-mono`}>{component.type}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      {report.sealed && (
        <p className="text-body-sm text-on-surface-variant">
          permitted subclasses:{' '}
          {report.permittedSubclasses.map((name) => (
            <span key={name} className="font-mono">
              {name}{' '}
            </span>
          ))}
        </p>
      )}

      <p className="font-mono text-caption text-on-surface-variant">
        class annotations: {report.annotations.length > 0 ? report.annotations.join(', ') : 'none'}
      </p>
    </div>
  );
}

function ErasurePanel({ report }: { report: ErasureReport }) {
  return (
    <div data-testid="java-erasure-report" className="flex flex-col gap-sm">
      <table className="w-full border-collapse text-body-sm">
        <thead>
          <tr className="text-left text-on-surface-variant">
            <th className="border-b border-outline px-xs py-xs font-semibold">declared field</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">generic type (kept)</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">raw type (erased)</th>
          </tr>
        </thead>
        <tbody className="text-on-surface">
          {report.declaredFields.map((field) => (
            <tr key={field.name}>
              <td className={`${CELL} font-mono`}>{field.name}</td>
              <td className={`${CELL} font-mono`}>{field.genericType}</td>
              <td className={`${CELL} font-mono text-on-surface-variant`}>{field.rawType}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <table className="w-full border-collapse text-body-sm">
        <thead>
          <tr className="text-left text-on-surface-variant">
            <th className="border-b border-outline px-xs py-xs font-semibold">instance</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">raw class at runtime</th>
            <th className="border-b border-outline px-xs py-xs font-semibold">items</th>
          </tr>
        </thead>
        <tbody className="text-on-surface">
          {report.instances.map((instance) => (
            <tr key={instance.declaredAs}>
              <td className={`${CELL} font-mono`}>{instance.declaredAs}</td>
              <td className={`${CELL} font-mono`}>{instance.rawClass}</td>
              <td className={`${CELL} font-mono text-on-surface-variant`}>
                {instance.items.join(', ')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <p data-testid="java-erasure-verdict" className="text-body-sm">
        {report.shareOneRawClass ? (
          <>
            one raw <span className="font-mono">Class</span> for both instances — the parameterization
            is gone; only the declared fields above remember it.
          </>
        ) : (
          'instances report different classes (unexpected — the JVM does not do this).'
        )}
      </p>
    </div>
  );
}

export default function RuntimeTypesDemo() {
  const [selected, setSelected] = useState<ClassChoice>('result');
  const [reflection, setReflection] = useState<PanelState<ClassReport>>({ kind: 'loading' });
  const [erasure, setErasure] = useState<PanelState<ErasureReport>>({ kind: 'loading' });
  const config = container.appConfig;

  const loadReflection = useCallback(
    (choice: ClassChoice) => {
      setReflection({ kind: 'loading' });
      void fetchPanelData(
        config,
        `/demo/java/reflection?class=${encodeURIComponent(choice)}`,
        parseClassReport,
      ).then(setReflection);
    },
    [config],
  );

  useEffect(() => {
    loadReflection('result');
  }, [loadReflection]);

  useEffect(() => {
    void fetchPanelData(config, '/demo/java/erasure', parseErasureReport).then(setErasure);
  }, [config]);

  return (
    <div className="flex flex-col gap-md">
      {/* Panel (a): the reflection inspector — pick one of this repo's classes. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>reflection inspector — what does the JVM see?</p>
        <ClassPicker
          active={selected}
          onPick={(choice) => {
            setSelected(choice);
            loadReflection(choice);
          }}
        />

        {reflection.kind === 'loading' && (
          <p data-testid="java-reflection-loading" className="text-body-sm text-on-surface-variant">
            asking the server…
          </p>
        )}
        {reflection.kind === 'error' && (
          <ErrorPanel subject="the reflection report" failure={reflection.failure} />
        )}
        {reflection.kind === 'ready' && <ReflectionPanel report={reflection.report} />}
      </section>

      {/* Panel (b): erasure, side by side — declarations kept, instances erased. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>erasure, side by side — declarations kept, instances erased</p>

        {erasure.kind === 'loading' && (
          <p data-testid="java-erasure-loading" className="text-body-sm text-on-surface-variant">
            asking the server…
          </p>
        )}
        {erasure.kind === 'error' && <ErrorPanel subject="the erasure evidence" failure={erasure.failure} />}
        {erasure.kind === 'ready' && <ErasurePanel report={erasure.report} />}
      </section>
    </div>
  );
}
