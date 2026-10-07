import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import SearchDemo from './search_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * SearchDemo spec — ranked search against a stubbed endpoint: suggestions
 * and input render; searching renders the ranked hits with ts_headline
 * excerpts marked, the ILIKE count, the three plan readouts, and the
 * comparison note; a failing search renders the error panel.
 */

const report = {
  q: 'errors',
  limit: 5,
  hits: [
    {
      id: '5b481618-f93a-4b46-572d-280949083b05',
      title: 'One error envelope beats five correct ones',
      rank: 0.607927,
      excerpt: 'ship this guide’s <b>error</b> envelope on every failure',
    },
  ],
  likeCount: 0,
  ftsPlan: ['Seq Scan on posts  (cost=0.00..2.25 rows=4 width=16)'],
  ftsPlanIndexForced: [
    'Bitmap Heap Scan on posts  (cost=8.53..12.54 rows=1 width=16)',
    '  Recheck Cond: (search_vector @@ …)',
  ],
  likePlan: ['Seq Scan on posts  (cost=0.00..2.25 rows=1 width=0)'],
  comparisonNote: 'try q=errors, where FTS matches the error-envelope post via stemming.',
};

function stubSearch(captured: Array<{ path: string }>, ok = true) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = typeof input === 'string' ? input : input.toString();
      captured.push({ path });
      return {
        ok,
        status: ok ? 200 : 500,
        json: async () =>
          ok ? report : { error: { code: 'validation_failed', message: 'q is required', requestId: 'x' } },
      };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SearchDemo', () => {
  it('renders the input, the search button, and the suggestions', () => {
    stubSearch([]);
    render(<SearchDemo />);
    expect(screen.getByTestId('search-input')).toHaveValue('errors');
    expect(screen.getByTestId('search-run')).toBeInTheDocument();
    expect(screen.getByTestId('search-suggest-pagination')).toBeInTheDocument();
    expect(screen.getByTestId('search-suggest-uniforminterface')).toBeInTheDocument();
  });

  it('renders ranked hits, the like count, the three plans, and the note', async () => {
    const captured: Array<{ path: string }> = [];
    stubSearch(captured);
    render(<SearchDemo />);

    fireEvent.click(screen.getByTestId('search-run'));
    expect(await screen.findByTestId('search-hit')).toHaveTextContent('One error envelope');
    expect(screen.getAllByTestId('search-excerpt')[0]?.textContent).toContain('error');
    expect(screen.getByTestId('search-rank')).toHaveTextContent('0.6079');
    expect(screen.getByTestId('search-like-count')).toHaveTextContent('0 row(s)');
    expect(screen.getByTestId('search-plan-fts')).toHaveTextContent('Seq Scan');
    expect(screen.getByTestId('search-plan-forced')).toHaveTextContent('Bitmap');
    expect(screen.getByTestId('search-plan-like')).toHaveTextContent('Seq Scan');
    expect(screen.getByTestId('search-note')).toHaveTextContent('stemming');
    expect(captured[0]?.path).toContain('/api/demo/db/search?q=');
  });

  it('renders the error panel when the search fails', async () => {
    stubSearch([], false);
    render(<SearchDemo />);

    fireEvent.click(screen.getByTestId('search-run'));
    await waitFor(() => {
      expect(document.body.textContent).toContain('q is required');
    });
    expect(screen.queryByTestId('search-hit')).not.toBeInTheDocument();
  });
});
