import { HttpBackend, provideHttpClient, withInterceptors } from '@angular/common/http';
import { APP_INITIALIZER, ApplicationConfig, inject, isDevMode, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { TranslateLoader, TranslateModule } from '@ngx-translate/core';

import { routes } from './app.routes';
import { apiResponseInterceptor } from './core/interceptors/api-response.interceptor';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { createTranslateLoader } from './core/i18n/translate-loader';
import { LocaleService } from './core/i18n/locale.service';

/**
 * Root application configuration for the citizen PWA (standalone bootstrap — Angular 18).
 *
 * <p>Responsibility: wires the cross-cutting providers ONCE: the router (component-input-binding for typed
 * route params + scroll restoration for accessibility), the HTTP client with the ordered interceptor chain
 * ({@link authInterceptor} first to attach the bearer + `Accept-Language` and refresh, then
 * {@link apiResponseInterceptor} to normalise errors + toast), ngx-translate (EN default fallback + lazy
 * per-locale JSON; the active locale — **Swahili** — applied by {@link LocaleService}), an
 * {@link APP_INITIALIZER} that boots the locale before first paint, and the **PWA service worker**
 * (registered after the app is stable; disabled in dev).</p>
 *
 * <p>WHY interceptor order matters: the auth interceptor owns the 401-refresh-retry, so registering it
 * first puts it outermost on the response path — refresh is attempted before the error is converted to a
 * user-facing {@link ApiError}.</p>
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
    ),
    provideHttpClient(withInterceptors([authInterceptor, apiResponseInterceptor])),
    ...(TranslateModule.forRoot({
      defaultLanguage: 'en',
      loader: {
        provide: TranslateLoader,
        useFactory: createTranslateLoader,
        deps: [HttpBackend],
      },
    }).providers ?? []),
    // Initialise the locale (Swahili default for the citizen app) before first render so the UI never
    // flashes the wrong language.
    {
      provide: APP_INITIALIZER,
      multi: true,
      useFactory: () => {
        const locale = inject(LocaleService);
        return () => locale.init();
      },
    },
    // Register the @angular/service-worker. Disabled in dev so live-reload works; in production it is
    // registered once the app is stable so it never competes with first paint (PRD §15). The SW serves the
    // offline app shell + the read-cache configured in ngsw-config.json.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
