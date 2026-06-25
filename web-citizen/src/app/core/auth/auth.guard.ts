import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Functional route guard for the citizen routes that require a signed-in account (file a report, track my
 * reports).
 *
 * <p>Responsibility: a CLIENT-SIDE convenience gate that redirects unauthenticated citizens to the login
 * page (preserving the attempted URL for post-login return). It is NOT a security boundary — the backend's
 * method-level `@PreAuthorize` / `@RequiresTier` is the real gate; this only saves a round-trip and
 * renders a sensible UX. Public routes (feed, find-my-rep) are deliberately NOT guarded so a guest can
 * browse offline-cached public content (PRD §15).</p>
 *
 * @returns `true` when a session exists; otherwise a `UrlTree` redirecting to `/auth`.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/auth'], { queryParams: { returnUrl: state.url } });
};
