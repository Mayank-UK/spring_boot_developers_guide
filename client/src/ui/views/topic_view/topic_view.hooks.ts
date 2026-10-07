import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { Failure } from '../../../core/error-handling/failures';
import { ServerFailure } from '../../../core/error-handling/failures';
import type { TopicDetail } from '../../../domain/content/content';
import type { GetTopicDetailUseCase } from '../../../domain/content/get-topic-detail-usecase';

export type TopicViewState =
  | { state: 'loading' }
  | { state: 'error'; failure: Failure }
  | { state: 'ready'; topic: TopicDetail };

/**
 * Loads the topic named by the route's :topicId param. Re-fetches when the id
 * changes (navigating between topics reuses the same screen): the effect
 * resets to loading and a `cancelled` flag drops the stale response, so a slow
 * first fetch can never overwrite the second topic's screen. A missing param
 * degrades to a friendly error instead of a request with "undefined" in the
 * URL. Expected failures arrive as typed Failures inside Results — the hook
 * never throws. The use case is injected as a default parameter
 * (react-guide `usePosts` style) so specs can substitute fakes.
 */
export function useTopicDetail(
  topicId: string | undefined,
  getTopicDetailUseCase: GetTopicDetailUseCase = container.getTopicDetailUseCase,
): TopicViewState {
  const [view, setView] = useState<TopicViewState>({ state: 'loading' });

  useEffect(() => {
    if (topicId === undefined) {
      setView({
        state: 'error',
        failure: new ServerFailure('No topic id in this URL.', {
          code: 'not_found',
          status: 404,
        }),
      });
      return;
    }

    let cancelled = false;
    setView({ state: 'loading' });

    void getTopicDetailUseCase.execute(topicId).then((result) => {
      if (cancelled) return;
      if (result.ok) {
        setView({ state: 'ready', topic: result.value });
      } else {
        setView({ state: 'error', failure: result.failure });
      }
    });

    return () => {
      cancelled = true;
    };
  }, [topicId, getTopicDetailUseCase]);

  return view;
}
