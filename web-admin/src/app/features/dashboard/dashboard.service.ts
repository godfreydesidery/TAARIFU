import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';

/**
 * Lightweight count provider for the admin dashboard stat cards (PRD §14 admin console).
 *
 * <p>Responsibility: returns the server-authoritative TOTAL for a few headline collections using existing
 * paged endpoints with `size=1` — so each card costs ~one row over the wire (data-cost discipline for a
 * low-end device / slow link, PRD §15). There is no dedicated `/admin/stats` endpoint yet (a CENTRAL
 * NEED — a single aggregated counts endpoint would replace these N calls); until then each count reads its
 * own list's `meta.total`. Any single failing count degrades to `null` (the card shows a dash) rather than
 * failing the whole dashboard. Envelope unwrapping is delegated to {@link ApiClient}.</p>
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly api = inject(ApiClient);

  /**
   * Returns the total count behind a paged list endpoint, or `null` on error.
   * @param path the paged list resource path.
   * @returns the server `meta.total`, or `null` if the call fails.
   */
  count(path: string): Observable<number | null> {
    return this.api.getPage<unknown>(path, { page: 0, size: 1 }).pipe(
      map((page) => page.meta.total),
      catchError(() => of(null)),
    );
  }

  /** Total reports (public near-me list — the only paged report list today). */
  reports(): Observable<number | null> {
    return this.count('/public/reports');
  }

  /** Total responder organisations (admin list). */
  organisations(): Observable<number | null> {
    return this.count('/responders/admin/organisations');
  }

  /** Total representatives (public directory). */
  representatives(): Observable<number | null> {
    return this.count('/representatives');
  }

  /** Pending moderation items. */
  pendingModeration(): Observable<number | null> {
    return this.api
      .getPage<unknown>('/moderation/items', { status: 'PENDING', page: 0, size: 1 })
      .pipe(
        map((page) => page.meta.total),
        catchError(() => of(null)),
      );
  }
}
