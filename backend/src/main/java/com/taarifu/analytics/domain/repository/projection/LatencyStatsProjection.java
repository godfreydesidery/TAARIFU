package com.taarifu.analytics.domain.repository.projection;

/**
 * Latency distribution statistics for a latency-bearing event type — powers the TTFR/TTR and
 * answer-latency dashboards (PRD §3.3 Reporting/Resolution/Responsiveness; Appendix C; Appendix E.2).
 *
 * <p>Responsibility: the aggregate read model for "how fast" — the count of measured events, the
 * <b>median (p50)</b> and <b>p90</b> latency in seconds, plus average for context. p50/p90 are computed
 * in SQL via PostgreSQL {@code percentile_cont}, the correct way to surface "median TTFR &lt; 48h" and
 * "median TTR &lt; 30 days" (§3.3) without pulling rows into the JVM.</p>
 *
 * <p>WHY percentiles (not just average): civic responsiveness is reported as medians/percentiles
 * (§3.3, Appendix E.2 p50/p90) — an average is skewed by a few very slow cases, so the median is the
 * honest headline number a council is held to.</p>
 */
public interface LatencyStatsProjection {

    /** @return the number of events that carried a latency measure in the window. */
    long getSampleCount();

    /** @return the median (p50) latency in seconds, or {@code null} when there are no samples. */
    Double getP50Seconds();

    /** @return the p90 latency in seconds, or {@code null} when there are no samples. */
    Double getP90Seconds();

    /** @return the mean latency in seconds, or {@code null} when there are no samples. */
    Double getAvgSeconds();
}
