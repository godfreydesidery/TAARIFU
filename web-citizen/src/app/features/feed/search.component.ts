import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { FeedService } from './feed.service';
import { SearchResult } from './feed.models';

/**
 * Cross-entity discovery search (reps, organisations, announcements, categories, public reports).
 *
 * <p>Responsibility: a single search box that queries `/search` and lists ranked results, grouped visually
 * by type with a translated type label + icon (low-literacy friendly). Uses `debounceTime` +
 * `distinctUntilChanged` + `switchMap` so a citizen typing on a slow link issues one request per settled
 * query (data-cost discipline, PRD §15) and stale responses are dropped. A blank query shows a prompt; no
 * matches shows an empty state.</p>
 */
@Component({
  selector: 'app-search',
  standalone: true,
  imports: [ReactiveFormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search.component.html',
  styleUrl: './search.component.scss',
})
export class SearchComponent {
  private readonly feed = inject(FeedService);

  /** The search query control (reactive, debounced). */
  protected readonly query = new FormControl('', { nonNullable: true });
  /** The latest ranked results. */
  protected readonly results = signal<SearchResult[]>([]);
  /** True while a query is in flight. */
  protected readonly loading = signal(false);
  /** True once at least one search has been run (distinguishes prompt from empty). */
  protected readonly searched = signal(false);

  constructor() {
    this.query.valueChanges
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((q) => {
          const term = q.trim();
          if (term.length < 2) {
            this.searched.set(false);
            this.results.set([]);
            return of(null);
          }
          this.loading.set(true);
          this.searched.set(true);
          return this.feed.search(term);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          if (page) {
            this.results.set(page.content);
          }
        },
        error: () => this.loading.set(false),
      });
  }

  /** Maps an entity type to a simple icon (icon+label pattern; never icon-only). */
  protected icon(type: string): string {
    switch (type) {
      case 'REPRESENTATIVE':
        return '🏛️';
      case 'ORGANISATION':
        return '🏢';
      case 'ANNOUNCEMENT':
        return '📢';
      case 'ISSUE_CATEGORY':
        return '🏷️';
      case 'PUBLIC_REPORT':
        return '📍';
      default:
        return '🔎';
    }
  }
}
