import { ErrorDetail } from './api-response.model';

/**
 * The normalised error a caller receives when a Taarifu API request fails.
 *
 * <p>Responsibility: a single, typed error shape for the whole app so components/services never parse
 * raw `HttpErrorResponse` bodies. The {@link ApiResponseInterceptor} translates every failure —
 * envelope errors, transport errors (offline/timeout), and non-envelope responses — into one of these.
 * UI code branches on the stable {@link code} (never the localised {@link message}).</p>
 */
export class ApiError extends Error {
  /**
   * @param code stable machine error code from `data.code` (e.g. `VALIDATION_FAILED`, `NOT_FOUND`,
   *   `UNAUTHENTICATED`), or a synthetic client code (`NETWORK_ERROR`, `UNKNOWN`) for transport failures.
   * @param message localised, user-safe message from the envelope (toast-ready), or a generic fallback.
   * @param statusCode the integer HTTP status (0 for a transport/offline failure).
   * @param errors field-level validation errors when {@link code} is `VALIDATION_FAILED`; else empty.
   */
  constructor(
    public readonly code: string,
    message: string,
    public readonly statusCode: number,
    public readonly errors: ErrorDetail[] = [],
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** True when the failure is a field-level validation error carrying {@link errors}. */
  get isValidation(): boolean {
    return this.code === 'VALIDATION_FAILED';
  }

  /** True when the user is unauthenticated (expired/invalid token) — drives the redirect to login. */
  get isUnauthenticated(): boolean {
    return this.code === 'UNAUTHENTICATED' || this.statusCode === 401;
  }

  /** True when the request reached no server (offline, DNS, CORS, timeout). */
  get isNetwork(): boolean {
    return this.code === 'NETWORK_ERROR' || this.statusCode === 0;
  }
}
