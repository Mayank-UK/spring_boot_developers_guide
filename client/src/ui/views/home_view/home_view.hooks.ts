import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { Failure } from '../../../core/error-handling/failures';
import type {
  ContentIndex,
  TopicGroup,
  TopicSummary,
} from '../../../domain/content/content';
import type { GetContentIndexUseCase } from '../../../domain/content/get-content-index-usecase';
import type { GetHealthUseCase } from '../../../domain/health/get-health-usecase';

/* ---- health chip (hero) — the Phase 0 pipeline, now container-wired ---- */

export type HealthChipState =
  | { state: 'checking' }
  | { state: 'ok'; status: string }
  | { state: 'fail'; message: string };

/**
 * useHealth — probes the server liveness endpoint once per mount and narrows
 * the Result into a three-state chip model. The use case is injected as a
 * default parameter (react-guide `usePosts` style) so specs can substitute
 * fakes; production resolves it from the container, never by importing data/.
 */
export function useHealth(
  getHealthUseCase: GetHealthUseCase = container.getHealthUseCase,
): HealthChipState {
  const [health, setHealth] = useState<HealthChipState>({ state: 'checking' });

  useEffect(() => {
    let cancelled = false;

    void getHealthUseCase.execute().then((result) => {
      if (cancelled) return;
      if (result.ok) {
        setHealth({ state: 'ok', status: result.value.status });
      } else {
        setHealth({ state: 'fail', message: result.failure.message });
      }
    });

    return () => {
      cancelled = true;
    };
  }, [getHealthUseCase]);

  return health;
}

/* ---- content index (topic map) ---- */

export type TopicMapState =
  | { state: 'loading' }
  | { state: 'error'; failure: Failure }
  | { state: 'ready'; index: ContentIndex };

/**
 * useContentIndex — loads the curriculum index once per mount. Expected
 * failures arrive as typed Failures inside Results — the hook never throws.
 */
export function useContentIndex(
  getContentIndexUseCase: GetContentIndexUseCase = container.getContentIndexUseCase,
): TopicMapState {
  const [map, setMap] = useState<TopicMapState>({ state: 'loading' });

  useEffect(() => {
    let cancelled = false;

    void getContentIndexUseCase.execute().then((result) => {
      if (cancelled) return;
      if (result.ok) {
        setMap({ state: 'ready', index: result.value });
      } else {
        setMap({ state: 'error', failure: result.failure });
      }
    });

    return () => {
      cancelled = true;
    };
  }, [getContentIndexUseCase]);

  return map;
}

export interface GroupedTopics {
  group: TopicGroup;
  topics: TopicSummary[];
}

/**
 * Groups the flat topic list under its group, in display order. The server
 * already sends both ordered; sorting here keeps the client's rendering
 * contract explicit instead of trusting wire order.
 */
export function groupTopics(index: ContentIndex): GroupedTopics[] {
  const byGroup = new Map<string, TopicSummary[]>();
  for (const topic of index.topics) {
    const bucket = byGroup.get(topic.group) ?? [];
    bucket.push(topic);
    byGroup.set(topic.group, bucket);
  }

  return [...index.groups]
    .sort((a, b) => a.order - b.order)
    .map((group) => ({
      group,
      topics: (byGroup.get(group.id) ?? []).sort((a, b) => a.order - b.order),
    }));
}
