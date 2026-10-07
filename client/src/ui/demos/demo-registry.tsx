import type { ComponentType, LazyExoticComponent } from 'react';
import { lazy } from 'react';

/**
 * Demo registry — the client-side half of the content API's demo-block
 * contract. Served content names a demo by `demoId`; this record is the only
 * place that binds an id to an implementation. Demos are lazy-loaded so a
 * topic's chunk only pays for the demos it actually renders, and an unknown id
 * degrades to a "not wired yet" panel instead of crashing the screen.
 *
 * ── HOW A DEMO LANDS (Phase 3+) ─────────────────────────────────────────────
 * 1. Create `src/ui/demos/<area>/<name>_demo.tsx` — a default-exported
 *    component taking no props (it resolves its own dependencies through the
 *    container, like every view).
 * 2. Add one entry here:
 *
 *      const MyDemo = lazy(() => import('./<area>/<name>_demo'));
 *      export const demoRegistry = { ..., 'area:demo-id': MyDemo };
 *
 * Nothing else changes: the content's `demoId` starts resolving through
 * DemoHost automatically. The first real demos landed with Phase 3's topics;
 * every new topic adds its entries here.
 */

export type DemoComponentType = ComponentType;

const RuntimeTypesDemo = lazy(() => import('./java/runtime_types_demo'));
const ThreadsDemo = lazy(() => import('./jvm/threads_demo'));
const StreamingDemo = lazy(() => import('./jvm/streaming_demo'));
const AppInfoDemo = lazy(() => import('./spring/app_info_demo'));
const HeadersDemo = lazy(() => import('./http/headers_demo'));
const ContainerDemo = lazy(() => import('./core/container_demo'));
const AopProxyDemo = lazy(() => import('./aop/proxy_demo'));
const AopTraceDemo = lazy(() => import('./aop/trace_demo'));
const AopSelfInvocationDemo = lazy(() => import('./aop/self_invocation_demo'));
const MiddlewarePipelineDemo = lazy(() => import('./middleware/pipeline_demo'));
const ValidationArenaDemo = lazy(() => import('./validation/arena_demo'));
const ReqResInspectorDemo = lazy(() => import('./reqres/inspector_demo'));
const ReqResMethodsDemo = lazy(() => import('./reqres/methods_demo'));
const RoutingExplorerDemo = lazy(() => import('./routing/explorer_demo'));
const PostsWorkbenchDemo = lazy(() => import('./api/posts_workbench_demo'));
const DocsLinkDemo = lazy(() => import('./api/docs_link_demo'));
const GraphqlPlaygroundDemo = lazy(() => import('./api/graphql_playground_demo'));
const WebhookSimulatorDemo = lazy(() => import('./api/webhook_simulator_demo'));
const GrpcConsoleDemo = lazy(() => import('./api/grpc_console_demo'));
const SchemaExplorerDemo = lazy(() => import('./db/schema_explorer_demo'));
const UuidDemo = lazy(() => import('./db/uuid_demo'));
const SqlSafetyDemo = lazy(() => import('./db/sql_safety_demo'));
const MigrationsDemo = lazy(() => import('./db/migrations_demo'));
const SqlLabDemo = lazy(() => import('./db/sql_lab_demo'));
const SearchDemo = lazy(() => import('./db/search_demo'));
const RaceDemo = lazy(() => import('./db/race_demo'));
const AuditDemo = lazy(() => import('./db/audit_demo'));
const JpaProbeDemo = lazy(() => import('./db/jpa_probe_demo'));
const MongoConsoleDemo = lazy(() => import('./db/mongo_console_demo'));
const RedisPlaygroundDemo = lazy(() => import('./redis/playground_demo'));
const CachingLabDemo = lazy(() => import('./caching/lab_demo'));
const MsgStreamDemo = lazy(() => import('./msg/stream_demo'));
const MsgOutboxDemo = lazy(() => import('./msg/outbox_demo'));
const AuthSessionsDemo = lazy(() => import('./auth/sessions_demo'));
const AuthApiKeysDemo = lazy(() => import('./auth/api_keys_demo'));
const AuthJwtDemo = lazy(() => import('./auth/jwt_demo'));
const AuthOAuthDemo = lazy(() => import('./auth/oauth_demo'));
const AuthHashingDemo = lazy(() => import('./auth/hashing_demo'));
const AuthRefreshDemo = lazy(() => import('./auth/refresh_demo'));
const AuthzMatrixDemo = lazy(() => import('./authz/matrix_demo'));
const SecurityLabDemo = lazy(() => import('./security/lab_demo'));
const SecurityPiiDemo = lazy(() => import('./security/pii_demo'));
const FilesWorkbenchDemo = lazy(() => import('./files/workbench_demo'));
const RealtimeTickerDemo = lazy(() => import('./realtime/ticker_demo'));
const RealtimeChatDemo = lazy(() => import('./realtime/chat_demo'));
const I18nNegotiatorDemo = lazy(() => import('./i18n/negotiator_demo'));
const JobsConsoleDemo = lazy(() => import('./jobs/console_demo'));
const ErrorsExplorerDemo = lazy(() => import('./errors/explorer_demo'));
const DepsManifestDemo = lazy(() => import('./deps/manifest_demo'));
const ObsViewerDemo = lazy(() => import('./obs/viewer_demo'));
const ObsTraceDemo = lazy(() => import('./obs/trace_demo'));
const ArchTracerDemo = lazy(() => import('./arch/tracer_demo'));
const TestingRunnerDemo = lazy(() => import('./testing/testing_runner_demo'));
const DebugStallDemo = lazy(() => import('./debugging/debug_stall_demo'));

