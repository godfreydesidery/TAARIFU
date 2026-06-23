/**
 * Development environment configuration.
 *
 * <p>Used by the default `ng serve` / `ng build --configuration development` build. Points at the local
 * Spring Boot server on `:8081`. The {@link environment.apiUrl} is the single declared backend URL
 * (CLAUDE.md §8 — no hardcoded URLs elsewhere). When serving via the bundled dev proxy
 * (`proxy.conf.json`), the proxy forwards `http://localhost:8081` for you and avoids browser CORS —
 * see web-admin/README.md.</p>
 */
export const environment = {
  /** Dev build flag — leaves verbose dev diagnostics enabled. */
  production: false,

  /** Local backend base URL including the `/api/v1` context-path. */
  apiUrl: 'http://localhost:8081/api/v1',

  /** Default UI locale (Swahili-first). */
  defaultLocale: 'sw',

  /** Fallback locale for the SW → EN → key chain. */
  fallbackLocale: 'en',
};
