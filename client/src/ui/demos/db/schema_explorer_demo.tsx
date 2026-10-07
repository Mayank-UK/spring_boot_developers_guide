import { useEffect, useMemo, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `db:schema-explorer` — topic 16 (sql-schema), the
 * indexes section. A live explorer over the server's catalog reader:
 * - GET /api/demo/db/schema → every user table the way PostgreSQL itself
 *   describes it (information_schema + pg_catalog — the same source psql's
 *   \d reads): columns with real types (varchar(120), text[], timestamptz),
 *   constraints (PK/CHECK/UNIQUE/FK with pg_get_constraintdef's wording),
 *   indexes with sizes, and per-table COMMENT ON annotations.
 * - The table picker selects one table's detail; the demo never ships a
 *   hand-written schema description — everything rendered arrived from the
 *   database's own catalog, which is the whole point of the section.
 */

interface ColumnInsight {
  name: string;
  type: string;
  nullable: boolean;
  defaultValue: string | null;
  comment: string | null;
}

interface ConstraintInsight {
  name: string;
  kind: string;
  definition: string;
}

interface IndexInsight {
  name: string;
  columns: string | null;
  definition: string;
  bytes: number;
  size: string;
}

interface TableInsight {
  name: string;
  comment: string | null;
  rowEstimate: number | null;
  totalBytes: number;
  totalSize: string;
  columns: ColumnInsight[];
  constraints: ConstraintInsight[];
  indexes: IndexInsight[];
}

interface SchemaView {
  area: string;
  dataMode: string;
  tables: TableInsight[];
}

type Panel =
  | { kind: 'loading' }
  | { kind: 'ready'; view: SchemaView; selected: string }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded fetch pipeline (same shape as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchJson(config: AppConfig, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`);
  } catch (error) {
    throw new NetworkException('Could not reach the server.', { cause: error });
  }
  let parsed: unknown;
  try {
    parsed = await response.json();
  } catch (error) {
    throw new ServerException(`Request failed with status ${String(response.status)}.`, {
      status: response.status,
      cause: error,
    });
  }
  if (!response.ok) {
    if (isRecord(parsed) && isRecord(parsed.error)) {
      throw new ServerException(
        typeof parsed.error.message === 'string'
          ? parsed.error.message
          : `Request failed with status ${String(response.status)}.`,
        {
          status: response.status,
          code: typeof parsed.error.code === 'string' ? parsed.error.code : 'unknown_error',
          requestId:
            typeof parsed.error.requestId === 'string' ? parsed.error.requestId : undefined,
        },
      );
    }
    throw new ServerException(`Request failed with status ${String(response.status)}.`, {
      status: response.status,
    });
  }
  return parsed;
}

function parseView(body: unknown): SchemaView {
  if (!isRecord(body) || !Array.isArray(body.tables)) throw new Error('schema view needs tables');
  if (body.tables.length === 0) throw new Error('the catalog came back with no tables');
  const tables: TableInsight[] = [];
  for (const raw of body.tables) {
    if (
      !isRecord(raw) ||
      typeof raw.name !== 'string' ||
      !Array.isArray(raw.columns) ||
      !Array.isArray(raw.constraints) ||
      !Array.isArray(raw.indexes)
    ) {
      throw new Error(`table ${String(raw)} is missing columns/constraints/indexes`);
    }
    tables.push({
      name: raw.name,
      comment: raw.comment === null || raw.comment === undefined ? null : String(raw.comment),
      rowEstimate:
        raw.rowEstimate === null || raw.rowEstimate === undefined ? null : Number(raw.rowEstimate),
      totalBytes: typeof raw.totalBytes === 'number' ? raw.totalBytes : 0,
      totalSize: typeof raw.totalSize === 'string' ? raw.totalSize : '0 bytes',
      columns: raw.columns.map((column) => {
        if (!isRecord(column) || typeof column.name !== 'string' || typeof column.type !== 'string') {
          throw new Error('each column needs name and type');
        }
        return {
          name: column.name,
          type: column.type,
          nullable: column.nullable === true,
          defaultValue:
            column.defaultValue === null || column.defaultValue === undefined
              ? null
              : String(column.defaultValue),
          comment:
            column.comment === null || column.comment === undefined ? null : String(column.comment),
        };
      }),
      constraints: raw.constraints.map((constraint) => {
        if (
          !isRecord(constraint) ||
          typeof constraint.name !== 'string' ||
          typeof constraint.kind !== 'string' ||
          typeof constraint.definition !== 'string'
        ) {
          throw new Error('each constraint needs name, kind, definition');
        }
        return {
          name: constraint.name,
          kind: constraint.kind,
          definition: constraint.definition,
        };
      }),
      indexes: raw.indexes.map((index) => {
        if (
          !isRecord(index) ||
          typeof index.name !== 'string' ||
          typeof index.definition !== 'string'
        ) {
          throw new Error('each index needs name and definition');
        }
        return {
          name: index.name,
          columns:
            index.columns === null || index.columns === undefined ? null : String(index.columns),
          definition: index.definition,
          bytes: typeof index.bytes === 'number' ? index.bytes : 0,
          size: typeof index.size === 'string' ? index.size : '0 bytes',
        };
      }),
    });
  }
  return {
    area: typeof body.area === 'string' ? body.area : 'db',
    dataMode: typeof body.dataMode === 'string' ? body.dataMode : 'unknown',
    tables,
  };
}

/* ---- presentation ---- */

function yesNo(value: boolean): string {
  return value ? 'yes' : 'no';
}

export default function SchemaExplorerDemo() {
  const config = container.appConfig;
  const [panel, setPanel] = useState<Panel>({ kind: 'loading' });

  useEffect(() => {
    void safeRepositoryCall(async () => parseView(await fetchJson(config, '/demo/db/schema'))).then(
      (result) => {
        if (result.ok) {
          setPanel({ kind: 'ready', view: result.value, selected: result.value.tables[0]!.name });
        } else {
          setPanel({ kind: 'error', failure: result.failure });
        }
      },
    );
  }, [config]);

  const selectedTable = useMemo(
    () =>
      panel.kind === 'ready'
        ? panel.view.tables.find((table) => table.name === panel.selected) ?? null
        : null,
    [panel],
  );

  if (panel.kind === 'loading') {
    return (
      <p data-testid="schema-loading" className="font-mono text-caption text-on-surface-variant">
        reading the catalog…
      </p>
    );
  }
  if (panel.kind === 'error') {
    return <ErrorPanel subject="the schema explorer" failure={panel.failure} />;
  }

  const { view } = panel;
  return (
    <div className="flex flex-col gap-md">
      <div className="flex flex-wrap items-center gap-sm">
        <label htmlFor="schema-table-select" className="font-mono text-caption text-on-surface-variant">
          \d — pick a table
        </label>
        <select
          id="schema-table-select"
          data-testid="schema-table-select"
          value={panel.selected}
          onChange={(event) => setPanel({ ...panel, selected: event.target.value })}
          className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
        >
          {view.tables.map((table) => (
            <option key={table.name} value={table.name}>
              {table.name}
            </option>
          ))}
        </select>
        <span data-testid="schema-datamode" className="font-mono text-caption text-on-surface-variant">
          area {view.area} · {view.dataMode}
        </span>
      </div>

      {selectedTable !== null && (
        <div className="flex flex-col gap-sm">
          <p data-testid="schema-table-comment" className="font-mono text-caption text-on-surface">
            {selectedTable.comment ?? '(no COMMENT ON annotation)'} — on disk{' '}
            <span data-testid="schema-table-size">{selectedTable.totalSize}</span>
            {selectedTable.rowEstimate !== null
              ? ` · planner estimates ~${String(selectedTable.rowEstimate)} rows`
              : ' · never analyzed (no row estimate)'}
          </p>

          <table data-testid="schema-columns-table" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                <th className="border-b border-outline px-xs py-xs font-semibold">column</th>
                <th className="border-b border-outline px-xs py-xs font-semibold">type</th>
                <th className="border-b border-outline px-xs py-xs font-semibold">nullable</th>
                <th className="border-b border-outline px-xs py-xs font-semibold">default</th>
              </tr>
            </thead>
            <tbody className="text-on-surface">
              {selectedTable.columns.map((column) => (
                <tr key={column.name} data-testid="schema-column-row">
                  <td className="border-b border-outline px-xs py-xs font-mono">{column.name}</td>
                  <td className="border-b border-outline px-xs py-xs font-mono">{column.type}</td>
                  <td className="border-b border-outline px-xs py-xs font-mono">
                    {yesNo(column.nullable)}
                  </td>
                  <td className="border-b border-outline px-xs py-xs font-mono text-caption text-on-surface-variant">
                    {column.defaultValue ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div data-testid="schema-constraints" className="flex flex-col gap-xxs">
            {selectedTable.constraints.map((constraint) => (
              <p
                key={constraint.name}
                data-testid="schema-constraint-row"
                className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
              >
                <span className="text-on-surface-variant">{constraint.kind}</span>{' '}
                <span className="font-semibold">{constraint.name}</span> — {constraint.definition}
              </p>
            ))}
          </div>

          <div data-testid="schema-indexes" className="flex flex-col gap-xxs">
            {selectedTable.indexes.map((index) => (
              <p
                key={index.name}
                data-testid="schema-index-row"
                className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
              >
                <span className="font-semibold">{index.name}</span>{' '}
                <span className="text-on-surface-variant">
                  ({index.columns ?? 'expression'}) · {index.size}
                </span>
                <br />
                {index.definition}
              </p>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
