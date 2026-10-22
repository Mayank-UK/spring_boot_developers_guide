import { Suspense } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { ThemeToggle } from './theme-toggle';
import { RoutePaths } from '../routing/routes';

/**
 * RootLayout — the single shared app shell: a quiet header (guide name as a
 * home link + theme toggle) and a scrolling content area via <Outlet/>. The
 * home topic map is the navigation hub (every topic is reachable from there),
 * so the header carries no drawer and no extra chrome — the served content
 * carries the page.
 *
 * Everywhere except home, the header also leads with a back arrow that walks
 * the browser history stack (navigate(-1)), so any number of nested topic
 * hops can be unwound one click at a time until reaching home.
 *
 * This is also the app's ONE Suspense boundary for route views: both content
 * views are lazy() chunks (see ui/routing/router.tsx) and resolve behind this
 * single fallback, so navigation shows exactly one consistent loading state.
 */
export function RootLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  // The back arrow is hidden exactly on home; every other route (including
  // the catch-all not-found) shows it.
  const canGoBack = location.pathname !== RoutePaths.Home;

  return (
    <div className="flex min-h-screen flex-col bg-surface-tint text-on-surface">
      <header className="sticky top-0 z-20 flex h-header items-center gap-md border-b border-outline bg-surface px-lg">
        {canGoBack && (
          <button
            type="button"
            onClick={() => {
              void navigate(-1);
            }}
            className="cursor-pointer rounded-md px-sm py-xxs text-body text-on-surface-variant hover:bg-surface-variant hover:text-on-surface"
            aria-label="Go back"
          >
            ←
          </button>
        )}
        <Link
          to={RoutePaths.Home}
          className="truncate font-display text-title-sm text-on-surface hover:text-primary"
        >
          Spring Boot Developers Guide
        </Link>
        <div className="flex-1" />
        <ThemeToggle />
      </header>

      <main className="flex flex-1 flex-col">
        <Suspense
          fallback={
            <div
              data-testid="route-loading"
              className="mx-auto flex w-full max-w-content flex-col gap-md px-md py-xl"
              aria-hidden
            >
              <p className="text-body text-on-surface-variant">loading…</p>
              <div className="h-lg w-full animate-pulse rounded-md bg-surface-variant" />
              <div className="h-lg w-2/3 animate-pulse rounded-md bg-surface-variant" />
            </div>
          }
        >
          <Outlet />
        </Suspense>
      </main>
    </div>
  );
}
