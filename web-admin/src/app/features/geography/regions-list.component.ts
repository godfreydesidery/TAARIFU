import { Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { District, Region } from './geography.models';
import { GeographyService } from './geography.service';

/**
 * Regions list with an inline districts drill-down (geography MVP; `GET /regions`, `/districts`).
 *
 * <p>Responsibility: a "smart" list component establishing the server-paginated read pattern every other
 * list reuses — it loads a page of regions, renders the loading / empty / error states, and pages via the
 * shared {@link PaginationComponent}. Selecting a region lazily loads its districts (one extra call only
 * when expanded — cheap on a slow link, PRD §15). All RxJS subscriptions are torn down via
 * {@link takeUntilDestroyed} (no leaks).</p>
 *
 * <p>Accessibility: a real data `<table>` with `<th scope>` headers; the districts toggle is a `<button>`
 * with `aria-expanded`; loading/empty/error are announced regions. All copy is i18n.</p>
 */
@Component({
  selector: 'app-regions-list',
  standalone: true,
  imports: [TranslateModule, PaginationComponent],
  templateUrl: './regions-list.component.html',
})
export class RegionsListComponent implements OnInit {
  private readonly geography = inject(GeographyService);
  private readonly destroyRef = inject(DestroyRef);

  /** UI states for the regions list (discriminated by these flags — KISS over a union for the template). */
  readonly regions = signal<Region[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** The currently expanded region id (its districts are shown), or `null`. */
  readonly expandedRegionId = signal<string | null>(null);
  /** Districts of the expanded region. */
  readonly districts = signal<District[]>([]);
  readonly districtsLoading = signal(false);

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of regions, managing the loading/error/empty states.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.geography
      .listRegions({ page, size: this.pageSize, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.regions.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
          // Collapse any expanded row when the page changes.
          this.expandedRegionId.set(null);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /**
   * Toggles the districts drill-down for a region, lazily loading them the first time it is opened.
   * @param region the region whose districts to show/hide.
   */
  toggleDistricts(region: Region): void {
    if (this.expandedRegionId() === region.id) {
      this.expandedRegionId.set(null);
      return;
    }
    this.expandedRegionId.set(region.id);
    this.districts.set([]);
    this.districtsLoading.set(true);
    this.geography
      .listDistricts(region.id, { page: 0, size: 100, sort: 'name,asc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.districts.set(result.content);
          this.districtsLoading.set(false);
        },
        error: () => {
          this.districtsLoading.set(false);
        },
      });
  }
}
