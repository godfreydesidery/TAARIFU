package com.taarifu.search.api.dto;

/**
 * The current discovery-index status read ({@code GET /search/admin/reindex/status}, ADR-0017 backfill
 * follow-up "a count/last-run status read").
 *
 * <p>Responsibility: a lightweight, PII-free snapshot for the admin console — the live index size right now and a
 * summary of the most recent reindex run (or {@code null} {@code lastRun} if none has run since boot). The
 * operator uses {@code indexSize} to confirm the index is populated and {@code registeredSources} to see how many
 * owning modules have shipped a backfill source (an owner without one contributes nothing — CENTRAL NEEDS).</p>
 *
 * <p>The last-run summary is held in memory and resets on restart (it is an operational hint, not an audit record;
 * the durable trace is the {@code AnalyticsEvent}/audit emitted by the run, not this read).</p>
 *
 * @param indexSize         the live {@code search_document} row count right now ({@code >= 0}).
 * @param registeredSources the number of {@link com.taarifu.search.domain.port.SearchBackfillSource} adapters
 *                          currently wired (0 until owners ship them).
 * @param lastRun           the most recent run's result, or {@code null} if none has run since boot.
 */
public record SearchReindexStatus(
        long indexSize,
        int registeredSources,
        SearchReindexResult lastRun
) {
}
