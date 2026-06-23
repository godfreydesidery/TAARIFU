import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, merge } from 'rxjs';
import { debounceTime, distinctUntilChanged, startWith, switchMap } from 'rxjs/operators';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { PoliticalParty } from './institutions.models';
import { InstitutionsService } from './institutions.service';

/**
 * Political-party directory list with debounced server-side search (`GET /parties`; PRD §9.1).
 *
 * <p>Responsibility: a read-only "smart" list mirroring the representatives pattern (debounced search →
 * `switchMap` query → shared pagination) for the party directory. Establishes that the same query
 * pipeline is reused across features (DRY). Subscriptions torn down via {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-parties-list',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule, PaginationComponent],
  templateUrl: './parties-list.component.html',
})
export class PartiesListComponent implements OnInit {
  private readonly institutions = inject(InstitutionsService);
  private readonly destroyRef = inject(DestroyRef);

  /** Free-text search control. */
  readonly search = new FormControl<string>('', { nonNullable: true });

  /** List UI state. */
  readonly rows = signal<PoliticalParty[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageRequests = new Subject<number>();
  private currentPage = 0;
  private readonly pageSize = 20;

  /** Wires the debounced query stream. */
  ngOnInit(): void {
    const searchChanges = this.search.valueChanges.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      startWith(''),
    );

    merge(
      searchChanges.pipe(switchMap(() => this.query(0))),
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

  /** Requests a specific page. */
  goToPage(page: number): void {
    this.pageRequests.next(page);
  }

  /** Re-runs the current query. */
  retry(): void {
    this.pageRequests.next(this.currentPage);
  }

  /** Builds and runs the API query for the given page with the current search term. */
  private query(page: number) {
    this.currentPage = page;
    this.loading.set(true);
    return this.institutions.listParties({
      q: this.search.value || undefined,
      page,
      size: this.pageSize,
      sort: 'name,asc',
    });
  }
}
