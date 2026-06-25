import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

import { PageMeta } from '../../core/api/api-response.model';
import { ToastService } from '../../core/notifications/toast.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { StatePanelComponent } from '../../shared/components/state-panel.component';
import { SkeletonTableComponent } from '../../shared/components/skeleton.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge.component';
import { statusTone } from '../../shared/util/status-tone.util';
import { DSR_STATUSES, DSR_TYPES, DsrRequest } from './privacy.models';
import { PrivacyService } from './privacy.service';

/**
 * DSR (data-subject-request) admin queue — PDPA access/erasure handling (UC-A17 / UC-S09; PRD §25.1, §18).
 *
 * <p>Responsibility: the operator console for Tanzania PDPA data-subject requests. It lists requests paged
 * from {@code GET /privacy/dsr} with server-side filters by type (ACCESS/ERASURE) and status, flags those
 * overdue against the §25.1 ≤30-day SLA, and lets the operator action each one inline — acknowledge (starts
 * the SLA clock), complete (export delivered / anonymisation done), or place on HOLD with a reason (suspends
 * erasure until released, §25.1). The actual export/anonymisation job is server-side (UC-S09); the console
 * only records the human decision. The detail view reuses the row already loaded in the list (the backend
 * exposes no admin GET-by-id), so there is no separate per-request fetch.
 * Authorization is enforced SERVER-side (ADMIN/ROOT). Subjects are shown PII-minimised — public id + masked
 * contact only, never raw PII (defeating the erasure would be self-defeating, §18). Loading/empty/error
 * states are handled; the screen degrades a missing endpoint to a friendly error panel; subscriptions use
 * {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-dsr-list',
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    TranslateModule,
    PaginationComponent,
    StatePanelComponent,
    SkeletonTableComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './dsr-list.component.html',
})
export class DsrListComponent implements OnInit {
  private readonly privacy = inject(PrivacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** List UI state. */
  readonly rows = signal<DsrRequest[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** The id of a request whose action is currently in flight (disables its row controls). */
  readonly acting = signal<string | null>(null);

  /** Server-side filter state. */
  readonly typeFilter = signal('');
  readonly statusFilter = signal('');

  /** Selectable tokens for the filter selects. */
  readonly types = DSR_TYPES;
  readonly statuses = DSR_STATUSES;

  private readonly pageSize = 20;

  /** Whether any filter is active (drives a "clear" affordance + empty-state hint). */
  readonly hasFilters = computed(() => !!this.typeFilter() || !!this.statusFilter());

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of DSRs for the active filters.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.privacy
      .list({
        type: this.typeFilter() || undefined,
        status: this.statusFilter() || undefined,
        page,
        size: this.pageSize,
      })
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

  /** Re-queries from page 0 when a filter changes. */
  applyFilters(): void {
    this.loadPage(0);
  }

  /** Acknowledges a request (starts the SLA clock), then refreshes the page. */
  acknowledge(req: DsrRequest): void {
    this.act(req.id, this.privacy.acknowledge(req.id), 'privacy.acknowledged');
  }

  /** Marks a request complete (export delivered / anonymisation done), then refreshes. */
  complete(req: DsrRequest): void {
    if (!confirm(this.translate.instant('privacy.confirmComplete'))) {
      return;
    }
    this.act(req.id, this.privacy.complete(req.id), 'privacy.completed');
  }

  /** Places a request on legal HOLD with a prompted machine reason code, then refreshes. */
  hold(req: DsrRequest): void {
    const reasonCode = (prompt(this.translate.instant('privacy.holdPrompt')) || '').trim();
    if (!reasonCode) {
      return;
    }
    this.act(req.id, this.privacy.hold(req.id, reasonCode), 'privacy.held');
  }

  /**
   * Shared inline-action runner: marks the row busy, runs the mutation, toasts the localised outcome, and
   * reloads the current page so the new status/SLA is authoritative (server-truth, not an optimistic guess).
   */
  private act(id: string, action$: Observable<DsrRequest>, successKey: string): void {
    this.acting.set(id);
    action$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.acting.set(null);
        this.toast.success(this.translate.instant(successKey));
        this.loadPage(this.meta()?.page ?? 0);
      },
      // The error interceptor already toasts a user-safe message; just clear the busy state.
      error: () => this.acting.set(null),
    });
  }

  /** Whether a request can still be acknowledged (only a fresh PENDING request). */
  canAcknowledge(req: DsrRequest): boolean {
    return req.status === 'PENDING';
  }

  /** Whether a request can be completed (acknowledged/in-progress and not on hold). */
  canComplete(req: DsrRequest): boolean {
    return (req.status === 'ACKNOWLEDGED' || req.status === 'IN_PROGRESS') && !req.legalHold;
  }

  /**
   * Whether a request can still be placed on HOLD: any non-terminal status that is not already on hold.
   * A completed or rejected (terminal) request, or one already ON_HOLD, cannot be (re-)held.
   */
  canHold(req: DsrRequest): boolean {
    return req.status !== 'COMPLETED' && req.status !== 'REJECTED' && req.status !== 'ON_HOLD';
  }

  /**
   * Whether a request is OVERDUE: still open (not completed/rejected) and past its §25.1 SLA `dueAt`.
   * Drives the row's overdue badge so an operator can triage the backlog.
   */
  isOverdue(req: DsrRequest): boolean {
    if (!req.dueAt || req.status === 'COMPLETED' || req.status === 'REJECTED') {
      return false;
    }
    return new Date(req.dueAt).getTime() < Date.now();
  }

  /** Maps a DSR status token to a badge tone (shared design-system mapping). */
  tone = statusTone;
}
