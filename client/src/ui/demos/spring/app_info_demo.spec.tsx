import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import AppInfoDemo from './app_info_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * AppInfoDemo spec — topic 04's spring:app-info widget over the shared
 * guarded pipeline: the live introspection report renders (facts, context
 * counts, package layout), refresh re-fetches through the same route, and
 * the server's error envelope message surfaces on failure.
 */

const APP_INFO_PATH = '/api/demo/spring/app-info';

const payload = (uptimeSeconds: number) => ({
  applicationName: 'springboot-developers-guide',
  activeProfiles: ['dev'],
  build: { artifact: 'server', version: '0.0.1-SNAPSHOT', builtAt: '2026-10-06T09:00:00Z' },
  javaVersion: '25',
  javaVendor: 'Eclipse Adoptium',
  startedAt: '2026-10-06T09:00:01Z',
  uptimeSeconds,
  embeddedServer: 'tomcat',
  serverPort: 8080,
  actuatorEndpoints: ['health', 'info'],
  beanDefinitionCount: 187,
  configurationClasses: 41,
  guideBeans: 7,
  packages: [
    { name: '(root)', beans: 1 },
    { name: 'config', beans: 3 },
    { name: 'presentation', beans: 2 },
  ],
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('AppInfoDemo', () => {
  it('renders the live introspection report', async () => {
    stubApiFetch([{ url: APP_INFO_PATH, body: payload(90) }]);

    render(<AppInfoDemo />);

    await screen.findByTestId('spring-app-info-report');
    expect(screen.getByTestId('spring-app-info-name')).toHaveTextContent(
      'springboot-developers-guide',
    );
    expect(screen.getByTestId('spring-app-info-profiles')).toHaveTextContent('dev');
    expect(screen.getByTestId('spring-app-info-build')).toHaveTextContent('0.0.1-SNAPSHOT');
    expect(screen.getByTestId('spring-app-info-java')).toHaveTextContent('Eclipse Adoptium');
    expect(screen.getByTestId('spring-app-info-uptime')).toHaveTextContent('90s');
    expect(screen.getByTestId('spring-app-info-server')).toHaveTextContent('tomcat : 8080');
    expect(screen.getByTestId('spring-app-info-actuator')).toHaveTextContent('health, info');
    expect(screen.getByTestId('spring-app-info-context')).toHaveTextContent(
      '187 bean definitions',
    );
    expect(screen.getByTestId('spring-app-info-context')).toHaveTextContent(
      '41 @Configuration classes',
    );

    const packages = screen.getByTestId('spring-app-info-packages');
    expect(packages).toHaveTextContent('(root)');
    expect(packages).toHaveTextContent('config');
    expect(packages).toHaveTextContent('presentation');
  });

  it('shows the empty-profile state when no profile is active', async () => {
    stubApiFetch([{ url: APP_INFO_PATH, body: { ...payload(5), activeProfiles: [] } }]);

    render(<AppInfoDemo />);

    expect(await screen.findByTestId('spring-app-info-profiles')).toHaveTextContent(
      '(none — defaults only)',
    );
  });

  it('refresh re-fetches and the uptime ticks — live state, not a cache', async () => {
    stubApiFetch([{ url: APP_INFO_PATH, body: payload(90) }]);
    render(<AppInfoDemo />);
    expect(await screen.findByTestId('spring-app-info-uptime')).toHaveTextContent('90s');

    // Replace the stub with a later snapshot, then press refresh.
    stubApiFetch([{ url: APP_INFO_PATH, body: payload(240) }]);
    fireEvent.click(screen.getByTestId('spring-app-info-refresh'));

    await waitFor(() =>
      expect(screen.getByTestId('spring-app-info-uptime')).toHaveTextContent('240s'),
    );
  });

  it('shows the error envelope message when the endpoint fails', async () => {
    stubApiFetch([
      {
        url: APP_INFO_PATH,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-9' },
        },
      },
    ]);

    render(<AppInfoDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the app-info report');
    expect(alert).toHaveTextContent('boom');
    expect(alert).toHaveTextContent('internal_error · status 500 · request req-9');
    expect(screen.queryByTestId('spring-app-info-report')).not.toBeInTheDocument();
  });
});
