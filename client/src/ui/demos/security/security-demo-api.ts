import { container } from '../../../core/container';
import { parseDto, safeRepositoryCall } from '../../../core/error-handling/guards';
import { NetworkException, ServerException } from '../../../core/error-handling/exceptions';
import type { Failure } from '../../../core/error-handling/failures';

/**
 * The security demos' shared guarded-fetch helper — the same apiJson shape
 * every demo area uses (the auth area's helper, minus the cookie jar: these
 * demos are stateless API calls; credentials are a CORS-section LESSON here,
 * not a setting). A NON-2xx answer resolves as { ok: false, failure } with
 * the server's status and error envelope — the rate limiter's 429 and the
 * PII vault's typed not-found are FLOW EVENTS the demos render as evidence.
 */
export type ApiResult<T> = { ok: true; value: T } | { ok: false; failure: Failure };

export async function securityApiJson<T>(
  path: string,
  init: RequestInit | undefined,
  parse: (body: unknown) => T,
): Promise<ApiResult<T>> {
  const config = container.appConfig;
  const result = await safeRepositoryCall(async () => {
    let response: Response;
    try {
      response = await fetch(`${config.apiBaseUrl}${path}`, init);
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

export function requireNumber(row: unknown, field: string): number {
  if (!isRecord(row) || typeof row[field] !== 'number') {
    throw new Error(`field ${field} must be a number`);
  }
  return row[field];
}
