import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { StatePanelComponent } from '../../shared/components/state-panel.component';
import { SkeletonTableComponent } from '../../shared/components/skeleton.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge.component';
import { statusTone } from '../../shared/util/status-tone.util';
import {
  MODERATION_ACTION_TYPES,
  MODERATION_STATUSES,
  ModerationItem,
} from './moderation.models';
import { ModerationService } from './moderation.service';

/**
 * Moderation flag queue + take-action (PRD §18; US-12.2, UC-H01/H02).
 *
 * <p>Responsibility: lists the prioritised moderation queue for a chosen status (severity + SLA ordered
 * server-side) and lets a moderator take an action on a selected item via an inline panel (action type +
 * required reason code + optional note). The conflict-of-interest guard (a moderator may not action their
 * own content, D16) is enforced SERVER-side and surfaces as a CONFLICT toast. Loading/empty/error states
 * are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-moderation-queue',
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    RouterLink,
    RouterLinkActive,
    TranslateModule,
    PaginationComponent,
    StatePanelComponent,
    SkeletonTableComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './moderation-queue.component.html',
})
export class ModerationQueueComponent implements OnInit {
  private readonly moderation = inject(ModerationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** List UI state. */
  readonly rows = signal<ModerationItem[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly acting = signal(false);

  /** Status filter (server-side). */
  readonly statusFilter = signal('PENDING');

  /** The item currently expanded for an action (or null). */
  readonly activeItemId = signal<string | null>(null);

  /** Action form fields. */
  readonly actionType = signal('DISMISS');
  readonly reasonCode = signal('');
  readonly note = signal('');

  /** Selectable tokens. */
  readonly statuses = MODERATION_STATUSES;
  readonly actionTypes = MODERATION_ACTION_TYPES;

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of the queue for the current status filter.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.moderation
      .listQueue({ status: this.statusFilter(), page, size: this.pageSize, sort: 'severity,desc' })
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
    this.activeItemId.set(null);
    this.loadPage(0);
  }

  /**
   * Expands/collapses the action panel for an item.
   * @param itemId the queue item id.
   */
  toggleAction(itemId: string): void {
    this.activeItemId.update((current) => (current === itemId ? null : itemId));
    this.reasonCode.set('');
    this.note.set('');
    this.actionType.set('DISMISS');
  }

  /**
   * Whether an item's SLA is breached (past due, not yet actioned/dismissed).
   * @param item the queue item.
   */
  slaBreached(item: ModerationItem): boolean {
    return (
      !!item.slaDueAt &&
      Date.parse(item.slaDueAt) < Date.now() &&
      !['ACTIONED', 'DISMISSED'].includes(item.status)
    );
  }

  /** Submits the action for the active item, then reloads. No-ops without a reason code or while acting. */
  submitAction(): void {
    const itemId = this.activeItemId();
    const reasonCode = this.reasonCode().trim();
    if (!itemId || !reasonCode || this.acting()) {
      return;
    }
    this.acting.set(true);
    this.moderation
      .takeAction(itemId, { type: this.actionType(), reasonCode, note: this.note().trim() || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.acting.set(false);
          this.toast.success(this.translate.instant('moderation.actionTaken'));
          this.activeItemId.set(null);
          this.loadPage(this.meta()?.page ?? 0);
        },
        error: () => this.acting.set(false),
      });
  }

  /** Maps a severity/status token to a badge tone (shared design-system mapping). */
  tone = statusTone;
}
