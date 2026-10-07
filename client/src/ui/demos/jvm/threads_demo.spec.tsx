import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ThreadsDemo from './threads_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ThreadsDemo spec — topic 02's jvm:threads demo over the shared guarded
 * pipeline: the head-to-head comparison renders both models (bounded pool
 * vs one-thread-per-task, carrier visible), the task pills re-fetch, the
 * solo buttons run one model alongside the comparison, and the 400 envelope
 * message surfaces for a bad parameter.
 */

const COMPARE_24 = '/api/demo/jvm/threads/compare?tasks=24';
const COMPARE_64 = '/api/demo/jvm/threads/compare?tasks=64';
const SOLO_VIRTUAL_64 = '/api/demo/jvm/threads?tasks=64&mode=virtual';

const platformRun = (tasks: number, wall: number) => ({
  mode: 'platform',
  tasks,
  wallTimeMillis: wall,
  distinctThreadNames: 4,
  sampleThreadNames: [
    'Thread[pool-1-thread-1,5,main]',
    'Thread[pool-1-thread-2,5,main]',
    'Thread[pool-1-thread-3,5,main]',
  ],
  virtualThreadsObserved: false,
  carrierObserved: false,
  carrierSample: null,
});

const virtualRun = (tasks: number, wall: number) => ({
  mode: 'virtual',
  tasks,
  wallTimeMillis: wall,
  distinctThreadNames: tasks,
  sampleThreadNames: ['VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1'],
  virtualThreadsObserved: true,
  carrierObserved: true,
  carrierSample: 'VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1',
});

const comparePayload = (tasks: number, platformWall: number, virtualWall: number) => ({
  tasks,
  platform: platformRun(tasks, platformWall),
  virtual: virtualRun(tasks, virtualWall),
  summary:
    `${tasks} blocking tasks: the pool of 4 OS threads ran them in ~${Math.ceil(tasks / 4)} waves on 4 threads; ` +
    `the virtual executor ran all ${tasks} at once, each parked while it slept.`,
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ThreadsDemo', () => {
  it('renders both models side by side with the carrier evidence', async () => {
    stubApiFetch([{ url: COMPARE_24, body: comparePayload(24, 63, 12) }]);

    render(<ThreadsDemo />);

    const platform = await screen.findByTestId('jvm-threads-compare-platform');
    expect(platform).toHaveTextContent('63 ms wall');
    expect(screen.getByTestId('jvm-threads-compare-platform-distinct')).toHaveTextContent('4');
    expect(platform).toHaveTextContent('Thread[pool-1-thread-1,5,main]');
    expect(platform).toHaveTextContent('1:1 OS threads');

    const virtual = screen.getByTestId('jvm-threads-compare-virtual');
    expect(virtual).toHaveTextContent('12 ms wall');
    expect(screen.getByTestId('jvm-threads-compare-virtual-distinct')).toHaveTextContent('24');
    expect(screen.getByTestId('jvm-threads-compare-virtual-carrier')).toHaveTextContent(
      'VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1',
    );

    const summary = screen.getByTestId('jvm-threads-summary');
    expect(summary).toHaveTextContent('~6 waves');
    expect(summary).toHaveTextContent('24 at once');
  });

  it('re-fetches the comparison when the task pill changes', async () => {
    stubApiFetch([
      { url: COMPARE_24, body: comparePayload(24, 63, 12) },
      { url: COMPARE_64, body: comparePayload(64, 171, 13) },
    ]);

    render(<ThreadsDemo />);
    await screen.findByTestId('jvm-threads-compare-platform');

    fireEvent.click(screen.getByTestId('jvm-threads-tasks-64'));

    await waitFor(() =>
      expect(screen.getByTestId('jvm-threads-compare-virtual-distinct')).toHaveTextContent('64'),
    );
    expect(screen.getByTestId('jvm-threads-compare-platform')).toHaveTextContent('171 ms wall');
    expect(screen.getByTestId('jvm-threads-summary')).toHaveTextContent('~16 waves');
  });

  it('runs one model alone from the solo buttons', async () => {
    stubApiFetch([
      { url: COMPARE_24, body: comparePayload(24, 63, 12) },
      { url: COMPARE_64, body: comparePayload(64, 171, 13) },
      { url: SOLO_VIRTUAL_64, body: virtualRun(64, 14) },
    ]);

    render(<ThreadsDemo />);
    await screen.findByTestId('jvm-threads-compare-platform');

    fireEvent.click(screen.getByTestId('jvm-threads-tasks-64'));
    await waitFor(() =>
      expect(screen.getByTestId('jvm-threads-compare-virtual-distinct')).toHaveTextContent('64'),
    );

    fireEvent.click(screen.getByTestId('jvm-threads-run-virtual'));

    const solo = await screen.findByTestId('jvm-threads-solo-virtual');
    expect(solo).toHaveTextContent('14 ms wall');
    expect(screen.getByTestId('jvm-threads-solo-virtual-distinct')).toHaveTextContent('64');
    // the comparison cards stay rendered beside the solo card
    expect(screen.getByTestId('jvm-threads-compare-platform')).toBeInTheDocument();
  });

  it('shows the 400 envelope message for an invalid parameter', async () => {
    stubApiFetch([
      {
        url: COMPARE_24,
        status: 400,
        body: {
          error: {
            code: 'validation_failed',
            message: 'tasks must be an integer between 1 and 100',
            requestId: 'req-21',
          },
        },
      },
    ]);

    render(<ThreadsDemo />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the thread comparison');
    expect(alert).toHaveTextContent('tasks must be an integer between 1 and 100');
    expect(alert).toHaveTextContent('validation_failed · status 400 · request req-21');
  });
});
