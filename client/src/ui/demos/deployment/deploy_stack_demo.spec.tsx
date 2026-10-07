import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import DeployStackDemo from './deploy_stack_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * DeployStackDemo spec — the deployment story over a stubbed endpoint:
 * the report paints what ships (container image/base/launcher/port), the
 * six-service compose mirror with images and published ports, the env rails
 * (parity values identical dev vs prod, deliberate differences named), the
 * probe pair, and the graceful-shutdown posture read from the answer;
 * "re-read the stack" issues a second GET; and a degraded server renders
 * the error panel. Every asserted string must come from the SERVER's
 * answer — the component narrates nothing the inspection did not say.
 */

interface Captured {
  url: string;
  method: string;
}

/** Mirrors the real /api/demo/deploy/stack answer (ComposeStack + posture). */
const STACK = {
  container: {
    image: 'sbdg_server_prod',
    base: 'eclipse-temurin:25-jre-noble',
    entrypoint: 'org.springframework.boot.loader.launch.JarLauncher',
    port: 8080,
  },
  composeServices: [
    { name: 'dev', image: 'sbdg_dev_toolchain', ports: [], role: 'the devcontainer tooling container' },
    { name: 'server', image: 'maven:3.9-eclipse-temurin-25', ports: ['8080:8080'], role: 'Spring Boot dev server' },
    { name: 'client', image: 'node:22-bookworm', ports: ['5176:5176'], role: 'Vite dev server' },
    { name: 'postgres', image: 'postgres:17', ports: ['5432:5432'], role: 'the guide\u2019s only SQL engine' },
    { name: 'redis', image: 'redis:7-alpine', ports: ['6379:6379'], role: 'optional service' },
    { name: 'mongo', image: 'mongo:8', ports: ['27017:27017'], role: 'optional service' },
  ],
  envRails: [
    {
      name: 'SPRING_PROFILES_ACTIVE',
      devValue: 'dev',
      prodValue: '(unset → default)',
      note: 'dev boots the dev profile; prod runs the default profile',
    },
    {
      name: 'SPRING_DATASOURCE_URL',
      devValue: 'jdbc:postgresql://postgres:5432/guide',
      prodValue: 'jdbc:postgresql://postgres:5432/guide',
      note: 'parity: identical in both stacks',
    },
    {
      name: 'SPRING_DATA_REDIS_HOST',
      devValue: 'redis',
      prodValue: 'redis',
      note: 'parity: identical',
    },
  ],
  probes: {
    livenessPath: '/actuator/health/liveness',
    readinessPath: '/actuator/health/readiness',
    port: 8080,
  },
  shutdown: {
    mode: 'graceful',
    timeout: '30s',
    contract: 'SIGTERM → stop accepting new connections → finish in-flight requests',
  },
  reading: [
    'one build, many environments: the env rails above are the WHOLE difference between dev and prod compose',
  ],
};

type Mode = 'up' | 'down';

/** One stub for a whole test: the GET answers the stack unless the server is "down". */
function stubEndpoint(captured: Captured[], mode: { current: Mode }): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      captured.push({ url, method: init?.method ?? 'GET' });
      const respond = (status: number, body: unknown) => ({
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
      });
      if (mode.current === 'down') {
        return respond(503, {
          error: { code: 'service_unavailable', message: 'stack unreachable', requestId: 'x' },
        });
      }
      return respond(200, STACK);
    }),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DeployStackDemo', () => {
  it('paints the deployment story: container, six services, probes, shutdown posture', async () => {
    stubEndpoint([], { current: 'up' });

    render(<DeployStackDemo />);

    // what ships — the prod container the layered-jar image runs
    const containerLine = await screen.findByTestId('deploy-image');
    expect(containerLine).toHaveTextContent('sbdg_server_prod');
    expect(containerLine).toHaveTextContent('eclipse-temurin:25-jre-noble');
    expect(screen.getByTestId('deploy-container')).toHaveTextContent(
      'org.springframework.boot.loader.launch.JarLauncher',
    );
    expect(screen.getByTestId('deploy-container')).toHaveTextContent('8080');

    // the compose mirror: all six services, images and published ports
    const services = screen.getAllByTestId('deploy-service');
    expect(services).toHaveLength(6);
    expect(services[0]).toHaveTextContent('dev');
    expect(services[0]).toHaveTextContent('sbdg_dev_toolchain');
    expect(services[1]).toHaveTextContent('server');
    expect(services[1]).toHaveTextContent('maven:3.9-eclipse-temurin-25');
    expect(services[1]).toHaveTextContent('[8080:8080]');
    expect(services[3]).toHaveTextContent('postgres:17');
    expect(services[3]).toHaveTextContent('[5432:5432]');

    // the ops posture — the probe pair the orchestrator polls, graceful drain
    expect(screen.getByTestId('deploy-probes')).toHaveTextContent('/actuator/health/liveness');
    expect(screen.getByTestId('deploy-probes')).toHaveTextContent('/actuator/health/readiness');
    const shutdown = screen.getByTestId('deploy-shutdown');
    expect(shutdown).toHaveTextContent('graceful');
    expect(shutdown).toHaveTextContent('30s');
  });

  it('paints the env rails: parity values identical, deliberate differences named', async () => {
    stubEndpoint([], { current: 'up' });

    render(<DeployStackDemo />);

    const rails = await screen.findAllByTestId('deploy-rail');
    expect(rails).toHaveLength(3);
    // the parity rail: byte-identical dev and prod values, side by side
    expect(rails[1]).toHaveTextContent('SPRING_DATASOURCE_URL');
    expect(rails[1]).toHaveTextContent('jdbc:postgresql://postgres:5432/guide');
    // the deliberate difference: the value that NAMES the environment
    expect(rails[0]).toHaveTextContent('SPRING_PROFILES_ACTIVE');
    expect(rails[0]).toHaveTextContent('dev');
    expect(rails[0]).toHaveTextContent('(unset → default)');
    // the self-teaching narration line travels with the report
    expect(screen.getByTestId('deploy-reading-0')).toHaveTextContent('one build, many environments');
  });

  it('re-reads the stack on demand: the button issues a second GET', async () => {
    const captured: Captured[] = [];
    stubEndpoint(captured, { current: 'up' });

    render(<DeployStackDemo />);
    await screen.findByTestId('deploy-image');

    fireEvent.click(screen.getByTestId('deploy-refresh'));

    await waitFor(() => {
      expect(captured.filter((call) => call.url.includes('/demo/deploy/stack'))).toHaveLength(2);
    });
    // the report is still painted after the refresh settles
    expect(await screen.findByTestId('deploy-image')).toHaveTextContent('sbdg_server_prod');
  });

  it('renders the degraded panel when the server is down', async () => {
    stubEndpoint([], { current: 'down' });

    render(<DeployStackDemo />);

    const panel = await screen.findByTestId('error-panel');
    expect(panel).toHaveTextContent('stack unreachable');
  });
});
