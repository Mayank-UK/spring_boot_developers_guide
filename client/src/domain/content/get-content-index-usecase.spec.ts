import { describe, expect, it } from 'vitest';
import { failed, success, type Result } from '../../core/result';
import { NetworkFailure } from '../../core/error-handling/failures';
import type { ContentIndex } from './content';
import type { ContentRepository } from './content-repository';
import { GetContentIndexUseCase } from './get-content-index-usecase';

/** In-memory repository stand-in — the port's contract is all the use case sees. */
function fakeRepository(result: Result<ContentIndex>): ContentRepository {
  return {
    getContentIndex: () => Promise.resolve(result),
    getTopicDetail: () => {
      throw new Error('not expected in this use case');
    },
  };
}

const index: ContentIndex = {
  version: 1,
  groups: [{ id: 'fundamentals', title: 'Fundamentals', order: 1 }],
  topics: [],
};

describe('GetContentIndexUseCase', () => {
  it('passes a successful repository result through as a Success', async () => {
    const useCase = new GetContentIndexUseCase(fakeRepository(success(index)));

    await expect(useCase.execute()).resolves.toEqual(success(index));
  });

  it('passes a repository failure through as a Failed (never throws)', async () => {
    const failure = new NetworkFailure();
    const useCase = new GetContentIndexUseCase(fakeRepository(failed(failure)));

    await expect(useCase.execute()).resolves.toEqual(failed(failure));
  });
});
