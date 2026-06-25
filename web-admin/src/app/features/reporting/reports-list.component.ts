import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { WardPickerComponent } from '../../shared/components/ward-picker.component';
import { StatePanelComponent } from '../../shared/components/state-panel.component';
import { SkeletonTableComponent } from '../../shared/components/skeleton.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge.component';
import { statusTone } from '../../shared/util/status-tone.util';
import { IssueCategory } from '../categories/category.models';
import { CategoryService } from '../categories/category.service';
import { AdminReportSummary, REPORT_STATUSES } from './reporting.models';
import { ReportingService } from './reporting.service';

/**
 * Official report queue — the case-management list (M14, Epic M3; UC-D05).
 *
 * <p>Responsibility: lists reports paged from the owner-grade `GET /admin/reports` and lets the operator
 * narrow by status, issue category, area (ward), and an SLA-breached toggle — all applied SERVER-side, so
 * the filtered counts are authoritative and the queue scales past a single page. A free-text box additionally
 * filters the current page by code/title client-side (the admin queue has no text filter). Each row links to
 * the staff case detail. Rows are PII-minimised (no reporter identity, no precise geo, PRD §18). Loading/
 * empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-reports-list',
  standalone: true,
  imports: [
    RouterLink,
    FormsModule,
    DatePipe,
    TranslateModule,
    PaginationComponent,
    WardPickerComponent,
    StatePanelComponent,
    SkeletonTableComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './reports-list.component.html',
})
export class ReportsListComponent implements OnInit {
  private readonly reporting = inject(ReportingService);
  private readonly categories = inject(CategoryService);
  private readonly destroyRef = inject(DestroyRef);

  /** List UI state. */
  readonly rows = signal<AdminReportSummary[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** Server-side filter state. */
  readonly statusFilter = signal('');
  readonly categoryFilter = signal('');
  readonly areaFilter = signal('');
  readonly slaBreachedOnly = signal(false);

  /** Client-side text filter (over the current page; the admin queue has no text filter). */
  readonly searchTerm = signal('');

  /** Selectable status tokens for the filter. */
  readonly statuses = REPORT_STATUSES;

  /** Active categories for the category filter (fetched once; small, stable taxonomy). */
  readonly categoryOptions = signal<IssueCategory[]>([]);

  private readonly pageSize = 20;

  /** The current page filtered by the client-side text term. */
  readonly filteredRows = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    if (!term) {
      return this.rows();
    }
    return this.rows().filter((r) => `${r.code} ${r.title}`.toLowerCase().includes(term));
  });

  /** Loads the category options + first page on init. */
  ngOnInit(): void {
    this.loadCategories();
    this.loadPage(0);
  }

  /** Loads the active category taxonomy for the filter (best-effort; non-fatal to the queue). */
  private loadCategories(): void {
    this.categories
      .listActive({ page: 0, size: 100, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.categoryOptions.set(page.content),
        error: () => this.categoryOptions.set([]),
      });
  }

  /**
   * Loads a page of reports for the active server-side filters.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.reporting
      .listQueue({
        status: this.statusFilter() || undefined,
        categoryId: this.categoryFilter() || undefined,
        areaId: this.areaFilter() || undefined,
        slaBreached: this.slaBreachedOnly() ? true : undefined,
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

  /** Re-queries from page 0 when any server-side filter changes. */
  applyFilters(): void {
    this.loadPage(0);
  }

  /** Maps a status/priority token to a badge tone (shared design-system mapping). */
  tone = statusTone;
}
