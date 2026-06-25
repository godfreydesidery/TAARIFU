import { Injectable, inject, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { environment } from '../../../environments/environment';

/** The locales the citizen PWA ships with — **Swahili-first** (PRD §14). */
export type SupportedLocale = 'sw' | 'en';

/**
 * Owns the active UI locale and bridges it to ngx-translate + the backend's `Accept-Language` header.
 *
 * <p>Responsibility: single source of truth for the current language. **Swahili is the default**; English
 * is the fallback (the SW → EN → key chain is configured on {@link TranslateService}). The chosen locale
 * is persisted so it survives reloads, and exposed as a signal so the language switch updates reactively.
 * The {@link AuthInterceptor} reads {@link current} to set `Accept-Language` so the server returns
 * localised envelope messages in the citizen's language.</p>
 */
@Injectable({ providedIn: 'root' })
export class LocaleService {
  private static readonly STORAGE_KEY = 'taarifu.citizen.locale';
  private readonly translate = inject(TranslateService);

  /** Reactive active locale; drives the header switcher and `<html lang>`. */
  private readonly localeSignal = signal<SupportedLocale>(this.initialLocale());

  /** Read-only view of the active locale signal. */
  readonly locale = this.localeSignal.asReadonly();

  /**
   * Configures ngx-translate with the supported locales, the SW→EN fallback, and the active locale.
   * Called once from the app bootstrap (APP_INITIALIZER) so the right language is live on first paint.
   */
  init(): void {
    this.translate.addLangs(['sw', 'en']);
    // Fallback chain: a key missing in the active locale falls back to English, then to the raw key.
    this.translate.setDefaultLang(environment.fallbackLocale);
    const active = this.localeSignal();
    this.translate.use(active);
    document.documentElement.lang = active;
  }

  /** @returns the active locale (e.g. `'sw'`), used for `Accept-Language`. */
  get current(): SupportedLocale {
    return this.localeSignal();
  }

  /**
   * Switches the active locale, applies it to ngx-translate, persists it, and updates `<html lang>`.
   * @param locale the locale to activate.
   */
  use(locale: SupportedLocale): void {
    this.localeSignal.set(locale);
    this.translate.use(locale);
    document.documentElement.lang = locale;
    localStorage.setItem(LocaleService.STORAGE_KEY, locale);
  }

  /** Toggles between Swahili and English (the header's single language button). */
  toggle(): void {
    this.use(this.current === 'sw' ? 'en' : 'sw');
  }

  /** Resolves the startup locale: persisted choice → configured default (Swahili). */
  private initialLocale(): SupportedLocale {
    const saved = localStorage.getItem(LocaleService.STORAGE_KEY);
    if (saved === 'sw' || saved === 'en') {
      return saved;
    }
    return environment.defaultLocale as SupportedLocale;
  }
}
