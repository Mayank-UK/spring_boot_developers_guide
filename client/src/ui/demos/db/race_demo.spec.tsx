import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import RaceDemo from './race_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * RaceDemo spec — the oversell race against a stubbed endpoint: the three
 * strategy buttons and both knobs render; a naive run shows the oversold
 * badge with reserved telling the truth (4 claims, stock still 0); an
 * atomic run shows the held badge with exactly one claim; the story reads;
 * validation failures render the error panel.
 */

interface CapturedCall {
  path: string;
  body: string;
}

const naiveOutcome = {
  strategy: 'naive',
  concurrency: 4,
  initialStock: 1,
  attempted: 4,
  succeeded: 4,
  declined: 0,
  oversold: true,
  finalStock: 0,
  finalReserved: 4,
  story: 'all 4 racers read stock=1 … the shelf lies because every writer overwrote a number.',
};

const atomicOutcome = {
  strategy: 'atomic',
  concurrency: 4,
  initialStock: 1,
  attempted: 4,
  succeeded: 1,
  declined: 3,
  oversold: false,
  finalStock: 0,
  finalReserved: 1,
  story: 'one statement per racer: the stock >= 1 guard and the decrement are indivisible.',
};

function stubRace(captured: CapturedCall[], strategy: string, ok = true) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = typeof input === 'string' ? input : input.toString();
      const body = typeof init?.body === 'string' ? init.body : '';
      captured.push({ path, body });
      return {
        ok,
        status: ok ? 200 : 400,
        json: async () =>
          ok
            ? strategy === 'naive'
              ? naiveOutcome
              : atomicOutcome
            : {
                error: {
                  code: 'validation_failed',
                  message: 'concurrency must be between 2 and 8',
                  requestId: 'x',
                },
              },
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

describe('RaceDemo', () => {
  it('renders the three strategies and both knobs', () => {
    stubRace([], 'naive');
    render(<RaceDemo />);
    expect(screen.getByTestId('race-run-naive')).toBeInTheDocument();
    expect(screen.getByTestId('race-run-guarded')).toBeInTheDocument();
    expect(screen.getByTestId('race-run-atomic')).toBeInTheDocument();
    expect(screen.getByTestId('race-concurrency')).toHaveValue(4);
    expect(screen.getByTestId('race-stock')).toHaveValue(1);
  });

  it('shows the oversold badge when naive loses the race', async () => {
    const captured: CapturedCall[] = [];
    stubRace(captured, 'naive');
    render(<RaceDemo />);

    fireEvent.click(screen.getByTestId('race-run-naive'));
    expect(await screen.findByTestId('race-oversold')).toHaveTextContent('oversold');
    expect(screen.getByTestId('race-succeeded')).toHaveTextContent('4');
    expect(screen.getByTestId('race-final-reserved')).toHaveTextContent('4');
    expect(screen.getByTestId('race-final-stock')).toHaveTextContent('0');
    expect(screen.getByTestId('race-story')).toHaveTextContent('shelf lies');
    expect(captured[0]?.body).toContain('"strategy":"naive"');
  });

  it('shows the held badge when atomic guards the decrement', async () => {
    stubRace([], 'atomic');
    render(<RaceDemo />);

    fireEvent.click(screen.getByTestId('race-run-atomic'));
    expect(await screen.findByTestId('race-oversold')).toHaveTextContent('held');
    expect(screen.getByTestId('race-succeeded')).toHaveTextContent('1');
    expect(screen.getByTestId('race-declined')).toHaveTextContent('3');
  });

  it('renders the error panel when the run is rejected', async () => {
    stubRace([], 'naive', false);
    render(<RaceDemo />);

    fireEvent.click(screen.getByTestId('race-run-naive'));
    await waitFor(() => {
      expect(document.body.textContent).toContain('concurrency must be between 2 and 8');
    });
    expect(screen.queryByTestId('race-oversold')).not.toBeInTheDocument();
  });
});
