/**
 * Development environment configuration for the CITIZEN PWA.
 *
 * <p>Used by the default `ng serve` / `ng build --configuration development`. {@link apiUrl} is RELATIVE
 * (`/api/v1`) so the bundled dev proxy (`proxy.conf.json` → :8081) serves it same-origin, avoiding browser
 * CORS regardless of which port `ng serve` runs on. The Spring Boot server uses
 * `server.servlet.context-path=/api/v1` on port 8081 (see web-citizen/README.md).</p>
 */
export const environment = {
  /** Dev build flag — leaves verbose dev diagnostics enabled. */
  production: false,

  /**
   * Backend base URL — RELATIVE so the bundled dev proxy (`proxy.conf.json` → :8081) serves it
   * same-origin. The Angular dev server forwards `/api/v1/*` to the local backend.
   */
  apiUrl: '/api/v1',

  /** Default UI locale — Swahili-first for the citizen app (PRD §14). */
  defaultLocale: 'sw',

  /** Fallback locale for the SW → EN → key chain. */
  fallbackLocale: 'en',
};
