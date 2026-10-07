import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import NegotiatorDemo from './negotiator_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * NegotiatorDemo spec — the i18n negotiator over stubbed endpoints: the
 * builder composes the header from rows (q-suffixes only when they
 * differ from 1), negotiation renders candidates, skipped junk, the
 * winner, and the resolver's agreeing choice; the hostile preset still
 * answers (junk skipped, never an error); the greeting sends the built
 * header as Accept-Language and renders the localized message with its
 * fallback chain; the format matrix renders per-locale rows (Japanese
 * compact 万, currency codes) and the two-zone contrast; and a degraded
 * dependency (typed 503 envelope) renders the error panel.
 */

interface Captured {
  url: string;
  method: string;
  headers?: Record<string, string>;
}

function negotiateBody() {
  return {
    header: 'de-DE,fr;q=0.8',
    fromRequest: false,
    candidates: [
      { tag: 'de-DE', language: 'de', region: 'DE', quality: 1, wildcard: false, rank: 1 },
      { tag: 'fr', language: 'fr', region: '', quality: 0.8, wildcard: false, rank: 2 },
    ],
    skipped: ['segment 2 "zz-ZZ;q=OOPS": unrecognized parameter (only q= is defined)'],
    supported: ['en', 'de', 'fr', 'ja'],
    scores: [
      { supported: 'en', score: 0, matched: null, kind: 'NONE' },
      { supported: 'de', score: 1, matched: 'de-DE', kind: 'LANGUAGE' },
      { supported: 'fr', score: 0.8, matched: 'fr', kind: 'EXACT' },
      { supported: 'ja', score: 0, matched: null, kind: 'NONE' },
    ],
    winner: 'de',
    kind: 'LANGUAGE',
    reason: 'language match: candidate de-DE (q=1.0) serves de',
    resolverChose: 'de',
    resolverAgrees: true,
  };
}

function greetingBody() {
  return {
    useCase: 'fallback',
    locale: 'de',
    localeDisplay: 'Deutsch',
    key: 'demo.i18n.fallback.shared',
    message: 'This sentence lives only in the base bundle. Every locale falls through to it.',
    answeringBundle: 'messages.properties',
    template: 'This sentence lives only in the base bundle. Every locale falls through to it.',
    chain: [
      { bundle: 'messages_de.properties', hit: false, template: null },
      { bundle: 'messages.properties', hit: true, template: 'This sentence lives only in the base bundle. Every locale falls through to it.' },
    ],
    note: 'The key exists ONLY in the base bundle.',
  };
}

function formatBody() {
  return {
    instant: '2026-10-06T12:34:56Z',
    number: 1234567.891,
    locales: [
      { tag: 'en', display: 'English', date: 'October 6, 2026', time: '2:34:56 PM', dateTime: 'Oct 6, 2026, 2:34:56 PM', currencyLocaleTag: 'en-US', currency: '$1,234,567.89', currencyCode: 'USD', decimal: '1,234,567.891', percent: '89%', compact: '1.2M' },
      { tag: 'ja', display: '日本語', date: '2026年10月6日', time: '14:34:56', dateTime: '2026/10/06 14:34:56', currencyLocaleTag: 'ja-JP', currency: '￥1,234,568', currencyCode: 'JPY', decimal: '1,234,567.891', percent: '89%', compact: '123.5万' },
    ],
    zones: [
      { zoneId: 'Europe/Berlin', offset: '+02:00', dateTime: '06.10.2026, 14:34:56' },
      { zoneId: 'Asia/Tokyo', offset: '+09:00', dateTime: '06.10.2026, 21:34:56' },
    ],
    note: 'currency is an account setting, never a locale inference',
  };
}

function stubEndpoints(captured: Captured[], ok: boolean) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({
        url,
        method: init?.method ?? 'GET',
        headers:
          init?.headers && typeof init.headers === 'object' && !Array.isArray(init.headers)
            ? (init.headers as Record<string, string>)
            : undefined,
      });
      if (!ok) {
        return {
          ok: false,
          status: 503,
          headers: { get: () => null },
          json: async () => ({
            error: { code: 'service_unavailable', message: 'i18n demos are down', requestId: 'stub' },
          }),
        };
      }
      let body: unknown;
      if (url.includes('/negotiate')) body = negotiateBody();
      else if (url.includes('/greeting')) body = greetingBody();
      else body = formatBody();
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => body,
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

