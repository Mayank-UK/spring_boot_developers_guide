import { beforeAll, describe, expect, it } from 'vitest';
import { container, ContainerTokens } from './container';
import { createAppConfig } from './config/app-config';
import { ContentRepositoryImpl } from '../data/content/content-repository-impl';
import { HealthRepositoryImpl } from '../data/health/health-repository-impl';
import { GetContentIndexUseCase } from '../domain/content/get-content-index-usecase';
import { GetTopicDetailUseCase } from '../domain/content/get-topic-detail-usecase';
import { GetHealthUseCase } from '../domain/health/get-health-usecase';

/**
 * The default config wires only fetch-based sources, so initializing the
 * container here is side-effect-free — no request leaves the machine until a
 * use case is executed against a stubbed fetch.
 */
beforeAll(() => {
  container.initialize(createAppConfig());
});

describe('container', () => {
  it('resolves the content repository through the container after initialize', () => {
    expect(container.contentRepository).toBeInstanceOf(ContentRepositoryImpl);
  });

  it('wires both pipelines end-to-end: use cases resolve with real dependencies', () => {
    expect(container.getContentIndexUseCase).toBeInstanceOf(GetContentIndexUseCase);
    expect(container.getTopicDetailUseCase).toBeInstanceOf(GetTopicDetailUseCase);
    expect(container.getHealthUseCase).toBeInstanceOf(GetHealthUseCase);
    expect(container.healthRepository).toBeInstanceOf(HealthRepositoryImpl);
    expect(container.appConfig.apiBaseUrl).toBe('/api');
  });

  it('getWiring exposes the registered singletons keyed by symbol token', () => {
    const wiring = container.getWiring();

    expect(wiring.get(ContainerTokens.ContentRepository)).toBeInstanceOf(
      ContentRepositoryImpl,
    );
    expect(wiring.get(ContainerTokens.GetContentIndexUseCase)).toBeInstanceOf(
      GetContentIndexUseCase,
    );
    expect(wiring.size).toBe(Object.keys(ContainerTokens).length);
  });

  it('resolve throws loudly for an unregistered token', () => {
    expect(() => container.resolve(Symbol('never registered'))).toThrow(
      /is not registered/,
    );
  });

  it('re-initialization is idempotent (re-registers without throwing)', () => {
    const before = container.getContentIndexUseCase;
    container.initialize(createAppConfig());
    expect(container.getContentIndexUseCase).toBeInstanceOf(GetContentIndexUseCase);
    expect(container.getContentIndexUseCase).not.toBe(before);
  });

  it('dispose clears the registry so subsequent resolve throws', () => {
    container.dispose();
    expect(() => container.contentRepository).toThrow(/is not registered/);
    // re-initialize so downstream suites / teardown stay valid
    container.initialize(createAppConfig());
  });
});
