import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { ServerFailure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `api:posts-workbench` — topic 11 (api-design), the
 * whole-pipeline section. A full CRUD console over the posts resource the
 * topic builds:
 *
 * - GET    /demo/api/posts?page&size&status&tag — listing with pagination
 *   and filter controls
 * - POST   /demo/api/posts                      — create (light client-side
 *   validation first: the same title/body rules the server enforces)
 * - PUT    /demo/api/posts/{id}                 — edit carrying the version
 *   it read; a 409 renders as "someone updated it first" with both versions
 *   (the optimistic-concurrency story, readable)
 * - DELETE /demo/api/posts/{id}                 — remove, then refresh
 * - PATCH  /demo/scenario                       — the ScenarioStore's
 *   injectFailure toggle: on, every posts call answers the 503 envelope
 *
 * The demo speaks raw fetch like the arena demo — the workbench's product is
 * watching ONE resource answer the whole status matrix, including errors
 * rendered as data (409 detail fields), not as exceptions.
 */

type PostStatusWire = 'draft' | 'published';
type StatusFilter = 'all' | PostStatusWire;

interface PostDto {
  id: string;
  title: string;
  body: string;
  tags: string[];
  status: PostStatusWire;
  version: number;
  createdAt: string;
  updatedAt: string;
}

interface PagedDto {
  items: PostDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface ErrorEnvelope {
  code: string;
  message: string;
  requestId?: string;
  expectedVersion?: number;
  currentVersion?: number;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PagedDto }
  | { kind: 'error'; message: string; code: string; status: number; requestId?: string };

interface ConflictState {
  postId: string;
  expectedVersion: number;
  currentVersion: number;
}

interface EditorState {
  mode: 'create' | 'edit';
  postId?: string;
  version?: number;
  title: string;
  body: string;
  tags: string;
  status: PostStatusWire;
}

type Notice = { kind: 'client-validation' | 'server-validation'; message: string } | null;

const CELL = 'border-b border-outline px-xs py-xs';
const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';

const TITLE_MAX = 120;
const BODY_MAX = 64 * 1024;

/* ---- one guarded request: status + parsed body, never throws ---- */

interface ApiResponse {
  status: number;
  body: unknown;
}

async function request(
  config: AppConfig,
  method: string,
  path: string,
  payload?: unknown,
): Promise<ApiResponse> {
  try {
    const response = await fetch(`${config.apiBaseUrl}${path}`, {
      method,
      headers:
        payload === undefined
          ? { accept: 'application/json' }
          : { accept: 'application/json', 'content-type': 'application/json' },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    });
    const parsed: unknown = await response.json().catch(() => null);
    return { status: response.status, body: parsed };
  } catch {
    return { status: 0, body: null };
  }
}

function envelopeOf(body: unknown): ErrorEnvelope | null {
  if (
    typeof body === 'object' &&
    body !== null &&
    'error' in body &&
    typeof (body as { error: unknown }).error === 'object' &&
    (body as { error: unknown }).error !== null
  ) {
    const error = (body as { error: Record<string, unknown> }).error;
    return {
      code: typeof error['code'] === 'string' ? error['code'] : 'unknown_error',
      message:
        typeof error['message'] === 'string'
          ? error['message']
          : 'Request failed without a message.',
      requestId: typeof error['requestId'] === 'string' ? error['requestId'] : undefined,
      expectedVersion: typeof error['expectedVersion'] === 'number' ? error['expectedVersion'] : undefined,
      currentVersion: typeof error['currentVersion'] === 'number' ? error['currentVersion'] : undefined,
    };
  }
  return null;
}

/* ---- runtime validation of the stable wire shape (the client's mirror) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parsePaged(body: unknown): PagedDto {
  if (!isRecord(body) || !Array.isArray(body['items'])) {
    throw new Error('paged posts answer must carry an items array');
  }
  const items = body['items'].map((raw): PostDto => {
    if (
      !isRecord(raw) ||
      typeof raw['id'] !== 'string' ||
      typeof raw['title'] !== 'string' ||
      typeof raw['body'] !== 'string' ||
      !Array.isArray(raw['tags']) ||
      (raw['status'] !== 'draft' && raw['status'] !== 'published') ||
      typeof raw['version'] !== 'number' ||
      typeof raw['createdAt'] !== 'string' ||
      typeof raw['updatedAt'] !== 'string'
    ) {
      throw new Error('each post needs id, title, body, tags, status, version, timestamps');
    }
    return {
      id: raw['id'],
      title: raw['title'],
      body: raw['body'],
      tags: raw['tags'].filter((tag): tag is string => typeof tag === 'string'),
      status: raw['status'],
      version: raw['version'],
      createdAt: raw['createdAt'],
      updatedAt: raw['updatedAt'],
    };
  });
  for (const key of ['page', 'size', 'totalElements', 'totalPages'] as const) {
    if (typeof body[key] !== 'number') throw new Error(`paged posts answer needs ${key}`);
  }
  return {
    items,
    page: body['page'] as number,
    size: body['size'] as number,
    totalElements: body['totalElements'] as number,
    totalPages: body['totalPages'] as number,
  };
}

function listUrl(page: number, size: number, status: StatusFilter, tag: string): string {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status !== 'all') params.set('status', status);
  const trimmedTag = tag.trim();
  if (trimmedTag !== '') params.set('tag', trimmedTag);
  return `/demo/api/posts?${params.toString()}`;
}

function failurePanel(status: number, body: unknown): PanelState {
  const envelope = envelopeOf(body);
  if (envelope !== null) {
    return {
      kind: 'error',
      message: envelope.message,
      code: envelope.code,
      status,
      requestId: envelope.requestId,
    };
  }
  return {
    kind: 'error',
    message: status === 0 ? 'Could not reach the server.' : `Request failed with status ${String(status)}.`,
    code: 'unknown_error',
    status,
  };
}

function panelFailure(panel: Extract<PanelState, { kind: 'error' }>) {
  return new ServerFailure(panel.message, {
    code: panel.code,
    status: panel.status,
    requestId: panel.requestId,
  });
}

/* ---- the workbench ---- */

export default function PostsWorkbenchDemo() {
  const config = container.appConfig;
  const [panel, setPanel] = useState<PanelState>({ kind: 'loading' });
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(5);
  const [status, setStatus] = useState<StatusFilter>('all');
  const [tag, setTag] = useState('');
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [conflict, setConflict] = useState<ConflictState | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [injectFailure, setInjectFailure] = useState(false);

  const load = useCallback(
    async (nextPage: number, nextSize: number, nextStatus: StatusFilter, nextTag: string) => {
      setPanel({ kind: 'loading' });
      const { status: httpStatus, body } = await request(
        config,
        'GET',
        listUrl(nextPage, nextSize, nextStatus, nextTag),
      );
      if (httpStatus === 200) {
        try {
          setPanel({ kind: 'ready', page: parsePaged(body) });
        } catch {
          setPanel(failurePanel(httpStatus, body));
        }
        return;
      }
      setPanel(failurePanel(httpStatus, body));
    },
    [config],
  );

  useEffect(() => {
    void load(page, size, status, tag);
  }, [load, page, size, status, tag]);

  const openCreate = () => {
    setConflict(null);
    setNotice(null);
    setEditor({ mode: 'create', title: '', body: '', tags: '', status: 'draft' });
  };

  const openEdit = (post: PostDto) => {
    setConflict(null);
    setNotice(null);
    setEditor({
      mode: 'edit',
      postId: post.id,
      version: post.version,
      title: post.title,
      body: post.body,
      tags: post.tags.join(', '),
      status: post.status,
    });
  };

  /** Light client validation: the same title/body bounds the server enforces at 422. */
  const clientFault = (editorState: EditorState): string | null => {
    const title = editorState.title.trim();
    if (title.length < 1) return 'a post needs a title (1..120 characters)';
    if (title.length > TITLE_MAX) return `title must be at most ${String(TITLE_MAX)} characters`;
    if (editorState.body.length > BODY_MAX) return 'body must be at most 64 KiB';
    return null;
  }

  const save = async () => {
    if (editor === null) return;
    const fault = clientFault(editor);
    if (fault !== null) {
      setNotice({ kind: 'client-validation', message: fault });
      return;
    }
    setNotice(null);
    const payload = {
      title: editor.title,
      body: editor.body,
      tags: editor.tags
        .split(',')
        .map((entry) => entry.trim())
        .filter((entry) => entry !== ''),
      status: editor.status,
    };
    if (editor.mode === 'create') {
      const { status: httpStatus, body } = await request(config, 'POST', '/demo/api/posts', payload);
      if (httpStatus === 201) {
        setEditor(null);
        void load(page, size, status, tag);
        return;
      }
      const envelope = envelopeOf(body);
      setNotice({
        kind: 'server-validation',
        message: envelope?.message ?? `create failed with status ${String(httpStatus)}`,
      });
      return;
    }
    // The edit carries the version it read — the optimistic-concurrency token.
    const postId = editor.postId ?? '';
    const version = editor.version ?? 1;
    const { status: httpStatus, body } = await request(
      config,
      'PUT',
      `/demo/api/posts/${postId}`,
      { ...payload, version },
    );
    if (httpStatus === 200) {
      setEditor(null);
      setConflict(null);
      void load(page, size, status, tag);
      return;
    }
    if (httpStatus === 409) {
      const envelope = envelopeOf(body);
      setConflict({
        postId,
        expectedVersion: envelope?.expectedVersion ?? version,
        currentVersion: envelope?.currentVersion ?? version,
      });
      return;
    }
    const envelope = envelopeOf(body);
    setNotice({
      kind: 'server-validation',
      message: envelope?.message ?? `update failed with status ${String(httpStatus)}`,
    });
  };

  const remove = async (post: PostDto) => {
    await request(config, 'DELETE', `/demo/api/posts/${post.id}`);
    void load(page, size, status, tag);
  };

  /** The ScenarioStore toggle: PATCH /api/demo/scenario, then meet the 503 it injects. */
  const toggleScenario = async (next: boolean) => {
    setInjectFailure(next);
    await request(config, 'PATCH', '/demo/scenario', { injectFailure: next });
    void load(page, size, status, tag);
  };

  const paged = panel.kind === 'ready' ? panel.page : null;
  const pageNumber = paged === null ? 0 : paged.totalPages === 0 ? 1 : paged.page + 1;
  const pageCount = paged === null ? 1 : Math.max(paged.totalPages, 1);

  return (
    <div className="flex flex-col gap-md">
      {/* Controls: the listing contract as UI — filters, page size, page turner. */}
      <section className="flex flex-col gap-sm">
        <p className={OVERLINE}>filters &amp; paging — every control is a query param</p>
        <div className="flex flex-wrap items-center gap-xs" role="group" aria-label="listing controls">
          <label htmlFor="posts-status" className="font-mono text-caption text-on-surface-variant">
            status
          </label>
          <select
            id="posts-status"
            data-testid="posts-status-filter"
            value={status}
            onChange={(event) => {
              setPage(0);
              setStatus(event.target.value as StatusFilter);
            }}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            <option value="all">all</option>
            <option value="draft">draft</option>
            <option value="published">published</option>
          </select>
          <label htmlFor="posts-tag" className="font-mono text-caption text-on-surface-variant">
            tag
          </label>
          <input
            id="posts-tag"
            data-testid="posts-tag-filter"
            value={tag}
            onChange={(event) => {
              setPage(0);
              setTag(event.target.value);
            }}
            placeholder="rest"
            className="w-28 rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <label htmlFor="posts-size" className="font-mono text-caption text-on-surface-variant">
            size
          </label>
          <select
            id="posts-size"
            data-testid="posts-size"
            value={size}
            onChange={(event) => {
              setPage(0);
              setSize(Number(event.target.value));
            }}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          >
            {[5, 10, 20].map((option) => (
              <option key={option} value={option}>
                {String(option)}
              </option>
            ))}
          </select>
          <button
            type="button"
            data-testid="posts-prev-page"
            disabled={page === 0}
            onClick={() => setPage((current) => Math.max(current - 1, 0))}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-40"
          >
            ← prev
          </button>
          <span data-testid="posts-page-indicator" className="font-mono text-caption text-on-surface-variant">
            page {String(pageNumber)} / {String(pageCount)}
            {paged !== null ? ` · ${String(paged.totalElements)} posts` : ''}
          </span>
          <button
            type="button"
            data-testid="posts-next-page"
            disabled={paged === null || page + 1 >= paged.totalPages}
            onClick={() => setPage((current) => current + 1)}
            className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant disabled:opacity-40"
          >
            next →
          </button>
          <button
            type="button"
            data-testid="posts-new"
            onClick={openCreate}
            className="rounded-pill border border-secondary bg-secondary/15 px-sm py-xxs font-mono text-caption text-on-surface"
          >
            new post
          </button>
        </div>
      </section>

      {/* The listing. */}
      <section className="flex flex-col gap-sm">
        {panel.kind === 'loading' && (
          <p data-testid="posts-loading" className="text-body-sm text-on-surface-variant">
            loading posts…
          </p>
        )}
        {panel.kind === 'error' && <ErrorPanel subject="the posts listing" failure={panelFailure(panel)} />}
        {panel.kind === 'ready' && (
          <table data-testid="posts-table" className="w-full border-collapse text-body-sm">
            <thead>
              <tr className="text-left text-on-surface-variant">
                <th className={CELL}>title</th>
                <th className={CELL}>status</th>
                <th className={CELL}>tags</th>
                <th className={CELL}>version</th>
                <th className={CELL}>actions</th>
              </tr>
            </thead>
            <tbody className="text-on-surface">
              {paged?.items.map((post) => (
                <tr key={post.id} data-testid="posts-row">
                  <td className={`${CELL} font-medium`}>{post.title}</td>
                  <td className={CELL}>
                    <span className="font-mono text-caption text-on-surface-variant">{post.status}</span>
                  </td>
                  <td className={CELL}>
                    <span className="font-mono text-caption text-on-surface-variant">
                      {post.tags.join(', ') === '' ? '—' : post.tags.join(', ')}
                    </span>
                  </td>
                  <td className={`${CELL} font-mono text-caption`}>v{String(post.version)}</td>
                  <td className={CELL}>
                    <span className="flex gap-xs">
                      <button
                        type="button"
                        data-testid="posts-edit"
                        onClick={() => openEdit(post)}
                        className="rounded-pill border border-outline px-xs py-xxs font-mono text-caption text-on-surface-variant hover:bg-surface-tint"
                      >
                        edit
                      </button>
                      <button
                        type="button"
                        data-testid="posts-delete"
                        onClick={() => void remove(post)}
                        className="rounded-pill border border-outline px-xs py-xxs font-mono text-caption text-on-surface-variant hover:bg-surface-tint"
                      >
                        delete
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* The conflict: a 409 rendered as data, readable. */}
      {conflict !== null && (
        <section
          data-testid="posts-conflict"
          className="flex flex-col gap-xxs rounded-lg border border-error bg-surface-tint p-sm"
        >
          <p className="font-display text-body-sm text-error">someone updated it first</p>
          <p className="text-body-sm text-on-surface">
            Your edit was made against version {String(conflict.expectedVersion)}, but the post is
            now at version {String(conflict.currentVersion)}. Nothing was lost — the other writer
            won this round.
          </p>
          <button
            type="button"
            data-testid="posts-conflict-reload"
            className="self-start rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface"
            onClick={() => {
              setConflict(null);
              void load(page, size, status, tag);
            }}
          >
            reload the listing and edit the current version
          </button>
        </section>
      )}

      {/* The editor: create or replace, carrying the version it read. */}
      {editor !== null && (
        <section
          data-testid="posts-editor"
          className="flex flex-col gap-xs rounded-lg border border-outline bg-surface p-sm"
        >
          <p className={OVERLINE}>
            {editor.mode === 'create' ? 'create — POST /api/demo/api/posts' : 'replace — PUT carries the version'}
          </p>
          <label htmlFor="posts-title" className="font-mono text-caption text-on-surface-variant">
            title (1..120)
          </label>
          <input
            id="posts-title"
            data-testid="posts-title-input"
            value={editor.title}
            onChange={(event) => setEditor({ ...editor, title: event.target.value })}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <label htmlFor="posts-body" className="font-mono text-caption text-on-surface-variant">
            body
          </label>
          <textarea
            id="posts-body"
            data-testid="posts-body-input"
            rows={4}
            value={editor.body}
            onChange={(event) => setEditor({ ...editor, body: event.target.value })}
            className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
          />
          <div className="flex flex-wrap gap-xs">
            <div className="flex flex-col gap-xxs">
              <label htmlFor="posts-editor-tags" className="font-mono text-caption text-on-surface-variant">
                tags (comma-separated)
              </label>
              <input
                id="posts-editor-tags"
                data-testid="posts-tags-input"
                value={editor.tags}
                onChange={(event) => setEditor({ ...editor, tags: event.target.value })}
                className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
              />
            </div>
            <div className="flex flex-col gap-xxs">
              <label htmlFor="posts-editor-status" className="font-mono text-caption text-on-surface-variant">
                status
              </label>
              <select
                id="posts-editor-status"
                data-testid="posts-status-input"
                value={editor.status}
                onChange={(event) => setEditor({ ...editor, status: event.target.value as PostStatusWire })}
                className="rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface"
              >
                <option value="draft">draft</option>
                <option value="published">published</option>
              </select>
            </div>
          </div>
          <div className="flex gap-xs">
            <button
              type="button"
              data-testid="posts-save"
              onClick={() => void save()}
              className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
            >
              {editor.mode === 'create' ? 'create (201 expected)' : 'save (200 or 409)'}
            </button>
            <button
              type="button"
              data-testid="posts-cancel"
              onClick={() => setEditor(null)}
              className="rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface-variant"
            >
              cancel
            </button>
          </div>
        </section>
      )}

      {/* Notices: client-side bounds and server 422s, inline. */}
      {notice !== null && (
        <p
          data-testid="posts-notice"
          className="rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface-variant"
        >
          {notice.message}
        </p>
      )}

      {/* The ScenarioStore exhibit: one PATCH, every posts call answers 503. */}
      <section className="flex flex-col gap-xxs">
        <p className={OVERLINE}>scenario — inject a dependency failure</p>
        <button
          type="button"
          data-testid="posts-inject-failure"
          aria-pressed={injectFailure}
          onClick={() => void toggleScenario(!injectFailure)}
          className={`self-start rounded-pill border px-sm py-xxs font-mono text-caption transition-colors ${
            injectFailure
              ? 'border-error bg-error/10 text-on-surface'
              : 'border-outline text-on-surface-variant hover:bg-surface-tint'
          }`}
        >
          {injectFailure ? 'injectFailure: ON — every posts call answers 503' : 'injectFailure: OFF'}
        </button>
      </section>
    </div>
  );
}
