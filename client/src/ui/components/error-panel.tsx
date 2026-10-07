import type { Failure } from '../../core/error-handling/failures';

interface ErrorPanelProps {
  /** What the user was trying to load, e.g. "the topic map" or "this topic". */
  subject: string;
  failure: Failure;
}

/**
 * ErrorPanel — the full-screen failure state for a Critical failure: shows the
 * failure's user message plus the machine diagnostics (code, HTTP status, and
 * the server's requestId when present) so any failure can be traced in the
 * server logs. Used wherever a screen's data fetch resolved to a Failed
 * Result; non-blocking severities would degrade inline instead (chips/notes).
 */
export function ErrorPanel({ subject, failure }: ErrorPanelProps) {
  return (
    <section
      role="alert"
      data-testid="error-panel"
      className="flex w-full flex-col gap-xs rounded-lg border border-outline bg-surface-tint p-md shadow-button-card"
    >
      <h3 className="font-display text-title-sm text-error">
        Could not load {subject}
      </h3>
      <p className="text-body-sm text-on-surface-variant">{failure.message}</p>
      <p className="font-mono text-caption text-on-surface-variant">
        {failure.code} · status {String(failure.status)}
        {failure.requestId !== undefined && ` · request ${failure.requestId}`}
      </p>
    </section>
  );
}
