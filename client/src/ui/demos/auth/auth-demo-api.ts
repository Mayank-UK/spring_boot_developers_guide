import { container } from '../../../core/container';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';

/**
 * The auth demos' shared guarded-fetch helper — the same apiJson shape
 * every demo area in this guide uses, extracted once because all six
 * auth components speak it (one file keeps the six focused on their
 * flows, not on plumbing). Two auth-specific behaviors:
 * - `credentials: 'include'` rides the browser cookie jar by default
 *   (the session demo's whole point); token demos ignore it harmlessly.
 * - a NON-2xx answer resolves as { ok: false, failure } carrying the
 *   server's status — the auth island's 401s are FLOW EVENTS (anonymous
 *   me, revoked key, tampered token), not accidents, so the demos render
 *   them as evidence instead of error panels when asked to.
 */
export type ApiResult<T> = { ok: true; value: T } | { ok: false; failure: Failure };

export async function authApiJson<T>(
  path: string,
  init: RequestInit | undefined,
  parse: (body: unknown) => T,
): Promise<ApiResult<T>> {
  const config = container.appConfig;
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}${path}`, {
        credentials: 'include',
        ...init,
      });
    } catch (error) {
      throw new NetworkException('Could not reach the server.', { cause: error });
    }
    let parsed: unknown;
    try {
      parsed = await response.json();
    } catch (error) {
      throw new ServerException(`Request failed with status ${String(response.status)}.`, {
        status: response.status,
        cause: error,
      });
    }
    if (!response.ok) {
      if (isRecord(parsed) && isRecord(parsed.error)) {
        throw new ServerException(
          typeof parsed.error.message === 'string'
            ? parsed.error.message
            : `Request failed with status ${String(response.status)}.`,
          {
            status: response.status,
            code: typeof parsed.error.code === 'string' ? parsed.error.code : 'unknown_error',
            requestId:
              typeof parsed.error.requestId === 'string' ? parsed.error.requestId : undefined,
          },
        );
      }
      throw new ServerException(`Request failed with status ${String(response.status)}.`, {
        status: response.status,
      });
    }
    return parseDto(parsed, response.status, parse);
  });
  if (result.ok) return { ok: true, value: result.value };
  return { ok: false, failure: result.failure };
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

export function requireString(row: unknown, field: string): string {
  if (!isRecord(row) || typeof row[field] !== 'string') {
    throw new Error(`field ${field} must be a string`);
  }
  return row[field];
}
