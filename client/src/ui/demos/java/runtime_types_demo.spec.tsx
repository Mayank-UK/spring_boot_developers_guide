import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import RuntimeTypesDemo from './runtime_types_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * RuntimeTypesDemo spec — the java-language demo over the shared guarded
 * pipeline: the reflection report renders (including the not-a-record empty
 * state and sealed permits), the picker re-fetches, and both panels surface
 * the server's error envelope message on failure.
 */

const REFLECTION_PATH = '/api/demo/java/reflection?class=result';
const TEXT_BLOCK_PATH = '/api/demo/java/reflection?class=text-block';
const ERASURE_PATH = '/api/demo/java/erasure';

const resultPayload = {
  requestedName: 'result',
  className: 'guide.core.result.Result',
  kind: 'sealed interface',
  recordComponents: [],
  sealed: true,
  permittedSubclasses: ['guide.core.result.Success', 'guide.core.result.Failed'],
  annotations: [],
};

const textBlockPayload = {
  requestedName: 'text-block',
  className: 'guide.content.TextBlock',
  kind: 'record',
  recordComponents: [
    { name: 'id', type: 'java.lang.String' },
    { name: 'body', type: 'java.lang.String' },
  ],
  sealed: false,
  permittedSubclasses: [],
  annotations: ['com.fasterxml.jackson.annotation.JsonInclude'],
};

const erasurePayload = {
  declaredFields: [
    { name: 'tags', genericType: 'java.util.List<java.lang.String>', rawType: 'java.util.List' },
    {
      name: 'counts',
      genericType: 'java.util.Map<java.lang.String, java.lang.Integer>',
      rawType: 'java.util.Map',
    },
  ],
  instances: [
    {
      declaredAs: 'Box<String>',
      rawClass: 'guide.domain.java.RuntimeTypesExhibit.Box',
      items: ['guide', 'boot', 'jvm'],
    },
    {
      declaredAs: 'Box<Integer>',
      rawClass: 'guide.domain.java.RuntimeTypesExhibit.Box',
      items: ['1', '2', '3'],
    },
  ],
  shareOneRawClass: true,
};

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('RuntimeTypesDemo', () => {
  it('renders the sealed-interface reflection report and the erasure evidence', async () => {
    stubApiFetch([
      { url: REFLECTION_PATH, body: resultPayload },
      { url: ERASURE_PATH, body: erasurePayload },
    ]);

    render(<RuntimeTypesDemo />);

    // Reflection: Result reports as a sealed interface with its two permits —
    // and the not-a-record empty state for its components.
    const report = await screen.findByTestId('java-reflection-report');
    expect(report).toHaveTextContent('guide.core.result.Result');
    expect(report).toHaveTextContent('sealed interface');
    expect(screen.getByTestId('java-reflection-sealed')).toHaveTextContent('2 permits');
    expect(report).toHaveTextContent('guide.core.result.Success');
    expect(report).toHaveTextContent('guide.core.result.Failed');
    expect(screen.getByTestId('java-reflection-empty')).toHaveTextContent(
      'not a record',
    );

    // Erasure: declared generics kept, instances share one raw Class.
    const erasure = await screen.findByTestId('java-erasure-report');
    expect(erasure).toHaveTextContent('java.util.List<java.lang.String>');
    expect(erasure).toHaveTextContent('java.util.List');
    expect(erasure).toHaveTextContent('Box<String>');
    expect(erasure).toHaveTextContent('Box<Integer>');
    expect(screen.getByTestId('java-erasure-verdict')).toHaveTextContent(
      'one raw Class for both instances',
    );
  });

  it('switches the inspected class from the picker and renders record components', async () => {
    stubApiFetch([
      { url: REFLECTION_PATH, body: resultPayload },
      { url: TEXT_BLOCK_PATH, body: textBlockPayload },
      { url: ERASURE_PATH, body: erasurePayload },
    ]);

    render(<RuntimeTypesDemo />);
    await screen.findByTestId('java-reflection-report');

    fireEvent.click(screen.getByTestId('java-class-text-block'));

    await waitFor(() =>
      expect(screen.getByTestId('java-reflection-report')).toHaveTextContent(
        'guide.content.TextBlock',
      ),
    );
    const report = screen.getByTestId('java-reflection-report');
    expect(report).toHaveTextContent('record');
    expect(report).toHaveTextContent('id');
    expect(report).toHaveTextContent('body');
    expect(report).toHaveTextContent('com.fasterxml.jackson.annotation.JsonInclude');
    expect(screen.queryByTestId('java-reflection-sealed')).not.toBeInTheDocument();
  });

  it('shows the 400 envelope message for an unknown class', async () => {
    stubApiFetch([
      {
        url: REFLECTION_PATH,
        status: 400,
        body: {
          error: {
            code: 'validation_failed',
            message:
              'unknown class "string-builder" — inspectable classes are: result, text-block, topic',
            requestId: 'req-7',
          },
        },
      },
      { url: ERASURE_PATH, body: erasurePayload },
    ]);

    render(<RuntimeTypesDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the reflection report');
    expect(alert).toHaveTextContent('unknown class "string-builder"');
    expect(alert).toHaveTextContent('validation_failed · status 400 · request req-7');
  });

  it('shows the error panel when the erasure endpoint fails', async () => {
    stubApiFetch([
      { url: REFLECTION_PATH, body: resultPayload },
      {
        url: ERASURE_PATH,
        status: 500,
        body: {
          error: { code: 'internal_error', message: 'boom', requestId: 'req-8' },
        },
      },
    ]);

    render(<RuntimeTypesDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the erasure evidence');
    expect(alert).toHaveTextContent('boom');
    expect(screen.queryByTestId('java-erasure-report')).not.toBeInTheDocument();
  });
});
