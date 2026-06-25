import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import { Payment, PaymentListFilter, PaymentTotals, RefundTopUpRequest } from './payments.models';

/**
 * Data access for the Phase-2 mobile-money payments admin view (ADR-0015 + addendum; PRD §23, §18, §21 EI-20).
 *
 * <p>Responsibility: the feature's typed gateway over the payments admin endpoints, delegating envelope/HTTP
 * concerns to {@link ApiClient} (DRY, CLAUDE.md §8). It reads a paged, filterable list of top-ups
 * ({@code GET /admin/payments}), an aggregate totals strip ({@code GET /admin/payments/totals}), one top-up's
 * full admin view ({@code GET /admin/payments/{id}}), and performs the two operator money-movement actions —
 * REFUND a settled top-up ({@code POST /admin/payments/{id}/refund}) and VOID an un-settled one
 * ({@code POST /admin/payments/{id}/void}). Authorization is enforced SERVER-side (ROLE_ADMIN; ROOT inherits).</p>
 *
 * <p>The TOTALS call DEGRADES GRACEFULLY to {@code null} on any error (404 when not deployed, 403, network)
 * so a missing totals endpoint hides the strip without breaking the list (PRD §15). The LIST and the detail
 * GET are left to throw so the page can show an explicit error/retry state — an admin who can't load payments
 * needs to know, not see a silently empty ledger. REFUND/VOID are left to throw so the central error
 * interceptor surfaces a localised toast (e.g. the 409 when a top-up is not in a refundable/voidable state)
 * and the caller keeps the drawer open.</p>
 *
 * <p><b>Privacy + fence (PRD §18, D18):</b> all payloads are PII-minimised by construction — no MSISDN (never
 * stored), no national/voter ID, an opaque buyer UUID only. Refund/void touch ONLY the convenience wallet;
 * nothing here reads or gates on a token balance, and tokens never buy democratic weight (§23).</p>
 */
@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly api = inject(ApiClient);

  /**
   * Lists mobile-money top-ups, filtered and paged. `GET /admin/payments`.
   * @param filter optional provider/status/date-window filters plus `page`/`size` (all server-side).
   * @returns a {@link Page} of {@link Payment}.
   */
  list(filter: PaymentListFilter): Observable<Page<Payment>> {
    return this.api.getPage<Payment>('/admin/payments', { ...filter });
  }

  /**
   * Fetches aggregate payment totals for the active filter window. `GET /admin/payments/totals`.
   *
   * <p>Degrades to {@code null} on any error so the totals strip is hidden rather than failing the page.</p>
   *
   * @param filter the same provider/status/date-window filters as the list (page/size ignored server-side).
   * @returns the {@link PaymentTotals}, or `null` if unavailable.
   */
  totals(filter: PaymentListFilter): Observable<PaymentTotals | null> {
    return this.api
      .get<PaymentTotals>('/admin/payments/totals', { ...filter })
      .pipe(catchError(() => of(null)));
  }

  /**
   * Fetches one top-up's full admin view. `GET /admin/payments/{publicId}`.
   *
   * <p>Left to throw on error so the detail drawer can show an explicit error/retry (a 404 means the row was
   * concurrently removed; the operator must see that, not a blank panel).</p>
   *
   * @param publicId the top-up public id.
   * @returns the {@link Payment} admin detail.
   */
  get(publicId: string): Observable<Payment> {
    return this.api.get<Payment>(`/admin/payments/${publicId}`);
  }

  /**
   * Refunds a SETTLED top-up — reverses the convenience-token credit (SUCCEEDED → REFUNDED).
   * `POST /admin/payments/{publicId}/refund`.
   *
   * <p>The server enforces the state machine: a 409 is returned (and surfaced as a localised toast by the
   * interceptor) if the top-up is not in a refundable state. D18-safe: only convenience tokens are reversed,
   * never democratic weight.</p>
   *
   * @param publicId the top-up public id.
   * @param request  the required audit reason (no PII; capped at 256 chars server-side).
   * @returns the refunded {@link Payment} in its new (REFUNDED) state.
   */
  refund(publicId: string, request: RefundTopUpRequest): Observable<Payment> {
    return this.api.post<Payment, RefundTopUpRequest>(`/admin/payments/${publicId}/refund`, request);
  }

  /**
   * Voids an UN-SETTLED top-up attempt — cancels with no wallet effect (INITIATED/PENDING → VOIDED).
   * `POST /admin/payments/{publicId}/void`.
   *
   * <p>The server enforces the state machine: a 409 is returned if the top-up is already settlement-terminal.
   * Named `voidTopUp` because `void` is a reserved word in TypeScript.</p>
   *
   * @param publicId the top-up public id.
   * @param request  the required audit reason (no PII; capped at 256 chars server-side).
   * @returns the voided {@link Payment} in its new (VOIDED) state.
   */
  voidTopUp(publicId: string, request: RefundTopUpRequest): Observable<Payment> {
    return this.api.post<Payment, RefundTopUpRequest>(`/admin/payments/${publicId}/void`, request);
  }
}
