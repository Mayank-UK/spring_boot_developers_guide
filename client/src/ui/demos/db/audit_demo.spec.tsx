import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import AuditDemo from './audit_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * AuditDemo spec — the audited transfer against stubbed endpoints: the form
 * (post id, title, actor, the failAfter switch) renders; a committed
 * transfer shows the committed badge, both titles, and the trail-row counts;
 * flipping failAfter shows the rolled-back badge with the title UNCHANGED
 * and an empty trail; the trail list renders ledger entries oldest-first.
 */

interface CapturedCall {
  method?: string;
  path: string;
  body: string;
}

const POST_ID = '9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01';
const ORIGINAL_TITLE = 'The uniform interface is the contract that scales';

const committedProof = {
  committed: true,
  postId: POST_ID,
  titleBefore: ORIGINAL_TITLE,
  titleAfter: 'Renamed by the audit demo',
  changedBy: 'demo-operator',
  trailRowsBefore: 0,
  trailRowsAfter: 1,
  note: 'one commit, both facts: the new title and its trail row landed together or not at all',
};

const rolledBackProof = {
  committed: false,
  postId: POST_ID,
  titleBefore: ORIGINAL_TITLE,
  titleAfter: ORIGINAL_TITLE,
  changedBy: 'demo-operator',
  trailRowsBefore: 0,
  trailRowsAfter: 0,
  note: 'rolled back — the crash left NO trace: title unchanged, trail row never existed',
};

const trailWithEntry = {
  entityId: POST_ID,
  entries: [
    {
      id: 1,
      actor: 'demo-operator',
      payload: '{"action":"title-change","titleAfter":"Renamed by the audit demo","actor":"demo-operator"}',
      changedAt: '2026-10-06T12:00:00Z',
    },
  ],
};

const emptyTrail = { entityId: POST_ID, entries: [] };

function stubAudit(captured: CapturedCall[], failAfter: boolean) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = typeof input === 'string' ? input : input.toString();
      const body = typeof init?.body === 'string' ? init.body : '';
      captured.push({ method: init?.method ?? 'GET', path, body });
      if (path.endsWith('/audit/transfer')) {
        return {
          ok: true,
          status: 200,
          json: async () => (failAfter ? rolledBackProof : committedProof),
        };
      }
      return {
        ok: true,
        status: 200,
        json: async () => (failAfter ? emptyTrail : trailWithEntry),
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

describe('AuditDemo', () => {
  it('renders the transfer form with the failAfter switch off', () => {
    stubAudit([], false);
    render(<AuditDemo />);
    expect(screen.getByTestId('audit-post-id')).toHaveValue(POST_ID);
    expect(screen.getByTestId('audit-title')).toHaveValue('Renamed by the audit demo');
    expect(screen.getByTestId('audit-fail-after')).not.toBeChecked();
    expect(screen.getByTestId('audit-run')).toBeInTheDocument();
  });

  it('shows the committed proof and the trail entry', async () => {
    const captured: CapturedCall[] = [];
    stubAudit(captured, false);
    render(<AuditDemo />);

    fireEvent.click(screen.getByTestId('audit-run'));
    expect(await screen.findByTestId('audit-proof')).toBeInTheDocument();
    expect(screen.getByTestId('audit-committed')).toHaveTextContent('committed');
    expect(screen.getByTestId('audit-titles')).toHaveTextContent(ORIGINAL_TITLE);
    expect(screen.getByTestId('audit-counts')).toHaveTextContent('0 → 1');
    expect(await screen.findByTestId('audit-trail-entry')).toHaveTextContent('title-change');
    expect(captured[0]?.body).toContain(`"postId":"${POST_ID}"`);
  });

  it('shows the rolled-back proof with an empty trail when failAfter is set', async () => {
    const captured: CapturedCall[] = [];
    stubAudit(captured, true);
    render(<AuditDemo />);

    fireEvent.click(screen.getByTestId('audit-fail-after'));
    fireEvent.click(screen.getByTestId('audit-run'));
    expect(await screen.findByTestId('audit-committed')).toHaveTextContent('rolled back');
    expect(screen.getByTestId('audit-titles')).toHaveTextContent(ORIGINAL_TITLE);
    expect(screen.getByTestId('audit-counts')).toHaveTextContent('0 → 0');
    expect(await screen.findByTestId('audit-trail-empty')).toBeInTheDocument();
    expect(captured[0]?.body).toContain('"failAfter":true');
  });
});
