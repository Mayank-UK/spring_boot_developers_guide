import { useCallback, useEffect, useState } from 'react';
import { container } from '../../../core/container';
import { guardedGetJson, parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import type { Failure } from '../../../core/error-handling/failures';
import { ErrorPanel } from '../../components/error-panel';

/**
 * Demo for DemoBlock `deps:manifest` — topic 31 (dependency-management),
 * the live half of the tree-and-enforcer section. Two endpoints, one
 * truth: GET /api/demo/deps/manifest serves the RUNTIME inventory (each
 * curated artifact with the version the JVM actually loaded, the source of
 * that reading, and the flagged-choices registry beside it); GET
 * /api/demo/deps/tree serves the summarized dependency:tree snapshot with
 * its recorded mediation conflicts. Clicking a direct dependency reveals
 * its second-level children.
 *
 * A teaching widget (like the other demos): it fetches directly through
 * the SAME guarded pipeline every data source uses, no dedicated
 * repository.
 */

interface ArtifactRow {
  groupId: string;
  artifactId: string;
  version: string | null;
  versionSource: string | null;
  present: boolean;
  scope: string;
  managedBy: string;
  category: string;
  note: string;
}

interface FlaggedRow {
  groupId: string;
  artifactId: string;
  pinnedVersion: string;
  rationale: string;
  topic: string;
}

interface ManifestReport {
  artifacts: ArtifactRow[];
  flaggedDecisions: FlaggedRow[];
}

interface TreeChild {
  groupId: string;
  artifactId: string;
  version: string;
  scope: string | null;
}

interface TreeDirect {
  groupId: string;
  artifactId: string;
  version: string;
  scope: string | null;
  optional: boolean;
  children: TreeChild[];
}

interface TreeReport {
  capturedOn: string;
  parentBom: string;
  importedBom: string;
  directCount: number;
  direct: TreeDirect[];
  conflicts: string[];
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'ready'; manifest: ManifestReport; tree: TreeReport }
  | { kind: 'error'; failure: Failure };

/* ---- runtime validation of the wire shape (nulls are dropped server-side) ---- */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string`);
  return value;
}

function optionalString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === undefined) return null;
  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string when present`);
  return value;
}

function parseArtifact(source: unknown): ArtifactRow {
  if (!isRecord(source)) throw new Error('an artifact row must be an object');
  return {
    groupId: stringField(source, 'groupId'),
    artifactId: stringField(source, 'artifactId'),
    version: optionalString(source, 'version'),
    versionSource: optionalString(source, 'versionSource'),
    present: source.present === true,
    scope: stringField(source, 'scope'),
    managedBy: stringField(source, 'managedBy'),
    category: stringField(source, 'category'),
    note: stringField(source, 'note'),
  };
}

function parseFlagged(source: unknown): FlaggedRow {
  if (!isRecord(source)) throw new Error('a flagged decision must be an object');
  return {
    groupId: stringField(source, 'groupId'),
    artifactId: stringField(source, 'artifactId'),
    pinnedVersion: stringField(source, 'pinnedVersion'),
    rationale: stringField(source, 'rationale'),
    topic: stringField(source, 'topic'),
  };
}

function parseChild(source: unknown): TreeChild {
  if (!isRecord(source)) throw new Error('a tree child must be an object');
  return {
    groupId: stringField(source, 'groupId'),
    artifactId: stringField(source, 'artifactId'),
    version: stringField(source, 'version'),
    scope: optionalString(source, 'scope'),
  };
}

function parseDirect(source: unknown): TreeDirect {
  if (!isRecord(source)) throw new Error('a direct dependency must be an object');
  const children = source.children;
  if (!Array.isArray(children)) throw new Error('field "children" must be an array');
  if (typeof source.optional !== 'boolean') {
    throw new Error('field "optional" must be a boolean');
  }
  return {
    groupId: stringField(source, 'groupId'),
    artifactId: stringField(source, 'artifactId'),
    version: stringField(source, 'version'),
    scope: optionalString(source, 'scope'),
    optional: source.optional,
    children: children.map(parseChild),
  };
}

function parseManifestBody(source: unknown): ManifestReport {
  if (!isRecord(source)) throw new Error('manifest report must be an object');
  const artifacts = source.artifacts;
  const flagged = source.flaggedDecisions;
  if (!Array.isArray(artifacts)) throw new Error('field "artifacts" must be an array');
  if (!Array.isArray(flagged)) throw new Error('field "flaggedDecisions" must be an array');
  return { artifacts: artifacts.map(parseArtifact), flaggedDecisions: flagged.map(parseFlagged) };
}

