import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import GraphqlPlaygroundDemo from './graphql_playground_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * GraphqlPlaygroundDemo spec — the playground over stubbed demo endpoints:
 * the picker loads the preset catalog (first preset seeded into the
 * editors), picking another preset swaps document AND variables, run posts
 * the edited document to the run endpoint and renders the data viewer, the
 * error outcome renders extensions.code + requestId (the taxonomy as data),
 * and a non-object variables blob is refused client-side without a run.
 */

const QUERIES_URL = '/api/demo/graphql/queries';
const RUN_URL = '/api/demo/graphql/run';

interface CapturedCall {
  url: string;
  init?: RequestInit;
}

const presets = [
  {
    id: 'page-of-posts',
    title: 'A page of posts',
    description: 'The shape YOU choose.',
    query: 'query FirstPage { posts(page: 0, size: 3) { items { id title } } }',
    defaultVariables: '{}',
  },
  {
    id: 'error-envelope',
    title: 'The error envelope',
    description: 'The not-found error, as data.',
    query: 'query NoSuchPost { post(id: "does-not-exist") { id } }',
    defaultVariables: '{}',
  },
];

const okOutcome = {
  data: { posts: { items: [{ id: 'p1', title: 'The uniform interface is the contract that scales' }] } },
  errors: [],
};

const errorOutcome = {
  data: null,
  errors: [
    {
      message: 'no post with id "does-not-exist"',
      path: ['post'],
      extensions: { code: 'not_found', requestId: 'req-123', postId: 'does-not-exist' },
    },
  ],
};

function stubDemo(captured: CapturedCall[], runBody: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url, init });
      let body: unknown;
      if (url === QUERIES_URL) {
        body = presets;
      } else if (url === RUN_URL) {
        body = runBody;
      } else {
        return {
          ok: false,
          status: 404,
          json: async () => ({ error: { code: 'not_found', message: `no stub for ${url}`, requestId: 'stub' } }),
        };
      }
      return { ok: true, status: 200, json: async () => body };
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GraphqlPlaygroundDemo', () => {
  it('loads the catalog, seeds the editors, and renders the preset commentary', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okOutcome);

    render(<GraphqlPlaygroundDemo />);

    const select = await screen.findByTestId('graphql-preset-select') as HTMLSelectElement;
    expect(select.value).toBe('page-of-posts');
    const queryEditor = screen.getByTestId('graphql-query-input') as HTMLTextAreaElement;
    expect(queryEditor.value).toContain('query FirstPage');
    expect(screen.getByTestId('graphql-preset-description')).toHaveTextContent('The shape YOU choose.');
    expect(screen.getByTestId('graphiql-link')).toHaveAttribute('href', '/graphiql');
  });

  it('picking another preset swaps the document', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okOutcome);

    render(<GraphqlPlaygroundDemo />);
    const select = await screen.findByTestId('graphql-preset-select') as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'error-envelope' } });

    const queryEditor = screen.getByTestId('graphql-query-input') as HTMLTextAreaElement;
    expect(queryEditor.value).toContain('does-not-exist');
  });

  it('runs the edited document and renders the data viewer', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okOutcome);

    render(<GraphqlPlaygroundDemo />);
    await screen.findByTestId('graphql-preset-select');
    fireEvent.click(screen.getByTestId('graphql-run'));

    const data = await screen.findByTestId('graphql-data');
    expect(data).toHaveTextContent('The uniform interface is the contract that scales');
    expect(screen.getByTestId('graphql-errors-empty')).toBeInTheDocument();

    const runCall = captured.find((call) => call.url === RUN_URL);
    expect(runCall).toBeDefined();
    const sent = JSON.parse(String(runCall?.init?.body)) as { query: string; variables: unknown };
    expect(sent.query).toContain('query FirstPage');
    expect(sent.variables).toEqual({});
  });

  it('renders GraphQL errors with their extensions code and requestId', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, errorOutcome);

    render(<GraphqlPlaygroundDemo />);
    await screen.findByTestId('graphql-preset-select');
    fireEvent.change(screen.getByTestId('graphql-preset-select') as HTMLSelectElement, {
      target: { value: 'error-envelope' },
    });
    fireEvent.click(screen.getByTestId('graphql-run'));

    await screen.findByTestId('graphql-error-row');
    expect(screen.getByTestId('graphql-error-code')).toHaveTextContent('not_found');
    expect(screen.getByTestId('graphql-error-code')).toHaveTextContent('["post"]');
    expect(screen.getByTestId('graphql-error-request-id')).toHaveTextContent('req-123');
    expect(screen.getByTestId('graphql-error-row')).toHaveTextContent('no post with id');
    expect(screen.getByTestId('graphql-data-null')).toBeInTheDocument();
  });

  it('refuses a non-object variables blob without running', async () => {
    const captured: CapturedCall[] = [];
    stubDemo(captured, okOutcome);

    render(<GraphqlPlaygroundDemo />);
    await screen.findByTestId('graphql-preset-select');
    fireEvent.change(screen.getByTestId('graphql-variables-input'), { target: { value: 'not json' } });
    fireEvent.click(screen.getByTestId('graphql-run'));

    expect(await screen.findByTestId('graphql-variables-invalid')).toBeInTheDocument();
    expect(captured.filter((call) => call.url === RUN_URL)).toHaveLength(0);
  });
});
