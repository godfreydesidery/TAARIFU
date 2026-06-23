package com.taarifu.analytics.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The "reports volume" dashboard payload — total reports filed in a window plus breakdowns by category
 * and area (PRD §3.3 Reporting; Appendix C "reports volume by area/category/time"; M15).
 *
 * <p>Responsibility: the read model for US-15.1's reports-volume tile. The {@code from}/{@code to} echo the
 * resolved window so the client can label the chart, {@code total} is the headline number, and the two
 * breakdown lists feed the category and geo-heatmap views. All counts are aggregated over the
 * {@code analytics_event} table (not a live cross-module read — ADR-0013).</p>
 *
 * @param from           inclusive window start applied (UTC).
 * @param to             exclusive window end applied (UTC).
 * @param total          total {@code REPORT_FILED} events in the window (after any area/category filter).
 * @param byCategory     count of reports per issue-category id (descending), each {@link MetricPointDto}.
 * @param byArea         count of reports per geographic-area id (descending) — the heatmap feed.
 */
public record VolumeReportDto(
        Instant from,
        Instant to,
        long total,
        List<MetricPointDto> byCategory,
        List<MetricPointDto> byArea
) {
}
