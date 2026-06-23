import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';

import { AuthService } from '../auth/auth.service';
import { LocaleService } from '../i18n/locale.service';

/** Auth/refresh endpoints that must NOT carry a (possibly stale) bearer token nor trigger a refresh loop. */
const AUTH_PATHS = ['/auth/login', '/auth/refresh', '/auth/otp', '/auth/signup'];

/**
 * Functional HTTP interceptor that (1) attaches the bearer access token and the `Accept-Language`
 * header to every API call, and (2) transparently refreshes the token once on a `401`
 * (ARCHITECTURE.md §6.1; AUTH-DESIGN §5.2).
 *
 * <p>Responsibility: the single place outbound auth headers are added, so no service hand-builds them.
 * `Accept-Language` carries the active UI locale so the backend localises the envelope `message`
 * (SW default — ADR-0010). On a `401` for a non-auth endpoint it calls `/auth/refresh` ONCE via
 * {@link AuthService.refresh}; on success it replays the original request with the new token, on failure
 * it clears the session (the {@link authGuard} then redirects to login). Auth endpoints themselves are
 * exempt to avoid an infinite refresh loop.</p>
 *
 * <p>WHY a functional interceptor: Angular 18's `withInterceptors` composes plain functions — lighter
 * and tree-shakeable versus class interceptors (PRD §15 bundle budget).</p>
 *
 * @param req the outbound request.
 * @param next the next handler in the chain.
 * @returns the (possibly retried) response stream.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const locale = inject(LocaleService);

  const isAuthEndpoint = AUTH_PATHS.some((p) => req.url.includes(p));
  const withHeaders = applyHeaders(req, locale.current, isAuthEndpoint ? null : auth.getAccessToken());

  return next(withHeaders).pipe(
    catchError((error: unknown) => {
      // Only attempt a single refresh for a 401 on a protected (non-auth) endpoint.
      if (error instanceof HttpErrorResponse && error.status === 401 && !isAuthEndpoint) {
        return refreshAndRetry(req, next, auth, locale);
      }
      return throwError(() => error);
    }),
  );
};

/** Rotates the token once and replays the original request with the fresh bearer; clears session on failure. */
function refreshAndRetry(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  locale: LocaleService,
): Observable<HttpEvent<unknown>> {
  return auth.refresh().pipe(
    switchMap((tokens) => next(applyHeaders(req, locale.current, tokens.accessToken))),
    catchError((refreshError: unknown) => {
      // Refresh failed → the session is unrecoverable; clear it so the guard sends the user to login.
      auth.clearSession();
      return throwError(() => refreshError);
    }),
  );
}

/** Clones a request adding `Accept-Language` and (when present) the bearer `Authorization` header. */
function applyHeaders(
  req: HttpRequest<unknown>,
  language: string,
  accessToken: string | null,
): HttpRequest<unknown> {
  const headers: Record<string, string> = { 'Accept-Language': language };
  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }
  return req.clone({ setHeaders: headers });
}
