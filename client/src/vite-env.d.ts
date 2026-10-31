/// <reference types="vite/client" />

/**
 * Narrow the `VITE_*` env vars (Vite's default `ImportMetaEnv` index signature
 * types them as `any`) to `string | undefined` so config parsing is
 * type-checked. Vite only exposes `VITE_`-prefixed vars to client code.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_ENABLE_LOGS?: string;
  /**
   * Umami analytics (see src/ui/analytics/analytics.ts) — PUBLIC by
   * design: both values are baked into the served bundle and visible in
   * the page source anyway, so they ride repository variables in CI,
   * never secrets. BOTH must be non-empty to enable analytics; empty or
   * unset (the default) means a complete no-op.
   */
  readonly VITE_UMAMI_SRC?: string;
  readonly VITE_UMAMI_ID?: string;
  /**
   * Grafana Faro crash telemetry (see src/ui/analytics/crash-telemetry.ts)
   * — same PUBLIC-by-design discipline: both unset/empty means no script
   * injected and no initialization, the complete no-op default.
   */
  readonly VITE_FARO_URL?: string;
  readonly VITE_FARO_APP?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
