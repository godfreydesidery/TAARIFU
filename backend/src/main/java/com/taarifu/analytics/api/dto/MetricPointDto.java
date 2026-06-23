package com.taarifu.analytics.api.dto;

/**
 * One labelled count in a grouped analytics result (PRD Appendix C dashboards; M15).
 *
 * <p>Responsibility: the wire shape for a single bucket of any "count by X" dashboard — reports by
 * category/area, channel mix, verification-funnel step, SLA breaches by type, moderation actions by
 * outcome, engagement counts. Keeping one DTO for every grouped metric keeps the API surface uniform
 * and the Angular/Flutter chart code generic (DRY).</p>
 *
 * @param key   the bucket label — an enum name, an outcome code, or a public-id string (the area/category
 *              the client resolves to a display name via the geography/reporting reads); {@code null} for an
 *              unattributed bucket.
 * @param count the number of events in this bucket.
 */
public record MetricPointDto(String key, long count) {
}
