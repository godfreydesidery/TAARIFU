import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AdminStats } from '../reporting/reporting.models';
import { DashboardService } from './dashboard.service';

/**
 * Admin dashboard landing page — stat cards + quick actions + feature nav (M14 admin console; PRD §14).
 *
 * <p>Responsibility: the post-login home. It shows headline counts — open cases, SLA-breached cases, and
 * pending moderation flags from the single aggregate `GET /admin/stats`, plus responder organisations and
 * the representatives directory from their list totals — then quick-action links and the feature nav cards.
 * The aggregate is loaded once (one round-trip vs N probes; data-cost discipline, PRD §15) and each count
 * degrades to a dash on failure so one slow/failing collection never blocks the page. Counts are fetched in
 * `ngOnInit`; the cards render placeholders until each resolves. Subscriptions use {@link takeUntilDestroyed}.</p>
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

  /** Aggregate stats. `undefined` = loading, `null` = failed, else the {@link AdminStats}. */
  readonly stats = signal<AdminStats | null | undefined>(undefined);

  /** List-total counts not covered by /admin/stats. `undefined` = loading, `null` = failed, else number. */
  readonly organisationsCount = signal<number | null | undefined>(undefined);
  readonly representativesCount = signal<number | null | undefined>(undefined);

  /** Open-case total from the aggregate (`undefined`/`null`/number, mirroring {@link stats}). */
  readonly openCases = computed<number | null | undefined>(() => this.cardValue((s) => s.openCases));
  /** SLA-breached case total from the aggregate. */
  readonly slaBreachedCases = computed<number | null | undefined>(() => this.cardValue((s) => s.slaBreachedCases));
  /** Pending moderation flags from the aggregate. */
  readonly flagsPending = computed<number | null | undefined>(() => this.cardValue((s) => s.flagsPending));

  /** Loads the aggregate stats + the two list counts independently. */
  ngOnInit(): void {
    this.dashboard
      .stats()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((s) => this.stats.set(s));
    this.dashboard
      .organisations()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((n) => this.organisationsCount.set(n));
    this.dashboard
      .representatives()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((n) => this.representativesCount.set(n));
  }

  /** Maps the aggregate's loading/failed/loaded tri-state onto a single numeric card value. */
  private cardValue(pick: (s: AdminStats) => number): number | null | undefined {
    const s = this.stats();
    if (s === undefined) {
      return undefined;
    }
    if (s === null) {
      return null;
    }
    return pick(s);
  }
}
