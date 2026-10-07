import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ContainerDemo from './container_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ContainerDemo spec — topic 05's core:container widget over the shared
 * guarded pipeline: the overview renders (totals, package layout, convention
 * claims, sample bean), a lookup fetches the bean detail, an unknown name
 * surfaces the server's typed 404 envelope message, and a broken overview
 * shows the full error panel.
 */

const OVERVIEW_PATH = '/api/demo/core/beans';

const bean = (name: string, dependsOn: string[] = []) => ({
  name,
  className: `guide.domain.spring.${name}`,
  scope: 'singleton',
  factoryMethod: name,
  dependsOn,
  proxied: false,
});

const overview = {
  totalBeans: 214,
  byPackage: [
    { name: 'config', beans: 5 },
    { name: 'domain', beans: 9 },
  ],
  useCaseBeans: ['showAppInfo', 'showContainer'],
  useCaseConventions: ['domain-layer beans (guide.domain.*)', 'UseCase-suffixed classes (*UseCase)'],
  sampleBean: bean('appInfoSource', ['environment', 'context']),
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ContainerDemo', () => {
  it('renders the container overview', async () => {
    stubApiFetch([{ url: OVERVIEW_PATH, body: overview }]);

    render(<ContainerDemo />);

    await screen.findByTestId('core-container-report');
    expect(screen.getByTestId('core-container-total')).toHaveTextContent('214');
    expect(screen.getByTestId('core-container-conventions')).toHaveTextContent('domain-layer beans');
    expect(screen.getByTestId('core-container-conventions')).toHaveTextContent('UseCase-suffixed');

    const packages = screen.getByTestId('core-container-packages');
    expect(packages).toHaveTextContent('config');
    expect(packages).toHaveTextContent('domain');

    const useCases = screen.getByTestId('core-container-usecases');
    expect(useCases).toHaveTextContent('showAppInfo');
    expect(useCases).toHaveTextContent('showContainer');

    const sample = screen.getByTestId('core-container-sample');
    expect(sample).toHaveTextContent('appInfoSource');
    expect(sample).toHaveTextContent('environment');
    expect(sample).toHaveTextContent('singleton');
    expect(sample).not.toHaveTextContent('none — classpath-scanned');
  });

  it('inspects a bean by typed name through the detail endpoint', async () => {
    stubApiFetch([
      { url: OVERVIEW_PATH, body: overview },
      { url: '/api/demo/core/beans/showAppInfo', body: bean('showAppInfo', ['appInfoSource']) },
    ]);

    render(<ContainerDemo />);
    await screen.findByTestId('core-container-report');

    fireEvent.change(screen.getByTestId('core-container-lookup-input'), {
      target: { value: 'showAppInfo' },
    });
    fireEvent.click(screen.getByTestId('core-container-lookup-button'));

    const detail = await screen.findByTestId('core-container-detail');
    expect(detail).toHaveTextContent('showAppInfo');
    expect(detail).toHaveTextContent('appInfoSource');
    expect(detail).toHaveTextContent('guide.domain.spring.showAppInfo');
  });

  it('clicking a use-case chip inspects that bean', async () => {
    stubApiFetch([
      { url: OVERVIEW_PATH, body: overview },
      { url: '/api/demo/core/beans/showContainer', body: bean('showContainer') },
    ]);

    render(<ContainerDemo />);
    await screen.findByTestId('core-container-report');

    fireEvent.click(screen.getByTestId('core-container-usecase-showContainer'));

    const detail = await screen.findByTestId('core-container-detail');
    expect(detail).toHaveTextContent('showContainer');
    expect(detail).toHaveTextContent('(nothing — a leaf)');
  });

  it('shows the typed 404 envelope message for an unknown bean', async () => {
    stubApiFetch([
      { url: OVERVIEW_PATH, body: overview },
      {
        url: '/api/demo/core/beans/no-such-bean',
        status: 404,
        body: {
          error: {
            code: 'not_found',
            message: "no bean named 'no-such-bean' in this context",
            requestId: 'req-7',
            bean: 'no-such-bean',
          },
        },
      },
    ]);

    render(<ContainerDemo />);
    await screen.findByTestId('core-container-report');

    fireEvent.change(screen.getByTestId('core-container-lookup-input'), {
      target: { value: 'no-such-bean' },
    });
    fireEvent.click(screen.getByTestId('core-container-lookup-button'));

    const error = await screen.findByTestId('core-container-detail-error');
    expect(error).toHaveTextContent("no bean named 'no-such-bean' in this context");
    expect(error).toHaveTextContent('not_found · status 404');
  });

  it('shows the full error panel when the overview itself fails', async () => {
    stubApiFetch([
      {
        url: OVERVIEW_PATH,
        status: 500,
        body: { error: { code: 'internal_error', message: 'boom', requestId: 'req-9' } },
      },
    ]);

    render(<ContainerDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the container overview');
    expect(alert).toHaveTextContent('boom');
    expect(screen.queryByTestId('core-container-report')).not.toBeInTheDocument();
  });

  it('refresh re-fetches the overview', async () => {
    stubApiFetch([{ url: OVERVIEW_PATH, body: overview }]);
    render(<ContainerDemo />);
    expect(await screen.findByTestId('core-container-total')).toHaveTextContent('214');

    stubApiFetch([{ url: OVERVIEW_PATH, body: { ...overview, totalBeans: 231 } }]);
    fireEvent.click(screen.getByTestId('core-container-refresh'));

    await waitFor(() =>
      expect(screen.getByTestId('core-container-total')).toHaveTextContent('231'),
    );
  });
});
