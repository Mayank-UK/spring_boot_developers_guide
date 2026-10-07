import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ProxyDemo from './proxy_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ProxyDemo spec — topic 06's aop:proxy widget over the shared guarded
 * pipeline: the four bean verdicts render (runtime class with its proxy
 * marker, mechanism, decision rule, interfaces), the unproxied bean shows
 * its plain-class state, refresh re-fetches, and the server's error
 * envelope message surfaces on failure.
 */

const PROXY_PATH = '/api/demo/aop/proxy';

const payload = () => [
  {
    beanName: 'tracedSampleSteps',
    declaredType: 'guide.domain.aop.TracedSampleSteps',
    runtimeClass: 'guide.domain.aop.TracedSampleSteps$$SpringCGLIB$$0',
    targetClass: 'guide.domain.aop.TracedSampleSteps',
    proxied: true,
    mechanism: 'CGLIB subclass proxy',
    decisionRule:
      "no interface on the target — the subclass proxy is the only mechanism",
    interfaces: ['org.springframework.aop.SpringProxy', 'org.springframework.aop.Advised'],
  },
  {
    beanName: 'tracedOrderWorkflow',
    declaredType: 'guide.domain.teaching.ExhibitSelfInvocation',
    runtimeClass: 'guide.domain.teaching.TracedOrderWorkflow$$SpringCGLIB$$0',
    targetClass: 'guide.domain.teaching.TracedOrderWorkflow',
    proxied: true,
    mechanism: 'CGLIB subclass proxy',
    decisionRule:
      "JDK-eligible (implements ExhibitSelfInvocation) but Boot's default spring.aop.proxy-target-class=true forces CGLIB",
    interfaces: [
      'guide.domain.teaching.ExhibitSelfInvocation',
      'org.springframework.aop.SpringProxy',
    ],
  },
  {
    beanName: 'showTraceEvidence',
    declaredType: 'guide.domain.aop.ShowTraceEvidence',
    runtimeClass: 'guide.domain.aop.ShowTraceEvidence',
    targetClass: 'guide.domain.aop.ShowTraceEvidence',
    proxied: false,
    mechanism: 'not proxied',
    decisionRule:
      "no advice's pointcut matches this bean — the container hands out the plain instance",
    interfaces: [],
  },
  {
    beanName: 'invocationTraceAspect',
    declaredType: 'guide.core.aop.InvocationTraceAspect',
    runtimeClass: 'guide.core.aop.InvocationTraceAspect',
    targetClass: 'guide.core.aop.InvocationTraceAspect',
    proxied: false,
    mechanism: 'not proxied',
    decisionRule: 'the aspect is the interceptor; Spring does not advise aspects',
    interfaces: [],
  },
];

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ProxyDemo', () => {
  it('renders the four bean verdicts with their proxy markers', async () => {
    stubApiFetch([{ url: PROXY_PATH, body: payload() }]);

    render(<ProxyDemo />);

    expect(await screen.findByTestId('aop-proxy-bean-tracedSampleSteps')).toHaveTextContent(
      'TracedSampleSteps$$',
    );
    // two beans are proxied — one badge each
    const badges = screen.getAllByTestId('aop-proxy-mechanism-proxied');
    expect(badges).toHaveLength(2);
    expect(badges[0]).toHaveTextContent('CGLIB subclass proxy');
    expect(screen.getByTestId('aop-proxy-bean-tracedOrderWorkflow')).toHaveTextContent(
      'ExhibitSelfInvocation',
    );
    expect(screen.getByTestId('aop-proxy-bean-tracedOrderWorkflow')).toHaveTextContent(
      'proxy-target-class=true forces CGLIB',
    );
    expect(screen.getByTestId('aop-proxy-bean-showTraceEvidence')).toHaveTextContent(
      'not proxied',
    );
    expect(screen.getByTestId('aop-proxy-bean-showTraceEvidence')).toHaveTextContent(
      '(none — the authored class)',
    );
    expect(screen.getByTestId('aop-proxy-bean-invocationTraceAspect')).toHaveTextContent(
      'does not advise aspects',
    );
  });

  it('refresh re-fetches through the same route', async () => {
    stubApiFetch([{ url: PROXY_PATH, body: payload() }]);
    render(<ProxyDemo />);
    expect(await screen.findByTestId('aop-proxy-bean-invocationTraceAspect')).toBeInTheDocument();

    stubApiFetch([
      {
        url: PROXY_PATH,
        body: payload().map((entry) =>
          entry.beanName === 'showTraceEvidence'
            ? { ...entry, decisionRule: 'a changed rule proves a fresh fetch' }
            : entry,
        ),
      },
    ]);
    fireEvent.click(screen.getByTestId('aop-proxy-refresh'));

    await waitFor(() =>
      expect(screen.getByTestId('aop-proxy-bean-showTraceEvidence')).toHaveTextContent(
        'a changed rule proves a fresh fetch',
      ),
    );
  });

  it('shows the error envelope message when the endpoint fails', async () => {
    stubApiFetch([
      {
        url: PROXY_PATH,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-4' },
        },
      },
    ]);

    render(<ProxyDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the proxy report');
    expect(alert).toHaveTextContent('boom');
    expect(alert).toHaveTextContent('internal_error · status 500 · request req-4');
    expect(screen.queryByTestId('aop-proxy-bean-tracedSampleSteps')).not.toBeInTheDocument();
  });
});
