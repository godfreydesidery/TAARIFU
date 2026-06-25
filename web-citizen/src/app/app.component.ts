import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { PwaUpdateService } from './core/pwa/pwa-update.service';
import { ToastContainerComponent } from './core/notifications/toast-container.component';

/**
 * Root application component for the citizen PWA.
 *
 * <p>Responsibility: the thinnest possible root — it hosts the top-level {@link RouterOutlet} (which renders
 * the auth page or the {@link ShellComponent} per the route config) and the always-present
 * {@link ToastContainerComponent}. On init it starts the {@link PwaUpdateService} so a freshly deployed
 * app version is detected and the citizen is prompted to update. All chrome lives in the shell.</p>
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
export class AppComponent implements OnInit {
  private readonly pwaUpdate = inject(PwaUpdateService);

  /** Starts the service-worker update watcher (no-op when the SW is disabled, e.g. dev builds). */
  ngOnInit(): void {
    this.pwaUpdate.init();
  }
}
