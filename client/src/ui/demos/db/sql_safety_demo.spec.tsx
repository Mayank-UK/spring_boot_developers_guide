import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import SqlSafetyDemo from './sql_safety_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * SqlSafetyDemo spec — the injection showdown against a stubbed endpoint:
 * the payload input + presets render; running the classic attack shows the
 * two doors side by side (parameterized: zero rows with the unchanged SQL,
 * vulnerable: leaked secret rows with the payload visible inside its SQL)
 * and the verdict banner; the UNION preset surfaces the containment error;
 * a failing endpoint renders the error panel instead.
 */

const showdown = (payload: string, vulnerableRows: unknown[], containment: string | null) => ({
  payload,
  parameterized: {
    sqlShown: 'SELECT username, role, secret FROM demo_injection WHERE username = ?',
    rows: [],
    note: 'the payload traveled as a VALUE.',
    containmentError: null,
  },
  vulnerable: {
    sqlShown: `SELECT username, role, secret FROM demo_injection WHERE username = '${payload}'`,
    rows: vulnerableRows,
    note: 'the payload became SQL STRUCTURE.',
    containmentError: containment,
  },
  verdict: 'concatenation let input become SQL structure',
});

function stubShowdown(body: unknown, ok = true, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok,
      status,
      json: async () => body,
    })),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SqlSafetyDemo', () => {
  it('renders the presets, the payload input, and the run button', () => {
    stubShowdown(showdown("' OR '1'='1", [], null));
    render(<SqlSafetyDemo />);
    expect(screen.getByTestId('safety-preset-classic')).toBeInTheDocument();
    expect(screen.getByTestId('safety-preset-union')).toBeInTheDocument();
    expect(screen.getByTestId('safety-payload-input')).toHaveValue("' OR '1'='1");
    expect(screen.getByTestId('safety-run')).toBeInTheDocument();
  });

  it('shows both doors: blocked bind parameter vs leaking concatenation', async () => {
    stubShowdown(
      showdown("' OR '1'='1", [
        { username: 'ada', role: 'admin', secret: 'flag{ada-reads-everything}' },
      ], null),
    );
    render(<SqlSafetyDemo />);
    fireEvent.click(screen.getByTestId('safety-run'));
    expect(await screen.findByTestId('safety-parameterized')).toBeInTheDocument();
    expect(screen.getByTestId('safety-parameterized-empty')).toHaveTextContent('0 rows');
    expect(screen.getByTestId('safety-vulnerable-sql')).toHaveTextContent("' OR '1'='1");
    expect(screen.getByTestId('safety-vulnerable-rows')).toHaveTextContent('flag{ada-reads-everything}');
    expect(screen.getByTestId('safety-verdict')).toHaveTextContent('structure');
  });

  it('surfaces the containment error for the UNION escape preset', async () => {
    stubShowdown(showdown("' UNION SELECT id FROM posts --", [], 'permission denied for table posts'));
    render(<SqlSafetyDemo />);
    fireEvent.click(screen.getByTestId('safety-preset-union'));
    await waitFor(() => {
      expect(screen.getByTestId('safety-vulnerable-contained')).toHaveTextContent(
        'permission denied',
      );
    });
    expect(screen.queryByTestId('safety-vulnerable-rows')).not.toBeInTheDocument();
  });

  it('renders the error panel when the endpoint fails', async () => {
    stubShowdown(
      { error: { code: 'internal_error', message: 'sandbox unreachable', requestId: 'x' } },
      false,
      500,
    );
    render(<SqlSafetyDemo />);
    fireEvent.click(screen.getByTestId('safety-run'));
    await waitFor(() => {
      expect(document.body.textContent).toContain('sandbox unreachable');
    });
    expect(screen.queryByTestId('safety-verdict')).not.toBeInTheDocument();
  });
});
