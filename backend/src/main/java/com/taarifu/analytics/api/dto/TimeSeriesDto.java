package com.taarifu.analytics.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A time-series (trend) dashboard payload — an ordered list of per-bucket counts (PRD §3.3 trends;
 * Appendix C "reports volume over time / SLA-breach trend"; ADR-0020 §1; M15).
 *
 * <p>Responsibility: the read model for any "count per time bucket" tile — reports volume over time and
 * the SLA-breach trend. The window and the {@link TimeBucket} granularity are echoed so the client can
 * label the axis, and {@code points} preserves chronological order (oldest → newest) so the Angular/Flutter
 * chart renders without re-sorting. One shape powers every trend tile (DRY), mirroring {@link BreakdownDto}
 * for single-dimension counts.</p>
 *
 * <p><b>No PII (Appendix E.4):</b> every field is a label, an {@link Instant}, or an integer count over the
 * already-PII-free {@code analytics_event} facts; nothing here resolves to a person or precise location.</p>
 *
 * @param metric a label for what is being trended (e.g. {@code "REPORT_FILED"}, {@code "SLA_BREACH"}).
 * @param bucket the time-bucket granularity applied.
 * @param from   inclusive window start applied (UTC).
 * @param to     exclusive window end applied (UTC).
 * @param points the per-bucket counts, chronologically ordered (oldest → newest).
 */
public record TimeSeriesDto(
        String metric,
        TimeBucket bucket,
        Instant from,
        Instant to,
        List<Point> points
) {

    /**
     * One point in the series.
     *
     * @param bucketStart the inclusive start instant of the bucket (the truncated {@code occurred_at}, UTC).
     * @param count       the number of events that fell in this bucket.
     */
    public record Point(Instant bucketStart, long count) {
    }
}
