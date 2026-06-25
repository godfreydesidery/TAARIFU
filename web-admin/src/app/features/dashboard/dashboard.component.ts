import { Component, DestroyRef, OnInit, WritableSignal, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ChartData } from 'chart.js';
import { Observable } from 'rxjs';

import { AdminStats } from '../reporting/reporting.models';
import { DashboardService } from './dashboard.service';
import { AnalyticsService } from './analytics.service';
import { Breakdown, Funnel, LatencyStats, VolumeReport } from './analytics.models';
import { ChartComponent, TF_CHART_PALETTE } from '../../shared/components/chart.component';

/** A simple legend row the template renders alongside a chart for accessibility + at-a-glance reading. */
interface LegendEntry {
  /** Already-localised label. */
  label: string;
  /** Hex swatch matching the chart slice/bar. */
  color: string;
  /** The bucket count. */
  value: number;
}

/**
 * The admin dashboard — headline KPIs + real analytics charts (M14/M15 admin console; PRD §14, §3.3).
 *
 * <p>Responsibility: the post-login home. A row of KPI stat tiles (open cases, SLA breaches, pending
 * moderation, verification-queue depth) from the single aggregate `GET /admin/stats`, followed by an
 * elegant chart grid: reports-by-status (bar) is derived from that same `GET /admin/stats` payload, while
 * the remaining charts are fed by `GET /admin/analytics/*` — channel mix (doughnut), the T0→T3 verification
 * funnel (bar with conversion), SLA breaches by type, moderation actions by outcome, and TTFR/TTR
 * responsiveness tiles. EVERY data source degrades independently — a tri-state
 * (`undefined`=loading skeleton, `null`=unavailable empty state, value=render) per tile — so a missing or
 * `404` analytics endpoint never blocks the page (PRD §15 resilience). Each chart is paired with an
 * accessible numeric legend so the data is never chart-only (WCAG 2.1 AA). Subscriptions use
 * {@link takeUntilDestroyed}.</p>
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, DecimalPipe, TranslateModule, ChartComponent],
  templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly analytics = inject(AnalyticsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly translate = inject(TranslateService);

  // --- Aggregate stats (KPI tiles) ------------------------------------------
  /** Aggregate stats. `undefined` = loading, `null` = failed, else the {@link AdminStats}. */
  readonly stats = signal<AdminStats | null | undefined>(undefined);
  readonly organisationsCount = signal<number | null | undefined>(undefined);
  readonly representativesCount = signal<number | null | undefined>(undefined);

  readonly openCases = computed(() => this.cardValue((s) => s.openCases));
  readonly slaBreachedCases = computed(() => this.cardValue((s) => s.slaBreachedCases));
  readonly flagsPending = computed(() => this.cardValue((s) => s.flagsPending));
  readonly verificationQueueDepth = computed(() => this.cardValue((s) => s.verificationQueueDepth));

  // --- Analytics payloads (charts) ------------------------------------------
  /** Tri-state per analytics source: `undefined` loading, `null` unavailable, else the payload. */
  readonly volume = signal<VolumeReport | null | undefined>(undefined);
  readonly channelMix = signal<Breakdown | null | undefined>(undefined);
  readonly funnel = signal<Funnel | null | undefined>(undefined);
  readonly slaBreaks = signal<Breakdown | null | undefined>(undefined);
  readonly moderation = signal<Breakdown | null | undefined>(undefined);
  readonly ttfr = signal<LatencyStats | null | undefined>(undefined);
  readonly ttr = signal<LatencyStats | null | undefined>(undefined);

  // --- Derived chart data ----------------------------------------------------
  /** Reports-by-status bar chart, built from the aggregate's per-status breakdown. */
  readonly statusChart = computed<ChartData<'bar'> | null>(() => {
    const s = this.stats();
    if (!s || s.reportsByStatus.length === 0) {
      return null;
    }
    const rows = [...s.reportsByStatus].sort((a, b) => b.count - a.count);
    return {
      labels: rows.map((r) => this.translate.instant('reports.statusValue.' + r.status)),
      datasets: [
        {
          label: this.translate.instant('reports.title'),
          data: rows.map((r) => r.count),
          backgroundColor: TF_CHART_PALETTE[0],
          borderRadius: 6,
          maxBarThickness: 38,
        },
      ],
    };
  });

  /** Channel-mix doughnut (APP/WEB/PWA/USSD/SMS). */
  readonly channelChart = computed<ChartData<'doughnut'> | null>(() =>
    this.doughnutFrom(this.channelMix(), (k) => this.channelLabel(k)),
  );

  /** SLA-breach-by-type doughnut (TTFR vs TTR). */
  readonly slaChart = computed<ChartData<'doughnut'> | null>(() =>
    this.doughnutFrom(this.slaBreaks(), (k) => k ?? '—'),
  );

  /** Moderation-actions-by-outcome bar. */
  readonly moderationChart = computed<ChartData<'bar'> | null>(() => {
    const b = this.moderation();
    if (!b || b.points.length === 0) {
      return null;
    }
    return {
      labels: b.points.map((p) => this.moderationLabel(p.key)),
      datasets: [
        {
          label: this.translate.instant('analytics.moderationActions'),
          data: b.points.map((p) => p.count),
          backgroundColor: b.points.map((_, i) => TF_CHART_PALETTE[i % TF_CHART_PALETTE.length]),
          borderRadius: 6,
          maxBarThickness: 38,
        },
      ],
    };
  });

  /** Verification-funnel bar (absolute step counts). */
  readonly funnelChart = computed<ChartData<'bar'> | null>(() => {
    const f = this.funnel();
    if (!f || f.steps.length === 0) {
      return null;
    }
    return {
      labels: f.steps.map((st) => this.funnelLabel(st.step)),
      datasets: [
        {
          label: this.translate.instant('analytics.funnel'),
          data: f.steps.map((st) => st.count),
          backgroundColor: TF_CHART_PALETTE[2],
          borderRadius: 6,
          maxBarThickness: 46,
        },
      ],
    };
  });

  // --- Legends (accessible numeric companions to the doughnuts) --------------
  readonly channelLegend = computed(() => this.legendFrom(this.channelMix(), (k) => this.channelLabel(k)));
  readonly slaLegend = computed(() => this.legendFrom(this.slaBreaks(), (k) => k ?? '—'));

  /** Whether the verification funnel reached the §3.3 ≥40% T3 headline (last step conversion). */
  readonly funnelConversion = computed<number | null>(() => {
    const f = this.funnel();
    if (!f || f.steps.length === 0) {
      return null;
    }
    return f.steps[f.steps.length - 1].conversionFromTop;
  });

  ngOnInit(): void {
    // KPI aggregate + the two list counts.
    this.bind(this.dashboard.stats(), this.stats);
    this.bind(this.dashboard.organisations(), this.organisationsCount);
    this.bind(this.dashboard.representatives(), this.representativesCount);

    // Analytics charts — each independent, each degrades to null on 404/403/error.
    this.bind(this.analytics.reportsVolume(), this.volume);
    this.bind(this.analytics.reportChannelMix(), this.channelMix);
    this.bind(this.analytics.verificationFunnel(), this.funnel);
    this.bind(this.analytics.slaBreaches(), this.slaBreaks);
    this.bind(this.analytics.moderationActions(), this.moderation);
    this.bind(this.analytics.ttfr(), this.ttfr);
    this.bind(this.analytics.ttr(), this.ttr);
  }

  /** Median (p50) TTFR, formatted human-readable (e.g. "12h", "3d"), or "—". */
  readonly ttfrP50 = computed(() => this.formatLatency(this.ttfr(), (s) => s.p50Seconds));
  /** p90 TTFR, formatted, or "—". */
  readonly ttfrP90 = computed(() => this.formatLatency(this.ttfr(), (s) => s.p90Seconds));
  /** Median (p50) TTR, formatted, or "—". */
  readonly ttrP50 = computed(() => this.formatLatency(this.ttr(), (s) => s.p50Seconds));
  /** p90 TTR, formatted, or "—". */
  readonly ttrP90 = computed(() => this.formatLatency(this.ttr(), (s) => s.p90Seconds));

  /** Formats a latency in seconds as a human "Xh"/"Yd" string, or a dash when no samples. */
  private formatLatency(
    stats: LatencyStats | null | undefined,
    pick: (s: LatencyStats) => number | null,
  ): string {
    if (!stats) {
      return '—';
    }
    const seconds = pick(stats);
    if (seconds === null || stats.sampleCount === 0) {
      return '—';
    }
    const hours = seconds / 3600;
    if (hours < 48) {
      return `${Math.round(hours)}h`;
    }
    return `${Math.round(hours / 24)}d`;
  }

  // --- helpers --------------------------------------------------------------

  /** Subscribes a degradable source into a signal, teardown-safe. */
  private bind<T>(source$: Observable<T>, target: WritableSignal<T | null | undefined>): void {
    source$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((v) => target.set(v));
  }

  /** Maps the aggregate's tri-state onto a single numeric card value. */
  private cardValue(pick: (s: AdminStats) => number): number | null | undefined {
    const s = this.stats();
    return s === undefined ? undefined : s === null ? null : pick(s);
  }

  /** Builds a doughnut dataset from a {@link Breakdown}, or null when empty. */
  private doughnutFrom(b: Breakdown | null | undefined, label: (k: string | null) => string): ChartData<'doughnut'> | null {
    if (!b || b.points.length === 0) {
      return null;
    }
    return {
      labels: b.points.map((p) => label(p.key)),
      datasets: [
        {
          data: b.points.map((p) => p.count),
          backgroundColor: b.points.map((_, i) => TF_CHART_PALETTE[i % TF_CHART_PALETTE.length]),
          borderWidth: 0,
          hoverOffset: 6,
        },
      ],
    };
  }

  /** Builds an accessible legend (label/colour/value) mirroring a doughnut's slices. */
  private legendFrom(b: Breakdown | null | undefined, label: (k: string | null) => string): LegendEntry[] {
    if (!b || b.points.length === 0) {
      return [];
    }
    return b.points.map((p, i) => ({
      label: label(p.key),
      color: TF_CHART_PALETTE[i % TF_CHART_PALETTE.length],
      value: p.count,
    }));
  }

  /** Localises a channel token, falling back to the raw key. */
  private channelLabel(key: string | null): string {
    return key ? this.translate.instant('analytics.channelValue.' + key) || key : '—';
  }

  /** Localises a moderation-outcome token, falling back to the raw key. */
  private moderationLabel(key: string | null): string {
    return key ? this.translate.instant('analytics.outcomeValue.' + key) || key : '—';
  }

  /** Localises a verification-funnel step token, falling back to the raw key. */
  private funnelLabel(key: string): string {
    return this.translate.instant('analytics.funnelStep.' + key) || key;
  }
}
