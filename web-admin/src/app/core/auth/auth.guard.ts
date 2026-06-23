import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Functional route guard that protects the authenticated admin shell (ARCHITECTURE.md §6.2).
 *
 * <p>Responsibility: a CLIENT-SIDE convenience gate that redirects unauthenticated users to the login
 * page (preserving the attempted URL for post-login return). It is NOT a security boundary — the
 * backend's method-level `@PreAuthorize` is the real gate; this only saves a round-trip and renders a
 * sensible UX. Applied to every feature route under the shell.</p>
 *
 * @returns `true` when a session exists; otherwise a `UrlTree` redirecting to `/login`.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  // Redirect to login, remembering where the user was heading so we can return there after login.
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
