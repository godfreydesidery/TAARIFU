import { HttpBackend, HttpClient } from '@angular/common/http';
import { TranslateHttpLoader } from '@ngx-translate/http-loader';

/**
 * Factory for ngx-translate's HTTP loader: loads `/i18n/{lang}.json` static dictionaries.
 *
 * <p>Responsibility: externalises ALL user-facing strings into per-locale JSON (CLAUDE.md §8), served
 * from `public/i18n/`. Lazy HTTP loading keeps each locale's dictionary out of the initial JS bundle
 * (PRD §15 bundle budget) — only the active language is fetched. The SW→EN fallback is configured on the
 * `TranslateService` default lang (see {@link LocaleService}).</p>
 *
 * <p>WHY a bare HttpClient from HttpBackend: it BYPASSES the interceptor chain. Going through the
 * interceptors pulls authInterceptor → LocaleService → TranslateService → this loader → HttpClient — a DI
 * cycle (NG0200) that prevents translations from ever loading. Absolute `/i18n/` (not `./i18n/`) so deep
 * routes resolve to `public/i18n/`, not `/<route>/i18n/`.</p>
 *
 * @param handler the low-level HTTP backend (no interceptors).
 * @returns a configured {@link TranslateHttpLoader}.
 */
export function createTranslateLoader(handler: HttpBackend): TranslateHttpLoader {
  return new TranslateHttpLoader(new HttpClient(handler), '/i18n/', '.json');
}
