import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ManifestDemo from './manifest_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';
import { stubApiFetch } from '../../../test/fetch_stub';

/**
 * ManifestDemo spec — topic 31's deps:manifest widget over the shared
 * guarded pipeline: the manifest table renders runtime versions with their
 * reading source (manifest vs jar-name) and renders test-scoped artifacts
 * as absent; the flagged filter narrows to the three pins; a flagged
 * rationale card ships; the tree snapshot panel expands a direct
 * dependency's children and lists the recorded conflicts; and a broken
 * manifest shows the error panel.
 */

const MANIFEST_PATH = '/api/demo/deps/manifest';
const TREE_PATH = '/api/demo/deps/tree';

const manifest = (artifacts: unknown[], flaggedDecisions: unknown[]) => ({
  artifacts,
  flaggedDecisions,
});

const artifact = (overrides: Record<string, unknown>) => ({
  scope: 'compile',
  managedBy: 'Boot parent BOM',
  category: 'web',
  note: 'note',
  present: true,
  ...overrides,
});

const tree = () => ({
  capturedOn: '2026-10-07',
  parentBom: 'spring-boot-starter-parent 4.1.1',
  importedBom: 'spring-grpc-dependencies 1.0.3',
  directCount: 2,
  direct: [
    {
      groupId: 'org.postgresql',
      artifactId: 'postgresql',
      version: '42.7.13',
      scope: 'runtime',
      optional: false,
      children: [
        { groupId: 'org.checkerframework', artifactId: 'checker-qual', version: '3.55.1', scope: 'runtime' },
      ],
    },
    {
      groupId: 'net.coobird',
      artifactId: 'thumbnailator',
      version: '0.4.21',
      scope: 'compile',
      optional: false,
      children: [],
    },
  ],
  conflicts: [
    'com.google.guava:guava:33.4.8-jre omitted for conflict with 33.4.8-android — NEAREST WINS',
  ],
});

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ManifestDemo', () => {
  it('renders runtime versions with their reading source', async () => {
    stubApiFetch([
      {
        url: MANIFEST_PATH,
        body: manifest(
          [
            artifact({
              groupId: 'org.springframework',
              artifactId: 'spring-core',
              version: '7.0.9',
              versionSource: 'manifest',
            }),
            artifact({
              groupId: 'com.zaxxer',
              artifactId: 'HikariCP',
              version: '7.0.2',
              versionSource: 'jar-name',
              category: 'data',
            }),
          ],
          [],
        ),
      },
      { url: TREE_PATH, body: tree() },
    ]);

    render(<ManifestDemo />);

    await screen.findByTestId('deps-artifact-spring-core');
    expect(screen.getByTestId('deps-artifact-spring-core')).toHaveTextContent('7.0.9');
    expect(screen.getByTestId('deps-artifact-spring-core')).toHaveTextContent('via manifest');
    expect(screen.getByTestId('deps-artifact-HikariCP')).toHaveTextContent('via jar-name');
  });

  it('renders test-scoped artifacts as absent and flags the pins', async () => {
    stubApiFetch([
      {
        url: MANIFEST_PATH,
        body: manifest(
          [
            artifact({
              groupId: 'org.testcontainers',
              artifactId: 'testcontainers-postgresql',
              scope: 'test',
              managedBy: "Boot parent's testcontainers BOM",
              category: 'tests',
              present: false,
            }),
            artifact({
              groupId: 'net.coobird',
              artifactId: 'thumbnailator',
              version: '0.4.21',
              versionSource: 'manifest',
              managedBy: 'PINNED in pom (flagged)',
              category: 'files',
            }),
          ],
          [
            {
              groupId: 'net.coobird',
              artifactId: 'thumbnailator',
              pinnedVersion: '0.4.21',
              rationale: 'single-jar, focused, no transitive weight',
              topic: '26 files-media',
            },
          ],
        ),
      },
      { url: TREE_PATH, body: tree() },
    ]);

    render(<ManifestDemo />);

    await screen.findByTestId('deps-absent');
    expect(screen.getByTestId('deps-absent')).toHaveTextContent('test scope');
    await screen.findByTestId('deps-flagged-thumbnailator');
    expect(screen.getByTestId('deps-flagged-thumbnailator')).toHaveTextContent('26 files-media');

    fireEvent.click(screen.getByTestId('deps-filter-flagged'));
    expect(screen.queryByTestId('deps-artifact-testcontainers-postgresql')).toBeNull();
    expect(screen.getByTestId('deps-artifact-thumbnailator')).toBeInTheDocument();
  });

  it('serves the tree snapshot with expandable children and conflicts', async () => {
    stubApiFetch([
      {
        url: MANIFEST_PATH,
        body: manifest(
          [
            artifact({
              groupId: 'io.grpc',
              artifactId: 'grpc-stub',
              version: '1.77.1',
              versionSource: 'manifest',
              managedBy: 'spring-grpc BOM',
              category: 'rpc',
            }),
          ],
          [],
        ),
      },
      { url: TREE_PATH, body: tree() },
    ]);

    render(<ManifestDemo />);

    await screen.findByTestId('deps-direct-postgresql');
    expect(screen.getByText(/spring-boot-starter-parent 4\.1\.1/)).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('deps-direct-postgresql'));
    expect(screen.getByTestId('deps-children-postgresql')).toHaveTextContent('checker-qual');

    fireEvent.click(screen.getByTestId('deps-direct-thumbnailator'));
    expect(screen.getByTestId('deps-children-thumbnailator-leaf')).toHaveTextContent('leaf');

    expect(screen.getAllByTestId('deps-conflict').length).toBe(1);
    expect(screen.getByTestId('deps-conflict')).toHaveTextContent('NEAREST WINS');
  });

  it('renders the error panel when the manifest cannot be read', async () => {
    stubApiFetch([{ url: MANIFEST_PATH, brokenJson: true, status: 200 }]);

    render(<ManifestDemo />);

    const panel = await screen.findByRole('alert');
    expect(panel).toBeInTheDocument();
  });
});
