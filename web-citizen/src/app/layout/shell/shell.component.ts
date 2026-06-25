import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from '../../core/auth/auth.service';
import { DraftQueueService } from '../../features/report/draft-queue.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { OfflineBannerComponent } from '../../shared/offline-banner.component';

/**
 * The citizen app shell: a compact top bar (brand + language toggle + account), the offline banner, the
 * routed content, and a fixed bottom navigation.
 *
 * <p>Responsibility: the single chrome for the whole citizen experience, designed mobile-first for
 * one-handed use on a low-end Android (persona P1 Amina). The bottom nav uses icon+label items (low-literacy
 * friendly, WCAG); the language toggle flips SW↔EN; the "file a report" action is the visually dominant
 * centre item. A draft badge on the bottom nav reflects the offline queue. The header stays minimal to
 * preserve data and vertical space.</p>
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslateModule, OfflineBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly locale = inject(LocaleService);
  private readonly auth = inject(AuthService);
  /** Draft queue — exposes the pending count for the nav badge. */
  protected readonly drafts = inject(DraftQueueService);

  /** Active locale code, shown on the toggle (e.g. `SW`). */
  protected readonly localeLabel = computed(() => this.locale.locale().toUpperCase());

  /** Whether a citizen is signed in (controls the account/login affordance). */
  protected readonly isAuthed = this.auth.isAuthenticated;

  /** Flips the UI language SW↔EN. */
  protected toggleLanguage(): void {
    this.locale.toggle();
  }
}
