import { useEffect, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';
import { safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `files:workbench` — topic 26 (files-media), the
 * receiving-uploads-safely section. One small workbench over /api/demo/files:
 *
 * - UPLOAD: a plain file picker (no drag-and-drop theater) POSTed as
 *   multipart FormData; the answer card shows the lesson — the SERVED
 *   content type (sniffed from magic bytes) beside what the client
 *   CLAIMED, plus the sha256 that becomes the download's ETag.
 * - LIST: every stored record, originals and derived variants together.
 * - INLINE DOWNLOAD: fetch the selected record, read it as a blob, and
 *   preview it inline when the browser can (images render; everything
 *   else reports its byte length) — Content-Disposition inline vs
 *   attachment made visible.
 * - RANGE FETCH: one request with a Range header (editable, default
 *   0-99); the card reports the status (206), the Content-Range the
 *   server answered, and how many bytes ACTUALLY arrived — the client
 *   half of the range story.
 * - THUMBNAIL: derive a resized PNG variant on the server and preview it.
 */

interface StoredFile {
  id: string;
  filename: string;
  contentType: string;
  size: number;
  sha256: string;
  derivedFrom: string | null;
}

interface UploadAnswer {
  id: string;
  filename: string;
  contentType: string;
  claimedContentType: string;
  size: number;
  sha256: string;
}

type Panel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; view: UploadAnswer }
  | { kind: 'error'; failure: Failure };

type ListPanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; files: StoredFile[] }
  | { kind: 'error'; failure: Failure };

type RangePanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; status: number; contentRange: string; received: number }
  | { kind: 'error'; failure: Failure };

type InlinePanel =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; url: string | null; received: number; contentType: string }
  | { kind: 'error'; failure: Failure };

/* ---- the guarded fetch pipeline (same shape as every demo) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function requestJson(config: AppConfig, path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, init);
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

function parseFile(body: unknown): StoredFile {
  if (
    !isRecord(body) ||
    typeof body.id !== 'string' ||
    typeof body.filename !== 'string' ||
    typeof body.contentType !== 'string' ||
    typeof body.size !== 'number' ||
    typeof body.sha256 !== 'string'
  ) {
    throw new Error('stored file answer needs id, filename, contentType, size, sha256');
  }
  return {
    id: body.id,
    filename: body.filename,
    contentType: body.contentType,
    size: body.size,
    sha256: body.sha256,
    derivedFrom: typeof body.derivedFrom === 'string' ? body.derivedFrom : null,
  };
}

/** The upload: the browser builds the multipart envelope; the file streams from disk. */
async function uploadFile(config: AppConfig, file: File): Promise<UploadAnswer> {
  const form = new FormData();
  form.append('file', file, file.name);
  const body = await requestJson(config, '/demo/files/upload', { method: 'POST', body: form });
  if (
    !isRecord(body) ||
    typeof body.id !== 'string' ||
    typeof body.filename !== 'string' ||
    typeof body.contentType !== 'string' ||
    typeof body.claimedContentType !== 'string' ||
    typeof body.size !== 'number' ||
    typeof body.sha256 !== 'string'
  ) {
    throw new Error('upload answer needs id, filename, types, size, sha256');
  }
  return {
    id: body.id,
    filename: body.filename,
    contentType: body.contentType,
    claimedContentType: body.claimedContentType,
    size: body.size,
    sha256: body.sha256,
  };
}

/**
 * The range fetch — ONE request header is the whole client half: the
 * server answers 206 with exactly the slice, and Content-Range says
 * which slice that was. received is what ACTUALLY arrived.
 */
async function fetchRange(
  config: AppConfig,
  id: string,
  range: string,
): Promise<{ status: number; contentRange: string; received: number }> {
  const response = await fetch(`${config.apiBaseUrl}/demo/files/${id}`, {
    headers: { range },
  });
  return {
    status: response.status,
    contentRange: response.headers.get('content-range') ?? '(none)',
    received: (await response.arrayBuffer()).byteLength,
  };
}

/**
 * The inline preview: fetch the bytes, read them as a blob, let the browser
 * render. Object-URL creation is fail-soft — environments without it (or
 * with a strict shim) still get the byte-count preview.
 */
