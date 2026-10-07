import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import WorkbenchDemo from './workbench_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * WorkbenchDemo spec — the files workbench over stubbed endpoints: the
 * picker upload POSTs multipart and renders the honest card (sniffed type
 * BESIDE the claimed type, the sha that becomes the ETag); the list GETs
 * and renders rows; selecting a record and fetching a range sends the
 * Range header verbatim and renders the 206 answer with the Content-Range
 * and the byte count that actually arrived; the thumbnail POSTs and then
 * previews the derived record inline; and the typed 413 envelope
 * (payload_too_large) renders the error panel with the envelope's message
 * and code.
 */

interface Captured {
  url: string;
  method: string;
  headers?: Record<string, string>;
}

type Responder = (
  url: string,
  init?: RequestInit,
) => {
  ok: boolean;
  status: number;
  headers: { get: (name: string) => string | null };
  json: () => Promise<unknown>;
  arrayBuffer: () => Promise<ArrayBuffer>;
  blob: () => Promise<{ size: number }>;
};

const UPLOADED = {
  id: '11111111-1111-1111-1111-111111111111',
  filename: 'cat.png',
  contentType: 'image/png',
  claimedContentType: 'text/html',
  size: 4321,
  sha256: 'a'.repeat(64),
  etag: `"${'a'.repeat(64)}"`,
  uploadedAt: '2026-10-06T10:00:00Z',
  derivedFrom: null,
};

const DERIVED = {
  id: '22222222-2222-2222-2222-222222222222',
  filename: 'cat-64.png',
  contentType: 'image/png',
  size: 900,
  sha256: 'b'.repeat(64),
  etag: `"${'b'.repeat(64)}"`,
  uploadedAt: '2026-10-06T10:01:00Z',
  derivedFrom: UPLOADED.id,
};

function stubEndpoints(captured: Captured[], mode: 'ok' | 'too-large'): Responder {
  const respond: Responder = (url, init) => {
    captured.push({
      url,
      method: init?.method ?? 'GET',
      headers:
        init?.headers && typeof init.headers === 'object' && !Array.isArray(init.headers)
          ? (init.headers as Record<string, string>)
          : undefined,
    });
    const headers = {
      get: (name: string) => {
        const lower = name.toLowerCase();
        if (lower === 'content-range') return 'bytes 0-99/4321';
        if (lower === 'content-type') return 'image/png';
        return null;
      },
    };
    if (mode === 'too-large' && url.includes('/upload')) {
      return {
        ok: false,
        status: 413,
        headers,
        json: async () => ({
          error: {
            code: 'payload_too_large',
            message: 'upload exceeded the configured cap (max 1024 bytes, received 2048)',
            requestId: 'stub',
          },
        }),
        arrayBuffer: async () => new ArrayBuffer(0),
        blob: async () => ({ size: 0 }),
      };
    }
    if (url.includes('/upload')) {
      return {
        ok: true,
        status: 201,
        headers,
        json: async () => UPLOADED,
        arrayBuffer: async () => new ArrayBuffer(0),
        blob: async () => ({ size: 0 }),
      };
    }
    if (url.includes('/thumbnail')) {
      return {
        ok: true,
        status: 201,
        headers,
        json: async () => DERIVED,
        arrayBuffer: async () => new ArrayBuffer(0),
        blob: async () => ({ size: 0 }),
      };
    }
    if (url === '/api/demo/files') {
      return {
        ok: true,
        status: 200,
        headers,
        json: async () => [UPLOADED, DERIVED],
        arrayBuffer: async () => new ArrayBuffer(0),
        blob: async () => ({ size: 0 }),
      };
    }
    if (url.includes('disposition=inline')) {
      return {
        ok: true,
        status: 200,
        headers,
        json: async () => ({}),
        arrayBuffer: async () => new ArrayBuffer(4321),
        blob: async () => ({ size: 4321 }),
      };
    }
    // the range fetch: 206, exactly 100 bytes of body
    return {
      ok: true,
      status: 206,
      headers,
      json: async () => ({}),
      arrayBuffer: async () => new ArrayBuffer(100),
      blob: async () => ({ size: 100 }),
    };
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      return respond(url, init);
    }),
  );
  return respond;
}

