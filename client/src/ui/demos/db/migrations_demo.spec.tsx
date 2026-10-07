import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import MigrationsDemo from './migrations_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * MigrationsDemo spec — the ledger against stubbed endpoints: the history
 * table renders (versions, descriptions, install times, the state sentence,
 * the programmatic-mode note); Apply posts to /migrations/apply and renders
 * the zero-scripts outcome; a failing fetch renders the error panel.
 */

interface Captured {
  method?: string;
  path: string;
}

const history = {
  applied: [
    { version: '1', description: 'posts', installedOn: '2026-10-06T10:00:00Z', success: true },
    { version: '10', description: 'inventory', installedOn: '2026-10-06T10:00:01Z', success: true },
    { version: '11', description: 'sql lab', installedOn: '2026-10-06T10:00:02Z', success: true },
    { version: '12', description: 'search', installedOn: '2026-10-06T10:00:03Z', success: true },
  ],
  summary: '4 applied, 0 pending: 12',
  pending: 0,
  mode: 'programmatic (spring.flyway.enabled=false — SchemaMigrator, not autoconfig)',
};

const application = {
  migrationsApplied: 0,
  appliedVersions: [],
  summary: '4 applied, 0 pending: 12',
  note: 'idempotent by construction: flyway_schema_history already lists every migration',
};

function stubMigrations(captured: Captured[], ok = true) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      captured.push({ method, path });
      const body = path.endsWith('/apply') ? application : history;
      return {
        ok,
        status: ok ? 200 : 500,
        json: async () =>
          ok ? body : { error: { code: 'service_unavailable', message: 'postgres down', requestId: 'x' } },
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

describe('MigrationsDemo', () => {
  it('renders the ledger, the state sentence, and the programmatic mode', async () => {
    const captured: Captured[] = [];
    stubMigrations(captured);
    render(<MigrationsDemo />);

    expect(await screen.findByTestId('migrations-row-10')).toHaveTextContent('inventory');
    expect(screen.getByTestId('migrations-row-12')).toHaveTextContent('search');
    expect(screen.getByTestId('migrations-summary')).toHaveTextContent('4 applied, 0 pending: 12');
    expect(screen.getByTestId('migrations-mode')).toHaveTextContent('programmatic');
    expect(captured.some((call) => call.path === '/api/demo/db/migrations')).toBe(true);
  });

  it('applies and reports the idempotent zero', async () => {
    const captured: Captured[] = [];
    stubMigrations(captured);
    render(<MigrationsDemo />);

    await screen.findByTestId('migrations-row-1');
    fireEvent.click(screen.getByTestId('migrations-apply'));
    expect(await screen.findByTestId('migrations-application')).toHaveTextContent(
      'migrationsApplied: 0',
    );
    const applyCall = captured.find((call) => call.method === 'POST');
    expect(applyCall?.path.endsWith('/api/demo/db/migrations/apply')).toBe(true);
  });

  it('renders the error panel when the ledger cannot be fetched', async () => {
    stubMigrations([], false);
    render(<MigrationsDemo />);

    await waitFor(() => {
      expect(document.body.textContent).toContain('postgres down');
    });
    expect(screen.queryByTestId('migrations-ledger')).not.toBeInTheDocument();
  });
});
