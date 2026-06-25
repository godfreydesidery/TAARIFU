import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { StatePanelComponent } from '../../shared/components/state-panel.component';
import { SkeletonTableComponent } from '../../shared/components/skeleton.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge.component';
import { statusTone } from '../../shared/util/status-tone.util';
import { PaymentDetailDrawerComponent } from './payment-detail-drawer.component';
import { PAYMENT_PROVIDERS, PAYMENT_STATUSES, Payment, PaymentTotals } from './payments.models';
import { PaymentsService } from './payments.service';
import { minorToMajor } from './payments.util';

/**
 * Mobile-money payments admin view — Phase-2 token-purchase ledger (ADR-0015 + addendum; PRD §23, §18).
 *
 * <p>Responsibility: the reconciliation console for mobile-money top-ups (M-Pesa / Tigo Pesa / Airtel Money /
 * HaloPesa). It lists payments paged from {@code GET /admin/payments} with server-side filters by provider,
 * status, and date window, and shows an aggregate TOTALS strip ({@code GET /admin/payments/totals}) — settled
 * / pending / failed / refunded — so the operator sees the money picture without summing pages. Clicking a row
 * opens the {@link PaymentDetailDrawerComponent} (full detail + status timeline + the REFUND/VOID operator
 * actions); a successful action reloads the current page so the new status is server-authoritative. The totals
 * strip degrades to hidden if its endpoint is absent; the list shows an explicit error/retry. A standing
 * reminder reinforces the §23 fence: buying tokens never buys democratic weight. Loading/empty/error states
 * are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 *
 * <p><b>Privacy (PRD §18, D18):</b> the rows carry no MSISDN (never stored) and no national/voter ID — only an
 * opaque buyer id, redacted reason codes, and reconciliation references. Money is held in minor units and
 * converted at the presentation edge ({@link minorToMajor}).</p>
 */
@Component({
  selector: 'app-payments-list',
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    DecimalPipe,
    TranslateModule,
    PaginationComponent,
    StatePanelComponent,
    SkeletonTableComponent,
    StatusBadgeComponent,
    PaymentDetailDrawerComponent,
  ],
  templateUrl: './payments-list.component.html',
})
export class PaymentsListComponent implements OnInit {
  private readonly payments = inject(PaymentsService);
  private readonly destroyRef = inject(DestroyRef);

  /** List UI state. */
  readonly rows = signal<Payment[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  /** Aggregate totals strip. `null` = unavailable/loading (the strip hides). */
  readonly totals = signal<PaymentTotals | null>(null);

  /** The id of the payment whose detail drawer is open, or `null` when the drawer is closed. */
  readonly selectedId = signal<string | null>(null);

  /** Server-side filter state. */
  readonly providerFilter = signal('');
  readonly statusFilter = signal('');
  readonly fromFilter = signal('');
  readonly toFilter = signal('');

  /** Selectable tokens for the filter selects. */
  readonly providers = PAYMENT_PROVIDERS;
  readonly statuses = PAYMENT_STATUSES;

  private readonly pageSize = 20;

  /** Whether any filter is active (drives the empty-state hint). */
  readonly hasFilters = computed(
    () => !!this.providerFilter() || !!this.statusFilter() || !!this.fromFilter() || !!this.toFilter(),
  );

  /** Loads the first page + totals on init. */
  ngOnInit(): void {
    this.reload(0);
  }

  /** The active filter object shared by the list + totals queries (DRY). */
  private filter(page: number): {
    provider?: string;
    status?: string;
    from?: string;
    to?: string;
    page: number;
    size: number;
  } {
    return {
      provider: this.providerFilter() || undefined,
      status: this.statusFilter() || undefined,
      from: this.fromFilter() || undefined,
      to: this.toFilter() || undefined,
      page,
      size: this.pageSize,
    };
  }

  /**
   * Loads a page of payments AND refreshes the totals for the active filters.
   * @param page zero-based page index.
   */
  reload(page: number): void {
    const filter = this.filter(page);
    this.loading.set(true);
    this.errored.set(false);

    this.payments
      .list(filter)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.rows.set(result.content);
          this.meta.set(result.meta);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });

    // Totals are independent + degradable — never block or fail the list.
    this.payments
      .totals(filter)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((t) => this.totals.set(t));
  }

  /** Re-queries from page 0 when a filter changes. */
  applyFilters(): void {
    this.reload(0);
  }

  /** Opens the detail drawer for a row (click or keyboard activation). */
  openDetail(payment: Payment): void {
    this.selectedId.set(payment.id);
  }

  /** Closes the detail drawer, clearing the selection. */
  closeDetail(): void {
    this.selectedId.set(null);
  }

  /**
   * Refreshes the current page after a refund/void succeeded in the drawer, so the row's new status is
   * server-authoritative (not an optimistic guess); the drawer stays open showing the updated detail.
   */
  onDetailChanged(): void {
    this.reload(this.meta()?.page ?? 0);
  }

  /** Minor → major money conversion for display. */
  readonly minorToMajor = minorToMajor;

  /** Maps a payment status token to a badge tone (shared design-system mapping). */
  tone = statusTone;
}