function uploadViaPicker(file: File) {
  const picker = screen.getByTestId('files-picker') as HTMLInputElement;
  Object.defineProperty(picker, 'files', { value: [file] });
  fireEvent.change(picker);
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('WorkbenchDemo', () => {
  it('renders the idle prompt before any upload', () => {
    stubEndpoints([], 'ok');
    render(<WorkbenchDemo />);
    expect(screen.getByTestId('files-idle')).toHaveTextContent('sniffed type');
    expect(screen.queryByTestId('files-upload-result')).toBeNull();
  });

  it('uploads through the picker and renders sniffed type beside the claim', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');
    render(<WorkbenchDemo />);

    uploadViaPicker(new File([new Uint8Array([1, 2, 3])], 'cat.png', { type: 'text/html' }));

    const card = await screen.findByTestId('files-upload-result');
    expect(card).toHaveTextContent('cat.png');
    expect(card).toHaveTextContent('4321 bytes');
    expect(screen.getByTestId('files-upload-types')).toHaveTextContent('served: image/png');
    expect(screen.getByTestId('files-upload-types')).toHaveTextContent('claimed: text/html');
    const uploadCall = captured.find((call) => call.url.includes('/upload'));
    expect(uploadCall?.method).toBe('POST');
  });

  it('lists stored files with original and derived rows', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');
    render(<WorkbenchDemo />);

    fireEvent.click(screen.getByTestId('files-refresh'));
    const list = await screen.findByTestId('files-list');
    expect(list).toHaveTextContent('cat.png');
    expect(list).toHaveTextContent('derived');
    expect(captured.find((call) => call.url === '/api/demo/files')).toBeDefined();
  });

  it('fetches a range for the selected record and reports the 206 answer', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');
    render(<WorkbenchDemo />);

    uploadViaPicker(new File([new Uint8Array([1, 2, 3])], 'cat.png', { type: 'text/html' }));
    await screen.findByTestId('files-upload-result');

    fireEvent.change(screen.getByTestId('files-range-input'), { target: { value: '0-99' } });
    fireEvent.click(screen.getByTestId('files-range-fetch'));

    const panel = await screen.findByTestId('files-range-result');
    expect(screen.getByTestId('files-range-status')).toHaveTextContent('status 206');
    expect(panel).toHaveTextContent('content-range: bytes 0-99/4321');
    expect(screen.getByTestId('files-range-received')).toHaveTextContent('100 bytes');
    const rangeCall = captured.find((call) => call.headers !== undefined);
    expect(rangeCall?.headers).toEqual({ range: 'bytes=0-99' });
    expect(rangeCall?.url).toContain(UPLOADED.id);
  });

  it('derives a thumbnail and previews the variant inline', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, 'ok');
    render(<WorkbenchDemo />);

    uploadViaPicker(new File([new Uint8Array([1, 2, 3])], 'cat.png', { type: 'text/html' }));
    await screen.findByTestId('files-upload-result');

    fireEvent.change(screen.getByTestId('files-thumb-size'), { target: { value: '64' } });
    fireEvent.click(screen.getByTestId('files-thumbnail'));

    const panel = await screen.findByTestId('files-thumb-result');
    expect(panel).toHaveTextContent('derived variant');
    const thumbnailCall = captured.find((call) => call.url.includes('/thumbnail'));
    expect(thumbnailCall?.url).toContain('size=64');
    const previewCall = captured.find((call) => call.url.includes('disposition=inline'));
    expect(previewCall?.url).toContain(DERIVED.id);
  });

  it('renders the typed 413 envelope when the cap is crossed', async () => {
    stubEndpoints([], 'too-large');
    render(<WorkbenchDemo />);

    uploadViaPicker(new File([new Uint8Array([1, 2, 3])], 'big.bin', { type: 'application/octet-stream' }));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('exceeded the configured cap');
    expect(panel).toHaveTextContent('payload_too_large');
  });
});
