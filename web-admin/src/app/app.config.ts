import { HttpBackend, provideHttpClient, withInterceptors } from '@angular/common/http';
import { APP_INITIALIZER, ApplicationConfig, inject, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { TranslateLoader, TranslateModule } from '@ngx-translate/core';

import { routes } from './app.routes';
import { apiResponseInterceptor } from './core/interceptors/api-response.interceptor';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { LocaleService } from './core/i18n/locale.service';
import { createTranslateLoader } from './core/i18n/translate-loader';

/**
 * Root application configuration (standalone bootstrap — Angular 18).
 *
 * <p>Responsibility: wires the cross-cutting providers ONCE: the router (with component input binding for
 * typed route params and scroll restoration for accessibility), the HTTP client with the ordered
 * interceptor chain ({@link authInterceptor} first to attach the bearer + `Accept-Language` and refresh,
 * then {@link apiResponseInterceptor} to normalise errors + toast), ngx-translate (EN default fallback +
 * lazy per-locale JSON loader; the active locale, Swahili, is applied by {@link LocaleService}), and an
 * {@link APP_INITIALIZER} that boots the locale before the app renders so the correct language is active
 * on first paint.</p>
 *
 * <p>WHY interceptor order matters: the auth interceptor owns the 401-refresh-retry, so registering it
 * first puts it outermost on the response path — refresh is attempted before the error is converted to a
 * user-facing {@link ApiError} by the response interceptor.</p>
 *
 * <p>WHY {@code TranslateModule.forRoot(...).providers}: @ngx-translate/core v15 exposes its providers via
 * the module's static {@code forRoot}; spreading its `providers` registers the translate service +
 * pipe/directive app-wide in a standalone app (no NgModule).</p>
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
    // Initialise the locale (Swahili default) before first render so the UI is never momentarily English.
    {
      provide: APP_INITIALIZER,
      multi: true,
      useFactory: () => {
        const locale = inject(LocaleService);
        return () => locale.init();
      },
    },
  ],
};
