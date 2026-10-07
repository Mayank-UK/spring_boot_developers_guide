import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `aop:proxy` — topic 06 (aop), the live half of the
 * proxies-jdk-vs-cglib section: GET /api/demo/aop/proxy reports, for four of
 * this app's own wired beans, the object the container ACTUALLY hands out:
 * runtime class name (with its $$ proxy markers), the interfaces the object
 * grew, a proxied-or-not verdict, and the rule that decided each one. Read
 * from the live context through Spring's AopUtils at request time — the
 * proxy story, demonstrated rather than diagrammed.
 *
 * A teaching widget (like the spring demos): it fetches directly through the
 * SAME guarded pipeline every data source uses.
 */

interface ProxyFacts {
  beanName: string;
  declaredType: string;
  runtimeClass: string;
  targetClass: string;
  proxied: boolean;
  mechanism: string;
  decisionRule: string;
  interfaces: string[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; facts: ProxyFacts[] }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the wire shape ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function booleanField(source: Record<string, unknown>, key: string): boolean {
  const value = source[key];
  if (typeof value !== 'boolean') throw new Error(`field "${key}" must be a boolean`);
  return value;
}

function stringListField(source: Record<string, unknown>, key: string): string[] {
  const value = source[key];
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new Error(`field "${key}" must be an array of strings`);
  }
  return value as string[];
}

function parseProxyFacts(body: unknown): ProxyFacts[] {
  if (!Array.isArray(body)) throw new Error('proxy report must be an array');
  return body.map((entry) => {
    if (!isRecord(entry)) throw new Error('each proxy fact must be an object');
    return {
      beanName: stringField(entry, 'beanName'),
      declaredType: stringField(entry, 'declaredType'),
      runtimeClass: stringField(entry, 'runtimeClass'),
      targetClass: stringField(entry, 'targetClass'),
      proxied: booleanField(entry, 'proxied'),
      mechanism: stringField(entry, 'mechanism'),
      decisionRule: stringField(entry, 'decisionRule'),
      interfaces: stringListField(entry, 'interfaces'),
    };
  });
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const MARKER_CLASS = 'rounded-pill px-xs py-xxs font-mono text-caption';

function shortClassName(name: string): string {
  const lastDot = name.lastIndexOf('.');
  const simple = lastDot === -1 ? name : name.slice(lastDot + 1);
  const marker = name.includes('$$') ? ' $$' : '';
  return simple + marker;
}

function MechanismBadge({ proxied, mechanism }: { proxied: boolean; mechanism: string }) {
  return proxied ? (
    <span
      data-testid="aop-proxy-mechanism-proxied"
      className={`${MARKER_CLASS} border border-outline bg-surface-tint text-on-surface`}
    >
      {mechanism}
    </span>
  ) : (
    <span
      data-testid="aop-proxy-mechanism-plain"
      className={`${MARKER_CLASS} border border-outline text-on-surface-variant`}
    >
      {mechanism}
    </span>
  );
}

function FactCard({ facts }: { facts: ProxyFacts }) {
  return (
    <div
      data-testid={`aop-proxy-bean-${facts.beanName}`}
      className="flex flex-col gap-xs rounded-md border border-outline bg-surface px-sm py-sm"
    >
      <div className="flex flex-wrap items-center justify-between gap-xs">
        <span className="font-mono text-body-sm text-on-surface">{facts.beanName}</span>
        <MechanismBadge proxied={facts.proxied} mechanism={facts.mechanism} />
      </div>
      <dl className="flex flex-col gap-xxs text-body-sm text-on-surface">
        <div className="flex flex-col">
          <dt className={OVERLINE}>runtime class (injected object)</dt>
          <dd className="break-all font-mono text-on-surface">
            {shortClassName(facts.runtimeClass)}
          </dd>
        </div>
        <div className="flex flex-col">
          <dt className={OVERLINE}>target class (underneath)</dt>
          <dd className="break-all font-mono text-on-surface">
            {shortClassName(facts.targetClass)}
          </dd>
        </div>
        <div className="flex flex-col">
          <dt className={OVERLINE}>interfaces the object grew</dt>
          <dd className="break-all font-mono text-on-surface-variant">
            {facts.interfaces.length > 0
              ? facts.interfaces.map(shortClassName).join(', ')
              : '(none — the authored class)'}
          </dd>
        </div>
        <div className="flex flex-col">
          <dt className={OVERLINE}>the rule that decided it</dt>
          <dd className="text-on-surface-variant">{facts.decisionRule}</dd>
        </div>
      </dl>
    </div>
  );
}

export default function ProxyDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const { body, status } = await guardedGetJson(config, '/demo/aop/proxy');
      return parseDto(body, status, parseProxyFacts);
    });
    setState(
      result.ok ? { kind: 'ready', facts: result.value } : { kind: 'error', failure: result.failure },
    );
  }, [config]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="flex flex-col gap-sm">
      <div className="flex flex-wrap items-center justify-between gap-xs">
        <p className={OVERLINE}>what the container really handed out</p>
        <button
          type="button"
          data-testid="aop-proxy-refresh"
          onClick={() => void load()}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant transition-colors hover:bg-surface-tint"
        >
          refresh
        </button>
      </div>

      {state.kind === 'loading' && (
        <p data-testid="aop-proxy-loading" className="text-body-sm text-on-surface-variant">
          asking the server…
        </p>
      )}
      {state.kind === 'error' && <ErrorPanel subject="the proxy report" failure={state.failure} />}
      {state.kind === 'ready' && (
        <div className="grid grid-cols-1 gap-sm lg:grid-cols-2">
          {state.facts.map((facts) => (
            <FactCard key={facts.beanName} facts={facts} />
          ))}
        </div>
      )}
    </div>
  );
}