export const demoRegistry: Readonly<
  Record<string, LazyExoticComponent<DemoComponentType>>
> = {
  'java:runtime-types': RuntimeTypesDemo,
  'jvm:threads': ThreadsDemo,
  'jvm:streaming': StreamingDemo,
  'spring:app-info': AppInfoDemo,
  'http:headers': HeadersDemo,
  'core:container': ContainerDemo,
  'aop:proxy': AopProxyDemo,
  'aop:trace': AopTraceDemo,
  'aop:self-invocation': AopSelfInvocationDemo,
  'middleware:pipeline': MiddlewarePipelineDemo,
  'routing:explorer': RoutingExplorerDemo,
  'validation:arena': ValidationArenaDemo,
  'reqres:inspector': ReqResInspectorDemo,
  'reqres:methods': ReqResMethodsDemo,
  'api:posts-workbench': PostsWorkbenchDemo,
  'api:docs-link': DocsLinkDemo,
  'api:graphql-playground': GraphqlPlaygroundDemo,
  'api:webhook-simulator': WebhookSimulatorDemo,
  'api:grpc-console': GrpcConsoleDemo,
  'db:schema-explorer': SchemaExplorerDemo,
  'db:uuid': UuidDemo,
  'db:sql-safety': SqlSafetyDemo,
  'db:migrations': MigrationsDemo,
  'db:sql-lab': SqlLabDemo,
  'db:search': SearchDemo,
  'db:race': RaceDemo,
  'db:audit': AuditDemo,
  'db:jpa-probe': JpaProbeDemo,
  'db:mongo-console': MongoConsoleDemo,
  'redis:playground': RedisPlaygroundDemo,
  'caching:lab': CachingLabDemo,
  'msg:stream': MsgStreamDemo,
  'msg:outbox': MsgOutboxDemo,
  'auth:sessions': AuthSessionsDemo,
  'auth:api-keys': AuthApiKeysDemo,
  'auth:jwt': AuthJwtDemo,
  'auth:oauth': AuthOAuthDemo,
  'auth:hashing': AuthHashingDemo,
  'auth:refresh': AuthRefreshDemo,
  'authz:matrix': AuthzMatrixDemo,
  'security:lab': SecurityLabDemo,
  'security:pii': SecurityPiiDemo,
  'files:workbench': FilesWorkbenchDemo,
  'realtime:ticker': RealtimeTickerDemo,
  'realtime:chat': RealtimeChatDemo,
  'i18n:negotiator': I18nNegotiatorDemo,
  'jobs:console': JobsConsoleDemo,
  'deps:manifest': DepsManifestDemo,
  'errors:explorer': ErrorsExplorerDemo,
  'obs:viewer': ObsViewerDemo,
  'obs:trace': ObsTraceDemo,
  'arch:tracer': ArchTracerDemo,
  'testing:runner': TestingRunnerDemo,
  'debug:stall': DebugStallDemo,
};