async function fetchInline(
  config: AppConfig,
  id: string,
): Promise<{ url: string | null; received: number; contentType: string }> {
  const response = await fetch(`${config.apiBaseUrl}/demo/files/${id}?disposition=inline`);
  const blob = await response.blob();
  let url: string | null = null;
  try {
    if (typeof URL.createObjectURL === 'function') url = URL.createObjectURL(blob);
  } catch {
    url = null;
  }
  return { url, received: blob.size, contentType: response.headers.get('content-type') ?? '(none)' };
}

/* ---- presentation ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const BUTTON =
  'rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface';
const GHOST_BUTTON = 'rounded-pill border border-outline px-md py-xxs font-mono text-caption text-on-surface';

function shortSha(sha256: string): string {
  return sha256.length > 12 ? `${sha256.slice(0, 12)}…` : sha256;
}

export default function WorkbenchDemo() {
  const config = container.appConfig;
  const [panel, setPanel] = useState<Panel>({ kind: 'idle' });
  const [listPanel, setListPanel] = useState<ListPanel>({ kind: 'idle' });
  const [inlinePanel, setInlinePanel] = useState<InlinePanel>({ kind: 'idle' });
  const [rangePanel, setRangePanel] = useState<RangePanel>({ kind: 'idle' });
  const [thumbPanel, setThumbPanel] = useState<InlinePanel>({ kind: 'idle' });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [range, setRange] = useState('0-99');
  const [thumbSize, setThumbSize] = useState('128');

  useEffect(() => {
    return () => {
      if (inlinePanel.kind === 'ready' && inlinePanel.url) URL.revokeObjectURL(inlinePanel.url);
      if (thumbPanel.kind === 'ready' && thumbPanel.url) URL.revokeObjectURL(thumbPanel.url);
    };
  }, [inlinePanel, thumbPanel]);

  const upload = (file: File | undefined) => {
    if (!file) return;
    setPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => uploadFile(config, file)).then((result) => {
      setPanel(
        result.ok
          ? { kind: 'ready', view: result.value }
          : { kind: 'error', failure: result.failure },
      );
      if (result.ok) setSelectedId(result.value.id);
    });
  };

  const refreshList = () => {
    setListPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const body = await requestJson(config, '/demo/files');
      if (!Array.isArray(body)) throw new Error('list answer must be an array');
      const parsed = body.map((entry) => parseFile(entry));
      return { files: parsed };
    }).then((result) => {
      setListPanel(
        result.ok
          ? { kind: 'ready', files: result.value.files }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const runInline = (id: string) => {
    setInlinePanel({ kind: 'loading' });
    void safeRepositoryCall(async () => fetchInline(config, id)).then((result) => {
      setInlinePanel(
        result.ok
          ? { kind: 'ready', ...result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const runRange = () => {
    if (!selectedId) return;
    setRangePanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const answer = await fetchRange(config, selectedId, `bytes=${range}`);
      return { ...answer };
    }).then((result) => {
      setRangePanel(
        result.ok
          ? { kind: 'ready', ...result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  const makeThumbnail = () => {
    if (!selectedId) return;
    setThumbPanel({ kind: 'loading' });
    void safeRepositoryCall(async () => {
      const created = parseFile(
        await requestJson(config, `/demo/files/${selectedId}/thumbnail?size=${thumbSize}`, {
          method: 'POST',
        }),
      );
      const preview = await fetchInline(config, created.id);
      return preview;
    }).then((result) => {
      setThumbPanel(
        result.ok
          ? { kind: 'ready', ...result.value }
          : { kind: 'error', failure: result.failure },
      );
    });
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          pick a file
          <input
            data-testid="files-picker"
            type="file"
            onChange={(event) => upload(event.target.files?.[0])}
            className={`${INPUT} w-64`}
          />
        </label>
        <button type="button" data-testid="files-refresh" onClick={refreshList} className={BUTTON}>
          list stored files
        </button>
      </section>

      {panel.kind === 'idle' && (
        <p data-testid="files-idle" className="font-mono text-caption text-on-surface-variant">
          upload something — the card shows the sniffed type beside the client's claim
        </p>
      )}
      {panel.kind === 'loading' && (
        <p data-testid="files-uploading" className="font-mono text-caption text-on-surface-variant">
          uploading…
        </p>
      )}
      {panel.kind === 'error' && <ErrorPanel subject="the upload" failure={panel.failure} />}
      {panel.kind === 'ready' && (
        <section
          data-testid="files-upload-result"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          <span data-testid="files-upload-name">
            stored “{panel.view.filename}” ({String(panel.view.size)} bytes)
          </span>
          <span data-testid="files-upload-types">
            served: {panel.view.contentType} · claimed: {panel.view.claimedContentType}
          </span>
          <span data-testid="files-upload-sha" className="break-all text-on-surface-variant">
            sha256: {shortSha(panel.view.sha256)} — this becomes the download's ETag
          </span>
        </section>
      )}

      {listPanel.kind === 'ready' && (
        <section
          data-testid="files-list"
          className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {listPanel.files.length === 0 && <span className="text-on-surface-variant">nothing stored yet</span>}
          {listPanel.files.map((file) => (
            <button
              type="button"
              key={file.id}
              data-testid={`files-row-${file.id}`}
              onClick={() => setSelectedId(file.id)}
              className={`flex items-center gap-sm text-left ${
                selectedId === file.id ? 'text-secondary' : 'text-on-surface'
              }`}
            >
              <span className="w-48 truncate">{file.filename}</span>
              <span className="text-on-surface-variant">{file.contentType}</span>
              <span>{String(file.size)}B</span>
              {file.derivedFrom && (
                <span className="rounded-pill border border-outline px-xs text-on-surface-variant">
                  derived
                </span>
              )}
            </button>
          ))}
        </section>
      )}
      {listPanel.kind === 'error' && <ErrorPanel subject="the list" failure={listPanel.failure} />}

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>the selected record ({selectedId ? 'chosen' : 'pick one above'})</p>
        <div className="flex flex-wrap items-end gap-sm">
          <button
            type="button"
            data-testid="files-inline"
            disabled={selectedId === null}
            onClick={() => selectedId && runInline(selectedId)}
            className={BUTTON}
          >
            fetch inline
          </button>
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            range
            <input
              data-testid="files-range-input"
              value={range}
              onChange={(event) => setRange(event.target.value)}
              className={`${INPUT} w-24`}
            />
          </label>
          <button type="button" data-testid="files-range-fetch" onClick={runRange} className={GHOST_BUTTON}>
            fetch bytes=
          </button>
          <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
            size
            <select
              data-testid="files-thumb-size"
              value={thumbSize}
              onChange={(event) => setThumbSize(event.target.value)}
              className={INPUT}
            >
              <option value="32">32</option>
              <option value="64">64</option>
              <option value="128">128</option>
            </select>
          </label>
          <button type="button" data-testid="files-thumbnail" onClick={makeThumbnail} className={GHOST_BUTTON}>
            make thumbnail
          </button>
        </div>

        {inlinePanel.kind === 'ready' && (
          <section
            data-testid="files-inline-result"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <span>
              received {String(inlinePanel.received)} bytes as {inlinePanel.contentType}
            </span>
            {inlinePanel.url && inlinePanel.contentType.startsWith('image/') && (
              <img
                data-testid="files-inline-image"
                src={inlinePanel.url}
                alt="the stored file, served inline"
                className="max-h-32 w-auto rounded-sm border border-outline"
              />
            )}
          </section>
        )}
        {inlinePanel.kind === 'error' && <ErrorPanel subject="the inline fetch" failure={inlinePanel.failure} />}

        {rangePanel.kind === 'ready' && (
          <section
            data-testid="files-range-result"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <span data-testid="files-range-status">
              status {String(rangePanel.status)}
              {rangePanel.status === 206 ? ' — partial content' : rangePanel.status === 416 ? ' — unsatisfiable' : ''}
            </span>
            <span className="break-all text-on-surface-variant">content-range: {rangePanel.contentRange}</span>
            <span data-testid="files-range-received">{String(rangePanel.received)} bytes actually arrived</span>
          </section>
        )}
        {rangePanel.kind === 'error' && <ErrorPanel subject="the range fetch" failure={rangePanel.failure} />}

        {thumbPanel.kind === 'ready' && (
          <section
            data-testid="files-thumb-result"
            className="flex flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
          >
            <span>derived variant: {String(thumbPanel.received)} bytes of {thumbPanel.contentType}</span>
            {thumbPanel.url && (
              <img
                data-testid="files-thumb-image"
                src={thumbPanel.url}
                alt="the derived thumbnail"
                className="h-16 w-16 rounded-sm border border-outline object-contain"
              />
            )}
          </section>
        )}
        {thumbPanel.kind === 'error' && <ErrorPanel subject="the thumbnail" failure={thumbPanel.failure} />}
      </section>
    </div>
  );
}
