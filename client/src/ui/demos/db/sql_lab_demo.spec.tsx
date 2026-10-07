import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import SqlLabDemo from './sql_lab_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * SqlLabDemo spec — the named-query lab against stubbed endpoints: the
 * catalog loads first (four whitelist entries); running a query renders its
 * SQL beside its rows with the row count; a failing query renders the error
 * panel. No raw-SQL input exists anywhere in the component — by design.
 */

interface Captured {
  path: string;
}

const catalog = {
  available: [
    { name: 'cte', title: 'Recursive CTE: the org chart under a manager', description: 'walks manager_id' },
    { name: 'window', title: 'Window functions: rank beside running totals', description: 'RANK, ROW_NUMBER, SUM OVER' },
    { name: 'lag', title: 'LAG: days between consecutive hires', description: 'lag over a named window' },
    { name: 'aggregate', title: 'Join + aggregate: one rollup row per department', description: 'aggregate first, join after' },
  ],
};

const labResult = {
  name: 'cte',
  title: 'Recursive CTE: the org chart under a manager',
  description: 'WITH RECURSIVE walks the self-referencing manager_id edge down the tree.',
  sql: 'WITH RECURSIVE org_chart (depth, id, name, manager_id, path) AS (…)',
  rows: [
    { depth: 0, id: 'staff-002', name: 'Grace Hopper', path: 'Grace Hopper' },
    { depth: 1, id: 'staff-006', name: 'Barbara Liskov', path: 'Grace Hopper > Barbara Liskov' },
  ],
};

function stubLab(captured: Captured[], failQueries = false) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = typeof input === 'string' ? input : input.toString();
      captured.push({ path });
      const isCatalogCall = !path.includes('query=');
      const ok = !failQueries || isCatalogCall;
      return {
        ok,
        status: ok ? 200 : 500,
        json: async () => {
          if (!ok) {
            return { error: { code: 'validation_failed', message: 'Unknown lab query "bogus".', requestId: 'x' } };
          }
          return isCatalogCall ? catalog : labResult;
        },
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

describe('SqlLabDemo', () => {
  it('loads the four-entry whitelist catalog first', async () => {
    const captured: Captured[] = [];
    stubLab(captured);
    render(<SqlLabDemo />);

    const catalogList = await screen.findByTestId('lab-catalog');
    expect(catalogList).toHaveTextContent('window');
    expect(catalogList).toHaveTextContent('lag');
    expect(captured[0]?.path).toBe('/api/demo/db/sql/lab');
  });

  it('runs a named query and renders SQL beside rows', async () => {
    const captured: Captured[] = [];
    stubLab(captured);
    render(<SqlLabDemo />);

    await screen.findByTestId('lab-catalog');
    fireEvent.click(screen.getByTestId('lab-run-cte'));
    expect(await screen.findByTestId('lab-sql')).toHaveTextContent('WITH RECURSIVE');
    expect(screen.getAllByTestId('lab-row')).toHaveLength(2);
    expect(screen.getAllByTestId('lab-row')[0]).toHaveTextContent('Grace Hopper');
    expect(screen.getByTestId('lab-rowcount')).toHaveTextContent('2 rows');
    expect(captured.some((call) => call.path.includes('query=cte'))).toBe(true);
  });

  it('exposes no raw-SQL input at all', async () => {
    stubLab([]);
    render(<SqlLabDemo />);
    await screen.findByTestId('lab-catalog');
    expect(document.querySelector('textarea')).toBeNull();
    expect(screen.queryByTestId('lab-sql-input')).not.toBeInTheDocument();
  });

  it('renders the error panel when the query fails', async () => {
    stubLab([], true);
    render(<SqlLabDemo />);

    await screen.findByTestId('lab-catalog');
    fireEvent.click(screen.getByTestId('lab-run-window'));
    await waitFor(() => {
      expect(document.body.textContent).toContain('Unknown lab query');
    });
  });
});
