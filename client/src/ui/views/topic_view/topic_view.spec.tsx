import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, createMemoryRouter, RouterProvider } from 'react-router';
import { TopicView } from './topic_view';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch, stubNetworkFailure } from '../../../test/fetch_stub';

/**
 * TopicView spec — the generic renderer's behavior: every block kind in wire
 * order, numbered sections, degradation for unknown ids / network loss, and
 * race-safety when the route param changes mid-flight.
 */

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderTopic(topicId: string) {
  return render(
    <MemoryRouter initialEntries={[`/topics/${topicId}`]}>
      <Routes>
        <Route path="/topics/:topicId" element={<TopicView />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** One topic with two sections; the first carries every block kind. */
const richTopic = {
  version: 1,
  id: 'demo-topic',
  title: 'Demo Topic',
  subtitle: 'A topic assembled for tests',
  group: 'fundamentals',
  order: 1,
  sections: [
    {
      id: 'demo-topic:rich-section',
      title: 'The rich section',
      lead: 'The lead paragraph.',
      blocks: [
        { kind: 'text', id: 'b-text', body: 'The body of the text block.' },
        {
          kind: 'code',
          id: 'b-code',
          language: 'java',
          caption: 'the caption',
          code: 'record Hello(String name) {}',
          source: 'server/src/main/java/guide/Hello.java',
        },
        {
          kind: 'demo',
          id: 'b-demo',
          demoId: 'nope:not-registered',
          title: 'Inspecting the running app',
          description: 'Ask the server about itself.',
        },
      ],
    },
    {
      id: 'demo-topic:terminal',
      title: 'Terminal companion',
      blocks: [{ kind: 'text', id: 'b-text-2', body: 'curl away.' }],
    },
  ],
};

describe('TopicView', () => {
  it('renders loading, then the topic: numbered sections, text, verbatim code, and the demo panel', async () => {
    stubApiFetch([{ url: '/api/content/demo-topic', body: richTopic }]);

    renderTopic('demo-topic');

    expect(screen.getByTestId('topic-loading')).toBeInTheDocument();

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Demo Topic' }),
    ).toBeInTheDocument();
    expect(screen.getByText('A topic assembled for tests')).toBeInTheDocument();

    // Numbered sections with titles + leads (01, 02). The number joins the
    // heading's accessible name, so title matching uses a regex.
    expect(screen.getByText('01')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 2, name: /The rich section/ }),
    ).toBeInTheDocument();
    expect(screen.getByText('The lead paragraph.')).toBeInTheDocument();
    expect(screen.getByText('02')).toBeInTheDocument();

    // Text block renders its body.
    expect(screen.getByText('The body of the text block.')).toBeInTheDocument();

    // Code block starts collapsed (label visible, source hidden), then expands
    // to render the code verbatim with caption, source path, and copy button.
    const codeToggle = screen.getByRole('button', { name: /java/ });
    expect(codeToggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(codeToggle);
    expect(codeToggle).toHaveAttribute('aria-expanded', 'true');
    expect(
      screen.getByText(
        (_, element) =>
          element?.tagName === 'CODE' && element.textContent === 'record Hello(String name) {}',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('the caption')).toBeInTheDocument();
    expect(screen.getByText('source: server/src/main/java/guide/Hello.java')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'copy' })).toBeInTheDocument();

    // Demo block resolves through DemoHost — an unregistered demoId degrades
    // to the friendly not-wired-yet card inside its titled panel.
    expect(
      await screen.findByRole('heading', { level: 3, name: 'Inspecting the running app' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('demo-not-wired')).toHaveTextContent(
      'demoId: nope:not-registered',
    );
  });

  it('surfaces the 404 envelope with the server requestId for an unknown topic id', async () => {
    stubApiFetch([
      {
        url: '/api/content/missing',
        status: 404,
        body: {
          error: {
            code: 'not_found',
            message: 'no topic with id "missing"',
            requestId: 'req-9',
          },
        },
      },
    ]);

    renderTopic('missing');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load this topic');
    expect(alert).toHaveTextContent('no topic with id "missing"');
    expect(alert).toHaveTextContent('not_found · status 404 · request req-9');
  });

  it('shows the error panel when the network is down (status 0)', async () => {
    stubNetworkFailure();

    renderTopic('anything');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not reach the server.');
    expect(alert).toHaveTextContent('network_error · status 0');
  });

  it('is race-safe on route param changes: a slow stale fetch never overwrites the new topic', async () => {
    const topicA = { ...richTopic, id: 'topic-a', title: 'Slow Topic A' };
    const topicB = { ...richTopic, id: 'topic-b', title: 'Fast Topic B' };
    stubApiFetch([
      { url: '/api/content/topic-a', body: topicA, delayMs: 80 },
      { url: '/api/content/topic-b', body: topicB },
    ]);

    const router = createMemoryRouter(
      [{ path: '/topics/:topicId', element: <TopicView /> }],
      { initialEntries: ['/topics/topic-a'] },
    );
    render(<RouterProvider router={router} />);
    expect(screen.getByTestId('topic-loading')).toBeInTheDocument();

    // Navigate to topic B before A's slow response lands.
    await act(async () => {
      await router.navigate('/topics/topic-b');
    });

    // B wins; the loading state reset for the new param.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Fast Topic B' }),
    ).toBeInTheDocument();

    // A's late response is dropped — it never overwrites B's screen.
    await new Promise((resolve) => setTimeout(resolve, 150));
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'Fast Topic B' })).toBeInTheDocument(),
    );
    expect(screen.queryByText('Slow Topic A')).not.toBeInTheDocument();
  });
});
