import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { AdminStats } from '../reporting/reporting.models';

/**
 * Count provider for the admin dashboard stat cards (M14 admin console; PRD §14).
 *
 * <p>Responsibility: serves the dashboard's headline numbers. The report-domain figures (open cases, SLA
 * breaches, moderation flags pending, verification-queue depth, per-status breakdown) come from the single
 * aggregate `GET /admin/stats` endpoint — one round-trip instead of N list probes (data-cost discipline for
 * a low-end device / slow link, PRD §15). The few collections not covered by `/admin/stats` (responder
 * organisations, the representatives directory) still read their list's `meta.total` via {@link count}.
 * Any failing call degrades to `null` (the card shows a dash) rather than failing the whole dashboard.
 * Envelope unwrapping is delegated to {@link ApiClient}.</p>
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly api = inject(ApiClient);

  /**
   * Fetches the aggregate overview counts, or `null` on error. `GET /admin/stats`.
   * @returns the {@link AdminStats} snapshot, or `null` if the call fails.
   */
  stats(): Observable<AdminStats | null> {
    return this.api.get<AdminStats>('/admin/stats').pipe(catchError(() => of(null)));
  }

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

  /** Total responder organisations (admin list — not covered by /admin/stats). */
  organisations(): Observable<number | null> {
    return this.count('/responders/admin/organisations');
  }

  /** Total representatives (public directory — not covered by /admin/stats). */
  representatives(): Observable<number | null> {
    return this.count('/representatives');
  }
}
