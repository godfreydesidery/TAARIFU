import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { DashboardService } from './dashboard.service';

/**
 * Admin dashboard landing page — stat cards + quick actions + feature nav (PRD §14 admin console).
 *
 * <p>Responsibility: the post-login home. It shows four headline counts (reports, organisations,
 * representatives, pending moderation), quick-action links to the most common tasks, and the feature nav
 * cards. Each count is loaded independently and degrades to a dash on failure so one slow/failing
 * collection never blocks the page (resilience + data-cost discipline, PRD §15). The counts are fetched in
 * `ngOnInit` (not blocking the first paint — the cards render skeletons until each resolves); a future
 * single `/admin/stats` endpoint would replace the N calls (CENTRAL NEED). Subscriptions use
 * {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, DecimalPipe, TranslateModule],
  templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);

  /** Headline counts. `undefined` = loading, `null` = failed, `number` = loaded. */
  readonly reportsCount = signal<number | null | undefined>(undefined);
  readonly organisationsCount = signal<number | null | undefined>(undefined);
  readonly representativesCount = signal<number | null | undefined>(undefined);
  readonly moderationCount = signal<number | null | undefined>(undefined);

  /** Loads the four headline counts independently. */
  ngOnInit(): void {
    this.dashboard
      .reports()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((n) => this.reportsCount.set(n));
    this.dashboard
      .organisations()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((n) => this.organisationsCount.set(n));
    this.dashboard
      .representatives()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((n) => this.representativesCount.set(n));
    this.dashboard
      .pendingModeration()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((n) => this.moderationCount.set(n));
  }
}
