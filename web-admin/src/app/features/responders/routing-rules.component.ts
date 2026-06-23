import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { PageMeta } from '../../core/api/api-response.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { RoutingRule } from './responders.models';
import { RespondersService } from './responders.service';

/**
 * Routing-rules view — the category → responder-kind/sector map (PRD §24.3, D21).
 *
 * <p>Responsibility: a read-only, paged list of routing rules so an admin can audit how reports are
 * routed (category, responder type, selection mode, priority, active). Rule authoring is a follow-up
 * (the create endpoint exists server-side; the MVP console surfaces the view first per the brief).
 * Loading/empty/error states are handled; subscriptions use {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-routing-rules',
  standalone: true,
  imports: [RouterLink, TranslateModule, PaginationComponent],
  templateUrl: './routing-rules.component.html',
})
export class RoutingRulesComponent implements OnInit {
  private readonly responders = inject(RespondersService);
  private readonly destroyRef = inject(DestroyRef);

  /** List UI state. */
  readonly rows = signal<RoutingRule[]>([]);
  readonly meta = signal<PageMeta | null>(null);
  readonly loading = signal(false);
  readonly errored = signal(false);

  private readonly pageSize = 20;

  /** Loads the first page on init. */
  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Loads a page of routing rules.
   * @param page zero-based page index.
   */
  loadPage(page: number): void {
    this.loading.set(true);
    this.errored.set(false);
    this.responders
      .listRoutingRules({ page, size: this.pageSize, sort: 'priority,asc' })
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
