import { type AppConfig } from './config/app-config';
import { ContentApi } from '../data/content/content-api';
import { ContentRepositoryImpl } from '../data/content/content-repository-impl';
import { HealthApi } from '../data/health/health-api';
import { HealthRepositoryImpl } from '../data/health/health-repository-impl';
import type { ContentRepository } from '../domain/content/content-repository';
import { GetContentIndexUseCase } from '../domain/content/get-content-index-usecase';
import { GetTopicDetailUseCase } from '../domain/content/get-topic-detail-usecase';
import type { HealthRepository } from '../domain/health/health-repository';
import { GetHealthUseCase } from '../domain/health/get-health-usecase';

/**
 * Service-locator container — this client's composition root, mirroring the
 * express guide's `container.ts`. A small hand-rolled locator: `register`
 * singletons keyed by symbol, `resolve` them. It keeps wiring in one explicit,
 * debuggable place and lets views resolve use cases without prop-drilling or a
 * module-bound default per service.
 *
 * Layer rules it enforces by construction: only THIS file imports data/
 * adapters; views resolve through the container and never import data/
 * directly; data/ never imports the container.
 *
 * `initialize` is idempotent — re-running it just re-registers. Call it once
 * at startup (main.tsx) or in a test's beforeAll.
 */

/** Symbol tokens — the single registry of injectable service identities. */
export const ContainerTokens = {
  AppConfig: Symbol('AppConfig'),
  HealthApi: Symbol('HealthApi'),
  HealthRepository: Symbol('HealthRepository'),
  GetHealthUseCase: Symbol('GetHealthUseCase'),
  ContentApi: Symbol('ContentApi'),
  ContentRepository: Symbol('ContentRepository'),
  GetContentIndexUseCase: Symbol('GetContentIndexUseCase'),
  GetTopicDetailUseCase: Symbol('GetTopicDetailUseCase'),
} as const;

class Container {
  private readonly registry = new Map<symbol, unknown>();

  initialize(config: AppConfig): void {
    // Eager singletons — the full graph, config → api → repository → use case
    // for each pipeline, mirroring the server's @Configuration composition root.
    this.register(ContainerTokens.AppConfig, config);

    this.register(ContainerTokens.HealthApi, new HealthApi(config));
    this.register(
      ContainerTokens.HealthRepository,
      new HealthRepositoryImpl(this.healthApi),
    );
    this.register(
      ContainerTokens.GetHealthUseCase,
      new GetHealthUseCase(this.healthRepository),
    );

    this.register(ContainerTokens.ContentApi, new ContentApi(config));
    this.register(
      ContainerTokens.ContentRepository,
      new ContentRepositoryImpl(this.contentApi),
    );
    this.register(
      ContainerTokens.GetContentIndexUseCase,
      new GetContentIndexUseCase(this.contentRepository),
    );
    this.register(
      ContainerTokens.GetTopicDetailUseCase,
      new GetTopicDetailUseCase(this.contentRepository),
    );
  }

  // ---- typed service accessors (the only names views should use) ----

  get appConfig(): AppConfig {
    return this.resolve<AppConfig>(ContainerTokens.AppConfig);
  }
  get healthApi(): HealthApi {
    return this.resolve<HealthApi>(ContainerTokens.HealthApi);
  }
  get healthRepository(): HealthRepository {
    return this.resolve<HealthRepository>(ContainerTokens.HealthRepository);
  }
  get getHealthUseCase(): GetHealthUseCase {
    return this.resolve<GetHealthUseCase>(ContainerTokens.GetHealthUseCase);
  }
  get contentApi(): ContentApi {
    return this.resolve<ContentApi>(ContainerTokens.ContentApi);
  }
  get contentRepository(): ContentRepository {
    return this.resolve<ContentRepository>(ContainerTokens.ContentRepository);
  }
  get getContentIndexUseCase(): GetContentIndexUseCase {
    return this.resolve<GetContentIndexUseCase>(ContainerTokens.GetContentIndexUseCase);
  }
  get getTopicDetailUseCase(): GetTopicDetailUseCase {
    return this.resolve<GetTopicDetailUseCase>(ContainerTokens.GetTopicDetailUseCase);
  }

  // ---- wiring helpers ----

  /** Register (or replace) a singleton under its token. */
  register(token: symbol, instance: unknown): void {
    this.registry.set(token, instance);
  }

  /** Resolve a singleton; throws loudly when the token was never registered. */
  resolve<T>(token: symbol): T {
    const instance = this.registry.get(token);
    if (instance === undefined) {
      throw new Error(`Dependency "${String(token.description)}" is not registered`);
    }
    return instance as T;
  }

  /** Test-only: the raw symbol → instance wiring, for wiring assertions. */
  getWiring(): ReadonlyMap<symbol, unknown> {
    return this.registry;
  }

  /** Test-only: tear down registered singletons. */
  dispose(): void {
    this.registry.clear();
  }
}

export const container = new Container();
