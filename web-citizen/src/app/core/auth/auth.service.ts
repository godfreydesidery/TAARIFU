import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { map, tap } from 'rxjs/operators';

import { ApiClient } from '../api/api-client.service';
import { ApiError } from '../api/api-error';
import {
  AuthResult,
  LoginResult,
  OtpChallenge,
  OtpRequest,
  RefreshRequest,
  Session,
  TokenPair,
  VerifyOtpRequest,
} from './auth.models';
import { isTokenExpired, sessionFromToken } from './jwt.util';
import { TokenStorageService } from './token-storage.service';

/**
 * Central authentication state + phone/OTP flows for the citizen PWA (AuthController, AUTH-DESIGN §3/§4).
 *
 * <p>Responsibility: owns the passwordless citizen journey — request a signup/login OTP, verify it to
 * obtain tokens, refresh, and logout — plus the reactive {@link session} signal that drives UI gating
 * (e.g. the tier-unlock prompt). It rehydrates the session from {@link TokenStorageService} on
 * construction so re-opening the installed PWA keeps the citizen logged in.</p>
 *
 * <p>Security notes: the decoded tier/roles exposed via {@link session} are a UI convenience only — the
 * server re-checks every protected action. OTP codes and raw tokens are never logged. The signup/login
 * OTP-request endpoints are non-committal (always 202) so the UI must NOT reveal whether a phone already
 * has an account (anti-enumeration).</p>
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiClient);
  private readonly storage = inject(TokenStorageService);

  /** Reactive current session (signal); `null` when logged out. UI reads this for gating. */
  private readonly sessionSignal = signal<Session | null>(this.restoreSession());

  /** Read-only view of the current session signal. */
  readonly session = this.sessionSignal.asReadonly();

  /** Derived flag: whether a session exists. */
  readonly isAuthenticated = computed(() => this.sessionSignal() !== null);

  /** Derived current trust tier (e.g. `T1`), or `null` — drives tier-gated UI hints. */
  readonly tier = computed(() => this.sessionSignal()?.tier ?? null);

  /**
   * Requests a SIGNUP OTP for a brand-new account. Always resolves to a challenge (the server returns 202
   * non-committally — never reveals whether the phone already exists).
   *
   * @param phone the destination phone in E.164.
   * @returns the {@link OtpChallenge} to verify against.
   */
  requestSignupOtp(phone: string): Observable<OtpChallenge> {
    return this.api.post<OtpChallenge, OtpRequest>('/auth/otp/request', { phone });
  }

  /**
   * Completes signup by verifying the OTP → creates/activates a T1 account and issues tokens, which are
   * stored and published as the session.
   *
   * @param request the challenge id + SMS code.
   * @returns the {@link AuthResult} (account id, tier, tokens).
   */
  completeSignup(request: VerifyOtpRequest): Observable<AuthResult> {
    return this.api
      .post<AuthResult, VerifyOtpRequest>('/auth/signup', request)
      .pipe(tap((result) => this.applyTokens(result.tokens)));
  }

  /**
   * Requests a LOGIN OTP for an existing account. Always resolves to a challenge (202, anti-enumeration).
   *
   * @param phone the destination phone in E.164.
   * @returns the {@link OtpChallenge} to verify against.
   */
  requestLoginOtp(phone: string): Observable<OtpChallenge> {
    return this.api.post<OtpChallenge, OtpRequest>('/auth/login/otp/request', { phone });
  }

  /**
   * Completes a passwordless login by verifying the OTP. For a citizen this issues tokens (stored +
   * published). A staff/MFA account would return `mfaRequired=true` with no tokens — the caller surfaces a
   * "use the staff console" message (the citizen PWA does not implement the TOTP step).
   *
   * @param request the challenge id + SMS code.
   * @returns the {@link LoginResult}.
   */
  completeLogin(request: VerifyOtpRequest): Observable<LoginResult> {
    return this.api.post<LoginResult, VerifyOtpRequest>('/auth/login/otp', request).pipe(
      tap((result) => {
        if (result.tokens) {
          this.applyTokens(result.tokens);
        }
      }),
    );
  }

  /**
   * Rotates the refresh token to obtain a fresh access token (single-use; reuse revokes the family). Used
   * by the auth interceptor on a 401.
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
   * server result (so the citizen is always logged out client-side even when offline).
   *
   * @returns `void` once local state is cleared.
   */
  logout(): Observable<void> {
    const refreshToken = this.storage.getRefreshToken();
    this.clearSession();
    if (!refreshToken) {
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

  /** @returns true if there is a stored access token and it has expired. */
  isAccessTokenExpired(): boolean {
    const token = this.storage.getAccessToken();
    return token !== null && isTokenExpired(token);
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
