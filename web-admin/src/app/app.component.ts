import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { ToastContainerComponent } from './core/notifications/toast-container.component';

/**
 * Root application component.
 *
 * <p>Responsibility: the thinnest possible root — it hosts the top-level {@link RouterOutlet} (which
 * renders either the login page or the authenticated {@link ShellComponent} per the route config) and the
 * always-present {@link ToastContainerComponent} so notifications can surface from anywhere in the app.
 * All real chrome lives in the shell, keeping this component free of layout concerns.</p>
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, ToastContainerComponent],
  template: `
    <router-outlet />
    <app-toast-container />
  `,
})
export class AppComponent {}
