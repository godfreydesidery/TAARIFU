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
import { PAYMENT_PROVIDERS, PAYMENT_STATUSES, Payment, PaymentTotals } from './payments.models';
import { PaymentsService } from './payments.service';
import { maskMsisdn } from './payments.util';

/**
 * Mobile-money payments admin view — Phase-2 token-purchase ledger (D19; PRD §23, §21 EI-20).
 *
 * <p>Responsibility: the reconciliation console for mobile-money payments (M-Pesa / Tigo Pesa / Airtel
 * Money / HaloPesa / card). It lists payments paged from {@code GET /payments/admin} with server-side filters
 * by provider, status, and date window, and shows an aggregate TOTALS strip ({@code GET /payments/admin/
 * totals}) — collected / pending / failed / refunded — so the operator sees the money picture without
 * summing pages. Each row shows a MASKED payer MSISDN (defence-in-depth client mask over the server's, PRD
 * §18/DI5), the provider, amount, and the tokens credited. The totals strip degrades to hidden if its
 * endpoint is absent; the list shows an explicit error/retry. A standing reminder reinforces the §23 fence:
 * buying tokens never buys democratic weight. Loading/empty/error states are handled; subscriptions use
 * {@link takeUntilDestroyed}.</p>
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

  /** Defence-in-depth MSISDN mask (server is expected to mask; this guarantees it). */
  mask = maskMsisdn;

  /** Maps a payment status token to a badge tone (shared design-system mapping). */
  tone = statusTone;
}