function parseTreeBody(source: unknown): TreeReport {
  if (!isRecord(source)) throw new Error('tree report must be an object');
  const direct = source.direct;
  const conflicts = source.conflicts;
  if (!Array.isArray(direct)) throw new Error('field "direct" must be an array');
  if (!Array.isArray(conflicts)) throw new Error('field "conflicts" must be an array');
  for (const conflict of conflicts) {
    if (typeof conflict !== 'string') throw new Error('each conflict must be a string');
  }
  return {
    capturedOn: stringField(source, 'capturedOn'),
    parentBom: stringField(source, 'parentBom'),
    importedBom: stringField(source, 'importedBom'),
    directCount: numberField(source, 'directCount'),
    direct: direct.map(parseDirect),
    conflicts,
  };
}

function numberField(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`field "${key}" must be a number`);
  }
  return value;
}

/* ---- presentation helpers ---- */

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const PILL =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface transition-colors hover:bg-surface-tint';
const CELL = 'border-b border-outline px-xs py-xs text-left';

function VersionCell({ artifact }: { artifact: ArtifactRow }) {
  if (!artifact.present) {
    return (
      <span data-testid="deps-absent" className="font-mono text-caption text-on-surface-variant">
        (absent here — {artifact.scope} scope)
      </span>
    );
  }
  return (
    <span className="flex flex-wrap items-baseline gap-xxs">
      <span className="font-mono text-body-sm text-on-surface">{artifact.version}</span>
      <span className="font-mono text-caption text-on-surface-variant">
        via {artifact.versionSource ?? '?'}
      </span>
    </span>
  );
}

