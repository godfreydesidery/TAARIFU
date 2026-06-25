import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiClient } from '../../core/api/api-client.service';
import { Breakdown, Funnel, LatencyStats, VolumeReport } from './analytics.models';

/**
 * Read-only provider for the dashboard analytics charts (`GET /admin/analytics/*`; PRD §3.3 KPIs, M15).
 *
 * <p>Responsibility: fetch the operational-dashboard aggregates — reports volume, channel mix, the T0→T3
 * verification funnel, SLA breaches by type, moderation actions by outcome, engagement counts, and
 * TTFR/TTR latency — and unwrap each via {@link ApiClient}. EVERY call DEGRADES GRACEFULLY: any error
 * (including a `404` when an endpoint isn't deployed in this environment, or a `403` for a role without
 * dashboard scope) maps to `null`, so the dashboard renders the tiles it CAN and shows an empty state for
 * the rest rather than failing the whole page (a project resilience requirement, PRD §15). All payloads are
 * aggregate counts only — no PII (Appendix E.4).</p>
 *
 * <p>The optional `from`/`to` window is left to the server defaults (last 30 days) for the headline
 * dashboard; a future date-range control can thread params through {@link windowParams}.</p>
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly api = inject(ApiClient);

  /** Reports volume (total + by category + by area), or `null` if unavailable. */
  reportsVolume(from?: string, to?: string): Observable<VolumeReport | null> {
    return this.degradable(this.api.get<VolumeReport>('/admin/analytics/reports/volume', this.windowParams(from, to)));
  }

  /** Channel mix for filed reports (APP/WEB/PWA/USSD/SMS), or `null` if unavailable. */
  reportChannelMix(from?: string, to?: string): Observable<Breakdown | null> {
    return this.degradable(this.api.get<Breakdown>('/admin/analytics/reports/channel-mix', this.windowParams(from, to)));
  }

  /** SLA-breach counts split by breach type (TTFR vs TTR), or `null` if unavailable. */
  slaBreaches(from?: string, to?: string): Observable<Breakdown | null> {
    return this.degradable(this.api.get<Breakdown>('/admin/analytics/reports/sla-breaches', this.windowParams(from, to)));
  }

  /** The T0→T3 verification funnel with step conversion, or `null` if unavailable. */
  verificationFunnel(from?: string, to?: string): Observable<Funnel | null> {
    return this.degradable(this.api.get<Funnel>('/admin/analytics/verification/funnel', this.windowParams(from, to)));
  }

  /** Moderation actions by outcome, or `null` if unavailable. */
  moderationActions(from?: string, to?: string): Observable<Breakdown | null> {
    return this.degradable(this.api.get<Breakdown>('/admin/analytics/moderation/actions', this.windowParams(from, to)));
  }

  /** Engagement counts (signatures, poll responses, Q&A, ratings, follows), or `null` if unavailable. */
  engagementCounts(from?: string, to?: string): Observable<Breakdown | null> {
    return this.degradable(this.api.get<Breakdown>('/admin/analytics/engagement/counts', this.windowParams(from, to)));
  }

  /** Time-to-first-response distribution (p50/p90), or `null` if unavailable. */
  ttfr(from?: string, to?: string): Observable<LatencyStats | null> {
    return this.degradable(this.api.get<LatencyStats>('/admin/analytics/reports/ttfr', this.windowParams(from, to)));
  }

  /** Time-to-resolution distribution (p50/p90), or `null` if unavailable. */
  ttr(from?: string, to?: string): Observable<LatencyStats | null> {
    return this.degradable(this.api.get<LatencyStats>('/admin/analytics/reports/ttr', this.windowParams(from, to)));
  }

  /** Wraps a source so ANY error (404/403/network) becomes `null` — the tile degrades, the page survives. */
  private degradable<T>(source$: Observable<T>): Observable<T | null> {
    return source$.pipe(catchError(() => of(null)));
  }

  /** Builds the optional ISO-8601 window params, omitting undefined ends (server applies its defaults). */
  private windowParams(from?: string, to?: string): Record<string, string | undefined> {
    return { from, to };
  }
}
