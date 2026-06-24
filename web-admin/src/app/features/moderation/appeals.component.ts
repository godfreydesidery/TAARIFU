import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { APPEAL_OUTCOMES, APPEAL_STATUSES, AppealSummary } from './moderation.models';
import { ModerationService } from './moderation.service';

/**
 * Appeals queue + decision surface (PRD §25.8, UC-H03).
 *
 * <p>Responsibility: lists the moderator appeals queue from {@code GET /moderation/appeals} (filterable by
 * status, newest first) and lets an independent moderator decide an OPEN appeal inline (UPHELD/OVERTURNED +
 * optional note). The backend enforces <b>appeal independence</b> — the decider must differ from the
 * moderator who took the original action (§25.8) — and surfaces a violation as a CONFLICT toast. After a
 * decision the queue reloads so the row reflects the server's authoritative outcome. Rows are
 * moderator-only and carry no moderated content, appellant PII, or appeal grounds (the appellant is an
 * opaque account id). Loading/empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-appeals',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, TranslateModule, PaginationComponent],
  templateUrl: './appeals.component.html',
})
export class AppealsComponent implements OnInit {
  private readonly moderation = inject(ModerationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** List UI state. */
  readonly rows = signal<AppealSummary[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly deciding = signal(false);

  /** Status filter (server-side; default OPEN — the actionable backlog). */
  readonly statusFilter = signal('OPEN');

  /** The appeal currently expanded for a decision (or null). */
  readonly activeAppealId = signal<string | null>(null);

  /** Decision form fields. */
  readonly outcome = signal('UPHELD');
  readonly decisionNote = signal('');

  /** Selectable tokens. */
  readonly statuses = APPEAL_STATUSES;
  readonly outcomes = APPEAL_OUTCOMES;

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of the appeals queue for the current status filter.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.moderation
      .listAppeals({ status: this.statusFilter() || undefined, page, size: this.pageSize, sort: 'createdAt,desc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Re-loads from page 0 when the status filter changes. */
  onStatusChange(status: string): void {
    this.statusFilter.set(status);
    this.activeAppealId.set(null);
    this.loadPage(0);
  }

  /**
   * Expands/collapses the decision panel for an appeal.
   * @param appealId the appeal id.
   */
  toggleDecide(appealId: string): void {
    this.activeAppealId.update((current) => (current === appealId ? null : appealId));
    this.outcome.set('UPHELD');
    this.decisionNote.set('');
  }

  /** Submits the decision for the active appeal, then reloads. No-ops while deciding. */
  decide(): void {
    const appealId = this.activeAppealId();
    if (!appealId || this.deciding()) {
      return;
    }
    this.deciding.set(true);
    this.moderation
      .decideAppeal(appealId, { outcome: this.outcome(), decisionNote: this.decisionNote().trim() || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.deciding.set(false);
          this.toast.success(this.translate.instant('moderation.appealDecided'));
          this.activeAppealId.set(null);
          this.loadPage(this.meta()?.page ?? 0);
        },
        error: () => this.deciding.set(false),
      });
  }
}
