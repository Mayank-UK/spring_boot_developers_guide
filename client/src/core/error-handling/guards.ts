import type { AppConfig } from '../config/app-config';
import {
  AppException,
  InvalidResponseException,
  NetworkException,
  ServerException,
} from './exceptions';
import {
  Failure,
  NetworkFailure,
  ServerFailure,
  UnknownFailure,
} from './failures';
import { failed, success, type Result } from '../result';

/**
 * Guards — the heart of the error pipeline (express-guide client shape,
 * trimmed to this client's fetch-based data layer). Nothing past a repository
 * ever throws for an expected failure; everything arrives as a typed Failure
 * wrapped in a Result.
 *
 *  1. `guardedGetJson`    : the shared fetch pipeline. Every transport failure
 *                           is upgraded to a typed AppException:
 *                             fetch rejected           → NetworkException (status 0)
 *                             body is not JSON         → ServerException (unknown_error)
 *                             non-2xx + error envelope → ServerException (envelope
 *                                                      code/message/requestId/status)
 *  2. `parseDto`          : runs a DTO `fromJson` and upgrades a shape
 *                           violation to InvalidResponseException.
 *  3. `guardedApiCall`    : wraps a data-source call, re-classifying any
 *                           leftover thrown value as a typed AppException.
 *  4. `safeRepositoryCall`: the repository's guarantee — a thrown AppException
 *                           maps to a Failure in a Failed Result; the method
 *                           itself never throws.
 */

export interface JsonResponse {
  readonly body: unknown;
  readonly status: number;
}

/** GET {apiBaseUrl}{path} as JSON with every transport failure guarded. */
export async function guardedGetJson(
  config: AppConfig,
  path: string,
): Promise<JsonResponse> {
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      headers: { accept: 'application/json' },
    });
  } catch (error) {
    throw new NetworkException('Could not reach the server.', { cause: error });
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (error) {
    throw unknownStatusException(response.status, error);
  }

  if (!response.ok) {
    throw errorEnvelopeException(body, response.status);
  }

  return { body, status: response.status };
}

/**
 * Parse the server's `{ error: { code, message, requestId, …details } }`
 * envelope. Only the canonical code/message/requestId survive — extra merged
 * detail keys are ignored. Non-envelope bodies degrade to unknown_error with a
 * status-derived message.
 */
function errorEnvelopeException(body: unknown, status: number): ServerException {
  if (isRecord(body) && isRecord(body.error)) {
    const code = typeof body.error.code === 'string' ? body.error.code : 'unknown_error';
    const message =
      typeof body.error.message === 'string'
        ? body.error.message
        : `Request failed with status ${String(status)}.`;
    const requestId =
      typeof body.error.requestId === 'string' ? body.error.requestId : undefined;
    return new ServerException(message, { code, status, requestId });
  }
  return unknownStatusException(status, undefined);
}

function unknownStatusException(status: number, cause: unknown): ServerException {
  return new ServerException(`Request failed with status ${String(status)}.`, {
    code: 'unknown_error',
    status,
    cause,
  });
}

/**
 * Validate a (2xx) JSON body into a DTO: a `fromJson` that throws on a shape
 * violation is upgraded to an InvalidResponseException carrying the response
 * status, so malformed server data surfaces as a typed server failure instead
 * of "[object Object]" strings reaching the UI.
 */
export function parseDto<T>(
  body: unknown,
  status: number,
  fromJson: (body: unknown) => T,
): T {
  try {
    return fromJson(body);
  } catch (error) {
    throw new InvalidResponseException(status, { cause: error });
  }
}

/** Wrap a low-level data-source operation. Re-throws typed AppExceptions. */
export async function guardedApiCall<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (e) {
    throw toAppException(e);
  }
}

/** Convert any thrown value into a typed AppException (idempotent if already one). */
export function toAppException(error: unknown): AppException {
  if (error instanceof AppException) return error;

  if (error instanceof TypeError) {
    // fetch()/network failures surface as TypeError ("Failed to fetch").
    return new NetworkException('Could not reach the server.', { cause: error });
  }

  return new ServerException('Unexpected error', { cause: error });
}

/** Map an AppException to a Failure, preserving the machine diagnostics. */
export function mapExceptionToFailure(e: AppException): Failure {
  const diagnostics = { code: e.code, status: e.status, requestId: e.requestId };

  if (e instanceof NetworkException) {
    return new NetworkFailure(e.message, diagnostics);
  }
  if (e instanceof ServerException) {
    return new ServerFailure(e.message, diagnostics);
  }

  return new UnknownFailure(e.message, diagnostics);
}

/**
 * Wrap a repository method and return a Result. This is the layer's one
 * guarantee: expected failures (network down, error envelope, malformed
 * payload) AND unexpected engine errors all resolve as Failed Results — the
 * caller never needs try/catch and no rejection goes unhandled.
 */
export async function safeRepositoryCall<T>(call: () => Promise<T>): Promise<Result<T>> {
  try {
    return success(await call());
  } catch (e) {
    return failed(mapExceptionToFailure(toAppException(e)));
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
