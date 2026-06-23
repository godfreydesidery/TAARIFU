import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, merge } from 'rxjs';
import { debounceTime, distinctUntilChanged, startWith, switchMap } from 'rxjs/operators';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { RepresentativeSummary } from './institutions.models';
import { InstitutionsService } from './institutions.service';

/**
 * Representatives directory list with debounced server-side search + type/status filters
 * (`GET /representatives`; PRD §22.6).
 *
 * <p>Responsibility: a "smart" list that demonstrates the search/filter pattern. The free-text box,
 * type, and status filters feed a single reactive query stream: their value changes are merged,
 * debounced (so a citizen on 2G isn't hammered per keystroke — PRD §15), and `switchMap`ped to the API
 * (cancelling the in-flight request when a newer query arrives). Results page via the shared
 * {@link PaginationComponent}. Subscriptions are torn down with {@link takeUntilDestroyed}.</p>
 *
 * <p>Accessibility: labelled search input + selects, a semantic data table with `<th scope>`, and
 * announced loading/empty states. All copy is i18n; the `FORMER` status is shown as a badge.</p>
 */
@Component({
  selector: 'app-representatives-list',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule, PaginationComponent],
  templateUrl: './representatives-list.component.html',
})
export class RepresentativesListComponent implements OnInit {
  private readonly institutions = inject(InstitutionsService);
  private readonly destroyRef = inject(DestroyRef);

  /** Search + filter controls (drive the query stream). */
  readonly search = new FormControl<string>('', { nonNullable: true });
  readonly typeFilter = new FormControl<string>('', { nonNullable: true });
  readonly statusFilter = new FormControl<string>('', { nonNullable: true });

  /** Selectable type / status options (mirror the backend enums). */
  readonly types = ['MP', 'COUNCILLOR', 'WARD_EXEC'];
  readonly statuses = ['SITTING', 'FORMER', 'PENDING_VERIFICATION'];

  /** List UI state. */
  readonly rows = signal<RepresentativeSummary[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** Emits the requested page index; merged with filter changes into the query stream. */
  private readonly pageRequests = new Subject<number>();
  private currentPage = 0;
  private readonly pageSize = 20;

  /** Wires the debounced, cancel-on-new query stream. */
  ngOnInit(): void {
    // Any filter change resets to page 0; page requests keep the current filters.
    const filterChanges = merge(
      this.search.valueChanges.pipe(debounceTime(300)),
      this.typeFilter.valueChanges,
      this.statusFilter.valueChanges,
    ).pipe(
      distinctUntilChanged(),
      // Reset to first page on a filter change.
      startWith(null),
    );

    merge(
      filterChanges.pipe(switchMap(() => this.query(0))),
      this.pageRequests.pipe(switchMap((page) => this.query(page))),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
          this.errored.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /** Requests a specific page (from the pagination control), keeping current filters. */
  goToPage(page: number): void {
    this.pageRequests.next(page);
  }

  /** Re-runs the current query (retry button). */
  retry(): void {
    this.pageRequests.next(this.currentPage);
  }

  /** Builds and runs the API query for the given page with the current filter values. */
  private query(page: number) {
    this.currentPage = page;
    this.loading.set(true);
    return this.institutions.listRepresentatives({
      q: this.search.value || undefined,
      type: this.typeFilter.value || undefined,
      status: this.statusFilter.value || undefined,
      page,
      size: this.pageSize,
      sort: 'status,asc',
    });
  }
}
