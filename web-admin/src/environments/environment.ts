/**
 * Production environment configuration.
 *
 * <p>Single source of truth for environment-dependent values. The {@link apiUrl} is the ONLY place a
 * backend base URL is declared — no component, service, or interceptor hardcodes a URL (CLAUDE.md §8:
 * "no hardcoded URLs"). Swap this file at build time via Angular's `fileReplacements` to point a
 * production build at the in-country host.</p>
 */
export const environment = {
  /** Whether this is a production build (enables prod optimisations, silences dev-only logs). */
  production: true,

  /**
   * Backend API base URL. Includes the server's `/api/v1` context-path so every relative call in the
   * app appends only the resource path (e.g. `${apiUrl}/regions`). Matches the Spring Boot server
   * (`server.port=8081`, `server.servlet.context-path=/api/v1`).
   */
  apiUrl: 'http://localhost:8081/api/v1',

  /** Default UI locale (Swahili-first per PRD §14, CLAUDE.md §5). */
  defaultLocale: 'sw',

  /** Fallback locale used when a key is missing in the active locale (SW → EN → key chain). */
  fallbackLocale: 'en',
};
