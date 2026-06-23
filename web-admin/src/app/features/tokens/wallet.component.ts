import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { TokenTransaction, Wallet } from './tokens.models';
import { TokensService } from './tokens.service';

/**
 * Own-wallet balance + ledger view (PRD §23.1, §23.5 transparency; M17).
 *
 * <p>Responsibility: shows the signed-in operator's own token balance and a paged, newest-first ledger.
 * This is the read-only transparency surface; the wallet is own-only on the server (owner derived from
 * the JWT). Tokens never buy democratic weight (fence D18) — copy avoids any "spend to act" framing.
 * Loading/empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-wallet',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, TranslateModule, PaginationComponent],
  templateUrl: './wallet.component.html',
})
export class WalletComponent implements OnInit {
  private readonly tokens = inject(TokensService);
  private readonly destroyRef = inject(DestroyRef);

  /** Wallet + ledger UI state. */
  readonly wallet = signal<Wallet | null>(null);
  readonly rows = signal<TokenTransaction[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageSize = 20;

  /** Loads the wallet then the ledger on init. */
  ngOnInit(): void {
    this.load();
  }

  /** Loads the wallet header (then the ledger page). */
  load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.tokens
      .getMyWallet()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (wallet) => {
          this.wallet.set(wallet);
          this.loadPage(0);
        },
        error: () => {
          this.loading.set(false);
          this.errored.set(true);
        },
      });
  }

  /**
   * Loads a page of the ledger.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.tokens
      .getMyLedger({ page, size: this.pageSize, sort: 'createdAt,desc' })
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
  }
}
