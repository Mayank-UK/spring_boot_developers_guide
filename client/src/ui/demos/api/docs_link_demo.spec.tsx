import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import DocsLinkDemo from './docs_link_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * DocsLinkDemo spec — the docs-link panel against the stubbed coverage
 * endpoint: the link pair renders (Swagger UI at /docs, the raw contract at
 * /v3/api-docs, resolved against the docs origin); the coverage report
 * renders the server's live counts (paths/operations/schemas + specVersion)
 * and the grouped sample paths; the request goes through the API proxy path;
 * and a failing endpoint renders the error panel instead of numbers.
 */

const COVERAGE_URL = '/api/demo/api-docs/coverage';

interface CapturedCall {
  url: string;
}

const okCoverage = {
  paths: 34,
  operations: 71,
  schemas: 12,
  samplePaths: {
    posts: ['/api/demo/api/posts', '/api/demo/api/posts/{id}'],
    content: ['/api/content', '/api/content/{topicId}'],
    health: ['/api/health'],
    scenario: ['/api/demo/scenario'],
  },
  specVersion: '3.1.0',
  swaggerUiUrl: '/docs',
};

function stubCoverage(captured: CapturedCall[], body: unknown, ok = true, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url });
      if (!ok) {
        return {
          ok: false,
          status,
          json: async () => ({ error: { code: 'internal_error', message: 'spec unreachable', requestId: 'stub' } }),
        };
      }
      return { ok: true, status, json: async () => body };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DocsLinkDemo', () => {
  it('renders the link pair pointing at the docs origin', () => {
    const captured: CapturedCall[] = [];
    stubCoverage(captured, okCoverage);

    render(<DocsLinkDemo />);

    const open = screen.getByTestId('docs-link-open') as HTMLAnchorElement;
    expect(open.href.endsWith('/docs')).toBe(true);
    expect(open.getAttribute('target')).toBe('_blank');
    const spec = screen.getByTestId('docs-link-spec') as HTMLAnchorElement;
    expect(spec.href.endsWith('/v3/api-docs')).toBe(true);
  });

  it('renders the live coverage counts and grouped sample paths', async () => {
    const captured: CapturedCall[] = [];
    stubCoverage(captured, okCoverage);

    render(<DocsLinkDemo />);

    expect(await screen.findByTestId('docs-paths-count')).toHaveTextContent('34');
    expect(screen.getByTestId('docs-operations-count')).toHaveTextContent('71');
    expect(screen.getByTestId('docs-schemas-count')).toHaveTextContent('12');
    expect(screen.getByTestId('docs-spec-version')).toHaveTextContent('3.1.0');
    const posts = await screen.findByTestId('docs-sample-group-posts');
    expect(posts).toHaveTextContent('/api/demo/api/posts/{id}');
    expect(screen.getByTestId('docs-sample-group-content')).toHaveTextContent('/api/content');
    expect(screen.getByTestId('docs-sample-group-health')).toBeInTheDocument();

    expect(captured.some((call) => call.url === COVERAGE_URL)).toBe(true);
  });

  it('renders the error panel when the coverage endpoint fails', async () => {
    const captured: CapturedCall[] = [];
    stubCoverage(captured, null, false, 500);

    render(<DocsLinkDemo />);

    // the links survive the failure — the docs exist even when the report cannot be fetched
    expect(screen.getByTestId('docs-link-open')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByTestId('docs-paths-count')).not.toBeInTheDocument();
    });
    expect(document.body.textContent).toContain('spec unreachable');
  });

  it('renders the loading state before the report arrives', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)), // never resolves
    );

    render(<DocsLinkDemo />);

    expect(screen.getByTestId('docs-coverage-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('docs-counts')).not.toBeInTheDocument();
  });
});
