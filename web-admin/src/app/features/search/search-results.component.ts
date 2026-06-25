import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { StatePanelComponent } from '../../shared/components/state-panel.component';
import { deepLinkFor, iconFor, SEARCH_GROUP_ORDER } from './search.util';
import { SearchHit, SearchResultType } from './search.models';
import { SearchService } from './search.service';

/** A display group: one entity type with its hits, for the grouped results layout. */
interface ResultGroup {
  /** The entity type this group holds. */
  type: SearchResultType;
  /** i18n key for the group heading. */
  labelKey: string;
  /** The hits in this group (already deep-linkable). */
  hits: SearchHit[];
}

/**
 * Full-page global search results (EI-10 / SY5; PRD §21 EI-10).
 *
 * <p>Responsibility: the destination for the topbar search's "see all results" / Enter action. It re-runs the
 * query from the bound {@link q} route param and renders the hits GROUPED by entity type (reports,
 * representatives, organisations, announcements), each row a deep link into the owning feature. It reuses the
 * same {@link SearchService} (which degrades a missing/`404` endpoint to an empty result) so the page shows a
 * friendly empty state rather than an error when search isn't deployed (PRD §15 resilience). All hits are
 * already PII-minimised + public server-side (sensitive reports are never indexed, SR-5). Loading/empty
 * states are handled; the subscription uses {@link takeUntilDestroyed}.</p>
 *
 * <p>Accessibility (WCAG 2.1 AA): results are real grouped lists with headings; each hit is a keyboard-
 * reachable link with an accessible name; the loading region is announced; all copy is i18n.</p>
 */
@Component({
  selector: 'app-search-results',
  standalone: true,
  imports: [RouterLink, TranslateModule, StatePanelComponent],
  templateUrl: './search-results.component.html',
})
export class SearchResultsComponent implements OnInit {
  private readonly searchService = inject(SearchService);
  private readonly destroyRef = inject(DestroyRef);

  /** The query, bound from the `?q=` route param via the router's component-input-binding. */
  readonly q = input<string>('');

  /** Results UI state. `loading` true while a query is in flight. */
  readonly hits = signal<SearchHit[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  /** The query actually run (echoed for the heading), independent of the live input. */
  readonly ranQuery = signal('');

  /** Hits grouped by type in a stable, civic-sensible order, dropping empty groups. */
  readonly groups = computed<ResultGroup[]>(() => {
    const all = this.hits();
    return SEARCH_GROUP_ORDER.map((g) => ({
      type: g.type,
      labelKey: g.labelKey,
      hits: all.filter((h) => h.type === g.type),
    })).filter((group) => group.hits.length > 0);
  });

  /** Runs the bound query on first load. The input is set by the router before `ngOnInit`. */
  ngOnInit(): void {
    this.run(this.q());
  }

  /**
   * Executes a search and binds the grouped results.
   * @param query the raw query text.
   */
  private run(query: string): void {
    const q = query.trim();
    this.ranQuery.set(q);
    if (!q) {
      this.hits.set([]);
      this.total.set(0);
      return;
    }
    this.loading.set(true);
    this.searchService
      .search(q, 50)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((res) => {
        this.hits.set(res.hits);
        this.total.set(res.total);
        this.loading.set(false);
      });
  }

  /** The router link array for a hit's owning feature detail/list. */
  link = deepLinkFor;

  /** The glyph for a hit's entity type. */
  icon = iconFor;
}
