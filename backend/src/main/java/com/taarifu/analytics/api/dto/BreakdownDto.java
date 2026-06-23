package com.taarifu.analytics.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A generic single-dimension breakdown payload — channel mix, SLA breaches by type, moderation actions
 * by outcome, engagement counts (PRD Appendix C dashboards; M15).
 *
 * <p>Responsibility: the read model for any "count by one dimension" tile, with the window echoed and a
 * total so the client can render proportions (e.g. "% sessions via USSD", §3.3 Reach). Used for the
 * channel-mix, SLA-breach, moderation-action, and engagement endpoints — one shape, many tiles (DRY).</p>
 *
 * @param dimension a label for what was grouped (e.g. {@code "CHANNEL"}, {@code "BREACH_TYPE"}, {@code "OUTCOME"}).
 * @param from      inclusive window start applied (UTC).
 * @param to        exclusive window end applied (UTC).
 * @param total     sum of all bucket counts (denominator for proportions).
 * @param points    the buckets (descending by count).
 */
public record BreakdownDto(
        String dimension,
        Instant from,
        Instant to,
        long total,
        List<MetricPointDto> points
) {
}
