import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiResponse } from '../api/api-response.model';
import { ApiError } from '../api/api-error';
import { ToastService } from '../notifications/toast.service';

/**
 * Functional HTTP interceptor that NORMALISES every backend error into a typed {@link ApiError} and
 * surfaces a localised toast (ARCHITECTURE.md §5.2; PRD §17).
 *
 * <p>Responsibility: the single error-handling chokepoint. The backend wraps errors in the envelope with
 * the stable machine code at `data.code` and field errors at `data.errors[]`; this interceptor extracts
 * them so every downstream `catchError` receives a uniform {@link ApiError} (callers branch on
 * {@link ApiError.code}, never parse raw bodies). It also raises a toast for the user with the envelope's
 * already-localised `message`, except for `401` (handled by the auth interceptor's refresh) and
 * validation errors (the form shows those inline) — avoiding double/duplicate messaging.</p>
 *
 * <p>Security: only the server-provided localised `message` is shown — never a stack trace or raw body
 * (PRD §18). A transport failure (offline/CORS/timeout, `status === 0`) becomes a `NETWORK_ERROR`
 * {@link ApiError} with a generic, low-data-friendly message.</p>
 *
 * @param req the outbound request.
 * @param next the next handler.
 * @returns the response stream with errors mapped to {@link ApiError}.
 */
export const apiResponseInterceptor: HttpInterceptorFn = (req, next) => {
  const toast = inject(ToastService);

  return next(req).pipe(
    catchError((error: unknown) => {
      const apiError = toApiError(error);

      // Don't toast for: 401 (auth interceptor handles refresh/redirect) or validation (shown inline).
      const suppressToast = apiError.statusCode === 401 || apiError.isValidation;
      if (!suppressToast) {
        toast.error(apiError.message);
      }
      return throwError(() => apiError);
    }),
  );
};

/** Maps a raw `HttpErrorResponse` (or any thrown value) into a uniform {@link ApiError}. */
function toApiError(error: unknown): ApiError {
  if (!(error instanceof HttpErrorResponse)) {
    return new ApiError('UNKNOWN', 'Hitilafu isiyojulikana. Jaribu tena.', 0);
  }

  // Transport-level failure: no server reached (offline, DNS, CORS, timeout).
  if (error.status === 0) {
    return new ApiError(
      'NETWORK_ERROR',
      'Mtandao haupatikani. Angalia muunganisho wako.',
      0,
    );
  }

  // Envelope error: { success:false, statusCode, message, data: { code, errors[] } }.
  const body = error.error as ApiResponse<{ code: string; errors?: never[] }> | undefined;
  if (body && typeof body === 'object' && 'data' in body && body.data && typeof body.data === 'object') {
    const apiErr = body.data as { code?: string; errors?: never[] };
    return new ApiError(
      apiErr.code ?? 'UNKNOWN',
      body.message ?? defaultMessageFor(error.status),
      body.statusCode ?? error.status,
      apiErr.errors ?? [],
    );
  }

  // Non-envelope error (e.g. a gateway/proxy 502 HTML page) — fall back to status-based defaults.
  return new ApiError('UNKNOWN', defaultMessageFor(error.status), error.status);
}

/** A generic Swahili-first message keyed to the HTTP status when the server provided none. */
function defaultMessageFor(status: number): string {
  switch (status) {
    case 403:
      return 'Huna ruhusa ya kufanya kitendo hiki.';
    case 404:
      return 'Rasilimali haikupatikana.';
    case 409:
      return 'Kuna mgongano wa data. Onyesha upya na ujaribu tena.';
    case 429:
      return 'Maombi mengi sana. Subiri kidogo kisha ujaribu tena.';
    case 500:
    case 502:
    case 503:
      return 'Hitilafu ya seva. Jaribu tena baadaye.';
    default:
      return 'Ombi limeshindikana. Jaribu tena.';
  }
}
