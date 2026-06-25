import { Injectable, inject } from '@angular/core';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { TranslateService } from '@ngx-translate/core';
import { filter } from 'rxjs/operators';

import { ToastService } from '../notifications/toast.service';

/**
 * Watches for a new service-worker version and prompts the citizen to update.
 *
 * <p>Responsibility: PWA version hygiene. When the SW detects a freshly deployed app version it emits a
 * `VERSION_READY` event; we surface a localised "new version available" toast and reload to activate it.
 * Keeping this in one place means the force-update / min-version policy lives in a single spot. No-ops when
 * the service worker is disabled (dev builds), so it is safe to construct unconditionally.</p>
 */
@Injectable({ providedIn: 'root' })
export class PwaUpdateService {
  private readonly swUpdate = inject(SwUpdate);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Subscribes to version-ready events; called once at app start from {@link AppComponent}. */
  init(): void {
    if (!this.swUpdate.isEnabled) {
      return;
    }
    this.swUpdate.versionUpdates
      .pipe(filter((e): e is VersionReadyEvent => e.type === 'VERSION_READY'))
      .subscribe(() => {
        this.toast.info(this.translate.instant('pwa.updateAvailable'));
        // Give the toast a moment to be seen, then reload to activate the new SW.
        setTimeout(() => document.location.reload(), 1500);
      });
  }
}
