import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { map, tap } from 'rxjs/operators';

import { ApiClient } from '../api/api-client.service';
import { ApiError } from '../api/api-error';
import { LoginResult, PasswordLoginRequest, RefreshRequest, Session, TokenPair } from './auth.models';
import { isTokenExpired, sessionFromToken } from './jwt.util';
import { TokenStorageService } from './token-storage.service';

/**
 * Central authentication state + flows for the admin console (AUTH-DESIGN §14.1; AuthController).
 *
 * <p>Responsibility: owns login (`/auth/login/password`), token refresh (`/auth/refresh`), logout, and
 * the reactive {@link session} signal that drives UI gating. It rehydrates the session from
 * {@link TokenStorageService} on construction so a page reload keeps the admin logged in.</p>
 *
 * <p>Security notes (ARCHITECTURE.md §6.2): the decoded tier/roles exposed via {@link session} are a UI
 * convenience only — the server re-checks every protected action. We never persist the password; tokens
 * are stored by {@link TokenStorageService}; raw tokens are never logged.</p>
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiClient);
  private readonly storage = inject(TokenStorageService);

  /** Reactive current session (signal); `null` when logged out. UI reads this for gating. */
  private readonly sessionSignal = signal<Session | null>(this.restoreSession());

  /** Read-only view of the current session signal. */
  readonly session = this.sessionSignal.asReadonly();

  /** Derived flag: whether a (non-expired) session exists. */
  readonly isAuthenticated = computed(() => this.sessionSignal() !== null);

  /**
   * Logs in with phone/email + password. On a non-MFA admin account this stores the issued tokens and
   * publishes the session. For an MFA-gated staff account the server returns `mfaRequired=true` and NO
   * tokens — the caller must drive the TOTP step (out of scope for this foundation slice; surfaced as a
   * clear message). The raw password never leaves this call.
   *
   * @param request the account key + password.
   * @returns the {@link LoginResult}; emits an {@link ApiError} on failure (normalised by the interceptor).
   */
  loginWithPassword(request: PasswordLoginRequest): Observable<LoginResult> {
    return this.api.post<LoginResult, PasswordLoginRequest>('/auth/login/password', request).pipe(
      tap((result) => {
        if (result.tokens) {
          this.applyTokens(result.tokens);
        }
      }),
    );
  }

  /**
   * Rotates the refresh token to obtain a fresh access token (single-use; the backend revokes the family
   * on reuse — AUTH-DESIGN §5.2). Used by the auth interceptor on a 401 and at startup if the access
   * token is expired.
   *
   * @returns the new token pair; errors if there is no stored refresh token or rotation fails.
   */
  refresh(): Observable<TokenPair> {
    const refreshToken = this.storage.getRefreshToken();
    if (!refreshToken) {
      return throwError(() => new ApiError('UNAUTHENTICATED', 'No refresh token', 401));
    }
    return this.api
      .post<TokenPair, RefreshRequest>('/auth/refresh', { refreshToken })
      .pipe(tap((tokens) => this.applyTokens(tokens)));
  }

  /**
   * Logs out: best-effort server revocation of the refresh token, then local clear regardless of the
   * server result (so the user is always logged out client-side even if the network is down).
   *
   * @returns `void` once local state is cleared.
   */
  logout(): Observable<void> {
    const refreshToken = this.storage.getRefreshToken();
    this.clearSession();
    if (!refreshToken) {
      // Already clean; nothing to revoke.
      return new Observable<void>((sub) => {
        sub.next();
        sub.complete();
      });
    }
    return this.api.post<void, RefreshRequest>('/auth/logout', { refreshToken }).pipe(map(() => undefined));
  }

  /** @returns the current access token, or `null` — used by the auth interceptor to attach the header. */
  getAccessToken(): string | null {
    return this.storage.getAccessToken();
  }

  /** @returns true if there is a stored access token and it has expired (drives pre-emptive refresh). */
  isAccessTokenExpired(): boolean {
    const token = this.storage.getAccessToken();
    return token !== null && isTokenExpired(token);
  }

  /** @returns true if the current session holds the given role (UI gating hint only). */
  hasRole(role: string): boolean {
    return this.sessionSignal()?.roles.includes(role) ?? false;
  }

  /** Clears tokens + session (used by the interceptor when refresh is impossible). */
  clearSession(): void {
    this.storage.clear();
    this.sessionSignal.set(null);
  }

  /** Persists tokens and republishes the decoded session. */
  private applyTokens(tokens: TokenPair): void {
    this.storage.save(tokens);
    this.sessionSignal.set(sessionFromToken(tokens.accessToken));
  }

  /** Rebuilds the session from a stored access token at startup; `null` if absent/unreadable. */
  private restoreSession(): Session | null {
    const token = this.storage.getAccessToken();
    return token ? sessionFromToken(token) : null;
  }
}
