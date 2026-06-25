import { Injectable } from '@angular/core';

import { TokenPair } from './auth.models';

/**
 * Persists the JWT pair across page reloads (and across app re-opens, for an installed PWA).
 *
 * <p>Responsibility: the single owner of where tokens live. Uses `localStorage` so a citizen who installed
 * the PWA stays logged in between sessions (important on a phone they re-open many times a day); the
 * rotating single-use refresh token limits the blast radius if a token leaks (the backend revokes the
 * family on reuse). Storage keys are namespaced (`taarifu.citizen.*`) to avoid clashing with the admin
 * console if both are ever served from the same origin.</p>
 */
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  private static readonly ACCESS_KEY = 'taarifu.citizen.accessToken';
  private static readonly REFRESH_KEY = 'taarifu.citizen.refreshToken';

  /** @returns the stored access token, or `null` if not logged in. */
  getAccessToken(): string | null {
    return localStorage.getItem(TokenStorageService.ACCESS_KEY);
  }

  /** @returns the stored refresh token, or `null` if not logged in. */
  getRefreshToken(): string | null {
    return localStorage.getItem(TokenStorageService.REFRESH_KEY);
  }

  /**
   * Stores a freshly issued/rotated token pair.
   * @param tokens the access + refresh pair to persist.
   */
  save(tokens: TokenPair): void {
    localStorage.setItem(TokenStorageService.ACCESS_KEY, tokens.accessToken);
    localStorage.setItem(TokenStorageService.REFRESH_KEY, tokens.refreshToken);
  }

  /** Clears all tokens (logout, or after an unrecoverable auth failure). */
  clear(): void {
    localStorage.removeItem(TokenStorageService.ACCESS_KEY);
    localStorage.removeItem(TokenStorageService.REFRESH_KEY);
  }
}
