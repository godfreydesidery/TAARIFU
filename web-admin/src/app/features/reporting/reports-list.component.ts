import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { PublicReport, REPORT_STATUSES } from './reporting.models';
import { ReportingService } from './reporting.service';

/**
 * Official report queue — the case-management list (PRD Epic M3; UC-D05).
 *
 * <p>Responsibility: lists reports paged from the server and lets the operator narrow by status, free-text
 * (code/title), and an SLA-breached toggle, linking each row to the case detail. Status/text/SLA
 * filtering is applied client-side over the current page because the backend's paged report list today is
 * the public near-me endpoint (no admin queue endpoint with these server filters yet — see CENTRAL
 * NEEDS); when that endpoint lands, the filters move server-side. Loading/empty/error states are handled;
 * subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-reports-list',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe, TranslateModule, PaginationComponent],
  templateUrl: './reports-list.component.html',
})
export class ReportsListComponent implements OnInit {
  private readonly reporting = inject(ReportingService);
  private readonly destroyRef = inject(DestroyRef);

  /** List UI state. */
  readonly rows = signal<PublicReport[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** Client-side filter state (server filters are a CENTRAL NEED). */
  readonly statusFilter = signal('');
  readonly searchTerm = signal('');
  readonly slaBreachedOnly = signal(false);

  /** Selectable status tokens for the filter. */
  readonly statuses = REPORT_STATUSES;

  private readonly pageSize = 20;

  /** The current page filtered by the active client-side criteria. */
  readonly filteredRows = computed(() => {
    const status = this.statusFilter();
    const term = this.searchTerm().trim().toLowerCase();
    const slaOnly = this.slaBreachedOnly();
    const now = Date.now();
    return this.rows().filter((r) => {
      if (status && r.status !== status) {
        return false;
      }
      if (term && !(`${r.code} ${r.title}`.toLowerCase().includes(term))) {
        return false;
      }
      if (slaOnly && !(r.dueAt && Date.parse(r.dueAt) < now && !this.isClosed(r.status))) {
        return false;
      }
      return true;
    });
  });

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of reports.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.reporting
      .listQueue({ page, size: this.pageSize, sort: 'createdAt,desc' })
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

  /**
   * Whether a report's SLA is breached (past due and still open).
   * @param report the report row.
   */
  slaBreached(report: PublicReport): boolean {
    return !!report.dueAt && Date.parse(report.dueAt) < Date.now() && !this.isClosed(report.status);
  }

  /** Whether a status is terminal (no SLA breach once closed/resolved). */
  private isClosed(status: string): boolean {
    return ['RESOLVED', 'CONFIRMED', 'CLOSED', 'REJECTED', 'DUPLICATE'].includes(status);
  }
}
