import { Injectable } from '@angular/core';

import { TokenPair } from './auth.models';

/**
 * Persists the JWT pair across page reloads.
 *
 * <p>Responsibility: the single owner of where tokens live. Uses `localStorage` so an admin staying on
 * the console survives a refresh without re-login; the rotating single-use refresh token limits the
 * blast radius if a token leaks (the backend revokes the family on reuse — AUTH-DESIGN §5.2).</p>
 *
 * <p>WHY a dedicated service (not direct `localStorage` calls scattered around): one chokepoint means we
 * can later swap to an in-memory + httpOnly-cookie strategy for hardened deployments without touching
 * callers. Storage keys are namespaced to avoid clashes with other apps on the same origin.</p>
 */
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  private static readonly ACCESS_KEY = 'taarifu.admin.accessToken';
  private static readonly REFRESH_KEY = 'taarifu.admin.refreshToken';

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
