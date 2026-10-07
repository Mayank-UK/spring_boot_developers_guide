import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import EnvInspectorDemo from './env_inspector_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * EnvInspectorDemo spec — the environment story over stubbed endpoints:
 * the default inspection paints profiles "default", the four property lines
 * (safe trio echoed, the secret REDACTED), the dev-default verdict, and the
 * two runtime flags; "flip data-mode" PATCHes /demo/scenario with the next
 * mode and re-inspects — the flag line moves while every guide.env.* line
 * stays frozen; and a degraded server renders the error panel. The verdict
 * and reading lines must come from the SERVER's answer — the component
 * narrates nothing the inspection did not say.
 */

interface Captured {
  url: string;
  method: string;
  body?: string;
}

const DEFAULT_INSPECTION = {
  activeProfiles: ['default'],
  properties: [
    { key: 'guide.env.deploy-environment', value: 'local', classification: 'safe' },
    { key: 'guide.env.region', value: 'local', classification: 'safe' },
    { key: 'guide.env.maintenance-mode', value: 'false', classification: 'safe' },
    { key: 'guide.env.demo-api-key', value: '<redacted>', classification: 'secret' },
  ],
  demoSecretIsDevDefault: true,
  flags: [
    {
      name: 'scenario.data-mode',
      state: 'fake',
      runtimeSwitchable: true,
      note: 'GET/PATCH /api/demo/scenario — flips without a restart',
    },
    {
      name: 'scenario.inject-failure',
      state: 'false',
      runtimeSwitchable: true,
      note: 'GET/PATCH /api/demo/scenario — the failure-injection switch',
    },
  ],
  reading: [
    "profiles: default — 'default' means NO profile is active; it is not a name @Profile matches",
    'the safe values above were typed and validated at boot: AppConfig\u2019s constructor normalized guide.env.* — a bad value would have failed startup, not the first request',
    'guide.env.demo-api-key is classified secret — its value has no path into this answer; the inspector reports only whether it is the known DEV DEFAULT: yes (demo material — production sets the env var)',
    'the flags are RUNTIME-switchable — config is not; data-mode is currently fake and PATCH /api/demo/scenario flips it live',
  ],
};

function inspectionWithMode(state: string): typeof DEFAULT_INSPECTION {
  return {
    ...DEFAULT_INSPECTION,
    flags: [
      { ...DEFAULT_INSPECTION.flags[0], state },
      DEFAULT_INSPECTION.flags[1],
    ],
    reading: DEFAULT_INSPECTION.reading.map((line, index) =>
      index === 3
        ? `the flags are RUNTIME-switchable — config is not; data-mode is currently ${state} and PATCH /api/demo/scenario flips it live`
        : line,
    ),
  };
}

type Mode = 'fake' | 'live' | 'down';

/** One stub for a whole test: the scenario PATCH flips `mode`, the GET answers it. */
function stubEndpoints(captured: Captured[], mode: { current: Mode }): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      captured.push({ url, method, body: typeof init?.body === 'string' ? init.body : undefined });
      const respond = (status: number, body: unknown) => ({
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
      });
      if (mode.current === 'down') {
        return respond(503, {
          error: { code: 'service_unavailable', message: 'inspector unreachable', requestId: 'x' },
        });
      }
      if (url.includes('/demo/scenario')) {
        mode.current = (JSON.parse(String(init?.body)).dataMode === 'live' ? 'live' : 'fake') as Mode;
        return respond(200, { dataMode: mode.current, injectFailure: false });
      }
      return respond(200, inspectionWithMode(mode.current));
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('EnvInspectorDemo', () => {
  it('paints the default inspection: profiles, safe trio, redacted secret, flags', async () => {
    stubEndpoints([], { current: 'fake' });

    render(<EnvInspectorDemo />);

    const panel = await screen.findByTestId('env-inspection');
    expect(panel).toHaveTextContent('default');
    expect(panel).toHaveTextContent('guide.env.deploy-environment');
    expect(panel).toHaveTextContent('local');
    expect(panel).toHaveTextContent('guide.env.demo-api-key');
    expect(panel).toHaveTextContent('<redacted>');
    expect(panel).toHaveTextContent('(secret)');
    expect(screen.getByTestId('env-secret-verdict')).toHaveTextContent('DEV DEFAULT');
    expect(panel).toHaveTextContent('scenario.data-mode');
    expect(panel).toHaveTextContent('fake');
    expect(panel).toHaveTextContent('(runtime-switchable)');
  });

  it('flips the runtime flag by PATCHing the scenario, then re-inspects', async () => {
    const captured: Captured[] = [];
    const mode = { current: 'fake' as Mode };
    stubEndpoints(captured, mode);

    render(<EnvInspectorDemo />);
    await screen.findByTestId('env-inspection');
    expect(screen.getAllByTestId('env-flag')[0]).toHaveTextContent('fake');

    fireEvent.click(screen.getByTestId('env-flip-mode'));

    await waitFor(() => {
      expect(screen.getAllByTestId('env-flag')[0]).toHaveTextContent('live');
    });
    // boot-shaped config untouched by the runtime flip
    expect(screen.getAllByTestId('env-property')[0]).toHaveTextContent('local');
    expect(screen.getAllByTestId('env-property')[3]).toHaveTextContent('<redacted>');

    const patch = captured.find((call) => call.method === 'PATCH');
    expect(patch?.url).toContain('/demo/scenario');
    expect(patch?.body).toContain('"dataMode":"live"');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoints([], { current: 'down' });

    render(<EnvInspectorDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('inspector unreachable');
  });
});
