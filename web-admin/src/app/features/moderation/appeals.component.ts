import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/notifications/toast.service';
import { APPEAL_OUTCOMES, Appeal } from './moderation.models';
import { ModerationService } from './moderation.service';

/**
 * Appeals decision surface (PRD §25.8, UC-H03).
 *
 * <p>Responsibility: lets an independent moderator decide an appeal by its public id (UPHELD/OVERTURNED +
 * optional note). The backend enforces <b>appeal independence</b> — the decider must differ from the
 * moderator who took the original action (§25.8) — and surfaces a violation as a CONFLICT toast. There is
 * no list-appeals endpoint today (appeals reach a moderator via notification/audit), so this MVP surface
 * decides by id; a paged appeals queue is a CENTRAL NEED. Subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-appeals',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule],
  templateUrl: './appeals.component.html',
})
export class AppealsComponent {
  private readonly moderation = inject(ModerationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Decision form fields. */
  readonly appealId = signal('');
  readonly outcome = signal('UPHELD');
  readonly decisionNote = signal('');
  readonly deciding = signal(false);

  /** The last decided appeal (feedback after a successful decision). */
  readonly decided = signal<Appeal | null>(null);

  /** Selectable outcome tokens. */
  readonly outcomes = APPEAL_OUTCOMES;

  /** Submits the appeal decision. No-ops without an appeal id or while in flight. */
  decide(): void {
    const id = this.appealId().trim();
    if (!id || this.deciding()) {
      return;
    }
    this.deciding.set(true);
    this.decided.set(null);
    this.moderation
      .decideAppeal(id, { outcome: this.outcome(), decisionNote: this.decisionNote().trim() || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (appeal) => {
          this.deciding.set(false);
          this.decided.set(appeal);
          this.toast.success(this.translate.instant('moderation.appealDecided'));
          this.decisionNote.set('');
        },
        error: () => this.deciding.set(false),
      });
  }
}
