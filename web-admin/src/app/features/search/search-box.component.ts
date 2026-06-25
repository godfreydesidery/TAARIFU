import { Component, DestroyRef, ElementRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { TranslateModule } from '@ngx-translate/core';

import { deepLinkFor, iconFor, SEARCH_GROUP_ORDER } from './search.util';
import { SearchHit, SearchResultType } from './search.models';
import { SearchService } from './search.service';

/** A display group inside the dropdown: one entity type with its (capped) hits. */
interface BoxGroup {
  /** The entity type. */
  type: SearchResultType;
  /** i18n key for the group heading. */
  labelKey: string;
  /** The hits in this group. */
  hits: SearchHit[];
}

/**
 * The global SEARCH box that lives in the admin topbar (EI-10 / SY5; PRD §21 EI-10).
 *
 * <p>Responsibility: an always-available typeahead over the public search index — representatives, responder
 * organisations, announcements, and public reports. As the operator types, a DEBOUNCED query (300ms) hits
 * {@link SearchService} via `switchMap` (so a slow in-flight request is cancelled when the term changes —
 * no out-of-order results, data-cheap on a slow link, PRD §15). Results drop into a grouped dropdown of deep
 * links; Enter (or "see all") opens the full results page. The service degrades a missing/`404`/error search
 * endpoint to an empty result, so the box never breaks the topbar (PRD §15 resilience).</p>
 *
 * <p>Accessibility (WCAG 2.1 AA): a labelled combobox — the input carries `role="combobox"`,
 * `aria-expanded`, and `aria-controls`; the dropdown is a `role="listbox"` of links with an accessible name;
 * Escape closes and a click outside dismisses; the live region announces result counts. All copy is i18n.</p>
 */
@Component({
  selector: 'app-search-box',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './search-box.component.html',
})
export class SearchBoxComponent {
  private readonly searchService = inject(SearchService);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly destroyRef = inject(DestroyRef);

  /** The live query text bound to the input. */
  readonly term = signal('');
  /** Whether the results dropdown is open. */
  readonly open = signal(false);
  /** Whether a query is in flight (drives the inline spinner + busy announcement). */
  readonly loading = signal(false);
  /** The latest hits (flat, ranked). */
  readonly hits = signal<SearchHit[]>([]);

  /** Pushes each keystroke into the debounced search pipeline. */
  private readonly query$ = new Subject<string>();

  /** Hits grouped by type for the dropdown, capped per group so the panel stays compact. */
  readonly groups = computed<BoxGroup[]>(() => {
    // Defensive: never assume `hits()` is a populated array — a malformed/empty wire payload must degrade to
    // "no groups", not throw inside the template's change detection (guards the prior `undefined.filter` bug).
    const all = this.hits() ?? [];
    return SEARCH_GROUP_ORDER.map((g) => ({
      type: g.type,
      labelKey: g.labelKey,
      hits: all.filter((h) => h.type === g.type).slice(0, 4),
    })).filter((group) => group.hits.length > 0);
  });

  /** Whether to show the "no matches" line (a query ran, returned nothing, and we're not still loading). */
  readonly showEmpty = computed(
    () => this.open() && !this.loading() && this.term().trim().length > 0 && this.hits().length === 0,
  );

  constructor() {
    // Debounced typeahead: cancel a stale in-flight request when the term changes (switchMap).
    this.query$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          this.loading.set(true);
          return this.searchService.search(q, 12);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        this.hits.set(res.hits);
        this.loading.set(false);
      });
  }

  /** Handles input: updates the term, opens the panel, and pushes into the debounced pipeline. */
  onInput(value: string): void {
    this.term.set(value);
    this.open.set(true);
    if (!value.trim()) {
      this.hits.set([]);
      this.loading.set(false);
      return;
    }
    this.query$.next(value);
  }

  /** Re-opens the dropdown on focus if there is something to show. */
  onFocus(): void {
    if (this.term().trim()) {
      this.open.set(true);
    }
  }

  /** Submits the search → navigates to the full results page with the query, and closes the dropdown. */
  submit(): void {
    const q = this.term().trim();
    if (!q) {
      return;
    }
    this.open.set(false);
    void this.router.navigate(['/search'], { queryParams: { q } });
  }

  /** Closes the dropdown after a result is clicked (the link navigates away). */
  onResultClick(): void {
    this.open.set(false);
  }

  /** Escape closes the dropdown without losing the typed term. */
  @HostListener('keydown.escape')
  onEscape(): void {
    this.open.set(false);
  }

  /** A click anywhere outside the search box dismisses the dropdown. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  /** The glyph for a hit's entity type. */
  icon = iconFor;

  /** The router link array for a hit. */
  link = deepLinkFor;
}