export default function ManifestDemo() {
  const [state, setState] = useState<PanelState>({ kind: 'loading' });
  const [expanded, setExpanded] = useState<string | null>(null);
  const [onlyFlagged, setOnlyFlagged] = useState(false);
  const config = container.appConfig;

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    const result = await safeRepositoryCall(async () => {
      const manifest = await guardedGetJson(config, '/demo/deps/manifest');
      const tree = await guardedGetJson(config, '/demo/deps/tree');
      return {
        manifest: parseDto(manifest.body, manifest.status, parseManifestBody),
        tree: parseDto(tree.body, tree.status, parseTreeBody),
      };
    });
    setState(
      result.ok
        ? { kind: 'ready', manifest: result.value.manifest, tree: result.value.tree }
        : { kind: 'error', failure: result.failure },
    );
  }, [config]);

  useEffect(() => {
    void load();
  }, [load]);

  if (state.kind === 'loading') {
    return (
      <p data-testid="deps-loading" className="text-body-sm text-on-surface-variant">
        asking the classpath…
      </p>
    );
  }
  if (state.kind === 'error') {
    return <ErrorPanel subject="the dependency manifest" failure={state.failure} />;
  }

  const { manifest, tree } = state;
  const rows = onlyFlagged
    ? manifest.artifacts.filter((artifact) => artifact.managedBy.startsWith('PINNED'))
    : manifest.artifacts;
  const flaggedCoordinates = new Set(
    manifest.flaggedDecisions.map((decision) => `${decision.groupId}:${decision.artifactId}`),
  );

  return (
    <div className="flex flex-col gap-md">
      <div className="flex flex-wrap items-center justify-between gap-xs">
        <p className={OVERLINE}>what the JVM actually loaded — not what the pom claims</p>
        <div className="flex flex-wrap gap-xxs">
          <button
            type="button"
            data-testid="deps-filter-flagged"
            onClick={() => setOnlyFlagged((current) => !current)}
            className={PILL}
          >
            {onlyFlagged ? 'showing the 3 flagged pins' : 'flagged pins only'}
          </button>
          <button type="button" data-testid="deps-refresh" onClick={() => void load()} className={PILL}>
            refresh
          </button>
        </div>
      </div>

      <div className="flex flex-col gap-xxs overflow-x-auto">
        <table
          data-testid="deps-manifest-table"
          className="w-full border-collapse text-body-sm"
        >
          <thead>
            <tr className="text-left text-on-surface-variant">
              <th className={CELL}>artifact</th>
              <th className={CELL}>runtime version</th>
              <th className={CELL}>managed by</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((artifact) => {
              const coordinate = `${artifact.groupId}:${artifact.artifactId}`;
              return (
                <tr
                  key={coordinate}
                  data-testid={`deps-artifact-${artifact.artifactId}`}
                  title={artifact.note}
                  className={flaggedCoordinates.has(coordinate) ? 'bg-surface-tint' : undefined}
                >
                  <td className={`${CELL} font-mono`}>
                    {coordinate}
                    <span className="ml-xxs font-caption text-on-surface-variant">
                      ({artifact.category} · {artifact.scope})
                    </span>
                  </td>
                  <td className={CELL}>
                    <VersionCell artifact={artifact} />
                  </td>
                  <td className={`${CELL} text-on-surface-variant`}>{artifact.managedBy}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <p className="text-caption text-on-surface-variant">
          highlighted rows carry an explicit pom pin — the rationale ships below; “via manifest” =
          the jar’s MANIFEST.MF, “via jar-name” = parsed from the jar file name (HikariCP and
          flyway-core ship no manifest version)
        </p>
      </div>

      <div className="flex flex-col gap-xs">
        <span className={OVERLINE}>the flagged choices — why each pin exists</span>
        {manifest.flaggedDecisions.map((decision) => (
          <div
            key={`${decision.groupId}:${decision.artifactId}`}
            data-testid={`deps-flagged-${decision.artifactId}`}
            className="flex flex-col gap-xxs rounded-lg border border-outline bg-surface-tint p-sm"
          >
            <div className="flex flex-wrap items-baseline justify-between gap-xs">
              <span className="font-mono text-body-sm text-on-surface">
                {decision.groupId}:{decision.artifactId}
              </span>
              <span className="font-mono text-caption text-on-surface-variant">
                pinned {decision.pinnedVersion} · {decision.topic}
              </span>
            </div>
            <span className="text-caption text-on-surface-variant">{decision.rationale}</span>
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-xs">
        <span className={OVERLINE}>
          the dependency:tree snapshot — captured {tree.capturedOn}, {String(tree.directCount)}{' '}
          direct dependencies
        </span>
        <p className="text-caption text-on-surface-variant">
          {tree.parentBom} · {tree.importedBom} — a static, honestly-labeled capture (regeneration
          command in the topic); click a dependency to see its second-level children
        </p>
        <div className="flex flex-wrap gap-xxs">
          {tree.direct.map((dependency) => {
            const coordinate = `${dependency.groupId}:${dependency.artifactId}`;
            const isOpen = expanded === coordinate;
            return (
              <div key={coordinate} className="flex flex-col gap-xxs">
                <button
                  type="button"
                  data-testid={`deps-direct-${dependency.artifactId}`}
                  onClick={() => setExpanded(isOpen ? null : coordinate)}
                  className={PILL}
                  title={dependency.children.length > 0 ? 'expand children' : 'a leaf — nothing transitive'}
                >
                  {dependency.artifactId} {dependency.version}
                  {dependency.optional ? ' (optional)' : ''}
                </button>
                {isOpen && dependency.children.length > 0 && (
                  <div
                    data-testid={`deps-children-${dependency.artifactId}`}
                    className="flex flex-col gap-xxs rounded-lg border border-outline p-xs"
                  >
                    {dependency.children.map((child) => (
                      <span
                        key={`${child.groupId}:${child.artifactId}`}
                        className="font-mono text-caption text-on-surface-variant"
                      >
                        {child.groupId}:{child.artifactId} {child.version}
                        {child.scope ? ` · ${child.scope}` : ''}
                      </span>
                    ))}
                  </div>
                )}
                {isOpen && dependency.children.length === 0 && (
                  <span data-testid={`deps-children-${dependency.artifactId}-leaf`}
                    className="text-caption text-on-surface-variant">
                    nothing transitive — a leaf
                  </span>
                )}
              </div>
            );
          })}
        </div>
      </div>

      <div className="flex flex-col gap-xxs">
        <span className={OVERLINE}>mediation conflicts, as maven reported them</span>
        {tree.conflicts.map((conflict) => (
          <p
            key={conflict}
            data-testid="deps-conflict"
            className="rounded-lg border border-outline p-xs font-mono text-caption text-on-surface-variant break-words"
          >
            {conflict}
          </p>
        ))}
      </div>
    </div>
  );
}
