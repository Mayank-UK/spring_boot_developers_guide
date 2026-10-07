import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import PiiDemo from './pii_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * PiiDemo spec — the crypto-shred before/after over stubbed routes: store
 * renders the ciphertext preview and key id; read decrypts while the key
 * exists; erase renders the shred receipt; the read AFTER erase renders
 * the typed undecryptable failure (the same route, a different story);
 * the log renders the append-only trail.
 */

const STORE_URL = '/api/demo/security/pii/store';
const READ_URL = '/api/demo/security/pii/read/usr_demo_1';
const ERASE_URL = '/api/demo/security/pii/erase/usr_demo_1';
const LOG_URL = '/api/demo/security/pii/log';

const STORED = {
  subject: 'usr_demo_1',
  keyId: 'key_ab12cd34',
  ciphertextPreview: { name: 'qL0tX3NlZWRm…', email: 'ZGFhQGV4YW1wbGUuY29tIOKApi…' },
  algorithm: 'AES-256/GCM/NoPadding',
  storedAt: '2026-10-06T12:00:00Z',
  fieldCount: 2,
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PiiDemo', () => {
  it('stores, reads, erases, and reads the typed undecryptable failure', async () => {
    let erased = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url === STORE_URL && init?.method === 'POST') {
          return jsonResponse(STORED);
        }
        if (url === READ_URL) {
          return erased
            ? jsonResponse(
                {
                  error: {
                    code: 'not_found',
                    message:
                      'subject "usr_demo_1" has 2 ciphertext field(s) stored, but the key that could read them was destroyed at erasure — undecryptable by design',
                    requestId: 'req-9',
                    erased: true,
                    keyId: 'key_ab12cd34',
                    ciphertextFields: 2,
                  },
                },
                404,
              )
            : jsonResponse({
                subject: 'usr_demo_1',
                keyId: 'key_ab12cd34',
                fields: { name: 'Ada Lovelace', email: 'ada@example.com' },
              });
        }
        if (url === ERASE_URL && init?.method === 'POST') {
          erased = true;
          return jsonResponse({
            subject: 'usr_demo_1',
            keyId: 'key_ab12cd34',
            ciphertextFieldsStillStored: 2,
            dataUnreadableForever: true,
            logEntryId: 'erasure_9f1c2d3e',
            erasedAt: '2026-10-06T12:01:00Z',
          });
        }
        if (url === LOG_URL) {
          return jsonResponse([
            {
              id: 'erasure_9f1c2d3e',
              subjectId: 'usr_demo_1',
              keyId: 'key_ab12cd34',
              action: 'right-to-be-forgotten: per-subject key destroyed; 2 ciphertext field(s) retained',
              at: '2026-10-06T12:01:00Z',
            },
          ]);
        }
        return jsonResponse({ error: { code: 'not_found', message: 'no stub', requestId: 'x' } }, 404);
      }),
    );

    render(<PiiDemo />);

    fireEvent.click(screen.getByTestId('secpii-store'));
    const stored = await screen.findAllByTestId('secpii-stored');
    expect(stored[0]).toHaveTextContent('qL0tX3NlZWRm…');
    expect(await screen.findByText(/key_ab12cd34/)).toBeTruthy();

    fireEvent.click(screen.getByTestId('secpii-read'));
    await waitFor(() =>
      expect(screen.getByTestId('secpii-read-result')).toHaveTextContent('name: Ada Lovelace'),
    );
    expect(screen.getByTestId('secpii-read-result')).toHaveTextContent('email: ada@example.com');

    fireEvent.click(screen.getByTestId('secpii-erase'));
    const receipt = await screen.findByTestId('secpii-erased');
    expect(receipt).toHaveTextContent('key_ab12cd34 destroyed');
    expect(receipt).toHaveTextContent('unreadable forever');

    fireEvent.click(screen.getByTestId('secpii-read'));
    await waitFor(() =>
      expect(screen.getByTestId('secpii-read-result')).toHaveTextContent('undecryptable'),
    );
    expect(screen.getByTestId('secpii-read-result')).toHaveTextContent('destroyed at erasure');

    fireEvent.click(screen.getByTestId('secpii-log'));
    const entries = await screen.findAllByTestId('secpii-log-entry');
    expect(entries[0]).toHaveTextContent('usr_demo_1');
    expect(entries[0]).toHaveTextContent('key destroyed');
  });
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}
