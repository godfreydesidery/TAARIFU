import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { SearchHit, SearchResults, SearchResultType } from './search.models';

/**
 * The raw hit shape returned by the backend `GET /search` (mirrors {@code SearchResultDto}; ADR-0017 §4).
 *
 * <p>The server returns a PAGED FLAT ARRAY under the envelope's `data`, with the total in `meta` — NOT a
 * `{ hits }` object. Each row is keyed `entityType`/`entityPublicId` (not `type`/`id`) and uses the
 * server's `SearchEntityType` tokens (e.g. {@code PUBLIC_REPORT}). {@link SearchService} maps this wire shape
 * into the client's UI-facing {@link SearchHit}; the components never see the raw shape (boundary mapping,
 * CLAUDE.md §8 — DTOs at the boundary).</p>
 */
interface SearchResultDto {
  /** Server entity-kind token (a superset of the client's groups; see {@link toClientType}). */
  entityType: string;
  /** The matched aggregate's public id (the deep-link/re-read key). */
  entityPublicId: string;
  /** Display label. */
  title: string;
  /** Locale-resolved snippet, or null. */
  snippet: string | null;
  /** Matched area public id, or null. */
  areaId: string | null;
  /** Matched category public id, or null. */
  categoryId: string | null;
  /** FTS relevance rank (higher = more relevant); results arrive pre-sorted. */
  rank: number;
}

/**
 * Maps a server {@code SearchEntityType} token to the client's {@link SearchResultType}, or `null` for a
 * server type the admin console does not surface yet (engagement: petitions/polls/Q&A, issue categories).
 *
 * <p>WHY: the search index is cross-cutting and indexes MORE kinds than this console renders. The console
 * groups/links only reps, organisations, public reports, and announcements (PRD §21 EI-10), so any other
 * server type is silently dropped here rather than reaching the UI as an un-iconed, un-linkable "•" row.
 * The notable rename is {@code PUBLIC_REPORT → REPORT}: the server distinguishes public reports from private
 * ones (only public are returned to non-staff), while the client groups them under a single "REPORT" bucket.</p>
 */
function toClientType(serverType: string): SearchResultType | null {
  switch (serverType) {
    case 'PUBLIC_REPORT':
      return 'REPORT';
    case 'REPRESENTATIVE':
      return 'REPRESENTATIVE';
    case 'ORGANISATION':
      return 'ORGANISATION';
    case 'ANNOUNCEMENT':
      return 'ANNOUNCEMENT';
    default:
      // ISSUE_CATEGORY / PETITION / POLL / QUESTION — not surfaced in the admin console search yet.
      return null;
  }
}

/**
 * Data access for the global search box + results page (`GET /search`; EI-10 / SY5, PRD §21).
 *
 * <p>Responsibility: the feature's typed gateway over the public search surface, delegating envelope/HTTP
 * concerns to {@link ApiClient} (DRY, CLAUDE.md §8). It queries the indexed PUBLIC entities (representatives,
 * responder organisations, announcements, public reports) and returns a flat ranked hit list the UI groups
 * by type.</p>
 *
 * <p>WIRE-SHAPE MAPPING: the backend serves a PAGED ARRAY (`data` = `SearchResultDto[]`, total in `meta`),
 * not a `{ hits }` object, and keys each row `entityType`/`entityPublicId`. This service is the single place
 * that maps that wire shape into the client {@link SearchResults} — so the components bind a stable
 * `{ query, total, hits }` regardless of the transport. (A prior bug typed the array as a `{ hits }` object,
 * so `res.hits` was `undefined` and the dropdown's grouping computed threw — see ADR-0017 / contract test.)</p>
 *
 * <p>DEGRADES GRACEFULLY (a project resilience requirement, PRD §15): any error — a `404` when the search
 * endpoint isn't deployed in this environment, a `403`, or a network failure — maps to a benign EMPTY result
 * (`{ query, total: 0, hits: [] }`) rather than a thrown error, so the search box never breaks the topbar.
 * The PRD's own degradation story is server-side (external engine down → Postgres FTS fallback behind the
 * same contract); this client-side fallback covers the endpoint simply not existing yet.</p>
 */
@Injectable({ providedIn: 'root' })
export class SearchService {
  // NOTE: search wire shape = paged array (data: SearchResultDto[]) mapped to SearchResults here.
  private readonly api = inject(ApiClient);

  /**
   * Runs a global search across indexed public entities. `GET /search?q=…&size=…`.
   *
   * @param query the user's raw search text (trimmed by the caller); a blank query yields an empty result
   *   without a round-trip.
   * @param limit max hits to request (sent as the server's `size` page param; keeps the topbar dropdown small
   *   + data-cheap on a slow link).
   * @returns the {@link SearchResults}; ALWAYS resolves (errors degrade to an empty result, never thrown).
   */
  search(query: string, limit = 20): Observable<SearchResults> {
    const q = query.trim();
    if (!q) {
      return of({ query: '', total: 0, hits: [] });
    }
    // The server paginates: `page`/`size` (NOT `limit`). One page of `size` hits is plenty for both the
    // dropdown (≤12) and the results page (≤50).
    return this.api.getPage<SearchResultDto>('/search', { q, page: 0, size: limit }).pipe(
      map((pageResult) => {
        const hits: SearchHit[] = pageResult.content
          .map((row) => {
            const type = toClientType(row.entityType);
            if (!type) {
              return null;
            }
            const hit: SearchHit = {
              type,
              id: row.entityPublicId,
              title: row.title,
              // No dedicated subtitle on the wire; reuse the locale-resolved snippet as the secondary line.
              subtitle: row.snippet ?? null,
              snippet: row.snippet ?? null,
            };
            return hit;
          })
          .filter((h): h is SearchHit => h !== null);
        return { query: q, total: pageResult.meta.total, hits };
      }),
      catchError(() => of({ query: q, total: 0, hits: [] })),
    );
  }
}