describe('NegotiatorDemo', () => {
  it('renders the builder and the idle prompt before any negotiation', () => {
    stubEndpoints([], true);
    render(<NegotiatorDemo />);
    expect(screen.getByTestId('i18n-idle')).toHaveTextContent('build a header');
    expect(screen.getByTestId('i18n-header-rows').children.length).toBe(3);
    expect(screen.queryByTestId('i18n-negotiate-result')).toBeNull();
  });

  it('composes the header from rows, q-suffixing only non-1 weights', () => {
    stubEndpoints([], true);
    render(<NegotiatorDemo />);
    expect(screen.getByTestId('i18n-header-value')).toHaveTextContent(
      'Accept-Language: de-DE, fr;q=0.8, en',
    );

    fireEvent.change(screen.getByTestId('i18n-row-1-q'), { target: { value: '0.9' } });
    expect(screen.getByTestId('i18n-header-value')).toHaveTextContent('de-DE;q=0.9');
  });

  it('negotiates and renders candidates, skipped junk, the winner and the resolver', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);
    render(<NegotiatorDemo />);

    fireEvent.click(screen.getByTestId('i18n-negotiate-run'));
    const result = await screen.findByTestId('i18n-negotiate-result');
    expect(screen.getByTestId('i18n-candidates')).toHaveTextContent('#1 de-DE q=1');
    expect(screen.getByTestId('i18n-candidates')).toHaveTextContent('#2 fr q=0.8');
    expect(screen.getByTestId('i18n-skipped')).toHaveTextContent('zz-ZZ;q=OOPS');
    expect(screen.getByTestId('i18n-scores')).toHaveTextContent('de: 1 via de-DE (language)');
    expect(screen.getByTestId('i18n-scores')).toHaveTextContent('en: —');
    expect(screen.getByTestId('i18n-winner')).toHaveTextContent('winner: de');
    expect(screen.getByTestId('i18n-resolver')).toHaveTextContent('resolver chose: de (agrees)');
    expect(result).toHaveTextContent('language match: candidate de-DE');

    const negotiateCall = captured.find((call) => call.url.includes('/negotiate'));
    expect(negotiateCall?.url).toBe(
      '/api/demo/i18n/negotiate?header=de-DE%2C%20fr%3Bq%3D0.8%2C%20en%3Bq%3D0.5',
    );
  });

  it('the hostile preset swaps the rows in (junk rows included, on purpose)', () => {
    stubEndpoints([], true);
    render(<NegotiatorDemo />);
    fireEvent.change(screen.getByTestId('i18n-preset'), { target: { value: 'hostile' } });
    expect(screen.getByTestId('i18n-header-value')).toHaveTextContent('EN-gb');
    expect(screen.getByTestId('i18n-header-value')).toHaveTextContent('zz-ZZ;q=OOPS');
    expect(screen.getByTestId('i18n-header-value')).toHaveTextContent('fr;q=1.5');
  });

  it('greets in the negotiated locale, header riding the request, chain rendered', async () => {
    const captured: Captured[] = [];
    stubEndpoints(captured, true);
    render(<NegotiatorDemo />);

    fireEvent.change(screen.getByTestId('i18n-usecase-select'), { target: { value: 'fallback' } });
    fireEvent.click(screen.getByTestId('i18n-greeting-run'));
    const result = await screen.findByTestId('i18n-greeting-result');
    expect(screen.getByTestId('i18n-greeting-message')).toHaveTextContent(
      'This sentence lives only in the base bundle',
    );
    expect(screen.getByTestId('i18n-chain')).toHaveTextContent(
      'messages_de.properties miss → messages.properties HIT',
    );
    expect(result).toHaveTextContent('locale de (Deutsch)');

    const greetingCall = captured.find((call) => call.url.includes('/greeting'));
    expect(greetingCall?.headers).toEqual({ 'Accept-Language': 'de-DE, fr;q=0.8, en;q=0.5' });
    expect(greetingCall?.url).toBe('/api/demo/i18n/greeting?useCase=fallback&name=Mak');
  });

  it('renders the format matrix with per-locale rows and the zone contrast', async () => {
    stubEndpoints([], true);
    render(<NegotiatorDemo />);

    fireEvent.click(screen.getByTestId('i18n-format-run'));
    const table = await screen.findByTestId('i18n-format-table');
    expect(table).toHaveTextContent('October 6, 2026');
    expect(table).toHaveTextContent('$1,234,567.89');
    expect(table).toHaveTextContent('USD');
    expect(table).toHaveTextContent('￥1,234,568');
    expect(table).toHaveTextContent('123.5万');
    const zones = await screen.findByTestId('i18n-zones');
    expect(zones).toHaveTextContent('Europe/Berlin (+02:00): 06.10.2026, 14:34:56');
    expect(zones).toHaveTextContent('Asia/Tokyo (+09:00): 06.10.2026, 21:34:56');
  });

  it('renders the degraded panel when the dependency is down', async () => {
    stubEndpoints([], false);
    render(<NegotiatorDemo />);

    fireEvent.click(screen.getByTestId('i18n-negotiate-run'));

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('i18n demos are down');
    expect(panel).toHaveTextContent('service_unavailable');
  });
});
