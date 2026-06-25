import { bootstrapApplication } from '@angular/platform-browser';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

/**
 * Citizen PWA bootstrap (standalone — Angular 18). Renders {@link AppComponent} with {@link appConfig}
 * (router, HTTP + interceptors, i18n, PWA service-worker registration). Bootstrap failures are logged
 * rather than swallowed so a misconfigured environment is diagnosable.
 */
bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
