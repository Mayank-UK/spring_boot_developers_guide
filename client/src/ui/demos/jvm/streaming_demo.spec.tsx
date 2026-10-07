import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import StreamingDemo from './streaming_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * StreamingDemo spec — topic 02's jvm:streaming demo over the shared guarded
 * pipeline: the four-consumer table renders (Reader clean, naive corrupted,
 * byte consumers exact), the charset pill re-fetches, and the 400 envelope
 * message surfaces for an unsupported charset.
 */

const UTF8_16 = '/api/demo/jvm/streaming?chunks=16&charset=utf-8';
const UTF8_32 = '/api/demo/jvm/streaming?chunks=32&charset=utf-8';
const UTF16_16 = '/api/demo/jvm/streaming?chunks=16&charset=utf-16';

const report = (overrides: Partial<Record<string, unknown>> = {}) => ({
  chunks: 16,
  charset: 'utf-8',
  bufferSize: 8,
  payloadBytes: 144,
  expectedCharCount: 112,
  streamScan: { totalBytes: 144, readCalls: 18 },
  readerScan: { totalChars: 112, multibyteChars: 16, replacementChars: 0 },
  naiveScan: { decodedSample: 'c00000��c00001→c0000', replacementChars: 4 },
  channelScan: { totalBytes: 144, readCalls: 18 },
  readerClean: true,
  naiveCorrupted: true,
  ...overrides,
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('StreamingDemo', () => {
  it('renders the four consumers with the split-character verdict', async () => {
    stubApiFetch([{ url: UTF8_16, body: report() }]);

    render(<StreamingDemo />);

    const table = await screen.findByTestId('jvm-streaming-report');
    expect(table).toHaveTextContent('144 bytes');
    expect(table).toHaveTextContent('112 chars, 16 × \'→\'');
    expect(screen.getByTestId('jvm-streaming-reader-replacements')).toHaveTextContent('0');
    expect(screen.getByTestId('jvm-streaming-naive-sample').textContent).toContain('c00000');
    expect(table).toHaveTextContent('4 replacement chars');

    const verdict = screen.getByTestId('jvm-streaming-verdict');
    expect(verdict).toHaveTextContent('0 replacement characters');
    expect(verdict).toHaveTextContent('4');
    expect(verdict).toHaveTextContent('Decode once, on a Reader');
  });

  it('re-fetches when the chunk pill changes', async () => {
    stubApiFetch([
      { url: UTF8_16, body: report() },
      {
        url: UTF8_32,
        body: report({
          chunks: 32,
          payloadBytes: 288,
          expectedCharCount: 224,
          streamScan: { totalBytes: 288, readCalls: 36 },
          readerScan: { totalChars: 224, multibyteChars: 32, replacementChars: 0 },
          channelScan: { totalBytes: 288, readCalls: 36 },
          naiveScan: { decodedSample: 'c00000��c00001→c0000', replacementChars: 4 },
        }),
      },
    ]);

    render(<StreamingDemo />);
    await screen.findByTestId('jvm-streaming-report');

    fireEvent.click(screen.getByTestId('jvm-streaming-chunks-32'));

    await waitFor(() => expect(screen.getByTestId('jvm-streaming-report')).toHaveTextContent('288 bytes'));
    expect(screen.getByTestId('jvm-streaming-verdict')).toHaveTextContent('32 chunks');
  });

  it('re-fetches when the charset pill switches to utf-16', async () => {
    stubApiFetch([
      { url: UTF8_16, body: report() },
      {
        url: UTF16_16,
        body: report({
          charset: 'utf-16',
          bufferSize: 15,
          payloadBytes: 226,
          streamScan: { totalBytes: 226, readCalls: 16 },
          readerScan: { totalChars: 112, multibyteChars: 16, replacementChars: 0 },
          channelScan: { totalBytes: 226, readCalls: 16 },
          naiveScan: { decodedSample: 'c00000��c00001→c0000', replacementChars: 3 },
        }),
      },
    ]);

    render(<StreamingDemo />);
    await screen.findByTestId('jvm-streaming-report');

    fireEvent.click(screen.getByTestId('jvm-streaming-charset-utf-16'));

    await waitFor(() => expect(screen.getByTestId('jvm-streaming-report')).toHaveTextContent('226 bytes'));
    expect(screen.getByTestId('jvm-streaming-report')).toHaveTextContent('buffer 15 B');
  });

  it('shows the 400 envelope message for an unsupported charset', async () => {
    stubApiFetch([
      {
        url: UTF8_16,
        status: 400,
        body: {
          error: {
            code: 'validation_failed',
            message: 'charset must be one of: utf-8, utf-16',
            requestId: 'req-31',
          },
        },
      },
    ]);

    render(<StreamingDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the streaming report');
    expect(alert).toHaveTextContent('charset must be one of: utf-8, utf-16');
    expect(alert).toHaveTextContent('validation_failed · status 400 · request req-31');
  });
});
