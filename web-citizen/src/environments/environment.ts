/**
 * Production environment configuration for the CITIZEN PWA.
 *
 * <p>Single source of truth for environment-dependent values. {@link apiUrl} is the ONLY place a backend
 * base URL is declared — no component, service, or interceptor hardcodes a URL (CLAUDE.md §8). For the
 * citizen PWA we keep {@link apiUrl} RELATIVE so the app is deployed same-origin behind the API gateway /
 * reverse proxy (the SW also matches relative `/api/v1/**` patterns for its read-cache, ngsw-config.json).
 * Swap this file at build time via Angular `fileReplacements` if a cross-origin host is ever needed.</p>
 */
export const environment = {
  /** Whether this is a production build (enables prod optimisations, silences dev-only logs). */
  production: true,

  /**
   * Backend API base URL — RELATIVE. The citizen PWA is served same-origin with the API behind a reverse
   * proxy that exposes the Spring Boot server's `/api/v1` context-path. Relative keeps the service-worker
   * data-cache URL patterns simple and avoids CORS entirely (PRD §15, low-data/offline).
   */
  apiUrl: '/api/v1',

  /** Default UI locale — the citizen app is **Swahili-first** (PRD §14; opposite of the admin console). */
  defaultLocale: 'sw',

  /** Fallback locale for the SW → EN → key chain (PRD §14). */
  fallbackLocale: 'en',
};
