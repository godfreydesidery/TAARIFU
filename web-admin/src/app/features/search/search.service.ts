import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { SearchResults } from './search.models';

/**
 * Data access for the global search box + results page (`GET /search`; EI-10 / SY5, PRD §21).
 *
 * <p>Responsibility: the feature's typed gateway over the public search surface, delegating envelope/HTTP
 * concerns to {@link ApiClient} (DRY, CLAUDE.md §8). It queries the indexed PUBLIC entities (representatives,
 * responder organisations, announcements, public reports) and returns a flat ranked hit list the UI groups
 * by type.</p>
 *
 * <p>DEGRADES GRACEFULLY (a project resilience requirement, PRD §15): any error — a `404` when the search
 * endpoint isn't deployed in this environment, a `403`, or a network failure — maps to a benign EMPTY result
 * (`{ query, total: 0, hits: [] }`) rather than a thrown error, so the search box never breaks the topbar.
 * The PRD's own degradation story is server-side (external engine down → Postgres FTS fallback behind the
 * same contract); this client-side fallback covers the endpoint simply not existing yet.</p>
 */
@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly api = inject(ApiClient);

  /**
   * Runs a global search across indexed public entities. `GET /search?q=…&limit=…`.
   *
   * @param query the user's raw search text (trimmed by the caller); a blank query yields an empty result
   *   without a round-trip.
   * @param limit max hits to request (keeps the topbar dropdown small + data-cheap on a slow link).
   * @returns the {@link SearchResults}; ALWAYS resolves (errors degrade to an empty result, never thrown).
   */
  search(query: string, limit = 20): Observable<SearchResults> {
    const q = query.trim();
    if (!q) {
      return of({ query: '', total: 0, hits: [] });
    }
    return this.api
      .get<SearchResults>('/search', { q, limit })
      .pipe(catchError(() => of({ query: q, total: 0, hits: [] })));
  }
}
