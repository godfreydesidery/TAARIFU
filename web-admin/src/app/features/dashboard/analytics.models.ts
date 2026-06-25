/**
 * TypeScript mirrors of the backend analytics-dashboard DTOs
 * (`com.taarifu.analytics.api.dto.*`, served under `GET /admin/analytics/*`; PRD §3.3 KPIs, M15).
 *
 * <p>These shape the elegant dashboard's charts: reports volume, channel mix, the T0→T3 verification
 * funnel, SLA breaches by type, moderation actions by outcome, engagement counts, and TTFR/TTR latency
 * stats. Every payload is aggregate counts/percentiles only — no PII, no precise location, no report body
 * (Appendix E.4) — so it is safe to render in the operator console. The client branches on the stable
 * bucket `key`/`dimension`/`step` strings, never on a localised label.</p>
 */

/** One labelled count in a grouped result (mirrors `MetricPointDto`). */
export interface MetricPoint {
  /** Bucket label — an enum name, outcome code, or public-id string; `null` for an unattributed bucket. */
  key: string | null;
  /** Number of events in this bucket. */
  count: number;
}

/** Reports-volume payload (mirrors `VolumeReportDto`). */
export interface VolumeReport {
  /** Inclusive window start applied (ISO-8601 UTC). */
  from: string;
  /** Exclusive window end applied (ISO-8601 UTC). */
  to: string;
  /** Total `REPORT_FILED` events in the window. */
  total: number;
  /** Reports per issue-category (descending). */
  byCategory: MetricPoint[];
  /** Reports per geographic area (descending) — the heatmap feed. */
  byArea: MetricPoint[];
}

/** Single-dimension breakdown payload (mirrors `BreakdownDto`) — channel mix, SLA, moderation, engagement. */
export interface Breakdown {
  /** What was grouped (e.g. `CHANNEL`, `BREACH_TYPE`, `OUTCOME`, `ENGAGEMENT_TYPE`). */
  dimension: string;
  /** Inclusive window start applied (ISO-8601 UTC). */
  from: string;
  /** Exclusive window end applied (ISO-8601 UTC). */
  to: string;
  /** Sum of all bucket counts (denominator for proportions). */
  total: number;
  /** The buckets (descending by count). */
  points: MetricPoint[];
}

/** One verification-funnel step (mirrors `FunnelDto.Step`). */
export interface FunnelStep {
  /** Step label (e.g. `ACCOUNT_SIGNED_UP`). */
  step: string;
  /** Absolute count at this step in the window. */
  count: number;
  /** Fraction in `[0,1]` of the first step's count that reached this step. */
  conversionFromTop: number;
}

/** Verification-funnel payload (mirrors `FunnelDto`). */
export interface Funnel {
  /** Funnel label (e.g. `VERIFICATION_T0_T3`). */
  name: string;
  /** Inclusive window start applied (ISO-8601 UTC). */
  from: string;
  /** Exclusive window end applied (ISO-8601 UTC). */
  to: string;
  /** Ordered steps, top → bottom. */
  steps: FunnelStep[];
}

/** Latency-distribution payload (mirrors `LatencyStatsDto`) — TTFR/TTR p50/p90 in seconds. */
export interface LatencyStats {
  /** Which latency this is (e.g. `TTFR`, `TTR`). */
  metric: string;
  /** Inclusive window start applied (ISO-8601 UTC). */
  from: string;
  /** Exclusive window end applied (ISO-8601 UTC). */
  to: string;
  /** Number of measured events behind the statistics. */
  sampleCount: number;
  /** Median latency in seconds, or `null` when there are no samples. */
  p50Seconds: number | null;
  /** p90 latency in seconds, or `null` when there are no samples. */
  p90Seconds: number | null;
  /** Mean latency in seconds, or `null` when there are no samples. */
  avgSeconds: number | null;
}
