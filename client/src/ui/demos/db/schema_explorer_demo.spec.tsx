import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import SchemaExplorerDemo from './schema_explorer_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * SchemaExplorerDemo spec — the explorer over the stubbed catalog endpoint:
 * the default table renders PostgreSQL's own description (typed columns,
 * the PK/CHECK constraints, the two posts indexes with sizes); the picker
 * switches to another table; and the area/dataMode chips render what the
 * lazy activation did.
 */

const SCHEMA_URL = '/api/demo/db/schema';

function stubSchema() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url !== SCHEMA_URL) {
        return {
          ok: false,
          status: 404,
          json: async () => ({ error: { code: 'not_found', message: `no stub for ${url}`, requestId: 'stub' } }),
        };
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({
          area: 'db',
          dataMode: 'live',
          tables: [
            {
              name: 'posts',
              comment: 'The guide canonical resource.',
              rowEstimate: 12,
              totalBytes: 73728,
              totalSize: '72 kB',
              columns: [
                { name: 'id', type: 'uuid', nullable: false, defaultValue: null, comment: null },
                {
                  name: 'title',
                  type: 'character varying(120)',
                  nullable: false,
                  defaultValue: null,
                  comment: null,
                },
                { name: 'tags', type: 'text[]', nullable: false, defaultValue: "'{}'::text[]", comment: 'text[] not jsonb' },
                {
                  name: 'created_at',
                  type: 'timestamp with time zone',
                  nullable: false,
                  defaultValue: null,
                  comment: null,
                },
              ],
              constraints: [
                { name: 'posts_pkey', kind: 'PRIMARY KEY', definition: 'PRIMARY KEY (id)' },
                {
                  name: 'posts_status_check',
                  kind: 'CHECK',
                  definition: "CHECK ((status)::text = ANY ((ARRAY['draft'::character varying, 'published'::character varying])::text[]))",
                },
              ],
              indexes: [
                {
                  name: 'posts_pkey',
                  columns: 'id',
                  definition: 'CREATE UNIQUE INDEX posts_pkey ON public.posts USING btree (id)',
                  bytes: 16384,
                  size: '16 kB',
                },
                {
                  name: 'posts_status_idx',
                  columns: 'status',
                  definition: 'CREATE INDEX posts_status_idx ON public.posts USING btree (status)',
                  bytes: 8192,
                  size: '8192 bytes',
                },
              ],
            },
            {
              name: 'audit_log',
              comment: 'Append-only change ledger.',
              rowEstimate: null,
              totalBytes: 8192,
              totalSize: '8192 bytes',
              columns: [
                {
                  name: 'id',
                  type: 'bigint',
                  nullable: false,
                  defaultValue: "nextval('audit_log_id_seq'::regclass)",
                  comment: null,
                },
                { name: 'payload', type: 'jsonb', nullable: false, defaultValue: null, comment: null },
              ],
              constraints: [{ name: 'audit_log_pkey', kind: 'PRIMARY KEY', definition: 'PRIMARY KEY (id)' }],
              indexes: [
                {
                  name: 'audit_log_entity_idx',
                  columns: 'entity_type, entity_id, id',
                  definition:
                    'CREATE INDEX audit_log_entity_idx ON public.audit_log USING btree (entity_type, entity_id, id)',
                  bytes: 8192,
                  size: '8192 bytes',
                },
              ],
            },
          ],
        }),
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

describe('SchemaExplorerDemo', () => {
  it('renders the default table as the catalog describes it', async () => {
    stubSchema();

    render(<SchemaExplorerDemo />);

    const columns = await screen.findByTestId('schema-columns-table');
    expect(columns).toHaveTextContent('uuid');
    expect(columns).toHaveTextContent('text[]');
    expect(columns).toHaveTextContent("'{}'::text[]");
    expect(screen.getAllByTestId('schema-column-row').length).toBe(4);

    const constraints = screen.getByTestId('schema-constraints');
    expect(constraints).toHaveTextContent('PRIMARY KEY');
    expect(constraints).toHaveTextContent('posts_pkey');
    expect(constraints).toHaveTextContent('CHECK');

    const indexes = screen.getByTestId('schema-indexes');
    expect(indexes).toHaveTextContent('posts_status_idx');
    expect(indexes).toHaveTextContent('status');
    expect(screen.getAllByTestId('schema-index-row').length).toBe(2);

    expect(screen.getByTestId('schema-table-size')).toHaveTextContent('72 kB');
    expect(screen.getByTestId('schema-datamode')).toHaveTextContent('live');
  });

  it('switches tables through the picker and renders the other table', async () => {
    stubSchema();

    render(<SchemaExplorerDemo />);
    await screen.findByTestId('schema-columns-table');

    const select = screen.getByTestId('schema-table-select') as HTMLSelectElement;
    expect(select.value).toBe('posts');

    fireEvent.change(select, { target: { value: 'audit_log' } });

    const columns = await screen.findByTestId('schema-columns-table');
    expect(columns).toHaveTextContent('jsonb');
    expect(columns).toHaveTextContent("nextval('audit_log_id_seq'::regclass)");
    expect(screen.getByTestId('schema-indexes')).toHaveTextContent('entity_type, entity_id, id');
    expect(screen.getByTestId('schema-table-comment')).toHaveTextContent('Append-only change ledger.');
  });

  it('renders the degraded panel when the dependency is down', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 503,
        json: async () => ({
          error: { code: 'service_unavailable', message: 'PostgreSQL is unreachable', requestId: 'stub' },
        }),
      })),
    );

    render(<SchemaExplorerDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('PostgreSQL is unreachable');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
