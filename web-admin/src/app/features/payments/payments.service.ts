import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { Payment, PaymentListFilter, PaymentTotals } from './payments.models';

/**
 * Data access for the Phase-2 mobile-money payments admin view (D19; PRD §23, §21 EI-20).
 *
 * <p>Responsibility: the feature's typed gateway over the payments admin query endpoints, delegating
 * envelope/HTTP concerns to {@link ApiClient} (DRY, CLAUDE.md §8). It reads a paged, filterable list of
 * mobile-money payments ({@code GET /payments/admin}) and an aggregate totals strip
 * ({@code GET /payments/admin/totals}). Authorization is enforced SERVER-side (ADMIN/ROOT — payments are a
 * Phase-2 admin/finance surface).</p>
 *
 * <p>The TOTALS call DEGRADES GRACEFULLY to {@code null} on any error (404 when not deployed, 403, network)
 * so a missing totals endpoint hides the strip without breaking the list (PRD §15). The LIST is left to
 * throw so the page can show an explicit error/retry state — an admin who can't load payments needs to know,
 * not see a silently empty ledger. All payloads are PII-minimised: masked MSISDN only, no national/voter ID
 * (PRD §18, DI5).</p>
 */
@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly api = inject(ApiClient);

  /**
   * Lists mobile-money payments, filtered and paged. `GET /payments/admin`.
   * @param filter optional provider/status/date-window filters plus `page`/`size` (all server-side).
   * @returns a {@link Page} of {@link Payment}.
   */
  list(filter: PaymentListFilter): Observable<Page<Payment>> {
    return this.api.getPage<Payment>('/payments/admin', { ...filter });
  }

  /**
   * Fetches aggregate payment totals for the active filter window. `GET /payments/admin/totals`.
   *
   * <p>Degrades to {@code null} on any error so the totals strip is hidden rather than failing the page.</p>
   *
   * @param filter the same provider/status/date-window filters as the list (page/size ignored server-side).
   * @returns the {@link PaymentTotals}, or `null` if unavailable.
   */
  totals(filter: PaymentListFilter): Observable<PaymentTotals | null> {
    return this.api
      .get<PaymentTotals>('/payments/admin/totals', { ...filter })
      .pipe(catchError(() => of(null)));
  }
}
