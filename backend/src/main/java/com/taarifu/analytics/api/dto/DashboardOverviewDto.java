package com.taarifu.analytics.api.dto;

import java.time.Instant;

/**
 * A single composed dashboard overview — the headline KPIs of the operational dashboard assembled into one
 * payload (PRD §3.3 KPIs; Appendix C; ADR-0020 §2/§3; M15).
 *
 * <p>Responsibility: the read model the admin console loads on dashboard open, so a low-bandwidth client
 * fetches the headline numbers in <b>one</b> call instead of firing the eight per-tile endpoints separately
 * (PRD §15 lean payloads). It composes the existing tile results — reports-volume total, TTFR/TTR latency
 * distributions, the T0→T3 verification funnel, SLA-breach counts, channel mix, and moderation actions —
 * over a single window/area scope. It is returned by the published {@link com.taarifu.analytics.api.AnalyticsQueryApi}
 * read port and consumed by the admin module (ADR-0013 §1) — admin never touches analytics' internals.</p>
 *
 * <p><b>No PII (Appendix E.4):</b> every nested DTO is counts/percentiles over the PII-free
 * {@code analytics_event} facts; nothing here resolves to a person, precise location, or any free-text body.</p>
 *
 * @param from              inclusive window start applied (UTC).
 * @param to                exclusive window end applied (UTC).
 * @param reportsVolumeTotal total {@code REPORT_FILED} count in the window/scope (the headline number).
 * @param ttfr              time-to-first-response distribution (p50/p90), labelled {@code TTFR}.
 * @param ttr               time-to-resolution distribution (p50/p90), labelled {@code TTR}.
 * @param verificationFunnel the T0→T3 verification funnel with step conversion.
 * @param slaBreaches       SLA-breach counts split by breach type (TTFR/TTR).
 * @param channelMix        channel mix for filed reports (% USSD/SMS/app/web — feature-phone reach).
 * @param moderationActions moderation actions split by outcome.
 */
public record DashboardOverviewDto(
        Instant from,
        Instant to,
        long reportsVolumeTotal,
        LatencyStatsDto ttfr,
        LatencyStatsDto ttr,
        FunnelDto verificationFunnel,
        BreakdownDto slaBreaches,
        BreakdownDto channelMix,
        BreakdownDto moderationActions
) {
}
