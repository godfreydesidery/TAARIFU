package com.taarifu.analytics.api.dto;

import java.time.Instant;

/**
 * A latency-distribution dashboard payload — TTFR, TTR, or answer-latency (PRD §3.3 Reporting/Resolution/
 * Responsiveness; Appendix C; Appendix E.2 p50/p90; M15).
 *
 * <p>Responsibility: the read model for the responsiveness tiles. It reports the headline <b>median (p50)</b>
 * and <b>p90</b> latency the KPIs are stated in ("median TTFR &lt; 48h", "median TTR &lt; 30 days"), the
 * sample size behind them (so a small-n cell can be suppressed for k-anonymity, Appendix E.4), and the
 * mean for context. Latencies are seconds; the client formats to hours/days.</p>
 *
 * @param metric      a label for which latency this is (e.g. {@code "TTFR"}, {@code "TTR"}, {@code "ANSWER_LATENCY"}).
 * @param from        inclusive window start applied (UTC).
 * @param to          exclusive window end applied (UTC).
 * @param sampleCount number of measured events behind the statistics.
 * @param p50Seconds  median latency in seconds, or {@code null} when there are no samples.
 * @param p90Seconds  p90 latency in seconds, or {@code null} when there are no samples.
 * @param avgSeconds  mean latency in seconds, or {@code null} when there are no samples.
 */
public record LatencyStatsDto(
        String metric,
        Instant from,
        Instant to,
        long sampleCount,
        Double p50Seconds,
        Double p90Seconds,
        Double avgSeconds
) {
}
